//! Бинарник узла. Адрес — переменная `SM_RELAY_LISTEN` (по умолчанию `127.0.0.1:7400`);
//! `SM_RELAY_MODE=http` — приём HTTP/1.1 от nginx (ADR-90), иначе кадры прямо по TCP.
//! `SM_RELAY_REVOKED` — отозванные пропуска на `NEW` (ADR-136): ID по 16 байт в HEX через запятую,
//! тот же список, что в подписанной записи узла.
//! В stdout/stderr — только короткая ошибка запуска без подробностей (логов нет, `relay.md`).

use std::io::Write;
use std::net::SocketAddr;
use std::process::ExitCode;

use sm_relay::server::{Config, Server};
use sm_relay::store::PassId;

const DEFAULT_LISTEN: &str = "127.0.0.1:7400";

fn fail(msg: &str) -> ExitCode {
    let _ = std::io::stderr().write_all(format!("sm-relay: {msg}\n").as_bytes());
    ExitCode::FAILURE
}

/// ID пропусков из `a1b2…,c3d4…`; `None` — не HEX или не 16 байт.
fn parse_revoked(list: &str) -> Option<Vec<PassId>> {
    list.split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|h| {
            let bytes = h
                .as_bytes()
                .chunks(2)
                .map(|p| u8::from_str_radix(std::str::from_utf8(p).ok()?, 16).ok())
                .collect::<Option<Vec<u8>>>()?;
            PassId::try_from(bytes).ok()
        })
        .collect()
}

fn main() -> ExitCode {
    let listen = std::env::var("SM_RELAY_LISTEN").unwrap_or_else(|_| DEFAULT_LISTEN.to_owned());
    let Ok(addr) = listen.parse::<SocketAddr>() else {
        return fail("неверный адрес");
    };
    let mut config = Config::new(addr);
    config.http = match std::env::var("SM_RELAY_MODE").as_deref() {
        Ok("http") => true,
        Ok("tcp") | Err(_) => false,
        Ok(_) => return fail("SM_RELAY_MODE: http или tcp"),
    };
    if let Ok(list) = std::env::var("SM_RELAY_REVOKED") {
        let Some(revoked) = parse_revoked(&list) else {
            return fail("SM_RELAY_REVOKED: ID пропусков (32 HEX) через запятую");
        };
        // Список нужен до конца процесса.
        config.revoked = Box::leak(revoked.into_boxed_slice());
    }
    match Server::start(config) {
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
