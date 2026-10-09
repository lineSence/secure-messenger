//! Хранилище ключей (шаг 1 плана MVP).
//!
//! Ядро не шифрует ключи само (ADR-1): секрет «заворачивает» платформа через [`KeyWrapper`] —
//! на Android ключом из Android Keystore, на Windows — DPAPI. Метка записи передаётся
//! обёртке как связанные данные, поэтому подменить запись одного ключа записью другого нельзя.
//! Записи хранятся в [`KeyStorage`] в формате:
//!
//! ```text
//! [версия: u8 = 1][метка: u16 BE длина + байты][обёрнутый секрет: u16 BE длина + байты]
//! ```
//!
//! Секреты в памяти — [`Secret`]: обнуляется при удалении, не печатается в логах.

use core::fmt;
use std::collections::BTreeMap;

use zeroize::Zeroizing;

use crate::wire::{self, Reader, WireError};

/// Версия формата записи.
pub const RECORD_VERSION: u8 = 1;
/// Максимальная длина метки.
pub const MAX_LABEL: usize = 64;
/// Максимальная длина обёрнутого секрета.
pub const MAX_WRAPPED: usize = 8 * 1024;

/// Секрет в памяти. Обнуляется при удалении; `Debug` не показывает содержимое.
pub struct Secret(Zeroizing<Vec<u8>>);

impl Secret {
    /// Забирает байты секрета.
    #[must_use]
    pub fn new(bytes: Vec<u8>) -> Self {
        Self(Zeroizing::new(bytes))
    }

    /// Доступ к байтам.
    #[must_use]
    pub fn expose(&self) -> &[u8] {
        &self.0
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Secret(<{} байт скрыто>)", self.0.len())
    }
}

/// Метка ключа: `[a-z0-9._-]`, 1–64 символа, первый — `[a-z0-9]`, без `..` и без `.` в конце,
/// не зарезервированное имя Windows (`con`, `prn`, `aux`, `nul`, `com0`–`com9`, `lpt0`–`lpt9`,
/// в том числе с расширением: `nul.x`). Например, `identity`, `device.1.signed-prekey`.
/// Метка может стать именем файла, поэтому правила — безопасные для всех ОС.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct KeyLabel(String);

impl KeyLabel {
    /// Проверяет метку.
    ///
    /// # Errors
    /// [`KeyStoreError::BadLabel`].
    pub fn new(s: &str) -> Result<Self, KeyStoreError> {
        let ok = s.len() <= MAX_LABEL
            && s.bytes()
                .next()
                .is_some_and(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9'))
            && !s.contains("..")
            && !s.ends_with('.')
            && !is_windows_reserved(s)
            && s.bytes()
                .all(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'.' | b'_' | b'-'));
        if ok {
            Ok(Self(s.to_owned()))
        } else {
            Err(KeyStoreError::BadLabel)
        }
    }

    /// Строка метки.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Зарезервированное имя устройства Windows: часть до первой точки — `con`, `prn`, `aux`, `nul`,
/// `com0`–`com9` или `lpt0`–`lpt9`. Такие файлы на Windows открыть нельзя (`nul.x` тоже).
fn is_windows_reserved(s: &str) -> bool {
    let stem = s.split('.').next().unwrap_or(s);
    matches!(stem, "con" | "prn" | "aux" | "nul")
        || matches!(
            stem.as_bytes(),
            [b'c', b'o', b'm', b'0'..=b'9'] | [b'l', b'p', b't', b'0'..=b'9']
        )
}

/// Ошибка хранилища ключей.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyStoreError {
    /// Недопустимая метка.
    BadLabel,
    /// Ключа с такой меткой нет.
    NotFound,
    /// Запись повреждена или не той версии.
    Corrupt(WireError),
    /// Метка внутри записи не совпадает с запрошенной.
    LabelMismatch,
    /// Обёртка платформы отказала (ключ удалён, данные подменены и т. п.).
    Wrap,
    /// Ошибка постоянного хранилища.
    Storage,
}

impl From<WireError> for KeyStoreError {
    fn from(e: WireError) -> Self {
        Self::Corrupt(e)
    }
}

/// Обёртка секретов, которую даёт платформа. Обязана шифровать с проверкой целостности
/// и привязывать результат к `label` (связанные данные AEAD).
pub trait KeyWrapper {
    /// Заворачивает секрет.
    ///
    /// # Errors
    /// [`KeyStoreError::Wrap`].
    fn wrap(&self, label: &KeyLabel, secret: &[u8]) -> Result<Vec<u8>, KeyStoreError>;
    /// Разворачивает секрет; должна отказать, если данные или метка подменены.
    ///
    /// # Errors
    /// [`KeyStoreError::Wrap`].
    fn unwrap(&self, label: &KeyLabel, wrapped: &[u8]) -> Result<Secret, KeyStoreError>;
}

/// Постоянное хранилище записей (файл, база данных). Записи непрозрачны.
pub trait KeyStorage {
    /// Читает запись.
    ///
    /// # Errors
    /// [`KeyStoreError::Storage`].
    fn load(&self, label: &KeyLabel) -> Result<Option<Vec<u8>>, KeyStoreError>;
    /// Сохраняет запись атомарно (старая запись не теряется при сбое).
    ///
    /// # Errors
    /// [`KeyStoreError::Storage`].
    fn store(&mut self, label: &KeyLabel, record: &[u8]) -> Result<(), KeyStoreError>;
    /// Удаляет запись. Отсутствие записи — не ошибка.
    ///
    /// # Errors
    /// [`KeyStoreError::Storage`].
    fn delete(&mut self, label: &KeyLabel) -> Result<(), KeyStoreError>;
}

/// Хранилище в памяти — для тестов и как образец реализации.
#[derive(Debug, Default)]
pub struct MemoryStorage(BTreeMap<KeyLabel, Vec<u8>>);

impl KeyStorage for MemoryStorage {
    fn load(&self, label: &KeyLabel) -> Result<Option<Vec<u8>>, KeyStoreError> {
        Ok(self.0.get(label).cloned())
    }
    fn store(&mut self, label: &KeyLabel, record: &[u8]) -> Result<(), KeyStoreError> {
        self.0.insert(label.clone(), record.to_vec());
        Ok(())
    }
    fn delete(&mut self, label: &KeyLabel) -> Result<(), KeyStoreError> {
        self.0.remove(label);
        Ok(())
    }
}

/// Кодирует запись.
///
/// # Errors
/// [`KeyStoreError::Corrupt`], если обёрнутый секрет длиннее [`MAX_WRAPPED`].
pub fn encode_record(label: &KeyLabel, wrapped: &[u8]) -> Result<Vec<u8>, KeyStoreError> {
    let mut out = Vec::with_capacity(wrapped.len().saturating_add(MAX_LABEL).saturating_add(5));
    out.push(RECORD_VERSION);
    wire::put_bytes_u16(&mut out, label.as_str().as_bytes(), MAX_LABEL)
        .ok_or(KeyStoreError::BadLabel)?;
    wire::put_bytes_u16(&mut out, wrapped, MAX_WRAPPED).ok_or(KeyStoreError::Corrupt(
        WireError::TooLong {
            len: wrapped.len(),
            max: MAX_WRAPPED,
        },
    ))?;
    Ok(out)
}

/// Разбирает запись: метка и обёрнутый секрет (заимствуются из буфера).
///
/// # Errors
/// [`KeyStoreError::Corrupt`], [`KeyStoreError::BadLabel`].
pub fn decode_record(buf: &[u8]) -> Result<(KeyLabel, &[u8]), KeyStoreError> {
    let mut r = Reader::new(buf);
    if r.u8()? != RECORD_VERSION {
        return Err(KeyStoreError::Corrupt(WireError::BadValue));
    }
    let label = r.bytes_u16(MAX_LABEL)?;
    let label = KeyLabel::new(core::str::from_utf8(label).map_err(|_| KeyStoreError::BadLabel)?)?;
    let wrapped = r.bytes_u16(MAX_WRAPPED)?;
    r.finish()?;
    Ok((label, wrapped))
}

/// Хранилище ключей: обёртка платформы + постоянное хранилище.
#[derive(Debug)]
pub struct KeyStore<W, S> {
    wrapper: W,
    storage: S,
}

impl<W: KeyWrapper, S: KeyStorage> KeyStore<W, S> {
    /// Создаёт хранилище.
    pub const fn new(wrapper: W, storage: S) -> Self {
        Self { wrapper, storage }
    }

    /// Сохраняет секрет (заменяет прежний с той же меткой).
    ///
    /// # Errors
    /// [`KeyStoreError`].
    pub fn put(&mut self, label: &KeyLabel, secret: &Secret) -> Result<(), KeyStoreError> {
        let wrapped = self.wrapper.wrap(label, secret.expose())?;
        let record = encode_record(label, &wrapped)?;
        self.storage.store(label, &record)
    }

    /// Читает секрет.
    ///
    /// # Errors
    /// [`KeyStoreError::NotFound`], [`KeyStoreError::LabelMismatch`], [`KeyStoreError::Wrap`] и др.
    pub fn get(&self, label: &KeyLabel) -> Result<Secret, KeyStoreError> {
        let record = self.storage.load(label)?.ok_or(KeyStoreError::NotFound)?;
        let (stored, wrapped) = decode_record(&record)?;
        if &stored != label {
            return Err(KeyStoreError::LabelMismatch);
        }
        self.wrapper.unwrap(label, wrapped)
    }

    /// Удаляет секрет.
    ///
    /// # Errors
    /// [`KeyStoreError::Storage`].
    pub fn delete(&mut self, label: &KeyLabel) -> Result<(), KeyStoreError> {
        self.storage.delete(label)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Тестовая обёртка: НЕ шифрование. Проверяет только привязку к метке и целостность.
    struct TestWrapper;

    impl KeyWrapper for TestWrapper {
        fn wrap(&self, label: &KeyLabel, secret: &[u8]) -> Result<Vec<u8>, KeyStoreError> {
            let mut v = label.as_str().as_bytes().to_vec();
            v.push(b'|');
            v.extend(secret.iter().map(|b| b ^ 0x5A));
            Ok(v)
        }
        fn unwrap(&self, label: &KeyLabel, wrapped: &[u8]) -> Result<Secret, KeyStoreError> {
            let prefix = format!("{}|", label.as_str());
            let body = wrapped
                .strip_prefix(prefix.as_bytes())
                .ok_or(KeyStoreError::Wrap)?;
            Ok(Secret::new(body.iter().map(|b| b ^ 0x5A).collect()))
        }
    }

    fn l(s: &str) -> KeyLabel {
        KeyLabel::new(s).unwrap()
    }

    #[test]
    fn put_get_delete() {
        let mut ks = KeyStore::new(TestWrapper, MemoryStorage::default());
        ks.put(&l("identity"), &Secret::new(vec![1, 2, 3])).unwrap();
        assert_eq!(ks.get(&l("identity")).unwrap().expose(), [1, 2, 3]);
        assert_eq!(ks.get(&l("other")).unwrap_err(), KeyStoreError::NotFound);
        ks.delete(&l("identity")).unwrap();
        assert_eq!(ks.get(&l("identity")).unwrap_err(), KeyStoreError::NotFound);
    }

    #[test]
    fn swapped_record_is_rejected() {
        let mut ks = KeyStore::new(TestWrapper, MemoryStorage::default());
        ks.put(&l("a"), &Secret::new(vec![1])).unwrap();
        let rec_a = ks.storage.load(&l("a")).unwrap().unwrap();
        ks.storage.store(&l("b"), &rec_a).unwrap();
        assert_eq!(ks.get(&l("b")).unwrap_err(), KeyStoreError::LabelMismatch);
    }

    #[test]
    fn labels_are_validated() {
        for bad in [
            "",
            "Identity",
            "a b",
            "ключ",
            "../x",
            ".",
            "..",
            ".a",
            "-a",
            "_a",
            "a..b",
            "a.",
            "device.1.",
            "con",
            "nul",
            "nul.x",
            "aux.key.bak",
            "prn",
            "com0",
            "com9.x",
            "lpt1",
            &"a".repeat(MAX_LABEL + 1),
        ] {
            assert_eq!(KeyLabel::new(bad), Err(KeyStoreError::BadLabel), "{bad}");
        }
        assert!(KeyLabel::new("device.1.signed-prekey_2").is_ok());
        assert!(KeyLabel::new("0").is_ok());
        // Похожие, но не зарезервированные имена разрешены.
        for good in [
            "console",
            "com",
            "com10",
            "lpt",
            "x.nul",
            "nul-x",
            "auxiliary",
            "a.b",
        ] {
            assert!(KeyLabel::new(good).is_ok(), "{good}");
        }
    }

    #[test]
    fn record_roundtrip_and_corruption() {
        let rec = encode_record(&l("k"), b"wrapped").unwrap();
        assert_eq!(decode_record(&rec), Ok((l("k"), &b"wrapped"[..])));
        for n in 0..rec.len() {
            assert!(decode_record(&rec[..n]).is_err());
        }
        let mut v = rec.clone();
        v[0] = 2;
        assert!(decode_record(&v).is_err());
        let mut t = rec;
        t.push(0);
        assert_eq!(
            decode_record(&t),
            Err(KeyStoreError::Corrupt(WireError::TrailingBytes(1)))
        );
        assert!(encode_record(&l("k"), &vec![0; MAX_WRAPPED + 1]).is_err());
    }

    #[test]
    fn debug_hides_secret() {
        let s = format!("{:?}", Secret::new(vec![0x42; 4]));
        assert!(!s.contains("42") && !s.contains("66"), "{s}");
    }
}
