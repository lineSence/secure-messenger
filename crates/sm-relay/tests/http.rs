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

const T: Duration = Duration::from_secs(5);

fn start(wait: Duration) -> Server {
    let mut c = Config::new(SocketAddr::from(([127, 0, 0, 1], 0)));
    c.http = true;
    c.wait = wait;
    Server::start(c).unwrap()
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
    let (st, d) = call(s, Command::New, &[0; ID_LEN], b"");
    assert_eq!(st, Status::Ok);
    (
        d[..ID_LEN].try_into().unwrap(),
        d[ID_LEN..].try_into().unwrap(),
    )
}

#[test]
fn frames_over_http_keepalive_and_pipelining() {
    let server = start(Duration::from_millis(200));
    let mut s = connect(&server);
    assert_eq!(call(&mut s, Command::Ping, &[0; ID_LEN], b"").0, Status::Ok);
    let (rid, sid) = new_queue(&mut s);
    assert_eq!(call(&mut s, Command::Send, &sid, b"hello").0, Status::Ok);

    // Два запроса одним куском: второй ждёт в буфере, пока узел отвечает на первый.
    let mut two = post(&encode_request(Command::Fetch, &rid, b"").unwrap(), "");
    two.extend(post(
        &encode_request(Command::Ack, &rid, &1u64.to_be_bytes()).unwrap(),
        "",
    ));
    s.write_all(&two).unwrap();
    let (_, _, body) = read_response(&mut s);
    let (st, d) = frame(&body);
    assert_eq!((st, &d[9..]), (Status::Ok, &b"hello"[..]));
    assert_eq!(u64::from_be_bytes(d[..8].try_into().unwrap()), 0);
    let (_, _, body) = read_response(&mut s);
    assert_eq!(frame(&body).0, Status::Ok);

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
    let (st, d) = call(&mut s, Command::Fetch, &rid1, &list);
    // rid2 непуста и стоит раньше: блоб, а не отказ.
    assert_eq!((st, d[0]), (Status::Ok, 1));
    assert_eq!(
        call(&mut s, Command::Ack, &rid2, &0u64.to_be_bytes()).0,
        Status::Ok
    );
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
