//! Фаззинг разбора пропусков (ADR-136, ADR-146): чужие байты не роняют разбор; принятый пропуск
//! канонично — собранный заново с той же подписью совпадает с входом байт в байт.
#![no_main]

use libfuzzer_sys::fuzz_target;
use sm_core::pass::{GuestPass, QueuePass};

fuzz_target!(|data: &[u8]| {
    if let Ok((p, msg, sig)) = QueuePass::parse(data) {
        assert!(p.per_hour > 0);
        assert!(msg.ends_with(&p.body()));
        assert_eq!(p.with_signature(&sig), data);
    }
    if let Ok((g, msg, sig)) = GuestPass::parse(data) {
        assert!(g.per_hour > 0);
        assert!(msg.ends_with(&g.body()));
        assert_eq!(g.with_signature(&sig), data);
    }
});
