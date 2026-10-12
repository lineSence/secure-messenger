//! Сессии Signal и добавление контакта для Kotlin (ADR-62, ADR-63): обёртки над
//! `sm_signal::{contact, pairing, beacon}`.
//!
//! Kotlin даёт [`PlatformKv`] — строки SQLite, каждая зашифрована ключом `sm.store`
//! (связанные данные `sm/v1/` + таблица + id, ADR-63 п.2). Ядро хранит в них сессии и ключи
//! Signal; закрытый ключ личности — только в [`DeviceIdentity`] (ADR-63 п.4).
//! Все операции [`SignalSessions`] идут по очереди (замок): libsignal читает и переписывает
//! сессию, два одновременных вызова её бы испортили.

use std::sync::{Arc, Mutex};

use sm_signal::SignalError;
use sm_signal::beacon::{self, BEACON_KEY_LEN, PAIRING_SECRET_LEN};
use sm_signal::ble_part;
use sm_signal::contact::{self, Hello};
use sm_signal::invite::{CODE_KEY_LEN, InviteError, Kind, LinkInvite, SALT_LEN};
use sm_signal::node;
use sm_signal::pairing::{PROOF_LEN, PairFrame, QrInvite, SENDER_ID_LEN};
use sm_signal::queue::{self, QUEUE_SECRET_LEN};
use sm_signal::store::{Kv, KvError, Stores, Table};

use crate::keys::DeviceIdentity;

/// Ошибка Signal для Kotlin.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum SignalFfiError {
    /// Данные не разобрались (карточка, кадр, QR, адрес, имя).
    BadInput,
    /// Ключ личности не тот, что в QR (или не тот контакт).
    Mismatch,
    /// libsignal отказал: подпись, подделка, повтор, нет сессии.
    Protocol,
    /// Хранилище отказало (чтение, запись, расшифровка строки).
    Store,
    /// Обратный вызов Kotlin бросил неожиданное исключение.
    Callback,
}

impl std::fmt::Display for SignalFfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for SignalFfiError {}

impl From<uniffi::UnexpectedUniFFICallbackError> for SignalFfiError {
    fn from(_: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Callback
    }
}

impl From<SignalError> for SignalFfiError {
    fn from(e: SignalError) -> Self {
        match e {
            SignalError::BadInput => Self::BadInput,
            SignalError::Mismatch => Self::Mismatch,
            SignalError::Store => Self::Store,
            SignalError::Protocol(e) => {
                // Ошибка хранилища внутри libsignal — это отказ хранилища, а не протокола.
                if matches!(e, sm_signal::ProtocolError::ApplicationCallbackError(..)) {
                    Self::Store
                } else {
                    Self::Protocol
                }
            }
            SignalError::Signing
            | SignalError::Pending
            | SignalError::BadIdentity
            | SignalError::Crypto => Self::Protocol,
        }
    }
}

/// «Ключ → значение» на стороне Kotlin. `table` — имя таблицы (часть связанных данных
/// шифрования строки), `id` — id строки. Ошибка расшифровки — исключение, не `None`.
#[uniffi::export(with_foreign)]
pub trait PlatformKv: Send + Sync {
    /// Строка или `None`.
    ///
    /// # Errors
    /// [`SignalFfiError::Store`].
    fn get(&self, table: String, id: Vec<u8>) -> Result<Option<Vec<u8>>, SignalFfiError>;

    /// Записывает (заменяет) строку.
    ///
    /// # Errors
    /// [`SignalFfiError::Store`].
    fn put(&self, table: String, id: Vec<u8>, value: Vec<u8>) -> Result<(), SignalFfiError>;

    /// Удаляет строку; нет строки — не ошибка.
    ///
    /// # Errors
    /// [`SignalFfiError::Store`].
    fn delete(&self, table: String, id: Vec<u8>) -> Result<(), SignalFfiError>;
}

/// Переходник: хранилище Kotlin → `sm_signal::store::Kv`.
struct Bridge(Arc<dyn PlatformKv>);

impl Kv for Bridge {
    fn get(&self, table: Table, id: &[u8]) -> Result<Option<Vec<u8>>, KvError> {
        self.0
            .get(table.name().to_owned(), id.to_vec())
            .map_err(|_| KvError)
    }

    fn put(&self, table: Table, id: &[u8], value: &[u8]) -> Result<(), KvError> {
        self.0
            .put(table.name().to_owned(), id.to_vec(), value.to_vec())
            .map_err(|_| KvError)
    }

    fn delete(&self, table: Table, id: &[u8]) -> Result<(), KvError> {
        self.0
            .delete(table.name().to_owned(), id.to_vec())
            .map_err(|_| KvError)
    }
}

/// Сессия начата по карточке.
#[derive(Debug, uniffi::Record)]
pub struct StartedSession {
    /// Адрес контакта (ключ личности в hex).
    pub contact: String,
    /// Ключ личности контакта (33 байта).
    pub identity_key: Vec<u8>,
    /// Кадр [`PairFrame::Start`] для отправки.
    pub frame: Vec<u8>,
}

/// Первое сообщение принято.
#[derive(Debug, uniffi::Record)]
pub struct AcceptedSession {
    /// Адрес контакта.
    pub contact: String,
    /// Ключ личности контакта (33 байта).
    pub identity_key: Vec<u8>,
    /// Имя и ключ маяка контакта.
    pub hello: ContactHello,
}

/// Имя и ключ маяка контакта (идут только внутри Signal).
#[derive(uniffi::Record)]
pub struct ContactHello {
    /// Имя.
    pub name: String,
    /// Ключ маяка (32 байта, ADR-61).
    pub beacon_key: Vec<u8>,
}

impl std::fmt::Debug for ContactHello {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ContactHello")
            .field("name", &self.name)
            .finish_non_exhaustive()
    }
}

impl ContactHello {
    fn into_core(self) -> Result<Hello, SignalFfiError> {
        Ok(Hello {
            beacon_key: key32(&self.beacon_key)?,
            name: self.name,
        })
    }

    fn from_core(h: Hello) -> Self {
        Self {
            name: h.name,
            beacon_key: h.beacon_key.to_vec(),
        }
    }
}

fn key32(b: &[u8]) -> Result<[u8; BEACON_KEY_LEN], SignalFfiError> {
    <[u8; BEACON_KEY_LEN]>::try_from(b).map_err(|_| SignalFfiError::BadInput)
}

fn secret16(b: &[u8]) -> Result<[u8; PAIRING_SECRET_LEN], SignalFfiError> {
    <[u8; PAIRING_SECRET_LEN]>::try_from(b).map_err(|_| SignalFfiError::BadInput)
}

/// Разобранный кадр добавления контакта.
#[derive(Debug, uniffi::Enum)]
pub enum PairMessage {
    /// Карточка подключившегося; `proof` — доказательство знания секрета QR.
    Card {
        /// Карточка.
        card: Vec<u8>,
        /// Доказательство (32 байта) или `None` в «Добавить рядом».
        proof: Option<Vec<u8>>,
    },
    /// Первое сообщение Signal.
    Start {
        /// Зашифрованное сообщение.
        ciphertext: Vec<u8>,
    },
    /// Ответ по сессии.
    Reply {
        /// Зашифрованное сообщение.
        ciphertext: Vec<u8>,
    },
    /// Отказ пользователя.
    Decline,
    /// Имя для списка «Добавить рядом».
    Intro {
        /// Имя, которое человек указал сам.
        name: String,
    },
    /// Карточка получившего ссылку-приглашение и адрес его почты (ADR-83).
    LinkCard {
        /// Карточка.
        card: Vec<u8>,
        /// Доказательство знания секрета ссылки (32 байта).
        proof: Vec<u8>,
        /// ID отправителя его очереди (32 байта).
        sender_id: Vec<u8>,
        /// Запись его узла, текст `SMN1:…` (подпись ещё не проверена — `node_record_open`).
        node: String,
    },
}

/// Разбирает кадр добавления контакта.
///
/// # Errors
/// [`SignalFfiError::BadInput`].
#[uniffi::export]
pub fn pair_frame_decode(frame: &[u8]) -> Result<PairMessage, SignalFfiError> {
    Ok(match PairFrame::decode(frame)? {
        PairFrame::Card { card, proof } => PairMessage::Card {
            card,
            proof: proof.map(|p| p.to_vec()),
        },
        PairFrame::Start(ciphertext) => PairMessage::Start { ciphertext },
        PairFrame::Reply(ciphertext) => PairMessage::Reply { ciphertext },
        PairFrame::Decline => PairMessage::Decline,
        PairFrame::Intro(name) => PairMessage::Intro { name },
        PairFrame::LinkCard {
            card,
            proof,
            sender_id,
            node,
        } => PairMessage::LinkCard {
            card,
            proof: proof.to_vec(),
            sender_id: sender_id.to_vec(),
            node: node::signed_text(&node),
        },
    })
}

/// Кадр с именем для списка «Добавить рядом».
///
/// # Errors
/// [`SignalFfiError::BadInput`] — имя пустое или длиннее 128 байт.
#[uniffi::export]
pub fn pair_frame_intro(name: String) -> Result<Vec<u8>, SignalFfiError> {
    Ok(PairFrame::Intro(name).encode()?)
}

/// Кадр отказа.
///
/// # Errors
/// [`SignalFfiError::BadInput`] — не бывает, оставлено для единообразия.
#[uniffi::export]
pub fn pair_frame_decline() -> Result<Vec<u8>, SignalFfiError> {
    Ok(PairFrame::Decline.encode()?)
}

/// Содержимое QR.
#[derive(uniffi::Record)]
pub struct QrContent {
    /// Ключ личности показавшего (33 байта).
    pub identity_key: Vec<u8>,
    /// Секрет сопряжения (16 байт).
    pub secret: Vec<u8>,
}

impl std::fmt::Debug for QrContent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("QrContent").finish_non_exhaustive()
    }
}

/// Строка для QR: свой ключ личности и секрет сопряжения.
///
/// # Errors
/// [`SignalFfiError::BadInput`] — длины не те.
#[uniffi::export]
pub fn qr_encode(identity_key: &[u8], secret: &[u8]) -> Result<String, SignalFfiError> {
    Ok(QrInvite {
        identity_key: identity_key
            .try_into()
            .map_err(|_| SignalFfiError::BadInput)?,
        secret: secret16(secret)?,
    }
    .encode())
}

/// Разбирает строку из сканера; чужой QR — [`SignalFfiError::BadInput`].
///
/// # Errors
/// [`SignalFfiError::BadInput`].
#[uniffi::export]
pub fn qr_decode(text: &str) -> Result<QrContent, SignalFfiError> {
    let i = QrInvite::decode(text)?;
    Ok(QrContent {
        identity_key: i.identity_key.to_vec(),
        secret: i.secret.to_vec(),
    })
}

/// Содержимое ссылки-приглашения (ADR-83).
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct InviteLinkContent {
    /// Ключ личности пригласившего (33 байта).
    pub identity_key: Vec<u8>,
    /// Одноразовый секрет ссылки (16 байт).
    pub secret: Vec<u8>,
    /// До какого момента действует, секунды Unix.
    pub expires: u64,
    /// ID отправителя очереди пригласившего (32 байта).
    pub sender_id: Vec<u8>,
    /// Запись его узла, текст `SMN1:…` (подпись ещё не проверена — `node_record_open`).
    pub node: String,
}

impl std::fmt::Debug for InviteLinkContent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("InviteLinkContent")
            .field("expires", &self.expires)
            .finish_non_exhaustive()
    }
}

/// Почему ссылка не принята.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum InviteFfiError {
    /// Не ссылка-приглашение (или текст обрезан при пересылке).
    BadFormat,
    /// Срок ссылки истёк.
    Expired,
    /// Ссылка с кодом, код не подошёл.
    WrongCode,
}

/// Вид ссылки: открывается сразу или нужен код (ADR-142).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum InviteLinkKind {
    /// Версия 1: содержимое открыто.
    Open,
    /// Версия 2: нужен код из 4 слов.
    Coded,
}

impl From<InviteError> for InviteFfiError {
    fn from(e: InviteError) -> Self {
        match e {
            InviteError::BadFormat => Self::BadFormat,
            InviteError::Expired => Self::Expired,
            InviteError::WrongCode => Self::WrongCode,
        }
    }
}

impl std::fmt::Display for InviteFfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for InviteFfiError {}

fn sender32(b: &[u8]) -> Result<[u8; SENDER_ID_LEN], SignalFfiError> {
    <[u8; SENDER_ID_LEN]>::try_from(b).map_err(|_| SignalFfiError::BadInput)
}

/// Текст ссылки-приглашения: свой ключ личности, секрет ссылки, срок (секунды Unix), ID
/// отправителя своей очереди для этой ссылки и запись своего узла `SMN1:…`.
///
/// # Errors
/// [`SignalFfiError::BadInput`] — длины не те или запись узла не разобралась.
#[uniffi::export]
pub fn invite_link_encode(
    identity_key: &[u8],
    secret: &[u8],
    expires: u64,
    sender_id: &[u8],
    node: &str,
) -> Result<String, SignalFfiError> {
    LinkInvite {
        identity_key: identity_key
            .try_into()
            .map_err(|_| SignalFfiError::BadInput)?,
        secret: secret16(secret)?,
        expires,
        sender_id: sender32(sender_id)?,
        node: node::signed_bytes(node).map_err(|_| SignalFfiError::BadInput)?,
    }
    .encode()
    .map_err(|_| SignalFfiError::BadInput)
}

/// Разбирает вставленную ссылку на момент `now_secs` (секунды Unix).
///
/// # Errors
/// [`InviteFfiError`].
#[uniffi::export]
pub fn invite_link_decode(text: &str, now_secs: u64) -> Result<InviteLinkContent, InviteFfiError> {
    let i = LinkInvite::decode(text, now_secs)?;
    Ok(InviteLinkContent {
        identity_key: i.identity_key.to_vec(),
        secret: i.secret.to_vec(),
        expires: i.expires,
        sender_id: i.sender_id.to_vec(),
        node: i.node_text(),
    })
}

fn salt16(b: &[u8]) -> Result<[u8; SALT_LEN], SignalFfiError> {
    <[u8; SALT_LEN]>::try_from(b).map_err(|_| SignalFfiError::BadInput)
}

fn code_key32(b: &[u8]) -> Result<[u8; CODE_KEY_LEN], SignalFfiError> {
    <[u8; CODE_KEY_LEN]>::try_from(b).map_err(|_| SignalFfiError::BadInput)
}

/// Новая случайная соль Argon2id для ссылки с кодом (16 байт, `OsRng`).
#[uniffi::export]
#[must_use]
pub fn invite_link_salt_new() -> Vec<u8> {
    LinkInvite::salt_new().to_vec()
}

/// Текст ссылки с кодом (версия 2, ADR-142): то же содержимое, что у [`invite_link_encode`],
/// зашифровано ключом кода — результатом Argon2id от 4 слов и `salt` (его считает приложение).
///
/// # Errors
/// [`SignalFfiError::BadInput`] — длины не те или запись узла не разобралась.
#[uniffi::export]
pub fn invite_link_encode_coded(
    identity_key: &[u8],
    secret: &[u8],
    expires: u64,
    sender_id: &[u8],
    node: &str,
    salt: &[u8],
    code_key: &[u8],
) -> Result<String, SignalFfiError> {
    LinkInvite {
        identity_key: identity_key
            .try_into()
            .map_err(|_| SignalFfiError::BadInput)?,
        secret: secret16(secret)?,
        expires,
        sender_id: sender32(sender_id)?,
        node: node::signed_bytes(node).map_err(|_| SignalFfiError::BadInput)?,
    }
    .encode_coded(&salt16(salt)?, &code_key32(code_key)?)
    .map_err(|_| SignalFfiError::BadInput)
}

/// Вид вставленной ссылки: открыть сразу или спросить код.
///
/// # Errors
/// [`InviteFfiError::BadFormat`] — не ссылка-приглашение.
#[uniffi::export]
pub fn invite_link_kind(text: &str) -> Result<InviteLinkKind, InviteFfiError> {
    Ok(match LinkInvite::kind(text)? {
        Kind::Open => InviteLinkKind::Open,
        Kind::Coded => InviteLinkKind::Coded,
    })
}

/// Соль Argon2id из ссылки с кодом.
///
/// # Errors
/// [`InviteFfiError::BadFormat`] — не ссылка с кодом или текст обрезан.
#[uniffi::export]
pub fn invite_link_salt(text: &str) -> Result<Vec<u8>, InviteFfiError> {
    Ok(LinkInvite::coded_salt(text)?.to_vec())
}

/// Открывает ссылку с кодом ключом кода (32 байта — результат Argon2id) на момент `now_secs`.
///
/// # Errors
/// [`InviteFfiError::WrongCode`] — код не подошёл; [`InviteFfiError::BadFormat`] — не ссылка с
/// кодом или ключ не 32 байта; [`InviteFfiError::Expired`] — срок.
#[uniffi::export]
pub fn invite_link_decode_coded(
    text: &str,
    code_key: &[u8],
    now_secs: u64,
) -> Result<InviteLinkContent, InviteFfiError> {
    let key = <[u8; CODE_KEY_LEN]>::try_from(code_key).map_err(|_| InviteFfiError::BadFormat)?;
    let i = LinkInvite::decode_coded(text, &key, now_secs)?;
    Ok(InviteLinkContent {
        identity_key: i.identity_key.to_vec(),
        secret: i.secret.to_vec(),
        expires: i.expires,
        sender_id: i.sender_id.to_vec(),
        node: i.node_text(),
    })
}

/// ID получателя очереди ссылки-приглашения (32 байта): HMAC-SHA256(секрет очередей,
/// `sm-invite-queue/v1` ‖ секрет ссылки).
///
/// # Errors
/// [`SignalFfiError::BadInput`] — секрет очередей не 32 байта или секрет ссылки не 16.
#[uniffi::export]
pub fn queue_invite_recipient_id(
    secret: &[u8],
    invite_secret: &[u8],
) -> Result<Vec<u8>, SignalFfiError> {
    let secret =
        <[u8; QUEUE_SECRET_LEN]>::try_from(secret).map_err(|_| SignalFfiError::BadInput)?;
    Ok(queue::invite_recipient_id(&secret, &secret16(invite_secret)?)?.to_vec())
}

/// Новый одноразовый секрет сопряжения (16 байт, `OsRng`).
#[uniffi::export]
#[must_use]
pub fn pairing_secret_new() -> Vec<u8> {
    beacon::new_pairing_secret().to_vec()
}

/// Новый ключ маяка устройства (32 байта, `OsRng`).
#[uniffi::export]
#[must_use]
pub fn beacon_key_new() -> Vec<u8> {
    beacon::new_beacon_key().to_vec()
}

/// Новый секрет очередей устройства (32 байта, `OsRng`, ADR-134). Хранится зашифрованным, как
/// ключ маяка; контактам не передаётся.
#[uniffi::export]
#[must_use]
pub fn queue_secret_new() -> Vec<u8> {
    queue::new_queue_secret().to_vec()
}

/// ID получателя своей очереди для контакта `contact` (адрес контакта) в период `period`
/// (32 байта, ADR-134): HMAC-SHA256(секрет, `sm-queue/v1` ‖ контакт ‖ период u64 BE).
///
/// # Errors
/// [`SignalFfiError::BadInput`] — секрет не 32 байта или адрес контакта пустой.
#[uniffi::export]
pub fn queue_recipient_id(
    secret: &[u8],
    contact: &str,
    period: u64,
) -> Result<Vec<u8>, SignalFfiError> {
    let secret =
        <[u8; QUEUE_SECRET_LEN]>::try_from(secret).map_err(|_| SignalFfiError::BadInput)?;
    if contact.is_empty() {
        return Err(SignalFfiError::BadInput);
    }
    Ok(queue::recipient_id(&secret, contact, period)?.to_vec())
}

/// Метка сопряжения по секрету QR в эпоху `epoch` (16 байт).
///
/// # Errors
/// [`SignalFfiError::BadInput`] — секрет не 16 байт.
#[uniffi::export]
pub fn ble_pairing_token(secret: &[u8], epoch: u64) -> Result<Vec<u8>, SignalFfiError> {
    Ok(beacon::pairing_token(&secret16(secret)?, epoch)?.to_vec())
}

/// Своя метка по ключу маяка в эпоху `epoch` (16 байт) — её объявляет телефон.
///
/// # Errors
/// [`SignalFfiError::BadInput`] — ключ не 32 байта.
#[uniffi::export]
pub fn ble_contact_token(beacon_key: &[u8], epoch: u64) -> Result<Vec<u8>, SignalFfiError> {
    Ok(beacon::contact_token(&key32(beacon_key)?, epoch)?.to_vec())
}

/// Ключ частей Bluetooth для устройства с ключом маяка `beacon_key` (ADR-92): свой ключ маяка —
/// чтобы открывать входящие, ключ маяка контакта — чтобы писать ему.
///
/// # Errors
/// [`SignalFfiError::BadInput`] — ключ не 32 байта.
#[uniffi::export]
pub fn ble_part_key_contact(beacon_key: &[u8]) -> Result<Vec<u8>, SignalFfiError> {
    Ok(ble_part::contact_key(&key32(beacon_key)?)?.to_vec())
}

/// Ключ частей сопряжения по секрету QR (ADR-92 п.2).
///
/// # Errors
/// [`SignalFfiError::BadInput`] — секрет не 16 байт.
#[uniffi::export]
pub fn ble_part_key_pairing(secret: &[u8]) -> Result<Vec<u8>, SignalFfiError> {
    Ok(ble_part::pairing_key(&secret16(secret)?)?.to_vec())
}

/// Ключ частей экрана «Добавить рядом» — общий для всех.
///
/// # Errors
/// [`SignalFfiError`] — отказ HMAC.
#[uniffi::export]
pub fn ble_part_key_nearby() -> Result<Vec<u8>, SignalFfiError> {
    Ok(ble_part::add_nearby_key()?.to_vec())
}

/// Метки контакта на эпохи `t − 1`, `t`, `t + 1` — их ищет сканер.
///
/// # Errors
/// [`SignalFfiError::BadInput`] — ключ не 32 байта.
#[uniffi::export]
pub fn ble_contact_tokens_around(
    beacon_key: &[u8],
    epoch: u64,
) -> Result<Vec<Vec<u8>>, SignalFfiError> {
    Ok(beacon::contact_tokens_around(&key32(beacon_key)?, epoch)?
        .iter()
        .map(|t| t.to_vec())
        .collect())
}

/// Адрес контакта (как в книге контактов) по ключу личности из QR — до начала сессии.
///
/// # Errors
/// [`SignalFfiError::BadInput`].
#[uniffi::export]
pub fn contact_of_identity(identity_key: &[u8]) -> Result<String, SignalFfiError> {
    Ok(contact::identity_contact(identity_key)?)
}

/// Адрес владельца карточки — до начала сессии (уже добавленный контакт не трогаем).
///
/// # Errors
/// [`SignalFfiError::BadInput`].
#[uniffi::export]
pub fn contact_of_card(card: &[u8]) -> Result<String, SignalFfiError> {
    Ok(contact::contact_id(&contact::card_identity(card)?))
}

/// Адрес отправителя первого сообщения (кадр `Start`) — до расшифровки.
///
/// # Errors
/// [`SignalFfiError::BadInput`].
#[uniffi::export]
pub fn contact_of_start(ciphertext: &[u8]) -> Result<String, SignalFfiError> {
    Ok(contact::start_contact(ciphertext)?)
}

/// Код безопасности пары ключей (60 цифр) — одинаковый на обоих телефонах.
///
/// # Errors
/// [`SignalFfiError::BadInput`].
#[uniffi::export]
pub fn safety_number(local: &[u8], remote: &[u8]) -> Result<String, SignalFfiError> {
    Ok(contact::safety_number(local, remote)?)
}

/// Хранилища Signal устройства и операции над сессиями.
#[derive(uniffi::Object)]
pub struct SignalSessions {
    identity: Arc<DeviceIdentity>,
    kv: Bridge,
    lock: Mutex<()>,
}

impl std::fmt::Debug for SignalSessions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SignalSessions").finish_non_exhaustive()
    }
}

impl SignalSessions {
    fn with<T>(
        &self,
        f: impl FnOnce(&Stores<'_, Bridge>) -> Result<T, SignalError>,
    ) -> Result<T, SignalFfiError> {
        // Отравленный замок: прошлый вызов упал посреди операции — дальше не работаем.
        let _guard = self.lock.lock().map_err(|_| SignalFfiError::Store)?;
        let stores = Stores::new(self.identity.inner(), &self.kv);
        f(&stores).map_err(SignalFfiError::from)
    }
}

impl SignalSessions {
    /// [`contact::decrypt_from_any`] под замком (для `chat`).
    pub(crate) fn decrypt_from_any(
        &self,
        candidates: &[String],
        ciphertext: &[u8],
    ) -> Result<Option<(String, Vec<u8>)>, SignalFfiError> {
        self.with(|s| contact::decrypt_from_any(s, candidates, ciphertext))
    }
}

#[uniffi::export]
impl SignalSessions {
    /// Хранилища над ключом личности `identity` и строками `kv`.
    #[uniffi::constructor]
    #[must_use]
    pub fn new(identity: Arc<DeviceIdentity>, kv: Arc<dyn PlatformKv>) -> Arc<Self> {
        Arc::new(Self {
            identity,
            kv: Bridge(kv),
            lock: Mutex::new(()),
        })
    }

    /// Кадр [`PairFrame::Card`] со свежей карточкой; `qr_secret` — секрет из отсканированного
    /// QR (к карточке приложится доказательство), `None` — «Добавить рядом».
    ///
    /// # Errors
    /// [`SignalFfiError::Store`], [`SignalFfiError::BadInput`].
    pub fn card_frame(&self, qr_secret: Option<Vec<u8>>) -> Result<Vec<u8>, SignalFfiError> {
        let secret = qr_secret.map(|s| secret16(&s)).transpose()?;
        let card = self.with(contact::new_card)?;
        let proof = match secret {
            Some(s) => Some(
                <[u8; PROOF_LEN]>::try_from(beacon::pairing_proof(&s, &card)?)
                    .map_err(|_| SignalFfiError::Protocol)?,
            ),
            None => None,
        };
        Ok(PairFrame::Card { card, proof }.encode()?)
    }

    /// Принимает карточку: в сценарии QR (`qr_secret` — свой секрет из показанного QR)
    /// сначала проверяет доказательство; начинает сессию и шифрует своё [`ContactHello`].
    ///
    /// # Errors
    /// [`SignalFfiError::Mismatch`] — доказательства нет или оно неверно;
    /// [`SignalFfiError::Protocol`] — подпись ключей неверна; [`SignalFfiError::BadInput`].
    pub fn accept_card(
        &self,
        card: &[u8],
        proof: Option<Vec<u8>>,
        qr_secret: Option<Vec<u8>>,
        hello: ContactHello,
    ) -> Result<StartedSession, SignalFfiError> {
        if let Some(s) = qr_secret.map(|s| secret16(&s)).transpose()? {
            let p = proof.ok_or(SignalFfiError::Mismatch)?;
            if !beacon::pairing_proof_ok(&s, card, &p)? {
                return Err(SignalFfiError::Mismatch);
            }
        }
        let first = hello.into_core()?.encode()?;
        let st = self.with(|s| contact::start_session(s, card, &first))?;
        Ok(StartedSession {
            contact: st.contact,
            identity_key: st.identity_key,
            frame: PairFrame::Start(st.ciphertext).encode()?,
        })
    }

    /// Кадр [`PairFrame::LinkCard`]: свежая карточка, ID отправителя своей очереди для
    /// пригласившего и запись своего узла `SMN1:…`; доказательство — по секрету ссылки.
    ///
    /// # Errors
    /// [`SignalFfiError::Store`], [`SignalFfiError::BadInput`].
    pub fn link_card_frame(
        &self,
        link_secret: &[u8],
        sender_id: &[u8],
        node: &str,
    ) -> Result<Vec<u8>, SignalFfiError> {
        let secret = secret16(link_secret)?;
        let sender_id = sender32(sender_id)?;
        let node = node::signed_bytes(node).map_err(|_| SignalFfiError::BadInput)?;
        let card = self.with(contact::new_card)?;
        let proof =
            <[u8; PROOF_LEN]>::try_from(beacon::link_proof(&secret, &card, &sender_id, &node)?)
                .map_err(|_| SignalFfiError::Protocol)?;
        Ok(PairFrame::LinkCard {
            card,
            proof,
            sender_id,
            node,
        }
        .encode()?)
    }

    /// Принимает карточку из [`PairMessage::LinkCard`] по секрету своей ссылки: проверяет
    /// доказательство (вместе с адресом почты), начинает сессию и шифрует своё [`ContactHello`].
    /// Запись узла получившего проверяет Kotlin (`node_record_open`) до вызова.
    ///
    /// # Errors
    /// [`SignalFfiError::Mismatch`] — доказательство неверно; [`SignalFfiError::Protocol`],
    /// [`SignalFfiError::BadInput`].
    pub fn accept_link_card(
        &self,
        card: &[u8],
        proof: &[u8],
        sender_id: &[u8],
        node: &str,
        link_secret: &[u8],
        hello: ContactHello,
    ) -> Result<StartedSession, SignalFfiError> {
        let secret = secret16(link_secret)?;
        let sender_id = sender32(sender_id)?;
        let node = node::signed_bytes(node).map_err(|_| SignalFfiError::BadInput)?;
        if !beacon::link_proof_ok(&secret, card, &sender_id, &node, proof)? {
            return Err(SignalFfiError::Mismatch);
        }
        let first = hello.into_core()?.encode()?;
        let st = self.with(|s| contact::start_session(s, card, &first))?;
        Ok(StartedSession {
            contact: st.contact,
            identity_key: st.identity_key,
            frame: PairFrame::Start(st.ciphertext).encode()?,
        })
    }

    /// Принимает первое сообщение; `expected_identity` — ключ из отсканированного QR.
    /// Ответ — кадр [`PairFrame::Reply`] с `own` — получает [`Self::reply_frame`].
    ///
    /// # Errors
    /// [`SignalFfiError::Mismatch`] — ключ не тот, что в QR; [`SignalFfiError::Protocol`].
    pub fn accept_start(
        &self,
        ciphertext: &[u8],
        expected_identity: Option<Vec<u8>>,
    ) -> Result<AcceptedSession, SignalFfiError> {
        let acc = self
            .with(move |s| contact::accept_session(s, ciphertext, expected_identity.as_deref()))?;
        let hello = match Hello::decode(&acc.plaintext) {
            Ok(h) => h,
            Err(e) => {
                // Сессия создана, но содержимое не наше — контакт не добавляем.
                let _ = self.with(|s| contact::forget(s, &acc.contact));
                return Err(e.into());
            }
        };
        Ok(AcceptedSession {
            contact: acc.contact,
            identity_key: acc.identity_key,
            hello: ContactHello::from_core(hello),
        })
    }

    /// Кадр ответа со своим [`ContactHello`] по уже начатой сессии.
    ///
    /// # Errors
    /// [`SignalFfiError::Protocol`] — сессии нет; [`SignalFfiError::BadInput`].
    pub fn reply_frame(
        &self,
        contact: &str,
        hello: ContactHello,
    ) -> Result<Vec<u8>, SignalFfiError> {
        let b = hello.into_core()?.encode()?;
        let c = self.with(|s| contact::encrypt(s, contact, &b))?;
        Ok(PairFrame::Reply(c).encode()?)
    }

    /// Расшифровывает ответ с [`ContactHello`] контакта.
    ///
    /// # Errors
    /// [`SignalFfiError::Protocol`], [`SignalFfiError::BadInput`].
    pub fn open_reply(
        &self,
        contact: &str,
        ciphertext: &[u8],
    ) -> Result<ContactHello, SignalFfiError> {
        let p = self.with(|s| contact::decrypt(s, contact, ciphertext))?;
        Ok(ContactHello::from_core(Hello::decode(&p)?))
    }

    /// Шифрует сообщение контакту.
    ///
    /// # Errors
    /// [`SignalFfiError::Protocol`] — сессии нет.
    pub fn encrypt(&self, contact: &str, plaintext: &[u8]) -> Result<Vec<u8>, SignalFfiError> {
        self.with(|s| contact::encrypt(s, contact, plaintext))
    }

    /// Расшифровывает сообщение контакта.
    ///
    /// # Errors
    /// [`SignalFfiError::Protocol`] — подделка, повтор; [`SignalFfiError::Mismatch`].
    pub fn decrypt(&self, contact: &str, ciphertext: &[u8]) -> Result<Vec<u8>, SignalFfiError> {
        self.with(|s| contact::decrypt(s, contact, ciphertext))
    }

    /// Удаляет сессию и ключ контакта.
    ///
    /// # Errors
    /// [`SignalFfiError::Store`].
    pub fn forget(&self, contact: &str) -> Result<(), SignalFfiError> {
        self.with(|s| contact::forget(s, contact))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    type Rows = HashMap<(String, Vec<u8>), Vec<u8>>;

    #[derive(Default)]
    struct TestKv(Mutex<Rows>);

    impl PlatformKv for TestKv {
        fn get(&self, table: String, id: Vec<u8>) -> Result<Option<Vec<u8>>, SignalFfiError> {
            Ok(self.0.lock().unwrap().get(&(table, id)).cloned())
        }
        fn put(&self, table: String, id: Vec<u8>, value: Vec<u8>) -> Result<(), SignalFfiError> {
            self.0.lock().unwrap().insert((table, id), value);
            Ok(())
        }
        fn delete(&self, table: String, id: Vec<u8>) -> Result<(), SignalFfiError> {
            self.0.lock().unwrap().remove(&(table, id));
            Ok(())
        }
    }

    fn phone() -> (Arc<DeviceIdentity>, Arc<SignalSessions>) {
        let id = DeviceIdentity::generate_for_test();
        let s = SignalSessions::new(id.clone(), Arc::new(TestKv::default()));
        (id, s)
    }

    fn hello(name: &str) -> ContactHello {
        ContactHello {
            name: name.to_owned(),
            beacon_key: beacon_key_new(),
        }
    }

    /// Ссылка с кодом (ADR-142): вид, соль, неверный код, ключ не той длины.
    #[test]
    fn coded_link() {
        let (a_id, _) = phone();
        let now = 1_800_000_000;
        let secret = pairing_secret_new();
        let node = node::signed_text(&[1; 100]);
        let salt = invite_link_salt_new();
        let key = [5u8; 32];
        let link = invite_link_encode_coded(
            &a_id.public_key(),
            &secret,
            now + 86_400,
            &[3; 32],
            &node,
            &salt,
            &key,
        )
        .unwrap();
        assert_eq!(invite_link_kind(&link), Ok(InviteLinkKind::Coded));
        assert_eq!(invite_link_salt(&link), Ok(salt.clone()));
        assert_eq!(
            invite_link_decode(&link, now),
            Err(InviteFfiError::BadFormat)
        );
        let got = invite_link_decode_coded(&link, &key, now).unwrap();
        assert_eq!(got.identity_key, a_id.public_key());
        assert_eq!(got.secret, secret);
        assert_eq!(got.node, node);
        assert!(!format!("{got:?}").contains("secret"));
        assert_eq!(
            invite_link_decode_coded(&link, &[6u8; 32], now),
            Err(InviteFfiError::WrongCode)
        );
        assert_eq!(
            invite_link_decode_coded(&link, &key[..31], now),
            Err(InviteFfiError::BadFormat)
        );
        assert_eq!(
            invite_link_decode_coded(&link, &key, now + 86_400),
            Err(InviteFfiError::Expired)
        );
        let open =
            invite_link_encode(&a_id.public_key(), &secret, now + 86_400, &[3; 32], &node).unwrap();
        assert_eq!(invite_link_kind(&open), Ok(InviteLinkKind::Open));
        assert_eq!(invite_link_salt(&open), Err(InviteFfiError::BadFormat));
        assert_eq!(
            invite_link_encode_coded(
                &a_id.public_key(),
                &secret,
                now,
                &[3; 32],
                &node,
                &salt[..15],
                &key
            ),
            Err(SignalFfiError::BadInput)
        );
    }

    /// Сценарий ссылки (ADR-83): А создаёт ссылку, Б вставляет; кадры идут через узел.
    #[test]
    fn link_flow() {
        let (a_id, a) = phone();
        let (b_id, b) = phone();
        let now = 1_800_000_000;
        let secret = pairing_secret_new();
        let a_node = node::signed_text(&[1; 100]);
        let b_node = node::signed_text(&[2; 120]);
        let qs = queue_secret_new();
        let a_sender = queue_invite_recipient_id(&qs, &secret).unwrap();
        let link = invite_link_encode(
            &a_id.public_key(),
            &secret,
            now + 86_400,
            &a_sender,
            &a_node,
        )
        .unwrap();
        assert_eq!(
            invite_link_decode(&link, now + 86_400),
            Err(InviteFfiError::Expired)
        );
        assert_eq!(
            invite_link_decode("SM1:00", now),
            Err(InviteFfiError::BadFormat)
        );
        let got = invite_link_decode(&link, now).unwrap();
        assert_eq!(got.identity_key, a_id.public_key());
        assert_eq!(got.sender_id, a_sender);
        assert_eq!(got.node, a_node);
        assert!(!format!("{got:?}").contains("secret"));

        let frame = b.link_card_frame(&got.secret, &[7; 32], &b_node).unwrap();
        let PairMessage::LinkCard {
            card,
            proof,
            sender_id,
            node,
        } = pair_frame_decode(&frame).unwrap()
        else {
            panic!("не карточка ссылки");
        };
        assert_eq!(
            (sender_id.as_slice(), node.as_str()),
            (&[7; 32][..], b_node.as_str())
        );
        // Чужой секрет или подменённый адрес почты — отказ.
        for (sid, n, s) in [
            (&sender_id[..], node.as_str(), pairing_secret_new()),
            (&[8; 32][..], node.as_str(), secret.clone()),
            (&sender_id[..], a_node.as_str(), secret.clone()),
        ] {
            assert_eq!(
                a.accept_link_card(&card, &proof, sid, n, &s, hello("А"))
                    .unwrap_err(),
                SignalFfiError::Mismatch
            );
        }
        let st = a
            .accept_link_card(&card, &proof, &sender_id, &node, &secret, hello("Аня"))
            .unwrap();
        assert_eq!(st.identity_key, b_id.public_key());
        let PairMessage::Start { ciphertext } = pair_frame_decode(&st.frame).unwrap() else {
            panic!("не начало");
        };
        // Ключ пригласившего — из ссылки: подмену видно.
        let (other, _) = phone();
        assert_eq!(
            b.accept_start(&ciphertext, Some(other.public_key()))
                .unwrap_err(),
            SignalFfiError::Mismatch
        );
        let acc = b.accept_start(&ciphertext, Some(got.identity_key)).unwrap();
        assert_eq!(acc.hello.name, "Аня");
        let reply = b.reply_frame(&acc.contact, hello("Боря")).unwrap();
        let PairMessage::Reply { ciphertext } = pair_frame_decode(&reply).unwrap() else {
            panic!("не ответ");
        };
        assert_eq!(a.open_reply(&st.contact, &ciphertext).unwrap().name, "Боря");
    }

    #[test]
    fn link_inputs_are_checked() {
        let (id, s) = phone();
        let n = node::signed_text(&[1; 100]);
        let k = id.public_key();
        let sec = pairing_secret_new();
        assert!(invite_link_encode(&k, &sec, 1, &[0; 32], &n).is_ok());
        assert!(invite_link_encode(&k[..32], &sec, 1, &[0; 32], &n).is_err());
        assert!(invite_link_encode(&k, &sec[..15], 1, &[0; 32], &n).is_err());
        assert!(invite_link_encode(&k, &sec, 1, &[0; 31], &n).is_err());
        assert!(invite_link_encode(&k, &sec, 1, &[0; 32], "SMN1:00").is_err());
        assert!(s.link_card_frame(&sec, &[0; 32], "мусор").is_err());
        assert!(s.link_card_frame(&sec, &[0; 33], &n).is_err());
        assert!(queue_invite_recipient_id(&[0; 31], &sec).is_err());
        assert!(queue_invite_recipient_id(&[0; 32], &sec[..15]).is_err());
    }

    /// Полный сценарий QR: А показывает, Б сканирует.
    #[test]
    fn qr_flow() {
        let (a_id, a) = phone();
        let (b_id, b) = phone();
        let secret = pairing_secret_new();
        let qr = qr_encode(&a_id.public_key(), &secret).unwrap();

        let scanned = qr_decode(&qr).unwrap();
        let frame = b.card_frame(Some(scanned.secret.clone())).unwrap();

        let PairMessage::Card { card, proof } = pair_frame_decode(&frame).unwrap() else {
            panic!("не карточка");
        };
        // Без доказательства или с чужим секретом — отказ.
        assert_eq!(
            a.accept_card(&card, None, Some(secret.clone()), hello("А"))
                .unwrap_err(),
            SignalFfiError::Mismatch
        );
        assert_eq!(
            a.accept_card(&card, proof.clone(), Some(pairing_secret_new()), hello("А"))
                .unwrap_err(),
            SignalFfiError::Mismatch
        );
        let a_hello = hello("Аня");
        let a_key = a_hello.beacon_key.clone();
        // Адрес виден до начала сессии — тот же, что даст сессия.
        let b_addr = contact_of_card(&card).unwrap();
        assert_eq!(b_addr, contact_of_identity(&b_id.public_key()).unwrap());
        let st = a.accept_card(&card, proof, Some(secret), a_hello).unwrap();
        assert_eq!(st.identity_key, b_id.public_key());
        assert_eq!(st.contact, b_addr);

        let PairMessage::Start { ciphertext } = pair_frame_decode(&st.frame).unwrap() else {
            panic!("не начало");
        };
        let a_addr = contact_of_identity(&scanned.identity_key).unwrap();
        assert_eq!(contact_of_start(&ciphertext).unwrap(), a_addr);
        assert!(contact_of_start(&card).is_err());
        assert!(contact_of_card(&ciphertext).is_err());
        assert!(contact_of_identity(&[5; 32]).is_err());
        let acc = b
            .accept_start(&ciphertext, Some(scanned.identity_key))
            .unwrap();
        assert_eq!(acc.contact, a_addr);
        assert_eq!(acc.hello.name, "Аня");
        assert_eq!(acc.hello.beacon_key, a_key);
        let reply = b.reply_frame(&acc.contact, hello("Боря")).unwrap();
        let PairMessage::Reply { ciphertext } = pair_frame_decode(&reply).unwrap() else {
            panic!("не ответ");
        };
        assert_eq!(a.open_reply(&st.contact, &ciphertext).unwrap().name, "Боря");

        // Дальше — обычные сообщения.
        let m = a.encrypt(&st.contact, b"hi").unwrap();
        assert_eq!(b.decrypt(&acc.contact, &m).unwrap(), b"hi");
        assert_eq!(
            safety_number(&a_id.public_key(), &b_id.public_key()).unwrap(),
            safety_number(&b_id.public_key(), &a_id.public_key()).unwrap()
        );
        b.forget(&acc.contact).unwrap();
        assert!(b.encrypt(&acc.contact, b"x").is_err());
    }

    /// «Добавить рядом»: без секрета; подмена ключа в QR — отказ у сканировавшего.
    #[test]
    fn nearby_flow_and_wrong_qr_key() {
        let (_, a) = phone();
        let (_, b) = phone();
        let (other_id, _) = phone();
        let frame = b.card_frame(None).unwrap();
        let PairMessage::Card { card, proof } = pair_frame_decode(&frame).unwrap() else {
            panic!("не карточка");
        };
        assert!(proof.is_none());
        let st = a.accept_card(&card, None, None, hello("А")).unwrap();
        let PairMessage::Start { ciphertext } = pair_frame_decode(&st.frame).unwrap() else {
            panic!("не начало");
        };
        assert_eq!(
            b.accept_start(&ciphertext, Some(other_id.public_key()))
                .unwrap_err(),
            SignalFfiError::Mismatch
        );
        assert!(b.accept_start(&ciphertext, None).is_ok());
    }

    #[test]
    fn chat_after_pairing_and_resend() {
        use crate::chat::{ChatBody, ChatMessage, chat_encode, message_id_new};
        let (_, a) = phone();
        let (_, b) = phone();
        let PairMessage::Card { card, .. } =
            pair_frame_decode(&b.card_frame(None).unwrap()).unwrap()
        else {
            panic!("не карточка");
        };
        let st = a.accept_card(&card, None, None, hello("А")).unwrap();
        let PairMessage::Start { ciphertext } = pair_frame_decode(&st.frame).unwrap() else {
            panic!("не начало");
        };
        let acc = b.accept_start(&ciphertext, None).unwrap();
        let PairMessage::Reply { ciphertext: reply } =
            pair_frame_decode(&b.reply_frame(&acc.contact, hello("Б")).unwrap()).unwrap()
        else {
            panic!("не ответ");
        };
        a.open_reply(&st.contact, &reply).unwrap();

        let m = ChatMessage {
            id: message_id_new(),
            sent_at_ms: 5,
            body: ChatBody::Text {
                text: "тест".into(),
            },
        };
        let queued = chat_encode(m.clone()).unwrap();
        let first = a.encrypt(&st.contact, &queued).unwrap();
        let resend = a.encrypt(&st.contact, &queued).unwrap();
        let got = b.open(vec![acc.contact.clone()], &first).unwrap().unwrap();
        assert_eq!(got.contact, acc.contact);
        assert_eq!(got.message, m);
        // Тот же шифротекст ещё раз — «никто»; новая попытка из очереди — то же сообщение.
        assert!(b.open(vec![acc.contact.clone()], &first).unwrap().is_none());
        let again = b.open(vec![acc.contact.clone()], &resend).unwrap().unwrap();
        assert_eq!(again.message.id, m.id);
        // Расшифровалось, но это не сообщение (нет выравнивания) — ошибка.
        let raw = a.encrypt(&st.contact, b"not padded").unwrap();
        assert_eq!(
            b.open(vec![acc.contact.clone()], &raw).unwrap_err(),
            SignalFfiError::BadInput
        );
        assert!(b.open(vec![], &first).unwrap().is_none());
    }

    #[test]
    fn helpers_reject_bad_lengths() {
        assert!(qr_encode(&[1; 33], &[0; 15]).is_err());
        assert!(qr_encode(&[1; 32], &[0; 16]).is_err());
        assert!(qr_decode("SM1:00").is_err());
        assert!(ble_pairing_token(&[0; 15], 1).is_err());
        assert_eq!(ble_pairing_token(&[0; 16], 1).unwrap().len(), 16);
        assert!(ble_contact_token(&[0; 31], 1).is_err());
        let around = ble_contact_tokens_around(&[1; 32], 10).unwrap();
        assert_eq!(around.len(), 3);
        assert_eq!(around[1], ble_contact_token(&[1; 32], 10).unwrap());
        assert_eq!(beacon_key_new().len(), 32);
        assert_eq!(pairing_secret_new().len(), 16);
        assert!(matches!(
            pair_frame_decode(&pair_frame_decline().unwrap()).unwrap(),
            PairMessage::Decline
        ));
        assert!(pair_frame_decode(&[]).is_err());
        let PairMessage::Intro { name } =
            pair_frame_decode(&pair_frame_intro("Оля".to_owned()).unwrap()).unwrap()
        else {
            panic!("не имя");
        };
        assert_eq!(name, "Оля");
        assert!(pair_frame_intro(String::new()).is_err());
        let (_, a) = phone();
        assert!(a.card_frame(Some(vec![0; 3])).is_err());
        assert!(a.accept_card(&[1], None, None, hello("x")).is_err());
        let bad_hello = ContactHello {
            name: String::new(),
            beacon_key: vec![0; 32],
        };
        assert!(format!("{bad_hello:?}").contains("name"));
    }
    #[test]
    fn queue_recipient_id_checks_input() {
        let s = queue_secret_new();
        assert_eq!(s.len(), 32);
        let id = queue_recipient_id(&s, "05ab", 7).unwrap();
        assert_eq!(id.len(), 32);
        assert_eq!(id, queue_recipient_id(&s, "05ab", 7).unwrap());
        assert_ne!(id, queue_recipient_id(&s, "05ab", 8).unwrap());
        assert_eq!(
            queue_recipient_id(&s[..31], "05ab", 7),
            Err(SignalFfiError::BadInput)
        );
        assert_eq!(queue_recipient_id(&s, "", 7), Err(SignalFfiError::BadInput));
    }
}
