//! Фаззинг разбора заголовка HTTP от nginx (ADR-90): враждебные байты не роняют узел,
//! длина тела не выходит за предел, конец заголовка находится там, где он есть.
#![no_main]

use libfuzzer_sys::fuzz_target;
use sm_relay::http::{HEAD_END, MAX_BODY, head_end, parse_head};

fuzz_target!(|data: &[u8]| {
    if let Ok(h) = parse_head(data) {
        assert!(h.content_length <= MAX_BODY);
        // Принятый заголовок — одна строка запроса и поля через CRLF, без голых CR/LF и NUL.
        assert!(data.starts_with(b"POST / HTTP/1.1\r\n"));
        assert!(!data.contains(&0));
    }
    if let Some(i) = head_end(data) {
        assert_eq!(data.get(i..i + HEAD_END.len()), Some(HEAD_END));
        assert!(head_end(data.get(..i + HEAD_END.len() - 1).unwrap_or_default()).is_none());
    }
});
