//! Обёртка ядра для Kotlin через `UniFFI` (ADR-46).
//!
//! Здесь только тонкий слой: типы и функции для Kotlin, логика — в `sm-core`.
//! Код связки (`extern "C"`) генерирует макрос `UniFFI`; своего `unsafe` здесь нет.

use std::sync::{Mutex, MutexGuard};

use sm_core::chunk::{self, ChunkError, Reassembler};
use sm_core::message::{Body, Message, MessageId};
use sm_core::transport::MailboxId;

mod beacon;
mod delivery;
pub use beacon::{ble_add_nearby_token, ble_epoch_ms};
pub use delivery::{DeliveryFfiError, DueMessage, MessageKey, OutboxQueue, SeenWindow};

uniffi::setup_scaffolding!();

/// Версия ядра.
#[uniffi::export]
#[must_use]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// Самопроверка при запуске: сообщение кодируется и разбирается обратно без потерь, и libsignal
/// проходит полный круг в памяти (сессия PQXDH, сообщение и ответ — `sm_signal::self_check`).
/// `false` — сборка ядра повреждена, приложение не должно работать с ключами.
#[uniffi::export]
#[must_use]
pub fn core_self_check() -> bool {
    let m = Message {
        id: MessageId([7; 16]),
        sent_at_ms: 0,
        body: Body::Text("проверка".to_owned()),
    };
    let message_ok = m
        .encode()
        .ok()
        .and_then(|buf| Message::decode(&buf).ok())
        .is_some_and(|back| back == m);
    message_ok && sm_signal::self_check()
}

/// Наибольшая часть для Bluetooth LE, байт (значение атрибута GATT, ADR-49).
#[uniffi::export]
#[must_use]
pub fn chunk_max_len() -> u32 {
    // 512 всегда помещается в u32.
    u32::try_from(chunk::MAX_CHUNK).unwrap_or(u32::MAX)
}

/// Режет кадр на части не длиннее `chunk_len` байт (с заголовком), см. `sm_core::chunk::split`.
/// `None` — пустой кадр, кадр больше предела или `chunk_len` вне допустимого.
#[uniffi::export]
#[must_use]
pub fn chunk_split(frame: &[u8], transfer: u16, chunk_len: u32) -> Option<Vec<Vec<u8>>> {
    chunk::split(frame, transfer, usize::try_from(chunk_len).ok()?)
}

/// Кадр «ящик + блоб» (ADR-49).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChunkFrame {
    /// Ящик получателя, 32 байта.
    pub mailbox: Vec<u8>,
    /// Блоб (зашифрованный).
    pub blob: Vec<u8>,
}

/// Собирает кадр «ящик + блоб». `None` — ящик не 32 байта, пустой блоб или блоб больше предела.
#[uniffi::export]
#[must_use]
pub fn encode_frame(mailbox: &[u8], blob: &[u8]) -> Option<Vec<u8>> {
    let id = MailboxId(<[u8; 32]>::try_from(mailbox).ok()?);
    chunk::encode_frame(&id, blob)
}

/// Разбирает кадр «ящик + блоб». `None` — кадр короче ящика, пустой блоб или блоб больше предела.
#[uniffi::export]
#[must_use]
pub fn parse_frame(frame: &[u8]) -> Option<ChunkFrame> {
    let (id, blob) = chunk::parse_frame(frame).ok()?;
    Some(ChunkFrame {
        mailbox: id.0.to_vec(),
        blob: blob.to_vec(),
    })
}

/// Ошибка части из сети; незаконченный кадр при ней отброшен (см. `sm_core::chunk::ChunkError`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum ChunkFfiError {
    /// Часть короче заголовка, без данных или длиннее предела.
    BadLength,
    /// Неизвестная версия.
    BadVersion,
    /// Ноль частей или номер части не меньше их числа.
    BadIndex,
    /// Часть не по порядку.
    OutOfOrder,
    /// Кадр вышел бы больше предела.
    TooLarge,
}

impl From<ChunkError> for ChunkFfiError {
    fn from(e: ChunkError) -> Self {
        match e {
            ChunkError::BadLength => Self::BadLength,
            ChunkError::BadVersion => Self::BadVersion,
            ChunkError::BadIndex => Self::BadIndex,
            ChunkError::OutOfOrder => Self::OutOfOrder,
            ChunkError::TooLarge => Self::TooLarge,
        }
    }
}

impl std::fmt::Display for ChunkFfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for ChunkFfiError {}

/// Сборщик частей одного соединения (`sm_core::chunk::Reassembler`). Один объект — на одно
/// соединение; при разрыве объект удаляют вместе с незаконченным кадром.
#[derive(Debug, Default, uniffi::Object)]
pub struct ChunkReassembler {
    inner: Mutex<Reassembler>,
}

impl ChunkReassembler {
    /// Сборщик под замком. Отравленный замок (паника в другом потоке) — состояние не
    /// доверяем: незаконченный кадр отбрасываем и начинаем с пустого сборщика.
    fn lock(&self) -> MutexGuard<'_, Reassembler> {
        match self.inner.lock() {
            Ok(g) => g,
            Err(poisoned) => {
                let mut g = poisoned.into_inner();
                *g = Reassembler::new();
                self.inner.clear_poison();
                g
            }
        }
    }
}

#[uniffi::export]
impl ChunkReassembler {
    /// Пустой сборщик.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Принимает часть; возвращает кадр, когда пришла последняя.
    ///
    /// # Errors
    /// [`ChunkFfiError`]; незаконченный кадр при этом отбрасывается.
    pub fn push(&self, chunk: &[u8]) -> Result<Option<Vec<u8>>, ChunkFfiError> {
        self.lock().push(chunk).map_err(ChunkFfiError::from)
    }

    /// Есть ли незаконченный кадр.
    #[must_use]
    pub fn in_progress(&self) -> bool {
        self.lock().in_progress()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn self_check_passes() {
        assert!(core_self_check());
        assert_eq!(core_version(), "0.0.1");
    }

    #[test]
    fn chunk_roundtrip_through_ffi() {
        let blob: Vec<u8> = (0..3000u32)
            .map(|i| u8::try_from(i % 251).unwrap())
            .collect();
        let frame = encode_frame(&[9; 32], &blob).unwrap();
        let parts = chunk_split(&frame, 5, chunk_max_len()).unwrap();
        assert!(parts.len() > 1);
        let r = ChunkReassembler::new();
        let mut out = None;
        for p in parts {
            assert!(out.is_none());
            out = r.push(&p).unwrap();
        }
        assert!(!r.in_progress());
        let got = parse_frame(&out.unwrap()).unwrap();
        assert_eq!(got.mailbox, vec![9; 32]);
        assert_eq!(got.blob, blob);
    }

    #[test]
    fn ffi_rejects_bad_input() {
        assert_eq!(encode_frame(&[0; 31], &[1]), None);
        assert_eq!(encode_frame(&[0; 33], &[1]), None);
        assert_eq!(encode_frame(&[0; 32], &[]), None);
        assert_eq!(parse_frame(&[0; 32]), None);
        assert_eq!(chunk_split(&[1], 0, 7), None);
        assert_eq!(chunk_split(&[1], 0, u32::MAX), None);
        let r = ChunkReassembler::new();
        assert_eq!(
            r.push(&[1, 0, 0, 0, 0, 0, 1]),
            Err(ChunkFfiError::BadLength)
        );
        let parts = chunk_split(&[7; 2000], 1, 100).unwrap();
        assert_eq!(r.push(&parts[0]), Ok(None));
        assert!(r.in_progress());
        assert_eq!(r.push(&parts[2]), Err(ChunkFfiError::OutOfOrder));
        assert!(!r.in_progress(), "ошибка сбрасывает незаконченный кадр");
    }
}
