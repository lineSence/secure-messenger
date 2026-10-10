//! Фаззинг сборщика частей Bluetooth LE (ADR-49): враждебная последовательность частей
//! не роняет сборщик и не даёт кадр больше предела; нарезка и сборка — взаимно обратны.
#![no_main]

use libfuzzer_sys::fuzz_target;
use sm_core::chunk::{HEADER, MAX_CHUNK, MAX_FRAME, Reassembler, parse_frame, split};

fuzz_target!(|data: &[u8]| {
    // Части из входа: первый байт каждой — её длина.
    let mut r = Reassembler::new();
    let mut rest = data;
    while let Some((&len, tail)) = rest.split_first() {
        let n = usize::from(len).saturating_mul(2).min(tail.len());
        let (chunk, tail) = tail.split_at(n);
        if let Ok(Some(frame)) = r.push(chunk) {
            assert!(!frame.is_empty() && frame.len() <= MAX_FRAME);
            let _ = parse_frame(&frame);
        }
        rest = tail;
    }
    // Нарезка и сборка: длина части — из первого байта.
    if let Some((&b, frame)) = data.split_first() {
        let chunk_len = HEADER + 1 + usize::from(b) % (MAX_CHUNK - HEADER);
        if let Some(parts) = split(frame, u16::from(b), chunk_len) {
            let mut r = Reassembler::new();
            let mut out = None;
            for p in &parts {
                assert!(p.len() <= chunk_len);
                out = r.push(p).unwrap();
            }
            assert_eq!(out.as_deref(), Some(frame));
        }
    }
});
