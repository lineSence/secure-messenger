//! External resource regression; no measurement code is included in the running node.
#![allow(
    clippy::unwrap_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#[cfg(target_os = "linux")]
#[test]
fn five_hundred_idle_connections_do_not_create_five_hundred_threads() {
    use sm_core::relay::{Command, encode_request};
    use sm_relay::server::{Config, Server};
    use std::{
        io::{Read, Write},
        net::TcpStream,
        time::{Duration, Instant},
    };
    fn threads() -> usize {
        std::fs::read_to_string("/proc/self/status")
            .unwrap()
            .lines()
            .find_map(|l| l.strip_prefix("Threads:"))
            .unwrap()
            .trim()
            .parse()
            .unwrap()
    }
    let before = threads();
    let mut config = Config::new(([127, 0, 0, 1], 0).into());
    config.max_connections = 512;
    config.io_workers = 2;
    let server = Server::start(config).unwrap();
    let mut clients = Vec::new();
    for _ in 0..500 {
        let mut c = TcpStream::connect(server.addr()).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        c.write_all(&encode_request(Command::Ping, &[0; 32], &[]).unwrap())
            .unwrap();
        let mut reply = [0; 6];
        c.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [1, 0, 0, 0, 0, 0]);
        clients.push(c);
    }
    assert!(threads() <= before.saturating_add(3));
    let started = Instant::now();
    server.stop();
    assert!(started.elapsed() < Duration::from_secs(2));
    for mut c in clients {
        assert!(matches!(c.read(&mut [0; 1]), Ok(0) | Err(_)));
    }
}
