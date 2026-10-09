#![no_main]
//! Фаззинг формата сообщения: любой вход — без паник; успешный разбор канонический.

use libfuzzer_sys::fuzz_target;
use sm_core::{message::Message, padding};

fuzz_target!(|data: &[u8]| {
    if let Ok(m) = Message::decode(data) {
        assert_eq!(m.encode().expect("разобранное кодируется").as_slice(), data);
    }
    // Тот же вход как выровненный шифротекст после расшифровки.
    if let Ok(inner) = padding::unpad(data) {
        assert_eq!(padding::pad(inner).expect("снятое выравнивается").as_slice(), data);
        let _ = Message::decode(inner);
    }
});
