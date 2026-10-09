//! Очереди зашифрованных блобов. Всё состояние — в памяти: рестарт стирает всё.

use std::collections::{HashMap, VecDeque};

use sm_core::relay::{ID_LEN, MAX_BLOB};

/// ID очереди (отправителя или получателя).
pub type QueueId = [u8; ID_LEN];

/// Пределы узла против враждебного клиента.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// Блобов в одной очереди.
    pub blobs_per_queue: usize,
    /// Очередей на узле.
    pub queues: usize,
    /// Байт во всех блобах.
    pub total_bytes: usize,
    /// Байт в одной очереди: одна очередь не займёт общий пул.
    pub bytes_per_queue: usize,
    /// Новых очередей за час на весь узел.
    pub new_per_hour: usize,
    /// Срок жизни блоба, часов.
    pub blob_ttl_hours: u64,
    /// Очередь без обращений удаляется через столько часов.
    pub idle_hours: u64,
}

impl Default for Limits {
    /// Пределы из `relay.md`: 1000 блобов и 32 мебибайта на очередь, 10 000 очередей, 100 новых очередей
    /// в час, 256 мебибайт всего, срок блоба 7 дней, простой 30 дней.
    fn default() -> Self {
        Self {
            blobs_per_queue: 1000,
            queues: 10_000,
            total_bytes: 256 << 20,
            bytes_per_queue: 32 << 20,
            new_per_hour: 100,
            blob_ttl_hours: 7 * 24,
            idle_hours: 30 * 24,
        }
    }
}

/// Отказ хранилища.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StoreError {
    /// Очереди нет.
    NoQueue,
    /// Блоб больше [`MAX_BLOB`].
    TooBig,
    /// Превышен один из [`Limits`].
    Quota,
    /// Такой ID уже занят (случайные ID совпали) — создать заново.
    Collision,
    /// Слишком много новых очередей за этот час.
    RateLimited,
}

struct Blob {
    seq: u64,
    hour: u64,
    data: Vec<u8>,
}

struct Queue {
    sender: QueueId,
    blobs: VecDeque<Blob>,
    bytes: usize,
    next_seq: u64,
    last_hour: u64,
}

/// Хранилище очередей. Время — номер часа (точность до часа, `relay.md`).
/// `Debug` показывает только счётчики: ни ID, ни содержимого.
pub struct Store {
    limits: Limits,
    /// По ID получателя.
    queues: HashMap<QueueId, Queue>,
    /// ID отправителя → ID получателя.
    senders: HashMap<QueueId, QueueId>,
    total_bytes: usize,
    last_sweep: Option<u64>,
    /// (час, сколько очередей создано в этот час).
    created: (u64, usize),
}

impl core::fmt::Debug for Store {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(
            f,
            "Store {{ queues: {}, bytes: {} }}",
            self.queues.len(),
            self.total_bytes
        )
    }
}

impl Store {
    /// Пустое хранилище.
    #[must_use]
    pub fn new(limits: Limits) -> Self {
        Self {
            limits,
            queues: HashMap::new(),
            senders: HashMap::new(),
            total_bytes: 0,
            last_sweep: None,
            created: (0, 0),
        }
    }

    /// Очередей сейчас.
    #[must_use]
    pub fn queue_count(&self) -> usize {
        self.queues.len()
    }

    /// Байт в блобах сейчас.
    #[must_use]
    pub const fn total_bytes(&self) -> usize {
        self.total_bytes
    }

    /// Создаёт очередь с ID получателя `recipient` и ID отправителя `sender`.
    ///
    /// # Errors
    /// [`StoreError::Collision`] — ID занят или ID совпадают; [`StoreError::Quota`] — очередей слишком много;
    /// [`StoreError::RateLimited`] — исчерпан предел новых очередей за час.
    pub fn create(
        &mut self,
        recipient: QueueId,
        sender: QueueId,
        now: u64,
    ) -> Result<(), StoreError> {
        self.sweep(now);
        let taken = |id: &QueueId| self.queues.contains_key(id) || self.senders.contains_key(id);
        if recipient == sender || taken(&recipient) || taken(&sender) {
            return Err(StoreError::Collision);
        }
        if self.queues.len() >= self.limits.queues {
            return Err(StoreError::Quota);
        }
        let in_hour = if self.created.0 == now {
            self.created.1
        } else {
            0
        };
        if in_hour >= self.limits.new_per_hour {
            return Err(StoreError::RateLimited);
        }
        self.created = (now, in_hour.saturating_add(1));
        self.queues.insert(
            recipient,
            Queue {
                sender,
                blobs: VecDeque::new(),
                bytes: 0,
                next_seq: 0,
                last_hour: now,
            },
        );
        self.senders.insert(sender, recipient);
        Ok(())
    }

    /// Кладёт блоб по ID отправителя.
    ///
    /// # Errors
    /// [`StoreError::NoQueue`], [`StoreError::TooBig`], [`StoreError::Quota`].
    pub fn send(&mut self, sender: &QueueId, blob: Vec<u8>, now: u64) -> Result<(), StoreError> {
        self.sweep(now);
        if blob.len() > MAX_BLOB {
            return Err(StoreError::TooBig);
        }
        let recipient = self.senders.get(sender).ok_or(StoreError::NoQueue)?;
        let q = self.queues.get_mut(recipient).ok_or(StoreError::NoQueue)?;
        let total = self
            .total_bytes
            .checked_add(blob.len())
            .ok_or(StoreError::Quota)?;
        let queue_bytes = q.bytes.checked_add(blob.len()).ok_or(StoreError::Quota)?;
        if q.blobs.len() >= self.limits.blobs_per_queue
            || queue_bytes > self.limits.bytes_per_queue
            || total > self.limits.total_bytes
        {
            return Err(StoreError::Quota);
        }
        q.bytes = queue_bytes;
        q.blobs.push_back(Blob {
            seq: q.next_seq,
            hour: now,
            data: blob,
        });
        q.next_seq = q.next_seq.wrapping_add(1);
        q.last_hour = now;
        self.total_bytes = total;
        Ok(())
    }

    /// Самый старый блоб и его номер. Блоб остаётся в очереди до [`Store::ack`]:
    /// при обрыве связи повторный `fetch` вернёт его снова.
    ///
    /// # Errors
    /// [`StoreError::NoQueue`].
    pub fn fetch(
        &mut self,
        recipient: &QueueId,
        now: u64,
    ) -> Result<Option<(u64, Vec<u8>)>, StoreError> {
        self.sweep(now);
        let q = self.queues.get_mut(recipient).ok_or(StoreError::NoQueue)?;
        q.last_hour = now;
        Ok(q.blobs.front().map(|b| (b.seq, b.data.clone())))
    }

    /// Удаляет самый старый блоб, если его номер — `seq`. Повторный `ACK` ничего не делает.
    ///
    /// # Errors
    /// [`StoreError::NoQueue`].
    pub fn ack(&mut self, recipient: &QueueId, seq: u64, now: u64) -> Result<(), StoreError> {
        self.sweep(now);
        let q = self.queues.get_mut(recipient).ok_or(StoreError::NoQueue)?;
        q.last_hour = now;
        if q.blobs.front().is_some_and(|b| b.seq == seq)
            && let Some(b) = q.blobs.pop_front()
        {
            q.bytes = q.bytes.saturating_sub(b.data.len());
            self.total_bytes = self.total_bytes.saturating_sub(b.data.len());
        }
        Ok(())
    }

    /// Удаляет очередь со всеми блобами.
    ///
    /// # Errors
    /// [`StoreError::NoQueue`].
    pub fn delete(&mut self, recipient: &QueueId, now: u64) -> Result<(), StoreError> {
        self.sweep(now);
        let q = self.queues.remove(recipient).ok_or(StoreError::NoQueue)?;
        self.drop_queue(&q);
        Ok(())
    }

    fn drop_queue(&mut self, q: &Queue) {
        self.senders.remove(&q.sender);
        self.total_bytes = self.total_bytes.saturating_sub(q.bytes);
    }

    /// Раз в час удаляет истёкшие блобы и заброшенные очереди.
    fn sweep(&mut self, now: u64) {
        if self.last_sweep == Some(now) {
            return;
        }
        self.last_sweep = Some(now);
        let Limits {
            blob_ttl_hours,
            idle_hours,
            ..
        } = self.limits;
        let mut freed = 0usize;
        let mut idle = Vec::new();
        for (rid, q) in &mut self.queues {
            while q
                .blobs
                .front()
                .is_some_and(|b| now.saturating_sub(b.hour) >= blob_ttl_hours)
            {
                if let Some(b) = q.blobs.pop_front() {
                    q.bytes = q.bytes.saturating_sub(b.data.len());
                    freed = freed.saturating_add(b.data.len());
                }
            }
            if now.saturating_sub(q.last_hour) >= idle_hours {
                idle.push(*rid);
            }
        }
        self.total_bytes = self.total_bytes.saturating_sub(freed);
        for rid in idle {
            if let Some(q) = self.queues.remove(&rid) {
                self.drop_queue(&q);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const R: QueueId = [1; ID_LEN];
    const S: QueueId = [2; ID_LEN];

    fn store() -> Store {
        let mut s = Store::new(Limits::default());
        s.create(R, S, 0).unwrap();
        s
    }

    #[test]
    fn send_fetch_ack_in_order() {
        let mut s = store();
        s.send(&S, b"1".to_vec(), 0).unwrap();
        s.send(&S, b"2".to_vec(), 0).unwrap();
        let (seq, b) = s.fetch(&R, 0).unwrap().unwrap();
        assert_eq!(b, b"1");
        // Без ACK — тот же блоб снова (обрыв связи не теряет сообщение).
        assert_eq!(s.fetch(&R, 0).unwrap().unwrap(), (seq, b.clone()));
        s.ack(&R, seq, 0).unwrap();
        s.ack(&R, seq, 0).unwrap(); // повтор ACK безопасен
        let (seq2, b2) = s.fetch(&R, 0).unwrap().unwrap();
        assert_eq!(b2, b"2");
        s.ack(&R, seq2, 0).unwrap();
        assert_eq!(s.fetch(&R, 0).unwrap(), None);
        assert_eq!(s.total_bytes(), 0);
    }

    #[test]
    fn ids_are_not_interchangeable() {
        let mut s = store();
        // ID получателя не годится для отправки, ID отправителя — для чтения.
        assert_eq!(s.send(&R, b"x".to_vec(), 0), Err(StoreError::NoQueue));
        assert_eq!(s.fetch(&S, 0), Err(StoreError::NoQueue));
        assert_eq!(s.delete(&S, 0), Err(StoreError::NoQueue));
        assert_eq!(s.create(R, [3; ID_LEN], 0), Err(StoreError::Collision));
        assert_eq!(s.create([3; ID_LEN], S, 0), Err(StoreError::Collision));
        assert_eq!(
            s.create([4; ID_LEN], [4; ID_LEN], 0),
            Err(StoreError::Collision)
        );
    }

    #[test]
    fn ttl_and_idle_expiry() {
        let mut s = store();
        s.send(&S, b"old".to_vec(), 0).unwrap();
        s.send(&S, b"new".to_vec(), 100).unwrap();
        // Через 7 дней старый блоб удалён, новый — нет.
        let (_, b) = s.fetch(&R, 7 * 24).unwrap().unwrap();
        assert_eq!(b, b"new");
        assert_eq!(s.total_bytes(), 3);
        // Очередь без обращений 30 дней удаляется вместе с блобами.
        s.create([5; ID_LEN], [6; ID_LEN], 7 * 24).unwrap();
        s.fetch(&[5; ID_LEN], 7 * 24 + 30 * 24 - 1).unwrap();
        assert_eq!(s.fetch(&R, 7 * 24 + 30 * 24), Err(StoreError::NoQueue));
        assert_eq!(
            s.send(&S, b"x".to_vec(), 7 * 24 + 30 * 24),
            Err(StoreError::NoQueue)
        );
        assert_eq!(s.queue_count(), 1);
        assert_eq!(s.total_bytes(), 0);
    }

    #[test]
    fn limits() {
        let limits = Limits {
            blobs_per_queue: 2,
            queues: 2,
            total_bytes: 10,
            ..Limits::default()
        };
        let mut s = Store::new(limits);
        s.create(R, S, 0).unwrap();
        s.create([3; ID_LEN], [4; ID_LEN], 0).unwrap();
        assert_eq!(
            s.create([5; ID_LEN], [6; ID_LEN], 0),
            Err(StoreError::Quota)
        );
        assert_eq!(
            s.send(&S, vec![0; MAX_BLOB + 1], 0),
            Err(StoreError::TooBig)
        );
        s.send(&S, vec![0; 4], 0).unwrap();
        s.send(&S, vec![0; 4], 0).unwrap();
        assert_eq!(s.send(&S, vec![0; 1], 0), Err(StoreError::Quota)); // блобов в очереди
        assert_eq!(s.send(&[4; ID_LEN], vec![0; 3], 0), Err(StoreError::Quota)); // вся память
        s.delete(&R, 0).unwrap();
        assert_eq!(s.total_bytes(), 0);
        s.send(&[4; ID_LEN], vec![0; 10], 0).unwrap();
        assert_eq!(s.send(&S, b"x".to_vec(), 0), Err(StoreError::NoQueue));
    }

    #[test]
    fn debug_hides_contents() {
        let mut s = store();
        s.send(&S, b"secret-blob".to_vec(), 0).unwrap();
        assert_eq!(format!("{s:?}"), "Store { queues: 1, bytes: 11 }");
    }

    #[test]
    fn per_queue_bytes_and_new_rate() {
        let limits = Limits {
            bytes_per_queue: 5,
            new_per_hour: 2,
            ..Limits::default()
        };
        let mut s = Store::new(limits);
        s.create(R, S, 0).unwrap();
        s.create([3; ID_LEN], [4; ID_LEN], 0).unwrap();
        // Третья очередь в тот же час — отказ, в следующий час — можно.
        assert_eq!(
            s.create([5; ID_LEN], [6; ID_LEN], 0),
            Err(StoreError::RateLimited)
        );
        s.create([5; ID_LEN], [6; ID_LEN], 1).unwrap();
        // Одна очередь не занимает больше своего предела; ACK освобождает место.
        s.send(&S, vec![0; 5], 1).unwrap();
        assert_eq!(s.send(&S, vec![0; 1], 1), Err(StoreError::Quota));
        s.send(&[4; ID_LEN], vec![0; 5], 1).unwrap();
        let (seq, _) = s.fetch(&R, 1).unwrap().unwrap();
        s.ack(&R, seq, 1).unwrap();
        s.send(&S, vec![0; 5], 1).unwrap();
        assert_eq!(s.total_bytes(), 10);
    }
}
