//! Фаззинг разбора записи узла (ADR-81): чужие байты не роняют разбор, принятая запись
//! допустима и канонична — собранная заново совпадает с входом байт в байт.
#![no_main]

use libfuzzer_sys::fuzz_target;
use sm_core::node::NodeRecord;

fuzz_target!(|data: &[u8]| {
    if let Ok(r) = NodeRecord::decode(data) {
        assert!(r.is_valid());
        assert_eq!(r.encode().as_deref(), Some(data));
    }
});
