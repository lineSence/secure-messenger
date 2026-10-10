//! Очередь отправки и окно повторов для Kotlin (`sm_core::delivery`, ADR-40, ADR-56).
//!
//! Объекты держат состояние под замком. Если замок отравлен (паника в другом потоке во время
//! изменения), состоянию не доверяем: все вызовы отвечают [`DeliveryFfiError::Broken`], а
//! приложение создаёт объект заново из последнего снимка на диске. Пустое состояние вместо
//! отравленного не подставляем — следующий снимок стёр бы с диска очередь неподтверждённых.

use std::sync::{Mutex, MutexGuard};

use sm_core::delivery::store::StoreError;
use sm_core::delivery::{Key, Outbox, OutboxError, OutboxLimits, Seen};
use sm_core::message::MessageId;
use sm_core::transport::MailboxId;

/// Ошибка очереди или окна.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum DeliveryFfiError {
    /// Ящик не 32 байта или ID сообщения не 16 байт.
    BadId,
    /// Сообщение с таким ключом уже ждёт подтверждения.
    Duplicate,
    /// Очередь заполнена (предел сообщений или байт).
    Full,
    /// Снимок повреждён, неизвестной версии, не канонический или больше пределов.
    BadSnapshot,
    /// Снимок не помещается в формат (длины больше `u32`).
    TooLarge,
    /// Состояние повреждено паникой; объект создают заново из снимка.
    Broken,
}

impl std::fmt::Display for DeliveryFfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for DeliveryFfiError {}

impl From<OutboxError> for DeliveryFfiError {
    fn from(e: OutboxError) -> Self {
        match e {
            OutboxError::Duplicate => Self::Duplicate,
            OutboxError::Full => Self::Full,
        }
    }
}

impl From<StoreError> for DeliveryFfiError {
    fn from(_: StoreError) -> Self {
        Self::BadSnapshot
    }
}

fn mailbox(bytes: &[u8]) -> Result<MailboxId, DeliveryFfiError> {
    bytes
        .try_into()
        .map(MailboxId)
        .map_err(|_| DeliveryFfiError::BadId)
}

fn key(mailbox_id: &[u8], message_id: &[u8]) -> Result<Key, DeliveryFfiError> {
    let id = message_id
        .try_into()
        .map(MessageId)
        .map_err(|_| DeliveryFfiError::BadId)?;
    Ok((mailbox(mailbox_id)?, id))
}

fn lock<T>(m: &Mutex<T>) -> Result<MutexGuard<'_, T>, DeliveryFfiError> {
    m.lock().map_err(|_| DeliveryFfiError::Broken)
}

/// Ключ сообщения: ящик собеседника (32 байта) и ID сообщения (16 байт).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MessageKey {
    /// Ящик собеседника.
    pub mailbox: Vec<u8>,
    /// ID сообщения.
    pub message_id: Vec<u8>,
}

impl From<Key> for MessageKey {
    fn from((m, id): Key) -> Self {
        Self {
            mailbox: m.0.to_vec(),
            message_id: id.0.to_vec(),
        }
    }
}

/// Сообщение, которое пора отправить.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct DueMessage {
    /// Кому и какое.
    pub key: MessageKey,
    /// Блоб — тот же, что в прошлый раз (для 1:1 — выровненное сообщение, шифруется при отправке).
    pub blob: Vec<u8>,
    /// Сколько раз уже пытались отправить.
    pub attempts: u32,
}

impl std::fmt::Debug for DueMessage {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "DueMessage {{ attempts: {}, len: {}, .. }}",
            self.attempts,
            self.blob.len()
        )
    }
}

/// Очередь отправки (`sm_core::delivery::Outbox`) с пределами по умолчанию. Время — монотонные
/// часы, мс (`SystemClock.elapsedRealtime()`); `boot` — `Settings.Global.BOOT_COUNT`.
#[derive(Debug, uniffi::Object)]
pub struct OutboxQueue {
    inner: Mutex<Outbox>,
}

#[uniffi::export]
impl OutboxQueue {
    /// Пустая очередь.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Outbox::new(OutboxLimits::DEFAULT)),
        }
    }

    /// Очередь из снимка; при смене `boot` отметки времени переносятся на новый отсчёт.
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadSnapshot`].
    #[uniffi::constructor]
    pub fn restore(snapshot: &[u8], boot: u64, now_ms: u64) -> Result<Self, DeliveryFfiError> {
        let o = Outbox::restore(snapshot, OutboxLimits::DEFAULT, boot, now_ms)?;
        Ok(Self {
            inner: Mutex::new(o),
        })
    }

    /// Снимок для записи на диск.
    ///
    /// # Errors
    /// [`DeliveryFfiError::TooLarge`], [`DeliveryFfiError::Broken`].
    pub fn encode(&self, boot: u64) -> Result<Vec<u8>, DeliveryFfiError> {
        lock(&self.inner)?
            .encode(boot)
            .ok_or(DeliveryFfiError::TooLarge)
    }

    /// Добавляет новое сообщение.
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadId`], [`DeliveryFfiError::Duplicate`], [`DeliveryFfiError::Full`],
    /// [`DeliveryFfiError::Broken`].
    pub fn push(
        &self,
        mailbox_id: &[u8],
        message_id: &[u8],
        blob: Vec<u8>,
        now_ms: u64,
    ) -> Result<(), DeliveryFfiError> {
        let k = key(mailbox_id, message_id)?;
        Ok(lock(&self.inner)?.push(k, blob, now_ms)?)
    }

    /// Сообщения, которые пора отправить, — от старых к новым.
    ///
    /// # Errors
    /// [`DeliveryFfiError::Broken`].
    pub fn due(&self, now_ms: u64) -> Result<Vec<DueMessage>, DeliveryFfiError> {
        Ok(lock(&self.inner)?
            .due(now_ms)
            .into_iter()
            .map(|d| DueMessage {
                key: d.key.into(),
                blob: d.blob.to_vec(),
                attempts: d.attempts,
            })
            .collect())
    }

    /// Отмечает попытку отправки (удачную или нет).
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadId`], [`DeliveryFfiError::Broken`].
    pub fn attempted(
        &self,
        mailbox_id: &[u8],
        message_id: &[u8],
        now_ms: u64,
    ) -> Result<(), DeliveryFfiError> {
        let k = key(mailbox_id, message_id)?;
        lock(&self.inner)?.attempted(&k, now_ms);
        Ok(())
    }

    /// Получатель подтвердил доставку; `false` — сообщения уже нет.
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadId`], [`DeliveryFfiError::Broken`].
    pub fn confirm(&self, mailbox_id: &[u8], message_id: &[u8]) -> Result<bool, DeliveryFfiError> {
        let k = key(mailbox_id, message_id)?;
        Ok(lock(&self.inner)?.confirm(&k))
    }

    /// Узел сменился или перезагрузился: всё ожидающее — к отправке без растущей паузы.
    ///
    /// # Errors
    /// [`DeliveryFfiError::Broken`].
    pub fn retry_all_now(&self, now_ms: u64) -> Result<(), DeliveryFfiError> {
        lock(&self.inner)?.retry_all_now(None, now_ms);
        Ok(())
    }

    /// То же для одного собеседника.
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadId`], [`DeliveryFfiError::Broken`].
    pub fn retry_peer_now(&self, mailbox_id: &[u8], now_ms: u64) -> Result<(), DeliveryFfiError> {
        let m = mailbox(mailbox_id)?;
        lock(&self.inner)?.retry_all_now(Some(&m), now_ms);
        Ok(())
    }

    /// Убирает просроченные сообщения и возвращает их ключи («не доставлено»).
    ///
    /// # Errors
    /// [`DeliveryFfiError::Broken`].
    pub fn expire(&self, now_ms: u64) -> Result<Vec<MessageKey>, DeliveryFfiError> {
        Ok(lock(&self.inner)?
            .expire(now_ms)
            .into_iter()
            .map(MessageKey::from)
            .collect())
    }

    /// Сообщений в очереди.
    ///
    /// # Errors
    /// [`DeliveryFfiError::Broken`].
    pub fn count(&self) -> Result<u32, DeliveryFfiError> {
        let n = lock(&self.inner)?.len();
        Ok(u32::try_from(n).unwrap_or(u32::MAX))
    }
}

/// Окно уже полученных сообщений (`sm_core::delivery::Seen`), ёмкость по умолчанию.
#[derive(Debug, uniffi::Object)]
pub struct SeenWindow {
    inner: Mutex<Seen>,
}

#[uniffi::export]
impl SeenWindow {
    /// Пустое окно.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Seen::new(Seen::DEFAULT_CAPACITY)),
        }
    }

    /// Окно из снимка.
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadSnapshot`].
    #[uniffi::constructor]
    pub fn restore(snapshot: &[u8]) -> Result<Self, DeliveryFfiError> {
        let s = Seen::restore(snapshot, Seen::DEFAULT_CAPACITY)?;
        Ok(Self {
            inner: Mutex::new(s),
        })
    }

    /// Снимок для записи на диск.
    ///
    /// # Errors
    /// [`DeliveryFfiError::TooLarge`], [`DeliveryFfiError::Broken`].
    pub fn encode(&self) -> Result<Vec<u8>, DeliveryFfiError> {
        lock(&self.inner)?
            .encode()
            .ok_or(DeliveryFfiError::TooLarge)
    }

    /// `true` — сообщение пришло впервые (показать); `false` — дубль. Подтверждение
    /// отправляют в обоих случаях.
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadId`], [`DeliveryFfiError::Broken`].
    pub fn first_time(
        &self,
        mailbox_id: &[u8],
        message_id: &[u8],
    ) -> Result<bool, DeliveryFfiError> {
        let k = key(mailbox_id, message_id)?;
        Ok(lock(&self.inner)?.first_time(k))
    }

    /// Забывает собеседника (контакт удалён).
    ///
    /// # Errors
    /// [`DeliveryFfiError::BadId`], [`DeliveryFfiError::Broken`].
    pub fn forget(&self, mailbox_id: &[u8]) -> Result<(), DeliveryFfiError> {
        let m = mailbox(mailbox_id)?;
        lock(&self.inner)?.forget(&m);
        Ok(())
    }
}

impl Default for OutboxQueue {
    fn default() -> Self {
        Self::new()
    }
}

impl Default for SeenWindow {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const M: [u8; 32] = [3; 32];
    const ID: [u8; 16] = [9; 16];

    #[test]
    fn outbox_round_trip_through_snapshot() {
        let q = OutboxQueue::new();
        q.push(&M, &ID, vec![1, 2, 3], 100).unwrap();
        assert_eq!(
            q.push(&M, &ID, vec![4], 100).unwrap_err(),
            DeliveryFfiError::Duplicate
        );
        let snap = q.encode(5).unwrap();
        let back = OutboxQueue::restore(&snap, 5, 200).unwrap();
        let due = back.due(200).unwrap();
        assert_eq!(due.len(), 1);
        assert_eq!(due[0].blob, vec![1, 2, 3]);
        assert_eq!(due[0].key.mailbox, M.to_vec());
        assert!(back.confirm(&M, &ID).unwrap());
        assert!(!back.confirm(&M, &ID).unwrap());
        assert_eq!(back.count().unwrap(), 0);
    }

    #[test]
    fn bad_ids_and_snapshots_are_rejected() {
        let q = OutboxQueue::new();
        assert_eq!(
            q.push(&M[..31], &ID, vec![1], 0).unwrap_err(),
            DeliveryFfiError::BadId
        );
        assert_eq!(
            q.push(&M, &ID[..15], vec![1], 0).unwrap_err(),
            DeliveryFfiError::BadId
        );
        assert_eq!(
            OutboxQueue::restore(&[0xff], 0, 0).unwrap_err(),
            DeliveryFfiError::BadSnapshot
        );
        assert_eq!(
            SeenWindow::restore(&[]).unwrap_err(),
            DeliveryFfiError::BadSnapshot
        );
    }

    #[test]
    fn seen_survives_snapshot() {
        let s = SeenWindow::new();
        assert!(s.first_time(&M, &ID).unwrap());
        assert!(!s.first_time(&M, &ID).unwrap());
        let back = SeenWindow::restore(&s.encode().unwrap()).unwrap();
        assert!(!back.first_time(&M, &ID).unwrap());
        back.forget(&M).unwrap();
        assert!(back.first_time(&M, &ID).unwrap());
    }

    #[test]
    fn poisoned_lock_fails_closed() {
        let q = std::sync::Arc::new(OutboxQueue::new());
        q.push(&M, &ID, vec![1], 0).unwrap();
        let q2 = q.clone();
        let _ = std::thread::spawn(move || {
            let _g = q2.inner.lock().unwrap();
            panic!("проверка отравления");
        })
        .join();
        assert_eq!(q.encode(0).unwrap_err(), DeliveryFfiError::Broken);
        assert_eq!(q.due(0).unwrap_err(), DeliveryFfiError::Broken);
    }
}
