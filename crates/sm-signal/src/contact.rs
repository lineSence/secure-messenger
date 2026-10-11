//! Контакт: набор ключей, начало сессии, сообщения (ADR-62 п.2–3, пункты 5–6 плана).
//!
//! Добавление контакта при встрече (оба телефона рядом):
//!
//! 1. Б (сканировал QR или выбрал телефон в «Добавить рядом») отдаёт А свою **карточку** —
//!    набор ключей Signal ([`new_card`]): ключ личности, подписанный ключ, ключ Kyber и
//!    одноразовый ключ; закрытые половины — в хранилище Б. В сценарии QR к карточке Б
//!    прикладывает доказательство знания секрета из QR (`beacon::pairing_proof`).
//! 2. А проверяет доказательство (или спрашивает пользователя), начинает сессию по карточке
//!    (PQXDH) и шифрует первое сообщение [`Hello`] — имя и ключ маяка ([`start_session`]).
//! 3. Б принимает его ([`accept_session`]); в сценарии QR — только если ключ личности
//!    отправителя совпал с ключом из QR. Б отвечает своим [`Hello`] уже по сессии ([`encrypt`]).
//!
//! Ключ маяка и имя идут только внутри Signal (ADR-61 п.2): по Bluetooth открыто — лишь
//! открытые ключи карточки. Адрес контакта в хранилищах — его ключ личности в hex.

use std::time::SystemTime;

use libsignal_protocol::{
    CiphertextMessage, CiphertextMessageType, DeviceId, Fingerprint, IdentityKey, KeyPair,
    KyberPreKeyId, KyberPreKeyRecord, PreKeyBundle, PreKeyId, PreKeyRecord, PreKeySignalMessage,
    ProtocolAddress, PublicKey, SignalMessage, SignedPreKeyId, SignedPreKeyRecord, Timestamp, kem,
    message_decrypt, message_encrypt, process_prekey_bundle,
};
use libsignal_protocol::{
    GenericSignedPreKey as _, KyberPreKeyStore as _, PreKeyStore as _, SignedPreKeyStore as _,
};
use rand::rngs::OsRng;
use rand::{RngCore as _, TryRngCore as _};

use crate::beacon::BEACON_KEY_LEN;
use crate::store::{Kv, Stores, Table, address_id};
use crate::{SignalError, now};

/// Версия карточки.
pub const CARD_VERSION: u8 = 1;
/// Версия [`Hello`].
pub const HELLO_VERSION: u8 = 1;
/// Наибольшая длина имени в [`Hello`], байт UTF-8 (в приложении — 32 знака).
pub const MAX_NAME_BYTES: usize = 128;
/// Длина открытого ключа личности (тип + X25519).
pub const IDENTITY_KEY_LEN: usize = 33;

/// У каждого контакта одно устройство (`0.0.1-alpha`).
const DEVICE: u8 = 1;
/// Номера ключей — 24 бита, как у Signal.
const KEY_ID_MASK: u32 = 0x00FF_FFFF;
/// Версия и число итераций кода безопасности — как у Signal.
const FINGERPRINT_VERSION: u32 = 2;
const FINGERPRINT_ITERATIONS: u32 = 5200;

/// Тип зашифрованного сообщения в первом байте.
const WIRE_PREKEY: u8 = CiphertextMessageType::PreKey as u8;
const WIRE_WHISPER: u8 = CiphertextMessageType::Whisper as u8;

fn device() -> Result<DeviceId, SignalError> {
    DeviceId::new(DEVICE).map_err(|_| SignalError::BadInput)
}

/// Адрес контакта в хранилищах: ключ личности в hex.
#[must_use]
pub fn contact_id(identity_key: &[u8]) -> String {
    use core::fmt::Write as _;
    identity_key.iter().fold(String::new(), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    })
}

fn address(contact: &str) -> Result<ProtocolAddress, SignalError> {
    let ok = contact.len() == IDENTITY_KEY_LEN * 2
        && contact
            .bytes()
            .all(|c| matches!(c, b'0'..=b'9' | b'a'..=b'f'));
    if !ok {
        return Err(SignalError::BadInput);
    }
    Ok(ProtocolAddress::new(contact.to_owned(), device()?))
}

/// Свободный номер ключа в таблице (24 бита, не ноль).
fn free_id<K: Kv>(kv: &K, table: Table) -> Result<u32, SignalError> {
    let mut rng = OsRng.unwrap_err();
    for _ in 0..16 {
        let id = rng.next_u32() & KEY_ID_MASK;
        if id != 0
            && kv
                .get(table, &id.to_be_bytes())
                .map_err(|_| SignalError::Store)?
                .is_none()
        {
            return Ok(id);
        }
    }
    Err(SignalError::Store)
}

fn put_bytes(out: &mut Vec<u8>, b: &[u8]) -> Result<(), SignalError> {
    let len = u16::try_from(b.len()).map_err(|_| SignalError::BadInput)?;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(b);
    Ok(())
}

/// Строгий разбор: каждое поле на месте, лишних байт нет.
struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], SignalError> {
        if self.0.len() < n {
            return Err(SignalError::BadInput);
        }
        let (head, rest) = self.0.split_at(n);
        self.0 = rest;
        Ok(head)
    }

    fn array<const N: usize>(&mut self) -> Result<[u8; N], SignalError> {
        <[u8; N]>::try_from(self.take(N)?).map_err(|_| SignalError::BadInput)
    }

    fn u8(&mut self) -> Result<u8, SignalError> {
        Ok(u8::from_be_bytes(self.array()?))
    }

    fn u32(&mut self) -> Result<u32, SignalError> {
        Ok(u32::from_be_bytes(self.array()?))
    }

    fn bytes(&mut self) -> Result<&'a [u8], SignalError> {
        let n = u16::from_be_bytes(self.array()?);
        self.take(usize::from(n))
    }

    fn end(&self) -> Result<(), SignalError> {
        if self.0.is_empty() {
            Ok(())
        } else {
            Err(SignalError::BadInput)
        }
    }
}

/// Новый набор ключей для одного добавления контакта; закрытые половины — в хранилище.
///
/// Формат: `[версия][номер регистрации u32][ключ личности][id u32][одноразовый]
/// [id u32][подписанный][подпись][id u32][Kyber][подпись]`; ключи и подписи — с длиной u16 BE.
///
/// # Errors
/// [`SignalError::Store`] — хранилище отказало; [`SignalError::Signing`].
pub fn new_card<K: Kv>(s: &Stores<'_, K>) -> Result<Vec<u8>, SignalError> {
    let mut rng = OsRng.unwrap_err();
    let me = s.me();
    let pre = KeyPair::generate(&mut rng);
    let signed = KeyPair::generate(&mut rng);
    let kyber = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
    let private = me.pair().private_key();
    let signed_sig = private
        .calculate_signature(&signed.public_key.serialize(), &mut rng)
        .map_err(|_| SignalError::Signing)?;
    let kyber_sig = private
        .calculate_signature(&kyber.public_key.serialize(), &mut rng)
        .map_err(|_| SignalError::Signing)?;
    let ts = Timestamp::from_epoch_millis(
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map_or(0, |d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX)),
    );

    let pre_id = free_id(s.kv(), Table::PreKey)?;
    let signed_id = free_id(s.kv(), Table::SignedPreKey)?;
    let kyber_id = free_id(s.kv(), Table::KyberPreKey)?;
    now(s
        .pre_keys()
        .save_pre_key(pre_id.into(), &PreKeyRecord::new(pre_id.into(), &pre)))?;
    now(s.signed_pre_keys().save_signed_pre_key(
        signed_id.into(),
        &SignedPreKeyRecord::new(signed_id.into(), ts, &signed, &signed_sig),
    ))?;
    now(s.kyber_pre_keys().save_kyber_pre_key(
        kyber_id.into(),
        &KyberPreKeyRecord::new(kyber_id.into(), ts, &kyber, &kyber_sig),
    ))?;

    let mut out = Vec::with_capacity(2048);
    out.push(CARD_VERSION);
    out.extend_from_slice(&me.registration_id().to_be_bytes());
    put_bytes(&mut out, &me.public_key())?;
    out.extend_from_slice(&pre_id.to_be_bytes());
    put_bytes(&mut out, &pre.public_key.serialize())?;
    out.extend_from_slice(&signed_id.to_be_bytes());
    put_bytes(&mut out, &signed.public_key.serialize())?;
    put_bytes(&mut out, &signed_sig)?;
    out.extend_from_slice(&kyber_id.to_be_bytes());
    put_bytes(&mut out, &kyber.public_key.serialize())?;
    put_bytes(&mut out, &kyber_sig)?;
    Ok(out)
}

/// Разбирает карточку в набор ключей libsignal (подписи проверит `process_prekey_bundle`).
fn parse_card(card: &[u8]) -> Result<PreKeyBundle, SignalError> {
    let mut r = Reader(card);
    if r.u8()? != CARD_VERSION {
        return Err(SignalError::BadInput);
    }
    let reg = r.u32()?;
    let identity = IdentityKey::decode(r.bytes()?).map_err(|_| SignalError::BadInput)?;
    let pre_id = PreKeyId::from(r.u32()?);
    let pre = PublicKey::deserialize(r.bytes()?).map_err(|_| SignalError::BadInput)?;
    let signed_id = SignedPreKeyId::from(r.u32()?);
    let signed = PublicKey::deserialize(r.bytes()?).map_err(|_| SignalError::BadInput)?;
    let signed_sig = r.bytes()?.to_vec();
    let kyber_id = KyberPreKeyId::from(r.u32()?);
    let kyber = kem::PublicKey::deserialize(r.bytes()?).map_err(|_| SignalError::BadInput)?;
    let kyber_sig = r.bytes()?.to_vec();
    r.end()?;
    Ok(PreKeyBundle::new(
        reg,
        device()?,
        Some((pre_id, pre)),
        signed_id,
        signed,
        signed_sig,
        kyber_id,
        kyber,
        kyber_sig,
        identity,
    )?)
}

/// Ключ личности из карточки (33 байта) — для сверки с QR до начала сессии.
///
/// # Errors
/// [`SignalError::BadInput`] — карточка не разобралась.
pub fn card_identity(card: &[u8]) -> Result<Vec<u8>, SignalError> {
    Ok(parse_card(card)?.identity_key()?.serialize().into_vec())
}

/// Адрес контакта по ключу личности (33 байта) — например, из отсканированного QR.
///
/// # Errors
/// [`SignalError::BadInput`] — не ключ личности.
pub fn identity_contact(identity_key: &[u8]) -> Result<String, SignalError> {
    let k = IdentityKey::decode(identity_key).map_err(|_| SignalError::BadInput)?;
    Ok(contact_id(&k.serialize()))
}

/// Адрес отправителя сообщения начала сессии — до расшифровки, чтобы не трогать сессию
/// уже добавленного контакта.
///
/// # Errors
/// [`SignalError::BadInput`] — не сообщение начала сессии.
pub fn start_contact(ciphertext: &[u8]) -> Result<String, SignalError> {
    let CiphertextMessage::PreKeySignalMessage(pre) = unwire(ciphertext)? else {
        return Err(SignalError::BadInput);
    };
    Ok(contact_id(&pre.identity_key().serialize()))
}

/// Первое сообщение контакту: имя и ключ маяка (идёт только внутри Signal).
#[derive(Clone, PartialEq, Eq)]
pub struct Hello {
    /// Имя, которое увидит контакт.
    pub name: String,
    /// Ключ маяка отправителя (ADR-61 п.2).
    pub beacon_key: [u8; BEACON_KEY_LEN],
}

impl core::fmt::Debug for Hello {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("Hello")
            .field("name", &self.name)
            .finish_non_exhaustive()
    }
}

/// Имя контакта (своё или из сети, ревью v0.0.1-alpha, п.11): 1..=[`MAX_NAME_BYTES`] байт,
/// правила [`sm_core::wire::text`] (без NUL, управляющих, невидимых и bidi-символов), без
/// переводов строки и табуляции, не из одних пробелов.
///
/// # Errors
/// [`SignalError::BadInput`].
pub fn check_name(b: &[u8]) -> Result<&str, SignalError> {
    let s = sm_core::wire::text(b, MAX_NAME_BYTES).map_err(|_| SignalError::BadInput)?;
    if s.trim().is_empty() || s.chars().any(|c| matches!(c, '\n' | '\r' | '\t')) {
        return Err(SignalError::BadInput);
    }
    Ok(s)
}

impl Hello {
    /// `[версия][ключ маяка 32][имя UTF-8]`.
    ///
    /// # Errors
    /// [`SignalError::BadInput`] — имя не проходит [`check_name`].
    pub fn encode(&self) -> Result<Vec<u8>, SignalError> {
        check_name(self.name.as_bytes())?;
        let mut out = Vec::with_capacity(MAX_NAME_BYTES.saturating_add(BEACON_KEY_LEN + 1));
        out.push(HELLO_VERSION);
        out.extend_from_slice(&self.beacon_key);
        out.extend_from_slice(self.name.as_bytes());
        Ok(out)
    }

    /// Разбор [`Hello::encode`].
    ///
    /// # Errors
    /// [`SignalError::BadInput`].
    pub fn decode(b: &[u8]) -> Result<Self, SignalError> {
        let mut r = Reader(b);
        if r.u8()? != HELLO_VERSION {
            return Err(SignalError::BadInput);
        }
        let key = r.take(BEACON_KEY_LEN)?;
        let beacon_key =
            <[u8; BEACON_KEY_LEN]>::try_from(key).map_err(|_| SignalError::BadInput)?;
        let name = check_name(r.0)?;
        Ok(Self {
            name: name.to_owned(),
            beacon_key,
        })
    }
}

fn wire(msg: &CiphertextMessage) -> Vec<u8> {
    let body = msg.serialize();
    let mut out = Vec::with_capacity(body.len().saturating_add(1));
    out.push(msg.message_type() as u8);
    out.extend_from_slice(body);
    out
}

fn unwire(b: &[u8]) -> Result<CiphertextMessage, SignalError> {
    let (&kind, body) = b.split_first().ok_or(SignalError::BadInput)?;
    match kind {
        WIRE_PREKEY => Ok(CiphertextMessage::PreKeySignalMessage(
            PreKeySignalMessage::try_from(body)?,
        )),
        WIRE_WHISPER => Ok(CiphertextMessage::SignalMessage(SignalMessage::try_from(
            body,
        )?)),
        _ => Err(SignalError::BadInput),
    }
}

/// Сессия начата: контакт и первое зашифрованное сообщение для него.
#[derive(Debug)]
pub struct Started {
    /// Адрес контакта ([`contact_id`] его ключа личности).
    pub contact: String,
    /// Ключ личности контакта (33 байта).
    pub identity_key: Vec<u8>,
    /// Зашифрованное первое сообщение (сообщение начала сессии).
    pub ciphertext: Vec<u8>,
}

/// Начинает сессию по карточке контакта (PQXDH) и шифрует `first`.
///
/// # Errors
/// [`SignalError::BadInput`] — карточка не разобралась; [`SignalError::Protocol`] — подпись
/// ключа неверна или ключ личности не совпал с уже записанным для этого адреса.
pub fn start_session<K: Kv>(
    s: &Stores<'_, K>,
    card: &[u8],
    first: &[u8],
) -> Result<Started, SignalError> {
    let bundle = parse_card(card)?;
    let identity_key = bundle.identity_key()?.serialize().into_vec();
    if identity_key == s.me().public_key() {
        return Err(SignalError::BadInput);
    }
    let contact = contact_id(&identity_key);
    let to = address(&contact)?;
    let me = address(&contact_id(&s.me().public_key()))?;
    let mut rng = OsRng.unwrap_err();
    now(process_prekey_bundle(
        &to,
        &me,
        &mut s.sessions(),
        &mut s.identity(),
        &bundle,
        SystemTime::now(),
        &mut rng,
    ))?;
    let ciphertext = encrypt(s, &contact, first)?;
    Ok(Started {
        contact,
        identity_key,
        ciphertext,
    })
}

/// Первое сообщение принято.
#[derive(Debug)]
pub struct Accepted {
    /// Адрес контакта.
    pub contact: String,
    /// Ключ личности контакта (33 байта).
    pub identity_key: Vec<u8>,
    /// Расшифрованное сообщение.
    pub plaintext: Vec<u8>,
}

/// Принимает сообщение начала сессии от нового контакта.
///
/// `expected_identity` — ключ личности из QR: другой ключ — отказ ещё до расшифровки.
///
/// # Errors
/// [`SignalError::Mismatch`] — ключ не тот, что в QR; [`SignalError::BadInput`] — не сообщение
/// начала сессии; [`SignalError::Protocol`] — не расшифровалось (ключи не наши, повтор).
pub fn accept_session<K: Kv>(
    s: &Stores<'_, K>,
    ciphertext: &[u8],
    expected_identity: Option<&[u8]>,
) -> Result<Accepted, SignalError> {
    let msg = unwire(ciphertext)?;
    let CiphertextMessage::PreKeySignalMessage(pre) = &msg else {
        return Err(SignalError::BadInput);
    };
    let identity_key = pre.identity_key().serialize().into_vec();
    if expected_identity.is_some_and(|e| e != identity_key.as_slice()) {
        return Err(SignalError::Mismatch);
    }
    if identity_key == s.me().public_key() {
        return Err(SignalError::BadInput);
    }
    let contact = contact_id(&identity_key);
    let plaintext = decrypt_message(s, &contact, &msg)?;
    Ok(Accepted {
        contact,
        identity_key,
        plaintext,
    })
}

/// Шифрует сообщение контакту по уже начатой сессии.
///
/// # Errors
/// [`SignalError::Protocol`] — сессии нет; [`SignalError::BadInput`] — неверный адрес.
pub fn encrypt<K: Kv>(
    s: &Stores<'_, K>,
    contact: &str,
    plaintext: &[u8],
) -> Result<Vec<u8>, SignalError> {
    let to = address(contact)?;
    let me = address(&contact_id(&s.me().public_key()))?;
    let mut rng = OsRng.unwrap_err();
    let msg = now(message_encrypt(
        plaintext,
        &to,
        &me,
        &mut s.sessions(),
        &mut s.identity(),
        SystemTime::now(),
        &mut rng,
    ))?;
    Ok(wire(&msg))
}

fn decrypt_message<K: Kv>(
    s: &Stores<'_, K>,
    contact: &str,
    msg: &CiphertextMessage,
) -> Result<Vec<u8>, SignalError> {
    let from = address(contact)?;
    let me = address(&contact_id(&s.me().public_key()))?;
    let mut rng = OsRng.unwrap_err();
    now(message_decrypt(
        msg,
        &from,
        &me,
        &mut s.sessions(),
        &mut s.identity(),
        &mut s.pre_keys(),
        &s.signed_pre_keys(),
        &mut s.kyber_pre_keys(),
        &mut rng,
    ))
}

/// Расшифровывает сообщение известного контакта.
///
/// # Errors
/// [`SignalError::BadInput`] — не сообщение Signal; [`SignalError::Protocol`] — подделка,
/// повтор, чужой ключ личности.
pub fn decrypt<K: Kv>(
    s: &Stores<'_, K>,
    contact: &str,
    ciphertext: &[u8],
) -> Result<Vec<u8>, SignalError> {
    let msg = unwire(ciphertext)?;
    if let CiphertextMessage::PreKeySignalMessage(pre) = &msg
        && contact_id(&pre.identity_key().serialize()) != contact
    {
        return Err(SignalError::Mismatch);
    }
    decrypt_message(s, contact, &msg)
}

/// Новый идентификатор сообщения: 16 случайных байт (`OsRng`, ADR-14).
#[must_use]
pub fn new_message_id() -> [u8; 16] {
    let mut id = [0u8; 16];
    OsRng.unwrap_err().fill_bytes(&mut id);
    id
}

/// Расшифровывает сообщение одного из контактов `candidates`, если отправитель не подписан
/// (кадр Bluetooth: сервер GATT не знает, какой контакт записал кадр; ADR-61 п.4).
///
/// Сообщение начала сессии несёт ключ личности — пробуется только его контакт. Обычное
/// сообщение пробуется по очереди с сессией каждого кандидата: libsignal сначала проверяет
/// MAC и при неудаче ничего не сохраняет, так что чужая сессия не портится.
/// `Ok(None)` — ни один кандидат не подошёл (не контакт, подделка, повтор).
///
/// # Errors
/// [`SignalError::BadInput`] — кадр или адрес кандидата не разобрались;
/// [`SignalError::Store`] — хранилище отказало.
pub fn decrypt_from_any<K: Kv>(
    s: &Stores<'_, K>,
    candidates: &[String],
    ciphertext: &[u8],
) -> Result<Option<(String, Vec<u8>)>, SignalError> {
    let msg = unwire(ciphertext)?;
    let only = match &msg {
        CiphertextMessage::PreKeySignalMessage(pre) => {
            Some(contact_id(&pre.identity_key().serialize()))
        }
        _ => None,
    };
    for c in candidates {
        if only.as_ref().is_some_and(|o| o != c) {
            continue;
        }
        match decrypt_message(s, c, &msg) {
            Ok(plain) => return Ok(Some((c.clone(), plain))),
            Err(SignalError::Protocol(e)) if !store_failed(&e) => {}
            Err(SignalError::Mismatch) => {}
            Err(e) => return Err(e),
        }
    }
    Ok(None)
}

/// Ошибка хранилища внутри libsignal — отказ хранилища, а не «не тот контакт».
fn store_failed(e: &libsignal_protocol::SignalProtocolError) -> bool {
    matches!(
        e,
        libsignal_protocol::SignalProtocolError::ApplicationCallbackError(..)
    )
}

/// Удаляет сессию и ключ личности контакта (контакт удалён или добавление отменено).
///
/// # Errors
/// [`SignalError::Store`]; [`SignalError::BadInput`] — неверный адрес.
pub fn forget<K: Kv>(s: &Stores<'_, K>, contact: &str) -> Result<(), SignalError> {
    let id = address_id(&address(contact)?);
    s.kv()
        .delete(Table::Session, &id)
        .and_then(|()| s.kv().delete(Table::Identity, &id))
        .map_err(|_| SignalError::Store)
}

/// Код безопасности (60 цифр) пары ключей личности — одинаковый на обоих телефонах (ADR-62 п.3).
///
/// # Errors
/// [`SignalError::BadInput`] — ключ не разобрался.
pub fn safety_number(local: &[u8], remote: &[u8]) -> Result<String, SignalError> {
    let l = IdentityKey::decode(local).map_err(|_| SignalError::BadInput)?;
    let r = IdentityKey::decode(remote).map_err(|_| SignalError::BadInput)?;
    Fingerprint::new(
        FINGERPRINT_VERSION,
        FINGERPRINT_ITERATIONS,
        local,
        &l,
        remote,
        &r,
    )
    .and_then(|f| f.display_string())
    .map_err(|_| SignalError::BadInput)
}
