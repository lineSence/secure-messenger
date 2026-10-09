//! TCP-сервер протокола [`sm_core::relay`]. Поток на соединение (пользователей 2–5).
//!
//! Шаг 2: только loopback-адрес (`127.0.0.1`), снаружи — SSH-туннель (решение владельца 2026-10-09).

use std::fs::File;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use sm_core::relay::{
    Command, ID_LEN, REQUEST_HEADER, RequestHeader, Status, encode_response, parse_request_header,
};

use crate::store::{Limits, QueueId, Store, StoreError};

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
        let store = Arc::new(Mutex::new(Store::new(config.limits)));
        let stop2 = Arc::clone(&stop);
        let accept = std::thread::Builder::new()
            .name("accept".into())
            .spawn(move || accept_loop(&listener, &store, &stop2, config))?;
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

fn accept_loop(
    listener: &TcpListener,
    store: &Arc<Mutex<Store>>,
    stop: &AtomicBool,
    config: Config,
) {
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
        let store = Arc::clone(store);
        let guard = Active(Arc::clone(&active));
        let spawned = std::thread::Builder::new()
            .name("conn".into())
            .spawn(move || {
                let _guard = guard;
                let _ = serve(conn, &store, config.idle_timeout);
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
fn serve(mut conn: TcpStream, store: &Mutex<Store>, timeout: Duration) -> io::Result<()> {
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
        // Длина уже проверена по пределам команды (не больше MAX_BLOB).
        let mut body = vec![0; req.len];
        read_by(&mut conn, &mut body, deadline)?;
        let (status, data) = handle(store, &req, body);
        write_by(&mut conn, &response(status, &data), deadline_in(timeout))?;
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

fn handle(store: &Mutex<Store>, req: &RequestHeader, body: Vec<u8>) -> (Status, Vec<u8>) {
    let now = now_hour();
    let lock = || store.lock().unwrap_or_else(PoisonError::into_inner);
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
        Command::Send => done(lock().send(&req.id, body, now)),
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
