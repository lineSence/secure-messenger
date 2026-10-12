//! Бинарник узла. Адрес — переменная `SM_RELAY_LISTEN` (по умолчанию `127.0.0.1:7400`);
//! `SM_RELAY_MODE=http` — приём HTTP/1.1 от nginx (ADR-90), иначе кадры прямо по TCP.
//! `SM_RELAY_REVOKED` — отозванные пропуска на `NEW` (ADR-136): ID по 16 байт в HEX через запятую,
//! тот же список, что в подписанной записи узла.
//! `SM_RELAY_GUEST_KEY_FILE` — файл с постоянным ключом гостевых пропусков (ADR-146): 64 HEX-знака;
//! если не задан, берётся `$CREDENTIALS_DIRECTORY/guest-key` (systemd `LoadCredential`); без
//! ключа узел не выдаёт билеты, ссылки идут без них.
//! В stdout/stderr — только короткая ошибка запуска без подробностей (логов нет, `relay.md`).

use std::io::Write;
use std::net::SocketAddr;
use std::process::ExitCode;

use sm_relay::pass::GuestKey;
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

/// Ключ гостевых пропусков из файла: `Ok(None)` — файл не задан; `Err` — не читается или не ключ.
fn load_guest_key() -> Result<Option<GuestKey>, ()> {
    let path = std::env::var_os("SM_RELAY_GUEST_KEY_FILE")
        .map(std::path::PathBuf::from)
        .or_else(|| {
            let p = std::path::PathBuf::from(std::env::var_os("CREDENTIALS_DIRECTORY")?)
                .join("guest-key");
            p.exists().then_some(p)
        });
    let Some(path) = path else {
        return Ok(None);
    };
    let text = std::fs::read_to_string(path).map_err(|_| ())?;
    GuestKey::from_hex(&text).map(Some).ok_or(())
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
    match load_guest_key() {
        // Ключ нужен до конца процесса.
        Ok(Some(key)) => config.guest_key = Some(Box::leak(Box::new(key))),
        Ok(None) => {}
        Err(()) => return fail("SM_RELAY_GUEST_KEY_FILE: файл с ключом (64 HEX-знака)"),
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
