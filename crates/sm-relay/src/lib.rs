//! Узел-реле (шаг 2 плана MVP, `wiki/architecture/relay.md`).
//!
//! - [`store`] — очереди блобов в памяти: TTL, пределы, повтор `FETCH` до `ACK`;
//! - [`server`] — TCP-сервер протокола [`sm_core::relay`], только на loopback-адресе.
//!
//! Логов нет: узел ничего не пишет ни в stdout, ни на диск (semgrep `sm-relay-no-logs`).

pub mod server;
pub mod store;
