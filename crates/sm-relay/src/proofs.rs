//! Доказательства Kani: разбор заголовка HTTP от nginx не паникует на любом входе ограниченной
//! длины и не пропускает тело больше предела. Запуск: `cargo kani -p sm-relay`.

use crate::http;

/// Любые 20 байт заголовка: разбор не паникует, длина тела — не больше `MAX_BODY`.
/// Длинные заголовки и каноничность проверяют тесты и фаззер `relay_http`.
#[kani::proof]
#[kani::unwind(22)]
fn http_head_total() {
    let buf: [u8; 20] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 20);
    if let Ok(h) = http::parse_head(&buf[..len]) {
        assert!(h.content_length <= http::MAX_BODY);
    }
}

/// Поиск конца заголовка не паникует и указывает на `\r\n\r\n` внутри буфера.
#[kani::proof]
#[kani::unwind(14)]
fn http_head_end_total() {
    let buf: [u8; 12] = kani::any();
    if let Some(i) = http::head_end(&buf) {
        assert!(buf.get(i..i + 4) == Some(http::HEAD_END));
    }
}
