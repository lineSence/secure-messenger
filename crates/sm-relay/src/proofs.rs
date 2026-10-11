//! Доказательства Kani: разбор заголовка HTTP от nginx не паникует на любом входе ограниченной
//! длины и не пропускает тело больше предела. Запуск: `cargo kani -p sm-relay -Z stubbing`.
//!
//! Разбор доказывается по частям, как `message_parse_total` в `sm-core`: в `http_head_total`
//! строка поля, имя поля, `Content-Length` и `Connection` заменены заглушками с тем же
//! контрактом, а сами эти функции доказываются отдельно — на более длинных входах.

use crate::http::{self, HttpError};

/// Заглушка [`http::field`]: любой исход, имя и значение — части строки.
fn field_stub(line: &[u8]) -> Result<(&[u8], &[u8]), HttpError> {
    if kani::any() {
        return Err(HttpError::Field);
    }
    let i: usize = kani::any();
    let j: usize = kani::any();
    kani::assume(i <= j && j <= line.len());
    Ok((&line[..i], &line[j..]))
}

/// Заглушка [`http::parse_length`]: любой исход, длина — не больше `MAX_BODY`
/// (это доказывает `http_length_total`).
fn length_stub(_v: &[u8]) -> Result<usize, HttpError> {
    match kani::any::<u8>() {
        0 => Err(HttpError::ContentLength),
        1 => Err(HttpError::TooBig),
        _ => {
            let n: usize = kani::any();
            kani::assume(n <= http::MAX_BODY);
            Ok(n)
        }
    }
}

/// Заглушка [`http::has_close`]: любой ответ.
fn close_stub(_v: &[u8]) -> bool {
    kani::any()
}

/// Заглушка [`http::kind`]: любой вид поля.
fn kind_stub(_name: &[u8]) -> http::Kind {
    match kani::any::<u8>() {
        0 => http::Kind::ContentLength,
        1 => http::Kind::TransferEncoding,
        2 => http::Kind::Connection,
        _ => http::Kind::Other,
    }
}

/// Любые 20 байт заголовка: разбор не паникует, длина тела — не больше `MAX_BODY`.
/// Длинные заголовки и каноничность проверяют тесты и фаззер `relay_http`.
#[kani::proof]
#[kani::stub(crate::http::field, field_stub)]
#[kani::stub(crate::http::kind, kind_stub)]
#[kani::stub(crate::http::parse_length, length_stub)]
#[kani::stub(crate::http::has_close, close_stub)]
#[kani::unwind(22)]
fn http_head_total() {
    let buf: [u8; 20] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 20);
    if let Ok(h) = http::parse_head(&buf[..len]) {
        assert!(h.content_length <= http::MAX_BODY);
    }
}

/// Любая строка поля до 24 байт: не паникует, имя и значение — части строки.
#[kani::proof]
#[kani::unwind(26)]
fn http_field_total() {
    let buf: [u8; 24] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 24);
    if let Ok((name, value)) = http::field(&buf[..len]) {
        assert!(!name.is_empty());
        assert!(name.len() + value.len() < len);
    }
}

/// Любое имя поля до 24 байт: распознавание не паникует.
#[kani::proof]
#[kani::unwind(26)]
fn http_kind_total() {
    let buf: [u8; 24] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 24);
    let _ = http::kind(&buf[..len]);
}

/// Любое значение `Content-Length` до 12 байт: не паникует и не больше `MAX_BODY`.
#[kani::proof]
#[kani::unwind(14)]
fn http_length_total() {
    let buf: [u8; 12] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 12);
    if let Ok(n) = http::parse_length(&buf[..len]) {
        assert!(n <= http::MAX_BODY);
    }
}

/// Любое значение `Connection` до 10 байт: поиск `close` не паникует.
#[kani::proof]
#[kani::unwind(14)]
fn http_close_total() {
    let buf: [u8; 10] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 10);
    let _ = http::has_close(&buf[..len]);
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
