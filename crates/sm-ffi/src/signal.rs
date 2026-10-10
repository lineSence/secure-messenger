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
use sm_signal::contact::{self, Hello};
use sm_signal::pairing::{PROOF_LEN, PairFrame, QrInvite};
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
    })
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
        let st = a.accept_card(&card, proof, Some(secret), a_hello).unwrap();
        assert_eq!(st.identity_key, b_id.public_key());

        let PairMessage::Start { ciphertext } = pair_frame_decode(&st.frame).unwrap() else {
            panic!("не начало");
        };
        let acc = b
            .accept_start(&ciphertext, Some(scanned.identity_key))
            .unwrap();
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
        let (_, a) = phone();
        assert!(a.card_frame(Some(vec![0; 3])).is_err());
        assert!(a.accept_card(&[1], None, None, hello("x")).is_err());
        let bad_hello = ContactHello {
            name: String::new(),
            beacon_key: vec![0; 32],
        };
        assert!(format!("{bad_hello:?}").contains("name"));
    }
}
