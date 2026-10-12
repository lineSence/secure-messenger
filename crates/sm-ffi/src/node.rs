//! Запись узла для Kotlin (ADR-81): принять текст `SMN1:…` от владельца или контакта —
//! проверить подпись корневым ключом из APK и срок; разбор и проверка — в ядре.

use sm_core::pass::GuestPass;
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
    /// Домены узла (ADR-86) с пинами каждого (ADR-135).
    pub domains: Vec<NodeDomainInfo>,
    /// Отозванные пропуска на `NEW`, по 16 байт (ADR-136).
    pub revoked: Vec<Vec<u8>>,
}

/// Домен узла и пины его сертификата.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NodeDomainInfo {
    /// Имя DNS.
    pub name: String,
    /// SHA-256 ключей (SPKI) сертификата, по 32 байта: основной и запасной (ADR-72, ADR-135).
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
        domains: r
            .domains
            .into_iter()
            .map(|d| NodeDomainInfo {
                name: d.name,
                pins: d.pins.iter().map(|p| p.to_vec()).collect(),
            })
            .collect(),
        revoked: r.revoked.iter().map(|id| id.to_vec()).collect(),
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

/// Гостевой пропуск (ADR-146), выданный узлом по билету из ссылки.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GuestPassInfo {
    /// ID пропуска (16 байт).
    pub id: Vec<u8>,
    /// ID пропуска владельца, от которого пошла цепочка (16 байт): отозван в записи узла —
    /// отозваны и гости.
    pub parent: Vec<u8>,
    /// До какого момента действует, секунды Unix.
    pub expires: u64,
    /// Новых очередей в час.
    pub per_hour: u16,
}

/// Разбирает гостевой пропуск, полученный от своего узла по TLS с пином, на момент `now_secs`.
/// Подпись ключом узла приложение проверить не может (ключа у него нет) — её проверяет узел при
/// `NEW`; здесь только формат и срок.
///
/// # Errors
/// [`NodeFfiError::BadFormat`] — не гостевой пропуск; [`NodeFfiError::Expired`] — срок вышел.
#[uniffi::export]
pub fn guest_pass_open(bytes: &[u8], now_secs: u64) -> Result<GuestPassInfo, NodeFfiError> {
    let (g, _, _) = GuestPass::parse(bytes).map_err(|_| NodeFfiError::BadFormat)?;
    if now_secs >= g.expires {
        return Err(NodeFfiError::Expired);
    }
    Ok(GuestPassInfo {
        id: g.id.to_vec(),
        parent: g.parent.to_vec(),
        expires: g.expires,
        per_hour: g.per_hour,
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
    fn guest_pass_is_parsed_with_expiry() {
        let g = GuestPass {
            id: [1; 16],
            expires: 100,
            per_hour: 5,
            parent: [2; 16],
        };
        let bytes = g.with_signature(&[0; 64]);
        let info = guest_pass_open(&bytes, 99).unwrap();
        assert_eq!(
            (info.id, info.parent, info.expires, info.per_hour),
            (vec![1; 16], vec![2; 16], 100, 5)
        );
        assert_eq!(guest_pass_open(&bytes, 100), Err(NodeFfiError::Expired));
        assert_eq!(
            guest_pass_open(&bytes[1..], 0),
            Err(NodeFfiError::BadFormat)
        );
        // Пропуск владельца — не гостевой.
        assert_eq!(guest_pass_open(&[0; 91], 0), Err(NodeFfiError::BadFormat));
    }

    #[test]
    fn owner_root_key_is_ready_and_garbage_is_rejected() {
        assert!(node_root_ready());
        assert_eq!(node_record_open("SMN1:00", 0), Err(NodeFfiError::BadFormat));
        assert_eq!(node_record_open("мусор", 0), Err(NodeFfiError::BadFormat));
    }
}
