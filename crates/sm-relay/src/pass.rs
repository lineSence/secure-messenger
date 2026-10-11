//! Пропуск на `NEW` (ADR-136) и ID отправителя очереди (ADR-134).
//!
//! Подпись пропуска — `XEdDSA` корневым ключом владельца; проверка — `libsignal-core` (та же
//! библиотека, что подписывает в `sm-signal`, своей криптографии нет, ADR-1). Узел списка
//! пропусков не хранит: пропуск проверяется по подписи и переживает перезапуск узла.

use libsignal_core::curve::PublicKey;
use sha2::{Digest, Sha256};
use sm_core::node::ROOT_PUBLIC_LEN;
use sm_core::pass::QueuePass;

use crate::store::{PassId, QueueId};

/// Контекст ID отправителя: SHA-256(`SENDER_CONTEXT` ‖ ID получателя).
pub const SENDER_CONTEXT: &[u8] = b"sm-qsend/v1";

/// Принимает пропуск: подпись одним из `roots`, срок не истёк к `now_secs`, не в `revoked`.
#[must_use]
pub fn check(
    pass: &[u8],
    roots: &[[u8; ROOT_PUBLIC_LEN]],
    revoked: &[PassId],
    now_secs: u64,
) -> Option<QueuePass> {
    let (p, message, signature) = QueuePass::parse(pass).ok()?;
    if now_secs >= p.expires || revoked.contains(&p.id) {
        return None;
    }
    roots
        .iter()
        .filter_map(|r| PublicKey::deserialize(r).ok())
        .any(|k| k.verify_signature(&message, &signature))
        .then_some(p)
}

/// ID отправителя очереди с ID получателя `recipient` (ADR-134): узнать ID получателя по нему
/// нельзя — нужен прообраз SHA-256.
#[must_use]
pub fn sender_id(recipient: &QueueId) -> QueueId {
    let mut h = Sha256::new();
    h.update(SENDER_CONTEXT);
    h.update(recipient);
    h.finalize().into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use libsignal_core::curve::KeyPair;
    use rand::TryRngCore as _;
    use rand::rngs::OsRng;
    use sm_core::pass::signed_message;

    fn signed(kp: &KeyPair, p: &QueuePass) -> Vec<u8> {
        let sig = kp
            .private_key
            .calculate_signature(&signed_message(&p.body()), &mut OsRng.unwrap_err())
            .unwrap();
        p.with_signature(&sig[..].try_into().unwrap())
    }

    #[test]
    fn pass_needs_root_signature_time_and_not_revoked() {
        let mut rng = OsRng.unwrap_err();
        let root = KeyPair::generate(&mut rng);
        let other = KeyPair::generate(&mut rng);
        let roots: [[u8; ROOT_PUBLIC_LEN]; 1] =
            [root.public_key.serialize()[..].try_into().unwrap()];
        let p = QueuePass {
            id: [1; 16],
            expires: 1_000,
            per_hour: 5,
        };
        let good = signed(&root, &p);
        assert_eq!(check(&good, &roots, &[], 999), Some(p));
        assert_eq!(check(&good, &roots, &[], 1_000), None);
        assert_eq!(check(&good, &roots, &[[1; 16]], 999), None);
        assert_eq!(check(&good, &[], &[], 999), None);
        assert_eq!(check(&signed(&other, &p), &roots, &[], 999), None);
        // Подмена поля ломает подпись.
        let mut bad = good;
        bad[20] ^= 1;
        assert_eq!(check(&bad, &roots, &[], 999), None);
    }

    #[test]
    fn sender_id_is_fixed_hash() {
        let a = sender_id(&[0; 32]);
        assert_eq!(a, sender_id(&[0; 32]));
        assert_ne!(a, sender_id(&[1; 32]));
        assert_ne!(a, [0; 32]);
    }
}
