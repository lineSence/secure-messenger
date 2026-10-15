//! Узел-реле (шаг 2 плана MVP, `wiki/architecture/relay.md`).
//!
//! - [`store`] — очереди блобов в памяти: TTL, пределы, повтор `FETCH` до `ACK`;
//! - [`server`] — сервер протокола [`sm_core::relay`], только на loopback-адресе: кадры по TCP
//!   или HTTP/1.1 от nginx;
//! - [`http`] — узкий разбор HTTP/1.1 от nginx (ADR-90);
//! - [`pass`] — пропуск на `NEW` (ADR-136) и ID отправителя очереди (ADR-134).
//!
//! Логов нет: узел ничего не пишет ни в stdout, ни на диск (semgrep `sm-relay-no-logs`).

pub mod http;
mod notify;
pub mod pass;
#[cfg(kani)]
mod proofs;
pub mod server;
pub mod settings;
pub mod store;
