//! Подпись записи узла корневым ключом владельца (ADR-81, ADR-127).
//!
//! Подпись — `XEdDSA` ключом Curve25519 из `libsignal-protocol` (та же библиотека, что и для
//! переписки; отдельный крейт Ed25519 запрещён списком ADR-1). Подписывается
//! [`SIGN_CONTEXT`] ‖ тело (`sm_core::node`), чтобы подпись записи нельзя было выдать за
//! подпись чего-то другого. Текст для QR и передачи — `SMN1:` + HEX(тело ‖ подпись 64 байта),
//! заглавные буквы — как у QR сопряжения (буквенно-цифровой режим QR).
//!
//! Закрытый корневой ключ живёт только у владельца, офлайн (инструмент `sm-node-tool`);
//! открытые — в [`ROOT_KEYS`] (`sm_core::node`): в APK и в узле — он проверяет ими пропуска
//! на `NEW` (ADR-136).

use libsignal_protocol::{KeyPair, PrivateKey, PublicKey};
use rand::TryRngCore as _;
use rand::rngs::OsRng;
use sm_core::node::NodeRecord;
use sm_core::pass::{self, QueuePass};

use crate::SignalError;

pub use sm_core::node::{ROOT_KEYS, ROOT_PUBLIC_LEN};

/// Длина закрытого корневого ключа.
pub const ROOT_PRIVATE_LEN: usize = 32;
/// Длина подписи `XEdDSA`.
pub const SIGNATURE_LEN: usize = 64;
/// Префикс текста записи.
pub const TEXT_PREFIX: &str = "SMN1:";
/// Префикс текста пропуска на `NEW` (ADR-136).
pub const PASS_PREFIX: &str = "SMP1:";
/// Контекст подписи.
pub const SIGN_CONTEXT: &[u8] = b"SM node record v1\0";
/// Самое длинное тело записи: 8 доменов по 253 с 4 пинами, путь 128, 32 отозванных — около
/// 3,7 КБ (`sm_core::node`, тест `largest_record_fits_mailbox_message`), с запасом.
const MAX_BODY: usize = 4096;
/// Самая длинная подписанная запись в байтах (тело ‖ подпись).
pub const MAX_SIGNED: usize = MAX_BODY + SIGNATURE_LEN;

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

/// Байты подписанной записи (тело ‖ подпись) из текста `SMN1:…` — для ссылки-приглашения
/// (ADR-83), где HEX удлинил бы текст вдвое. Подпись и поля здесь не проверяются: это делает
/// [`open`] над [`signed_text`].
///
/// # Errors
/// [`NodeError::BadFormat`] — не текст записи или длина вне `SIGNATURE_LEN + 1..=MAX_SIGNED`.
pub fn signed_bytes(text: &str) -> Result<Vec<u8>, NodeError> {
    let hex = text
        .trim()
        .strip_prefix(TEXT_PREFIX)
        .ok_or(NodeError::BadFormat)?
        .as_bytes();
    if hex.len() > 2 * MAX_SIGNED || hex.len() <= 2 * SIGNATURE_LEN {
        return Err(NodeError::BadFormat);
    }
    unhex(hex).ok_or(NodeError::BadFormat)
}

/// Текст `SMN1:…` из байтов [`signed_bytes`].
#[must_use]
pub fn signed_text(bytes: &[u8]) -> String {
    format!("{TEXT_PREFIX}{}", hex_upper(bytes))
}

fn hex_upper(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len().saturating_mul(2));
    for b in bytes {
        for nibble in [b >> 4, b & 0x0F] {
            if let Some(&c) = HEX.get(usize::from(nibble)) {
                s.push(char::from(c));
            }
        }
    }
    s
}

/// Подписывает пропуск на `NEW` (ADR-136): текст `SMP1:` + HEX(пропуск).
///
/// # Errors
/// [`SignalError::BadInput`] — ключ не тот; [`SignalError::Signing`].
pub fn sign_pass(p: &QueuePass, private: &[u8]) -> Result<String, SignalError> {
    let key = PrivateKey::deserialize(private).map_err(|_| SignalError::BadInput)?;
    let sig = key
        .calculate_signature(&pass::signed_message(&p.body()), &mut OsRng.unwrap_err())
        .map_err(|_| SignalError::Signing)?;
    let sig: [u8; pass::SIGNATURE_LEN] = sig[..].try_into().map_err(|_| SignalError::Signing)?;
    Ok(format!(
        "{PASS_PREFIX}{}",
        hex_upper(&p.with_signature(&sig))
    ))
}

/// Принимает текст пропуска: подпись одним из `roots`, срок не истёк к `now`. Возвращает поля
/// и байты пропуска — их клиент шлёт в `NEW`; окончательно пропуск проверяет узел.
///
/// # Errors
/// [`NodeError`].
pub fn open_pass(
    text: &str,
    roots: &[[u8; ROOT_PUBLIC_LEN]],
    now: u64,
) -> Result<(QueuePass, Vec<u8>), NodeError> {
    let hex = text
        .trim()
        .strip_prefix(PASS_PREFIX)
        .ok_or(NodeError::BadFormat)?
        .as_bytes();
    if hex.len() != 2 * pass::PASS_LEN {
        return Err(NodeError::BadFormat);
    }
    let bytes = unhex(hex).ok_or(NodeError::BadFormat)?;
    let (p, message, sig) = QueuePass::parse(&bytes).map_err(|_| NodeError::BadFormat)?;
    let signed = roots
        .iter()
        .any(|k| PublicKey::deserialize(k).is_ok_and(|pk| pk.verify_signature(&message, &sig)));
    if !signed {
        return Err(NodeError::BadSignature);
    }
    if now >= p.expires {
        return Err(NodeError::Expired);
    }
    Ok((p, bytes))
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
            domains: ["a.example.org", "b.example.net"]
                .map(|name| sm_core::node::NodeDomain {
                    name: name.into(),
                    pins: vec![[7; 32]],
                })
                .to_vec(),
            revoked: Vec::new(),
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
    fn owner_root_key_is_set() {
        assert_eq!(ROOT_KEYS.len(), 1);
        assert_eq!(ROOT_KEYS[0][0], 0x05);
        assert!(libsignal_protocol::PublicKey::deserialize(&ROOT_KEYS[0]).is_ok());
    }

    #[test]
    fn record_signed_by_foreign_key_is_rejected() {
        let (sk, _) = root();
        let text = sign(&record(), &sk).unwrap();
        assert_eq!(open(&text, ROOT_KEYS, 1_500), Err(NodeError::BadSignature));
    }

    #[test]
    fn pass_sign_and_open() {
        let (private, public) = root_keygen();
        let roots: [[u8; ROOT_PUBLIC_LEN]; 1] = [public.as_slice().try_into().unwrap()];
        let p = QueuePass {
            id: [7; pass::ID_LEN],
            expires: 2_000,
            per_hour: 10,
        };
        let text = sign_pass(&p, &private).unwrap();
        assert!(text.starts_with(PASS_PREFIX));
        let (got, bytes) = open_pass(&format!(" {text}\n"), &roots, 1_999).unwrap();
        assert_eq!((got, bytes.len()), (p, pass::PASS_LEN));
        assert_eq!(open_pass(&text, &roots, 2_000), Err(NodeError::Expired));
        assert_eq!(open_pass(&text, &[], 0), Err(NodeError::BadSignature));
        // Подпись пропуска — не подпись записи узла: контексты разные.
        assert_eq!(
            open(&text.replace(PASS_PREFIX, TEXT_PREFIX), &roots, 0),
            Err(NodeError::BadFormat)
        );
        assert_eq!(
            open_pass(&text[..text.len() - 2], &roots, 0),
            Err(NodeError::BadFormat)
        );
    }
}
