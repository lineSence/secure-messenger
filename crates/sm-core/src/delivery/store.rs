//! Сохранение [`Outbox`] и [`Seen`] между запусками приложения (шаг хранилища клиента).
//!
//! Снимок — двоичная запись; шифрует её платформа (Android Keystore) до записи на диск:
//! в снимке ящики собеседников и сроки — метаданные. Файл с диска враждебен (кто-то мог его
//! подменить или он побился), поэтому разбор проверяет границы, память выделяется только по
//! фактическим данным, а не по числам из заголовка, и запись каноническая: из разобранного
//! снимка получается тот же снимок.
//!
//! Очередь хранит номер загрузки устройства (Android `Settings.Global.BOOT_COUNT`). Отметки
//! монотонных часов верны только внутри одной загрузки, поэтому [`Outbox::restore`] сам
//! вызывает [`Outbox::rebase`], если номер сменился, и не трогает отметки, если это перезапуск
//! процесса в той же загрузке.
//!
//! Очередь: `[версия u8 = 1][загрузка u64][число u32]` и записи по возрастанию ключа:
//! `[ящик 32][id 16][порядок u64][возраст u64][учтено u64][след. попытка u64]`
//! `[была попытка u8: 0/1][последняя попытка u64][попыток u32][блоб: u32 длина + байты]`.
//! Если попытки не было, поле «последняя попытка» — ноль.
//!
//! Окна: `[версия u8 = 1][собеседников u32]` и окна по возрастанию ящика:
//! `[ящик 32][число u32][id по 16 байт, от старого к новому]`.

use std::collections::BTreeMap;

use super::{Key, Outbox, OutboxLimits, Pending, Seen, Window};
use crate::message::MessageId;
use crate::transport::MailboxId;
use crate::wire::{Reader, WireError};

/// Версия формата снимков.
pub const STORE_VERSION: u8 = 1;

/// Байт записи очереди без блоба.
const ENTRY_FIXED: usize = 32 + 16 + 8 * 4 + 1 + 8 + 4 + 4;

/// Ошибка разбора снимка.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StoreError {
    /// Повреждённые данные: обрыв, лишние байты, недопустимое значение.
    Wire(WireError),
    /// Неизвестная версия формата.
    BadVersion,
    /// Записи не по возрастанию ключа, повтор ключа или ID, пустое окно.
    NotCanonical,
    /// Очередь больше пределов [`OutboxLimits`] (пределы уменьшились или данные подменены).
    OverLimit,
}

impl From<WireError> for StoreError {
    fn from(e: WireError) -> Self {
        Self::Wire(e)
    }
}

fn read_u32(r: &mut Reader<'_>) -> Result<u32, WireError> {
    r.array().map(u32::from_be_bytes)
}

fn read_len(r: &mut Reader<'_>) -> Result<usize, WireError> {
    usize::try_from(read_u32(r)?).map_err(|_| WireError::BadValue)
}

fn put_len(out: &mut Vec<u8>, n: usize) -> Option<()> {
    out.extend_from_slice(&u32::try_from(n).ok()?.to_be_bytes());
    Some(())
}

impl Outbox {
    /// Снимок очереди; `boot` — номер текущей загрузки устройства.
    ///
    /// `None` — длины не помещаются в формат или нарушен инвариант непустого блоба.
    /// При штатных пределах и добавлении через [`Outbox::push`] этого не бывает.
    #[must_use]
    pub fn encode(&self, boot: u64) -> Option<Vec<u8>> {
        if self.pending.values().any(|p| p.blob.is_empty()) {
            return None;
        }
        let cap = ENTRY_FIXED
            .checked_mul(self.pending.len())?
            .checked_add(self.bytes)?
            .checked_add(1 + 8 + 4)?;
        let mut out = Vec::with_capacity(cap);
        out.push(STORE_VERSION);
        out.extend_from_slice(&boot.to_be_bytes());
        put_len(&mut out, self.pending.len())?;
        for ((mailbox, id), p) in &self.pending {
            out.extend_from_slice(&mailbox.0);
            out.extend_from_slice(&id.0);
            for v in [p.seq, p.age_ms, p.last_seen_ms, p.next_try_ms] {
                out.extend_from_slice(&v.to_be_bytes());
            }
            out.push(u8::from(p.last_try_ms.is_some()));
            out.extend_from_slice(&p.last_try_ms.unwrap_or(0).to_be_bytes());
            out.extend_from_slice(&p.attempts.to_be_bytes());
            put_len(&mut out, p.blob.len())?;
            out.extend_from_slice(&p.blob);
        }
        Some(out)
    }

    /// Восстанавливает очередь из снимка с пределами `limits`.
    ///
    /// `boot` — номер текущей загрузки, `now_ms` — монотонные часы сейчас. Если снимок сделан
    /// в другой загрузке, отметки переносятся на новый отсчёт ([`Outbox::rebase`]).
    ///
    /// # Errors
    /// [`StoreError`]: снимок повреждён, не канонический или больше пределов.
    pub fn restore(
        bytes: &[u8],
        limits: OutboxLimits,
        boot: u64,
        now_ms: u64,
    ) -> Result<Self, StoreError> {
        let mut r = Reader::new(bytes);
        if r.u8()? != STORE_VERSION {
            return Err(StoreError::BadVersion);
        }
        let saved_boot = r.u64()?;
        let count = read_len(&mut r)?;
        if count > limits.max_messages {
            return Err(StoreError::OverLimit);
        }
        let mut out = Self::new(limits);
        let mut prev: Option<Key> = None;
        for _ in 0..count {
            let key: Key = (MailboxId(r.array()?), MessageId(r.array()?));
            if prev.is_some_and(|p| p >= key) {
                return Err(StoreError::NotCanonical);
            }
            prev = Some(key);
            let (seq, age_ms, last_seen_ms, next_try_ms) = (r.u64()?, r.u64()?, r.u64()?, r.u64()?);
            let last_try_ms = match (r.u8()?, r.u64()?) {
                (0, 0) => None,
                (1, t) => Some(t),
                _ => return Err(WireError::BadValue.into()),
            };
            let attempts = read_u32(&mut r)?;
            let len = read_len(&mut r)?;
            if len == 0 {
                return Err(WireError::BadValue.into());
            }
            // Предел проверяется до чтения: длина из файла не выделяет память.
            let total = out
                .bytes
                .checked_add(len)
                .filter(|t| *t <= limits.max_bytes)
                .ok_or(StoreError::OverLimit)?;
            let blob = r.bytes(len)?.to_vec();
            out.bytes = total;
            out.next_seq = out.next_seq.max(seq.saturating_add(1));
            out.pending.insert(
                key,
                Pending {
                    blob,
                    seq,
                    age_ms,
                    last_seen_ms,
                    next_try_ms,
                    last_try_ms,
                    attempts,
                },
            );
        }
        r.finish()?;
        if saved_boot != boot {
            out.rebase(now_ms);
        }
        Ok(out)
    }
}

impl Seen {
    /// Снимок окон. `None` — не бывает при разумных размерах (числа помещаются в `u32`).
    #[must_use]
    pub fn encode(&self) -> Option<Vec<u8>> {
        let cap = self
            .len()
            .checked_mul(16)?
            .checked_add(self.peers.len().checked_mul(36)?)?
            .checked_add(5)?;
        let mut out = Vec::with_capacity(cap);
        out.push(STORE_VERSION);
        put_len(&mut out, self.peers.len())?;
        for (peer, w) in &self.peers {
            out.extend_from_slice(&peer.0);
            put_len(&mut out, w.order.len())?;
            for id in &w.order {
                out.extend_from_slice(&id.0);
            }
        }
        Some(out)
    }

    /// Восстанавливает окна по `capacity` ID на собеседника. Если в снимке окно длиннее
    /// (ёмкость уменьшилась), остаются самые новые ID.
    ///
    /// # Errors
    /// [`StoreError`]: снимок повреждён или не канонический (ящики не по возрастанию, повтор ID
    /// в окне, пустое окно).
    pub fn restore(bytes: &[u8], capacity: usize) -> Result<Self, StoreError> {
        let mut r = Reader::new(bytes);
        if r.u8()? != STORE_VERSION {
            return Err(StoreError::BadVersion);
        }
        let peers = read_len(&mut r)?;
        let mut out = Self::new(capacity);
        let mut map = BTreeMap::new();
        let mut prev: Option<MailboxId> = None;
        for _ in 0..peers {
            let peer = MailboxId(r.array()?);
            if prev.is_some_and(|p| p >= peer) {
                return Err(StoreError::NotCanonical);
            }
            prev = Some(peer);
            let n = read_len(&mut r)?;
            if n == 0 {
                return Err(StoreError::NotCanonical);
            }
            // Число ID из файла проверяется по оставшимся данным до чтения.
            if n.checked_mul(16).is_none_or(|b| b > r.remaining()) {
                return Err(WireError::Truncated.into());
            }
            let mut w = Window::default();
            for _ in 0..n {
                let id = MessageId(r.array()?);
                if !w.set.insert(id) {
                    return Err(StoreError::NotCanonical);
                }
                w.order.push_back(id);
            }
            while w.order.len() > out.capacity {
                if let Some(old) = w.order.pop_front() {
                    w.set.remove(&old);
                }
            }
            map.insert(peer, w);
        }
        r.finish()?;
        out.peers = map;
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(p: u8, i: u8) -> Key {
        (MailboxId([p; 32]), MessageId([i; 16]))
    }

    fn sample() -> Outbox {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push(key(2, 1), vec![1, 2, 3], 1_000).unwrap();
        o.push(key(1, 9), vec![7; 300], 2_000).unwrap();
        o.attempted(&key(1, 9), 5_000);
        o
    }

    fn due_keys(o: &Outbox, now: u64) -> Vec<Key> {
        o.due(now).iter().map(|d| d.key).collect()
    }

    #[test]
    fn empty_blob_rejected_without_poisoning_snapshot() {
        let mut o = sample();
        let before = o.encode(42).unwrap();
        let before_seq = o.next_seq;
        let rejected = key(3, 1);
        assert_eq!(
            o.push(rejected, vec![], 9_000),
            Err(super::super::OutboxError::EmptyBlob)
        );
        assert!(!o.contains(&rejected));
        assert_eq!(o.next_seq, before_seq);
        assert_eq!(o.encode(42).unwrap(), before);
        let back = Outbox::restore(&before, OutboxLimits::DEFAULT, 42, 9_000).unwrap();
        assert_eq!(back.len(), o.len());
        assert_eq!(back.bytes(), o.bytes());
        assert_eq!(back.encode(42).unwrap(), before);
        // Ошибка имеет тот же смысл для дубликата и полной очереди.
        assert_eq!(
            o.push(key(2, 1), vec![], 0),
            Err(super::super::OutboxError::EmptyBlob)
        );
        let mut full = Outbox::new(OutboxLimits {
            max_messages: 0,
            ..OutboxLimits::DEFAULT
        });
        assert_eq!(
            full.push(rejected, vec![], 0),
            Err(super::super::OutboxError::EmptyBlob)
        );
    }

    #[test]
    fn empty_blob_cannot_be_encoded_or_restored() {
        let mut o = Outbox::new(OutboxLimits::DEFAULT);
        o.push(key(1, 1), vec![1], 0).unwrap();
        let mut legacy = o.encode(1).unwrap();
        let len_at = 13 + ENTRY_FIXED - 4;
        legacy[len_at..len_at + 4].copy_from_slice(&0u32.to_be_bytes());
        legacy.pop();
        assert_eq!(
            Outbox::restore(&legacy, OutboxLimits::DEFAULT, 1, 0).unwrap_err(),
            StoreError::Wire(WireError::BadValue)
        );
        // Защита сериализатора даже при нарушении внутреннего состояния.
        o.pending.get_mut(&key(1, 1)).unwrap().blob.clear();
        o.bytes = 0;
        assert!(o.encode(1).is_none());
        let empty = Outbox::new(OutboxLimits::DEFAULT).encode(1).unwrap();
        assert!(
            Outbox::restore(&empty, OutboxLimits::DEFAULT, 1, 0)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn outbox_roundtrip_same_boot() {
        let o = sample();
        let snap = o.encode(42).unwrap();
        let back = Outbox::restore(&snap, OutboxLimits::DEFAULT, 42, 999_999).unwrap();
        // Та же загрузка: отметки не тронуты, снимок канонический.
        assert_eq!(back.encode(42).unwrap(), snap);
        assert_eq!(back.len(), 2);
        assert_eq!(back.bytes(), 303);
        assert_eq!(
            due_keys(&back, 5_000),
            vec![key(2, 1)],
            "повтор второго ещё не наступил"
        );
    }

    #[test]
    fn outbox_rebase_on_new_boot() {
        let snap = sample().encode(1).unwrap();
        // Новая загрузка: часы начались с нуля, отметки старой загрузки к ним неприменимы.
        let back = Outbox::restore(&snap, OutboxLimits::DEFAULT, 2, 10).unwrap();
        assert_eq!(
            due_keys(&back, 10),
            vec![key(2, 1)],
            "неотправленное — сразу"
        );
        let later = due_keys(&back, 10 + OutboxLimits::DEFAULT.first_retry_ms);
        assert_eq!(later.len(), 2, "отправленное — после паузы");
    }

    #[test]
    fn outbox_new_messages_after_restore_keep_order() {
        let snap = sample().encode(7).unwrap();
        let mut back = Outbox::restore(&snap, OutboxLimits::DEFAULT, 7, 6_000).unwrap();
        back.push(key(0, 0), vec![5], 6_000).unwrap();
        // Новое сообщение — после старых, хотя его ключ меньше.
        assert_eq!(due_keys(&back, 6_000), vec![key(2, 1), key(0, 0)]);
    }

    #[test]
    fn outbox_rejects_bad_snapshots() {
        let snap = sample().encode(1).unwrap();
        let lim = OutboxLimits::DEFAULT;
        let restore = |b: &[u8]| Outbox::restore(b, lim, 1, 0);
        assert_eq!(
            restore(&[]).unwrap_err(),
            StoreError::Wire(WireError::Truncated)
        );
        let mut v = snap.clone();
        v[0] = 2;
        assert_eq!(restore(&v).unwrap_err(), StoreError::BadVersion);
        for cut in 1..snap.len() {
            assert!(restore(&snap[..cut]).is_err(), "обрыв на {cut}");
        }
        let mut v = snap.clone();
        v.push(0);
        assert_eq!(
            restore(&v).unwrap_err(),
            StoreError::Wire(WireError::TrailingBytes(1))
        );
        // Огромная длина блоба из файла — отказ до чтения, без выделения памяти.
        let mut v = snap.clone();
        let len_at = 1 + 8 + 4 + ENTRY_FIXED - 4;
        v[len_at..][..4].copy_from_slice(&u32::MAX.to_be_bytes());
        assert_eq!(restore(&v).unwrap_err(), StoreError::OverLimit);
        // Пределы уменьшились.
        let fewer = OutboxLimits {
            max_messages: 1,
            ..lim
        };
        assert_eq!(
            Outbox::restore(&snap, fewer, 1, 0).unwrap_err(),
            StoreError::OverLimit
        );
        let smaller = OutboxLimits {
            max_bytes: 100,
            ..lim
        };
        assert_eq!(
            Outbox::restore(&snap, smaller, 1, 0).unwrap_err(),
            StoreError::OverLimit
        );
        // Флаг попытки не 0/1.
        let mut v = snap.clone();
        let flag_at = 1 + 8 + 4 + 32 + 16 + 32;
        v[flag_at] = 2;
        assert_eq!(
            restore(&v).unwrap_err(),
            StoreError::Wire(WireError::BadValue)
        );
    }

    #[test]
    fn outbox_rejects_unsorted_and_duplicates() {
        let one = |k: Key| {
            let mut o = Outbox::new(OutboxLimits::DEFAULT);
            o.push(k, vec![1], 0).unwrap();
            o.encode(0).unwrap()[13..].to_vec()
        };
        let head = |n: u32| {
            let mut h = vec![STORE_VERSION];
            h.extend_from_slice(&0u64.to_be_bytes());
            h.extend_from_slice(&n.to_be_bytes());
            h
        };
        let lim = OutboxLimits::DEFAULT;
        let unsorted = [head(2), one(key(2, 0)), one(key(1, 0))].concat();
        assert_eq!(
            Outbox::restore(&unsorted, lim, 0, 0).unwrap_err(),
            StoreError::NotCanonical
        );
        let dup = [head(2), one(key(1, 0)), one(key(1, 0))].concat();
        assert_eq!(
            Outbox::restore(&dup, lim, 0, 0).unwrap_err(),
            StoreError::NotCanonical
        );
        let sorted = [head(2), one(key(1, 0)), one(key(2, 0))].concat();
        assert!(Outbox::restore(&sorted, lim, 0, 0).is_ok());
    }

    #[test]
    fn seen_roundtrip_and_truncate() {
        let mut s = Seen::new(3);
        for i in 0..5 {
            s.first_time(key(1, i));
        }
        s.first_time(key(0, 9));
        let snap = s.encode().unwrap();
        let mut back = Seen::restore(&snap, 3).unwrap();
        assert_eq!(back.encode().unwrap(), snap);
        assert!(!back.first_time(key(1, 4)), "дубль после перезапуска");
        assert!(back.first_time(key(1, 0)), "вытесненный — снова новый");
        // Ёмкость уменьшилась: остаются самые новые.
        let mut small = Seen::restore(&snap, 1).unwrap();
        assert_eq!(small.len(), 2);
        assert!(!small.first_time(key(1, 4)));
        assert!(small.first_time(key(1, 3)));
    }

    #[test]
    fn seen_rejects_bad_snapshots() {
        let mut s = Seen::new(10);
        s.first_time(key(1, 1));
        s.first_time(key(2, 1));
        let snap = s.encode().unwrap();
        for cut in 0..snap.len() {
            assert!(Seen::restore(&snap[..cut], 10).is_err(), "обрыв на {cut}");
        }
        // Огромное число ID — отказ до чтения.
        let mut v = snap.clone();
        v[1 + 4 + 32..1 + 4 + 36].copy_from_slice(&u32::MAX.to_be_bytes());
        assert_eq!(
            Seen::restore(&v, 10).unwrap_err(),
            StoreError::Wire(WireError::Truncated)
        );
        let peer = |p: u8, ids: &[u8]| {
            let mut v = vec![p; 32];
            v.extend_from_slice(&u32::try_from(ids.len()).unwrap().to_be_bytes());
            for i in ids {
                v.extend_from_slice(&[*i; 16]);
            }
            v
        };
        let head = |n: u32| [vec![STORE_VERSION], n.to_be_bytes().to_vec()].concat();
        let bad = [head(2), peer(2, &[1]), peer(1, &[1])].concat();
        assert_eq!(
            Seen::restore(&bad, 10).unwrap_err(),
            StoreError::NotCanonical
        );
        let dup = [head(1), peer(1, &[1, 1])].concat();
        assert_eq!(
            Seen::restore(&dup, 10).unwrap_err(),
            StoreError::NotCanonical
        );
        let empty = [head(1), peer(1, &[])].concat();
        assert_eq!(
            Seen::restore(&empty, 10).unwrap_err(),
            StoreError::NotCanonical
        );
        assert!(Seen::restore(&[head(1), peer(1, &[1, 2])].concat(), 10).is_ok());
    }
}
