//! Ссылка-приглашение (ADR-83, ADR-133): текст base64url без приставки и без `=`.
//!
//! ```text
//! [версия u8 = 1][ключ личности 33][секрет 16][действует до u64 BE, с][ID отправителя 32]
//! [длина записи узла u16 BE][запись узла пригласившего: тело ‖ подпись]
//! ```
//!
//! Секрет — одноразовый, как в «Моём QR» (ADR-62): получивший ссылку доказывает его знание
//! в [`crate::pairing::PairFrame::LinkCard`]. ID отправителя — входящая очередь пригласившего
//! для этой ссылки (`queue::invite_recipient_id`). Запись узла здесь только разбирается по
//! длине (длина впереди — обрезанный при пересылке текст не примется); подпись владельца и срок проверяет `node::open` над [`LinkInvite::node_text`].
//! Версия 1 — содержимое открыто; код к ссылке (ADR-133) — следующая версия формата.

use crate::beacon::PAIRING_SECRET_LEN;
use crate::contact::IDENTITY_KEY_LEN;
use crate::node::{MAX_SIGNED, SIGNATURE_LEN};
use crate::pairing::SENDER_ID_LEN;

/// Версия формата: содержимое без кода.
pub const VERSION: u8 = 1;
/// Срок ссылки (ADR-83).
pub const LIFETIME_SECS: u64 = 24 * 3600;
/// Запас на расхождение часов телефонов: срок дальше `LIFETIME_SECS` + запас — не наша ссылка.
pub const CLOCK_SLACK_SECS: u64 = 3600;

const HEAD: usize = 1 + IDENTITY_KEY_LEN + PAIRING_SECRET_LEN + 8 + SENDER_ID_LEN + 2;
const MAX_BYTES: usize = HEAD + MAX_SIGNED;
const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// Содержимое ссылки.
#[derive(Clone, PartialEq, Eq)]
pub struct LinkInvite {
    /// Открытый ключ личности пригласившего.
    pub identity_key: [u8; IDENTITY_KEY_LEN],
    /// Одноразовый секрет ссылки.
    pub secret: [u8; PAIRING_SECRET_LEN],
    /// До какого момента действует, секунды Unix.
    pub expires: u64,
    /// ID отправителя входящей очереди пригласившего.
    pub sender_id: [u8; SENDER_ID_LEN],
    /// Подписанная запись узла (`node::signed_bytes`).
    pub node: Vec<u8>,
}

impl core::fmt::Debug for LinkInvite {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("LinkInvite")
            .field("expires", &self.expires)
            .finish_non_exhaustive()
    }
}

/// Почему ссылка не принята.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InviteError {
    /// Не ссылка-приглашение: знаки, длина, версия или поля.
    BadFormat,
    /// Срок истёк (или указан дальше, чем живёт ссылка).
    Expired,
}

impl LinkInvite {
    /// Текст ссылки.
    ///
    /// # Errors
    /// [`InviteError::BadFormat`] — запись узла пустая по подписи или длиннее предела.
    pub fn encode(&self) -> Result<String, InviteError> {
        if self.node.len() <= SIGNATURE_LEN || self.node.len() > MAX_SIGNED {
            return Err(InviteError::BadFormat);
        }
        let len = u16::try_from(self.node.len()).map_err(|_| InviteError::BadFormat)?;
        let mut b = Vec::with_capacity(HEAD.saturating_add(self.node.len()));
        b.push(VERSION);
        b.extend_from_slice(&self.identity_key);
        b.extend_from_slice(&self.secret);
        b.extend_from_slice(&self.expires.to_be_bytes());
        b.extend_from_slice(&self.sender_id);
        b.extend_from_slice(&len.to_be_bytes());
        b.extend_from_slice(&self.node);
        Ok(base64url(&b))
    }

    /// Разбирает вставленный текст на момент `now` (секунды Unix); пробелы и переводы строк по
    /// краям отбрасываются. Ключ личности проверяется как ключ libsignal.
    ///
    /// # Errors
    /// [`InviteError`].
    pub fn decode(text: &str, now: u64) -> Result<Self, InviteError> {
        let text = text.trim();
        if text.len() > MAX_BYTES.div_ceil(3).saturating_mul(4) {
            return Err(InviteError::BadFormat);
        }
        let b = unbase64url(text.as_bytes()).ok_or(InviteError::BadFormat)?;
        let (&version, rest) = b.split_first().ok_or(InviteError::BadFormat)?;
        if version != VERSION {
            return Err(InviteError::BadFormat);
        }
        let (identity_key, rest) = rest
            .split_first_chunk::<IDENTITY_KEY_LEN>()
            .ok_or(InviteError::BadFormat)?;
        libsignal_protocol::IdentityKey::decode(identity_key)
            .map_err(|_| InviteError::BadFormat)?;
        let (secret, rest) = rest
            .split_first_chunk::<PAIRING_SECRET_LEN>()
            .ok_or(InviteError::BadFormat)?;
        let (expires, rest) = rest
            .split_first_chunk::<8>()
            .ok_or(InviteError::BadFormat)?;
        let (sender_id, rest) = rest
            .split_first_chunk::<SENDER_ID_LEN>()
            .ok_or(InviteError::BadFormat)?;
        let (len, node) = rest
            .split_first_chunk::<2>()
            .ok_or(InviteError::BadFormat)?;
        if usize::from(u16::from_be_bytes(*len)) != node.len()
            || node.len() <= SIGNATURE_LEN
            || node.len() > MAX_SIGNED
        {
            return Err(InviteError::BadFormat);
        }
        let expires = u64::from_be_bytes(*expires);
        let latest = now.saturating_add(LIFETIME_SECS + CLOCK_SLACK_SECS);
        if now >= expires || expires > latest {
            return Err(InviteError::Expired);
        }
        Ok(Self {
            identity_key: *identity_key,
            secret: *secret,
            expires,
            sender_id: *sender_id,
            node: node.to_vec(),
        })
    }

    /// Запись узла текстом `SMN1:…` — для `node::open`.
    #[must_use]
    pub fn node_text(&self) -> String {
        crate::node::signed_text(&self.node)
    }
}

const SHIFTS: [u32; 4] = [18, 12, 6, 0];

fn base64url(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3).saturating_mul(4));
    for chunk in data.chunks(3) {
        let (n, chars) = match *chunk {
            [hi] => (u32::from(hi).wrapping_shl(16), 2),
            [hi, mid] => (
                u32::from(hi).wrapping_shl(16) | u32::from(mid).wrapping_shl(8),
                3,
            ),
            [hi, mid, lo, ..] => (
                u32::from(hi).wrapping_shl(16) | u32::from(mid).wrapping_shl(8) | u32::from(lo),
                4,
            ),
            [] => continue,
        };
        for shift in SHIFTS.iter().take(chars) {
            let idx = n.wrapping_shr(*shift) & 0x3F;
            if let Some(&c) = usize::try_from(idx).ok().and_then(|i| ALPHABET.get(i)) {
                out.push(char::from(c));
            }
        }
    }
    out
}

/// Строгий разбор: только алфавит base64url, без `=`, лишние биты в конце — нули
/// (у каждого содержимого ровно один текст).
fn unbase64url(t: &[u8]) -> Option<Vec<u8>> {
    let value = |c: u8| {
        ALPHABET
            .iter()
            .position(|&a| a == c)
            .and_then(|v| u32::try_from(v).ok())
    };
    let mut out = Vec::with_capacity((t.len() / 4).saturating_mul(3).saturating_add(2));
    for chunk in t.chunks(4) {
        let take = match chunk.len() {
            2 => 1,
            3 => 2,
            4 => 3,
            _ => return None,
        };
        let mut n = 0u32;
        for (&c, shift) in chunk.iter().zip(SHIFTS) {
            n |= value(c)?.wrapping_shl(shift);
        }
        let bytes = n.to_be_bytes();
        let (data, tail) = bytes.get(1..)?.split_at_checked(take)?;
        if tail.iter().any(|&x| x != 0) {
            return None;
        }
        out.extend_from_slice(data);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DeviceIdentity;
    use crate::beacon::new_pairing_secret;

    const NOW: u64 = 1_800_000_000;

    fn invite() -> LinkInvite {
        LinkInvite {
            identity_key: DeviceIdentity::generate().public_key().try_into().unwrap(),
            secret: new_pairing_secret(),
            expires: NOW + LIFETIME_SECS,
            sender_id: [5; SENDER_ID_LEN],
            node: vec![9; 300],
        }
    }

    #[test]
    fn round_trip_is_base64url() {
        let i = invite();
        let s = i.encode().unwrap();
        assert_eq!(s.len(), ((HEAD + 300) * 4).div_ceil(3));
        assert!(
            s.bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        );
        assert_eq!(LinkInvite::decode(&format!("  {s}\n"), NOW).unwrap(), i);
        assert!(!format!("{i:?}").contains("secret"));
    }

    #[test]
    fn base64url_matches_rfc4648() {
        // RFC 4648, раздел 10, без `=`; знаки 62 и 63 — `-` и `_`.
        for (raw, text) in [
            (&b""[..], ""),
            (b"f", "Zg"),
            (b"fo", "Zm8"),
            (b"foo", "Zm9v"),
            (b"foob", "Zm9vYg"),
            (b"fooba", "Zm9vYmE"),
            (b"foobar", "Zm9vYmFy"),
            (&[0xFB, 0xFF], "-_8"),
        ] {
            assert_eq!(base64url(raw), text);
            assert_eq!(unbase64url(text.as_bytes()).unwrap(), raw);
        }
        for bad in ["Zg==", "Zh", "Z", "Zm9v+", "Zm9/", "Zm 9v"] {
            assert!(unbase64url(bad.as_bytes()).is_none(), "{bad}");
        }
    }

    #[test]
    fn expiry_is_checked() {
        let i = invite();
        let s = i.encode().unwrap();
        assert!(LinkInvite::decode(&s, i.expires - 1).is_ok());
        assert_eq!(LinkInvite::decode(&s, i.expires), Err(InviteError::Expired));
        let far = LinkInvite {
            expires: NOW + LIFETIME_SECS + CLOCK_SLACK_SECS + 1,
            ..i
        };
        assert_eq!(
            LinkInvite::decode(&far.encode().unwrap(), NOW),
            Err(InviteError::Expired)
        );
    }

    #[test]
    fn foreign_text_is_rejected() {
        let i = invite();
        let s = i.encode().unwrap();
        let mut v2 = s.clone().into_bytes();
        v2[1] = b'I'; // версия 2
        let mut bad_key = s.clone().into_bytes();
        bad_key[2] = b'k'; // тип ключа не 0x05
        for bad in [
            "",
            "SM1:00",
            "uncensored:i/abc",
            &s[..s.len() - 1],
            &s[..s.len() - 4],
            &format!("{s}A"),
            &format!("{s}AAA"),
            core::str::from_utf8(&v2).unwrap(),
            core::str::from_utf8(&bad_key).unwrap(),
        ] {
            assert_eq!(
                LinkInvite::decode(bad, NOW),
                Err(InviteError::BadFormat),
                "{bad}"
            );
        }
        for node in [vec![1; SIGNATURE_LEN], vec![1; MAX_SIGNED + 1]] {
            let i = LinkInvite { node, ..i.clone() };
            assert_eq!(i.encode(), Err(InviteError::BadFormat));
        }
    }

    #[test]
    fn node_text_round_trips() {
        let i = invite();
        let text = i.node_text();
        assert!(text.starts_with(crate::node::TEXT_PREFIX));
        assert_eq!(crate::node::signed_bytes(&text).unwrap(), i.node);
    }
}
