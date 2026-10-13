//! Ключи устройства для Kotlin (пункт 4 плана `0.0.1-alpha`, ADR-56).
//!
//! Платформа даёт две вещи через обратные вызовы `UniFFI`:
//! - [`PlatformKeyWrapper`] — шифрование секрета ключом Android Keystore `sm.keys`
//!   (AES-256-GCM, связанные данные — метка);
//! - [`PlatformKeyStorage`] — файлы записей.
//!
//! Закрытый ключ личности в Kotlin не выдаётся: [`DeviceIdentity`] отдаёт только открытый ключ и
//! номер регистрации. Секрет проходит через Kotlin только на время шифрования Keystore.
//! Ключ личности никогда не перезаписывается молча: [`DeviceIdentity::create`] при уже
//! сохранённой записи отвечает [`KeyFfiError::Exists`].

use std::sync::Arc;

use sm_core::keystore::{KeyLabel, KeyStorage, KeyStore, KeyStoreError, KeyWrapper, Secret};

/// Метка записи ключа личности.
pub const IDENTITY_LABEL: &str = "identity";

/// Ошибка ключей устройства.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum KeyFfiError {
    /// Ключа ещё нет — нужен первый запуск.
    NotFound,
    /// Ключ уже есть — новый не создаётся.
    Exists,
    /// Keystore не завернул или не развернул секрет (ключ удалён, данные подменены).
    Wrap,
    /// Не удалось прочитать или записать файл.
    Storage,
    /// Запись или секрет повреждены.
    Corrupt,
    /// Обратный вызов Kotlin бросил неожиданное исключение.
    Callback,
}

impl std::fmt::Display for KeyFfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for KeyFfiError {}

impl From<uniffi::UnexpectedUniFFICallbackError> for KeyFfiError {
    fn from(_: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Callback
    }
}

impl From<KeyStoreError> for KeyFfiError {
    fn from(e: KeyStoreError) -> Self {
        match e {
            KeyStoreError::NotFound => Self::NotFound,
            KeyStoreError::Wrap => Self::Wrap,
            KeyStoreError::Storage => Self::Storage,
            KeyStoreError::BadLabel | KeyStoreError::Corrupt(_) | KeyStoreError::LabelMismatch => {
                Self::Corrupt
            }
        }
    }
}

/// Шифрование секретов ключом платформы. Обязана проверять целостность и привязывать результат
/// к `label` (связанные данные AEAD); при любой ошибке — исключение, не пустой результат.
#[uniffi::export(with_foreign)]
pub trait PlatformKeyWrapper: Send + Sync {
    /// Заворачивает секрет. The callback owns `secret` and must wipe it on every exit
    /// (including exceptions). Rust cannot reclaim a Vec moved into the generated
    /// UniFFI callback; generated/direct-buffer copies are outside this guarantee.
    ///
    /// # Errors
    /// [`KeyFfiError::Wrap`].
    fn wrap(&self, label: String, secret: Vec<u8>) -> Result<Vec<u8>, KeyFfiError>;

    /// Разворачивает секрет; отказ, если данные или метка подменены.
    /// The returned Vec is immediately owned by Secret in Rust. The callback must
    /// wipe its own extra plaintext temporaries; the transferred result must remain
    /// valid until UniFFI lowers it. Generated intermediate copies are not controlled.
    ///
    /// # Errors
    /// [`KeyFfiError::Wrap`].
    fn unwrap(&self, label: String, wrapped: Vec<u8>) -> Result<Vec<u8>, KeyFfiError>;
}

/// Постоянное хранилище записей (файлы). Запись — атомарно.
#[uniffi::export(with_foreign)]
pub trait PlatformKeyStorage: Send + Sync {
    /// Читает запись; `None` — записи нет.
    ///
    /// # Errors
    /// [`KeyFfiError::Storage`].
    fn load(&self, label: String) -> Result<Option<Vec<u8>>, KeyFfiError>;

    /// Сохраняет запись атомарно.
    ///
    /// # Errors
    /// [`KeyFfiError::Storage`].
    fn store(&self, label: String, record: Vec<u8>) -> Result<(), KeyFfiError>;
}

/// Переходник: обёртка платформы → `sm_core::keystore::KeyWrapper`.
struct Wrapper(Arc<dyn PlatformKeyWrapper>);

impl KeyWrapper for Wrapper {
    fn wrap(&self, label: &KeyLabel, secret: &[u8]) -> Result<Vec<u8>, KeyStoreError> {
        self.0
            .wrap(label.as_str().to_owned(), secret.to_vec())
            .map_err(|_| KeyStoreError::Wrap)
    }

    fn unwrap(&self, label: &KeyLabel, wrapped: &[u8]) -> Result<Secret, KeyStoreError> {
        self.0
            .unwrap(label.as_str().to_owned(), wrapped.to_vec())
            .map(Secret::new)
            .map_err(|_| KeyStoreError::Wrap)
    }
}

/// Переходник: хранилище платформы → `sm_core::keystore::KeyStorage`.
struct Storage(Arc<dyn PlatformKeyStorage>);

impl KeyStorage for Storage {
    fn load(&self, label: &KeyLabel) -> Result<Option<Vec<u8>>, KeyStoreError> {
        self.0
            .load(label.as_str().to_owned())
            .map_err(|_| KeyStoreError::Storage)
    }

    fn store(&mut self, label: &KeyLabel, record: &[u8]) -> Result<(), KeyStoreError> {
        self.0
            .store(label.as_str().to_owned(), record.to_vec())
            .map_err(|_| KeyStoreError::Storage)
    }

    fn delete(&mut self, _label: &KeyLabel) -> Result<(), KeyStoreError> {
        // Ключ личности не удаляется (сброс — вместе с данными приложения).
        Err(KeyStoreError::Storage)
    }
}

fn identity_label() -> Result<KeyLabel, KeyFfiError> {
    KeyLabel::new(IDENTITY_LABEL).map_err(KeyFfiError::from)
}

/// Ключ личности устройства. Закрытый ключ остаётся в ядре.
#[derive(Debug, uniffi::Object)]
pub struct DeviceIdentity {
    inner: sm_signal::DeviceIdentity,
}

#[uniffi::export]
impl DeviceIdentity {
    /// Загружает сохранённый ключ.
    ///
    /// # Errors
    /// [`KeyFfiError::NotFound`] — первый запуск; остальные — отказ (ключ не используется).
    #[uniffi::constructor]
    pub fn load(
        wrapper: Arc<dyn PlatformKeyWrapper>,
        storage: Arc<dyn PlatformKeyStorage>,
    ) -> Result<Arc<Self>, KeyFfiError> {
        let store = KeyStore::new(Wrapper(wrapper), Storage(storage));
        let secret = store.get(&identity_label()?)?;
        let inner = sm_signal::DeviceIdentity::from_secret(secret.expose())
            .map_err(|_| KeyFfiError::Corrupt)?;
        Ok(Arc::new(Self { inner }))
    }

    /// Создаёт и сохраняет новый ключ — только если записи ещё нет.
    ///
    /// # Errors
    /// [`KeyFfiError::Exists`] — ключ уже есть (или запись не читается — тоже не перезаписываем);
    /// [`KeyFfiError::Wrap`], [`KeyFfiError::Storage`].
    #[uniffi::constructor]
    pub fn create(
        wrapper: Arc<dyn PlatformKeyWrapper>,
        storage: Arc<dyn PlatformKeyStorage>,
    ) -> Result<Arc<Self>, KeyFfiError> {
        let label = identity_label()?;
        if storage.load(IDENTITY_LABEL.to_owned())?.is_some() {
            return Err(KeyFfiError::Exists);
        }
        let inner = sm_signal::DeviceIdentity::generate();
        let mut store = KeyStore::new(Wrapper(wrapper), Storage(storage));
        store.put(&label, &Secret::new(inner.to_secret()))?;
        // Проверка: записанное читается и даёт тот же ключ.
        let back = sm_signal::DeviceIdentity::from_secret(store.get(&label)?.expose())
            .map_err(|_| KeyFfiError::Corrupt)?;
        if back.public_key() != inner.public_key() {
            return Err(KeyFfiError::Corrupt);
        }
        Ok(Arc::new(Self { inner }))
    }

    /// Открытый ключ личности (33 байта).
    #[must_use]
    pub fn public_key(&self) -> Vec<u8> {
        self.inner.public_key()
    }

    /// Номер регистрации (14 бит).
    #[must_use]
    pub fn registration_id(&self) -> u32 {
        self.inner.registration_id()
    }
}

impl DeviceIdentity {
    /// Ключ для хранилищ Signal (`signal.rs`); наружу из ядра не выходит.
    pub(crate) const fn inner(&self) -> &sm_signal::DeviceIdentity {
        &self.inner
    }

    #[cfg(test)]
    pub(crate) fn generate_for_test() -> Arc<Self> {
        Arc::new(Self {
            inner: sm_signal::DeviceIdentity::generate(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;
    use std::sync::Mutex;

    /// Тестовая «обёртка»: метка + XOR (не шифрование — только проверка связки).
    struct TestWrapper {
        fail: bool,
    }

    impl PlatformKeyWrapper for TestWrapper {
        fn wrap(&self, label: String, secret: Vec<u8>) -> Result<Vec<u8>, KeyFfiError> {
            let secret = Secret::new(secret);
            if self.fail {
                return Err(KeyFfiError::Wrap);
            }
            let mut out = label.into_bytes();
            out.push(0);
            out.extend(secret.expose().iter().map(|b| b ^ 0x5A));
            Ok(out)
        }

        fn unwrap(&self, label: String, wrapped: Vec<u8>) -> Result<Vec<u8>, KeyFfiError> {
            let mut head = label.into_bytes();
            head.push(0);
            let body = wrapped.strip_prefix(&head[..]).ok_or(KeyFfiError::Wrap)?;
            Ok(body.iter().map(|b| b ^ 0x5A).collect())
        }
    }

    #[derive(Default)]
    struct TestStorage(Mutex<BTreeMap<String, Vec<u8>>>);

    impl PlatformKeyStorage for TestStorage {
        fn load(&self, label: String) -> Result<Option<Vec<u8>>, KeyFfiError> {
            Ok(self.0.lock().unwrap().get(&label).cloned())
        }

        fn store(&self, label: String, record: Vec<u8>) -> Result<(), KeyFfiError> {
            self.0.lock().unwrap().insert(label, record);
            Ok(())
        }
    }

    fn ok_wrapper() -> Arc<dyn PlatformKeyWrapper> {
        Arc::new(TestWrapper { fail: false })
    }

    #[test]
    fn create_then_load() {
        let storage = Arc::new(TestStorage::default());
        let a = DeviceIdentity::create(ok_wrapper(), storage.clone()).unwrap();
        let b = DeviceIdentity::load(ok_wrapper(), storage).unwrap();
        assert_eq!(a.public_key(), b.public_key());
        assert_eq!(a.registration_id(), b.registration_id());
    }

    #[test]
    fn load_without_key_is_not_found() {
        let storage = Arc::new(TestStorage::default());
        assert_eq!(
            DeviceIdentity::load(ok_wrapper(), storage).unwrap_err(),
            KeyFfiError::NotFound
        );
    }

    #[test]
    fn create_never_overwrites() {
        let storage = Arc::new(TestStorage::default());
        let a = DeviceIdentity::create(ok_wrapper(), storage.clone()).unwrap();
        assert_eq!(
            DeviceIdentity::create(ok_wrapper(), storage.clone()).unwrap_err(),
            KeyFfiError::Exists
        );
        // Даже повреждённая запись не перезаписывается.
        storage
            .0
            .lock()
            .unwrap()
            .insert(IDENTITY_LABEL.to_owned(), vec![9; 4]);
        assert_eq!(
            DeviceIdentity::create(ok_wrapper(), storage.clone()).unwrap_err(),
            KeyFfiError::Exists
        );
        assert_eq!(
            DeviceIdentity::load(ok_wrapper(), storage).unwrap_err(),
            KeyFfiError::Corrupt
        );
        drop(a);
    }

    #[test]
    fn wrapper_failure_stops_creation() {
        let storage = Arc::new(TestStorage::default());
        assert_eq!(
            DeviceIdentity::create(Arc::new(TestWrapper { fail: true }), storage.clone())
                .unwrap_err(),
            KeyFfiError::Wrap
        );
        assert!(storage.0.lock().unwrap().is_empty());
    }

    #[test]
    fn tampered_record_is_rejected() {
        let storage = Arc::new(TestStorage::default());
        DeviceIdentity::create(ok_wrapper(), storage.clone()).unwrap();
        {
            let mut m = storage.0.lock().unwrap();
            let rec = m.get_mut(IDENTITY_LABEL).unwrap();
            let last = rec.len() - 1;
            rec[last] ^= 1;
        }
        // Повреждённый секрет: libsignal или наш разбор отказывает, ключ не выдаётся.
        assert!(DeviceIdentity::load(ok_wrapper(), storage).is_err());
    }
}
