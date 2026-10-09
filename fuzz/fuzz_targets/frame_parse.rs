#![no_main]
//! Фаззинг разбора кадров: любой вход — без паник; успешный разбор воспроизводится через encode.

use libfuzzer_sys::fuzz_target;
use sm_core::frame;

fuzz_target!(|data: &[u8]| {
    if let Ok(f) = frame::parse(data) {
        let again = frame::encode(f.kind, f.payload).expect("разобранный кадр должен кодироваться");
        assert_eq!(again.as_slice(), data);
    }
});
