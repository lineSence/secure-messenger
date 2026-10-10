//! Доставка до подтверждения (ADR-40).
//!
//! Узел может исчезнуть в любой момент вместе с очередью (ADR-39), поэтому сообщение считается
//! доставленным только после подтверждения от получателя ([`Body::Delivered`]), а не после
//! ответа узла:
//! - отправитель кладёт зашифрованный блоб в [`Outbox`] и хранит его до подтверждения;
//!   отправляет снова по таймеру (с растущей паузой) и сразу — после смены или перезагрузки
//!   узла ([`Outbox::retry_all_now`]);
//! - получатель пропускает каждое сообщение через [`Seen`]: дубль не показывается, но
//!   подтверждение на него отправляется снова — прошлое могло потеряться вместе с узлом.
//!
//! На подтверждение подтверждения нет: [`Body::Delivered`] в [`Outbox`] не кладут. Потерянное
//! подтверждение вызовет повтор сообщения, а повтор — новое подтверждение.
//!
//! Время передаёт вызывающий (мс UNIX), модуль часов не читает — так он проверяется тестами.
//! Хранение очереди и окна на устройстве между запусками приложения — шаг хранилища клиента.
//!
//! [`Body::Delivered`]: crate::message::Body::Delivered

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use crate::message::MessageId;
use crate::transport::MailboxId;

/// Ключ записи: ящик собеседника (кому отправили или от кого получили) и ID сообщения.
///
/// ID сообщения — 16 случайных байт отправителя (ADR-14); ящик в ключе не даёт одному
/// собеседнику своими ID подавить сообщения другого.
pub type Key = (MailboxId, MessageId);

/// Пределы очереди отправки.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OutboxLimits {
    /// Сообщений в очереди.
    pub max_messages: usize,
    /// Байт блобов в очереди.
    pub max_bytes: usize,
    /// Сколько ждать подтверждения, мс; потом сообщение — «не доставлено».
    pub ttl_ms: u64,
    /// Пауза перед первым повтором, мс; дальше удваивается.
    pub first_retry_ms: u64,
    /// Наибольшая пауза между повторами, мс.
    pub max_retry_ms: u64,
}

impl OutboxLimits {
    /// Значения по умолчанию: срок — как TTL блоба на узле (7 дней), повтор через 1 мин,
    /// пауза — до часа; пределы — как у одной очереди узла (1000 блобов, 32 `МиБ`).
    pub const DEFAULT: Self = Self {
        max_messages: 1000,
        max_bytes: 32 * 1024 * 1024,
        ttl_ms: 7 * 24 * 3600 * 1000,
        first_retry_ms: 60 * 1000,
        max_retry_ms: 3600 * 1000,
    };
}

impl Default for OutboxLimits {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// Отказ [`Outbox::push`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutboxError {
    /// Сообщение с таким ключом уже ждёт подтверждения.
    Duplicate,
    /// Превышен предел числа сообщений или байт.
    Full,
}

#[derive(Clone)]
struct Pending {
    blob: Vec<u8>,
    created_ms: u64,
    next_try_ms: u64,
    attempts: u32,
}

/// Сообщение, которое пора отправить.
#[derive(Clone, Copy)]
pub struct Due<'a> {
    /// Кому и какое.
    pub key: Key,
    /// Зашифрованный блоб — тот же, что в прошлый раз.
    pub blob: &'a [u8],
    /// Сколько раз уже пытались отправить.
    pub attempts: u32,
}

impl core::fmt::Debug for Due<'_> {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(
            f,
            "Due {{ attempts: {}, len: {}, .. }}",
            self.attempts,
            self.blob.len()
        )
    }
}

/// Очередь отправки: сообщения, на которые ещё нет подтверждения доставки.
#[derive(Clone)]
pub struct Outbox {
    limits: OutboxLimits,
    pending: BTreeMap<Key, Pending>,
    bytes: usize,
}

impl core::fmt::Debug for Outbox {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(
            f,
            "Outbox {{ messages: {}, bytes: {}, .. }}",
            self.pending.len(),
            self.bytes
        )
    }
}

impl Outbox {
    /// Пустая очередь с пределами.
    #[must_use]
    pub const fn new(limits: OutboxLimits) -> Self {
        Self {
            limits,
            pending: BTreeMap::new(),
            bytes: 0,
        }
    }

    /// Сколько сообщений ждёт подтверждения.
    #[must_use]
    pub fn len(&self) -> usize {
        self.pending.len()
    }

    /// Нет ли ожидающих.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.pending.is_empty()
    }

    /// Байт блобов в очереди.
    #[must_use]
    pub const fn bytes(&self) -> usize {
        self.bytes
    }

    /// Ждёт ли сообщение подтверждения.
    #[must_use]
    pub fn contains(&self, key: &Key) -> bool {
        self.pending.contains_key(key)
    }

    /// Добавляет новое сообщение; отправить его можно сразу ([`Outbox::due`]).
    ///
    /// # Errors
    /// [`OutboxError::Duplicate`] — ключ уже в очереди; [`OutboxError::Full`] — превышен предел.
    pub fn push(&mut self, key: Key, blob: Vec<u8>, now_ms: u64) -> Result<(), OutboxError> {
        if self.pending.contains_key(&key) {
            return Err(OutboxError::Duplicate);
        }
        let bytes = self
            .bytes
            .checked_add(blob.len())
            .filter(|b| *b <= self.limits.max_bytes)
            .ok_or(OutboxError::Full)?;
        if self.pending.len() >= self.limits.max_messages {
            return Err(OutboxError::Full);
        }
        self.bytes = bytes;
        self.pending.insert(
            key,
            Pending {
                blob,
                created_ms: now_ms,
                next_try_ms: now_ms,
                attempts: 0,
            },
        );
        Ok(())
    }

    /// Сообщения, которые пора отправить к моменту `now_ms`, — от старых к новым.
    #[must_use]
    pub fn due(&self, now_ms: u64) -> Vec<Due<'_>> {
        let mut out: Vec<(u64, Due<'_>)> = self
            .pending
            .iter()
            .filter(|(_, p)| p.next_try_ms <= now_ms)
            .map(|(key, p)| {
                (
                    p.created_ms,
                    Due {
                        key: *key,
                        blob: &p.blob,
                        attempts: p.attempts,
                    },
                )
            })
            .collect();
        out.sort_by_key(|(created, d)| (*created, d.key));
        out.into_iter().map(|(_, d)| d).collect()
    }

    /// Отмечает попытку отправки — удачную или нет: следующая через растущую паузу.
    /// Удачная отправка на узел не убирает сообщение: убирает только [`Outbox::confirm`].
    pub fn attempted(&mut self, key: &Key, now_ms: u64) {
        let limits = self.limits;
        if let Some(p) = self.pending.get_mut(key) {
            p.attempts = p.attempts.saturating_add(1);
            p.next_try_ms = now_ms.saturating_add(backoff(&limits, p.attempts));
        }
    }

    /// Получатель подтвердил доставку: сообщение убирается. `false` — его уже нет
    /// (повторное подтверждение или чужой ключ).
    pub fn confirm(&mut self, key: &Key) -> bool {
        match self.pending.remove(key) {
            Some(p) => {
                self.bytes = self.bytes.saturating_sub(p.blob.len());
                true
            }
            None => false,
        }
    }

    /// Узел сменился или перезагрузился (например, ответ `NO_QUEUE`): всё ожидающее —
    /// к отправке сейчас. Для одного собеседника — `Some(ящик)`, для всех — `None`.
    pub fn retry_all_now(&mut self, to: Option<&MailboxId>, now_ms: u64) {
        for ((mailbox, _), p) in &mut self.pending {
            if to.is_none_or(|t| t == mailbox) {
                p.next_try_ms = now_ms;
            }
        }
    }

    /// Убирает сообщения без подтверждения дольше [`OutboxLimits::ttl_ms`] и возвращает их
    /// ключи — интерфейс показывает «не доставлено».
    pub fn expire(&mut self, now_ms: u64) -> Vec<Key> {
        let ttl = self.limits.ttl_ms;
        let old: Vec<Key> = self
            .pending
            .iter()
            .filter(|(_, p)| now_ms.saturating_sub(p.created_ms) >= ttl)
            .map(|(k, _)| *k)
            .collect();
        for k in &old {
            self.confirm(k);
        }
        old
    }
}

/// Пауза после `attempts`-й попытки: `first_retry_ms · 2^(attempts−1)`, не больше `max_retry_ms`.
fn backoff(limits: &OutboxLimits, attempts: u32) -> u64 {
    let factor = 2_u64.saturating_pow(attempts.saturating_sub(1));
    limits
        .first_retry_ms
        .saturating_mul(factor)
        .min(limits.max_retry_ms)
}

/// Окно уже полученных сообщений у получателя: отсекает дубли от повторной отправки.
///
/// Хранит последние `capacity` ключей; самый старый вытесняется. Окно должно покрывать всё,
/// что отправитель может повторить за [`OutboxLimits::ttl_ms`]; при 2–5 пользователях
/// 10 000 ключей ([`Seen::DEFAULT_CAPACITY`]) — с большим запасом.
#[derive(Clone)]
pub struct Seen {
    capacity: usize,
    set: BTreeSet<Key>,
    order: VecDeque<Key>,
}

impl core::fmt::Debug for Seen {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(
            f,
            "Seen {{ len: {}, capacity: {} }}",
            self.set.len(),
            self.capacity
        )
    }
}

impl Seen {
    /// Ёмкость по умолчанию.
    pub const DEFAULT_CAPACITY: usize = 10_000;

    /// Пустое окно на `capacity` ключей (не меньше одного).
    #[must_use]
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            set: BTreeSet::new(),
            order: VecDeque::new(),
        }
    }

    /// Сколько ключей в окне.
    #[must_use]
    pub fn len(&self) -> usize {
        self.set.len()
    }

    /// Пусто ли окно.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.set.is_empty()
    }

    /// `true` — сообщение пришло впервые (показать); `false` — дубль (не показывать).
    /// В обоих случаях получатель отправляет [`Body::Delivered`].
    ///
    /// [`Body::Delivered`]: crate::message::Body::Delivered
    pub fn first_time(&mut self, key: Key) -> bool {
        if !self.set.insert(key) {
            return false;
        }
        self.order.push_back(key);
        while self.order.len() > self.capacity {
            if let Some(old) = self.order.pop_front() {
                self.set.remove(&old);
            }
        }
        true
    }
}

impl Default for Seen {
    fn default() -> Self {
        Self::new(Self::DEFAULT_CAPACITY)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::message::{Body, Message};
    use crate::transport::{Loopback, Transport};

    const ALICE: MailboxId = MailboxId([1; 32]);
    const BOB: MailboxId = MailboxId([2; 32]);
    const MIN: u64 = 60 * 1000;

    fn id(n: u8) -> MessageId {
        MessageId([n; 16])
    }

    fn small() -> OutboxLimits {
        OutboxLimits {
            max_messages: 2,
            max_bytes: 10,
            ..OutboxLimits::DEFAULT
        }
    }

    #[test]
    fn keeps_message_until_confirmed() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push((BOB, id(1)), b"blob".to_vec(), 0).unwrap();
        assert_eq!(o.due(0).len(), 1);
        o.attempted(&(BOB, id(1)), 0);
        assert!(o.due(0).is_empty(), "после отправки — пауза");
        assert!(o.contains(&(BOB, id(1))), "ответ узла не убирает сообщение");
        assert!(o.confirm(&(BOB, id(1))));
        assert!(
            !o.confirm(&(BOB, id(1))),
            "повторное подтверждение безопасно"
        );
        assert!(o.is_empty());
        assert_eq!(o.bytes(), 0);
    }

    #[test]
    fn backoff_doubles_and_is_capped() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let k = (BOB, id(1));
        o.push(k, vec![0], 0).unwrap();
        let mut now = 0;
        for expected in [1, 2, 4, 8, 16, 32, 60, 60] {
            o.attempted(&k, now);
            assert!(o.due(now + expected * MIN - 1).is_empty());
            assert_eq!(o.due(now + expected * MIN).len(), 1);
            now += expected * MIN;
        }
        for _ in 0..100 {
            o.attempted(&k, u64::MAX - 1);
        }
        assert!(o.due(u64::MAX - 2).is_empty(), "без переполнения времени");
    }

    #[test]
    fn retry_all_now_after_node_change() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push((BOB, id(1)), vec![1], 0).unwrap();
        o.push((ALICE, id(2)), vec![2], 0).unwrap();
        o.attempted(&(BOB, id(1)), 0);
        o.attempted(&(ALICE, id(2)), 0);
        o.retry_all_now(Some(&BOB), 5);
        let due: Vec<Key> = o.due(5).iter().map(|d| d.key).collect();
        assert_eq!(due, vec![(BOB, id(1))]);
        o.retry_all_now(None, 6);
        assert_eq!(o.due(6).len(), 2);
    }

    #[test]
    fn due_is_oldest_first() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push((BOB, id(9)), vec![9], 1).unwrap();
        o.push((BOB, id(1)), vec![1], 2).unwrap();
        let blobs: Vec<&[u8]> = o.due(2).iter().map(|d| d.blob).collect();
        assert_eq!(blobs, vec![&[9][..], &[1][..]]);
    }

    #[test]
    fn limits_and_duplicates() {
        let mut o = Outbox::new(small());
        o.push((BOB, id(1)), vec![0; 6], 0).unwrap();
        assert_eq!(
            o.push((BOB, id(1)), vec![0], 0),
            Err(OutboxError::Duplicate)
        );
        assert_eq!(o.push((BOB, id(2)), vec![0; 5], 0), Err(OutboxError::Full));
        o.push((BOB, id(2)), vec![0; 4], 0).unwrap();
        assert_eq!(o.push((BOB, id(3)), vec![], 0), Err(OutboxError::Full));
        o.push((ALICE, id(1)), vec![], 0).unwrap_err();
        assert!(o.confirm(&(BOB, id(1))));
        o.push((ALICE, id(1)), vec![0; 6], 0).unwrap();
        assert_eq!(o.bytes(), 10);
    }

    #[test]
    fn expires_after_ttl() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let ttl = OutboxLimits::DEFAULT.ttl_ms;
        o.push((BOB, id(1)), vec![1], 0).unwrap();
        o.push((BOB, id(2)), vec![2], 10).unwrap();
        assert_eq!(o.expire(ttl - 1).len(), 0);
        assert_eq!(o.expire(ttl), vec![(BOB, id(1))]);
        assert_eq!(o.len(), 1);
        assert_eq!(o.bytes(), 1);
        assert_eq!(o.expire(0).len(), 0, "часы назад — ничего не истекает");
    }

    #[test]
    fn seen_drops_duplicates_per_peer() {
        let mut s = Seen::new(2);
        assert!(s.first_time((BOB, id(1))));
        assert!(!s.first_time((BOB, id(1))));
        assert!(s.first_time((ALICE, id(1))), "чужой ящик — другой ключ");
        assert!(s.first_time((BOB, id(2))));
        assert_eq!(s.len(), 2);
        assert!(s.first_time((BOB, id(1))), "вытеснен самый старый");
        let mut one = Seen::new(0);
        assert!(one.first_time((BOB, id(1))));
        assert!(!one.first_time((BOB, id(1))), "ёмкость 0 становится 1");
    }

    #[test]
    fn debug_hides_contents() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push((BOB, id(1)), b"secret".to_vec(), 0).unwrap();
        let s = format!("{o:?} {:?} {:?}", o.due(0), Seen::default());
        assert!(!s.contains("secret"));
    }

    /// Сценарий ADR-40: узел перезагрузился и потерял очередь — сообщение всё равно доходит,
    /// дубль отсекается, подтверждение отправляется на каждый приход.
    #[test]
    fn survives_node_reboot() {
        let delivered = |target| {
            Message {
                id: id(200),
                sent_at_ms: 0,
                body: Body::Delivered { target },
            }
            .encode()
            .unwrap()
        };
        let mut node = Loopback::new(1024, 16);
        let mut outbox = Outbox::new(OutboxLimits::DEFAULT);
        let mut seen = Seen::default();
        let k = (BOB, id(1));
        outbox.push(k, b"hello".to_vec(), 0).unwrap();

        let send_due = |outbox: &mut Outbox, node: &mut Loopback, now| {
            let keys: Vec<(Key, Vec<u8>)> = outbox
                .due(now)
                .iter()
                .map(|d| (d.key, d.blob.to_vec()))
                .collect();
            for (key, blob) in keys {
                node.send(&key.0, &blob).unwrap();
                outbox.attempted(&key, now);
            }
        };

        send_due(&mut outbox, &mut node, 0);
        node = Loopback::new(1024, 16); // перезагрузка: очередь узла пропала
        assert_eq!(node.fetch(&BOB, 10).unwrap().len(), 0);
        outbox.retry_all_now(None, 1);
        send_due(&mut outbox, &mut node, 1);
        // Таймер тоже повторил — у получателя два экземпляра.
        send_due(&mut outbox, &mut node, 1 + 2 * MIN);

        let got = node.fetch(&BOB, 10).unwrap();
        assert_eq!(got.len(), 2);
        let mut shown = 0;
        for _ in &got {
            if seen.first_time((ALICE, id(1))) {
                shown += 1;
            }
            node.send(&ALICE, &delivered(id(1))).unwrap();
        }
        assert_eq!(shown, 1, "дубль не показан");

        for blob in node.fetch(&ALICE, 10).unwrap() {
            if let Body::Delivered { target } = Message::decode(&blob).unwrap().body {
                outbox.confirm(&(BOB, target));
            }
        }
        assert!(outbox.is_empty());
    }
}
