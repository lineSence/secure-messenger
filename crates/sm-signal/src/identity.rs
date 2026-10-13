//! Ключ личности устройства (пункт 4 плана `0.0.1-alpha`, ADR-17).
//!
//! Пара ключей — `IdentityKeyPair` из libsignal, номер регистрации — 14 бит, как у Signal.
//! Здесь только создание и (раз)упаковка в байты; хранит секрет ядро (`sm_core::keystore`),
//! заворачивает — платформа (Android Keystore `sm.keys`, ADR-56).
//!
//! Формат секрета: `[версия = 1][номер регистрации: u32 BE][IdentityKeyPair::serialize()]`.

use libsignal_protocol::IdentityKeyPair;
use rand::rngs::OsRng;
use rand::{RngCore as _, TryRngCore as _};

use crate::SignalError;
use sm_core::keystore::Secret;

/// Версия формата секрета.
pub const IDENTITY_VERSION: u8 = 1;

/// Номер регистрации: 14 бит, не ноль.
const REGISTRATION_MASK: u32 = 0x3FFF;

/// Ключ личности устройства и номер регистрации.
pub struct DeviceIdentity {
    pair: IdentityKeyPair,
    registration_id: u32,
}

impl core::fmt::Debug for DeviceIdentity {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("DeviceIdentity")
            .field("registration_id", &self.registration_id)
            .finish_non_exhaustive()
    }
}

impl DeviceIdentity {
    /// Новый ключ личности (системный генератор).
    #[must_use]
    pub fn generate() -> Self {
        let mut rng = OsRng.unwrap_err();
        let pair = IdentityKeyPair::generate(&mut rng);
        let mut registration_id = 0;
        while registration_id == 0 {
            registration_id = rng.next_u32() & REGISTRATION_MASK;
        }
        Self {
            pair,
            registration_id,
        }
    }

    /// Секрет для хранилища ключей. Вызывающий сразу заворачивает его в `sm_core::keystore::Secret`.
    #[must_use]
    pub fn to_secret(&self) -> Vec<u8> {
        let pair = Secret::new(self.pair.serialize().into_vec());
        let mut out = Vec::with_capacity(pair.expose().len().saturating_add(5));
        out.push(IDENTITY_VERSION);
        out.extend_from_slice(&self.registration_id.to_be_bytes());
        out.extend_from_slice(pair.expose());
        out
    }

    /// Разбирает секрет из хранилища.
    ///
    /// # Errors
    /// [`SignalError::BadIdentity`] — не та версия, номер вне 14 бит, пара не разобралась или
    /// открытый ключ не соответствует закрытому.
    pub fn from_secret(secret: &[u8]) -> Result<Self, SignalError> {
        let (&version, rest) = secret.split_first().ok_or(SignalError::BadIdentity)?;
        let (reg, pair) = rest
            .split_first_chunk::<4>()
            .ok_or(SignalError::BadIdentity)?;
        let registration_id = u32::from_be_bytes(*reg);
        if version != IDENTITY_VERSION
            || registration_id == 0
            || registration_id & !REGISTRATION_MASK != 0
        {
            return Err(SignalError::BadIdentity);
        }
        let pair = IdentityKeyPair::try_from(pair).map_err(|_| SignalError::BadIdentity)?;
        // Открытый ключ в записи должен получаться из закрытого: подменённая половина — отказ.
        let derived = pair
            .private_key()
            .public_key()
            .map_err(|_| SignalError::BadIdentity)?;
        if derived != *pair.public_key() {
            return Err(SignalError::BadIdentity);
        }
        Ok(Self {
            pair,
            registration_id,
        })
    }

    /// Открытый ключ личности (33 байта: тип + X25519) — его показывает QR и сверяют контакты.
    #[must_use]
    pub fn public_key(&self) -> Vec<u8> {
        self.pair.identity_key().serialize().into_vec()
    }

    /// Номер регистрации.
    #[must_use]
    pub const fn registration_id(&self) -> u32 {
        self.registration_id
    }

    /// Пара ключей для хранилищ libsignal (шаг 6).
    #[must_use]
    pub const fn pair(&self) -> &IdentityKeyPair {
        &self.pair
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_round_trip() {
        let id = DeviceIdentity::generate();
        let back = DeviceIdentity::from_secret(&id.to_secret()).unwrap();
        assert_eq!(back.public_key(), id.public_key());
        assert_eq!(back.registration_id(), id.registration_id());
        assert_eq!(id.public_key().len(), 33);
        assert!(id.registration_id() > 0 && id.registration_id() <= REGISTRATION_MASK);
    }

    #[test]
    fn bad_secrets_are_rejected() {
        let good = DeviceIdentity::generate().to_secret();
        let mut wrong_version = good.clone();
        wrong_version[0] = 2;
        let mut big_reg = good.clone();
        big_reg[1] = 0xFF;
        let mut zero_reg = good.clone();
        zero_reg[1..5].copy_from_slice(&[0; 4]);
        let mut other_private = good.clone();
        let last = other_private.len() - 1;
        other_private[last] ^= 1;
        for bad in [
            &[][..],
            &good[..3],
            &good[..good.len() - 1],
            &wrong_version,
            &big_reg,
            &zero_reg,
            &other_private,
        ] {
            assert!(DeviceIdentity::from_secret(bad).is_err());
        }
    }

    #[test]
    fn debug_hides_private_key() {
        let id = DeviceIdentity::generate();
        let s = format!("{id:?}");
        assert!(s.contains("registration_id") && !s.contains("pair"));
    }
}
