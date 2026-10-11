//! Шифрование частей Bluetooth LE (ADR-92): часть целиком (заголовок, ящик, блоб) уходит в эфир
//! только зашифрованной, снаружи — случайные байты без версии, номеров, длин и постоянных полей.
//!
//! ```text
//! в эфире = nonce (12 случайных байт) ‖ AES-256-GCM(ключ части, nonce, AAD, открытая часть) ‖ метка 16
//! ключ части = HMAC-SHA256(секрет, домен)
//! ```
//!
//! - контакту: секрет — ключ маяка **получателя** (ADR-61; у получателя он свой, у отправителя —
//!   из карточки контакта), получатель открывает своим ключом без перебора;
//! - сопряжение по QR: секрет — одноразовый секрет из QR (ADR-62 п.2);
//! - «Добавить рядом»: секрет открыт (как и метка этого экрана) — ключ вычислит любой с
//!   приложением; шифрование здесь прячет только формат от постороннего сниффера.
//!
//! Открытая часть — `sm_core::chunk` (все части кадра одного размера), номер передачи —
//! случайный. Примитивы — `Aes256GcmEncryption`/`Decryption` и `CryptographicMac` из
//! `signal-crypto` (libsignal, ADR-1, ADR-47); своей криптографии нет. Случайный nonce 96 бит:
//! на один ключ — не больше 2³² частей (NIST SP 800-38D, п. 8.3), это ~2 ТБ через Bluetooth.

use rand::rngs::OsRng;
use rand::{RngCore as _, TryRngCore as _};
use signal_crypto::{Aes256GcmDecryption, Aes256GcmEncryption, CryptographicMac};
use sm_core::chunk::{self, SEAL_OVERHEAD};

use crate::SignalError;
use crate::beacon::{BEACON_KEY_LEN, PAIRING_SECRET_LEN};

/// Длина nonce AES-GCM.
pub const NONCE_LEN: usize = 12;
/// Длина метки AES-GCM.
pub const TAG_LEN: usize = 16;
/// Длина ключа части.
pub const KEY_LEN: usize = 32;
/// Наименьшая зашифрованная часть: заголовок, 1 байт данных и шифрование.
pub const MIN_SEALED: usize = chunk::HEADER + 1 + SEAL_OVERHEAD;

const _: () = assert!(NONCE_LEN + TAG_LEN == SEAL_OVERHEAD);

const DOMAIN_CONTACT: &[u8] = b"sm-ble-part/v1/contact";
const DOMAIN_PAIR: &[u8] = b"sm-ble-part/v1/pair";
const DOMAIN_ADD: &[u8] = b"sm-ble-part/v1/add";
/// Открытый секрет «Добавить рядом» (как у метки этого экрана).
const ADD_SECRET: &[u8] = b"sm-ble-add-nearby";
/// Связанные данные: часть не подложить в другой протокол на том же ключе.
const AAD: &[u8] = b"sm-ble-part/v1";

/// Ключ шифрования частей.
pub type PartKey = [u8; KEY_LEN];

fn derive(secret: &[u8], domain: &[u8]) -> Result<PartKey, SignalError> {
    let mut m = CryptographicMac::new("HmacSha256", secret).map_err(|_| SignalError::Crypto)?;
    m.update(domain);
    PartKey::try_from(m.finalize().as_slice()).map_err(|_| SignalError::Crypto)
}

/// Ключ частей для устройства с ключом маяка `beacon` (получателя).
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn contact_key(beacon: &[u8; BEACON_KEY_LEN]) -> Result<PartKey, SignalError> {
    derive(beacon, DOMAIN_CONTACT)
}

/// Ключ частей сопряжения по секрету из QR.
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn pairing_key(secret: &[u8; PAIRING_SECRET_LEN]) -> Result<PartKey, SignalError> {
    derive(secret, DOMAIN_PAIR)
}

/// Ключ частей экрана «Добавить рядом» (общий для всех).
///
/// # Errors
/// [`SignalError::Crypto`].
pub fn add_nearby_key() -> Result<PartKey, SignalError> {
    derive(ADD_SECRET, DOMAIN_ADD)
}

/// Шифрует открытую часть: `nonce ‖ шифротекст ‖ метка`.
///
/// # Errors
/// [`SignalError::Crypto`] — `signal-crypto` отказал; [`SignalError::BadInput`] — часть пустая
/// или после шифрования длиннее [`chunk::MAX_SEALED`].
pub fn seal(key: &PartKey, plain: &[u8]) -> Result<Vec<u8>, SignalError> {
    if plain.is_empty() || plain.len() > chunk::MAX_CHUNK {
        return Err(SignalError::BadInput);
    }
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.unwrap_err().fill_bytes(&mut nonce);
    let mut enc = Aes256GcmEncryption::new(key, &nonce, AAD).map_err(|_| SignalError::Crypto)?;
    let mut out = Vec::with_capacity(plain.len().saturating_add(SEAL_OVERHEAD));
    out.extend_from_slice(&nonce);
    out.extend_from_slice(plain);
    if let Some(body) = out.get_mut(NONCE_LEN..) {
        enc.encrypt(body);
    }
    out.extend_from_slice(&enc.compute_tag());
    Ok(out)
}

/// Открывает часть из эфира. `None` — короче [`MIN_SEALED`], длиннее [`chunk::MAX_SEALED`],
/// другой ключ, подмена или повреждение: открытый текст не возвращается, пока метка не сошлась.
#[must_use]
pub fn open(key: &PartKey, sealed: &[u8]) -> Option<Vec<u8>> {
    if !(MIN_SEALED..=chunk::MAX_SEALED).contains(&sealed.len()) {
        return None;
    }
    let (nonce, rest) = sealed.split_at(NONCE_LEN);
    let (body, tag) = rest.split_at(rest.len().checked_sub(TAG_LEN)?);
    let mut dec = Aes256GcmDecryption::new(key, nonce, AAD).ok()?;
    let mut plain = body.to_vec();
    dec.decrypt(&mut plain);
    dec.verify_tag(tag).ok()?;
    Some(plain)
}

/// Режет кадр на зашифрованные части ровно по `sealed_len` байт; номер передачи — случайный.
/// `None` — пустой кадр, кадр больше предела или `sealed_len` вне
/// `MIN_SEALED..=MAX_SEALED`.
///
/// # Errors
/// [`SignalError::Crypto`] — `signal-crypto` отказал.
pub fn split_sealed(
    key: &PartKey,
    frame: &[u8],
    sealed_len: usize,
) -> Result<Option<Vec<Vec<u8>>>, SignalError> {
    if !(MIN_SEALED..=chunk::MAX_SEALED).contains(&sealed_len) {
        return Ok(None);
    }
    let mut t = [0u8; 2];
    OsRng.unwrap_err().fill_bytes(&mut t);
    let Some(parts) = chunk::split(
        frame,
        u16::from_be_bytes(t),
        sealed_len.saturating_sub(SEAL_OVERHEAD),
    ) else {
        return Ok(None);
    };
    parts
        .iter()
        .map(|p| seal(key, p))
        .collect::<Result<Vec<_>, _>>()
        .map(Some)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::beacon::{new_beacon_key, new_pairing_secret};
    use sm_core::chunk::{MAX_SEALED, Reassembler};

    fn frame(len: usize) -> Vec<u8> {
        (0..len).map(|i| u8::try_from(i % 251).unwrap()).collect()
    }

    #[test]
    fn roundtrip_and_fixed_size() {
        let key = contact_key(&new_beacon_key()).unwrap();
        for len in [1, 100, 1000, 5000] {
            for sealed_len in [MIN_SEALED, 64, 244, MAX_SEALED] {
                let parts = split_sealed(&key, &frame(len), sealed_len)
                    .unwrap()
                    .unwrap();
                assert!(
                    parts.iter().all(|p| p.len() == sealed_len),
                    "все части одного размера"
                );
                let mut r = Reassembler::new();
                let mut out = None;
                for p in &parts {
                    out = r.push(&open(&key, p).unwrap()).unwrap();
                }
                assert_eq!(out, Some(frame(len)));
            }
        }
    }

    #[test]
    fn wrong_key_or_tamper_rejected() {
        let k = contact_key(&new_beacon_key()).unwrap();
        let other = contact_key(&new_beacon_key()).unwrap();
        let p = split_sealed(&k, &frame(300), 128)
            .unwrap()
            .unwrap()
            .remove(0);
        assert!(open(&k, &p).is_some());
        assert!(open(&other, &p).is_none());
        for i in [0, NONCE_LEN, p.len() - 1] {
            let mut bad = p.clone();
            bad[i] ^= 1;
            assert!(open(&k, &bad).is_none(), "байт {i}");
        }
        assert!(open(&k, &p[..MIN_SEALED - 1]).is_none());
        assert!(open(&k, &vec![0; MAX_SEALED + 1]).is_none());
        assert_eq!(split_sealed(&k, &frame(10), MIN_SEALED - 1).unwrap(), None);
        assert_eq!(split_sealed(&k, &frame(10), MAX_SEALED + 1).unwrap(), None);
        assert_eq!(split_sealed(&k, &[], 128).unwrap(), None);
    }

    #[test]
    fn keys_are_separated() {
        let s = new_pairing_secret();
        let b = new_beacon_key();
        let mut padded = [0u8; BEACON_KEY_LEN];
        padded[..PAIRING_SECRET_LEN].copy_from_slice(&s);
        assert_ne!(pairing_key(&s).unwrap(), contact_key(&padded).unwrap());
        assert_ne!(
            contact_key(&b).unwrap(),
            contact_key(&new_beacon_key()).unwrap()
        );
        assert_eq!(add_nearby_key().unwrap(), add_nearby_key().unwrap());
        assert_ne!(add_nearby_key().unwrap(), pairing_key(&s).unwrap());
    }

    /// Тест-различитель (ADR-92 п.3): 10 000 частей одного и того же кадра — ни одна позиция
    /// байта не постоянна, распределение по позиции близко к равномерному, и соседние части не
    /// идут счётчиком (разность значений в позиции не повторяется подряд).
    #[test]
    fn distinguisher_no_constant_or_counter_bytes() {
        const N: usize = 10_000;
        let key = add_nearby_key().unwrap();
        // Кадр в одну часть: заголовок с номерами, ящик из нулей и блоб — одни и те же каждый раз.
        let mut f = vec![0u8; 32];
        f.extend_from_slice(&frame(100));
        let parts: Vec<Vec<u8>> = (0..N)
            .map(|_| split_sealed(&key, &f, 160).unwrap().unwrap().remove(0))
            .collect();
        let len = parts[0].len();
        assert!(parts.iter().all(|p| p.len() == len));
        for pos in 0..len {
            let mut counts = [0usize; 256];
            let mut same_delta = 0usize;
            let mut prev_delta = None;
            for w in parts.windows(2) {
                let d = w[1][pos].wrapping_sub(w[0][pos]);
                if prev_delta == Some(d) {
                    same_delta += 1;
                }
                prev_delta = Some(d);
            }
            for p in &parts {
                counts[usize::from(p[pos])] += 1;
            }
            let max = counts.iter().copied().max().unwrap_or(0);
            // Ожидание — 39 на значение; 100 и больше у случайных байтов — вероятность ~1e-15.
            assert!(
                max < 100,
                "позиция {pos}: значение повторилось {max} раз из {N}"
            );
            // Одинаковая разность подряд у случайных — около 1/256 (≈ 39), у счётчика — почти всегда.
            assert!(same_delta < 100, "позиция {pos}: счётчик ({same_delta})");
        }
    }
}
