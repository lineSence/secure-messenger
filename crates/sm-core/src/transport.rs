//! Интерфейс транспортных плагинов (ADR-12).
//!
//! Транспорт перевозит непрозрачные зашифрованные блобы между устройством и почтовым ящиком
//! на узле (ADR-9) или напрямую (mesh, ADR-18). Он ничего не знает о сообщениях и ключах.
//! Ядро выбирает транспорт по «лестнице» (шаг 5) и использует [`TransportError`], чтобы решить,
//! переключаться ли на запасной путь. [`Transport::probe`] — для диагностики (ADR-13, ADR-19).
//!
//! Вызовы блокирующие: ядро запускает каждый транспорт в своём потоке.

use std::collections::{BTreeMap, VecDeque};
use std::time::Duration;

/// Идентификатор почтового ящика: 32 непрозрачных байта (не связан с личностью).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct MailboxId(pub [u8; 32]);

/// Класс транспорта.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportKind {
    /// Свой узел через маскирующий протокол (VLESS + XHTTP + Reality и запасные).
    Relay,
    /// Путь через общедоступные сервисы из белых списков.
    Whitelist,
    /// Радиосеть без интернета (`LoRa` / `Reticulum`).
    Mesh,
    /// Внутри процесса — тесты.
    Loopback,
}

/// Свойства транспорта.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TransportInfo {
    /// Короткий стабильный идентификатор плагина, например `vless-xhttp`.
    pub id: &'static str,
    /// Класс.
    pub kind: TransportKind,
    /// Максимальный размер одного блоба.
    pub max_blob: usize,
}

/// Причина отказа — от неё зависит, переключаться ли на запасной путь.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportError {
    /// Похоже на блокировку (сброс соединения, подмена, ТСПУ): переключаться.
    Blocked,
    /// Нет сети или узел недоступен: переключаться.
    Unreachable,
    /// Не уложились во время: повторить или переключиться.
    Timeout,
    /// Блоб больше [`TransportInfo::max_blob`]: выбрать другой транспорт.
    TooLarge,
    /// Узел отказал (переполнен ящик, неверный запрос): не переключаться, сообщить.
    Rejected,
}

impl TransportError {
    /// Стоит ли пробовать следующий транспорт лестницы.
    #[must_use]
    pub const fn should_fall_back(self) -> bool {
        !matches!(self, Self::Rejected)
    }
}

/// Результат пробы транспорта для диагностики.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeResult {
    /// Работает; время ответа.
    Ok(Duration),
    /// Не работает.
    Failed(TransportError),
}

/// Транспортный плагин.
pub trait Transport: Send {
    /// Свойства.
    fn info(&self) -> TransportInfo;
    /// Кладёт блоб в ящик.
    ///
    /// # Errors
    /// [`TransportError`].
    fn send(&mut self, to: &MailboxId, blob: &[u8]) -> Result<(), TransportError>;
    /// Забирает до `max` блобов из ящика. Пустой список — новых нет.
    ///
    /// # Errors
    /// [`TransportError`].
    fn fetch(&mut self, mailbox: &MailboxId, max: usize) -> Result<Vec<Vec<u8>>, TransportError>;
    /// Проверяет, работает ли транспорт сейчас, не передавая пользовательских данных.
    fn probe(&mut self) -> ProbeResult;
}

/// Транспорт в памяти: образец реализации и основа тестов ядра.
#[derive(Debug)]
pub struct Loopback {
    max_blob: usize,
    max_queue: usize,
    queues: BTreeMap<MailboxId, VecDeque<Vec<u8>>>,
    /// Если задано — все вызовы отвечают этой ошибкой (имитация блокировки).
    pub fail_with: Option<TransportError>,
}

impl Loopback {
    /// Создаёт транспорт с пределами размера блоба и длины очереди ящика.
    #[must_use]
    pub const fn new(max_blob: usize, max_queue: usize) -> Self {
        Self {
            max_blob,
            max_queue,
            queues: BTreeMap::new(),
            fail_with: None,
        }
    }
}

impl Transport for Loopback {
    fn info(&self) -> TransportInfo {
        TransportInfo {
            id: "loopback",
            kind: TransportKind::Loopback,
            max_blob: self.max_blob,
        }
    }

    fn send(&mut self, to: &MailboxId, blob: &[u8]) -> Result<(), TransportError> {
        if let Some(e) = self.fail_with {
            return Err(e);
        }
        if blob.len() > self.max_blob {
            return Err(TransportError::TooLarge);
        }
        let q = self.queues.entry(*to).or_default();
        if q.len() >= self.max_queue {
            return Err(TransportError::Rejected);
        }
        q.push_back(blob.to_vec());
        Ok(())
    }

    fn fetch(&mut self, mailbox: &MailboxId, max: usize) -> Result<Vec<Vec<u8>>, TransportError> {
        if let Some(e) = self.fail_with {
            return Err(e);
        }
        let Some(q) = self.queues.get_mut(mailbox) else {
            return Ok(Vec::new());
        };
        let n = max.min(q.len());
        Ok(q.drain(..n).collect())
    }

    fn probe(&mut self) -> ProbeResult {
        self.fail_with
            .map_or(ProbeResult::Ok(Duration::ZERO), ProbeResult::Failed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: MailboxId = MailboxId([1; 32]);
    const B: MailboxId = MailboxId([2; 32]);

    #[test]
    fn delivers_in_order_per_mailbox() {
        let mut t = Loopback::new(100, 10);
        t.send(&A, b"1").unwrap();
        t.send(&A, b"2").unwrap();
        t.send(&B, b"x").unwrap();
        assert_eq!(t.fetch(&A, 1).unwrap(), vec![b"1".to_vec()]);
        assert_eq!(t.fetch(&A, 10).unwrap(), vec![b"2".to_vec()]);
        assert_eq!(t.fetch(&A, 10).unwrap().len(), 0);
        assert_eq!(t.fetch(&B, 10).unwrap(), vec![b"x".to_vec()]);
    }

    #[test]
    fn limits_and_failures() {
        let mut t = Loopback::new(4, 1);
        assert_eq!(t.send(&A, b"12345"), Err(TransportError::TooLarge));
        t.send(&A, b"1").unwrap();
        assert_eq!(t.send(&A, b"2"), Err(TransportError::Rejected));
        t.fail_with = Some(TransportError::Blocked);
        assert_eq!(t.probe(), ProbeResult::Failed(TransportError::Blocked));
        assert_eq!(t.fetch(&A, 1), Err(TransportError::Blocked));
        assert!(TransportError::Blocked.should_fall_back());
        assert!(!TransportError::Rejected.should_fall_back());
    }

    #[test]
    fn is_object_safe() {
        let mut ladder: Vec<Box<dyn Transport>> = vec![Box::new(Loopback::new(10, 10))];
        assert_eq!(ladder[0].info().kind, TransportKind::Loopback);
        assert_eq!(ladder[0].probe(), ProbeResult::Ok(Duration::ZERO));
    }
}
