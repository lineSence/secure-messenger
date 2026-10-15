//! Очереди зашифрованных блобов. Всё состояние — в памяти: рестарт стирает всё.

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::sync::Arc;

use rand::{TryRngCore as _, rngs::OsRng};
use sm_core::relay::{ID_LEN, MAX_BLOB};

use crate::pass::Caller;

/// ID очереди (отправителя или получателя); он же — билет ссылки (ADR-146).
pub type QueueId = [u8; ID_LEN];
type SharedBlob = Option<(u64, Arc<Vec<u8>>)>;

/// Сколько часов живёт билет: ссылка живёт 24 часа (ADR-83), плюс запас на часовую точность.
pub const TICKET_TTL_HOURS: u64 = 25;
/// Сколько билетов узел хранит одновременно.
pub const MAX_TICKETS: usize = 1000;

/// Пределы узла против враждебного клиента.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// Блобов в одной очереди.
    pub blobs_per_queue: usize,
    /// Maximum number of live blobs across all queues (metadata budget).
    pub messages: usize,
    /// Maximum number of invitation tickets.
    pub tickets: usize,
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
            messages: 100_000,
            tickets: MAX_TICKETS,
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
    /// Пустой блоб не является сообщением и блокировал бы приём у клиента.
    BadRequest,
    /// Не удалось получить случайность или исчерпан счётчик: отказ без изменения очереди.
    Unavailable,
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
    data: Arc<Vec<u8>>,
}

struct Queue {
    sender: QueueId,
    blobs: VecDeque<Blob>,
    bytes: usize,
    next_seq: u64,
    /// Курсор справедливого FETCH/WAIT по списку с этой очередью первой.
    fetch_turn: usize,
    last_hour: u64,
    sweep_at: Option<u64>,
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
    total_messages: usize,
    last_sweep: Option<u64>,
    expiry: BTreeSet<(u64, QueueId)>,
    /// (час, сколько очередей создано в этот час).
    created: (u64, usize),
    /// (час, сколько очередей создано в этот час по каждому пропуску, ADR-136).
    by_pass: (u64, HashMap<PassId, u16>),
    /// Билеты ссылок (ADR-146), по билету.
    tickets: HashMap<QueueId, Ticket>,
}

/// Билет ссылки (ADR-146): права будущего гостевого пропуска и, после обмена, сам пропуск.
struct Ticket {
    grant: Caller,
    until_hour: u64,
    issued: Option<Vec<u8>>,
}

/// Исход обмена билета ([`Store::redeem_ticket`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Redeem {
    /// Билет не обменивали: права гостевого пропуска.
    Fresh(Caller),
    /// Уже обменян (повтор после потерянного ответа): тот же гостевой пропуск.
    Done(Vec<u8>),
}

/// ID пропуска на `NEW` (ADR-136).
pub type PassId = [u8; sm_core::pass::ID_LEN];

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
            total_messages: 0,
            last_sweep: None,
            expiry: BTreeSet::new(),
            created: (0, 0),
            by_pass: (0, HashMap::new()),
            tickets: HashMap::new(),
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
    /// [`StoreError::RateLimited`] — исчерпан предел новых очередей за час;
    /// [`StoreError::Unavailable`] — ошибка источника случайности.
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
        // Поле ACK остаётся u64: клиенты возвращают непрозрачный номер без арифметики.
        // Новый случайный старт не переиспользует 0 после DEL/рестарта; 63 бита энтропии
        // оставляют как минимум 2^63 последовательных номеров до отказа без wraparound.
        let next_seq = OsRng.try_next_u64().map_err(|_| StoreError::Unavailable)? >> 1;
        self.created = (now, in_hour.saturating_add(1));
        self.queues.insert(
            recipient,
            Queue {
                sender,
                blobs: VecDeque::new(),
                bytes: 0,
                next_seq,
                fetch_turn: 0,
                last_hour: now,
                sweep_at: None,
            },
        );
        self.senders.insert(sender, recipient);
        self.schedule(&recipient);
        Ok(())
    }

    /// `NEW` по пропуску (ADR-134, ADR-136): очередь с ID получателя от клиента. Живая очередь
    /// с теми же ID — успех без изменений (повтор после потерянного ответа или после `NO_QUEUE`
    /// у второго телефона не нужен — очередь та же). Иначе — лимит пропуска `per_hour` в час
    /// и [`Store::create`].
    ///
    /// # Errors
    /// [`StoreError::RateLimited`] — исчерпан лимит пропуска или общий; остальные — как у
    /// [`Store::create`].
    pub fn open(
        &mut self,
        recipient: QueueId,
        sender: QueueId,
        pass: &PassId,
        per_hour: u16,
        now: u64,
    ) -> Result<(), StoreError> {
        self.sweep(now);
        if self
            .queues
            .get(&recipient)
            .is_some_and(|q| q.sender == sender)
        {
            return Ok(());
        }
        let used = self.pass_used(pass, now, per_hour)?;
        self.create(recipient, sender, now)?;
        self.by_pass.1.insert(*pass, used.saturating_add(1));
        Ok(())
    }

    /// Сколько очередей по пропуску создано в этот час; лимит исчерпан — [`StoreError::RateLimited`].
    fn pass_used(&mut self, pass: &PassId, now: u64, per_hour: u16) -> Result<u16, StoreError> {
        if self.by_pass.0 != now {
            self.by_pass = (now, HashMap::new());
        }
        let used = self.by_pass.1.get(pass).copied().unwrap_or(0);
        if used >= per_hour {
            return Err(StoreError::RateLimited);
        }
        Ok(used)
    }

    /// Запоминает билет `ticket` для ссылки (ADR-146): права гостевого пропуска — от `by`.
    /// Билет считается в лимит новых очередей пропуска `by` в этот час.
    ///
    /// # Errors
    /// [`StoreError::RateLimited`] — лимит пропуска исчерпан; [`StoreError::Quota`] — билетов
    /// слишком много; [`StoreError::Collision`] — такой билет уже есть.
    pub fn issue_ticket(
        &mut self,
        ticket: QueueId,
        by: &Caller,
        now: u64,
    ) -> Result<(), StoreError> {
        self.sweep(now);
        if self.tickets.contains_key(&ticket) {
            return Err(StoreError::Collision);
        }
        if self.tickets.len() >= self.limits.tickets {
            return Err(StoreError::Quota);
        }
        let used = self.pass_used(&by.id, now, by.per_hour)?;
        self.by_pass.1.insert(by.id, used.saturating_add(1));
        self.tickets.insert(
            ticket,
            Ticket {
                grant: *by,
                until_hour: now.saturating_add(TICKET_TTL_HOURS),
                issued: None,
            },
        );
        Ok(())
    }

    /// Обмен билета: `None` — билета нет, он истёк или узел перезапускался.
    pub fn redeem_ticket(&mut self, ticket: &QueueId, now: u64) -> Option<Redeem> {
        self.sweep(now);
        let t = self.tickets.get(ticket)?;
        if now >= t.until_hour {
            return None;
        }
        Some(
            t.issued
                .as_ref()
                .map_or(Redeem::Fresh(t.grant), |g| Redeem::Done(g.clone())),
        )
    }

    /// Запоминает выданный по билету гостевой пропуск: повтор обмена вернёт его же.
    pub fn finish_ticket(&mut self, ticket: &QueueId, pass: Vec<u8>) {
        if let Some(t) = self.tickets.get_mut(ticket) {
            t.issued = Some(pass);
        }
    }

    /// Билетов сейчас.
    #[must_use]
    pub fn ticket_count(&self) -> usize {
        self.tickets.len()
    }

    pub(crate) fn recipient(&self, sender: &QueueId) -> Option<QueueId> {
        self.senders.get(sender).copied()
    }

    /// Кладёт блоб по ID отправителя.
    ///
    /// # Errors
    /// [`StoreError::NoQueue`], [`StoreError::TooBig`], [`StoreError::Quota`],
    /// [`StoreError::BadRequest`] для пустого блоба, [`StoreError::Unavailable`] при переполнении номера.
    pub fn send(&mut self, sender: &QueueId, blob: Vec<u8>, now: u64) -> Result<(), StoreError> {
        self.sweep(now);
        if blob.is_empty() {
            return Err(StoreError::BadRequest);
        }
        if blob.len() > MAX_BLOB {
            return Err(StoreError::TooBig);
        }
        let recipient = *self.senders.get(sender).ok_or(StoreError::NoQueue)?;
        let q = self.queues.get_mut(&recipient).ok_or(StoreError::NoQueue)?;
        let total = self
            .total_bytes
            .checked_add(blob.len())
            .ok_or(StoreError::Quota)?;
        let queue_bytes = q.bytes.checked_add(blob.len()).ok_or(StoreError::Quota)?;
        if self.total_messages >= self.limits.messages
            || q.blobs.len() >= self.limits.blobs_per_queue
            || queue_bytes > self.limits.bytes_per_queue
            || total > self.limits.total_bytes
        {
            return Err(StoreError::Quota);
        }
        let next_seq = q.next_seq.checked_add(1).ok_or(StoreError::Unavailable)?;
        q.bytes = queue_bytes;
        q.blobs.push_back(Blob {
            seq: q.next_seq,
            hour: now,
            data: Arc::new(blob),
        });
        q.next_seq = next_seq;
        q.last_hour = now;
        self.total_bytes = total;
        self.total_messages = self.total_messages.saturating_add(1);
        self.schedule(&recipient);
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
        self.fetch_shared(recipient, now)
            .map(|v| v.map(|(seq, data)| (seq, data.as_ref().clone())))
    }

    pub(crate) fn fetch_shared(
        &mut self,
        recipient: &QueueId,
        now: u64,
    ) -> Result<SharedBlob, StoreError> {
        self.sweep(now);
        let q = self.queues.get_mut(recipient).ok_or(StoreError::NoQueue)?;
        q.last_hour = now;
        let result = q.blobs.front().map(|b| (b.seq, Arc::clone(&b.data)));
        self.schedule(recipient);
        Ok(result)
    }

    /// Начальная позиция обхода списка; курсор живёт в очереди, а не в неограниченном
    /// кеше запросов. Неизвестный первый ID всё равно проверяется в `fetch`.
    pub(crate) fn fetch_start(&self, anchor: &QueueId) -> usize {
        self.queues.get(anchor).map_or(0, |q| q.fetch_turn)
    }

    /// Следующее получение начинается после обслуженной очереди.
    pub(crate) fn advance_fetch(&mut self, anchor: &QueueId, next: usize) {
        if let Some(q) = self.queues.get_mut(anchor) {
            q.fetch_turn = next;
        }
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
            self.total_messages = self.total_messages.saturating_sub(1);
        }
        // Reclaim the empty deque's peak capacity, but do not shrink on every ACK.
        if q.blobs.is_empty() {
            q.blobs = VecDeque::new();
        }
        self.schedule(recipient);
        Ok(())
    }

    /// Удаляет очередь со всеми блобами.
    ///
    /// # Errors
    /// [`StoreError::NoQueue`].
    pub fn delete(&mut self, recipient: &QueueId, now: u64) -> Result<(), StoreError> {
        self.sweep(now);
        let q = self.queues.remove(recipient).ok_or(StoreError::NoQueue)?;
        self.unschedule(recipient, &q);
        self.drop_queue(&q);
        Ok(())
    }

    fn drop_queue(&mut self, q: &Queue) {
        self.senders.remove(&q.sender);
        self.total_bytes = self.total_bytes.saturating_sub(q.bytes);
        self.total_messages = self.total_messages.saturating_sub(q.blobs.len());
    }

    fn unschedule(&mut self, rid: &QueueId, q: &Queue) {
        if let Some(at) = q.sweep_at {
            self.expiry.remove(&(at, *rid));
        }
    }

    /// One timer per queue, updated only when its actual next expiry changes.
    fn schedule(&mut self, rid: &QueueId) {
        let Some(q) = self.queues.get_mut(rid) else {
            return;
        };
        let idle = q.last_hour.checked_add(self.limits.idle_hours);
        let blob = q
            .blobs
            .front()
            .and_then(|b| b.hour.checked_add(self.limits.blob_ttl_hours));
        let at = match (idle, blob) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        };
        if at == q.sweep_at {
            return;
        }
        if let Some(old) = q.sweep_at {
            self.expiry.remove(&(old, *rid));
        }
        q.sweep_at = at;
        if let Some(at) = at {
            self.expiry.insert((at, *rid));
        }
    }

    /// Visit only queues whose next deadline has arrived, not the whole population.
    fn sweep(&mut self, now: u64) {
        if self.last_sweep == Some(now) {
            return;
        }
        self.last_sweep = Some(now);
        while let Some(&(at, rid)) = self.expiry.first() {
            if at > now {
                break;
            }
            self.expiry.pop_first();
            let Some(q) = self.queues.get_mut(&rid) else {
                continue;
            };
            q.sweep_at = None;
            if now.saturating_sub(q.last_hour) >= self.limits.idle_hours {
                if let Some(q) = self.queues.remove(&rid) {
                    self.drop_queue(&q);
                }
                continue;
            }
            while q
                .blobs
                .front()
                .is_some_and(|b| now.saturating_sub(b.hour) >= self.limits.blob_ttl_hours)
            {
                if let Some(b) = q.blobs.pop_front() {
                    q.bytes = q.bytes.saturating_sub(b.data.len());
                    self.total_bytes = self.total_bytes.saturating_sub(b.data.len());
                    self.total_messages = self.total_messages.saturating_sub(1);
                }
            }
            if q.blobs.is_empty() {
                q.blobs = VecDeque::new();
            }
            self.schedule(&rid);
        }
        self.tickets.retain(|_, t| now < t.until_hour);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const R: QueueId = [1; ID_LEN];
    const S: QueueId = [2; ID_LEN];

    fn by(id: u8, per_hour: u16) -> Caller {
        Caller {
            id: [id; 16],
            per_hour,
            parent: [id; 16],
            expires: 1_000_000,
        }
    }

    #[test]
    fn tickets_expire_cost_pass_limit_and_repeat_the_same_pass() {
        let mut s = Store::new(Limits::default());
        let t1 = [1; ID_LEN];
        s.issue_ticket(t1, &by(1, 2), 10).unwrap();
        assert_eq!(
            s.issue_ticket(t1, &by(1, 2), 10),
            Err(StoreError::Collision)
        );
        // Билет тратит лимит пропуска вместе с очередями: 2 в час.
        s.open(R, S, &[1; 16], 2, 10).unwrap();
        assert_eq!(
            s.issue_ticket([2; ID_LEN], &by(1, 2), 10),
            Err(StoreError::RateLimited)
        );
        // В следующий час лимит свежий.
        s.issue_ticket([2; ID_LEN], &by(1, 2), 11).unwrap();

        assert_eq!(s.redeem_ticket(&t1, 10), Some(Redeem::Fresh(by(1, 2))));
        s.finish_ticket(&t1, b"guest".to_vec());
        assert_eq!(
            s.redeem_ticket(&t1, 10),
            Some(Redeem::Done(b"guest".to_vec()))
        );
        assert_eq!(s.redeem_ticket(&[9; ID_LEN], 10), None);
        // Билет живёт TICKET_TTL_HOURS и стирается.
        assert!(s.redeem_ticket(&t1, 10 + TICKET_TTL_HOURS - 1).is_some());
        assert_eq!(s.redeem_ticket(&t1, 10 + TICKET_TTL_HOURS), None);
        assert_eq!(s.ticket_count(), 1); // остался второй билет, выданный часом позже
    }

    #[test]
    fn tickets_are_capped() {
        let mut s = Store::new(Limits::default());
        for i in 0..MAX_TICKETS {
            let mut t = [0; ID_LEN];
            t[..8].copy_from_slice(&u64::try_from(i).unwrap().to_be_bytes());
            s.issue_ticket(t, &by(1, u16::MAX), 0).unwrap();
        }
        assert_eq!(
            s.issue_ticket([0xff; ID_LEN], &by(1, u16::MAX), 0),
            Err(StoreError::Quota)
        );
    }

    #[test]
    fn open_is_idempotent_and_limited_per_pass() {
        let mut s = Store::new(Limits::default());
        let (p1, p2) = ([1; 16], [2; 16]);
        s.open(R, S, &p1, 2, 0).unwrap();
        s.send(&S, b"x".to_vec(), 0).unwrap();
        // Повтор на живую очередь — успех, очередь и блоб те же, лимит не тратится.
        s.open(R, S, &p1, 2, 0).unwrap();
        assert_eq!(s.fetch(&R, 0).unwrap().map(|b| b.1), Some(b"x".to_vec()));
        s.open([3; ID_LEN], [4; ID_LEN], &p1, 2, 0).unwrap();
        assert_eq!(
            s.open([5; ID_LEN], [6; ID_LEN], &p1, 2, 0),
            Err(StoreError::RateLimited)
        );
        // Другой пропуск и следующий час — свой счёт.
        s.open([5; ID_LEN], [6; ID_LEN], &p2, 2, 0).unwrap();
        s.open([7; ID_LEN], [8; ID_LEN], &p1, 2, 1).unwrap();
        // Тот же ID получателя с чужим ID отправителя — занят.
        assert_eq!(
            s.open(R, [9; ID_LEN], &p2, 2, 1),
            Err(StoreError::Collision)
        );
    }

    fn store() -> Store {
        let mut s = Store::new(Limits::default());
        s.create(R, S, 0).unwrap();
        s
    }

    #[test]
    fn empty_send_does_not_change_memory_or_sequence() {
        let mut s = store();
        let before = s.queues.get(&R).unwrap().next_seq;
        assert_eq!(s.send(&S, Vec::new(), 0), Err(StoreError::BadRequest));
        assert_eq!(s.total_bytes(), 0);
        assert_eq!(s.queues.get(&R).unwrap().next_seq, before);
        assert_eq!(s.fetch(&R, 0).unwrap(), None);
    }

    #[test]
    fn sequence_exhaustion_fails_without_wrap_or_partial_write() {
        let mut s = store();
        s.queues.get_mut(&R).unwrap().next_seq = u64::MAX;
        assert_eq!(
            s.send(&S, b"not committed".to_vec(), 0),
            Err(StoreError::Unavailable)
        );
        assert_eq!(s.total_bytes(), 0);
        assert_eq!(s.queues.get(&R).unwrap().bytes, 0);
        assert_eq!(s.queues.get(&R).unwrap().next_seq, u64::MAX);
        assert_eq!(s.fetch(&R, 0).unwrap(), None);
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
    #[test]
    fn timer_index_is_bounded_and_tracks_new_head_and_idle_activity() {
        let mut s = Store::new(Limits::default());
        s.create(R, S, 100).unwrap();
        s.send(&S, vec![1], 100).unwrap();
        s.send(&S, vec![2], 101).unwrap();
        for _ in 0..1000 {
            s.fetch(&R, 102).unwrap();
        }
        assert_eq!(s.expiry.len(), 1);
        assert_eq!(s.fetch(&R, 268).unwrap().unwrap().1, vec![2]);
        assert!(s.fetch(&R, 269).unwrap().is_none());
        assert_eq!(s.expiry.len(), 1);
        s.delete(&R, 269).unwrap();
        assert!(s.expiry.is_empty());
        s.create(R, S, 300).unwrap();
        assert!(s.fetch(&R, 301).unwrap().is_none());
        assert_eq!(s.queue_count(), 1);
    }

    #[test]
    fn global_message_budget_is_released_by_ack_delete_and_expiry() {
        let mut s = Store::new(Limits {
            messages: 1,
            ..Limits::default()
        });
        s.create(R, S, 100).unwrap();
        s.send(&S, vec![1], 100).unwrap();
        assert_eq!(s.send(&S, vec![2], 100), Err(StoreError::Quota));
        let seq = s.fetch(&R, 100).unwrap().unwrap().0;
        s.ack(&R, seq, 100).unwrap();
        s.send(&S, vec![2], 100).unwrap();
        s.delete(&R, 100).unwrap();
        assert_eq!(s.total_messages, 0);
        s.create(R, S, 101).unwrap();
        s.send(&S, vec![3], 101).unwrap();
        assert!(s.fetch(&R, 269).unwrap().is_none());
        assert_eq!(s.total_messages, 0);
        s.send(&S, vec![4], 269).unwrap();
    }

    #[test]
    fn shared_reply_survives_ack_without_mutating_new_head() {
        let mut s = Store::new(Limits::default());
        s.create(R, S, 100).unwrap();
        s.send(&S, vec![1; 4096], 100).unwrap();
        let (seq, reply) = s.fetch_shared(&R, 100).unwrap().unwrap();
        let again = s.fetch_shared(&R, 100).unwrap().unwrap().1;
        assert!(Arc::ptr_eq(&reply, &again));
        s.ack(&R, seq, 100).unwrap();
        s.send(&S, vec![2], 100).unwrap();
        assert_eq!(reply.as_slice(), &[1; 4096]);
        assert_eq!(s.fetch(&R, 100).unwrap().unwrap().1, vec![2]);
    }

    #[test]
    fn timer_overflow_does_not_spin_or_expire_early() {
        let mut s = Store::new(Limits::default());
        s.create(R, S, u64::MAX - 1).unwrap();
        s.send(&S, vec![1], u64::MAX - 1).unwrap();
        assert_eq!(s.fetch(&R, u64::MAX).unwrap().unwrap().1, vec![1]);
        assert!(s.expiry.is_empty());
    }
}
