//! Сервер протокола [`sm_core::relay`]. Поток на соединение (пользователей 2–5).
//!
//! Только loopback-адрес (`127.0.0.1`). Два режима приёма:
//! - кадры прямо по TCP — шаг 2, снаружи SSH-туннель (решение владельца 2026-10-09);
//! - HTTP/1.1 от nginx (ADR-90, 0.0.2-alpha): тело запроса — один кадр, тело ответа — один кадр
//!   ([`crate::http`]).
//!
//! `WAIT` (ADR-82): при пустой очереди поток соединения ждёт блоб до [`Config::wait`] —
//! `SEND` в ту же очередь будит его сразу.

use std::fs::File;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use sm_core::relay::{
    Command, ID_LEN, REQUEST_HEADER, RequestHeader, Status, encode_response, parse_request_header,
};

use crate::http;
use crate::store::{Limits, QueueId, Store, StoreError};

/// Самое долгое ожидание `WAIT`: меньше 30 с `proxy_read_timeout` nginx (ADR-90).
pub const WAIT_MAX: Duration = Duration::from_secs(25);

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
        }
    }
}

/// Запущенный сервер. [`Server::stop`] останавливает приём соединений;
/// очереди живут, пока жив процесс (или последнее соединение после `stop`).
#[derive(Debug)]
pub struct Server {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    accept: Option<JoinHandle<()>>,
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
        let listener = TcpListener::bind(config.listen)?;
        let addr = listener.local_addr()?;
        let stop = Arc::new(AtomicBool::new(false));
        let shared = Arc::new(Shared {
            store: Mutex::new(Store::new(config.limits)),
            arrived: Condvar::new(),
        });
        let stop2 = Arc::clone(&stop);
        let accept = std::thread::Builder::new()
            .name("accept".into())
            .spawn(move || accept_loop(&listener, &shared, &stop2, config))?;
        Ok(Self {
            addr,
            stop,
            accept: Some(accept),
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
        // Разбудить accept(): он проверит флаг и выйдет.
        let _ = TcpStream::connect(self.addr);
        if let Some(h) = self.accept.take() {
            let _ = h.join();
        }
    }
}

/// Очереди и сигнал «в очередь пришёл блоб» для ждущих `WAIT`.
struct Shared {
    store: Mutex<Store>,
    arrived: Condvar,
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, Store> {
        self.store.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

fn accept_loop(listener: &TcpListener, shared: &Arc<Shared>, stop: &AtomicBool, config: Config) {
    let active = Arc::new(AtomicUsize::new(0));
    for conn in listener.incoming() {
        if stop.load(Ordering::SeqCst) {
            return;
        }
        let Ok(conn) = conn else { continue };
        if active.load(Ordering::SeqCst) >= config.max_connections {
            continue; // соединение закрывается при выходе из области видимости
        }
        active.fetch_add(1, Ordering::SeqCst);
        let shared = Arc::clone(shared);
        let guard = Active(Arc::clone(&active));
        let spawned = std::thread::Builder::new()
            .name("conn".into())
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
struct Active(Arc<AtomicUsize>);

impl Drop for Active {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
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
    let mut chunk = [0; BODY_CHUNK];
    while body.len() < len {
        let n = BODY_CHUNK.min(len.saturating_sub(body.len()));
        let part = chunk.get_mut(..n).ok_or(io::ErrorKind::InvalidData)?;
        read_by(conn, part, deadline)?;
        body.extend_from_slice(part);
    }
    Ok(body)
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
            let _ = write_by(
                &mut conn,
                &response(Status::BadRequest, &[]),
                deadline_in(timeout),
            );
            return Ok(());
        };
        // Длина уже проверена по пределам команды (не больше MAX_BLOB); память растёт по мере
        // прихода байт, а не по заявленной длине (ревью v0.0.1-alpha, low).
        let body = read_body(&mut conn, req.len, deadline)?;
        let (status, data) = handle(shared, &req, body, config.wait);
        write_by(&mut conn, &response(status, &data), deadline_in(timeout))?;
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
    let have = pending.len().min(n);
    let mut out: Vec<u8> = pending.drain(..have).collect();
    if out.len() < n {
        let rest = read_body(conn, n.saturating_sub(out.len()), deadline)?;
        out.extend_from_slice(&rest);
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
        let (status, data) = handle(shared, &req, body, config.wait);
        let frame = response(status, &data);
        let mut out = http::ok_head(frame.len(), head.close);
        out.extend_from_slice(&frame);
        write_by(&mut conn, &out, deadline_in(timeout))?;
        if head.close {
            return Ok(());
        }
    }
}

fn response(status: Status, data: &[u8]) -> Vec<u8> {
    // Данные ответа сервер собирает сам и не больше MAX_RESPONSE_DATA; запасной путь — пустой ответ.
    encode_response(status, data)
        .or_else(|| encode_response(Status::Unavailable, &[]))
        .unwrap_or_default()
}

/// Текущий час с начала эпохи Unix (точность хранения времени — до часа).
fn now_hour() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() / 3600)
}

/// 32 случайных байта из ОС (`/dev/urandom`, Linux): только `std`, без своей криптографии (ADR-1).
fn random_id() -> io::Result<QueueId> {
    let mut id = [0; ID_LEN];
    File::open("/dev/urandom")?.read_exact(&mut id)?;
    Ok(id)
}

fn store_status(e: StoreError) -> Status {
    match e {
        StoreError::NoQueue => Status::NoQueue,
        StoreError::TooBig => Status::TooBig,
        StoreError::Quota | StoreError::RateLimited => Status::Quota,
        StoreError::Collision => Status::Unavailable,
    }
}

/// `WAIT`: самый старый блоб; при пустой очереди — ждать `SEND` до `wait`, потом [`Status::Empty`].
fn wait_blob(shared: &Shared, id: &QueueId, wait: Duration) -> (Status, Vec<u8>) {
    let deadline = deadline_in(wait);
    let mut store = shared.lock();
    loop {
        match store.fetch(id, now_hour()) {
            Ok(Some((seq, blob))) => return (Status::Ok, [&seq.to_be_bytes()[..], &blob].concat()),
            Ok(None) => {}
            Err(e) => return (store_status(e), Vec::new()),
        }
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return (Status::Empty, Vec::new());
        }
        store = shared
            .arrived
            .wait_timeout(store, left)
            .map_or_else(|e| e.into_inner().0, |(g, _)| g);
    }
}

fn handle(
    shared: &Shared,
    req: &RequestHeader,
    body: Vec<u8>,
    wait: Duration,
) -> (Status, Vec<u8>) {
    let now = now_hour();
    let lock = || shared.lock();
    let done = |r: Result<(), StoreError>| {
        r.map_or_else(
            |e| (store_status(e), Vec::new()),
            |()| (Status::Ok, Vec::new()),
        )
    };
    match req.cmd {
        Command::Ping => (Status::Ok, Vec::new()),
        Command::New => {
            let (Ok(rid), Ok(sid)) = (random_id(), random_id()) else {
                return (Status::Unavailable, Vec::new());
            };
            match lock().create(rid, sid, now) {
                Ok(()) => (Status::Ok, [rid, sid].concat()),
                Err(e) => (store_status(e), Vec::new()),
            }
        }
        Command::Send => {
            let r = lock().send(&req.id, body, now);
            if r.is_ok() {
                shared.arrived.notify_all();
            }
            done(r)
        }
        Command::Wait => wait_blob(shared, &req.id, wait),
        Command::Fetch => match lock().fetch(&req.id, now) {
            Ok(Some((seq, blob))) => (Status::Ok, [&seq.to_be_bytes()[..], &blob].concat()),
            Ok(None) => (Status::Empty, Vec::new()),
            Err(e) => (store_status(e), Vec::new()),
        },
        Command::Ack => {
            let Ok(seq) = <[u8; 8]>::try_from(body.as_slice()) else {
                return (Status::BadRequest, Vec::new());
            };
            done(lock().ack(&req.id, u64::from_be_bytes(seq), now))
        }
        Command::Del => done(lock().delete(&req.id, now)),
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
    fn random_ids_differ() {
        assert_ne!(random_id().unwrap(), random_id().unwrap());
    }
}
