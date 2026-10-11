//! Метки поиска Bluetooth LE (ADR-61, ADR-62).
//!
//! Постоянный UUID выдаёт приложение любому сканеру (UX-24), поэтому UUID в объявлении и UUID
//! сервиса GATT — метка 16 байт, которая меняется каждые [`EPOCH_MS`]:
//!
//! ```text
//! метка = первые 16 байт HMAC-SHA256(ключ, домен ‖ номер эпохи u64 BE)
//! ```
//!
//! - контакт: ключ — ключ маяка устройства (32 байта, раздаётся контактам внутри сессии Signal);
//! - сопряжение по QR: ключ — одноразовый секрет из QR (ADR-62 п.2);
//! - «Добавить рядом» без QR: ключ — открытая строка, метку может вычислить любой с приложением,
//!   поэтому она видна только пока открыт экран добавления (ADR-62 п.3).
//!
//! HMAC — `CryptographicMac` из `signal-crypto` (libsignal, ADR-1, ADR-47); своей криптографии нет.
//! Метка — только способ найти друг друга, секретность сообщений обеспечивает Signal.

use rand::rngs::OsRng;
use rand::{RngCore as _, TryRngCore as _};
use signal_crypto::CryptographicMac;

use crate::SignalError;

/// Длина эпохи: 15 минут.
pub const EPOCH_MS: u64 = 15 * 60 * 1000;
/// Длина метки (UUID).
pub const TOKEN_LEN: usize = 16;
/// Длина ключа маяка.
pub const BEACON_KEY_LEN: usize = 32;
/// Длина секрета сопряжения из QR.
pub const PAIRING_SECRET_LEN: usize = 16;

const DOMAIN_CONTACT: &[u8] = b"sm-ble/v1";
const DOMAIN_PAIR: &[u8] = b"sm-pair/v1";
const DOMAIN_ADD: &[u8] = b"sm-add/v1";
const DOMAIN_PROOF: &[u8] = b"sm-pair-proof/v1";
const DOMAIN_LINK_PROOF: &[u8] = b"sm-link-proof/v1";
/// Открытый «ключ» для «Добавить рядом»: метка общая для всех приложений.
const ADD_KEY: &[u8] = b"sm-ble-add-nearby";

/// Метка BLE (UUID 16 байт).
pub type Token = [u8; TOKEN_LEN];

/// Номер эпохи для времени Unix в миллисекундах.
#[must_use]
pub const fn epoch(unix_ms: u64) -> u64 {
    unix_ms / EPOCH_MS
}

fn mac(key: &[u8], parts: &[&[u8]]) -> Result<Vec<u8>, SignalError> {
    let mut m = CryptographicMac::new("HmacSha256", key).map_err(|_| SignalError::Crypto)?;
    for p in parts {
        m.update(p);
    }
    Ok(m.finalize())
}

fn token(key: &[u8], domain: &[u8], epoch: u64) -> Result<Token, SignalError> {
    let full = mac(key, &[domain, &epoch.to_be_bytes()])?;
    full.get(..TOKEN_LEN)
        .and_then(|t| Token::try_from(t).ok())
        .ok_or(SignalError::Crypto)
}

/// Случайные байты из системного генератора.
fn random<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    OsRng.unwrap_err().fill_bytes(&mut out);
    out
}

/// Новый ключ маяка устройства.
#[must_use]
pub fn new_beacon_key() -> [u8; BEACON_KEY_LEN] {
    random()
}

/// Новый одноразовый секрет сопряжения для QR.
#[must_use]
pub fn new_pairing_secret() -> [u8; PAIRING_SECRET_LEN] {
    random()
}

/// Метка устройства с ключом маяка `key` в эпоху `epoch` — её объявляет устройство.
///
/// # Errors
/// [`SignalError::Crypto`] — libsignal не посчитал HMAC.
pub fn contact_token(key: &[u8; BEACON_KEY_LEN], epoch: u64) -> Result<Token, SignalError> {
    token(key, DOMAIN_CONTACT, epoch)
}

/// Метки, которые ищем у контакта: эпохи `t − 1`, `t`, `t + 1` (часы телефонов расходятся).
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn contact_tokens_around(
    key: &[u8; BEACON_KEY_LEN],
    epoch: u64,
) -> Result<[Token; 3], SignalError> {
    Ok([
        contact_token(key, epoch.saturating_sub(1))?,
        contact_token(key, epoch)?,
        contact_token(key, epoch.saturating_add(1))?,
    ])
}

/// Метка сопряжения по секрету из QR.
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn pairing_token(secret: &[u8; PAIRING_SECRET_LEN], epoch: u64) -> Result<Token, SignalError> {
    token(secret, DOMAIN_PAIR, epoch)
}

/// Метка экрана «Добавить рядом» — общая для всех.
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn add_nearby_token(epoch: u64) -> Result<Token, SignalError> {
    token(ADD_KEY, DOMAIN_ADD, epoch)
}

/// Доказательство знания секрета из QR: HMAC набора ключей отсканировавшего (ADR-62 п.2).
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn pairing_proof(
    secret: &[u8; PAIRING_SECRET_LEN],
    bundle: &[u8],
) -> Result<Vec<u8>, SignalError> {
    mac(secret, &[DOMAIN_PROOF, bundle])
}

/// Проверяет доказательство за постоянное время.
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn pairing_proof_ok(
    secret: &[u8; PAIRING_SECRET_LEN],
    bundle: &[u8],
    proof: &[u8],
) -> Result<bool, SignalError> {
    Ok(same(&pairing_proof(secret, bundle)?, proof))
}

/// Доказательство знания секрета ссылки-приглашения (ADR-83): HMAC карточки и адреса почты
/// получившего ссылку — узел по пути не подменит адрес, куда пригласивший пошлёт начало сессии.
/// Длина карточки (u16 BE) впереди, ID отправителя фиксированной длины, запись узла — до конца:
/// склейка однозначна.
///
/// # Errors
/// [`SignalError::BadInput`] — карточка длиннее `u16`; [`SignalError::Crypto`].
pub fn link_proof(
    secret: &[u8; PAIRING_SECRET_LEN],
    card: &[u8],
    sender_id: &[u8; 32],
    node: &[u8],
) -> Result<Vec<u8>, SignalError> {
    let len = u16::try_from(card.len()).map_err(|_| SignalError::BadInput)?;
    mac(
        secret,
        &[DOMAIN_LINK_PROOF, &len.to_be_bytes(), card, sender_id, node],
    )
}

/// Проверяет [`link_proof`] за постоянное время.
///
/// # Errors
/// [`SignalError::BadInput`], [`SignalError::Crypto`].
pub fn link_proof_ok(
    secret: &[u8; PAIRING_SECRET_LEN],
    card: &[u8],
    sender_id: &[u8; 32],
    node: &[u8],
    proof: &[u8],
) -> Result<bool, SignalError> {
    Ok(same(&link_proof(secret, card, sender_id, node)?, proof))
}

fn same(expected: &[u8], got: &[u8]) -> bool {
    if expected.len() != got.len() {
        return false;
    }
    // Сравнение без раннего выхода.
    expected
        .iter()
        .zip(got)
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn link_proof_binds_every_part() {
        let s = [1; PAIRING_SECRET_LEN];
        let p = link_proof(&s, b"card", &[2; 32], b"node").unwrap();
        assert!(link_proof_ok(&s, b"card", &[2; 32], b"node", &p).unwrap());
        assert!(!link_proof_ok(&[9; PAIRING_SECRET_LEN], b"card", &[2; 32], b"node", &p).unwrap());
        assert!(!link_proof_ok(&s, b"cardn", &[2; 32], b"ode", &p).unwrap());
        assert!(!link_proof_ok(&s, b"card", &[3; 32], b"node", &p).unwrap());
        assert!(!link_proof_ok(&s, b"card", &[2; 32], b"nodf", &p).unwrap());
        assert!(!link_proof_ok(&s, b"card", &[2; 32], b"node", &p[..31]).unwrap());
        // Доказательство QR над той же карточкой — другое (своя метка домена).
        assert_ne!(pairing_proof(&s, b"card").unwrap(), p);
        assert!(link_proof(&s, &vec![0; 70_000], &[2; 32], b"n").is_err());
    }

    #[test]
    fn epoch_is_15_minutes() {
        assert_eq!(epoch(0), 0);
        assert_eq!(epoch(EPOCH_MS - 1), 0);
        assert_eq!(epoch(EPOCH_MS), 1);
    }

    #[test]
    fn tokens_change_with_epoch_key_and_domain() {
        let k = new_beacon_key();
        let k2 = new_beacon_key();
        let s = new_pairing_secret();
        let t = contact_token(&k, 100).unwrap();
        assert_eq!(t, contact_token(&k, 100).unwrap());
        assert_ne!(t, contact_token(&k, 101).unwrap());
        assert_ne!(t, contact_token(&k2, 100).unwrap());
        assert_ne!(
            pairing_token(&s, 100).unwrap(),
            add_nearby_token(100).unwrap()
        );
        assert_ne!(
            add_nearby_token(100).unwrap(),
            add_nearby_token(101).unwrap()
        );
    }

    #[test]
    fn neighbours_cover_clock_skew() {
        let k = new_beacon_key();
        let around = contact_tokens_around(&k, 50).unwrap();
        assert_eq!(around[0], contact_token(&k, 49).unwrap());
        assert_eq!(around[1], contact_token(&k, 50).unwrap());
        assert_eq!(around[2], contact_token(&k, 51).unwrap());
        // Эпоха 0: соседа слева нет, метка повторяется, но не паникует.
        assert_eq!(
            contact_tokens_around(&k, 0).unwrap()[0],
            contact_token(&k, 0).unwrap()
        );
    }

    /// Известный ответ HMAC-SHA256 (RFC 4231, случай 2) — `signal-crypto` считает именно его.
    #[test]
    fn hmac_matches_rfc4231() {
        let out = mac(b"Jefe", &[b"what do ya want ", b"for nothing?"]).unwrap();
        let hex = out.iter().fold(String::new(), |mut acc, b| {
            use std::fmt::Write as _;
            write!(acc, "{b:02x}").unwrap();
            acc
        });
        assert_eq!(
            hex,
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
    }

    #[test]
    fn proof_checks_secret_and_bundle() {
        let s = new_pairing_secret();
        let p = pairing_proof(&s, b"bundle").unwrap();
        assert!(pairing_proof_ok(&s, b"bundle", &p).unwrap());
        assert!(!pairing_proof_ok(&s, b"bundlf", &p).unwrap());
        assert!(!pairing_proof_ok(&new_pairing_secret(), b"bundle", &p).unwrap());
        assert!(!pairing_proof_ok(&s, b"bundle", &p[..31]).unwrap());
    }

    #[test]
    fn keys_are_random() {
        assert_ne!(new_beacon_key(), new_beacon_key());
        assert_ne!(new_pairing_secret(), new_pairing_secret());
    }
}
