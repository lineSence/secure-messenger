//! Шифрование переписки 1:1 через libsignal (ADR-2, ADR-47, ADR-55).
//!
//! Свою криптографию не пишем (ADR-1): здесь только вызовы `libsignal-protocol` — PQXDH
//! (X25519 + ML-KEM/Kyber1024) для начала сессии и Double Ratchet со SPQR для сообщений.
//! Случайные числа — только системный генератор (`OsRng`).
//!
//! Пункт 3 плана `0.0.1-alpha` подключает библиотеку и проверяет её на устройстве
//! ([`self_check`]); пункт 4 — ключ личности устройства ([`identity`]). Контакты и постоянные
//! хранилища — следующие шаги.

use std::time::SystemTime;

use futures_util::FutureExt as _;
use libsignal_protocol::{
    CiphertextMessage, DeviceId, IdentityKeyPair, InMemSignalProtocolStore, KeyPair,
    KyberPreKeyRecord, PreKeyBundle, PreKeyRecord, ProtocolAddress, SignalProtocolError,
    SignedPreKeyRecord, Timestamp, kem, message_decrypt, message_encrypt, process_prekey_bundle,
};
use libsignal_protocol::{GenericSignedPreKey as _, SignedPreKeyStore as _};
use libsignal_protocol::{IdentityKeyStore as _, KyberPreKeyStore as _, PreKeyStore as _};
use rand::TryRngCore as _;
use rand::rngs::OsRng;

pub mod beacon;
pub mod identity;
pub use identity::DeviceIdentity;

/// Ошибка libsignal или наших проверок.
#[derive(Debug)]
pub enum SignalError {
    /// Ошибка libsignal.
    Protocol(SignalProtocolError),
    /// Не удалось подписать ключ.
    Signing,
    /// Хранилище ответило асинхронно — со своими синхронными хранилищами так быть не должно.
    Pending,
    /// Расшифровано не то, что зашифровано.
    Mismatch,
    /// Секрет ключа личности повреждён или не той версии.
    BadIdentity,
    /// Примитив `signal-crypto` отказал (HMAC).
    Crypto,
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

fn new_store() -> Result<InMemSignalProtocolStore, SignalError> {
    let mut rng = OsRng.unwrap_err();
    let identity = IdentityKeyPair::generate(&mut rng);
    // Номер регистрации — 14 бит (как у Signal); для проверки значение не важно.
    Ok(InMemSignalProtocolStore::new(identity, 1)?)
}

/// Набор ключей получателя для начала сессии (одноразовый, подписанный и Kyber-ключ).
fn bundle(
    store: &mut InMemSignalProtocolStore,
    device: DeviceId,
) -> Result<PreKeyBundle, SignalError> {
    let mut rng = OsRng.unwrap_err();
    let identity = now(store.identity_store.get_identity_key_pair())?;
    let pre = KeyPair::generate(&mut rng);
    let signed = KeyPair::generate(&mut rng);
    let kyber = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
    let signed_sig = identity
        .private_key()
        .calculate_signature(&signed.public_key.serialize(), &mut rng)
        .map_err(|_| SignalError::Signing)?;
    let kyber_sig = identity
        .private_key()
        .calculate_signature(&kyber.public_key.serialize(), &mut rng)
        .map_err(|_| SignalError::Signing)?;
    let (pre_id, signed_id, kyber_id) = (1u32.into(), 1u32.into(), 1u32.into());
    let ts = Timestamp::from_epoch_millis(0);
    now(store
        .pre_key_store
        .save_pre_key(pre_id, &PreKeyRecord::new(pre_id, &pre)))?;
    now(store.signed_pre_key_store.save_signed_pre_key(
        signed_id,
        &SignedPreKeyRecord::new(signed_id, ts, &signed, &signed_sig),
    ))?;
    now(store.kyber_pre_key_store.save_kyber_pre_key(
        kyber_id,
        &KyberPreKeyRecord::new(kyber_id, ts, &kyber, &kyber_sig),
    ))?;
    let reg = now(store.identity_store.get_local_registration_id())?;
    Ok(PreKeyBundle::new(
        reg,
        device,
        Some((pre_id, pre.public_key)),
        signed_id,
        signed.public_key,
        signed_sig.to_vec(),
        kyber_id,
        kyber.public_key,
        kyber_sig.to_vec(),
        *identity.identity_key(),
    )?)
}

fn encrypt(
    store: &mut InMemSignalProtocolStore,
    to: &ProtocolAddress,
    from: &ProtocolAddress,
    text: &[u8],
) -> Result<CiphertextMessage, SignalError> {
    let mut rng = OsRng.unwrap_err();
    now(message_encrypt(
        text,
        to,
        from,
        &mut store.session_store,
        &mut store.identity_store,
        SystemTime::now(),
        &mut rng,
    ))
}

fn decrypt(
    store: &mut InMemSignalProtocolStore,
    from: &ProtocolAddress,
    to: &ProtocolAddress,
    msg: &CiphertextMessage,
) -> Result<Vec<u8>, SignalError> {
    let mut rng = OsRng.unwrap_err();
    now(message_decrypt(
        msg,
        from,
        to,
        &mut store.session_store,
        &mut store.identity_store,
        &mut store.pre_key_store,
        &store.signed_pre_key_store,
        &mut store.kyber_pre_key_store,
        &mut rng,
    ))
}

/// Полный круг в памяти: сессия по набору ключей Боба (PQXDH), сообщение Алисы, ответ Боба.
///
/// # Errors
/// [`SignalError`] — libsignal не работает в этой сборке; приложение не должно продолжать.
pub fn round_trip() -> Result<(), SignalError> {
    let device = DeviceId::new(1).map_err(|_| SignalError::Mismatch)?;
    let alice_addr = ProtocolAddress::new("alice".to_owned(), device);
    let bob_addr = ProtocolAddress::new("bob".to_owned(), device);
    let mut alice = new_store()?;
    let mut bob = new_store()?;

    let bob_bundle = bundle(&mut bob, device)?;
    let mut rng = OsRng.unwrap_err();
    now(process_prekey_bundle(
        &bob_addr,
        &alice_addr,
        &mut alice.session_store,
        &mut alice.identity_store,
        &bob_bundle,
        SystemTime::now(),
        &mut rng,
    ))?;

    let first = encrypt(&mut alice, &bob_addr, &alice_addr, "проверка".as_bytes())?;
    if decrypt(&mut bob, &alice_addr, &bob_addr, &first)? != "проверка".as_bytes() {
        return Err(SignalError::Mismatch);
    }
    let reply = encrypt(&mut bob, &alice_addr, &bob_addr, "ответ".as_bytes())?;
    if decrypt(&mut alice, &bob_addr, &alice_addr, &reply)? != "ответ".as_bytes() {
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
mod tests {
    use super::*;

    #[test]
    fn round_trip_works() {
        round_trip().unwrap();
    }

    #[test]
    fn tampered_message_is_rejected() {
        let device = DeviceId::new(1).unwrap();
        let a = ProtocolAddress::new("a".to_owned(), device);
        let b = ProtocolAddress::new("b".to_owned(), device);
        let mut alice = new_store().unwrap();
        let mut bob = new_store().unwrap();
        let bb = bundle(&mut bob, device).unwrap();
        let mut rng = OsRng.unwrap_err();
        now(process_prekey_bundle(
            &b,
            &a,
            &mut alice.session_store,
            &mut alice.identity_store,
            &bb,
            SystemTime::now(),
            &mut rng,
        ))
        .unwrap();
        let msg = encrypt(&mut alice, &b, &a, b"secret").unwrap();
        let mut bytes = msg.serialize().to_vec();
        let last = bytes.len() - 1;
        bytes[last] ^= 1;
        let forged = CiphertextMessage::PreKeySignalMessage(
            libsignal_protocol::PreKeySignalMessage::try_from(bytes.as_slice()).unwrap(),
        );
        assert!(decrypt(&mut bob, &a, &b, &forged).is_err());
    }
}
