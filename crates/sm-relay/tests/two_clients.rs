//! Критерий шага 2: два клиента обмениваются блобами через узел; рестарт стирает блобы, а
//! очередь создаётся заново с теми же ID (ADR-134).
// Помощники теста вне #[test]: на них не действует allow-*-in-tests из clippy.toml.
#![allow(clippy::unwrap_used)]

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use sm_core::relay::{MAX_BLOB, RelayTcp, VERSION};
use sm_core::transport::MailboxId;
use sm_core::transport::{ProbeResult, Transport, TransportError};
use sm_relay::server::{Config, Server};

mod common;

static ROOT: std::sync::LazyLock<common::Root> = std::sync::LazyLock::new(common::Root::new);

const T: Duration = Duration::from_secs(5);

fn local() -> Config {
    ROOT.config(Config::new(SocketAddr::from(([127, 0, 0, 1], 0))))
}

/// Очередь с ID получателя `[n; 32]`: (ID получателя, ID отправителя).
fn queue(c: &mut RelayTcp, n: u8) -> (MailboxId, MailboxId) {
    let rid = MailboxId([n; 32]);
    let sid = c.create_queue(&rid, &ROOT.pass(1, 100)).unwrap();
    (rid, sid)
}

#[test]
fn two_clients_exchange_blobs() {
    let server = Server::start(local()).unwrap();
    let mut alice = RelayTcp::new(server.addr(), T);
    let mut bob = RelayTcp::new(server.addr(), T);
    assert!(matches!(alice.probe(), ProbeResult::Ok(_)));

    // Каждый создаёт свой входящий ящик и отдаёт контакту ID отправителя.
    let (bob_inbox, to_bob) = queue(&mut bob, 1);
    let (alice_inbox, to_alice) = queue(&mut alice, 2);
    assert_ne!(bob_inbox, to_bob);

    alice.send(&to_bob, b"hello").unwrap();
    alice.send(&to_bob, &vec![7; MAX_BLOB]).unwrap();
    bob.send(&to_alice, b"hi").unwrap();

    assert_eq!(bob.fetch(&bob_inbox, 1).unwrap(), vec![b"hello".to_vec()]);
    assert_eq!(bob.fetch(&bob_inbox, 10).unwrap(), vec![vec![7; MAX_BLOB]]);
    assert_eq!(bob.fetch(&bob_inbox, 10).unwrap().len(), 0);
    assert_eq!(alice.fetch(&alice_inbox, 10).unwrap(), vec![b"hi".to_vec()]);

    // ID отправителя не даёт читать; слишком большой блоб отвергается.
    assert_eq!(bob.fetch(&to_alice, 1), Err(TransportError::Rejected));
    assert_eq!(
        alice.send(&to_bob, &vec![0; MAX_BLOB + 1]),
        Err(TransportError::TooLarge)
    );

    bob.delete_queue(&bob_inbox).unwrap();
    assert_eq!(alice.send(&to_bob, b"x"), Err(TransportError::Rejected));
    server.stop();
}

#[test]
fn restart_erases_blobs_but_queue_comes_back_with_same_ids() {
    let server = Server::start(local()).unwrap();
    let addr = server.addr();
    let (inbox, to_inbox) = {
        let mut c = RelayTcp::new(addr, T);
        let ids = queue(&mut c, 3);
        c.send(&ids.1, b"before restart").unwrap();
        ids
    }; // клиент закрыл соединение
    server.stop();

    let server = Server::start(ROOT.config(Config::new(addr))).unwrap();
    let mut c = RelayTcp::new(addr, T);
    assert_eq!(c.fetch(&inbox, 10), Err(TransportError::Rejected));
    assert_eq!(c.send(&to_inbox, b"x"), Err(TransportError::Rejected));
    // NEW с тем же ID получателя — тот же ID отправителя: контакт шлёт по старому адресу.
    assert_eq!(queue(&mut c, 3), (inbox, to_inbox));
    c.send(&to_inbox, b"after restart").unwrap();
    assert_eq!(
        c.fetch(&inbox, 10).unwrap(),
        vec![b"after restart".to_vec()]
    );
    server.stop();
}

#[test]
fn hostile_frames_close_connection() {
    let server = Server::start(local()).unwrap();
    for head in [
        // SEND с длиной 4 ГиБ: отказ до выделения памяти.
        [&[VERSION, 2][..], &[0; 32], &u32::MAX.to_be_bytes()].concat(),
        // Пустой SEND тоже отвергается до handle.
        [&[VERSION, 2][..], &[0; 32], &[0; 4]].concat(),
        // Другая версия протокола.
        [&[9, 6][..], &[0; 32], &[0; 4]].concat(),
    ] {
        let mut s = TcpStream::connect(server.addr()).unwrap();
        s.set_read_timeout(Some(T)).unwrap();
        s.write_all(&head).unwrap();
        let mut resp = Vec::new();
        s.read_to_end(&mut resp).unwrap(); // сервер закрыл соединение
        assert_eq!(resp, [VERSION, 5, 0, 0, 0, 0]); // BadRequest
    }
    server.stop();
}

#[test]
fn slow_client_is_cut_off_by_frame_deadline() {
    let mut config = local();
    config.idle_timeout = Duration::from_millis(600);
    let server = Server::start(config).unwrap();
    let mut s = TcpStream::connect(server.addr()).unwrap();
    s.set_read_timeout(Some(T)).unwrap();
    let t0 = std::time::Instant::now();
    // По байту раз в 150 мс: каждое чтение укладывается в срок, но кадр целиком — нет.
    for b in [&[VERSION, 6][..], &[0; 32], &[0; 4]].concat() {
        if s.write_all(&[b]).is_err() {
            break;
        }
        std::thread::sleep(Duration::from_millis(150));
        if t0.elapsed() > Duration::from_secs(2) {
            break;
        }
    }
    let mut resp = Vec::new();
    let _ = s.read_to_end(&mut resp);
    assert_eq!(
        resp.len(),
        0,
        "сервер не должен был ответить на кадр после срока"
    );
    assert!(t0.elapsed() < Duration::from_secs(4));
    server.stop();
}

#[test]
fn client_survives_relay_closing_idle_connection() {
    let mut config = local();
    config.idle_timeout = Duration::from_millis(300);
    let server = Server::start(config).unwrap();
    let mut c = RelayTcp::new(server.addr(), T);
    let (inbox, to_inbox) = queue(&mut c, 4);
    std::thread::sleep(Duration::from_millis(600)); // узел закрыл соединение по сроку
    c.send(&to_inbox, b"after idle").unwrap();
    assert_eq!(c.fetch(&inbox, 10).unwrap(), vec![b"after idle".to_vec()]);
    server.stop();
}

#[test]
fn empty_send_rejected_and_next_legitimate_blob_delivered() {
    let server = Server::start(local()).unwrap();
    let mut c = RelayTcp::new(server.addr(), T);
    let (rid, sid) = queue(&mut c, 7);
    let mut raw = TcpStream::connect(server.addr()).unwrap();
    raw.set_read_timeout(Some(T)).unwrap();
    // Не используем encoder: он сам запрещает этот враждебный кадр.
    let head = [&[VERSION, 2][..], &sid.0, &[0; 4]].concat();
    raw.write_all(&head).unwrap();
    let mut resp = Vec::new();
    raw.read_to_end(&mut resp).unwrap();
    assert_eq!(resp, [VERSION, 5, 0, 0, 0, 0]);
    c.send(&sid, b"next legitimate blob").unwrap();
    assert_eq!(
        c.fetch(&rid, 10).unwrap(),
        vec![b"next legitimate blob".to_vec()]
    );
    assert_eq!(c.fetch(&rid, 10).unwrap(), Vec::<Vec<u8>>::new());
    server.stop();
}
