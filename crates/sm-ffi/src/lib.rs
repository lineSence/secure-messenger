//! Обёртка ядра для Kotlin через `UniFFI` (ADR-46).
//!
//! Здесь только тонкий слой: типы и функции для Kotlin, логика — в `sm-core`.
//! Код связки (`extern "C"`) генерирует макрос `UniFFI`; своего `unsafe` здесь нет.

use std::sync::{Mutex, MutexGuard};

use sm_core::chunk::{self, ChunkError, Reassembler};
use sm_core::message::{Body, Message, MessageId};
use sm_core::transport::MailboxId;
use sm_signal::ble_part::{self, PartKey};

mod beacon;
mod chat;
mod delivery;
mod keys;
mod signal;
pub use beacon::{ble_add_nearby_token, ble_epoch_ms};
pub use chat::{
    ChatBody, ChatMessage, OpenedMessage, chat_encode, contact_mailbox, message_id_new,
};
pub use delivery::{DeliveryFfiError, DueMessage, MessageKey, OutboxQueue, SeenWindow};
pub use keys::{DeviceIdentity, KeyFfiError, PlatformKeyStorage, PlatformKeyWrapper};
pub use signal::{
    AcceptedSession, ContactHello, PairMessage, PlatformKv, QrContent, SignalFfiError,
    SignalSessions, StartedSession, beacon_key_new, ble_contact_token, ble_contact_tokens_around,
    ble_pairing_token, ble_part_key_contact, ble_part_key_nearby, ble_part_key_pairing,
    pair_frame_decline, pair_frame_decode, pair_frame_intro, pairing_secret_new, qr_decode,
    qr_encode, safety_number,
};

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

/// Наибольшая зашифрованная часть для Bluetooth LE, байт (значение атрибута GATT, ADR-49).
#[uniffi::export]
#[must_use]
pub fn chunk_max_len() -> u32 {
    // 512 всегда помещается в u32.
    u32::try_from(chunk::MAX_SEALED).unwrap_or(u32::MAX)
}

/// Наименьшая зашифрованная часть, байт: при меньшем MTU по Bluetooth не отправить (ADR-92).
#[uniffi::export]
#[must_use]
pub fn chunk_min_len() -> u32 {
    u32::try_from(ble_part::MIN_SEALED).unwrap_or(u32::MAX)
}

/// Режет кадр на зашифрованные части ровно по `sealed_len` байт ключом частей `part_key`
/// (`ble_part_key_*`), номер передачи — случайный (`sm_signal::ble_part::split_sealed`, ADR-92).
/// `None` — ключ не 32 байта, пустой кадр, кадр больше предела, `sealed_len` вне допустимого
/// или отказ шифрования.
#[uniffi::export]
#[must_use]
pub fn ble_parts_seal(part_key: &[u8], frame: &[u8], sealed_len: u32) -> Option<Vec<Vec<u8>>> {
    let key = PartKey::try_from(part_key).ok()?;
    ble_part::split_sealed(&key, frame, usize::try_from(sealed_len).ok()?)
        .ok()
        .flatten()
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
    /// Ключ частей не 32 байта.
    BadKey,
    /// Часть не открылась: чужой ключ, подмена, повреждение или неверная длина.
    NotOpened,
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
#[derive(Debug, uniffi::Object)]
pub struct ChunkReassembler {
    key: PartKey,
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
    /// Пустой сборщик частей, зашифрованных ключом `part_key` (`ble_part_key_*`).
    ///
    /// # Errors
    /// [`ChunkFfiError::BadKey`] — ключ не 32 байта.
    #[uniffi::constructor]
    pub fn new(part_key: &[u8]) -> Result<Self, ChunkFfiError> {
        Ok(Self {
            key: PartKey::try_from(part_key).map_err(|_| ChunkFfiError::BadKey)?,
            inner: Mutex::new(Reassembler::new()),
        })
    }

    /// Принимает зашифрованную часть; возвращает кадр, когда пришла последняя.
    ///
    /// # Errors
    /// [`ChunkFfiError`]; незаконченный кадр при этом отбрасывается (и при неоткрывшейся части:
    /// отправитель начнёт кадр заново с части 0).
    pub fn push(&self, sealed: &[u8]) -> Result<Option<Vec<u8>>, ChunkFfiError> {
        let mut r = self.lock();
        let Some(plain) = ble_part::open(&self.key, sealed) else {
            *r = Reassembler::new();
            return Err(ChunkFfiError::NotOpened);
        };
        r.push(&plain).map_err(ChunkFfiError::from)
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
        let key = ble_part_key_nearby().unwrap();
        let parts = ble_parts_seal(&key, &frame, chunk_max_len()).unwrap();
        assert!(parts.len() > 1);
        assert!(parts.iter().all(|p| p.len() == 512));
        let r = ChunkReassembler::new(&key).unwrap();
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
        let key = ble_part_key_nearby().unwrap();
        assert_eq!(ble_parts_seal(&key, &[1], chunk_min_len() - 1), None);
        assert_eq!(ble_parts_seal(&key, &[1], u32::MAX), None);
        assert_eq!(ble_parts_seal(&key[..31], &[1], 100), None);
        assert!(ChunkReassembler::new(&key[..31]).is_err());
        let r = ChunkReassembler::new(&key).unwrap();
        assert_eq!(r.push(&[0; 40]), Err(ChunkFfiError::NotOpened));
        let parts = ble_parts_seal(&key, &[7; 2000], 128).unwrap();
        assert_eq!(r.push(&parts[0]), Ok(None));
        assert!(r.in_progress());
        assert_eq!(r.push(&parts[2]), Err(ChunkFfiError::OutOfOrder));
        assert!(!r.in_progress(), "ошибка сбрасывает незаконченный кадр");
        assert_eq!(r.push(&parts[0]), Ok(None));
        let other = ChunkReassembler::new(&ble_part_key_pairing(&[1; 16]).unwrap()).unwrap();
        assert_eq!(
            other.push(&parts[0]),
            Err(ChunkFfiError::NotOpened),
            "чужой ключ"
        );
        let mut bad = parts[1].clone();
        bad[20] ^= 1;
        assert_eq!(r.push(&bad), Err(ChunkFfiError::NotOpened));
        assert!(!r.in_progress(), "неоткрывшаяся часть сбрасывает кадр");
    }
}
