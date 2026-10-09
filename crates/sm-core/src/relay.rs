//! Протокол узла-реле (шаг 2, `wiki/architecture/relay.md`) и транспорт-плагин [`RelayTcp`].
//!
//! Запрос: `[версия u8 = 1][команда u8][ID 32 байта][длина u32 BE][данные]`.
//! Ответ: `[версия u8 = 1][код u8][длина u32 BE][данные]`.
//! Длина проверяется по пределам до выделения памяти под данные.
//!
//! У очереди два ID: отправителя (им кладут, [`Transport::send`]) и получателя
//! (им забирают, [`Transport::fetch`]). Узел содержимое не читает: блобы шифруют клиенты.

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

use crate::padding::MAX_PADDED;
use crate::transport::{
    MailboxId, ProbeResult, Transport, TransportError, TransportInfo, TransportKind,
};
use crate::wire::{Reader, WireError};

/// Версия протокола.
pub const VERSION: u8 = 1;
/// Длина ID очереди.
pub const ID_LEN: usize = 32;
/// Длина заголовка запроса.
pub const REQUEST_HEADER: usize = 38;
/// Длина заголовка ответа.
pub const RESPONSE_HEADER: usize = 6;
/// Самый большой блоб: максимум выравнивания (`padding`) + 64 байта на шифрование.
pub const MAX_BLOB: usize = MAX_PADDED + 64;
/// Самые большие данные ответа: номер блоба (8 байт) + блоб.
pub const MAX_RESPONSE_DATA: usize = MAX_BLOB + 8;

/// Команда запроса.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    /// Создать очередь. Ответ: ID получателя + ID отправителя.
    New,
    /// Положить блоб (ID отправителя).
    Send,
    /// Самый старый блоб (ID получателя). Ответ: номер u64 BE + блоб или [`Status::Empty`].
    Fetch,
    /// Удалить блоб с номером из данных, u64 BE (ID получателя).
    Ack,
    /// Удалить очередь (ID получателя).
    Del,
    /// Проверка связи для диагностики.
    Ping,
}

impl Command {
    /// Код команды в кадре.
    #[must_use]
    pub const fn code(self) -> u8 {
        match self {
            Self::New => 1,
            Self::Send => 2,
            Self::Fetch => 3,
            Self::Ack => 4,
            Self::Del => 5,
            Self::Ping => 6,
        }
    }

    const fn from_code(c: u8) -> Option<Self> {
        Some(match c {
            1 => Self::New,
            2 => Self::Send,
            3 => Self::Fetch,
            4 => Self::Ack,
            5 => Self::Del,
            6 => Self::Ping,
            _ => return None,
        })
    }

    /// Допустимая длина данных: `SEND` — до [`MAX_BLOB`], `ACK` — ровно 8, остальные — 0.
    const fn len_ok(self, len: usize) -> bool {
        match self {
            Self::Send => len <= MAX_BLOB,
            Self::Ack => len == 8,
            Self::New | Self::Fetch | Self::Del | Self::Ping => len == 0,
        }
    }
}

/// Код ответа.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    /// Выполнено.
    Ok,
    /// Очередь пуста.
    Empty,
    /// Очереди нет (не создана, удалена, истекла или узел перезапущен).
    NoQueue,
    /// Блоб больше [`MAX_BLOB`].
    TooBig,
    /// Превышен предел узла (блобов в очереди, очередей, памяти).
    Quota,
    /// Неверный запрос; узел закрывает соединение.
    BadRequest,
    /// Узел не смог выполнить запрос (например, нет случайных байт).
    Unavailable,
}

impl Status {
    /// Код ответа в кадре.
    #[must_use]
    pub const fn code(self) -> u8 {
        match self {
            Self::Ok => 0,
            Self::Empty => 1,
            Self::NoQueue => 2,
            Self::TooBig => 3,
            Self::Quota => 4,
            Self::BadRequest => 5,
            Self::Unavailable => 6,
        }
    }

    const fn from_code(c: u8) -> Option<Self> {
        Some(match c {
            0 => Self::Ok,
            1 => Self::Empty,
            2 => Self::NoQueue,
            3 => Self::TooBig,
            4 => Self::Quota,
            5 => Self::BadRequest,
            6 => Self::Unavailable,
            _ => return None,
        })
    }
}

/// Разобранный заголовок запроса. `Debug` не показывает ID: ID — права доступа к очереди.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct RequestHeader {
    /// Команда.
    pub cmd: Command,
    /// ID очереди (для `NEW` и `PING` не используется).
    pub id: [u8; ID_LEN],
    /// Длина данных, уже проверенная по пределам команды.
    pub len: usize,
}

impl core::fmt::Debug for RequestHeader {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(
            f,
            "RequestHeader {{ cmd: {:?}, len: {} }}",
            self.cmd, self.len
        )
    }
}

fn read_len(r: &mut Reader<'_>) -> Result<usize, WireError> {
    let n = u32::from_be_bytes(r.array::<4>()?);
    usize::try_from(n).map_err(|_| WireError::BadValue)
}

/// Разбирает заголовок запроса из враждебных данных.
///
/// # Errors
/// [`WireError::BadValue`] — другая версия или неизвестная команда;
/// [`WireError::TooLong`] — длина не подходит команде.
pub fn parse_request_header(buf: &[u8; REQUEST_HEADER]) -> Result<RequestHeader, WireError> {
    let mut r = Reader::new(buf);
    if r.u8()? != VERSION {
        return Err(WireError::BadValue);
    }
    let cmd = Command::from_code(r.u8()?).ok_or(WireError::BadValue)?;
    let id = r.array::<ID_LEN>()?;
    let len = read_len(&mut r)?;
    if !cmd.len_ok(len) {
        return Err(WireError::TooLong { len, max: MAX_BLOB });
    }
    Ok(RequestHeader { cmd, id, len })
}

/// Разбирает заголовок ответа: код и длина данных (не больше [`MAX_RESPONSE_DATA`]).
///
/// # Errors
/// [`WireError::BadValue`] — другая версия или неизвестный код; [`WireError::TooLong`] — длина.
pub fn parse_response_header(buf: &[u8; RESPONSE_HEADER]) -> Result<(Status, usize), WireError> {
    let mut r = Reader::new(buf);
    if r.u8()? != VERSION {
        return Err(WireError::BadValue);
    }
    let status = Status::from_code(r.u8()?).ok_or(WireError::BadValue)?;
    let len = read_len(&mut r)?;
    if len > MAX_RESPONSE_DATA {
        return Err(WireError::TooLong {
            len,
            max: MAX_RESPONSE_DATA,
        });
    }
    Ok((status, len))
}

/// Кадр запроса. `None`, если данные не подходят команде.
#[must_use]
pub fn encode_request(cmd: Command, id: &[u8; ID_LEN], data: &[u8]) -> Option<Vec<u8>> {
    if !cmd.len_ok(data.len()) {
        return None;
    }
    let len = u32::try_from(data.len()).ok()?;
    let mut out = Vec::with_capacity(REQUEST_HEADER.checked_add(data.len())?);
    out.push(VERSION);
    out.push(cmd.code());
    out.extend_from_slice(id);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(data);
    Some(out)
}

/// Кадр ответа. `None`, если данные длиннее [`MAX_RESPONSE_DATA`].
#[must_use]
pub fn encode_response(status: Status, data: &[u8]) -> Option<Vec<u8>> {
    if data.len() > MAX_RESPONSE_DATA {
        return None;
    }
    let len = u32::try_from(data.len()).ok()?;
    let mut out = Vec::with_capacity(RESPONSE_HEADER.checked_add(data.len())?);
    out.push(VERSION);
    out.push(status.code());
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(data);
    Some(out)
}

/// Клиент узла по TCP (шаг 2: `127.0.0.1` через SSH-туннель; на шаге 4 — поверх VLESS + Reality).
///
/// Соединение открывается при первом вызове и переоткрывается после ошибки.
/// [`Transport::fetch`] подтверждает (`ACK`) каждый блоб сразу после получения и при обрыве
/// возвращает уже полученные блобы; блоб может прийти повторно, если `ACK` не дошёл.
#[derive(Debug)]
pub struct RelayTcp {
    addr: SocketAddr,
    timeout: Duration,
    conn: Option<TcpStream>,
    /// Когда соединение последний раз использовалось.
    last_used: Option<Instant>,
    /// Последний вызов повторялся на новом соединении (первая попытка могла дойти до узла).
    retried: bool,
}

/// Соединение, простоявшее дольше, не переиспользуется: узел закрывает простаивающие через 30 с.
const REUSE_IDLE: Duration = Duration::from_secs(20);

impl RelayTcp {
    /// Клиент узла по адресу; `timeout` — на подключение и на каждую операцию чтения/записи.
    #[must_use]
    pub const fn new(addr: SocketAddr, timeout: Duration) -> Self {
        Self {
            addr,
            timeout,
            conn: None,
            last_used: None,
            retried: false,
        }
    }

    /// Создаёт очередь. Возвращает (ID получателя, ID отправителя): первый оставить себе,
    /// второй передать контакту.
    ///
    /// # Errors
    /// [`TransportError`].
    pub fn create_queue(&mut self) -> Result<(MailboxId, MailboxId), TransportError> {
        let data = self.call_ok(Command::New, &[0; ID_LEN], &[])?;
        let mut r = Reader::new(&data);
        let ids = (|| Ok::<_, WireError>((r.array::<ID_LEN>()?, r.array::<ID_LEN>()?)))();
        match (ids, r.remaining()) {
            (Ok((rid, sid)), 0) => Ok((MailboxId(rid), MailboxId(sid))),
            _ => Err(self.broken()),
        }
    }

    /// Удаляет очередь по ID получателя. «Очереди нет» после повтора — успех: первый `DEL`
    /// мог удалить очередь, а ответ — потеряться (DEL идемпотентен по эффекту, но не по ответу).
    ///
    /// # Errors
    /// [`TransportError`]; без повтора «очереди нет» — [`TransportError::Rejected`].
    pub fn delete_queue(&mut self, recipient: &MailboxId) -> Result<(), TransportError> {
        match self.call(Command::Del, &recipient.0, &[])? {
            (Status::Ok, _) => Ok(()),
            (Status::NoQueue, _) if self.retried => Ok(()),
            (s, _) => Err(status_error(s)),
        }
    }

    /// Ответ узла, разбитый на ошибку плохого ответа: соединение сбрасывается.
    fn broken(&mut self) -> TransportError {
        self.conn = None;
        TransportError::Blocked
    }

    fn call_ok(
        &mut self,
        cmd: Command,
        id: &[u8; ID_LEN],
        data: &[u8],
    ) -> Result<Vec<u8>, TransportError> {
        match self.call(cmd, id, data)? {
            (Status::Ok, d) => Ok(d),
            (s, _) => Err(status_error(s)),
        }
    }

    fn call(
        &mut self,
        cmd: Command,
        id: &[u8; ID_LEN],
        data: &[u8],
    ) -> Result<(Status, Vec<u8>), TransportError> {
        let frame = encode_request(cmd, id, data).ok_or(TransportError::TooLarge)?;
        if self.last_used.is_some_and(|t| t.elapsed() >= REUSE_IDLE) {
            self.conn = None;
        }
        let reused = self.conn.is_some();
        self.retried = false;
        let mut res = self.exchange(&frame);
        // Узел мог закрыть старое соединение (простой, рестарт): это не блокировка.
        // Один повтор на новом соединении; повтор SEND безопасен — доставка «хотя бы раз»,
        // FETCH не удаляет, ACK идемпотентен. DEL идемпотентен по эффекту, но не по ответу:
        // повтор после удаления даёт «очереди нет» — это разбирает `delete_queue`.
        // NEW не повторяется: если узел создал очередь, но ответ не дошёл, повтор оставил бы
        // на узле очередь, которую не удалить.
        if reused
            && cmd != Command::New
            && matches!(
                res,
                Err(TransportError::Blocked | TransportError::Unreachable)
            )
        {
            self.conn = None;
            self.retried = true;
            res = self.exchange(&frame);
        }
        if res.is_err() {
            self.conn = None;
        }
        self.last_used = Some(Instant::now());
        res
    }

    fn exchange(&mut self, frame: &[u8]) -> Result<(Status, Vec<u8>), TransportError> {
        if self.conn.is_none() {
            let c = TcpStream::connect_timeout(&self.addr, self.timeout).map_err(io_error)?;
            c.set_read_timeout(Some(self.timeout)).map_err(io_error)?;
            c.set_write_timeout(Some(self.timeout)).map_err(io_error)?;
            c.set_nodelay(true).map_err(io_error)?;
            self.conn = Some(c);
        }
        let Some(conn) = self.conn.as_mut() else {
            return Err(TransportError::Unreachable);
        };
        conn.write_all(frame).map_err(io_error)?;
        let mut head = [0; RESPONSE_HEADER];
        conn.read_exact(&mut head).map_err(io_error)?;
        // Ответ не по протоколу — похоже на подмену в пути.
        let (status, len) = parse_response_header(&head).map_err(|_| TransportError::Blocked)?;
        let mut body = vec![0; len];
        conn.read_exact(&mut body).map_err(io_error)?;
        if status == Status::BadRequest {
            self.conn = None;
        }
        Ok((status, body))
    }
}

const fn status_error(s: Status) -> TransportError {
    match s {
        Status::TooBig => TransportError::TooLarge,
        Status::Unavailable => TransportError::Unreachable,
        Status::Ok | Status::Empty | Status::NoQueue | Status::Quota | Status::BadRequest => {
            TransportError::Rejected
        }
    }
}

// По значению — чтобы писать `.map_err(io_error)`.
#[allow(clippy::needless_pass_by_value)]
fn io_error(e: io::Error) -> TransportError {
    use io::ErrorKind as K;
    match e.kind() {
        K::TimedOut | K::WouldBlock => TransportError::Timeout,
        K::ConnectionReset | K::ConnectionAborted | K::BrokenPipe | K::UnexpectedEof => {
            TransportError::Blocked
        }
        _ => TransportError::Unreachable,
    }
}

impl Transport for RelayTcp {
    fn info(&self) -> TransportInfo {
        TransportInfo {
            id: "relay-tcp",
            kind: TransportKind::Relay,
            max_blob: MAX_BLOB,
        }
    }

    fn send(&mut self, to: &MailboxId, blob: &[u8]) -> Result<(), TransportError> {
        if blob.len() > MAX_BLOB {
            return Err(TransportError::TooLarge);
        }
        self.call_ok(Command::Send, &to.0, blob).map(drop)
    }

    fn fetch(&mut self, mailbox: &MailboxId, max: usize) -> Result<Vec<Vec<u8>>, TransportError> {
        // Полученный блоб сначала попадает в `out`, потом подтверждается. Ошибка после первого
        // блоба не выбрасывает уже полученные (их узел мог удалить): возвращаем их, остальное —
        // при следующем вызове. Если ACK не дошёл, блоб придёт ещё раз — доставка «хотя бы раз»,
        // повторы отбрасываются по ID сообщения (шаг 3).
        let mut out = Vec::new();
        while out.len() < max {
            let data = match self.call(Command::Fetch, &mailbox.0, &[]) {
                Ok((Status::Ok, d)) => d,
                Ok((Status::Empty, _)) => break,
                Ok((s, _)) if out.is_empty() => return Err(status_error(s)),
                Err(e) if out.is_empty() => return Err(e),
                Ok(_) | Err(_) => break,
            };
            let Some((seq, blob)) = data.split_first_chunk::<8>() else {
                let e = self.broken();
                return if out.is_empty() { Err(e) } else { Ok(out) };
            };
            out.push(blob.to_vec());
            if self.call_ok(Command::Ack, &mailbox.0, seq).is_err() {
                break;
            }
        }
        Ok(out)
    }

    fn probe(&mut self) -> ProbeResult {
        let t0 = Instant::now();
        match self.call_ok(Command::Ping, &[0; ID_LEN], &[]) {
            Ok(_) => ProbeResult::Ok(t0.elapsed()),
            Err(e) => ProbeResult::Failed(e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn head(frame: &[u8]) -> [u8; REQUEST_HEADER] {
        frame[..REQUEST_HEADER].try_into().unwrap()
    }

    #[test]
    fn request_roundtrip() {
        let id = [7; ID_LEN];
        for (cmd, data) in [
            (Command::New, &b""[..]),
            (Command::Send, b"blob"),
            (Command::Fetch, b""),
            (Command::Ack, &5u64.to_be_bytes()),
            (Command::Del, b""),
            (Command::Ping, b""),
        ] {
            let f = encode_request(cmd, &id, data).unwrap();
            let h = parse_request_header(&head(&f)).unwrap();
            assert_eq!((h.cmd, h.id, h.len), (cmd, id, data.len()));
            assert_eq!(&f[REQUEST_HEADER..], data);
        }
    }

    #[test]
    fn hostile_request_headers_rejected() {
        let ok = encode_request(Command::Fetch, &[0; ID_LEN], b"").unwrap();
        let mut bad_ver = head(&ok);
        bad_ver[0] = 2;
        assert_eq!(parse_request_header(&bad_ver), Err(WireError::BadValue));
        let mut bad_cmd = head(&ok);
        bad_cmd[1] = 0;
        assert_eq!(parse_request_header(&bad_cmd), Err(WireError::BadValue));
        // Огромная длина отвергается до выделения памяти.
        let mut huge = head(&ok);
        huge[1] = Command::Send.code();
        huge[34..].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(matches!(
            parse_request_header(&huge),
            Err(WireError::TooLong { .. })
        ));
        let mut just_over = huge;
        just_over[34..].copy_from_slice(&u32::try_from(MAX_BLOB + 1).unwrap().to_be_bytes());
        assert!(parse_request_header(&just_over).is_err());
        // FETCH с данными, ACK не 8 байт.
        let mut fetch_data = head(&ok);
        fetch_data[34..].copy_from_slice(&1u32.to_be_bytes());
        assert!(parse_request_header(&fetch_data).is_err());
        assert!(encode_request(Command::Ack, &[0; ID_LEN], b"1234567").is_none());
        assert!(encode_request(Command::Send, &[0; ID_LEN], &vec![0; MAX_BLOB + 1]).is_none());
        assert!(encode_request(Command::Send, &[0; ID_LEN], &vec![0; MAX_BLOB]).is_some());
    }

    #[test]
    fn response_roundtrip_and_limits() {
        let f = encode_response(Status::Empty, b"").unwrap();
        assert_eq!(
            parse_response_header(&f[..].try_into().unwrap()),
            Ok((Status::Empty, 0))
        );
        let f = encode_response(Status::Ok, b"abc").unwrap();
        assert_eq!(
            parse_response_header(&f[..6].try_into().unwrap()),
            Ok((Status::Ok, 3))
        );
        assert!(encode_response(Status::Ok, &vec![0; MAX_RESPONSE_DATA + 1]).is_none());
        let mut huge = [VERSION, 0, 0, 0, 0, 0];
        huge[2..].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(parse_response_header(&huge).is_err());
        assert!(parse_response_header(&[VERSION, 99, 0, 0, 0, 0]).is_err());
    }

    #[test]
    fn debug_hides_queue_id() {
        let h = RequestHeader {
            cmd: Command::Fetch,
            id: [0xAB; ID_LEN],
            len: 0,
        };
        let s = format!("{h:?}");
        assert!(
            !s.contains("171") && !s.to_lowercase().contains("ab,"),
            "{s}"
        );
    }

    /// Узел-заглушка: отдаёт блоб `b"one"`, принимает ACK, на втором FETCH рвёт соединение.
    #[test]
    fn fetch_keeps_blobs_received_before_disconnect() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let fake = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut head = [0; REQUEST_HEADER];
            s.read_exact(&mut head).unwrap(); // FETCH
            s.write_all(
                &encode_response(Status::Ok, &[&7u64.to_be_bytes()[..], b"one"].concat()).unwrap(),
            )
            .unwrap();
            s.read_exact(&mut head).unwrap(); // ACK
            let mut seq = [0; 8];
            s.read_exact(&mut seq).unwrap();
            assert_eq!(u64::from_be_bytes(seq), 7);
            s.write_all(&encode_response(Status::Ok, b"").unwrap())
                .unwrap();
            s.read_exact(&mut head).unwrap(); // второй FETCH — обрыв
        });
        let mut t = RelayTcp::new(addr, Duration::from_secs(5));
        assert_eq!(t.fetch(&MailboxId([1; 32]), 10), Ok(vec![b"one".to_vec()]));
        fake.join().unwrap();
    }

    /// Узел-заглушка закрывает первое соединение после ответа; клиент переподключается сам.
    #[test]
    fn reconnects_after_relay_closed_idle_connection() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let fake = std::thread::spawn(move || {
            for _ in 0..2 {
                let (mut s, _) = listener.accept().unwrap();
                let mut head = [0; REQUEST_HEADER];
                s.read_exact(&mut head).unwrap(); // PING
                s.write_all(&encode_response(Status::Ok, b"").unwrap())
                    .unwrap();
            } // соединение закрыто
        });
        let mut t = RelayTcp::new(addr, Duration::from_secs(5));
        assert!(matches!(t.probe(), ProbeResult::Ok(_)));
        std::thread::sleep(Duration::from_millis(100));
        assert!(matches!(t.probe(), ProbeResult::Ok(_)));
        fake.join().unwrap();
    }

    /// NEW на переиспользуемом соединении, которое узел закрыл без ответа, не повторяется:
    /// узел мог уже создать очередь.
    #[test]
    fn new_is_not_retried_on_reused_connection() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let fake = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut head = [0; REQUEST_HEADER];
            s.read_exact(&mut head).unwrap(); // PING
            s.write_all(&encode_response(Status::Ok, b"").unwrap())
                .unwrap();
            s.read_exact(&mut head).unwrap(); // NEW — закрываем без ответа
            assert_eq!(head[1], Command::New.code());
            drop(s);
            listener.set_nonblocking(true).unwrap();
            std::thread::sleep(Duration::from_millis(300));
            // Повторного подключения с NEW быть не должно.
            assert!(listener.accept().is_err());
        });
        let mut t = RelayTcp::new(addr, Duration::from_secs(5));
        assert!(matches!(t.probe(), ProbeResult::Ok(_)));
        assert!(t.create_queue().is_err());
        fake.join().unwrap();
    }

    /// Узел-заглушка: PING на первом соединении; DEL на нём «выполняет» и закрывает соединение
    /// без ответа; на втором соединении отвечает на повтор DEL «очереди нет».
    fn del_lost_reply_relay(listener: &std::net::TcpListener) {
        let (mut s, _) = listener.accept().unwrap();
        let mut head = [0; REQUEST_HEADER];
        s.read_exact(&mut head).unwrap(); // PING
        s.write_all(&encode_response(Status::Ok, b"").unwrap())
            .unwrap();
        s.read_exact(&mut head).unwrap(); // DEL — очередь удалена, ответ потерян
        assert_eq!(head[1], Command::Del.code());
        drop(s);
        let (mut s, _) = listener.accept().unwrap();
        s.read_exact(&mut head).unwrap(); // повтор DEL
        assert_eq!(head[1], Command::Del.code());
        s.write_all(&encode_response(Status::NoQueue, b"").unwrap())
            .unwrap();
    }

    /// Повтор DEL после потерянного ответа: «очереди нет» — успех, очередь удалена.
    #[test]
    fn delete_queue_succeeds_when_retry_finds_no_queue() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let fake = std::thread::spawn(move || del_lost_reply_relay(&listener));
        let mut t = RelayTcp::new(addr, Duration::from_secs(5));
        assert!(matches!(t.probe(), ProbeResult::Ok(_)));
        assert_eq!(t.delete_queue(&MailboxId([3; 32])), Ok(()));
        fake.join().unwrap();
    }

    /// Без повтора «очереди нет» на DEL — по-прежнему отказ, а следующий вызов не считается
    /// повтором.
    #[test]
    fn delete_queue_without_retry_reports_no_queue() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let fake = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut head = [0; REQUEST_HEADER];
            for _ in 0..2 {
                s.read_exact(&mut head).unwrap(); // DEL
                s.write_all(&encode_response(Status::NoQueue, b"").unwrap())
                    .unwrap();
            }
        });
        let mut t = RelayTcp::new(addr, Duration::from_secs(5));
        assert_eq!(
            t.delete_queue(&MailboxId([3; 32])),
            Err(TransportError::Rejected)
        );
        assert_eq!(
            t.delete_queue(&MailboxId([3; 32])),
            Err(TransportError::Rejected)
        );
        fake.join().unwrap();
    }

    #[test]
    fn unreachable_relay_reported() {
        // Порт 1 на 127.0.0.1 закрыт: подключение отвергается.
        let mut t = RelayTcp::new(
            SocketAddr::from(([127, 0, 0, 1], 1)),
            Duration::from_secs(2),
        );
        assert!(matches!(
            t.probe(),
            ProbeResult::Failed(TransportError::Unreachable | TransportError::Timeout)
        ));
        assert_eq!(
            t.send(&MailboxId([0; 32]), &vec![0; MAX_BLOB + 1]),
            Err(TransportError::TooLarge)
        );
    }
}
