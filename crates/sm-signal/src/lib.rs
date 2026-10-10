//! Шифрование переписки 1:1 через libsignal (ADR-2, ADR-47, ADR-55).
//!
//! Свою криптографию не пишем (ADR-1): здесь только вызовы `libsignal-protocol` — PQXDH
//! (X25519 + ML-KEM/Kyber1024) для начала сессии и Double Ratchet со SPQR для сообщений.
//! Случайные числа — только системный генератор (`OsRng`).
//!
//! Пункт 3 плана `0.0.1-alpha` подключает библиотеку и проверяет её на устройстве
//! ([`self_check`]); пункт 4 — ключ личности устройства ([`identity`]). Контакты и постоянные
//! хранилища — следующие шаги.

use futures_util::FutureExt as _;
use libsignal_protocol::SignalProtocolError;

pub mod beacon;
pub mod contact;
pub mod identity;
pub mod pairing;
pub mod store;
pub use identity::DeviceIdentity;
/// Ошибка libsignal (для разбора причин отказа снаружи).
pub use libsignal_protocol::SignalProtocolError as ProtocolError;

/// Ошибка libsignal или наших проверок.
#[derive(Debug)]
pub enum SignalError {
    /// Ошибка libsignal.
    Protocol(SignalProtocolError),
    /// Не удалось подписать ключ.
    Signing,
    /// Хранилище ответило асинхронно — с синхронными хранилищами так быть не должно.
    Pending,
    /// Расшифровано не то, что зашифровано.
    Mismatch,
    /// Секрет ключа личности повреждён или не той версии.
    BadIdentity,
    /// Примитив `signal-crypto` отказал (HMAC).
    Crypto,
    /// Входные данные не разобрались (карточка, сообщение, адрес).
    BadInput,
    /// Хранилище платформы отказало.
    Store,
}

impl From<SignalProtocolError> for SignalError {
    fn from(e: SignalProtocolError) -> Self {
        Self::Protocol(e)
    }
}

/// Выполняет будущее libsignal, которое со синхронными хранилищами готово сразу.
fn now<T>(
    f: impl core::future::Future<Output = Result<T, SignalProtocolError>>,
) -> Result<T, SignalError> {
    f.now_or_never()
        .ok_or(SignalError::Pending)?
        .map_err(SignalError::from)
}

/// Полный круг в памяти через наши хранилища: карточка Боба, сессия Алисы (PQXDH), первое
/// сообщение, ответ Боба, ещё одно сообщение Алисы.
///
/// # Errors
/// [`SignalError`] — libsignal или наши хранилища не работают в этой сборке; приложение не
/// должно продолжать.
pub fn round_trip() -> Result<(), SignalError> {
    let (alice_id, bob_id) = (DeviceIdentity::generate(), DeviceIdentity::generate());
    let (alice_kv, bob_kv) = (store::MemKv::default(), store::MemKv::default());
    let alice = store::Stores::new(&alice_id, &alice_kv);
    let bob = store::Stores::new(&bob_id, &bob_kv);

    let card = contact::new_card(&bob)?;
    let started = contact::start_session(&alice, &card, "проверка".as_bytes())?;
    let accepted = contact::accept_session(&bob, &started.ciphertext, None)?;
    if accepted.plaintext != "проверка".as_bytes() || accepted.identity_key != alice_id.public_key()
    {
        return Err(SignalError::Mismatch);
    }
    let reply = contact::encrypt(&bob, &accepted.contact, "ответ".as_bytes())?;
    if contact::decrypt(&alice, &started.contact, &reply)? != "ответ".as_bytes() {
        return Err(SignalError::Mismatch);
    }
    let next = contact::encrypt(&alice, &started.contact, b"3")?;
    if contact::decrypt(&bob, &accepted.contact, &next)? != b"3" {
        return Err(SignalError::Mismatch);
    }
    Ok(())
}

/// Самопроверка при запуске: [`round_trip`] прошёл.
#[must_use]
pub fn self_check() -> bool {
    round_trip().is_ok()
}

#[cfg(test)]
mod tests;
