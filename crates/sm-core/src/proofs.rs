//! Доказательства Kani: разбор недоверенных данных не паникует на любом входе ограниченной длины.
//! Запуск: `cargo kani -p sm-core -Z stubbing`.

use crate::{
    chunk, keystore, message, padding, relay,
    wire::{Reader, WireError},
};

/// Заглушка проверки текста для доказательства разбора: любой исход (Ok/Err) возможен.
/// Саму `wire::text` проверяет `text_check_total`, поэтому разбор доказывается по частям.
fn text_stub(_bytes: &[u8], _max: usize) -> Result<&'static str, WireError> {
    if kani::any() {
        Ok("")
    } else {
        Err(WireError::BadText)
    }
}

/// Любой буфер до 128 байт: разбор сообщения не паникует и не читает за границы.
/// Каноничность (decode → encode) проверяет фаззер `message_decode`.
#[kani::proof]
#[kani::stub(crate::wire::text, text_stub)]
#[kani::unwind(130)]
fn message_parse_total() {
    let len: usize = kani::any();
    kani::assume(len <= 128);
    let buf: [u8; 128] = kani::any();
    let _ = message::parse(&buf[..len]);
}

/// Проверка текста из сети не паникует на любых 6 байтах.
#[kani::proof]
#[kani::unwind(8)]
fn text_check_total() {
    let buf: [u8; 6] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 6);
    let max: usize = kani::any();
    if let Ok(s) = crate::wire::text(&buf[..len], max) {
        assert!(s.len() <= max && !s.contains('\0'));
    }
}

/// Чтение полей с длиной: никогда не выходит за буфер.
#[kani::proof]
#[kani::unwind(10)]
fn reader_never_overreads() {
    let buf: [u8; 8] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 8);
    let max: usize = kani::any();
    let mut r = Reader::new(&buf[..len]);
    if let Ok(b) = r.bytes_u16(max) {
        assert!(b.len() <= max && b.len() + 2 <= len);
    }
    assert!(r.remaining() <= len);
}

/// Снятие выравнивания: не паникует и возвращает часть входа.
#[kani::proof]
#[kani::unwind(66)]
fn unpad_total() {
    let buf: [u8; 64] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 64);
    if let Ok(d) = padding::unpad(&buf[..len]) {
        assert!(d.len() < len);
    }
}

/// Разбор записи хранилища ключей не паникует.
#[kani::proof]
#[kani::unwind(10)]
fn keystore_record_total() {
    let buf: [u8; 8] = kani::any();
    let len: usize = kani::any();
    kani::assume(len <= 8);
    let _ = keystore::decode_record(&buf[..len]);
}

/// Любые 38 байт: разбор заголовка запроса к узлу-реле не паникует, длина — в пределах команды.
#[kani::proof]
#[kani::unwind(40)]
fn relay_request_header_total() {
    let buf: [u8; relay::REQUEST_HEADER] = kani::any();
    if let Ok(h) = relay::parse_request_header(&buf) {
        assert!(h.len <= relay::MAX_BLOB);
    }
}

/// Любые 6 байт: разбор заголовка ответа узла не паникует, длина не больше предела.
#[kani::proof]
#[kani::unwind(8)]
fn relay_response_header_total() {
    let buf: [u8; relay::RESPONSE_HEADER] = kani::any();
    if let Ok((_, len)) = relay::parse_response_header(&buf) {
        assert!(len <= relay::MAX_RESPONSE_DATA);
    }
}

/// Любые две части до 12 байт: сборщик Bluetooth LE не паникует, кадр не пустой и в пределах;
/// после ошибки незаконченного кадра нет.
#[kani::proof]
#[kani::unwind(14)]
fn chunk_push_total() {
    let mut r = chunk::Reassembler::new();
    for _ in 0..2 {
        let buf: [u8; 12] = kani::any();
        let len: usize = kani::any();
        kani::assume(len <= 12);
        match r.push(&buf[..len]) {
            Ok(Some(frame)) => assert!(!frame.is_empty() && frame.len() <= chunk::MAX_FRAME),
            Ok(None) => {}
            Err(_) => assert!(!r.in_progress()),
        }
    }
}
