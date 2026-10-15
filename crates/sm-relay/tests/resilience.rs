//! Регрессии отказоустойчивости узла: пустой блоб, справедливость, ACK и отмена WAIT.
// Помощники интеграционных тестов используют unwrap/индексы как остальные тестовые клиенты.
#![allow(
    clippy::unwrap_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
use sm_core::relay::{Command, Status, encode_request, parse_response_header};
use sm_relay::server::{Config, Server};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::thread;
use std::time::{Duration, Instant};
mod common;
static ROOT: std::sync::LazyLock<common::Root> = std::sync::LazyLock::new(common::Root::new);
const T: Duration = Duration::from_secs(5);
struct Client {
    stream: TcpStream,
    http: bool,
}
impl Client {
    fn new(addr: SocketAddr, http: bool) -> Self {
        let stream = TcpStream::connect(addr).unwrap();
        stream.set_read_timeout(Some(T)).unwrap();
        stream.set_nodelay(true).unwrap();
        Self { stream, http }
    }
    fn send(&mut self, cmd: Command, id: &[u8; 32], data: &[u8]) {
        let frame = encode_request(cmd, id, data).unwrap();
        if self.http {
            self.stream
                .write_all(
                    format!(
                        "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: {}\r\n\r\n",
                        frame.len()
                    )
                    .as_bytes(),
                )
                .unwrap();
        }
        self.stream.write_all(&frame).unwrap();
    }
    fn read(&mut self) -> (Status, Vec<u8>) {
        if self.http {
            let mut h = Vec::new();
            let mut b = [0; 1];
            while !h.ends_with(b"\r\n\r\n") {
                self.stream.read_exact(&mut b).unwrap();
                h.push(b[0]);
            }
            assert!(h.starts_with(b"HTTP/1.1 200"));
        }
        let mut h = [0; 6];
        self.stream.read_exact(&mut h).unwrap();
        let (status, len) = parse_response_header(&h).unwrap();
        let mut body = vec![0; len];
        self.stream.read_exact(&mut body).unwrap();
        (status, body)
    }
    fn call(&mut self, cmd: Command, id: &[u8; 32], data: &[u8]) -> (Status, Vec<u8>) {
        self.send(cmd, id, data);
        self.read()
    }
    fn queue(&mut self, id: &[u8; 32]) -> [u8; 32] {
        let (st, d) = self.call(Command::New, id, &ROOT.pass(1, 100));
        assert_eq!(st, Status::Ok);
        d.try_into().unwrap()
    }
}
fn start(http: bool, max: usize) -> Server {
    let mut c = Config::new(SocketAddr::from(([127, 0, 0, 1], 0)));
    c.http = http;
    c.max_connections = max;
    c.wait = Duration::from_secs(3);
    Server::start(ROOT.config(c)).unwrap()
}
#[test]
fn empty_blob_is_rejected_and_valid_delivery_still_works() {
    for http in [false, true] {
        let server = start(http, 64);
        let rid = [11; 32];
        let mut c = Client::new(server.addr(), http);
        let sid = c.queue(&rid);
        assert_eq!(c.call(Command::Send, &sid, b"").0, Status::BadRequest);
        drop(c);
        let mut c = Client::new(server.addr(), http);
        assert_eq!(c.call(Command::Send, &sid, b"valid").0, Status::Ok);
        let (st, d) = c.call(Command::Fetch, &rid, b"");
        assert_eq!(st, Status::Ok);
        assert_eq!(&d[9..], b"valid");
        server.stop();
    }
}
#[test]
fn busy_first_queue_does_not_starve_second() {
    for http in [false, true] {
        let server = start(http, 64);
        let mut c = Client::new(server.addr(), http);
        let r1 = [12; 32];
        let r2 = [13; 32];
        let s1 = c.queue(&r1);
        let s2 = c.queue(&r2);
        c.call(Command::Send, &s2, b"second");
        let mut found = false;
        for _ in 0..4 {
            c.call(Command::Send, &s1, b"busy");
            let (st, d) = c.call(Command::Fetch, &r1, &r2);
            assert_eq!(st, Status::Ok);
            let rid = if d[0] == 0 { &r1 } else { &r2 };
            c.call(Command::Ack, rid, &d[1..9]);
            if d[0] == 1 {
                assert_eq!(&d[9..], b"second");
                found = true;
                break;
            }
        }
        assert!(
            found,
            "вторая очередь должна обслуживаться при постоянной первой"
        );
        server.stop();
    }
}
#[test]
fn stale_ack_does_not_cross_queue_recreation() {
    for http in [false, true] {
        let server = start(http, 64);
        let mut c = Client::new(server.addr(), http);
        let rid = [14; 32];
        let sid = c.queue(&rid);
        c.call(Command::Send, &sid, b"old");
        let old = c.call(Command::Fetch, &rid, b"").1;
        c.call(Command::Del, &rid, b"");
        assert_eq!(c.queue(&rid), sid);
        c.call(Command::Send, &sid, b"new");
        let fresh = c.call(Command::Fetch, &rid, b"").1;
        assert_ne!(&old[1..9], &fresh[1..9]);
        c.call(Command::Ack, &rid, &old[1..9]);
        assert_eq!(c.call(Command::Fetch, &rid, b"").1, fresh);
        server.stop();
    }
}
#[test]
fn delete_wakes_wait_immediately() {
    for http in [false, true] {
        let server = start(http, 64);
        let mut c = Client::new(server.addr(), http);
        let rid = [15; 32];
        c.queue(&rid);
        c.send(Command::Wait, &rid, b"");
        thread::sleep(Duration::from_millis(100));
        let mut other = Client::new(server.addr(), http);
        let t = Instant::now();
        assert_eq!(other.call(Command::Del, &rid, b"").0, Status::Ok);
        assert_eq!(c.read(), (Status::NoQueue, vec![0]));
        assert!(t.elapsed() < Duration::from_secs(1));
        server.stop();
    }
}
#[test]
fn wait_budget_reserves_sender_slots() {
    for http in [false, true] {
        let server = start(http, 8);
        let mut creator = Client::new(server.addr(), http);
        let rid = [16; 32];
        let sid = creator.queue(&rid);
        drop(creator);
        let mut waiters: Vec<_> = (0..6).map(|_| Client::new(server.addr(), http)).collect();
        for c in &mut waiters {
            c.send(Command::Wait, &rid, b"");
        }
        thread::sleep(Duration::from_millis(100));
        let mut overflow = Client::new(server.addr(), http);
        assert_eq!(
            overflow.call(Command::Wait, &rid, b"").0,
            Status::Unavailable
        );
        assert_eq!(overflow.stream.read(&mut [0; 1]).unwrap(), 0);
        drop(overflow);
        let mut sender = Client::new(server.addr(), http);
        let t = Instant::now();
        assert_eq!(sender.call(Command::Send, &sid, b"wake").0, Status::Ok);
        for c in &mut waiters {
            let (st, d) = c.read();
            assert_eq!(st, Status::Ok);
            assert_eq!(&d[9..], b"wake");
        }
        assert!(t.elapsed() < Duration::from_secs(1));
        server.stop();
    }
}
#[test]
fn disconnected_waiters_release_slots_early() {
    for http in [false, true] {
        let server = start(http, 8);
        let mut creator = Client::new(server.addr(), http);
        let rid = [17; 32];
        creator.queue(&rid);
        drop(creator);
        let mut waiters: Vec<_> = (0..6).map(|_| Client::new(server.addr(), http)).collect();
        for c in &mut waiters {
            c.send(Command::Wait, &rid, b"");
        }
        thread::sleep(Duration::from_millis(100));
        drop(waiters);
        thread::sleep(Duration::from_millis(400));
        let mut fresh: Vec<_> = (0..6).map(|_| Client::new(server.addr(), http)).collect();
        for c in &mut fresh {
            c.send(Command::Wait, &rid, b"");
        }
        thread::sleep(Duration::from_millis(100));
        let mut sender = Client::new(server.addr(), http);
        let sid = sm_relay::pass::sender_id(&rid);
        assert_eq!(
            sender.call(Command::Send, &sid, b"after disconnect").0,
            Status::Ok
        );
        for c in &mut fresh {
            assert_eq!(c.read().0, Status::Ok);
        }
        server.stop();
    }
}

#[test]
fn stale_ack_does_not_cross_server_restart() {
    for http in [false, true] {
        let rid = [18; 32];
        let server = start(http, 64);
        let mut c = Client::new(server.addr(), http);
        let sid = c.queue(&rid);
        c.call(Command::Send, &sid, b"old process");
        let old = c.call(Command::Fetch, &rid, b"").1;
        drop(c);
        server.stop();
        let server = start(http, 64);
        let mut c = Client::new(server.addr(), http);
        assert_eq!(c.call(Command::Fetch, &rid, b"").0, Status::NoQueue);
        assert_eq!(c.queue(&rid), sid);
        c.call(Command::Send, &sid, b"new process");
        let fresh = c.call(Command::Fetch, &rid, b"").1;
        assert_ne!(&old[1..9], &fresh[1..9]);
        assert_eq!(c.call(Command::Ack, &rid, &old[1..9]).0, Status::Ok);
        assert_eq!(c.call(Command::Fetch, &rid, b"").1, fresh);
        assert_eq!(c.call(Command::Ack, &rid, &fresh[1..9]).0, Status::Ok);
        assert_eq!(c.call(Command::Fetch, &rid, b"").0, Status::Empty);
        drop(c);
        server.stop();
    }
}
