//! Пропуск на создание очередей (ADR-136): `NEW` узел выполняет только с пропуском, который
//! владелец подписал офлайн корневым ключом (ADR-127). Узел проверяет подпись открытым корневым
//! ключом и списка пропусков не хранит; лимит новых очередей в час — на пропуск.
//!
//! Здесь только формат и проверка полей — без криптографии: подпись `XEdDSA` ставит
//! `sm-signal::node`, проверяет узел (`sm-relay`). Формат (числа — big-endian):
//!
//! ```text
//! [версия u8 = 1][ID пропуска 16 байт][действует до u64, с][новых очередей в час u16 > 0]
//! [подпись `XEdDSA` 64 байта над SIGN_CONTEXT ‖ первые 27 байт]
//! ```

use crate::wire::{Reader, WireError};

/// Версия формата.
pub const VERSION: u8 = 1;
/// Длина ID пропуска.
pub const ID_LEN: usize = 16;
/// Длина подписанной части.
pub const BODY_LEN: usize = 1 + ID_LEN + 8 + 2;
/// Длина подписи `XEdDSA`.
pub const SIGNATURE_LEN: usize = 64;
/// Длина пропуска целиком — данные запроса `NEW`.
pub const PASS_LEN: usize = BODY_LEN + SIGNATURE_LEN;
/// Контекст подписи: отличает пропуск от записи узла, подписанной тем же ключом.
pub const SIGN_CONTEXT: &[u8] = b"SM queue pass v1\0";

/// Пропуск без подписи.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct QueuePass {
    /// ID пропуска: по нему считается лимит и отзывается пропуск.
    pub id: [u8; ID_LEN],
    /// До какого момента действует, секунды Unix.
    pub expires: u64,
    /// Сколько новых очередей в час можно создать по пропуску.
    pub per_hour: u16,
}

impl QueuePass {
    /// Подписываемая часть.
    #[must_use]
    pub fn body(&self) -> [u8; BODY_LEN] {
        let mut b = [0; BODY_LEN];
        let (version, rest) = b.split_at_mut(1);
        let (id, rest) = rest.split_at_mut(ID_LEN);
        let (expires, per_hour) = rest.split_at_mut(8);
        version.copy_from_slice(&[VERSION]);
        id.copy_from_slice(&self.id);
        expires.copy_from_slice(&self.expires.to_be_bytes());
        per_hour.copy_from_slice(&self.per_hour.to_be_bytes());
        b
    }

    /// Пропуск целиком: подписанная часть и подпись.
    #[must_use]
    pub fn with_signature(&self, signature: &[u8; SIGNATURE_LEN]) -> Vec<u8> {
        [&self.body()[..], signature].concat()
    }

    /// Разбирает пропуск: поля, подписанное сообщение (`SIGN_CONTEXT ‖ тело`) и подпись.
    ///
    /// # Errors
    /// [`WireError`] — не та длина, версия или лимит 0.
    pub fn parse(buf: &[u8]) -> Result<(Self, Vec<u8>, [u8; SIGNATURE_LEN]), WireError> {
        if buf.len() != PASS_LEN {
            return Err(WireError::BadValue);
        }
        let mut r = Reader::new(buf);
        if r.u8()? != VERSION {
            return Err(WireError::BadValue);
        }
        let id = r.array()?;
        let expires = r.u64()?;
        let per_hour = r.u16()?;
        if per_hour == 0 {
            return Err(WireError::BadValue);
        }
        let signature = r.array()?;
        r.finish()?;
        let pass = Self {
            id,
            expires,
            per_hour,
        };
        Ok((pass, signed_message(&pass.body()), signature))
    }
}

/// Что подписывается: `SIGN_CONTEXT ‖ тело`.
#[must_use]
pub fn signed_message(body: &[u8; BODY_LEN]) -> Vec<u8> {
    [SIGN_CONTEXT, &body[..]].concat()
}

#[cfg(test)]
mod tests {
    use super::*;

    const P: QueuePass = QueuePass {
        id: [5; ID_LEN],
        expires: 1_800_000_000,
        per_hour: 20,
    };

    #[test]
    fn round_trip_and_layout() {
        let buf = P.with_signature(&[9; SIGNATURE_LEN]);
        assert_eq!(buf.len(), PASS_LEN);
        assert_eq!(buf[0], VERSION);
        let (p, msg, sig) = QueuePass::parse(&buf).unwrap();
        assert_eq!(p, P);
        assert_eq!(sig, [9; SIGNATURE_LEN]);
        assert!(msg.starts_with(SIGN_CONTEXT));
        assert_eq!(&msg[SIGN_CONTEXT.len()..], &buf[..BODY_LEN]);
    }

    #[test]
    fn rejects_bad_input() {
        let good = P.with_signature(&[9; SIGNATURE_LEN]);
        assert!(QueuePass::parse(&good[..PASS_LEN - 1]).is_err());
        assert!(QueuePass::parse(&[&good[..], &[0]].concat()).is_err());
        let mut v = good.clone();
        v[0] = 2;
        assert!(QueuePass::parse(&v).is_err());
        let zero = QueuePass { per_hour: 0, ..P }.with_signature(&[9; SIGNATURE_LEN]);
        assert!(QueuePass::parse(&zero).is_err());
    }
}
