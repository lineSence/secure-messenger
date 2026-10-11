//! Тесты контакта и хранилищ поверх [`MemKv`].

use super::*;
use crate::contact::{
    Hello, accept_session, card_identity, contact_id, decrypt, decrypt_from_any, encrypt, forget,
    new_card, safety_number, start_session,
};
use crate::store::{Kv, KvError, MemKv, Stores, Table};

struct Phone {
    id: DeviceIdentity,
    kv: MemKv,
}

impl Phone {
    fn new() -> Self {
        Self {
            id: DeviceIdentity::generate(),
            kv: MemKv::default(),
        }
    }

    fn s(&self) -> Stores<'_, MemKv> {
        Stores::new(&self.id, &self.kv)
    }
}

#[test]
fn round_trip_works() {
    round_trip().unwrap();
    assert!(self_check());
}

#[test]
fn one_time_keys_are_consumed_and_replay_rejected() {
    let (a, b) = (Phone::new(), Phone::new());
    let card = new_card(&b.s()).unwrap();
    assert_eq!(b.kv.count(Table::PreKey), 1);
    assert_eq!(b.kv.count(Table::KyberPreKey), 1);
    let st = start_session(&a.s(), &card, b"hi").unwrap();
    accept_session(&b.s(), &st.ciphertext, Some(&a.id.public_key())).unwrap();
    assert_eq!(b.kv.count(Table::PreKey), 0);
    assert_eq!(b.kv.count(Table::KyberPreKey), 0);
    assert_eq!(b.kv.count(Table::Session), 1);
    assert_eq!(b.kv.count(Table::Identity), 1);
    // Повтор сообщения начала сессии — отказ.
    assert!(accept_session(&b.s(), &st.ciphertext, None).is_err());
}

#[test]
fn qr_identity_mismatch_stores_nothing() {
    let (a, b, other) = (Phone::new(), Phone::new(), Phone::new());
    let card = new_card(&b.s()).unwrap();
    let st = start_session(&a.s(), &card, b"hi").unwrap();
    let r = accept_session(&b.s(), &st.ciphertext, Some(&other.id.public_key()));
    assert!(matches!(r, Err(SignalError::Mismatch)));
    assert_eq!(b.kv.count(Table::Session), 0);
    assert_eq!(b.kv.count(Table::PreKey), 1);
}

#[test]
fn tampered_message_is_rejected() {
    let (a, b) = (Phone::new(), Phone::new());
    let card = new_card(&b.s()).unwrap();
    let mut st = start_session(&a.s(), &card, b"secret").unwrap();
    let last = st.ciphertext.len() - 1;
    st.ciphertext[last] ^= 1;
    assert!(accept_session(&b.s(), &st.ciphertext, None).is_err());
    assert_eq!(b.kv.count(Table::Session), 0);
}

#[test]
fn bad_cards_are_rejected() {
    let (a, b) = (Phone::new(), Phone::new());
    let card = new_card(&b.s()).unwrap();
    assert_eq!(card_identity(&card).unwrap(), b.id.public_key());
    let mut wrong_version = card.clone();
    wrong_version[0] = 2;
    let mut trailing = card.clone();
    trailing.push(0);
    for bad in [
        &[][..],
        &card[..10],
        &card[..card.len() - 1],
        &wrong_version,
        &trailing,
    ] {
        assert!(start_session(&a.s(), bad, b"x").is_err());
    }
    // Подпись Kyber-ключа (последнее поле) подменена — libsignal отказывает.
    let mut forged = card.clone();
    let last = forged.len() - 1;
    forged[last] ^= 1;
    assert!(start_session(&a.s(), &forged, b"x").is_err());
    // Своя карточка — не контакт.
    let own = new_card(&a.s()).unwrap();
    assert!(start_session(&a.s(), &own, b"x").is_err());
    assert_eq!(a.kv.count(Table::Session), 0);
}

#[test]
fn prekey_message_must_match_contact() {
    let (a, b, c) = (Phone::new(), Phone::new(), Phone::new());
    let card = new_card(&b.s()).unwrap();
    let st = start_session(&a.s(), &card, b"hi").unwrap();
    let wrong = contact::contact_id(&c.id.public_key());
    assert!(matches!(
        decrypt(&b.s(), &wrong, &st.ciphertext),
        Err(SignalError::Mismatch)
    ));
    assert!(decrypt(&b.s(), "zz", &st.ciphertext).is_err());
    assert!(encrypt(&a.s(), &wrong, b"x").is_err());
    assert!(decrypt(&b.s(), &st.contact, &[9, 1, 2]).is_err());
}

/// `a` и `b` — контакты: `a` начал сессию по карточке `b`, `b` ответил. Адрес `b` у `a`.
fn paired(a: &Phone, b: &Phone) -> String {
    let card = new_card(&b.s()).unwrap();
    let st = start_session(&a.s(), &card, b"hi").unwrap();
    let acc = accept_session(&b.s(), &st.ciphertext, None).unwrap();
    let reply = encrypt(&b.s(), &acc.contact, b"hello").unwrap();
    assert_eq!(decrypt(&a.s(), &st.contact, &reply).unwrap(), b"hello");
    st.contact
}

#[test]
fn decrypt_from_any_finds_sender_without_breaking_others() {
    let (a, b, c, d) = (Phone::new(), Phone::new(), Phone::new(), Phone::new());
    let b_id = paired(&a, &b);
    let c_id = paired(&a, &c);
    let a_id = contact_id(&a.id.public_key());
    let both = [c_id.clone(), b_id.clone()];

    let from_b = encrypt(&b.s(), &a_id, b"from b").unwrap();
    let got = decrypt_from_any(&a.s(), &both, &from_b).unwrap();
    assert_eq!(got, Some((b_id.clone(), b"from b".to_vec())));
    // Пробовали сессию `c` первой — она цела.
    let from_c = encrypt(&c.s(), &a_id, b"from c").unwrap();
    let got = decrypt_from_any(&a.s(), &both, &from_c).unwrap();
    assert_eq!(got, Some((c_id.clone(), b"from c".to_vec())));
    // Повтор того же кадра, не тот кандидат, мусор — «никто».
    assert_eq!(decrypt_from_any(&a.s(), &both, &from_b).unwrap(), None);
    let again = encrypt(&b.s(), &a_id, b"x").unwrap();
    assert_eq!(
        decrypt_from_any(&a.s(), std::slice::from_ref(&c_id), &again).unwrap(),
        None
    );
    assert_eq!(
        decrypt_from_any(&a.s(), &both, &again).unwrap().unwrap().0,
        b_id
    );
    assert!(decrypt_from_any(&a.s(), &both, b"").is_err());
    assert!(decrypt_from_any(&a.s(), &["zz".into()], &again).is_err());

    // Начало сессии от не-контакта: сессия не создаётся, одноразовые ключи не тратятся.
    let card = new_card(&a.s()).unwrap();
    let st = start_session(&d.s(), &card, b"hi").unwrap();
    let sessions = a.kv.count(Table::Session);
    assert_eq!(
        decrypt_from_any(&a.s(), &both, &st.ciphertext).unwrap(),
        None
    );
    assert_eq!(a.kv.count(Table::Session), sessions);
    assert_eq!(a.kv.count(Table::KyberPreKey), 1);
}

#[test]
fn forget_drops_session() {
    let (a, b) = (Phone::new(), Phone::new());
    let card = new_card(&b.s()).unwrap();
    let st = start_session(&a.s(), &card, b"hi").unwrap();
    forget(&a.s(), &st.contact).unwrap();
    assert_eq!(a.kv.count(Table::Session), 0);
    assert_eq!(a.kv.count(Table::Identity), 0);
    assert!(encrypt(&a.s(), &st.contact, b"x").is_err());
}

#[test]
fn safety_number_matches_on_both_sides() {
    let (a, b) = (Phone::new(), Phone::new());
    let ab = safety_number(&a.id.public_key(), &b.id.public_key()).unwrap();
    let ba = safety_number(&b.id.public_key(), &a.id.public_key()).unwrap();
    assert_eq!(ab, ba);
    assert_eq!(ab.len(), 60);
    assert!(ab.bytes().all(|c| c.is_ascii_digit()));
    assert!(safety_number(&[1, 2], &b.id.public_key()).is_err());
}

#[test]
fn hello_round_trip_and_limits() {
    let h = Hello {
        name: "Аня".to_owned(),
        beacon_key: [7; 32],
    };
    let b = h.encode().unwrap();
    assert_eq!(Hello::decode(&b).unwrap(), h);
    assert!(!format!("{h:?}").contains('7'));
    let long = Hello {
        name: "я".repeat(65),
        beacon_key: [0; 32],
    };
    assert!(long.encode().is_err());
    let empty = Hello {
        name: String::new(),
        beacon_key: [0; 32],
    };
    assert!(empty.encode().is_err());
    assert!(Hello::decode(&b[..20]).is_err());
    assert!(Hello::decode(&b[..33]).is_err());
    let mut bad_utf8 = b.clone();
    bad_utf8.push(0xFF);
    assert!(Hello::decode(&bad_utf8).is_err());
    let mut v2 = b.clone();
    v2[0] = 2;
    assert!(Hello::decode(&v2).is_err());
    // Невидимые, bidi и управляющие символы, переводы строки и пустое имя — отказ (п.11 ревью).
    for bad in [
        "А\u{202E}ня",
        "А\u{200B}ня",
        "Аня\n",
        "\tАня",
        "А\u{1B}ня",
        "А\u{7F}",
        "   ",
        "\u{3164}",
    ] {
        let h = Hello {
            name: bad.to_owned(),
            beacon_key: [0; 32],
        };
        assert!(h.encode().is_err(), "{bad:?}");
        let mut raw = b[..33].to_vec();
        raw.extend_from_slice(bad.as_bytes());
        assert!(Hello::decode(&raw).is_err(), "{bad:?}");
    }
}

/// Хранилище, которое всегда отказывает: ошибка, а не паника и не обход.
struct BrokenKv;

impl Kv for BrokenKv {
    fn get(&self, _: Table, _: &[u8]) -> Result<Option<Vec<u8>>, KvError> {
        Err(KvError)
    }
    fn put(&self, _: Table, _: &[u8], _: &[u8]) -> Result<(), KvError> {
        Err(KvError)
    }
    fn delete(&self, _: Table, _: &[u8]) -> Result<(), KvError> {
        Err(KvError)
    }
}

#[test]
fn broken_store_fails_closed() {
    let (a, b) = (Phone::new(), Phone::new());
    let broken = Stores::new(&a.id, &BrokenKv);
    assert!(new_card(&broken).is_err());
    let card = new_card(&b.s()).unwrap();
    assert!(start_session(&broken, &card, b"x").is_err());
    let st = start_session(&a.s(), &card, b"x").unwrap();
    let b_broken = Stores::new(&b.id, &BrokenKv);
    assert!(accept_session(&b_broken, &st.ciphertext, None).is_err());
}

#[test]
fn qr_pairing_end_to_end() {
    use crate::beacon::{new_beacon_key, new_pairing_secret, pairing_proof, pairing_proof_ok};
    use crate::pairing::{PairFrame, QrInvite};
    let (a, b) = (Phone::new(), Phone::new());
    let (a_beacon, b_beacon) = (new_beacon_key(), new_beacon_key());

    // А показывает QR, Б сканирует.
    let qr = QrInvite {
        identity_key: a.id.public_key().try_into().unwrap(),
        secret: new_pairing_secret(),
    }
    .encode();
    let invite = QrInvite::decode(&qr).unwrap();

    // Б → А: карточка и доказательство.
    let card = new_card(&b.s()).unwrap();
    let proof = pairing_proof(&invite.secret, &card).unwrap();
    let frame = PairFrame::Card {
        card,
        proof: Some(proof.try_into().unwrap()),
    }
    .encode()
    .unwrap();

    // А: проверяет доказательство своим секретом, начинает сессию.
    let PairFrame::Card {
        card,
        proof: Some(proof),
    } = PairFrame::decode(&frame).unwrap()
    else {
        panic!("не карточка");
    };
    assert!(pairing_proof_ok(&invite.secret, &card, &proof).unwrap());
    assert!(!pairing_proof_ok(&new_pairing_secret(), &card, &proof).unwrap());
    let hello_a = Hello {
        name: "Аня".to_owned(),
        beacon_key: a_beacon,
    };
    let st = start_session(&a.s(), &card, &hello_a.encode().unwrap()).unwrap();
    assert_eq!(st.identity_key, b.id.public_key());
    let frame = PairFrame::Start(st.ciphertext).encode().unwrap();

    // Б: ключ отправителя — тот, что в QR.
    let PairFrame::Start(c) = PairFrame::decode(&frame).unwrap() else {
        panic!("не начало");
    };
    let acc = accept_session(&b.s(), &c, Some(&invite.identity_key)).unwrap();
    assert_eq!(Hello::decode(&acc.plaintext).unwrap(), hello_a);
    let hello_b = Hello {
        name: "Боря".to_owned(),
        beacon_key: b_beacon,
    };
    let reply = encrypt(&b.s(), &acc.contact, &hello_b.encode().unwrap()).unwrap();
    let frame = PairFrame::Reply(reply).encode().unwrap();

    // А: ответ по сессии.
    let PairFrame::Reply(c) = PairFrame::decode(&frame).unwrap() else {
        panic!("не ответ");
    };
    let got = Hello::decode(&decrypt(&a.s(), &st.contact, &c).unwrap()).unwrap();
    assert_eq!(got, hello_b);
}
