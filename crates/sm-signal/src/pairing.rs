//! Приглашение в QR и кадры обмена при добавлении контакта (ADR-62).
//!
//! QR — строка `SM1:` + HEX(ключ личности 33 ‖ секрет сопряжения 16), заглавные буквы: так
//! она кодируется буквенно-цифровым режимом QR (меньше модулей, легче сканировать).
//!
//! Кадры по Bluetooth, пока открыт экран добавления (первый байт — тип):
//! - [`PairFrame::Card`] — карточка того, кто подключился (+ доказательство знания секрета QR);
//! - [`PairFrame::Start`] — первое сообщение Signal от принявшего карточку;
//! - [`PairFrame::Reply`] — ответ по сессии;
//! - [`PairFrame::Decline`] — пользователь отказался;
//! - [`PairFrame::Intro`] — имя для списка «Добавить рядом» (UX-25): его видят телефоны с
//!   открытым тем же экраном, поэтому только имя, которое человек указал сам.

use crate::SignalError;
use crate::beacon::PAIRING_SECRET_LEN;
use crate::contact::{IDENTITY_KEY_LEN, MAX_NAME_BYTES};

/// Префикс строки QR (версия 1).
pub const QR_PREFIX: &str = "SM1:";
/// Длина доказательства (HMAC-SHA256).
pub const PROOF_LEN: usize = 32;
/// Наибольшая длина кадра: карточка с Kyber1024 ≈ 1,8 КБ, сообщение начала сессии ≈ 1,8 КБ.
pub const MAX_PAIR_FRAME: usize = 8 * 1024;

const TAG_CARD: u8 = 1;
const TAG_START: u8 = 2;
const TAG_REPLY: u8 = 3;
const TAG_DECLINE: u8 = 4;
const TAG_INTRO: u8 = 5;

/// Содержимое QR.
#[derive(Clone, PartialEq, Eq)]
pub struct QrInvite {
    /// Открытый ключ личности показавшего QR.
    pub identity_key: [u8; IDENTITY_KEY_LEN],
    /// Одноразовый секрет сопряжения.
    pub secret: [u8; PAIRING_SECRET_LEN],
}

impl core::fmt::Debug for QrInvite {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("QrInvite").finish_non_exhaustive()
    }
}

const HEX: &[u8; 16] = b"0123456789ABCDEF";

/// Длина приглашения в байтах и в знаках после префикса.
const INVITE_LEN: usize = IDENTITY_KEY_LEN + PAIRING_SECRET_LEN;
const INVITE_HEX_LEN: usize = 2 * INVITE_LEN;

fn hex_value(c: u8) -> Option<u8> {
    HEX.iter()
        .position(|&h| h == c)
        .and_then(|v| u8::try_from(v).ok())
}

impl QrInvite {
    /// Строка для QR.
    #[must_use]
    pub fn encode(&self) -> String {
        let mut s = String::with_capacity(QR_PREFIX.len().saturating_add(INVITE_HEX_LEN));
        s.push_str(QR_PREFIX);
        for b in self.identity_key.iter().chain(&self.secret) {
            for nibble in [b >> 4, b & 0x0F] {
                if let Some(&c) = HEX.get(usize::from(nibble)) {
                    s.push(char::from(c));
                }
            }
        }
        s
    }

    /// Разбор строки из сканера; чужой QR — [`SignalError::BadInput`].
    ///
    /// # Errors
    /// [`SignalError::BadInput`].
    pub fn decode(text: &str) -> Result<Self, SignalError> {
        let body = text
            .strip_prefix(QR_PREFIX)
            .ok_or(SignalError::BadInput)?
            .as_bytes();
        if body.len() != INVITE_HEX_LEN {
            return Err(SignalError::BadInput);
        }
        let mut bytes = [0u8; INVITE_LEN];
        for (out, &[h, l]) in bytes.iter_mut().zip(body.as_chunks::<2>().0) {
            let hi = hex_value(h).ok_or(SignalError::BadInput)?;
            let lo = hex_value(l).ok_or(SignalError::BadInput)?;
            *out = (hi << 4) | lo;
        }
        let (key, secret) = bytes.split_at(IDENTITY_KEY_LEN);
        let identity_key =
            <[u8; IDENTITY_KEY_LEN]>::try_from(key).map_err(|_| SignalError::BadInput)?;
        // Ключ должен разбираться как ключ личности libsignal.
        libsignal_protocol::IdentityKey::decode(&identity_key)
            .map_err(|_| SignalError::BadInput)?;
        Ok(Self {
            identity_key,
            secret: <[u8; PAIRING_SECRET_LEN]>::try_from(secret)
                .map_err(|_| SignalError::BadInput)?,
        })
    }
}

/// Кадр обмена при добавлении контакта.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairFrame {
    /// Карточка подключившегося; `proof` — HMAC секрета QR (в «Добавить рядом» его нет).
    Card {
        /// Карточка (`contact::new_card`).
        card: Vec<u8>,
        /// Доказательство знания секрета QR.
        proof: Option<[u8; PROOF_LEN]>,
    },
    /// Первое сообщение Signal (сообщение начала сессии).
    Start(Vec<u8>),
    /// Ответ по сессии.
    Reply(Vec<u8>),
    /// Отказ пользователя.
    Decline,
    /// Имя для списка «Добавить рядом» (1..=[`MAX_NAME_BYTES`] байт UTF-8).
    Intro(String),
}

impl PairFrame {
    /// `[тип][данные]`; у карточки — `[длина u16 BE][карточка][доказательство 0 или 32]`.
    ///
    /// # Errors
    /// [`SignalError::BadInput`] — пусто или длиннее [`MAX_PAIR_FRAME`].
    pub fn encode(&self) -> Result<Vec<u8>, SignalError> {
        let mut out = Vec::new();
        match self {
            Self::Card { card, proof } => {
                out.push(TAG_CARD);
                let len = u16::try_from(card.len()).map_err(|_| SignalError::BadInput)?;
                if len == 0 {
                    return Err(SignalError::BadInput);
                }
                out.extend_from_slice(&len.to_be_bytes());
                out.extend_from_slice(card);
                if let Some(p) = proof {
                    out.extend_from_slice(p);
                }
            }
            Self::Start(c) | Self::Reply(c) => {
                if c.is_empty() {
                    return Err(SignalError::BadInput);
                }
                out.push(if matches!(self, Self::Start(_)) {
                    TAG_START
                } else {
                    TAG_REPLY
                });
                out.extend_from_slice(c);
            }
            Self::Decline => out.push(TAG_DECLINE),
            Self::Intro(name) => {
                if name.is_empty() || name.len() > MAX_NAME_BYTES {
                    return Err(SignalError::BadInput);
                }
                out.push(TAG_INTRO);
                out.extend_from_slice(name.as_bytes());
            }
        }
        if out.len() > MAX_PAIR_FRAME {
            return Err(SignalError::BadInput);
        }
        Ok(out)
    }

    /// Строгий разбор [`PairFrame::encode`].
    ///
    /// # Errors
    /// [`SignalError::BadInput`].
    pub fn decode(b: &[u8]) -> Result<Self, SignalError> {
        if b.len() > MAX_PAIR_FRAME {
            return Err(SignalError::BadInput);
        }
        let (&tag, rest) = b.split_first().ok_or(SignalError::BadInput)?;
        match tag {
            TAG_CARD => {
                let (len, rest) = rest.split_first_chunk::<2>().ok_or(SignalError::BadInput)?;
                let len = usize::from(u16::from_be_bytes(*len));
                if len == 0 || rest.len() < len {
                    return Err(SignalError::BadInput);
                }
                let (card, proof) = rest.split_at(len);
                let proof = match proof.len() {
                    0 => None,
                    PROOF_LEN => Some(
                        <[u8; PROOF_LEN]>::try_from(proof).map_err(|_| SignalError::BadInput)?,
                    ),
                    _ => return Err(SignalError::BadInput),
                };
                Ok(Self::Card {
                    card: card.to_vec(),
                    proof,
                })
            }
            TAG_START | TAG_REPLY if rest.is_empty() => Err(SignalError::BadInput),
            TAG_START => Ok(Self::Start(rest.to_vec())),
            TAG_REPLY => Ok(Self::Reply(rest.to_vec())),
            TAG_DECLINE if rest.is_empty() => Ok(Self::Decline),
            TAG_INTRO if !rest.is_empty() && rest.len() <= MAX_NAME_BYTES => {
                let name = core::str::from_utf8(rest).map_err(|_| SignalError::BadInput)?;
                Ok(Self::Intro(name.to_owned()))
            }
            _ => Err(SignalError::BadInput),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DeviceIdentity;
    use crate::beacon::new_pairing_secret;

    fn invite() -> QrInvite {
        let key = DeviceIdentity::generate().public_key();
        QrInvite {
            identity_key: key.try_into().unwrap(),
            secret: new_pairing_secret(),
        }
    }

    #[test]
    fn qr_round_trip_is_uppercase_alphanumeric() {
        let i = invite();
        let s = i.encode();
        assert_eq!(s.len(), 4 + 98);
        assert!(
            s[4..]
                .bytes()
                .all(|c| c.is_ascii_digit() || c.is_ascii_uppercase())
        );
        assert_eq!(QrInvite::decode(&s).unwrap(), i);
        assert!(!format!("{i:?}").contains(&s[4..10]));
    }

    #[test]
    fn foreign_qr_is_rejected() {
        let s = invite().encode();
        let lower = format!("SM1:{}", s[4..].to_lowercase());
        let mut bad_key = s.clone().into_bytes();
        bad_key[4] = b'0';
        bad_key[5] = b'9'; // тип ключа 0x09 — не ключ libsignal
        for bad in [
            "",
            "https://example.org",
            &s[..s.len() - 1],
            &format!("{s}0"),
            &format!("SM2:{}", &s[4..]),
            &lower,
            core::str::from_utf8(&bad_key).unwrap(),
        ] {
            assert!(QrInvite::decode(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn frames_round_trip() {
        for f in [
            PairFrame::Card {
                card: vec![1; 1800],
                proof: Some([9; 32]),
            },
            PairFrame::Card {
                card: vec![1],
                proof: None,
            },
            PairFrame::Start(vec![3; 2000]),
            PairFrame::Reply(vec![2]),
            PairFrame::Decline,
            PairFrame::Intro("Оля".to_owned()),
        ] {
            assert_eq!(PairFrame::decode(&f.encode().unwrap()).unwrap(), f);
        }
    }

    #[test]
    fn bad_frames_are_rejected() {
        let card = PairFrame::Card {
            card: vec![1; 10],
            proof: Some([0; 32]),
        }
        .encode()
        .unwrap();
        for bad in [
            &[][..],
            &[0],
            &[5],
            &[2],
            &[3],
            &[4, 0],
            &[5],
            &[5, 0xFF],
            &[&[5][..], &[b'a'; 129]].concat(),
            &[1, 0, 0],
            &[1, 0, 5, 1],
            &card[..card.len() - 1],
            &[card.as_slice(), &[0]].concat(),
            &vec![2; MAX_PAIR_FRAME + 1],
        ] {
            assert!(PairFrame::decode(bad).is_err());
        }
        assert!(PairFrame::Start(vec![]).encode().is_err());
        assert!(PairFrame::Intro(String::new()).encode().is_err());
        assert!(PairFrame::Intro("я".repeat(65)).encode().is_err());
        assert!(PairFrame::Start(vec![0; MAX_PAIR_FRAME]).encode().is_err());
        assert!(
            PairFrame::Card {
                card: vec![],
                proof: None
            }
            .encode()
            .is_err()
        );
    }
}
