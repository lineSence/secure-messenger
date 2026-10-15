//! End-to-end v2 batching. Test clients use explicit unwrap/indices as the existing wire tests do.
#![allow(
    clippy::unwrap_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
use sm_core::{
    relay::{Command, MAX_BLOB, RequestHeader, Status, encode_request, parse_response_header},
    relay_batch::{self, Item},
};
use sm_relay::server::{Config, Server};
use std::{
    io::{Read, Write},
    net::TcpStream,
};
mod common;
static ROOT: std::sync::LazyLock<common::Root> = std::sync::LazyLock::new(common::Root::new);
fn start() -> (Server, TcpStream) {
    let server = Server::start(ROOT.config(Config::new(([127, 0, 0, 1], 0).into()))).unwrap();
    let c = TcpStream::connect(server.addr()).unwrap();
    c.set_read_timeout(Some(std::time::Duration::from_secs(3)))
        .unwrap();
    (server, c)
}
fn call(c: &mut TcpStream, cmd: Command, id: &[u8; 32], body: &[u8]) -> (Status, Vec<u8>) {
    c.write_all(&encode_request(cmd, id, body).unwrap())
        .unwrap();
    let mut head = [0; 6];
    c.read_exact(&mut head).unwrap();
    let (status, len) = parse_response_header(&head).unwrap();
    let mut body = vec![0; len];
    c.read_exact(&mut body).unwrap();
    (status, body)
}
fn item(cmd: Command, id: [u8; 32], data: &[u8]) -> Item<'_> {
    Item {
        header: RequestHeader {
            cmd,
            id,
            len: data.len(),
        },
        data,
    }
}
fn batch(c: &mut TcpStream, frame: &[u8]) -> (Status, Vec<u8>) {
    c.write_all(frame).unwrap();
    let mut head = [0; 6];
    c.read_exact(&mut head).unwrap();
    assert_eq!(head[0], 2);
    let len = u32::from_be_bytes(head[2..].try_into().unwrap()) as usize;
    assert!(len <= sm_core::relay::MAX_RESPONSE_DATA);
    let mut data = vec![0; len];
    c.read_exact(&mut data).unwrap();
    let status = match head[1] {
        0 => Status::Ok,
        5 => Status::BadRequest,
        _ => Status::Unavailable,
    };
    (status, data)
}
fn new(c: &mut TcpStream, rid: [u8; 32]) -> [u8; 32] {
    let (st, data) = call(c, Command::New, &rid, &ROOT.pass(1, 100));
    assert_eq!(st, Status::Ok);
    data.try_into().unwrap()
}
#[test]
fn ordered_send_ack_fetch_and_v1_keepalive() {
    let (server, mut c) = start();
    let rid = [1; 32];
    let sid = new(&mut c, rid);
    assert_eq!(call(&mut c, Command::Send, &sid, b"first").0, Status::Ok);
    let (_, first) = call(&mut c, Command::Fetch, &rid, &[]);
    let ack = first[1..9].to_vec();
    let frame = relay_batch::encode_request(&[
        item(Command::Send, sid, b"second"),
        item(Command::Ack, rid, &ack),
        item(Command::Fetch, rid, &[]),
    ])
    .unwrap();
    let (status, body) = batch(&mut c, &frame);
    assert_eq!(status, Status::Ok);
    let items = relay_batch::decode_response(&body).unwrap();
    assert_eq!(items.len(), 3);
    assert!(items.iter().all(|x| x.0 == Status::Ok));
    assert_eq!(&items[2].1[9..], b"second");
    assert_eq!(call(&mut c, Command::Ping, &[0; 32], &[]).0, Status::Ok);
    server.stop();
}
#[test]
fn malformed_late_item_executes_nothing() {
    let (server, mut c) = start();
    let rid = [2; 32];
    let sid = new(&mut c, rid);
    let mut frame = relay_batch::encode_request(&[
        item(Command::Send, sid, b"must-not-appear"),
        item(Command::Ack, rid, &[0; 8]),
    ])
    .unwrap();
    frame.pop();
    let len = u32::try_from(frame.len() - 38).unwrap();
    frame[34..38].copy_from_slice(&len.to_be_bytes());
    assert_eq!(batch(&mut c, &frame).0, Status::BadRequest);
    let mut check = TcpStream::connect(server.addr()).unwrap();
    assert_eq!(call(&mut check, Command::Fetch, &rid, &[]).0, Status::Empty);
    server.stop();
}
#[test]
fn partial_failure_is_per_item_and_later_items_execute() {
    let (server, mut c) = start();
    let rid = [3; 32];
    let sid = new(&mut c, rid);
    let frame = relay_batch::encode_request(&[
        item(Command::Send, sid, b"a"),
        item(Command::Send, [99; 32], b"b"),
        item(Command::Send, sid, b"c"),
    ])
    .unwrap();
    let (_, body) = batch(&mut c, &frame);
    let rows = relay_batch::decode_response(&body).unwrap();
    assert_eq!(
        rows.iter().map(|x| x.0).collect::<Vec<_>>(),
        vec![Status::Ok, Status::NoQueue, Status::Ok]
    );
    let (_, first) = call(&mut c, Command::Fetch, &rid, &[]);
    assert_eq!(&first[9..], b"a");
    call(&mut c, Command::Ack, &rid, &first[1..9]);
    assert_eq!(&call(&mut c, Command::Fetch, &rid, &[]).1[9..], b"c");
    server.stop();
}
#[test]
fn response_budget_does_not_ack_or_drop_overflowing_fetch() {
    let (server, mut c) = start();
    let one = [4; 32];
    let two = [5; 32];
    let sid1 = new(&mut c, one);
    let sid2 = new(&mut c, two);
    let payload = vec![42; MAX_BLOB / 2];
    call(&mut c, Command::Send, &sid1, &payload);
    call(&mut c, Command::Send, &sid2, &payload);
    let frame = relay_batch::encode_request(&[
        item(Command::Fetch, one, &[]),
        item(Command::Fetch, two, &[]),
    ])
    .unwrap();
    let (_, body) = batch(&mut c, &frame);
    let rows = relay_batch::decode_response(&body).unwrap();
    assert_eq!(rows[0].0, Status::Ok);
    assert_eq!(rows[1].0, Status::TooBig);
    assert_eq!(
        &call(&mut c, Command::Fetch, &two, &[]).1[9..],
        payload.as_slice()
    );
    server.stop();
}
