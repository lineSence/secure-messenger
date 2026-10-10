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
//! Время [`Outbox`] передаёт вызывающий — это **монотонные** часы в мс (Android
//! `SystemClock.elapsedRealtime()`, Windows `GetTickCount64`), а не время UNIX: стеночные часы
//! переводятся в обе стороны (сброс RTC, NTP, вручную), и сроки по ним то замораживались, то
//! старили очередь (находки ревью PR #14–#16). Монотонные часы идут назад только при
//! перезагрузке устройства: это учтено как новый отсчёт. Модуль часов не читает — так он
//! проверяется тестами.
//! Хранение очереди и окон между запусками приложения — модуль [`store`]: [`Outbox::encode`] и
//! [`Outbox::restore`] (сам вызывает [`Outbox::rebase`] после перезагрузки устройства),
//! [`Seen::encode`] и [`Seen::restore`].
//!
//! [`Body::Delivered`]: crate::message::Body::Delivered

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use crate::message::MessageId;
use crate::transport::MailboxId;

pub mod store;

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
    /// Порядок добавления — для [`Outbox::due`], не зависит от часов.
    seq: u64,
    /// Возраст: сумма шагов монотонных часов вперёд между вызовами [`Outbox::expire`].
    age_ms: u64,
    /// Время последнего учёта возраста.
    last_seen_ms: u64,
    next_try_ms: u64,
    /// Момент последней попытки; `None` — ещё не отправляли.
    last_try_ms: Option<u64>,
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
    next_seq: u64,
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
            next_seq: 0,
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
        let seq = self.next_seq;
        self.next_seq = seq.saturating_add(1);
        self.pending.insert(
            key,
            Pending {
                blob,
                seq,
                age_ms: 0,
                last_seen_ms: now_ms,
                next_try_ms: now_ms,
                last_try_ms: None,
                attempts: 0,
            },
        );
        Ok(())
    }

    /// Сообщения, которые пора отправить к моменту `now_ms`, — от старых к новым.
    ///
    /// Срок повтора дальше `max_retry_ms` от `now_ms` бывает только после перезагрузки
    /// (монотонные часы начались заново) — такое сообщение тоже отдаётся, иначе оно зависло бы
    /// без повторов.
    #[must_use]
    pub fn due(&self, now_ms: u64) -> Vec<Due<'_>> {
        let max_retry = self.limits.max_retry_ms;
        let mut out: Vec<(u64, Due<'_>)> = self
            .pending
            .iter()
            .filter(|(_, p)| {
                p.next_try_ms <= now_ms || p.next_try_ms.saturating_sub(now_ms) > max_retry
            })
            .map(|(key, p)| {
                (
                    p.seq,
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
            p.last_try_ms = Some(now_ms);
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
    /// к отправке без экспоненциальной паузы. Для одного собеседника — `Some(ящик)`,
    /// для всех — `None`.
    ///
    /// Узел недоверенный и может отвечать `NO_QUEUE` на каждый запрос, поэтому повтор
    /// не чаще `first_retry_ms` после прошлой попытки: срок становится
    /// `max(now_ms, прошлая попытка + first_retry_ms)`, но не позже уже назначенного.
    pub fn retry_all_now(&mut self, to: Option<&MailboxId>, now_ms: u64) {
        let first = self.limits.first_retry_ms;
        for ((mailbox, _), p) in &mut self.pending {
            if to.is_none_or(|t| t == mailbox) {
                // Попытка «в будущем» — была перезагрузка: считаем от `now_ms`.
                let earliest = p
                    .last_try_ms
                    .filter(|last| *last <= now_ms)
                    .map_or(now_ms, |last| last.saturating_add(first).max(now_ms));
                p.next_try_ms = p.next_try_ms.min(earliest);
            }
        }
    }

    /// Новый отсчёт часов: отметки времени одной загрузки нельзя сравнивать с часами другой
    /// загрузки или другого устройства (находка ревью PR #17). Клиент хранит вместе с очередью
    /// номер загрузки (Android `Settings.Global.BOOT_COUNT`) и вызывает `rebase`, только если
    /// он сменился (перезагрузка, перенос очереди); при перезапуске процесса в той же загрузке
    /// отметки остаются верными, и `rebase` не нужен.
    ///
    /// Возраст и число попыток сохраняются. Оставшаяся пауза повтора переносится на новый
    /// отсчёт (не больше `max_retry_ms`), а после хотя бы одной попытки — не меньше
    /// `first_retry_ms`, как в [`Outbox::retry_all_now`]: частые перезапуски не превращаются в
    /// пачки повторов (находка ревью PR #19). Неотправленные — сразу к отправке.
    pub fn rebase(&mut self, now_ms: u64) {
        let (first, max_retry) = (self.limits.first_retry_ms, self.limits.max_retry_ms);
        for p in self.pending.values_mut() {
            // Последний известный момент старого отсчёта.
            let then = p
                .last_try_ms
                .map_or(p.last_seen_ms, |t| t.max(p.last_seen_ms));
            let left = p.next_try_ms.saturating_sub(then).min(max_retry);
            let pause = if p.last_try_ms.is_some() {
                left.max(first)
            } else {
                left
            };
            p.last_seen_ms = now_ms;
            p.next_try_ms = now_ms.saturating_add(pause);
            p.last_try_ms = p.last_try_ms.map(|_| now_ms);
        }
    }

    /// Убирает сообщения без подтверждения дольше [`OutboxLimits::ttl_ms`] и возвращает их
    /// ключи — интерфейс показывает «не доставлено».
    ///
    /// Возраст — сумма шагов монотонных часов вперёд между вызовами. Шаг назад бывает только
    /// после перезагрузки устройства: он не засчитывается, отсчёт продолжается с нового
    /// значения (время, пока устройство было выключено, не засчитывается — срок только
    /// удлиняется). Отметки другой загрузки или устройства сюда не передавать — сначала
    /// [`Outbox::rebase`].
    pub fn expire(&mut self, now_ms: u64) -> Vec<Key> {
        let ttl = self.limits.ttl_ms;
        for p in self.pending.values_mut() {
            let step = now_ms.saturating_sub(p.last_seen_ms);
            p.age_ms = p.age_ms.saturating_add(step);
            p.last_seen_ms = now_ms;
        }
        let old: Vec<Key> = self
            .pending
            .iter()
            .filter(|(_, p)| p.age_ms >= ttl)
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

/// Окна уже полученных сообщений у получателя: отсекают дубли от повторной отправки.
///
/// У каждого собеседника своё окно на `capacity` последних ID; самый старый вытесняется
/// только из окна того же собеседника. Так один собеседник потоком сообщений не вытеснит
/// чужие ID — он может «промыть» лишь своё окно и увидеть свои же дубли. Окно должно покрывать
/// всё, что собеседник может повторить за [`OutboxLimits::ttl_ms`]; очередь отправителя держит
/// не больше [`OutboxLimits::max_messages`] (1000), [`Seen::DEFAULT_CAPACITY`] — с запасом.
/// Число окон равно числу собеседников: окно создаётся только для расшифрованного сообщения
/// известного контакта, при удалении контакта — [`Seen::forget`].
#[derive(Clone)]
pub struct Seen {
    capacity: usize,
    peers: BTreeMap<MailboxId, Window>,
}

#[derive(Clone, Default)]
struct Window {
    set: BTreeSet<MessageId>,
    order: VecDeque<MessageId>,
}

impl core::fmt::Debug for Seen {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(
            f,
            "Seen {{ peers: {}, len: {}, capacity: {} }}",
            self.peers.len(),
            self.len(),
            self.capacity
        )
    }
}

impl Seen {
    /// Ёмкость окна одного собеседника по умолчанию.
    pub const DEFAULT_CAPACITY: usize = 10_000;

    /// Пустые окна по `capacity` ID на собеседника (не меньше одного).
    #[must_use]
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            peers: BTreeMap::new(),
        }
    }

    /// Сколько ID во всех окнах.
    #[must_use]
    pub fn len(&self) -> usize {
        self.peers
            .values()
            .fold(0_usize, |n, w| n.saturating_add(w.set.len()))
    }

    /// Пусто ли.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.peers.is_empty()
    }

    /// `true` — сообщение пришло впервые (показать); `false` — дубль (не показывать).
    /// В обоих случаях получатель отправляет [`Body::Delivered`].
    ///
    /// [`Body::Delivered`]: crate::message::Body::Delivered
    pub fn first_time(&mut self, key: Key) -> bool {
        let (peer, id) = key;
        let w = self.peers.entry(peer).or_default();
        if !w.set.insert(id) {
            return false;
        }
        w.order.push_back(id);
        while w.order.len() > self.capacity {
            if let Some(old) = w.order.pop_front() {
                w.set.remove(&old);
            }
        }
        true
    }

    /// Забывает окно собеседника (контакт удалён).
    pub fn forget(&mut self, peer: &MailboxId) {
        self.peers.remove(peer);
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
        for _ in 0..5 {
            o.attempted(&(BOB, id(1)), 0);
            o.attempted(&(ALICE, id(2)), 0);
        }
        o.retry_all_now(Some(&BOB), MIN);
        let due: Vec<Key> = o.due(MIN).iter().map(|d| d.key).collect();
        assert_eq!(due, vec![(BOB, id(1))]);
        o.retry_all_now(None, MIN + 1);
        assert_eq!(o.due(MIN + 1).len(), 2);
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

    const HOUR: u64 = 3600 * 1000;

    /// Вызывает `expire` каждый час от `from` до `to` включительно; возвращает истёкшие.
    fn expire_hourly(o: &mut Outbox, from: u64, to: u64) -> Vec<Key> {
        let mut out = Vec::new();
        let mut t = from;
        while t <= to {
            out.extend(o.expire(t));
            t = t.saturating_add(HOUR);
        }
        out
    }

    #[test]
    fn expires_after_ttl() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let ttl = OutboxLimits::DEFAULT.ttl_ms;
        o.push((BOB, id(1)), vec![1], 0).unwrap();
        assert_eq!(expire_hourly(&mut o, 0, ttl - HOUR).len(), 0);
        o.push((BOB, id(2)), vec![2], ttl - HOUR).unwrap();
        assert_eq!(o.expire(ttl), vec![(BOB, id(1))]);
        assert_eq!(o.len(), 1);
        assert_eq!(o.bytes(), 1);
        assert_eq!(o.expire(0).len(), 0, "часы назад — ничего не истекает");
    }

    /// Находка ревью PR #17: очередь, восстановленная на устройстве с другим отсчётом часов,
    /// после `rebase` не истекает разом; неотправленное — сразу к отправке.
    #[test]
    fn rebase_after_restore() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let k = (BOB, id(1));
        let fresh = (BOB, id(2));
        o.push(k, vec![1], HOUR).unwrap();
        o.attempted(&k, HOUR);
        assert_eq!(o.expire(2 * HOUR).len(), 0);
        o.push(fresh, vec![2], 2 * HOUR).unwrap();
        let uptime = 30 * 24 * HOUR;
        o.rebase(uptime);
        assert_eq!(
            o.expire(uptime).len(),
            0,
            "часы другой загрузки — не возраст"
        );
        let due: Vec<Key> = o.due(uptime).iter().map(|d| d.key).collect();
        assert_eq!(due, vec![fresh], "неотправленное — сразу");
        assert_eq!(
            o.due(uptime + MIN).len(),
            2,
            "отправленное — через first_retry_ms"
        );
        assert_eq!(o.pending.get(&k).map(|p| p.age_ms), Some(HOUR));
        assert_eq!(
            expire_hourly(&mut o, uptime, uptime.saturating_add(6 * 24 * HOUR)),
            vec![]
        );
        assert_eq!(
            o.expire(uptime.saturating_add(7 * 24 * HOUR - HOUR)),
            vec![k]
        );
    }

    /// Находка ревью PR #19: `rebase` сохраняет оставшуюся паузу повтора и не даёт повторять
    /// чаще `first_retry_ms`, сколько бы раз его ни вызвали.
    #[test]
    fn rebase_keeps_backoff() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let k = (BOB, id(1));
        o.push(k, vec![1], 0).unwrap();
        for _ in 0..6 {
            o.attempted(&k, 0); // пауза 32 мин
        }
        o.rebase(5 * MIN); // новый отсчёт: прошло 0 мин старого — осталось 32
        assert_eq!(o.due(5 * MIN + 32 * MIN - 1).len(), 0);
        assert_eq!(o.due(5 * MIN + 32 * MIN).len(), 1);
        // Пауза уже истекла в старом отсчёте — всё равно не раньше first_retry_ms.
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push(k, vec![1], 0).unwrap();
        o.attempted(&k, 0);
        assert_eq!(o.expire(10 * HOUR).len(), 0);
        for t in [100, 200, 300] {
            o.rebase(t);
            assert_eq!(o.due(t).len(), 0, "частые rebase — не пачки повторов");
        }
        assert_eq!(o.due(300 + MIN).len(), 1);
        o.retry_all_now(None, 300 + 10);
        assert_eq!(
            o.due(300 + 10).len(),
            0,
            "и retry_all_now не раньше first_retry_ms"
        );
        // Пауза не больше max_retry_ms, даже если старый отсчёт дал «далёкое будущее».
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push(k, vec![1], 0).unwrap();
        o.attempted(&k, u64::MAX - 1);
        o.rebase(0);
        assert_eq!(o.due(HOUR).len(), 1);
    }

    /// Находки ревью PR #15 и #16: возраст — только реально прошедшее монотонное время;
    /// перезагрузка (часы с нуля) не старит очередь, сколько бы раз ни повторялась.
    #[test]
    fn reboot_does_not_age_queue() {
        let t = 5 * 24 * HOUR;
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let k = (BOB, id(1));
        o.push(k, vec![1], t).unwrap();
        for _ in 0..10 {
            assert_eq!(o.expire(0).len(), 0, "перезагрузка: часы с нуля");
            assert_eq!(o.expire(HOUR).len(), 0);
        }
        assert_eq!(o.pending.get(&k).map(|p| p.age_ms), Some(10 * HOUR));
        assert_eq!(
            expire_hourly(&mut o, HOUR, 7 * 24 * HOUR - 9 * HOUR),
            vec![k]
        );
    }

    #[test]
    fn seen_drops_duplicates_per_peer() {
        let mut s = Seen::new(2);
        assert!(s.first_time((BOB, id(1))));
        assert!(!s.first_time((BOB, id(1))));
        assert!(s.first_time((ALICE, id(1))), "чужой ящик — другой ключ");
        assert!(s.first_time((BOB, id(2))));
        assert_eq!(s.len(), 3);
        assert!(s.first_time((BOB, id(3))));
        assert!(
            s.first_time((BOB, id(1))),
            "вытеснен самый старый у того же собеседника"
        );
        s.forget(&BOB);
        assert_eq!(s.len(), 1);
        let mut one = Seen::new(0);
        assert!(one.first_time((BOB, id(1))));
        assert!(!one.first_time((BOB, id(1))), "ёмкость 0 становится 1");
    }

    /// Находка ревью PR #14: поток от одного собеседника не вытесняет чужие ID.
    #[test]
    fn flood_from_one_peer_keeps_others() {
        let mut s = Seen::new(2);
        assert!(s.first_time((BOB, id(1))));
        for n in 0..=255 {
            s.first_time((ALICE, id(n)));
        }
        assert!(!s.first_time((BOB, id(1))));
    }

    /// Находка ревью PR #14: враждебный узел, отвечающий `NO_QUEUE` на каждый запрос,
    /// не обходит паузу повторов.
    #[test]
    fn retry_all_now_is_rate_limited() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let k = (BOB, id(1));
        o.push(k, vec![1], 0).unwrap();
        o.retry_all_now(None, 0);
        assert_eq!(o.due(0).len(), 1, "новое — сразу");
        o.attempted(&k, 0);
        for _ in 0..3 {
            o.attempted(&k, 0);
        }
        o.retry_all_now(None, 10);
        assert_eq!(o.due(10).len(), 0, "не чаще first_retry_ms");
        assert_eq!(o.due(MIN).len(), 1, "но без экспоненциальной паузы (8 мин)");
        o.retry_all_now(None, 100 * MIN);
        assert_eq!(o.due(100 * MIN).len(), 1);
    }

    /// Находка ревью PR #14: перезагрузка (монотонные часы с нуля) не замораживает сообщение.
    #[test]
    fn clock_jump_does_not_freeze() {
        let year = 365 * 24 * 3600 * 1000;
        let ttl = OutboxLimits::DEFAULT.ttl_ms;
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        let k = (BOB, id(1));
        o.push(k, vec![1], year).unwrap(); // часы ушли на год вперёд
        o.attempted(&k, year);
        // Часы вернули.
        assert_eq!(
            o.due(0).len(),
            1,
            "срок повтора в далёком будущем — повторить"
        );
        o.attempted(&k, 0);
        assert_eq!(o.due(0).len(), 0);
        o.retry_all_now(None, 0);
        assert_eq!(o.due(MIN).len(), 1);
        assert_eq!(o.expire(0).len(), 0);
        assert_eq!(
            expire_hourly(&mut o, 0, ttl),
            vec![k],
            "после возврата часов срок идёт дальше"
        );

        // Попытка «в будущем», время создания в прошлом.
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push(k, vec![1], 0).unwrap();
        o.attempted(&k, year);
        o.retry_all_now(None, 5);
        assert_eq!(o.due(5).len(), 1);
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
        outbox.retry_all_now(None, MIN);
        send_due(&mut outbox, &mut node, MIN);
        // Таймер тоже повторил — у получателя два экземпляра.
        send_due(&mut outbox, &mut node, 3 * MIN);

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
