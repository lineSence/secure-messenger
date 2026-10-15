//! Сервер протокола [`sm_core::relay`]. Пока поток на соединение; событийный I/O не реализован.
//!
//! Только loopback-адрес (`127.0.0.1`). Два режима приёма:
//! - кадры прямо по TCP — шаг 2, снаружи SSH-туннель (решение владельца 2026-10-09);
//! - HTTP/1.1 от nginx (ADR-90, 0.0.2-alpha): тело запроса — один кадр, тело ответа — один кадр
//!   ([`crate::http`]).
//!
//! `WAIT` (ADR-82): при пустой очереди поток соединения ждёт блоб до [`Config::wait`] —
//! `SEND` в ту же очередь будит его сразу.

use std::collections::HashMap;
use std::io::{self, IoSlice, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use sm_core::relay::{
    Command, ID_LEN, MAX_RESPONSE_DATA, REQUEST_HEADER, RequestHeader, Status, VERSION,
    parse_request_header,
};

use sm_core::node::{ROOT_KEYS, ROOT_PUBLIC_LEN};

use crate::budget::{Budget, Lease};
use crate::pass::GuestKey;
use crate::store::{Limits, PassId, QueueId, Redeem, Store, StoreError};
use crate::{http, pass};

/// Самое долгое ожидание `WAIT`: меньше 30 с `proxy_read_timeout` nginx (ADR-90).
pub const WAIT_MAX: Duration = Duration::from_secs(25);

const CONNECTION_STACK: usize = 2 << 20;
// Peak input Vec growth, HTTP buffers, WAIT links and thread/control metadata.
const CONNECTION_MEMORY: usize = CONNECTION_STACK + 4 * sm_core::relay::MAX_BLOB + (64 << 10);

pub(crate) fn runtime_memory(max_connections: usize) -> Option<usize> {
    max_connections
        .checked_mul(CONNECTION_MEMORY)?
        .checked_add(8 << 20)
}

/// Настройки сервера.
#[derive(Debug, Clone, Copy)]
pub struct Config {
    /// Адрес; только loopback.
    pub listen: SocketAddr,
    /// Пределы хранилища.
    pub limits: Limits,
    /// Одновременных соединений; лишние закрываются сразу.
    pub max_connections: usize,
    /// Срок на весь кадр запроса (с начала ожидания) и на отправку ответа; потом соединение закрывается.
    pub idle_timeout: Duration,
    /// Сколько `WAIT` ждёт блоб при пустой очереди.
    pub wait: Duration,
    /// Приём HTTP/1.1 от nginx вместо кадров прямо по TCP.
    pub http: bool,
    /// Открытые корневые ключи владельца для пропусков на `NEW` (ADR-136).
    pub roots: &'static [[u8; ROOT_PUBLIC_LEN]],
    /// Отозванные пропуска (ADR-136).
    pub revoked: &'static [PassId],
    /// Постоянный ключ узла для гостевых пропусков (ADR-146); без него `TICKET` — `Unavailable`.
    pub guest_key: Option<&'static GuestKey>,
}

impl Config {
    /// Настройки по умолчанию из `relay.md` для адреса `listen`.
    #[must_use]
    pub fn new(listen: SocketAddr) -> Self {
        Self {
            listen,
            limits: Limits::default(),
            max_connections: 64,
            idle_timeout: Duration::from_secs(30),
            wait: WAIT_MAX,
            http: false,
            roots: ROOT_KEYS,
            revoked: &[],
            guest_key: None,
        }
    }
}

/// Запущенный сервер. [`Server::stop`] останавливает приём соединений;
/// очереди живут, пока жив процесс (или последнее соединение после `stop`).
pub struct Server {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    accept: Option<JoinHandle<()>>,
    maintenance: Option<JoinHandle<()>>,
    shared: Arc<Shared>,
}

impl std::fmt::Debug for Server {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Server").finish_non_exhaustive()
    }
}

impl Server {
    /// Запускает сервер с пустым хранилищем.
    ///
    /// # Errors
    /// [`io::ErrorKind::InvalidInput`] — адрес не loopback; ошибки `bind`.
    pub fn start(config: Config) -> io::Result<Self> {
        if !config.listen.ip().is_loopback() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "только loopback-адрес (шаг 2)",
            ));
        }
        crate::settings::validate(&config)?;
        let budget = Budget::new(config.limits.memory_bytes);
        let runtime = runtime_memory(config.max_connections)
            .and_then(|n| budget.reserve(n))
            .ok_or(io::ErrorKind::InvalidInput)?;
        let listener = TcpListener::bind(config.listen)?;
        let addr = listener.local_addr()?;
        let stop = Arc::new(AtomicBool::new(false));
        let shared = Arc::new(Shared {
            store: Mutex::new(Store::with_budget(config.limits, budget)),
            _runtime: runtime,
            arrived: crate::notify::Registry::default(),
            issuing: Mutex::new(()),
            connections: Mutex::new(HashMap::new()),
            finished: Condvar::new(),
            waiters: AtomicUsize::new(0),
        });
        let stop2 = Arc::clone(&stop);
        let owned_shared = Arc::clone(&shared);
        let worker_shared = Arc::clone(&shared);
        let worker_stop = Arc::clone(&stop);
        let maintenance = std::thread::Builder::new()
            .name("reclaim".into())
            .spawn(move || maintain_loop(&worker_shared, &worker_stop))?;
        let accept = match std::thread::Builder::new()
            .name("accept".into())
            .spawn(move || accept_loop(&listener, &shared, &stop2, config))
        {
            Ok(h) => h,
            Err(e) => {
                stop.store(true, Ordering::SeqCst);
                owned_shared.finished.notify_all();
                let _ = maintenance.join();
                return Err(e);
            }
        };
        Ok(Self {
            addr,
            stop,
            accept: Some(accept),
            maintenance: Some(maintenance),
            shared: owned_shared,
        })
    }

    /// Фактический адрес (если в настройках был порт 0).
    #[must_use]
    pub const fn addr(&self) -> SocketAddr {
        self.addr
    }

    /// Ждёт остановки (для бинарника: вечно).
    pub fn wait(mut self) {
        if let Some(h) = self.accept.take() {
            let _ = h.join();
        }
    }

    /// Останавливает приём соединений и ждёт поток приёма.
    pub fn stop(mut self) {
        self.stop.store(true, Ordering::SeqCst);
        self.shared.finished.notify_all();
        // Разбудить accept(): он проверит флаг и выйдет.
        let _ = TcpStream::connect(self.addr);
        if let Some(h) = self.accept.take() {
            let _ = h.join();
        }
        if let Some(h) = self.maintenance.take() {
            let _ = h.join();
        }
        let mut live = self
            .shared
            .connections
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        for conn in live.values() {
            let _ = conn.shutdown(Shutdown::Both);
        }
        let deadline = deadline_in(Duration::from_secs(5));
        while !live.is_empty() {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            live = self
                .shared
                .finished
                .wait_timeout(live, left)
                .map_or_else(|e| e.into_inner().0, |(g, _)| g);
        }
    }
}

/// Очереди и сигнал «в очередь пришёл блоб» для ждущих `WAIT`.
struct Shared {
    store: Mutex<Store>,
    _runtime: Lease,
    arrived: crate::notify::Registry,
    issuing: Mutex<()>,
    connections: Mutex<HashMap<usize, TcpStream>>,
    finished: Condvar,
    /// WAIT не занимает все соединения: остаётся резерв для SEND/ACK/NEW/PING.
    waiters: AtomicUsize,
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, Store> {
        self.store.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

fn maintain_loop(shared: &Shared, stop: &AtomicBool) {
    while !stop.load(Ordering::SeqCst) {
        let pause = {
            let now = now_hour();
            let mut store = shared.lock();
            store.maintain(now, 256);
            if store.maintenance_pending(now) {
                Duration::from_millis(10)
            } else {
                Duration::from_secs(1)
            }
        };
        let live = shared
            .connections
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        if stop.load(Ordering::SeqCst) {
            return;
        }
        let _waited = shared.finished.wait_timeout(live, pause);
    }
}

fn accept_loop(listener: &TcpListener, shared: &Arc<Shared>, stop: &AtomicBool, config: Config) {
    let active = Arc::new(AtomicUsize::new(0));
    let mut next_id = 0usize;
    for conn in listener.incoming() {
        if stop.load(Ordering::SeqCst) {
            return;
        }
        let Ok(conn) = conn else { continue };
        if active.load(Ordering::SeqCst) >= config.max_connections {
            continue; // соединение закрывается при выходе из области видимости
        }
        let Ok(control) = conn.try_clone() else {
            continue;
        };
        let id = next_id;
        next_id = next_id.wrapping_add(1);
        shared
            .connections
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(id, control);
        active.fetch_add(1, Ordering::SeqCst);
        let shared = Arc::clone(shared);
        let guard = Active {
            count: Arc::clone(&active),
            shared: Arc::clone(&shared),
            id,
        };
        let spawned = std::thread::Builder::new()
            .name("conn".into())
            .stack_size(CONNECTION_STACK)
            .spawn(move || {
                let _guard = guard;
                let _ = if config.http {
                    serve_http(conn, &shared, config)
                } else {
                    serve(conn, &shared, config)
                };
            });
        drop(spawned);
    }
}

/// Счётчик активных соединений: уменьшается при завершении потока (в том числе при панике).
struct Active {
    count: Arc<AtomicUsize>,
    shared: Arc<Shared>,
    id: usize,
}

impl Drop for Active {
    fn drop(&mut self) {
        self.count.fetch_sub(1, Ordering::SeqCst);
        self.shared
            .connections
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .remove(&self.id);
        self.shared.finished.notify_all();
    }
}

/// Срок через `t` от текущего момента (при переполнении — сейчас, то есть срок уже вышел).
fn deadline_in(t: Duration) -> Instant {
    Instant::now().checked_add(t).unwrap_or_else(Instant::now)
}

/// Остаток времени до `deadline`; ноль — срок вышел.
fn remaining(deadline: Instant) -> io::Result<Duration> {
    let left = deadline.saturating_duration_since(Instant::now());
    if left.is_zero() {
        return Err(io::ErrorKind::TimedOut.into());
    }
    Ok(left)
}

/// Читает `buf` целиком до `deadline`: медленный клиент (по байту раз в 29 с) не продлевает срок.
fn read_by(conn: &mut TcpStream, mut buf: &mut [u8], deadline: Instant) -> io::Result<()> {
    while !buf.is_empty() {
        conn.set_read_timeout(Some(remaining(deadline)?))?;
        match conn.read(buf) {
            Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(n) => buf = buf.get_mut(n..).ok_or(io::ErrorKind::InvalidData)?,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// Часть тела запроса, читаемая за раз.
const BODY_CHUNK: usize = 4096;

/// Читает тело длиной `len` до `deadline`, кусками по [`BODY_CHUNK`]: клиент, заявивший большую
/// длину и замолчавший, занимает не больше одного куска памяти.
fn read_body(conn: &mut TcpStream, len: usize, deadline: Instant) -> io::Result<Vec<u8>> {
    let mut body = Vec::new();
    read_body_into(conn, &mut body, len, deadline)?;
    Ok(body)
}

fn read_body_into(
    conn: &mut TcpStream,
    body: &mut Vec<u8>,
    len: usize,
    deadline: Instant,
) -> io::Result<()> {
    let mut chunk = [0; BODY_CHUNK];
    while body.len() < len {
        let n = BODY_CHUNK.min(len.saturating_sub(body.len()));
        let part = chunk.get_mut(..n).ok_or(io::ErrorKind::InvalidData)?;
        read_by(conn, part, deadline)?;
        body.extend_from_slice(part);
    }
    Ok(())
}

/// Пишет `buf` целиком до `deadline`.
fn write_by(conn: &mut TcpStream, mut buf: &[u8], deadline: Instant) -> io::Result<()> {
    while !buf.is_empty() {
        conn.set_write_timeout(Some(remaining(deadline)?))?;
        match conn.write(buf) {
            Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
            Ok(n) => buf = buf.get(n..).ok_or(io::ErrorKind::InvalidData)?,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// Обслуживает соединение. Весь кадр (заголовок + данные) должен прийти за `timeout`
/// с начала ожидания, ответ — уйти за `timeout`: медленные соединения не держат слоты.
fn serve(mut conn: TcpStream, shared: &Shared, config: Config) -> io::Result<()> {
    let timeout = config.idle_timeout;
    conn.set_nodelay(true)?;
    loop {
        let deadline = deadline_in(timeout);
        let mut head = [0; REQUEST_HEADER];
        read_by(&mut conn, &mut head, deadline)?;
        let Ok(req) = parse_request_header(&head) else {
            // Неверный заголовок: ответить и закрыть — дальше поток байт не синхронизирован.
            let _ = write_reply(
                &mut conn,
                Status::BadRequest,
                &ReplyData::Plain(Vec::new()),
                None,
                timeout,
            );
            return Ok(());
        };
        // Длина уже проверена по пределам команды (не больше MAX_BLOB); память растёт по мере
        // прихода байт, а не по заявленной длине (ревью v0.0.1-alpha, low).
        let body = read_body(&mut conn, req.len, deadline)?;
        let (status, data) = handle(shared, &req, body, &config, &conn);
        write_reply(&mut conn, status, &data, None, timeout)?;
        if close_reply(&req, status) {
            return Ok(());
        }
    }
}

/// Что пришло вместо заголовка HTTP.
enum Got {
    /// Соединение закрыто до первого байта запроса (nginx закрыл простаивающее).
    Eof,
    /// Заголовок длиннее [`http::MAX_HEAD`].
    TooLong,
    /// Байты заголовка без завершающей пустой строки.
    Head(Vec<u8>),
}

/// Читает заголовок HTTP до `deadline`. Лишние прочитанные байты (начало тела или следующего
/// запроса) остаются в `pending`.
fn read_head(conn: &mut TcpStream, pending: &mut Vec<u8>, deadline: Instant) -> io::Result<Got> {
    let mut buf = [0; 1024];
    loop {
        if let Some(end) = http::head_end(pending) {
            let full = end.saturating_add(http::HEAD_END.len());
            if full > http::MAX_HEAD {
                return Ok(Got::TooLong);
            }
            let mut head: Vec<u8> = pending.drain(..full).collect();
            head.truncate(end);
            return Ok(Got::Head(head));
        }
        if pending.len() >= http::MAX_HEAD {
            return Ok(Got::TooLong);
        }
        conn.set_read_timeout(Some(remaining(deadline)?))?;
        match conn.read(&mut buf) {
            Ok(0) if pending.is_empty() => return Ok(Got::Eof),
            Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(n) => pending.extend_from_slice(buf.get(..n).ok_or(io::ErrorKind::InvalidData)?),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
}

/// Берёт `n` байт: сначала из `pending`, остальное — из соединения до `deadline`.
fn take(
    conn: &mut TcpStream,
    pending: &mut Vec<u8>,
    n: usize,
    deadline: Instant,
) -> io::Result<Vec<u8>> {
    if pending.is_empty() {
        return read_body(conn, n, deadline);
    }
    let have = pending.len().min(n);
    let mut out: Vec<u8> = pending.drain(..have).collect();
    if out.len() < n {
        read_body_into(conn, &mut out, n, deadline)?;
    }
    Ok(out)
}

/// Отвечает `400` и закрывает соединение.
fn bad_request(conn: &mut TcpStream, timeout: Duration) -> io::Result<()> {
    write_by(conn, http::BAD_REQUEST, deadline_in(timeout))
}

/// Обслуживает соединение от nginx (ADR-90): запрос — `POST / HTTP/1.1` с одним кадром в теле.
/// Сроки — как у [`serve`]: заголовок и тело должны прийти за `idle_timeout` с начала ожидания.
/// Соединение переиспользуется (keepalive nginx к узлу), пока клиент не попросит закрыть.
fn serve_http(mut conn: TcpStream, shared: &Shared, config: Config) -> io::Result<()> {
    let timeout = config.idle_timeout;
    conn.set_nodelay(true)?;
    let mut pending = Vec::new();
    loop {
        let deadline = deadline_in(timeout);
        let head = match read_head(&mut conn, &mut pending, deadline)? {
            Got::Eof => return Ok(()),
            Got::TooLong => return bad_request(&mut conn, timeout),
            Got::Head(h) => h,
        };
        let Ok(head) = http::parse_head(&head) else {
            return bad_request(&mut conn, timeout);
        };
        if head.content_length < REQUEST_HEADER {
            return bad_request(&mut conn, timeout);
        }
        let frame = take(&mut conn, &mut pending, REQUEST_HEADER, deadline)?;
        let Ok(frame) = <[u8; REQUEST_HEADER]>::try_from(frame.as_slice()) else {
            return bad_request(&mut conn, timeout);
        };
        // Длина тела должна совпасть с кадром: один кадр на запрос, без хвостов.
        let req = match parse_request_header(&frame) {
            Ok(req) if REQUEST_HEADER.checked_add(req.len) == Some(head.content_length) => req,
            _ => return bad_request(&mut conn, timeout),
        };
        let body = take(&mut conn, &mut pending, req.len, deadline)?;
        let (status, data) = handle(shared, &req, body, &config, &conn);
        let close = head.close || close_reply(&req, status);
        write_reply(&mut conn, status, &data, Some(close), timeout)?;
        if close {
            return Ok(());
        }
    }
}

/// Отклонённый WAIT не оставляет простаивающее соединение, которое съело бы резерв.
fn close_reply(req: &RequestHeader, status: Status) -> bool {
    status == Status::BadRequest || (req.cmd == Command::Wait && status == Status::Unavailable)
}

enum ReplyData {
    Plain(Vec<u8>),
    Blob {
        prefix: [u8; 9],
        bytes: Arc<crate::store::Payload>,
    },
}
impl From<Vec<u8>> for ReplyData {
    fn from(v: Vec<u8>) -> Self {
        Self::Plain(v)
    }
}
impl ReplyData {
    fn parts(&self) -> (&[u8], &[u8]) {
        match self {
            Self::Plain(v) => (v, &[]),
            Self::Blob { prefix, bytes } => (prefix, bytes),
        }
    }
}
type Reply = (Status, ReplyData);
fn plain(status: Status, data: Vec<u8>) -> Reply {
    (status, data.into())
}

fn write_reply(
    conn: &mut TcpStream,
    status: Status,
    data: &ReplyData,
    http_close: Option<bool>,
    timeout: Duration,
) -> io::Result<()> {
    let (prefix, blob) = data.parts();
    let len = prefix
        .len()
        .checked_add(blob.len())
        .filter(|&n| n <= MAX_RESPONSE_DATA)
        .ok_or(io::ErrorKind::InvalidData)?;
    let wire_len = u32::try_from(len).map_err(|_| io::ErrorKind::InvalidData)?;
    let mut frame = [0u8; 6];
    if let Some(first) = frame.get_mut(0) {
        *first = VERSION;
    }
    if let Some(code) = frame.get_mut(1) {
        *code = status.code();
    }
    frame
        .get_mut(2..)
        .ok_or(io::ErrorKind::InvalidData)?
        .copy_from_slice(&wire_len.to_be_bytes());
    let http_head = http_close.map_or_else(Vec::new, |close| {
        http::ok_head(len.saturating_add(6), close)
    });
    let mut chunks = [
        IoSlice::new(&http_head),
        IoSlice::new(&frame),
        IoSlice::new(prefix),
        IoSlice::new(blob),
    ];
    let mut chunks = chunks.as_mut_slice();
    let deadline = deadline_in(timeout);
    while !chunks.is_empty() {
        conn.set_write_timeout(Some(remaining(deadline)?))?;
        match conn.write_vectored(chunks) {
            Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
            Ok(n) => IoSlice::advance_slices(&mut chunks, n),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// Текущий час с начала эпохи Unix (точность хранения времени — до часа).
fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

fn now_hour() -> u64 {
    now_secs() / 3600
}

fn store_status(e: StoreError) -> Status {
    match e {
        StoreError::NoQueue => Status::NoQueue,
        StoreError::TooBig => Status::TooBig,
        StoreError::Quota | StoreError::RateLimited => Status::Quota,
        StoreError::Collision | StoreError::Unavailable => Status::Unavailable,
        StoreError::BadRequest => Status::BadRequest,
    }
}

/// Очереди запроса `FETCH`/`WAIT` (ADR-114): ID из заголовка, затем ID из данных.
fn queues(req: &RequestHeader, body: &[u8]) -> Vec<QueueId> {
    let mut ids = vec![req.id];
    ids.extend(body.as_chunks::<ID_LEN>().0.iter().copied());
    ids
}

/// FIFO внутри очереди, round-robin между очередями списка: данные `[номер в списке][номер блоба][блоб]`;
/// очереди нет — `NO_QUEUE` с её номером в списке.
fn fetch_any(store: &mut Store, ids: &[QueueId], now: u64) -> Option<Reply> {
    store.maintain(now, 64);
    let anchor = ids.first()?;
    let turn = store.fetch_start(anchor);
    for offset in 0..ids.len() {
        let i = turn
            .wrapping_add(offset)
            .checked_rem(ids.len())
            .unwrap_or(0);
        let id = ids.get(i)?;
        let Ok(index) = u8::try_from(i) else {
            return Some(plain(Status::BadRequest, Vec::new()));
        };
        match store.fetch_shared(id, now) {
            Ok(Some((seq, blob))) => {
                store.advance_fetch(anchor, i.wrapping_add(1));
                return Some((
                    Status::Ok,
                    ReplyData::Blob {
                        prefix: {
                            let mut p = [0; 9];
                            if let Some(first) = p.first_mut() {
                                *first = index;
                            }
                            if let Some(rest) = p.get_mut(1..) {
                                rest.copy_from_slice(&seq.to_be_bytes());
                            }
                            p
                        },
                        bytes: blob,
                    },
                ));
            }
            Ok(None) => {}
            Err(StoreError::NoQueue) => return Some(plain(Status::NoQueue, vec![index])),
            Err(e) => return Some(plain(store_status(e), Vec::new())),
        }
    }
    None
}

/// Предел припаркованных WAIT: четверть соединений, но хотя бы одно, зарезервирована.
fn wait_limit(max_connections: usize) -> usize {
    max_connections.saturating_sub((max_connections / 4).max(1))
}

struct Waiting<'a>(&'a AtomicUsize);
impl<'a> Waiting<'a> {
    fn take(count: &'a AtomicUsize, limit: usize) -> Option<Self> {
        count
            .try_update(Ordering::SeqCst, Ordering::SeqCst, |n| {
                (n < limit).then(|| n.saturating_add(1))
            })
            .ok()
            .map(|_| Self(count))
    }
}
impl Drop for Waiting<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// Проверка отмены: socket не читается, байты следующего запроса не теряются.
/// Неблокирующий peek не держит mutex хранилища и не продлевает сетевой deadline.
fn peer_open(conn: &TcpStream) -> io::Result<bool> {
    conn.set_nonblocking(true)?;
    let result = conn.peek(&mut [0; 1]);
    conn.set_nonblocking(false)?;
    match result {
        Ok(0) => Ok(false),
        Ok(_) => Ok(true),
        Err(e) if e.kind() == io::ErrorKind::WouldBlock => Ok(true),
        Err(e) => Err(e),
    }
}

/// Проверяем закрытие сокета не реже чем раз в 100 мс, не ждём 25 с после ухода клиента.
const WAIT_CANCEL_TICK: Duration = Duration::from_millis(100);

fn wait_blob(
    shared: &Shared,
    ids: &[QueueId],
    wait: Duration,
    conn: &TcpStream,
    max_connections: usize,
) -> Reply {
    if let Some(r) = fetch_any(&mut shared.lock(), ids, now_hour()) {
        return r;
    }
    let Some(_waiting) = Waiting::take(&shared.waiters, wait_limit(max_connections)) else {
        return plain(Status::Unavailable, Vec::new());
    };
    let subscription = shared.arrived.subscribe(ids);
    let deadline = deadline_in(wait);
    let mut changed = true;
    let mut checked_hour = now_hour();
    loop {
        if !peer_open(conn).unwrap_or(false) {
            return plain(Status::Unavailable, Vec::new());
        }
        let hour = now_hour();
        if changed || hour != checked_hour {
            if let Some(r) = fetch_any(&mut shared.lock(), ids, hour) {
                return r;
            }
            checked_hour = hour;
        }
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return plain(Status::Empty, Vec::new());
        }
        changed = subscription.wait(left.min(WAIT_CANCEL_TICK));
    }
}

fn issue_guest(shared: &Shared, key: &GuestKey, ticket: &QueueId, now: u64) -> Reply {
    let lock = || shared.lock();
    let _issuing = shared
        .issuing
        .lock()
        .unwrap_or_else(PoisonError::into_inner);
    let redeemed = lock().redeem_ticket(ticket, now);
    match redeemed {
        None => plain(Status::Denied, Vec::new()),
        Some(Redeem::Done(g)) => plain(Status::Ok, g),
        Some(Redeem::Fresh(grant)) => match pass::issue_guest(key, &grant, now_secs()) {
            Some(g) => {
                let mut store = lock();
                if store.redeem_ticket(ticket, now_hour()).is_none() {
                    return plain(Status::Denied, Vec::new());
                }
                if let Err(e) = store.finish_ticket(ticket, g.clone()) {
                    return plain(store_status(e), Vec::new());
                }
                plain(Status::Ok, g)
            }
            None => plain(Status::Unavailable, Vec::new()),
        },
    }
}

fn handle(
    shared: &Shared,
    req: &RequestHeader,
    body: Vec<u8>,
    config: &Config,
    conn: &TcpStream,
) -> Reply {
    let Config {
        wait,
        roots,
        revoked,
        guest_key,
        ..
    } = *config;
    let now = now_hour();
    let lock = || shared.lock();
    let done = |r: Result<(), StoreError>| {
        r.map_or_else(
            |e| plain(store_status(e), Vec::new()),
            |()| plain(Status::Ok, Vec::new()),
        )
    };
    match req.cmd {
        Command::Ping => plain(Status::Ok, Vec::new()),
        Command::New => {
            let Some(p) = pass::check_any(&body, roots, guest_key, revoked, now_secs()) else {
                return plain(Status::Denied, Vec::new());
            };
            let sid = pass::sender_id(&req.id);
            match lock().open(req.id, sid, &p.id, p.per_hour, now) {
                Ok(()) => plain(Status::Ok, sid.to_vec()),
                Err(e) => plain(store_status(e), Vec::new()),
            }
        }
        Command::Ticket => {
            if guest_key.is_none() {
                return plain(Status::Unavailable, Vec::new());
            }
            let Some(by) = pass::check_any(&body, roots, guest_key, revoked, now_secs()) else {
                return plain(Status::Denied, Vec::new());
            };
            let Some(ticket) = pass::new_ticket() else {
                return plain(Status::Unavailable, Vec::new());
            };
            match lock().issue_ticket(ticket, &by, now) {
                Ok(()) => plain(Status::Ok, ticket.to_vec()),
                Err(e) => plain(store_status(e), Vec::new()),
            }
        }
        Command::Guest => {
            let Some(key) = guest_key else {
                return plain(Status::Unavailable, Vec::new());
            };
            issue_guest(shared, key, &req.id, now)
        }
        Command::Send => {
            let (r, recipient) = {
                let mut store = lock();
                let recipient = store.recipient(&req.id);
                (store.send(&req.id, body, now), recipient)
            };
            if r.is_ok()
                && let Some(id) = recipient
            {
                shared.arrived.notify(&id);
            }
            done(r)
        }
        Command::Wait => wait_blob(
            shared,
            &queues(req, &body),
            wait,
            conn,
            config.max_connections,
        ),
        Command::Fetch => fetch_any(&mut lock(), &queues(req, &body), now)
            .unwrap_or(plain(Status::Empty, Vec::new())),
        Command::Ack => {
            let Ok(seq) = <[u8; 8]>::try_from(body.as_slice()) else {
                return plain(Status::BadRequest, Vec::new());
            };
            done(lock().ack(&req.id, u64::from_be_bytes(seq), now))
        }
        Command::Del => {
            let result = lock().delete(&req.id, now);
            if result.is_ok() {
                shared.arrived.notify(&req.id);
            }
            done(result)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refuses_non_loopback() {
        let e = Server::start(Config::new(SocketAddr::from(([0, 0, 0, 0], 0)))).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn runtime_budget_rejects_impossible_configuration_and_overflow() {
        let mut c = Config::new(([127, 0, 0, 1], 0).into());
        c.limits.memory_bytes = runtime_memory(c.max_connections).unwrap();
        assert_eq!(
            Server::start(c).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
        c.max_connections = usize::MAX;
        assert_eq!(
            Server::start(c).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[test]
    fn idle_server_reclaims_expired_payload_without_client_traffic() {
        let mut c = Config::new(([127, 0, 0, 1], 0).into());
        c.limits.blob_ttl_hours = 1;
        let server = Server::start(c).unwrap();
        let r = [1; ID_LEN];
        let s = [2; ID_LEN];
        let now = now_hour();
        {
            let mut store = server.shared.lock();
            store.create(r, s, now.saturating_sub(2)).unwrap();
            store.send(&s, vec![42], now.saturating_sub(2)).unwrap();
        }
        let deadline = deadline_in(Duration::from_secs(2));
        while server.shared.lock().total_bytes() != 0 && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(server.shared.lock().total_bytes(), 0);
        server.stop();
    }
}
