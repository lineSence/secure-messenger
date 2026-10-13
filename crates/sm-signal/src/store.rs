//! Хранилища libsignal поверх «ключ → значение» платформы (ADR-63 п.3).
//!
//! Платформа (Kotlin, SQLite) даёт [`Kv`]: записи по таблице и id. Шифрование строк ключом
//! `sm.store` и связанные данные `sm/v1/` + таблица + id — на стороне платформы (ADR-63 п.2);
//! сюда приходят уже расшифрованные байты, а ошибка расшифровки — [`KvError`] (отказ, п.5).
//!
//! Ключ личности в базе не хранится: его даёт [`DeviceIdentity`] (обёрнут `sm.keys`, ADR-63 п.4).
//!
//! Записи — сериализация самой libsignal (`SessionRecord`, `PreKeyRecord` …), id:
//! - адрес контакта — `имя.устройство` (UTF-8);
//! - ключи — номер `u32` BE.

use async_trait::async_trait;
use libsignal_protocol::{
    Direction, GenericSignedPreKey as _, IdentityChange, IdentityKey, IdentityKeyPair,
    IdentityKeyStore, KyberPreKeyId, KyberPreKeyRecord, KyberPreKeyStore, PreKeyId, PreKeyRecord,
    PreKeyStore, ProtocolAddress, PublicKey, SessionRecord, SessionStore, SignalProtocolError,
    SignedPreKeyId, SignedPreKeyRecord, SignedPreKeyStore,
};

use crate::DeviceIdentity;
use sm_core::keystore::Secret;

/// Таблица записей Signal.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Table {
    /// Ключи личности контактов (по адресу).
    Identity,
    /// Сессии Double Ratchet (по адресу).
    Session,
    /// Одноразовые ключи.
    PreKey,
    /// Подписанные ключи.
    SignedPreKey,
    /// Ключи Kyber (ML-KEM).
    KyberPreKey,
    /// Bounded card ownership and issuance quota journal.
    CardRegistry,
}

impl Table {
    /// Имя таблицы — часть связанных данных шифрования строки на платформе.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Identity => "signal_identity",
            Self::Session => "signal_session",
            Self::PreKey => "signal_prekey",
            Self::SignedPreKey => "signal_signed_prekey",
            Self::KyberPreKey => "signal_kyber_prekey",
            Self::CardRegistry => "signal_card_registry",
        }
    }
}

/// Хранилище не ответило или запись не расшифровалась — операция Signal отменяется.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KvError;

impl core::fmt::Display for KvError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str("хранилище платформы отказало")
    }
}

impl std::error::Error for KvError {}

/// «Ключ → значение» платформы. Изменение видно сразу следующему чтению.
pub trait Kv {
    /// Запись или `None`, если её нет.
    ///
    /// # Errors
    /// [`KvError`] — чтение или расшифровка не удались.
    fn get(&self, table: Table, id: &[u8]) -> Result<Option<Vec<u8>>, KvError>;

    /// Записывает (заменяет) запись.
    ///
    /// # Errors
    /// [`KvError`].
    fn put(&self, table: Table, id: &[u8], value: &[u8]) -> Result<(), KvError>;

    /// Удаляет запись; нет записи — не ошибка.
    ///
    /// # Errors
    /// [`KvError`].
    fn delete(&self, table: Table, id: &[u8]) -> Result<(), KvError>;
}

fn kv_err(method: &'static str) -> impl FnOnce(KvError) -> SignalProtocolError {
    SignalProtocolError::for_application_callback(method)
}

/// id записи по адресу контакта.
#[must_use]
pub fn address_id(address: &ProtocolAddress) -> Vec<u8> {
    format!("{}.{}", address.name(), address.device_id()).into_bytes()
}

/// Ключ личности: свой — из [`DeviceIdentity`], контактов — из [`Table::Identity`].
pub struct IdentityKv<'a, K: Kv> {
    pub(crate) me: &'a DeviceIdentity,
    pub(crate) kv: &'a K,
}

/// Сессии.
pub struct SessionKv<'a, K: Kv>(pub(crate) &'a K);
/// Одноразовые ключи.
pub struct PreKeyKv<'a, K: Kv>(pub(crate) &'a K);
/// Подписанные ключи.
pub struct SignedPreKeyKv<'a, K: Kv>(pub(crate) &'a K);
/// Ключи Kyber: одноразовые — после использования удаляются.
pub struct KyberPreKeyKv<'a, K: Kv>(pub(crate) &'a K);

impl<K: Kv> IdentityKv<'_, K> {
    fn stored(
        &self,
        address: &ProtocolAddress,
    ) -> Result<Option<IdentityKey>, SignalProtocolError> {
        self.kv
            .get(Table::Identity, &address_id(address))
            .map_err(kv_err("get_identity"))?
            .map(|b| IdentityKey::decode(&b))
            .transpose()
    }
}

#[async_trait(?Send)]
impl<K: Kv> IdentityKeyStore for IdentityKv<'_, K> {
    async fn get_identity_key_pair(&self) -> Result<IdentityKeyPair, SignalProtocolError> {
        Ok(*self.me.pair())
    }

    async fn get_local_registration_id(&self) -> Result<u32, SignalProtocolError> {
        Ok(self.me.registration_id())
    }

    async fn save_identity(
        &mut self,
        address: &ProtocolAddress,
        identity: &IdentityKey,
    ) -> Result<IdentityChange, SignalProtocolError> {
        let old = self.stored(address)?;
        if old.as_ref() == Some(identity) {
            return Ok(IdentityChange::NewOrUnchanged);
        }
        self.kv
            .put(Table::Identity, &address_id(address), &identity.serialize())
            .map_err(kv_err("save_identity"))?;
        Ok(IdentityChange::from_changed(old.is_some()))
    }

    /// Доверяем ключу, который уже записан для адреса; адрес без записи — первое знакомство,
    /// его допускает только сценарий добавления контакта (ADR-62), адрес = ключ личности.
    async fn is_trusted_identity(
        &self,
        address: &ProtocolAddress,
        identity: &IdentityKey,
        _direction: Direction,
    ) -> Result<bool, SignalProtocolError> {
        Ok(self.stored(address)?.is_none_or(|k| k == *identity))
    }

    async fn get_identity(
        &self,
        address: &ProtocolAddress,
    ) -> Result<Option<IdentityKey>, SignalProtocolError> {
        self.stored(address)
    }
}

#[async_trait(?Send)]
impl<K: Kv> SessionStore for SessionKv<'_, K> {
    async fn load_session(
        &self,
        address: &ProtocolAddress,
    ) -> Result<Option<SessionRecord>, SignalProtocolError> {
        self.0
            .get(Table::Session, &address_id(address))
            .map_err(kv_err("load_session"))?
            .map(|b| SessionRecord::deserialize(Secret::new(b).expose()))
            .transpose()
    }

    async fn store_session(
        &mut self,
        address: &ProtocolAddress,
        record: &SessionRecord,
    ) -> Result<(), SignalProtocolError> {
        self.0
            .put(
                Table::Session,
                &address_id(address),
                Secret::new(record.serialize()?).expose(),
            )
            .map_err(kv_err("store_session"))
    }
}

#[async_trait(?Send)]
impl<K: Kv> PreKeyStore for PreKeyKv<'_, K> {
    async fn get_pre_key(&self, id: PreKeyId) -> Result<PreKeyRecord, SignalProtocolError> {
        let b = self
            .0
            .get(Table::PreKey, &u32::from(id).to_be_bytes())
            .map_err(kv_err("get_pre_key"))?
            .ok_or(SignalProtocolError::InvalidPreKeyId)?;
        PreKeyRecord::deserialize(Secret::new(b).expose())
    }

    async fn save_pre_key(
        &mut self,
        id: PreKeyId,
        record: &PreKeyRecord,
    ) -> Result<(), SignalProtocolError> {
        self.0
            .put(
                Table::PreKey,
                &u32::from(id).to_be_bytes(),
                Secret::new(record.serialize()?).expose(),
            )
            .map_err(kv_err("save_pre_key"))
    }

    async fn remove_pre_key(&mut self, id: PreKeyId) -> Result<(), SignalProtocolError> {
        self.0
            .delete(Table::PreKey, &u32::from(id).to_be_bytes())
            .map_err(kv_err("remove_pre_key"))
    }
}

#[async_trait(?Send)]
impl<K: Kv> SignedPreKeyStore for SignedPreKeyKv<'_, K> {
    async fn get_signed_pre_key(
        &self,
        id: SignedPreKeyId,
    ) -> Result<SignedPreKeyRecord, SignalProtocolError> {
        let b = self
            .0
            .get(Table::SignedPreKey, &u32::from(id).to_be_bytes())
            .map_err(kv_err("get_signed_pre_key"))?
            .ok_or(SignalProtocolError::InvalidSignedPreKeyId)?;
        SignedPreKeyRecord::deserialize(Secret::new(b).expose())
    }

    async fn save_signed_pre_key(
        &mut self,
        id: SignedPreKeyId,
        record: &SignedPreKeyRecord,
    ) -> Result<(), SignalProtocolError> {
        self.0
            .put(
                Table::SignedPreKey,
                &u32::from(id).to_be_bytes(),
                Secret::new(record.serialize()?).expose(),
            )
            .map_err(kv_err("save_signed_pre_key"))
    }
}

#[async_trait(?Send)]
impl<K: Kv> KyberPreKeyStore for KyberPreKeyKv<'_, K> {
    async fn get_kyber_pre_key(
        &self,
        id: KyberPreKeyId,
    ) -> Result<KyberPreKeyRecord, SignalProtocolError> {
        let b = self
            .0
            .get(Table::KyberPreKey, &u32::from(id).to_be_bytes())
            .map_err(kv_err("get_kyber_pre_key"))?
            .ok_or(SignalProtocolError::InvalidKyberPreKeyId)?;
        KyberPreKeyRecord::deserialize(Secret::new(b).expose())
    }

    async fn save_kyber_pre_key(
        &mut self,
        id: KyberPreKeyId,
        record: &KyberPreKeyRecord,
    ) -> Result<(), SignalProtocolError> {
        self.0
            .put(
                Table::KyberPreKey,
                &u32::from(id).to_be_bytes(),
                Secret::new(record.serialize()?).expose(),
            )
            .map_err(kv_err("save_kyber_pre_key"))
    }

    /// Наш ключ Kyber одноразовый (свой на каждое добавление контакта): после первого
    /// использования удаляем — повтор того же сообщения начала сессии получит отказ.
    async fn mark_kyber_pre_key_used(
        &mut self,
        id: KyberPreKeyId,
        _ec_prekey_id: SignedPreKeyId,
        _base_key: &PublicKey,
    ) -> Result<(), SignalProtocolError> {
        crate::contact::mark_card_consumed(self.0, u32::from(id))
            .map_err(|_| kv_err("card_consumed")(KvError))?;
        self.0
            .delete(Table::KyberPreKey, &u32::from(id).to_be_bytes())
            .map_err(kv_err("mark_kyber_pre_key_used"))
    }
}

/// Все хранилища устройства над одним [`Kv`].
pub struct Stores<'a, K: Kv> {
    me: &'a DeviceIdentity,
    kv: &'a K,
}

impl<'a, K: Kv> Stores<'a, K> {
    /// Хранилища для ключа личности `me` над `kv`.
    #[must_use]
    pub const fn new(me: &'a DeviceIdentity, kv: &'a K) -> Self {
        Self { me, kv }
    }

    /// Свой ключ личности.
    #[must_use]
    pub const fn me(&self) -> &'a DeviceIdentity {
        self.me
    }

    pub(crate) const fn kv(&self) -> &'a K {
        self.kv
    }

    pub(crate) const fn identity(&self) -> IdentityKv<'a, K> {
        IdentityKv {
            me: self.me,
            kv: self.kv,
        }
    }

    pub(crate) const fn sessions(&self) -> SessionKv<'a, K> {
        SessionKv(self.kv)
    }

    pub(crate) const fn pre_keys(&self) -> PreKeyKv<'a, K> {
        PreKeyKv(self.kv)
    }

    pub(crate) const fn signed_pre_keys(&self) -> SignedPreKeyKv<'a, K> {
        SignedPreKeyKv(self.kv)
    }

    pub(crate) const fn kyber_pre_keys(&self) -> KyberPreKeyKv<'a, K> {
        KyberPreKeyKv(self.kv)
    }
}

/// [`Kv`] в памяти — для тестов и самопроверки.
#[derive(Default)]
pub struct MemKv {
    rows: std::sync::Mutex<MemRows>,
}

type MemRows = std::collections::HashMap<(Table, Vec<u8>), Vec<u8>>;

impl MemKv {
    /// Число записей в таблице.
    #[must_use]
    pub fn count(&self, table: Table) -> usize {
        self.rows
            .lock()
            .map_or(0, |r| r.keys().filter(|(t, _)| *t == table).count())
    }
}

impl Kv for MemKv {
    fn get(&self, table: Table, id: &[u8]) -> Result<Option<Vec<u8>>, KvError> {
        let rows = self.rows.lock().map_err(|_| KvError)?;
        Ok(rows.get(&(table, id.to_vec())).cloned())
    }

    fn put(&self, table: Table, id: &[u8], value: &[u8]) -> Result<(), KvError> {
        let mut rows = self.rows.lock().map_err(|_| KvError)?;
        rows.insert((table, id.to_vec()), value.to_vec());
        Ok(())
    }

    fn delete(&self, table: Table, id: &[u8]) -> Result<(), KvError> {
        let mut rows = self.rows.lock().map_err(|_| KvError)?;
        rows.remove(&(table, id.to_vec()));
        Ok(())
    }
}
