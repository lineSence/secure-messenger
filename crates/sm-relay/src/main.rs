//! Бинарник узла. Адрес — переменная `SM_RELAY_LISTEN` (по умолчанию `127.0.0.1:7400`).
//! В stdout/stderr — только короткая ошибка запуска без подробностей (логов нет, `relay.md`).

use std::io::Write;
use std::net::SocketAddr;
use std::process::ExitCode;

use sm_relay::server::{Config, Server};

const DEFAULT_LISTEN: &str = "127.0.0.1:7400";

fn fail(msg: &str) -> ExitCode {
    let _ = std::io::stderr().write_all(format!("sm-relay: {msg}\n").as_bytes());
    ExitCode::FAILURE
}

fn main() -> ExitCode {
    let listen = std::env::var("SM_RELAY_LISTEN").unwrap_or_else(|_| DEFAULT_LISTEN.to_owned());
    let Ok(addr) = listen.parse::<SocketAddr>() else {
        return fail("неверный адрес");
    };
    match Server::start(Config::new(addr)) {
        Ok(server) => {
            server.wait();
            ExitCode::SUCCESS
        }
        Err(e) if e.kind() == std::io::ErrorKind::InvalidInput => {
            fail("только loopback-адрес (127.0.0.1)")
        }
        Err(_) => fail("не удалось открыть порт"),
    }
}
