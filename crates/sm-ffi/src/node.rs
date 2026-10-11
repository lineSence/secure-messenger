//! Запись узла для Kotlin (ADR-81): принять текст `SMN1:…` от владельца или контакта —
//! проверить подпись корневым ключом из APK и срок; разбор и проверка — в ядре.

use sm_signal::node::{self, NodeError, ROOT_KEYS};

/// Принятая запись узла.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NodeInfo {
    /// Когда выпущена, секунды Unix: из двух верных записей берут более новую.
    pub issued: u64,
    /// До какого момента действует, секунды Unix.
    pub expires: u64,
    /// Порт HTTPS.
    pub port: u16,
    /// Секретный путь API.
    pub path: String,
    /// Домены узла (ADR-86).
    pub domains: Vec<String>,
    /// SHA-256 ключей (SPKI) сертификата, по 32 байта (ADR-72).
    pub pins: Vec<Vec<u8>>,
}

/// Почему запись не принята.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum NodeFfiError {
    /// Не запись узла.
    BadFormat,
    /// Подпись не от владельца (или корневой ключ ещё не задан).
    BadSignature,
    /// Срок действия истёк.
    Expired,
}

impl From<NodeError> for NodeFfiError {
    fn from(e: NodeError) -> Self {
        match e {
            NodeError::BadFormat => Self::BadFormat,
            NodeError::BadSignature => Self::BadSignature,
            NodeError::Expired => Self::Expired,
        }
    }
}

impl std::fmt::Display for NodeFfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for NodeFfiError {}

/// Принимает текст записи узла на момент `now_secs` (секунды Unix).
///
/// # Errors
/// [`NodeFfiError`].
#[uniffi::export]
pub fn node_record_open(text: &str, now_secs: u64) -> Result<NodeInfo, NodeFfiError> {
    let r = node::open(text, ROOT_KEYS, now_secs)?;
    Ok(NodeInfo {
        issued: r.issued,
        expires: r.expires,
        port: r.port,
        path: r.path,
        domains: r.domains,
        pins: r.pins.iter().map(|p| p.to_vec()).collect(),
    })
}

/// Принятый пропуск на создание очередей (ADR-136).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct QueuePassInfo {
    /// До какого момента действует, секунды Unix.
    pub expires: u64,
    /// Новых очередей в час.
    pub per_hour: u16,
    /// Пропуск как есть — данные запроса `NEW`.
    pub bytes: Vec<u8>,
}

/// Принимает текст пропуска `SMP1:…` на момент `now_secs`: подпись владельца и срок.
///
/// # Errors
/// [`NodeFfiError`].
#[uniffi::export]
pub fn queue_pass_open(text: &str, now_secs: u64) -> Result<QueuePassInfo, NodeFfiError> {
    let (p, bytes) = node::open_pass(text, ROOT_KEYS, now_secs)?;
    Ok(QueuePassInfo {
        expires: p.expires,
        per_hour: p.per_hour,
        bytes,
    })
}

/// Задан ли в APK корневой ключ владельца; нет — запись узла принять нельзя.
#[uniffi::export]
#[must_use]
pub fn node_root_ready() -> bool {
    !ROOT_KEYS.is_empty()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn without_root_key_nothing_is_accepted() {
        assert!(!node_root_ready());
        assert_eq!(node_record_open("SMN1:00", 0), Err(NodeFfiError::BadFormat));
        assert_eq!(node_record_open("мусор", 0), Err(NodeFfiError::BadFormat));
    }
}
