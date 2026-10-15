//! Пропуск на `NEW` (ADR-136), гостевой пропуск и билет (ADR-146), ID отправителя очереди (ADR-134).
//!
//! Подпись пропуска владельца — `XEdDSA` корневым ключом; гостевой пропуск подписывает сам узел
//! своим постоянным ключом ([`GuestKey`], `SM_RELAY_GUEST_KEY_FILE`). Проверка и подпись —
//! `libsignal-core` (та же библиотека, что подписывает в `sm-signal`, своей криптографии нет,
//! ADR-1). Узел списка пропусков не хранит: пропуск проверяется по подписи и переживает
//! перезапуск узла; в памяти — только билеты (24 часа, [`crate::store::Store`]).

use libsignal_core::curve::{PrivateKey, PublicKey};
use rand::TryRngCore as _;
use rand::rngs::OsRng;
use signal_crypto::CryptographicHash;
use sm_core::node::ROOT_PUBLIC_LEN;
use sm_core::pass::{
    GUEST_PASS_LEN, GuestPass, ID_LEN, PASS_LEN, QueuePass, SIGNATURE_LEN, guest_signed_message,
};

use crate::store::{PassId, QueueId};

/// Контекст ID отправителя: SHA-256(`SENDER_CONTEXT` ‖ ID получателя).
pub const SENDER_CONTEXT: &[u8] = b"sm-qsend/v1";
/// Сколько новых очередей в час даёт гостевой пропуск, не больше, чем у родителя.
pub const GUEST_PER_HOUR: u16 = 10;
/// Самый долгий гостевой пропуск, секунд (год); не дольше пропуска владельца, от которого он пошёл.
pub const GUEST_TTL_SECS: u64 = 365 * 24 * 3600;

/// Постоянный ключ узла для гостевых пропусков (ADR-146): 32 байта из файла. Без него узел
/// билетов не выдаёт. `Debug` ключ не показывает.
pub struct GuestKey(PrivateKey, PublicKey);

impl core::fmt::Debug for GuestKey {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str("GuestKey(..)")
    }
}

impl GuestKey {
    /// Ключ из 64 HEX-знаков (пробелы и перевод строки по краям отбрасываются); `None` — не HEX
    /// или не 32 байта.
    #[must_use]
    pub fn from_hex(text: &str) -> Option<Self> {
        let text = text.trim();
        if text.len() != 64 || !text.is_ascii() {
            return None;
        }
        let bytes = text
            .as_bytes()
            .chunks(2)
            .map(|p| u8::from_str_radix(std::str::from_utf8(p).ok()?, 16).ok())
            .collect::<Option<Vec<u8>>>()?;
        let private = PrivateKey::deserialize(&bytes).ok()?;
        let public = private.public_key().ok()?;
        Some(Self(private, public))
    }

    /// Случайный ключ (для тестов и для первой установки).
    #[must_use]
    pub fn generate() -> Option<Self> {
        let mut b = [0u8; 32];
        OsRng.try_fill_bytes(&mut b).ok()?;
        let private = PrivateKey::deserialize(&b).ok()?;
        let public = private.public_key().ok()?;
        Some(Self(private, public))
    }
}

/// Кто предъявил пропуск и что ему разрешено.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Caller {
    /// ID предъявленного пропуска: по нему считается лимит.
    pub id: PassId,
    /// Новых очередей в час.
    pub per_hour: u16,
    /// ID пропуска владельца, от которого пошла цепочка (у самого пропуска владельца — его ID).
    pub parent: PassId,
    /// Срок предъявленного пропуска, секунды Unix.
    pub expires: u64,
}

/// Принимает пропуск владельца: подпись одним из `roots`, срок не истёк к `now_secs`, не в `revoked`.
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

/// Принимает гостевой пропуск: подпись ключом узла `key`, срок не истёк, ни он сам, ни родитель
/// не в `revoked`.
#[must_use]
pub fn check_guest(
    pass: &[u8],
    key: &GuestKey,
    revoked: &[PassId],
    now_secs: u64,
) -> Option<GuestPass> {
    let (g, message, signature) = GuestPass::parse(pass).ok()?;
    if now_secs >= g.expires || revoked.contains(&g.id) || revoked.contains(&g.parent) {
        return None;
    }
    key.1.verify_signature(&message, &signature).then_some(g)
}

/// Принимает пропуск любого вида (по длине): владельца или гостевой (если у узла есть `key`).
#[must_use]
pub fn check_any(
    pass: &[u8],
    roots: &[[u8; ROOT_PUBLIC_LEN]],
    key: Option<&GuestKey>,
    revoked: &[PassId],
    now_secs: u64,
) -> Option<Caller> {
    match pass.len() {
        PASS_LEN => check(pass, roots, revoked, now_secs).map(|p| Caller {
            id: p.id,
            per_hour: p.per_hour,
            parent: p.id,
            expires: p.expires,
        }),
        GUEST_PASS_LEN => check_guest(pass, key?, revoked, now_secs).map(|g| Caller {
            id: g.id,
            per_hour: g.per_hour,
            parent: g.parent,
            expires: g.expires,
        }),
        _ => None,
    }
}

/// Случайный билет: 32 байта `OsRng`; `None` — нет случайных байт.
#[must_use]
pub fn new_ticket() -> Option<QueueId> {
    let mut t = [0u8; sm_core::pass::TICKET_LEN];
    OsRng.try_fill_bytes(&mut t).ok()?;
    Some(t)
}

/// Выдаёт гостевой пропуск по билету с правами `grant` (родитель, срок, лимит родителя) ключом
/// узла: срок — не дольше [`GUEST_TTL_SECS`] и не дольше родителя, лимит — не больше
/// [`GUEST_PER_HOUR`] и не больше родительского. `None` — нет случайных байт или подпись не вышла.
#[must_use]
pub fn issue_guest(key: &GuestKey, grant: &Caller, now_secs: u64) -> Option<Vec<u8>> {
    let mut id = [0u8; ID_LEN];
    OsRng.try_fill_bytes(&mut id).ok()?;
    let g = GuestPass {
        id,
        expires: grant.expires.min(now_secs.saturating_add(GUEST_TTL_SECS)),
        per_hour: grant.per_hour.min(GUEST_PER_HOUR),
        parent: grant.parent,
    };
    let sig = key
        .0
        .calculate_signature(&guest_signed_message(&g.body()), &mut OsRng.unwrap_err())
        .ok()?;
    let sig: [u8; SIGNATURE_LEN] = sig[..].try_into().ok()?;
    Some(g.with_signature(&sig))
}

/// ID отправителя очереди с ID получателя `recipient` (ADR-134): узнать ID получателя по нему
/// нельзя — нужен прообраз SHA-256.
#[must_use]
pub fn sender_id(recipient: &QueueId) -> QueueId {
    let Ok(mut h) = CryptographicHash::new("SHA-256") else {
        // Имя алгоритма постоянное: ошибки не бывает; на всякий случай — ID, который не совпадёт
        // ни с одной очередью клиента, а не паника.
        return [0; 32];
    };
    h.update(SENDER_CONTEXT);
    h.update(recipient);
    h.finalize().try_into().unwrap_or([0; 32])
}

#[cfg(test)]
mod tests {
    use super::*;
    use libsignal_core::curve::KeyPair;
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

    fn key(b: &str) -> GuestKey {
        GuestKey::from_hex(&b.repeat(32)).unwrap()
    }

    fn owner(per_hour: u16, expires: u64) -> Caller {
        Caller {
            id: [1; 16],
            per_hour,
            parent: [1; 16],
            expires,
        }
    }

    #[test]
    fn guest_pass_is_capped_and_needs_node_key_time_and_live_parent() {
        let k = key("ab");
        let day = 24 * 3600;
        // Срок — не дольше родителя и не дольше года; лимит — не выше родительского и предела.
        let g = issue_guest(&k, &owner(100, 10 * day), 1_000).unwrap();
        let (p, _, _) = GuestPass::parse(&g).unwrap();
        assert_eq!(
            (p.expires, p.per_hour, p.parent),
            (10 * day, GUEST_PER_HOUR, [1; 16])
        );
        let g2 = issue_guest(&k, &owner(3, u64::MAX), 1_000).unwrap();
        let (p2, _, _) = GuestPass::parse(&g2).unwrap();
        assert_eq!((p2.expires, p2.per_hour), (1_000 + GUEST_TTL_SECS, 3));

        assert_eq!(check_guest(&g, &k, &[], 999), Some(p));
        assert_eq!(check_guest(&g, &k, &[], 10 * day), None);
        assert_eq!(check_guest(&g, &key("cd"), &[], 999), None);
        assert_eq!(check_guest(&g, &k, &[p.id], 999), None);
        assert_eq!(check_guest(&g, &k, &[[1; 16]], 999), None);
        let mut bad = g.clone();
        bad[20] ^= 1;
        assert_eq!(check_guest(&bad, &k, &[], 999), None);
        // check_any выбирает вид по длине; без ключа узла гостевой не принимается.
        assert_eq!(
            check_any(&g, &[], Some(&k), &[], 999).map(|c| c.parent),
            Some([1; 16])
        );
        assert_eq!(check_any(&g, &[], None, &[], 999), None);
        assert_eq!(check_any(&g[..g.len() - 1], &[], Some(&k), &[], 999), None);
        // Пропуск владельца гостевым ключом не подписать: подпись другим ключом не пройдёт.
        assert!(
            GuestKey::from_hex("zz").is_none() && GuestKey::from_hex(&"g".repeat(64)).is_none()
        );
    }

    #[test]
    fn tickets_are_random_and_keys_do_not_leak_in_debug() {
        assert_ne!(new_ticket(), new_ticket());
        assert_eq!(format!("{:?}", key("ab")), "GuestKey(..)");
    }

    #[test]
    fn sender_id_is_fixed_hash() {
        let a = sender_id(&[0; 32]);
        // SHA-256(b"sm-qsend/v1" ‖ 32 нуля), посчитано независимо (Python hashlib).
        assert_eq!(
            a,
            [
                0x67, 0x98, 0x4b, 0x10, 0xb8, 0x64, 0xc5, 0xff, 0xd3, 0xd9, 0x95, 0x21, 0xa2, 0x42,
                0xd6, 0x23, 0x6c, 0x0e, 0xde, 0x05, 0x2b, 0x3c, 0x34, 0xbd, 0x72, 0x97, 0x22, 0x5d,
                0xc0, 0xed, 0x95, 0x73
            ]
        );
        assert_eq!(a, sender_id(&[0; 32]));
        assert_ne!(a, sender_id(&[1; 32]));
        assert_ne!(a, [0; 32]);
    }
}
