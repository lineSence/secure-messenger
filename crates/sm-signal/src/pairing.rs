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
//!   открытым тем же экраном, поэтому только имя, которое человек указал сам;
//! - [`PairFrame::LinkCard`] — карточка получившего ссылку-приглашение (ADR-83), через узел:
//!   с адресом его почты, куда пригласивший пошлёт [`PairFrame::Start`].

use crate::SignalError;
use crate::beacon::PAIRING_SECRET_LEN;
use crate::contact::{IDENTITY_KEY_LEN, MAX_NAME_BYTES, check_name};
use rand::rngs::OsRng;
use rand::{RngCore as _, TryRngCore as _};
use signal_crypto::{Aes256GcmDecryption, Aes256GcmEncryption, CryptographicMac};
use sm_core::keystore::Secret;

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
const TAG_LINK_CARD: u8 = 6;
const TAG_SEALED_LINK_CARD: u8 = 7;
const LINK_NONCE_LEN: usize = 12;
const LINK_TAG_LEN: usize = 16;
const LINK_KEY_DOMAIN: &[u8] = b"sm-link-card/v2/key";
const LINK_AAD: &[u8] = b"sm-link-card/v2/frame\x07";
/// Длина ID отправителя очереди на узле.
pub const SENDER_ID_LEN: usize = 32;

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
    /// Confidential link response: nonce || ciphertext || GCM tag. Legacy tag 6 is not
    /// accepted by `open_link_card`; no plaintext downgrade for new exchanges.
    SealedLinkCard(Vec<u8>),
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
    /// Карточка получившего ссылку (ADR-83) и адрес его почты: ID отправителя его очереди для
    /// пригласившего и подписанная запись его узла (байты `node::signed_bytes`);
    /// `proof` — `beacon::link_proof` над всем этим.
    LinkCard {
        /// Карточка (`contact::new_card`).
        card: Vec<u8>,
        /// Доказательство знания секрета ссылки.
        proof: [u8; PROOF_LEN],
        /// ID отправителя очереди получившего ссылку.
        sender_id: [u8; SENDER_ID_LEN],
        /// Запись его узла (тело ‖ подпись), не пустая.
        node: Vec<u8>,
    },
}

impl PairFrame {
    /// Seal identity, keys and return mailbox under the random link secret using libsignal
    /// AES-GCM and a separate HMAC-SHA256 key domain. Does not hide traffic/length or the
    /// identity in the later Signal `PreKey` Start. Old clients do not understand wire tag 7.
    ///
    /// # Errors
    /// [`SignalError::BadInput`] for non-LinkCard/oversize; [`SignalError::Crypto`].
    pub fn seal_link_card(
        &self,
        secret: &[u8; PAIRING_SECRET_LEN],
    ) -> Result<Vec<u8>, SignalError> {
        if !matches!(self, Self::LinkCard { .. }) {
            return Err(SignalError::BadInput);
        }
        let plain = Secret::new(self.encode()?);
        if plain.expose().len() > MAX_PAIR_FRAME.saturating_sub(1 + LINK_NONCE_LEN + LINK_TAG_LEN) {
            return Err(SignalError::BadInput);
        }
        let key = link_card_key(secret)?;
        let mut nonce = [0u8; LINK_NONCE_LEN];
        OsRng.unwrap_err().fill_bytes(&mut nonce);
        let mut enc = Aes256GcmEncryption::new(key.expose(), &nonce, LINK_AAD)
            .map_err(|_| SignalError::Crypto)?;
        let mut body = plain.expose().to_vec();
        enc.encrypt(&mut body);
        let mut sealed = nonce.to_vec();
        sealed.extend_from_slice(&body);
        sealed.extend_from_slice(&enc.compute_tag());
        Self::SealedLinkCard(sealed).encode()
    }

    /// Authenticate before parsing or exposing any plaintext; strictly sealed tag 7 only.
    ///
    /// # Errors
    /// [`SignalError::BadInput`] for format; [`SignalError::Crypto`] for wrong secret/tamper.
    pub fn open_link_card(
        frame: &[u8],
        secret: &[u8; PAIRING_SECRET_LEN],
    ) -> Result<Self, SignalError> {
        let Self::SealedLinkCard(sealed) = Self::decode(frame)? else {
            return Err(SignalError::BadInput);
        };
        let (nonce, rest) = sealed.split_at(LINK_NONCE_LEN);
        let (body, tag) = rest.split_at(rest.len().saturating_sub(LINK_TAG_LEN));
        let key = link_card_key(secret)?;
        let mut dec = Aes256GcmDecryption::new(key.expose(), nonce, LINK_AAD)
            .map_err(|_| SignalError::Crypto)?;
        let mut plain = body.to_vec();
        dec.decrypt(&mut plain);
        let plain = Secret::new(plain);
        dec.verify_tag(tag).map_err(|_| SignalError::Crypto)?;
        let card = Self::decode(plain.expose())?;
        if !matches!(card, Self::LinkCard { .. }) {
            return Err(SignalError::BadInput);
        }
        Ok(card)
    }

    /// `[тип][данные]`; у карточки — `[длина u16 BE][карточка][доказательство 0 или 32]`.
    ///
    /// # Errors
    /// [`SignalError::BadInput`] — пусто или длиннее [`MAX_PAIR_FRAME`].
    pub fn encode(&self) -> Result<Vec<u8>, SignalError> {
        let mut out = Vec::new();
        match self {
            Self::SealedLinkCard(sealed) => {
                if sealed.len() < LINK_NONCE_LEN + LINK_TAG_LEN + 1 {
                    return Err(SignalError::BadInput);
                }
                out.push(TAG_SEALED_LINK_CARD);
                out.extend_from_slice(sealed);
            }
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
            Self::LinkCard {
                card,
                proof,
                sender_id,
                node,
            } => {
                let len = u16::try_from(card.len()).map_err(|_| SignalError::BadInput)?;
                if len == 0 || node.is_empty() {
                    return Err(SignalError::BadInput);
                }
                out.push(TAG_LINK_CARD);
                out.extend_from_slice(&len.to_be_bytes());
                out.extend_from_slice(card);
                out.extend_from_slice(proof);
                out.extend_from_slice(sender_id);
                out.extend_from_slice(node);
            }
            Self::Intro(name) => {
                check_name(name.as_bytes())?;
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
            TAG_SEALED_LINK_CARD if rest.len() > LINK_NONCE_LEN + LINK_TAG_LEN => {
                Ok(Self::SealedLinkCard(rest.to_vec()))
            }
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
            TAG_LINK_CARD => {
                let (len, rest) = rest.split_first_chunk::<2>().ok_or(SignalError::BadInput)?;
                let len = usize::from(u16::from_be_bytes(*len));
                if len == 0 {
                    return Err(SignalError::BadInput);
                }
                let (card, rest) = rest.split_at_checked(len).ok_or(SignalError::BadInput)?;
                let (proof, rest) = rest
                    .split_first_chunk::<PROOF_LEN>()
                    .ok_or(SignalError::BadInput)?;
                let (sender_id, node) = rest
                    .split_first_chunk::<SENDER_ID_LEN>()
                    .ok_or(SignalError::BadInput)?;
                if node.is_empty() {
                    return Err(SignalError::BadInput);
                }
                Ok(Self::LinkCard {
                    card: card.to_vec(),
                    proof: *proof,
                    sender_id: *sender_id,
                    node: node.to_vec(),
                })
            }
            TAG_START | TAG_REPLY if rest.is_empty() => Err(SignalError::BadInput),
            TAG_START => Ok(Self::Start(rest.to_vec())),
            TAG_REPLY => Ok(Self::Reply(rest.to_vec())),
            TAG_DECLINE if rest.is_empty() => Ok(Self::Decline),
            TAG_INTRO if !rest.is_empty() && rest.len() <= MAX_NAME_BYTES => {
                Ok(Self::Intro(check_name(rest)?.to_owned()))
            }
            _ => Err(SignalError::BadInput),
        }
    }
}

fn link_card_key(secret: &[u8; PAIRING_SECRET_LEN]) -> Result<Secret, SignalError> {
    let mut mac = CryptographicMac::new("HmacSha256", secret).map_err(|_| SignalError::Crypto)?;
    mac.update(LINK_KEY_DOMAIN);
    Ok(Secret::new(mac.finalize()))
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

    fn link_card() -> PairFrame {
        PairFrame::LinkCard {
            card: vec![0xA5; 1800],
            proof: [0xC6; 32],
            sender_id: [0xD7; 32],
            node: vec![0xE8; 300],
        }
    }

    #[test]
    fn sealed_link_card_round_trip_and_confidential_fields() {
        let secret = new_pairing_secret();
        let card = link_card();
        let a = card.seal_link_card(&secret).unwrap();
        let b = card.seal_link_card(&secret).unwrap();
        assert_eq!(a[0], TAG_SEALED_LINK_CARD);
        assert_ne!(a, b); // fresh 96-bit nonce
        assert_eq!(PairFrame::open_link_card(&a, &secret).unwrap(), card);
        for marker in [0xA5, 0xC6, 0xD7, 0xE8] {
            assert!(!a.windows(32).any(|w| w == [marker; 32]));
        }
        assert_ne!(
            link_card_key(&secret).unwrap().expose(),
            crate::ble_part::pairing_key(&secret).unwrap()
        );
    }

    #[test]
    fn sealed_link_card_wrong_secret_and_every_byte_tamper_fail_closed() {
        let secret = new_pairing_secret();
        let sealed = link_card().seal_link_card(&secret).unwrap();
        let mut wrong = secret;
        wrong[0] ^= 1;
        assert!(PairFrame::open_link_card(&sealed, &wrong).is_err());
        for pos in 0..sealed.len() {
            let mut bad = sealed.clone();
            bad[pos] ^= 1;
            assert!(PairFrame::open_link_card(&bad, &secret).is_err(), "{pos}");
        }
    }

    #[test]
    fn sealed_link_card_rejects_truncation_legacy_downgrade_and_oversize() {
        let secret = new_pairing_secret();
        let card = link_card();
        let sealed = card.seal_link_card(&secret).unwrap();
        for len in 0..sealed.len() {
            assert!(PairFrame::open_link_card(&sealed[..len], &secret).is_err());
        }
        assert!(PairFrame::open_link_card(&card.encode().unwrap(), &secret).is_err());
        assert!(PairFrame::Start(vec![1]).seal_link_card(&secret).is_err());
        assert!(
            PairFrame::LinkCard {
                card: vec![1; MAX_PAIR_FRAME - 100],
                proof: [0; 32],
                sender_id: [0; 32],
                node: vec![1; 50],
            }
            .seal_link_card(&secret)
            .is_err()
        );
        assert!(PairFrame::open_link_card(&vec![7; MAX_PAIR_FRAME + 1], &secret).is_err());
    }

    #[test]
    fn authenticated_non_link_and_malformed_inner_payload_rejected() {
        let secret = new_pairing_secret();
        let key = link_card_key(&secret).unwrap();
        for mut body in [
            vec![TAG_DECLINE],
            vec![TAG_LINK_CARD, 0, 0, 1],
            vec![TAG_LINK_CARD, 0, 1, 1],
        ] {
            let nonce = [0; LINK_NONCE_LEN];
            let mut enc = Aes256GcmEncryption::new(key.expose(), &nonce, LINK_AAD).unwrap();
            enc.encrypt(&mut body);
            let mut sealed = nonce.to_vec();
            sealed.extend(body);
            sealed.extend(enc.compute_tag());
            let frame = PairFrame::SealedLinkCard(sealed).encode().unwrap();
            assert!(PairFrame::open_link_card(&frame, &secret).is_err());
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
            PairFrame::LinkCard {
                card: vec![1; 1800],
                proof: [9; 32],
                sender_id: [8; 32],
                node: vec![7; 4160],
            },
            PairFrame::LinkCard {
                card: vec![1],
                proof: [0; 32],
                sender_id: [0; 32],
                node: vec![7],
            },
        ] {
            assert_eq!(PairFrame::decode(&f.encode().unwrap()).unwrap(), f);
        }
    }

    #[test]
    fn bad_link_cards_are_rejected() {
        let f = |card: usize, node: usize| PairFrame::LinkCard {
            card: vec![1; card],
            proof: [2; 32],
            sender_id: [3; 32],
            node: vec![4; node],
        };
        assert!(f(0, 1).encode().is_err());
        assert!(f(1, 0).encode().is_err());
        assert!(f(4000, 4200).encode().is_err()); // длиннее MAX_PAIR_FRAME
        let ok = f(10, 5).encode().unwrap();
        assert_eq!(ok.len(), 1 + 2 + 10 + 32 + 32 + 5);
        for bad in [
            &ok[..ok.len() - 5], // без записи узла
            &ok[..3 + 10 + 40],  // обрезан ID отправителя
            &[6, 0, 0, 1][..],   // пустая карточка
            &[6, 0, 20, 1][..],  // карточка короче длины
        ] {
            assert!(PairFrame::decode(bad).is_err());
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
        for bad in ["О\u{202E}ля", "Оля\r\n", " ", "О\u{0}ля"] {
            assert!(
                PairFrame::Intro(bad.to_owned()).encode().is_err(),
                "{bad:?}"
            );
            assert!(
                PairFrame::decode(&[&[TAG_INTRO][..], bad.as_bytes()].concat()).is_err(),
                "{bad:?}"
            );
        }
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
