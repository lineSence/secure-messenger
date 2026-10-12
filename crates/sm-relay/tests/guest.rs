//! Билеты и гостевые пропуска (ADR-146): пригласивший берёт билет по своему пропуску, гость
//! меняет его на гостевой пропуск и дальше сам создаёт очереди; пропуск переживает перезапуск
//! узла (ключ узла постоянный) и отзывается вместе с пропуском владельца.
// Помощники теста вне #[test]: на них не действует allow-*-in-tests из clippy.toml.
#![allow(clippy::unwrap_used)]

use std::net::SocketAddr;
use std::time::Duration;

use sm_core::pass::{GUEST_PASS_LEN, GuestPass, TICKET_LEN};
use sm_core::relay::RelayTcp;
use sm_core::transport::{MailboxId, TransportError};
use sm_relay::pass::{GUEST_PER_HOUR, GuestKey};
use sm_relay::server::{Config, Server};

mod common;

static ROOT: std::sync::LazyLock<common::Root> = std::sync::LazyLock::new(common::Root::new);

const T: Duration = Duration::from_secs(5);

fn key(hex_byte: &str) -> &'static GuestKey {
    Box::leak(Box::new(GuestKey::from_hex(&hex_byte.repeat(32)).unwrap()))
}

fn start(guest: Option<&'static GuestKey>, revoked: &'static [[u8; 16]]) -> Server {
    let mut c = Config::new(SocketAddr::from(([127, 0, 0, 1], 0)));
    c.guest_key = guest;
    c.revoked = revoked;
    Server::start(ROOT.config(c)).unwrap()
}

/// Билет по пропуску `by` и сразу обмен на гостевой пропуск.
fn invite(c: &mut RelayTcp, by: &[u8]) -> Vec<u8> {
    let ticket = c.ticket(by).unwrap();
    c.guest_pass(&ticket).unwrap()
}

fn rid(n: u8) -> MailboxId {
    MailboxId([n; 32])
}

#[test]
fn ticket_becomes_guest_pass_that_makes_queues() {
    let server = start(Some(key("ab")), &[]);
    let mut host = RelayTcp::new(server.addr(), T);
    let mut guest = RelayTcp::new(server.addr(), T);
    let owner = ROOT.pass(1, 100);

    let ticket = host.ticket(&owner).unwrap();
    let pass = guest.guest_pass(&ticket).unwrap();
    assert_eq!(pass.len(), GUEST_PASS_LEN);
    let (g, _, _) = GuestPass::parse(&pass).unwrap();
    // Родитель — пропуск владельца; лимит не выше гостевого предела.
    assert_eq!((g.parent, g.per_hour), ([1; 16], GUEST_PER_HOUR));
    assert!(guest.create_queue(&rid(1), &pass).is_ok());

    // Повтор обмена (ответ потерялся) — тот же пропуск.
    assert_eq!(guest.guest_pass(&ticket).unwrap(), pass);
    // Неизвестный билет — отказ.
    assert!(matches!(
        guest.guest_pass(&[9; TICKET_LEN]),
        Err(TransportError::Rejected)
    ));
    server.stop();
}

#[test]
fn guest_pass_is_limited_and_signed_by_this_node_only() {
    let server = start(Some(key("ab")), &[]);
    let mut c = RelayTcp::new(server.addr(), T);
    let owner = ROOT.pass(1, 100);
    let pass = invite(&mut c, &owner);
    for n in 1..=u8::try_from(GUEST_PER_HOUR).unwrap() {
        c.create_queue(&rid(n), &pass).unwrap();
    }
    // Лимит гостя исчерпан, лимит владельца и других гостей — нет.
    assert!(c.create_queue(&rid(100), &pass).is_err());
    assert!(c.create_queue(&rid(100), &owner).is_ok());
    let second = invite(&mut c, &owner);
    assert_ne!(second, pass);
    assert!(c.create_queue(&rid(101), &second).is_ok());
    server.stop();

    // Другой узел (другой ключ) гостевой пропуск не принимает; узел без ключа — тоже.
    for other in [Some(key("cd")), None] {
        let server = start(other, &[]);
        let mut c = RelayTcp::new(server.addr(), T);
        assert!(matches!(
            c.create_queue(&rid(1), &pass),
            Err(TransportError::Rejected)
        ));
        server.stop();
    }
}

#[test]
fn tickets_count_against_the_issuing_pass() {
    let server = start(Some(key("ab")), &[]);
    let mut c = RelayTcp::new(server.addr(), T);
    let owner = ROOT.pass(7, 2);
    assert!(c.ticket(&owner).is_ok());
    assert!(c.ticket(&owner).is_ok());
    assert!(c.ticket(&owner).is_err());
    // Пропуск владельца с исчерпанным лимитом очередей тоже не даёт билет.
    assert!(c.create_queue(&rid(1), &owner).is_err());
    // Чужой пропуск билет не даёт.
    let stranger = common::Root::new().pass(8, 5);
    assert!(matches!(c.ticket(&stranger), Err(TransportError::Rejected)));
    server.stop();
}

#[test]
fn guest_invites_further_and_chain_keeps_parent() {
    let server = start(Some(key("ab")), &[]);
    let mut c = RelayTcp::new(server.addr(), T);
    let guest1 = invite(&mut c, &ROOT.pass(3, 100));
    // Гость сам приглашает: билет по гостевому пропуску, родитель тот же.
    let guest2 = invite(&mut c, &guest1);
    let (g2, _, _) = GuestPass::parse(&guest2).unwrap();
    assert_eq!(g2.parent, [3; 16]);
    assert!(c.create_queue(&rid(1), &guest2).is_ok());
    server.stop();
}

#[test]
fn revoking_the_parent_revokes_guests() {
    let server = start(Some(key("ab")), &[]);
    let mut c = RelayTcp::new(server.addr(), T);
    let guest = invite(&mut c, &ROOT.pass(4, 100));
    assert!(c.create_queue(&rid(1), &guest).is_ok());
    server.stop();

    let revoked: &'static [[u8; 16]] = Box::leak(Box::new([[4; 16]]));
    let server = start(Some(key("ab")), revoked);
    let mut c = RelayTcp::new(server.addr(), T);
    assert!(matches!(
        c.create_queue(&rid(2), &guest),
        Err(TransportError::Rejected)
    ));
    assert!(matches!(c.ticket(&guest), Err(TransportError::Rejected)));
    server.stop();
}

#[test]
fn guest_pass_survives_node_restart_but_tickets_do_not() {
    let k = key("ab");
    let server = start(Some(k), &[]);
    let addr = server.addr();
    let mut c = RelayTcp::new(addr, T);
    let ticket = c.ticket(&ROOT.pass(5, 100)).unwrap();
    let kept = c.ticket(&ROOT.pass(5, 100)).unwrap();
    let pass = c.guest_pass(&ticket).unwrap();
    drop(c);
    server.stop();

    let mut cfg = Config::new(addr);
    cfg.guest_key = Some(k);
    let server = Server::start(ROOT.config(cfg)).unwrap();
    let mut c = RelayTcp::new(addr, T);
    // Тот же ключ узла: пропуск принят; билет, не обмененный до перезапуска, пропал.
    assert!(c.create_queue(&rid(1), &pass).is_ok());
    assert!(matches!(c.guest_pass(&kept), Err(TransportError::Rejected)));
    server.stop();
}

#[test]
fn node_without_key_issues_no_tickets() {
    let server = start(None, &[]);
    let mut c = RelayTcp::new(server.addr(), T);
    assert!(matches!(
        c.ticket(&ROOT.pass(6, 100)),
        Err(TransportError::Unreachable)
    ));
    assert!(matches!(
        c.guest_pass(&[1; TICKET_LEN]),
        Err(TransportError::Unreachable)
    ));
    // Обычный NEW работает как раньше.
    assert!(c.create_queue(&rid(1), &ROOT.pass(6, 100)).is_ok());
    server.stop();
}
