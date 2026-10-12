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
//! Версия 1 — содержимое открыто. Версия 2 — с кодом (ADR-133, ADR-142):
//!
//! ```text
//! [версия u8 = 2][соль 16][nonce 12][AES-256-GCM(ключ ссылки, nonce, AAD, содержимое версии 1 без байта версии)][метка 16]
//! ключ ссылки = HMAC-SHA256(ключ кода, "sm-invite-code/v2" ‖ 0x01)   — HKDF-Expand (RFC 5869), один блок
//! AAD = "sm-invite-link/v2" ‖ версия ‖ соль
//! ```
//!
//! Ключ кода (32 байта) — Argon2id от 4 слов с солью из ссылки; считает приложение (`org.signal:argon2`,
//! ADR-142): ядро Argon2 не содержит (ADR-1). Неверный код — не сошлась метка: [`InviteError::WrongCode`].
//! Примитивы — `Aes256GcmEncryption`/`Decryption` и `CryptographicMac` из `signal-crypto` (libsignal);
//! своей криптографии нет.

use rand::rngs::OsRng;
use rand::{RngCore as _, TryRngCore as _};
use signal_crypto::{Aes256GcmDecryption, Aes256GcmEncryption, CryptographicMac};

use crate::beacon::PAIRING_SECRET_LEN;
use crate::contact::IDENTITY_KEY_LEN;
use crate::node::{MAX_SIGNED, SIGNATURE_LEN};
use crate::pairing::SENDER_ID_LEN;

/// Версия формата: содержимое без кода.
pub const VERSION: u8 = 1;
/// Версия формата с кодом (ADR-142).
pub const VERSION_CODED: u8 = 2;
/// Длина соли Argon2id в ссылке с кодом.
pub const SALT_LEN: usize = 16;
/// Длина ключа кода — результата Argon2id.
pub const CODE_KEY_LEN: usize = 32;
/// Срок ссылки (ADR-83).
pub const LIFETIME_SECS: u64 = 24 * 3600;
/// Запас на расхождение часов телефонов: срок дальше `LIFETIME_SECS` + запас — не наша ссылка.
pub const CLOCK_SLACK_SECS: u64 = 3600;

const HEAD: usize = 1 + IDENTITY_KEY_LEN + PAIRING_SECRET_LEN + 8 + SENDER_ID_LEN + 2;
const MAX_BYTES: usize = HEAD + MAX_SIGNED;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
const CODED_OVERHEAD: usize = SALT_LEN + NONCE_LEN + TAG_LEN;
const DOMAIN_KEY: &[u8] = b"sm-invite-code/v2";
const AAD_PREFIX: &[u8] = b"sm-invite-link/v2";
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
    /// Ссылка с кодом, код не подошёл (или текст повреждён: метка не сошлась).
    WrongCode,
}

/// Вид ссылки по первому байту: можно ли открыть сразу или нужен код.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Версия 1: содержимое открыто.
    Open,
    /// Версия 2: нужен код.
    Coded,
}

impl LinkInvite {
    /// Текст ссылки.
    ///
    /// # Errors
    /// [`InviteError::BadFormat`] — запись узла пустая по подписи или длиннее предела.
    pub fn encode(&self) -> Result<String, InviteError> {
        let mut b = Vec::with_capacity(HEAD.saturating_add(self.node.len()));
        b.push(VERSION);
        b.extend_from_slice(&self.body()?);
        Ok(base64url(&b))
    }

    /// Содержимое без байта версии (общее для версий 1 и 2).
    fn body(&self) -> Result<Vec<u8>, InviteError> {
        if self.node.len() <= SIGNATURE_LEN || self.node.len() > MAX_SIGNED {
            return Err(InviteError::BadFormat);
        }
        let len = u16::try_from(self.node.len()).map_err(|_| InviteError::BadFormat)?;
        let mut b = Vec::with_capacity(HEAD.saturating_add(self.node.len()));
        b.extend_from_slice(&self.identity_key);
        b.extend_from_slice(&self.secret);
        b.extend_from_slice(&self.expires.to_be_bytes());
        b.extend_from_slice(&self.sender_id);
        b.extend_from_slice(&len.to_be_bytes());
        b.extend_from_slice(&self.node);
        Ok(b)
    }

    /// Текст ссылки с кодом (версия 2): содержимое шифруется ключом из `code_key` — результата
    /// Argon2id от кода и `salt` (ADR-142). Соль и nonce лежат в ссылке открыто.
    ///
    /// # Errors
    /// [`InviteError::BadFormat`] — запись узла пустая по подписи или длиннее предела, либо
    /// `signal-crypto` отказал.
    pub fn encode_coded(
        &self,
        salt: &[u8; SALT_LEN],
        code_key: &[u8; CODE_KEY_LEN],
    ) -> Result<String, InviteError> {
        let mut nonce = [0u8; NONCE_LEN];
        OsRng.unwrap_err().fill_bytes(&mut nonce);
        self.encode_coded_with(salt, code_key, &nonce)
    }

    fn encode_coded_with(
        &self,
        salt: &[u8; SALT_LEN],
        code_key: &[u8; CODE_KEY_LEN],
        nonce: &[u8; NONCE_LEN],
    ) -> Result<String, InviteError> {
        let key = link_key(code_key)?;
        let mut enc = Aes256GcmEncryption::new(&key, nonce, &aad(salt))
            .map_err(|_| InviteError::BadFormat)?;
        let mut b = Vec::with_capacity(
            HEAD.saturating_add(self.node.len())
                .saturating_add(CODED_OVERHEAD),
        );
        b.push(VERSION_CODED);
        b.extend_from_slice(salt);
        b.extend_from_slice(nonce);
        let start = b.len();
        b.extend_from_slice(&self.body()?);
        if let Some(body) = b.get_mut(start..) {
            enc.encrypt(body);
        }
        b.extend_from_slice(&enc.compute_tag());
        Ok(base64url(&b))
    }

    /// Вид ссылки (по версии): открывается сразу или нужен код.
    ///
    /// # Errors
    /// [`InviteError::BadFormat`] — не ссылка-приглашение.
    pub fn kind(text: &str) -> Result<Kind, InviteError> {
        match Self::raw(text)?.first() {
            Some(&VERSION) => Ok(Kind::Open),
            Some(&VERSION_CODED) => Ok(Kind::Coded),
            _ => Err(InviteError::BadFormat),
        }
    }

    /// Соль Argon2id из ссылки с кодом: приложение считает по ней ключ кода.
    ///
    /// # Errors
    /// [`InviteError::BadFormat`] — не ссылка с кодом или текст обрезан.
    pub fn coded_salt(text: &str) -> Result<[u8; SALT_LEN], InviteError> {
        let b = Self::raw(text)?;
        let (version, rest) = b.split_first().ok_or(InviteError::BadFormat)?;
        let (salt, rest) = rest
            .split_first_chunk::<SALT_LEN>()
            .ok_or(InviteError::BadFormat)?;
        if *version != VERSION_CODED || rest.len() < NONCE_LEN + TAG_LEN {
            return Err(InviteError::BadFormat);
        }
        Ok(*salt)
    }

    /// Новая случайная соль для ссылки с кодом (`OsRng`).
    #[must_use]
    pub fn salt_new() -> [u8; SALT_LEN] {
        let mut s = [0u8; SALT_LEN];
        OsRng.unwrap_err().fill_bytes(&mut s);
        s
    }

    /// Открывает ссылку с кодом на момент `now` (секунды Unix) ключом кода `code_key`.
    ///
    /// # Errors
    /// [`InviteError::WrongCode`] — метка не сошлась (другой код или повреждение);
    /// [`InviteError::BadFormat`] — не ссылка с кодом; [`InviteError::Expired`] — срок.
    pub fn decode_coded(
        text: &str,
        code_key: &[u8; CODE_KEY_LEN],
        now: u64,
    ) -> Result<Self, InviteError> {
        let b = Self::raw(text)?;
        let (version, rest) = b.split_first().ok_or(InviteError::BadFormat)?;
        let (salt, rest) = rest
            .split_first_chunk::<SALT_LEN>()
            .ok_or(InviteError::BadFormat)?;
        let (nonce, rest) = rest
            .split_first_chunk::<NONCE_LEN>()
            .ok_or(InviteError::BadFormat)?;
        if *version != VERSION_CODED {
            return Err(InviteError::BadFormat);
        }
        let (body, tag) = rest
            .split_at_checked(rest.len().saturating_sub(TAG_LEN))
            .ok_or(InviteError::BadFormat)?;
        if tag.len() != TAG_LEN {
            return Err(InviteError::BadFormat);
        }
        let key = link_key(code_key)?;
        let mut dec = Aes256GcmDecryption::new(&key, nonce, &aad(salt))
            .map_err(|_| InviteError::BadFormat)?;
        let mut plain = body.to_vec();
        dec.decrypt(&mut plain);
        dec.verify_tag(tag).map_err(|_| InviteError::WrongCode)?;
        Self::parse_body(&plain, now)
    }

    /// Байты ссылки любой версии: пробелы по краям отбрасываются, длина ограничена.
    fn raw(text: &str) -> Result<Vec<u8>, InviteError> {
        let text = text.trim();
        if text.len() > (MAX_BYTES + CODED_OVERHEAD).div_ceil(3).saturating_mul(4) {
            return Err(InviteError::BadFormat);
        }
        unbase64url(text.as_bytes()).ok_or(InviteError::BadFormat)
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
        Self::parse_body(rest, now)
    }

    /// Разбор содержимого без байта версии (общий для версий 1 и 2) и проверка срока.
    fn parse_body(rest: &[u8], now: u64) -> Result<Self, InviteError> {
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

/// Ключ шифрования ссылки: HKDF-Expand (RFC 5869) на один блок — `HMAC(ключ кода, домен ‖ 0x01)`.
fn link_key(code_key: &[u8; CODE_KEY_LEN]) -> Result<[u8; 32], InviteError> {
    let mut m =
        CryptographicMac::new("HmacSha256", code_key).map_err(|_| InviteError::BadFormat)?;
    m.update(DOMAIN_KEY);
    m.update(&[1]);
    <[u8; 32]>::try_from(m.finalize().as_slice()).map_err(|_| InviteError::BadFormat)
}

/// Связанные данные: версия и соль не подменить.
fn aad(salt: &[u8; SALT_LEN]) -> Vec<u8> {
    let mut a = Vec::with_capacity(AAD_PREFIX.len().saturating_add(1).saturating_add(SALT_LEN));
    a.extend_from_slice(AAD_PREFIX);
    a.push(VERSION_CODED);
    a.extend_from_slice(salt);
    a
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

    const KEY: [u8; CODE_KEY_LEN] = [7; CODE_KEY_LEN];

    #[test]
    fn coded_round_trip() {
        let i = invite();
        let salt = LinkInvite::salt_new();
        let s = i.encode_coded(&salt, &KEY).unwrap();
        assert_eq!(s.len(), ((HEAD + 300 + CODED_OVERHEAD) * 4).div_ceil(3));
        assert!(
            s.bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        );
        assert_eq!(LinkInvite::kind(&s), Ok(Kind::Coded));
        assert_eq!(LinkInvite::coded_salt(&s), Ok(salt));
        assert_eq!(
            LinkInvite::decode_coded(&format!(" {s}\n"), &KEY, NOW),
            Ok(i)
        );
        // Содержимое не видно без кода: ни ключа личности, ни записи узла в открытом виде.
        let raw = unbase64url(s.as_bytes()).unwrap();
        assert!(!raw.windows(20).any(|w| w == [9u8; 20]));
    }

    #[test]
    fn coded_link_is_not_open_link() {
        let i = invite();
        let open = i.encode().unwrap();
        let coded = i.encode_coded(&[1; SALT_LEN], &KEY).unwrap();
        assert_eq!(LinkInvite::kind(&open), Ok(Kind::Open));
        assert_eq!(LinkInvite::decode(&coded, NOW), Err(InviteError::BadFormat));
        assert_eq!(
            LinkInvite::decode_coded(&open, &KEY, NOW),
            Err(InviteError::BadFormat)
        );
        assert_eq!(LinkInvite::coded_salt(&open), Err(InviteError::BadFormat));
        for bad in ["", "AA", "SM1:00", "uncensored:i/abc"] {
            assert_eq!(LinkInvite::kind(bad), Err(InviteError::BadFormat), "{bad}");
        }
    }

    #[test]
    fn wrong_code_or_tampering_is_wrong_code() {
        let i = invite();
        let salt = [3; SALT_LEN];
        let s = i.encode_coded(&salt, &KEY).unwrap();
        let mut other = KEY;
        other[0] ^= 1;
        assert_eq!(
            LinkInvite::decode_coded(&s, &other, NOW),
            Err(InviteError::WrongCode)
        );
        // Подмена любого байта после версии — соль, nonce, шифртекст, метка.
        let raw = unbase64url(s.as_bytes()).unwrap();
        for pos in [
            1,
            SALT_LEN,
            1 + SALT_LEN,
            1 + SALT_LEN + NONCE_LEN,
            raw.len() - 1,
        ] {
            let mut t = raw.clone();
            t[pos] ^= 1;
            assert_eq!(
                LinkInvite::decode_coded(&base64url(&t), &KEY, NOW),
                Err(InviteError::WrongCode),
                "{pos}"
            );
        }
        // Обрезанный текст — не ссылка (не WrongCode: код тут ни при чём).
        assert!(LinkInvite::decode_coded(&s[..s.len() - 2], &KEY, NOW).is_err());
        assert_eq!(
            LinkInvite::decode_coded(&s[..10], &KEY, NOW),
            Err(InviteError::BadFormat)
        );
    }

    #[test]
    fn coded_expiry_is_checked_after_decryption() {
        let i = invite();
        let s = i.encode_coded(&[1; SALT_LEN], &KEY).unwrap();
        assert!(LinkInvite::decode_coded(&s, &KEY, i.expires - 1).is_ok());
        assert_eq!(
            LinkInvite::decode_coded(&s, &KEY, i.expires),
            Err(InviteError::Expired)
        );
        // Срок не раскрывается тому, кто не знает код.
        let mut other = KEY;
        other[1] ^= 1;
        assert_eq!(
            LinkInvite::decode_coded(&s, &other, i.expires),
            Err(InviteError::WrongCode)
        );
    }

    #[test]
    fn coded_nonce_is_fresh_and_salt_is_bound() {
        let i = invite();
        let salt = [4; SALT_LEN];
        let a = i.encode_coded(&salt, &KEY).unwrap();
        let b = i.encode_coded(&salt, &KEY).unwrap();
        assert_ne!(a, b);
        // Соль привязана AAD: подменённая соль не открывается даже тем же ключом.
        let mut raw = unbase64url(a.as_bytes()).unwrap();
        raw[1] ^= 1;
        assert_eq!(
            LinkInvite::decode_coded(&base64url(&raw), &KEY, NOW),
            Err(InviteError::WrongCode)
        );
        let bad_node = LinkInvite {
            node: vec![1; SIGNATURE_LEN],
            ..i
        };
        assert_eq!(
            bad_node.encode_coded(&salt, &KEY),
            Err(InviteError::BadFormat)
        );
    }

    #[test]
    fn node_text_round_trips() {
        let i = invite();
        let text = i.node_text();
        assert!(text.starts_with(crate::node::TEXT_PREFIX));
        assert_eq!(crate::node::signed_bytes(&text).unwrap(), i.node);
    }
}
