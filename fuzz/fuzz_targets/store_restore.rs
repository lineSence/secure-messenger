//! Фаззинг восстановления очереди и окон из снимка с диска (враждебные данные).
#![no_main]

use libfuzzer_sys::fuzz_target;
use sm_core::delivery::store::STORE_VERSION;
use sm_core::delivery::{Outbox, OutboxLimits, Seen};

fuzz_target!(|data: &[u8]| {
    // Та же загрузка, что в снимке: отметки не переносятся, снимок должен совпасть с входом.
    let boot = data
        .get(1..9)
        .and_then(|b| <[u8; 8]>::try_from(b).ok())
        .map_or(0, u64::from_be_bytes);
    if let Ok(o) = Outbox::restore(data, OutboxLimits::DEFAULT, boot, 0) {
        assert_eq!(data.first(), Some(&STORE_VERSION));
        assert!(o.len() <= OutboxLimits::DEFAULT.max_messages);
        assert!(o.bytes() <= OutboxLimits::DEFAULT.max_bytes);
        assert_eq!(o.encode(boot).as_deref(), Some(data), "каноничность очереди");
    }
    // Другая загрузка: перенос отметок не паникует.
    let _ = Outbox::restore(data, OutboxLimits::DEFAULT, boot.wrapping_add(1), u64::MAX);
    // Без усечения окон снимок должен совпасть с входом.
    if let Ok(s) = Seen::restore(data, usize::MAX) {
        assert_eq!(s.encode().as_deref(), Some(data), "каноничность окон");
    }
    let _ = Seen::restore(data, 1);
});
