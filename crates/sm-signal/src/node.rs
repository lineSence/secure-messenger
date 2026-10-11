//! Подпись записи узла корневым ключом владельца (ADR-81, ADR-127).
//!
//! Подпись — `XEdDSA` ключом Curve25519 из `libsignal-protocol` (та же библиотека, что и для
//! переписки; отдельный крейт Ed25519 запрещён списком ADR-1). Подписывается
//! [`SIGN_CONTEXT`] ‖ тело (`sm_core::node`), чтобы подпись записи нельзя было выдать за
//! подпись чего-то другого. Текст для QR и передачи — `SMN1:` + HEX(тело ‖ подпись 64 байта),
//! заглавные буквы — как у QR сопряжения (буквенно-цифровой режим QR).
//!
//! Закрытый корневой ключ живёт только у владельца, офлайн (инструмент `sm-node-tool`);
//! открытые — в [`ROOT_KEYS`], то есть в APK.

use libsignal_protocol::{KeyPair, PrivateKey, PublicKey};
use rand::TryRngCore as _;
use rand::rngs::OsRng;
use sm_core::node::NodeRecord;

use crate::SignalError;

/// Открытые корневые ключи владельца (33 байта, `0x05` ‖ ключ Curve25519). Пока пусто —
/// ни одна запись не принимается; ключ добавляет владелец после `sm-node-tool keygen`.
pub const ROOT_KEYS: &[[u8; ROOT_PUBLIC_LEN]] = &[];

/// Длина открытого корневого ключа.
pub const ROOT_PUBLIC_LEN: usize = 33;
/// Длина закрытого корневого ключа.
pub const ROOT_PRIVATE_LEN: usize = 32;
/// Длина подписи `XEdDSA`.
pub const SIGNATURE_LEN: usize = 64;
/// Префикс текста записи.
pub const TEXT_PREFIX: &str = "SMN1:";
/// Контекст подписи.
pub const SIGN_CONTEXT: &[u8] = b"SM node record v1\0";
/// Самое длинное тело записи: 8 доменов по 253, путь 128, 4 пина — с запасом.
const MAX_BODY: usize = 4096;

const HEX: &[u8; 16] = b"0123456789ABCDEF";

/// Почему запись не принята.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NodeError {
    /// Не запись узла: префикс, HEX, длина или поля.
    BadFormat,
    /// Подпись не сходится ни с одним корневым ключом.
    BadSignature,
    /// Срок действия истёк.
    Expired,
}

fn signed_message(body: &[u8]) -> Vec<u8> {
    let mut m = Vec::with_capacity(SIGN_CONTEXT.len().saturating_add(body.len()));
    m.extend_from_slice(SIGN_CONTEXT);
    m.extend_from_slice(body);
    m
}

/// Новая пара корневых ключей: (закрытый 32 байта, открытый 33 байта). Генератор — `OsRng`.
#[must_use]
pub fn root_keygen() -> (Vec<u8>, Vec<u8>) {
    let pair = KeyPair::generate(&mut OsRng.unwrap_err());
    (
        pair.private_key.serialize(),
        pair.public_key.serialize().into_vec(),
    )
}

/// Открытый ключ для закрытого.
///
/// # Errors
/// [`SignalError::BadInput`] — не закрытый ключ Curve25519.
pub fn root_public(private: &[u8]) -> Result<Vec<u8>, SignalError> {
    let key = PrivateKey::deserialize(private).map_err(|_| SignalError::BadInput)?;
    Ok(key
        .public_key()
        .map_err(|_| SignalError::BadInput)?
        .serialize()
        .into_vec())
}

/// Подписывает запись: текст `SMN1:…`.
///
/// # Errors
/// [`SignalError::BadInput`] — поля записи недопустимы или ключ не тот; [`SignalError::Signing`].
pub fn sign(record: &NodeRecord, private: &[u8]) -> Result<String, SignalError> {
    let body = record.encode().ok_or(SignalError::BadInput)?;
    let key = PrivateKey::deserialize(private).map_err(|_| SignalError::BadInput)?;
    let sig = key
        .calculate_signature(&signed_message(&body), &mut OsRng.unwrap_err())
        .map_err(|_| SignalError::Signing)?;
    let mut s = String::from(TEXT_PREFIX);
    for b in body.iter().chain(sig.iter()) {
        for nibble in [b >> 4, b & 0x0F] {
            if let Some(&c) = HEX.get(usize::from(nibble)) {
                s.push(char::from(c));
            }
        }
    }
    Ok(s)
}

fn unhex(text: &[u8]) -> Option<Vec<u8>> {
    let value = |c: u8| {
        HEX.iter()
            .position(|&h| h == c)
            .and_then(|v| u8::try_from(v).ok())
    };
    text.chunks(2)
        .map(|p| match p {
            [hi, lo] => Some((value(*hi)? << 4) | value(*lo)?),
            _ => None,
        })
        .collect()
}

/// Принимает текст записи: подпись одним из корневых ключей `roots`, срок не истёк к `now`
/// (секунды Unix). Пробелы и переводы строк по краям отбрасываются.
///
/// # Errors
/// [`NodeError`].
pub fn open(
    text: &str,
    roots: &[[u8; ROOT_PUBLIC_LEN]],
    now: u64,
) -> Result<NodeRecord, NodeError> {
    let hex = text
        .trim()
        .strip_prefix(TEXT_PREFIX)
        .ok_or(NodeError::BadFormat)?
        .as_bytes();
    if hex.len() > 2 * (MAX_BODY + SIGNATURE_LEN) {
        return Err(NodeError::BadFormat);
    }
    let bytes = unhex(hex).ok_or(NodeError::BadFormat)?;
    let split = bytes
        .len()
        .checked_sub(SIGNATURE_LEN)
        .ok_or(NodeError::BadFormat)?;
    let (body, sig) = bytes.split_at_checked(split).ok_or(NodeError::BadFormat)?;
    let record = NodeRecord::decode(body).map_err(|_| NodeError::BadFormat)?;
    let message = signed_message(body);
    let signed = roots
        .iter()
        .any(|k| PublicKey::deserialize(k).is_ok_and(|pk| pk.verify_signature(&message, sig)));
    if !signed {
        return Err(NodeError::BadSignature);
    }
    if now >= record.expires {
        return Err(NodeError::Expired);
    }
    Ok(record)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record() -> NodeRecord {
        NodeRecord {
            issued: 1_000,
            expires: 2_000,
            port: 443,
            path: "/api_x".into(),
            domains: vec!["a.example.org".into(), "b.example.net".into()],
            pins: vec![[7; 32]],
        }
    }

    fn root() -> (Vec<u8>, [u8; ROOT_PUBLIC_LEN]) {
        let (sk, pk) = root_keygen();
        (sk, pk.try_into().unwrap())
    }

    #[test]
    fn signed_record_opens() {
        let (sk, pk) = root();
        assert_eq!(root_public(&sk).unwrap(), pk.to_vec());
        let text = sign(&record(), &sk).unwrap();
        assert!(text.starts_with(TEXT_PREFIX));
        assert!(text[TEXT_PREFIX.len()..].bytes().all(|c| HEX.contains(&c)));
        assert_eq!(open(&format!(" {text}\n"), &[pk], 1_500), Ok(record()));
        // Второй корневой ключ (запасной) тоже принимается.
        let (_, other) = root();
        assert_eq!(open(&text, &[other, pk], 1_500), Ok(record()));
    }

    #[test]
    fn foreign_key_tamper_and_expiry_rejected() {
        let (sk, pk) = root();
        let (_, other) = root();
        let text = sign(&record(), &sk).unwrap();
        assert_eq!(open(&text, &[other], 1_500), Err(NodeError::BadSignature));
        assert_eq!(open(&text, &[], 1_500), Err(NodeError::BadSignature));
        assert_eq!(open(&text, &[pk], 2_000), Err(NodeError::Expired));
        // Подмена порта (байты 17–18 тела = знаки 34–37 HEX) ломает подпись.
        let mut forged = text.clone().into_bytes();
        let i = TEXT_PREFIX.len() + 2 * 18 + 1;
        forged[i] = if forged[i] == b'1' { b'2' } else { b'1' };
        let forged = String::from_utf8(forged).unwrap();
        assert_eq!(open(&forged, &[pk], 1_500), Err(NodeError::BadSignature));
    }

    #[test]
    fn malformed_text_rejected() {
        let (sk, pk) = root();
        let text = sign(&record(), &sk).unwrap();
        for bad in [
            String::new(),
            "SMN1:".into(),
            text.to_lowercase(),
            text.replacen("SMN1:", "SMN2:", 1),
            text[..text.len() - 1].to_string(),
            format!("{text}0"),
            format!("{text}00"),
            "SMN1:".to_string() + &"00".repeat(SIGNATURE_LEN),
        ] {
            assert!(
                matches!(
                    open(&bad, &[pk], 1_500),
                    Err(NodeError::BadFormat | NodeError::BadSignature)
                ),
                "{bad:.20}"
            );
        }
    }

    #[test]
    fn invalid_record_not_signed() {
        let (sk, _) = root();
        let mut r = record();
        r.port = 0;
        assert!(matches!(sign(&r, &sk), Err(SignalError::BadInput)));
        assert!(matches!(
            sign(&record(), &[1, 2, 3]),
            Err(SignalError::BadInput)
        ));
    }

    #[test]
    fn no_root_keys_until_owner_adds_one() {
        let (sk, _) = root();
        let text = sign(&record(), &sk).unwrap();
        assert_eq!(open(&text, ROOT_KEYS, 1_500), Err(NodeError::BadSignature));
    }
}
