//! Приём HTTP/1.1 от nginx (ADR-90) и `WAIT` (ADR-82): запросы так, как их шлёт `proxy_pass`.
// Помощники теста вне #[test]: на них не действует allow-*-in-tests из clippy.toml.
#![allow(
    clippy::unwrap_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::thread;
use std::time::{Duration, Instant};

use sm_core::relay::{
    Command, ID_LEN, RESPONSE_HEADER, Status, encode_request, parse_response_header,
};
use sm_relay::server::{Config, Server};

mod common;

static ROOT: std::sync::LazyLock<common::Root> = std::sync::LazyLock::new(common::Root::new);

const T: Duration = Duration::from_secs(5);

fn start(wait: Duration) -> Server {
    let mut c = Config::new(SocketAddr::from(([127, 0, 0, 1], 0)));
    c.http = true;
    c.wait = wait;
    Server::start(ROOT.config(c)).unwrap()
}

fn connect(server: &Server) -> TcpStream {
    let s = TcpStream::connect(server.addr()).unwrap();
    s.set_read_timeout(Some(T)).unwrap();
    s
}

fn post(frame: &[u8], extra: &str) -> Vec<u8> {
    let mut r = format!(
        "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: {}\r\n{extra}\r\n",
        frame.len()
    )
    .into_bytes();
    r.extend_from_slice(frame);
    r
}

/// Читает один ответ HTTP: код, закрыть ли соединение, тело.
fn read_response(s: &mut TcpStream) -> (u16, bool, Vec<u8>) {
    let mut head = Vec::new();
    let mut b = [0; 1];
    while !head.ends_with(b"\r\n\r\n") {
        s.read_exact(&mut b).unwrap();
        head.push(b[0]);
    }
    let head = String::from_utf8(head).unwrap();
    let code = head[9..12].parse().unwrap();
    let len: usize = head
        .lines()
        .find_map(|l| l.strip_prefix("Content-Length: "))
        .unwrap()
        .parse()
        .unwrap();
    let mut body = vec![0; len];
    s.read_exact(&mut body).unwrap();
    (code, head.contains("Connection: close"), body)
}

/// Код и данные кадра ответа из тела HTTP.
fn frame(body: &[u8]) -> (Status, Vec<u8>) {
    let (status, len) = parse_response_header(body[..RESPONSE_HEADER].try_into().unwrap()).unwrap();
    assert_eq!(body.len(), RESPONSE_HEADER + len);
    (status, body[RESPONSE_HEADER..].to_vec())
}

fn call(s: &mut TcpStream, cmd: Command, id: &[u8; ID_LEN], data: &[u8]) -> (Status, Vec<u8>) {
    s.write_all(&post(&encode_request(cmd, id, data).unwrap(), ""))
        .unwrap();
    let (code, close, body) = read_response(s);
    assert_eq!((code, close), (200, false));
    frame(&body)
}

fn new_queue(s: &mut TcpStream) -> ([u8; ID_LEN], [u8; ID_LEN]) {
    use rand::TryRngCore as _;
    let mut rid = [0; ID_LEN];
    rand::rngs::OsRng.try_fill_bytes(&mut rid).unwrap();
    let (st, d) = call(s, Command::New, &rid, &ROOT.pass(1, 1000));
    assert_eq!(st, Status::Ok);
    (rid, d.try_into().unwrap())
}

#[test]
fn frames_over_http_keepalive_and_pipelining() {
    let server = start(Duration::from_millis(200));
    let mut s = connect(&server);
    assert_eq!(call(&mut s, Command::Ping, &[0; ID_LEN], b"").0, Status::Ok);
    let (rid, sid) = new_queue(&mut s);
    assert_eq!(call(&mut s, Command::Send, &sid, b"hello").0, Status::Ok);

    let (st, first) = call(&mut s, Command::Fetch, &rid, b"");
    assert_eq!(st, Status::Ok);
    // Два запроса одним куском: ACK возвращает настоящий непрозрачный номер.
    let mut two = post(&encode_request(Command::Fetch, &rid, b"").unwrap(), "");
    two.extend(post(
        &encode_request(Command::Ack, &rid, &first[1..9]).unwrap(),
        "",
    ));
    s.write_all(&two).unwrap();
    let (_, _, body) = read_response(&mut s);
    let (st, d) = frame(&body);
    assert_eq!((st, &d[9..]), (Status::Ok, &b"hello"[..]));
    assert_eq!(d, first);
    let (_, _, body) = read_response(&mut s);
    assert_eq!(frame(&body).0, Status::Ok);
    assert_eq!(
        call(&mut s, Command::Fetch, &rid, b""),
        (Status::Empty, vec![])
    );

    // Connection: close — ответ и закрытие.
    s.write_all(&post(
        &encode_request(Command::Ping, &[0; ID_LEN], b"").unwrap(),
        "Connection: close\r\n",
    ))
    .unwrap();
    let (code, close, _) = read_response(&mut s);
    assert_eq!((code, close), (200, true));
    assert_eq!(s.read(&mut [0; 1]).unwrap(), 0);
    server.stop();
}

#[test]
fn wait_returns_blob_as_soon_as_it_arrives() {
    let server = start(Duration::from_secs(10));
    let mut s = connect(&server);
    let (rid, sid) = new_queue(&mut s);
    let addr = server.addr();
    let sender = thread::spawn(move || {
        thread::sleep(Duration::from_millis(300));
        let mut c = TcpStream::connect(addr).unwrap();
        c.set_read_timeout(Some(T)).unwrap();
        assert_eq!(call(&mut c, Command::Send, &sid, b"late").0, Status::Ok);
    });
    let t = Instant::now();
    let (st, d) = call(&mut s, Command::Wait, &rid, b"");
    assert_eq!((st, &d[9..]), (Status::Ok, &b"late"[..]));
    assert!(
        t.elapsed() < Duration::from_secs(5),
        "WAIT проснулся по SEND, а не по сроку"
    );
    sender.join().unwrap();
    // Блоб остаётся до ACK: WAIT без ACK отдаёт его снова и сразу.
    assert_eq!(&call(&mut s, Command::Wait, &rid, b"").1[9..], b"late");
    server.stop();
}

#[test]
fn wait_times_out_empty_and_rejects_unknown_queue() {
    let server = start(Duration::from_millis(300));
    let mut s = connect(&server);
    let (rid, sid) = new_queue(&mut s);
    let t = Instant::now();
    assert_eq!(call(&mut s, Command::Wait, &rid, b"").0, Status::Empty);
    assert!(t.elapsed() >= Duration::from_millis(300));
    // ID отправителя не даёт читать — сразу, без ожидания.
    let t = Instant::now();
    assert_eq!(call(&mut s, Command::Wait, &sid, b"").0, Status::NoQueue);
    assert!(t.elapsed() < Duration::from_millis(300));
    server.stop();
}

#[test]
fn hostile_requests_get_400_and_close() {
    let server = start(Duration::from_millis(100));
    let ping = encode_request(Command::Ping, &[0; ID_LEN], b"").unwrap();
    let mut long = ping.clone();
    long.push(0);
    let cases: Vec<Vec<u8>> = vec![
        b"GET / HTTP/1.1\r\nHost: x\r\n\r\n".to_vec(),
        b"POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n".to_vec(),
        // Длина тела не совпадает с кадром (лишний байт в конце).
        post(&long, ""),
        // Тело короче заголовка кадра.
        post(b"abc", ""),
        // Неверный кадр.
        post(&[9; 38], ""),
        // Заголовок без конца длиннее предела.
        vec![b'X'; 4096],
    ];
    for req in cases {
        let mut s = connect(&server);
        s.write_all(&req).unwrap();
        let (code, close, body) = read_response(&mut s);
        assert_eq!((code, close, body.len()), (400, true, 0));
        assert_eq!(s.read(&mut [0; 1]).unwrap_or(0), 0);
    }
    server.stop();
}

#[test]
fn wait_and_fetch_over_queue_list() {
    let server = start(Duration::from_secs(10));
    let mut s = connect(&server);
    let (rid1, _) = new_queue(&mut s);
    let (rid2, sid2) = new_queue(&mut s);
    let addr = server.addr();
    let sender = thread::spawn(move || {
        thread::sleep(Duration::from_millis(300));
        let mut c = TcpStream::connect(addr).unwrap();
        c.set_read_timeout(Some(T)).unwrap();
        assert_eq!(call(&mut c, Command::Send, &sid2, b"two").0, Status::Ok);
    });
    // WAIT по [rid1, rid2]: просыпается по SEND во вторую, номер очереди в списке — 1.
    let t = Instant::now();
    let (st, d) = call(&mut s, Command::Wait, &rid1, &rid2);
    assert_eq!((st, d[0], &d[9..]), (Status::Ok, 1, &b"two"[..]));
    assert!(t.elapsed() < Duration::from_secs(5));
    sender.join().unwrap();
    // FETCH по тому же списку отдаёт тот же блоб без ожидания.
    let (st, d) = call(&mut s, Command::Fetch, &rid1, &rid2);
    assert_eq!((st, d[0], &d[9..]), (Status::Ok, 1, &b"two"[..]));

    // Неизвестная очередь — NO_QUEUE с её номером в списке.
    let list = [rid2, [7; ID_LEN]].concat();
    // Курсор теперь после rid2: неизвестная очередь не скрыта постоянной почтой.
    assert_eq!(
        call(&mut s, Command::Fetch, &rid1, &list),
        (Status::NoQueue, vec![2])
    );
    // Отказ не удалил блоб: он по-прежнему доступен по исправленному списку.
    let (st, d) = call(&mut s, Command::Fetch, &rid1, &rid2);
    assert_eq!((st, d[0], &d[9..]), (Status::Ok, 1, &b"two"[..]));
    assert_eq!(call(&mut s, Command::Ack, &rid2, &d[1..9]).0, Status::Ok);
    assert_eq!(
        call(&mut s, Command::Fetch, &rid1, &list),
        (Status::NoQueue, vec![2])
    );
    server.stop();
}

#[test]
fn queue_list_length_is_checked() {
    let id = [1; ID_LEN];
    // Данные не кратны ID_LEN или больше 63 ID — запрос не собирается.
    assert!(encode_request(Command::Wait, &id, &[0; ID_LEN + 1]).is_none());
    assert!(encode_request(Command::Fetch, &id, &[0; ID_LEN * 64]).is_none());
    assert!(encode_request(Command::Fetch, &id, &[0; ID_LEN * 63]).is_some());
}

#[test]
fn new_needs_pass_and_survives_restart() {
    let server = start(Duration::from_millis(100));
    let mut s = connect(&server);
    let rid = [42; ID_LEN];
    // Без пропуска или с пропуском чужого ключа — отказ; соединение живо.
    let stranger = common::Root::new();
    assert_eq!(
        call(&mut s, Command::New, &rid, &stranger.pass(1, 5)).0,
        Status::Denied
    );
    let pass = ROOT.pass(2, 1);
    let (st, sid) = call(&mut s, Command::New, &rid, &pass);
    assert_eq!(st, Status::Ok);
    assert_eq!(sid, sm_relay::pass::sender_id(&rid));
    // Повтор на живую очередь — тот же ID, лимит пропуска (1 в час) не тратится.
    assert_eq!(
        call(&mut s, Command::New, &rid, &pass),
        (Status::Ok, sid.clone())
    );
    assert_eq!(
        call(&mut s, Command::New, &[43; ID_LEN], &pass).0,
        Status::Quota
    );
    let sid: [u8; ID_LEN] = sid.try_into().unwrap();
    assert_eq!(call(&mut s, Command::Send, &sid, b"lost").0, Status::Ok);
    let addr = server.addr();
    drop(s);
    server.stop();

    // Узел перезапущен: очереди нет, NEW с тем же ID возвращает тот же ID отправителя.
    let mut c = Config::new(addr);
    c.http = true;
    let server = Server::start(ROOT.config(c)).unwrap();
    let mut s = connect(&server);
    assert_eq!(call(&mut s, Command::Fetch, &rid, b"").0, Status::NoQueue);
    assert_eq!(
        call(&mut s, Command::New, &rid, &ROOT.pass(3, 1)),
        (Status::Ok, sid.to_vec())
    );
    assert_eq!(call(&mut s, Command::Send, &sid, b"after").0, Status::Ok);
    assert_eq!(&call(&mut s, Command::Fetch, &rid, b"").1[9..], b"after");
    server.stop();
}

#[test]
fn shared_maximum_blob_response_remains_byte_exact_after_ack_and_del() {
    let server = start(Duration::from_secs(1));
    let mut c = connect(&server);
    let (rid, sid) = new_queue(&mut c);
    let payload = vec![0x42; sm_core::relay::MAX_BLOB];
    assert_eq!(call(&mut c, Command::Send, &sid, &payload).0, Status::Ok);
    let (status, data) = call(&mut c, Command::Fetch, &rid, &[]);
    assert_eq!(status, Status::Ok);
    assert_eq!(&data[9..], payload);
    assert_eq!(call(&mut c, Command::Ack, &rid, &data[1..9]).0, Status::Ok);
    assert_eq!(call(&mut c, Command::Del, &rid, &[]).0, Status::Ok);
    server.stop();
}

#[test]
fn stop_cancels_parked_wait_and_closes_existing_connections() {
    let server = start(Duration::from_secs(25));
    let mut c = connect(&server);
    let (rid, _) = new_queue(&mut c);
    c.write_all(&post(
        &encode_request(Command::Wait, &rid, &[]).unwrap(),
        "",
    ))
    .unwrap();
    thread::sleep(Duration::from_millis(50));
    let started = Instant::now();
    server.stop();
    assert!(started.elapsed() < Duration::from_secs(2));
    assert!(matches!(c.read(&mut [0; 1]), Ok(0) | Err(_)));
}

#[test]
fn slow_readers_do_not_block_other_connections_or_shutdown() {
    let server = start(Duration::from_secs(25));
    let mut client = connect(&server);
    let (recipient, sender) = new_queue(&mut client);
    let payload = vec![42; sm_core::relay::MAX_BLOB];
    client
        .write_all(&post(
            &encode_request(Command::Send, &sender, &payload).unwrap(),
            "",
        ))
        .unwrap();
    assert_eq!(read_response(&mut client).0, 200);
    let request = post(
        &encode_request(Command::Fetch, &recipient, &[]).unwrap(),
        "",
    );
    let mut slow = Vec::new();
    for _ in 0..4 {
        let mut stream = connect(&server);
        for _ in 0..128 {
            stream.write_all(&request).unwrap();
        }
        slow.push(stream);
    }
    thread::sleep(Duration::from_millis(100));
    let began = Instant::now();
    client
        .write_all(&post(
            &encode_request(Command::Ping, &[0; 32], &[]).unwrap(),
            "",
        ))
        .unwrap();
    let (status, _, body) = read_response(&mut client);
    assert_eq!(status, 200);
    assert_eq!(body, vec![1, 0, 0, 0, 0, 0]);
    assert!(began.elapsed() < Duration::from_secs(2));
    let began = Instant::now();
    server.stop();
    assert!(began.elapsed() < Duration::from_secs(2));
    drop(slow);
}

#[test]
fn pipeline_larger_than_drive_budget_is_drained_without_new_readiness() {
    let mut c = Config::new(([127, 0, 0, 1], 0).into());
    c.http = true;
    c.io_workers = 1;
    let server = Server::start(ROOT.config(c)).unwrap();
    let mut s = connect(&server);
    let ping = post(
        &encode_request(Command::Ping, &[0; ID_LEN], &[]).unwrap(),
        "",
    );
    s.write_all(&ping.repeat(3000)).unwrap();
    for _ in 0..3000 {
        let (code, close, body) = read_response(&mut s);
        assert_eq!((code, close, frame(&body).0), (200, false, Status::Ok));
    }
    assert_eq!(call(&mut s, Command::Ping, &[0; ID_LEN], &[]).0, Status::Ok);
    server.stop();
}
