//! ID получателя своей очереди на узле (ADR-134): его задаёт клиент, поэтому после перезапуска
//! узла очередь создаётся заново с тем же ID и контакту ничего передавать не нужно.
//!
//! ```text
//! ID получателя = HMAC-SHA256(секрет устройства, `sm-queue/v1` ‖ ID контакта ‖ номер периода u64 BE)
//! ```
//!
//! ID контакта — [`crate::contact::contact_id`] (hex ключа личности); номер периода — в конце и
//! фиксированной длины, поэтому склейка однозначна. Новый период — новые ID (передаются внутри
//! сессии Signal). HMAC — `CryptographicMac` из `signal-crypto` (libsignal, ADR-1), как в
//! `beacon` (ADR-61); своей криптографии нет.

use rand::rngs::OsRng;
use rand::{RngCore as _, TryRngCore as _};
use signal_crypto::CryptographicMac;

use crate::SignalError;

/// Длина секрета очередей устройства.
pub const QUEUE_SECRET_LEN: usize = 32;
/// Длина ID очереди на узле (`sm_core::relay::ID_LEN`).
pub const QUEUE_ID_LEN: usize = 32;

const DOMAIN: &[u8] = b"sm-queue/v1";
const DOMAIN_INVITE: &[u8] = b"sm-invite-queue/v1";

/// Новый секрет очередей устройства (случайные байты из ОС).
#[must_use]
pub fn new_queue_secret() -> [u8; QUEUE_SECRET_LEN] {
    let mut out = [0u8; QUEUE_SECRET_LEN];
    OsRng.unwrap_err().fill_bytes(&mut out);
    out
}

/// ID получателя очереди для контакта `contact` в период `period`.
///
/// # Errors
/// [`SignalError::Crypto`] — HMAC не посчитан.
pub fn recipient_id(
    secret: &[u8; QUEUE_SECRET_LEN],
    contact: &str,
    period: u64,
) -> Result<[u8; QUEUE_ID_LEN], SignalError> {
    let mut m = CryptographicMac::new("HmacSha256", secret).map_err(|_| SignalError::Crypto)?;
    m.update(DOMAIN);
    m.update(contact.as_bytes());
    m.update(&period.to_be_bytes());
    <[u8; QUEUE_ID_LEN]>::try_from(m.finalize()).map_err(|_| SignalError::Crypto)
}

/// ID получателя очереди ссылки-приглашения (ADR-83): контакта ещё нет, поэтому вместо ID
/// контакта — секрет ссылки. HMAC-SHA256(секрет устройства, `sm-invite-queue/v1` ‖ секрет
/// ссылки 16 байт): своя метка домена, с очередями контактов не совпадёт; после перезапуска
/// узла очередь создаётся заново с тем же ID (ADR-134).
///
/// # Errors
/// [`SignalError::Crypto`] — HMAC не посчитан.
pub fn invite_recipient_id(
    secret: &[u8; QUEUE_SECRET_LEN],
    invite_secret: &[u8; crate::beacon::PAIRING_SECRET_LEN],
) -> Result<[u8; QUEUE_ID_LEN], SignalError> {
    let mut m = CryptographicMac::new("HmacSha256", secret).map_err(|_| SignalError::Crypto)?;
    m.update(DOMAIN_INVITE);
    m.update(invite_secret);
    <[u8; QUEUE_ID_LEN]>::try_from(m.finalize()).map_err(|_| SignalError::Crypto)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invite_known_vector() {
        // HMAC-SHA256(32 × 0x07, b"sm-invite-queue/v1" ‖ 16 × 0x03), посчитано независимо
        // (Python hmac + hashlib).
        let id = invite_recipient_id(&[7; QUEUE_SECRET_LEN], &[3; 16]).unwrap();
        assert_eq!(hex(&id), INVITE_KNOWN);
        assert_ne!(
            id,
            invite_recipient_id(&[7; QUEUE_SECRET_LEN], &[4; 16]).unwrap()
        );
        assert_ne!(
            id,
            invite_recipient_id(&[8; QUEUE_SECRET_LEN], &[3; 16]).unwrap()
        );
    }

    #[test]
    fn known_vector() {
        // HMAC-SHA256(32 × 0x07, b"sm-queue/v1" ‖ b"05ab" ‖ 3u64 BE), посчитано независимо
        // (Python hmac + hashlib).
        let id = recipient_id(&[7; QUEUE_SECRET_LEN], "05ab", 3).unwrap();
        assert_eq!(hex(&id), KNOWN);
    }

    #[test]
    fn depends_on_every_input() {
        let s = [1; QUEUE_SECRET_LEN];
        let base = recipient_id(&s, "05ab", 1).unwrap();
        assert_eq!(base, recipient_id(&s, "05ab", 1).unwrap());
        assert_ne!(base, recipient_id(&s, "05ac", 1).unwrap());
        assert_ne!(base, recipient_id(&s, "05ab", 2).unwrap());
        assert_ne!(
            base,
            recipient_id(&[2; QUEUE_SECRET_LEN], "05ab", 1).unwrap()
        );
    }

    #[test]
    fn secrets_are_random() {
        assert_ne!(new_queue_secret(), new_queue_secret());
    }

    fn hex(b: &[u8]) -> String {
        crate::contact::contact_id(b)
    }

    const INVITE_KNOWN: &str = "96187b4d9f7c68c7bcb1997f7bafbaf68075ccb04cbc36a57d32835b33dee711";
    const KNOWN: &str = "b1686ce93b8bf6b5a9228627c8b3e9e40afe240253f1406f0998532bec1c433d";
}
