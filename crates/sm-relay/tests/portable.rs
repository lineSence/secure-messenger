//! Native-platform reactor contract: an idle client never blocks another client.
#![allow(clippy::unwrap_used)]
use sm_core::relay::{Command, Status, encode_request, parse_response_header};
use sm_relay::server::{Config, Server};
use std::{
    io::{Read, Write},
    net::TcpStream,
    time::Duration,
};

#[test]
fn idle_accept_and_keepalive_work_with_one_and_multiple_reactors() {
    for workers in [1, 2, 4] {
        let mut config = Config::new(([127, 0, 0, 1], 0).into());
        config.io_workers = workers;
        let server = Server::start(config).unwrap();
        let idle = TcpStream::connect(server.addr()).unwrap();
        let mut active = TcpStream::connect(server.addr()).unwrap();
        active
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        active
            .set_write_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let frame = encode_request(Command::Ping, &[0; 32], &[]).unwrap();
        for _ in 0..8 {
            active.write_all(&frame).unwrap();
            let mut head = [0; 6];
            active
                .read_exact(&mut head)
                .unwrap_or_else(|e| panic!("workers={workers}: {e}"));
            assert_eq!(parse_response_header(&head).unwrap(), (Status::Ok, 0));
        }
        drop(idle);
        drop(active);
        server.stop();
    }
}
