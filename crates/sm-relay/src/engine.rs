//! Independent data shards with one small admission/ticket control plane.
use crate::{
    budget::Budget,
    namespace::Namespace,
    pass::{self, Caller},
    quota::Quotas,
    store::{Limits, PassId, QueueId, SharedBlob, Store, StoreError},
};
use std::{
    collections::VecDeque,
    ops::{Deref, DerefMut},
    sync::{
        Arc, Mutex, MutexGuard, PoisonError, TryLockError,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

#[derive(Clone, Copy)]
pub(crate) struct Route {
    id: QueueId,
    shard: usize,
}
struct Shard {
    store: Mutex<Store>,
    due: AtomicU64,
    scheduled: AtomicBool,
}
impl Shard {
    fn new(store: Store) -> Self {
        Self {
            store: Mutex::new(store),
            due: AtomicU64::new(0),
            scheduled: AtomicBool::new(false),
        }
    }
    fn guard(&self) -> ShardGuard<'_> {
        ShardGuard {
            owner: self,
            guard: self.store.lock().unwrap_or_else(PoisonError::into_inner),
        }
    }
    fn due(&self, now: u64) -> bool {
        self.scheduled.load(Ordering::Acquire) && self.due.load(Ordering::Acquire) <= now
    }
}
pub(crate) struct ShardGuard<'a> {
    owner: &'a Shard,
    guard: MutexGuard<'a, Store>,
}
impl Deref for ShardGuard<'_> {
    type Target = Store;
    fn deref(&self) -> &Store {
        &self.guard
    }
}
impl DerefMut for ShardGuard<'_> {
    fn deref_mut(&mut self) -> &mut Store {
        &mut self.guard
    }
}
impl Drop for ShardGuard<'_> {
    fn drop(&mut self) {
        // Scheduling state only: no observations/history, and no global foreground mutex.
        if let Some(at) = self.guard.next_maintenance() {
            if self.owner.due.load(Ordering::Relaxed) != at {
                self.owner.due.store(at, Ordering::Release);
            }
            if !self.owner.scheduled.load(Ordering::Relaxed) {
                self.owner.scheduled.store(true, Ordering::Release);
            }
        } else if self.owner.scheduled.load(Ordering::Relaxed) {
            self.owner.scheduled.store(false, Ordering::Release);
        }
    }
}
struct Maintenance {
    ready: VecDeque<usize>,
    queued: Vec<bool>,
    scan_cursor: usize,
    scan_remaining: usize,
}
impl Maintenance {
    fn enqueue(&mut self, i: usize) {
        if let Some(queued) = self.queued.get_mut(i)
            && !*queued
        {
            *queued = true;
            self.ready.push_back(i);
        }
    }
}
pub(crate) struct Engine {
    shards: Vec<Shard>,
    control: Shard,
    names: Arc<Namespace>,
    maintenance: Mutex<Maintenance>,
}
impl Engine {
    pub(crate) fn new(limits: Limits, budget: Arc<Budget>, shards: usize) -> Self {
        let quotas = Quotas::new(limits);
        let shard_limits = Limits {
            new_per_hour: usize::MAX,
            ..limits
        };
        Self {
            shards: (0..shards.max(1))
                .map(|_| {
                    Shard::new(Store::with_resources(
                        shard_limits,
                        Arc::clone(&budget),
                        Arc::clone(&quotas),
                    ))
                })
                .collect(),
            control: Shard::new(Store::with_budget(limits, Arc::clone(&budget))),
            names: Namespace::new(budget),
            maintenance: Mutex::new(Maintenance {
                ready: VecDeque::with_capacity(shards.max(1).saturating_add(1)),
                queued: vec![false; shards.max(1).saturating_add(1)],
                scan_cursor: 0,
                scan_remaining: shards.max(1).saturating_add(1),
            }),
        }
    }
    fn index(&self, sender: &QueueId) -> usize {
        let mut head = [0; 8];
        if let Some(bytes) = sender.get(..8) {
            head.copy_from_slice(bytes);
        }
        let count = u64::try_from(self.shards.len()).unwrap_or(u64::MAX).max(1);
        usize::try_from(u64::from_le_bytes(head).checked_rem(count).unwrap_or(0)).unwrap_or(0)
    }

    fn at(&self, i: usize) -> &Shard {
        self.shards.get(i).unwrap_or(&self.control)
    }
    fn shard(&self, sender: &QueueId) -> ShardGuard<'_> {
        self.at(self.index(sender)).guard()
    }
    pub(crate) fn control(&self) -> ShardGuard<'_> {
        self.control.guard()
    }
    pub(crate) fn route(&self, id: &QueueId) -> Route {
        Route {
            id: *id,
            shard: self.index(&pass::sender_id(id)),
        }
    }
    pub(crate) fn open(
        &self,
        recipient: QueueId,
        pass: &PassId,
        per_hour: u16,
        now: u64,
    ) -> Result<QueueId, StoreError> {
        let sender = pass::sender_id(&recipient);
        let mut control = self.control();
        if self.shard(&sender).live_pair(&recipient, &sender, now) {
            return Ok(sender);
        }
        let names = self.names.reserve(recipient, sender)?;
        control.admit_new(pass, per_hour, now, || {
            let mut store = self.shard(&sender);
            store.create(recipient, sender, now)?;
            store.bind_names(&recipient, names);
            Ok(())
        })?;
        Ok(sender)
    }
    pub(crate) fn send(
        &self,
        sender: &QueueId,
        bytes: Vec<u8>,
        now: u64,
    ) -> Result<Option<QueueId>, StoreError> {
        let mut s = self.shard(sender);
        let rid = s.recipient(sender);
        s.send(sender, bytes, now)?;
        Ok(rid)
    }
    #[cfg(test)]
    pub(crate) fn fetch(&self, recipient: &QueueId, now: u64) -> Result<SharedBlob, StoreError> {
        self.fetch_one(&self.route(recipient), now)
    }
    pub(crate) fn fetch_one(&self, route: &Route, now: u64) -> Result<SharedBlob, StoreError> {
        let mut s = self.at(route.shard).guard();
        s.maintain(now, 1);
        let result = s.fetch_shared(&route.id, now)?;
        if result.is_some() {
            s.advance_fetch(&route.id, 1);
        }
        Ok(result)
    }
    pub(crate) fn fetch_routed(&self, route: &Route, now: u64) -> Result<SharedBlob, StoreError> {
        let mut s = self.at(route.shard).guard();
        s.maintain(now, 1);
        s.fetch_shared(&route.id, now)
    }
    pub(crate) fn fetch_start(&self, route: &Route) -> usize {
        self.at(route.shard).guard().fetch_start(&route.id)
    }
    pub(crate) fn advance_fetch(&self, route: &Route, next: usize) {
        self.at(route.shard).guard().advance_fetch(&route.id, next);
    }
    pub(crate) fn ack(&self, recipient: &QueueId, seq: u64, now: u64) -> Result<(), StoreError> {
        self.shard(&pass::sender_id(recipient))
            .ack(recipient, seq, now)
    }
    pub(crate) fn delete(&self, recipient: &QueueId, now: u64) -> Result<(), StoreError> {
        self.shard(&pass::sender_id(recipient))
            .delete(recipient, now)
    }
    pub(crate) fn issue_ticket(
        &self,
        ticket: QueueId,
        by: &Caller,
        now: u64,
    ) -> Result<(), StoreError> {
        self.control().issue_ticket(ticket, by, now)
    }
    pub(crate) fn maintain(&self, now: u64, steps: usize) -> bool {
        let mut state = self
            .maintenance
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        let total = self.shards.len().saturating_add(1);
        // Discovery and useful work have separate caps; empty shards consume no work quota.
        let visits = state.scan_remaining.min(steps).min(256);
        for _ in 0..visits {
            let i = state.scan_cursor;
            state.scan_cursor = i.saturating_add(1).checked_rem(total).unwrap_or(0);
            state.scan_remaining = state.scan_remaining.saturating_sub(1);
            if self.at(i).due(now) {
                state.enqueue(i);
            }
        }
        let scanning = state.scan_remaining != 0;
        if !scanning {
            state.scan_remaining = total;
        }
        let began = Instant::now();
        let mut remaining = steps;
        for _ in 0..steps.min(256) {
            if remaining == 0 || began.elapsed() >= Duration::from_millis(2) {
                break;
            }
            let Some(i) = state.ready.pop_front() else {
                break;
            };
            // Never block an I/O reactor behind another worker's shard/control lock.
            let target = self.at(i);
            let guard = match target.store.try_lock() {
                Ok(g) => g,
                Err(TryLockError::Poisoned(e)) => e.into_inner(),
                Err(TryLockError::WouldBlock) => {
                    state.ready.push_back(i);
                    continue;
                }
            };
            let mut s = ShardGuard {
                owner: target,
                guard,
            };
            let chunk = remaining.min(16);
            s.maintain(now, chunk);
            remaining = remaining.saturating_sub(chunk);
            if s.maintenance_pending(now) {
                state.ready.push_back(i);
            } else if let Some(queued) = state.queued.get_mut(i) {
                *queued = false;
            }
        }
        scanning || !state.ready.is_empty()
    }
    #[cfg(test)]
    pub(crate) fn total_bytes(&self) -> usize {
        self.shards.iter().map(|s| s.guard().total_bytes()).sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::{Barrier, mpsc},
        thread,
        time::Duration,
    };
    fn id(n: u64) -> QueueId {
        let mut r = [0; 32];
        r[..8].copy_from_slice(&n.to_le_bytes());
        r
    }
    fn engine(l: Limits) -> Arc<Engine> {
        Arc::new(Engine::new(l, Budget::new(l.memory_bytes), 16))
    }
    #[test]
    fn quotas_are_global_and_ack_delete_release_once() {
        let e = engine(Limits {
            queues: 2,
            messages: 2,
            total_bytes: 4,
            bytes_per_queue: 4,
            ..Limits::default()
        });
        let a = e.open(id(1), &[1; 16], 10, 0).unwrap();
        let b = e.open(id(2), &[1; 16], 10, 0).unwrap();
        assert_eq!(e.open(id(3), &[1; 16], 10, 0), Err(StoreError::Quota));
        e.send(&a, vec![1, 2], 0).unwrap();
        e.send(&b, vec![3, 4], 0).unwrap();
        assert_eq!(e.send(&a, vec![5], 0), Err(StoreError::Quota));
        let (seq, _) = e.fetch(&id(1), 0).unwrap().unwrap();
        e.ack(&id(1), seq, 0).unwrap();
        e.ack(&id(1), seq, 0).unwrap();
        e.send(&b, vec![5, 6], 0).unwrap();
        e.delete(&id(2), 0).unwrap();
        e.send(&a, vec![7, 8, 9, 10], 0).unwrap();
        assert_eq!(e.total_bytes(), 4);
    }
    #[test]
    fn namespace_collision_crosses_shards_and_is_released_on_delete() {
        let e = engine(Limits::default());
        let r = id(9);
        let s = e.open(r, &[1; 16], 10, 0).unwrap();
        assert_eq!(e.open(s, &[1; 16], 10, 0), Err(StoreError::Collision));
        e.delete(&r, 0).unwrap();
        assert!(e.open(s, &[1; 16], 10, 0).is_ok());
    }
    #[test]
    fn concurrent_new_shares_one_pass_and_global_hour_limit() {
        let e = engine(Limits {
            new_per_hour: 7,
            ..Limits::default()
        });
        let barrier = Arc::new(Barrier::new(32));
        let joins: Vec<_> = (0..32)
            .map(|n| {
                let e = Arc::clone(&e);
                let b = Arc::clone(&barrier);
                thread::spawn(move || {
                    b.wait();
                    e.open(id(n), &[1; 16], 5, 0).is_ok()
                })
            })
            .collect();
        assert_eq!(
            joins
                .into_iter()
                .map(|h| usize::from(h.join().unwrap()))
                .sum::<usize>(),
            5
        );
        assert!(e.open(id(100), &[2; 16], 100, 0).is_ok());
        assert!(e.open(id(101), &[2; 16], 100, 0).is_ok());
        assert_eq!(
            e.open(id(102), &[2; 16], 100, 0),
            Err(StoreError::RateLimited)
        );
    }
    #[test]
    fn locked_hot_shard_does_not_block_another_data_shard() {
        let engine = engine(Limits::default());
        let recipient = id(1);
        let hot = engine.open(recipient, &[1; 16], 10, 0).unwrap();
        let other = (2..1000)
            .map(id)
            .find(|r| engine.index(&pass::sender_id(r)) != engine.index(&hot))
            .unwrap();
        let sender = engine.open(other, &[1; 16], 10, 0).unwrap();
        let held = engine.shard(&hot);
        let (tx, rx) = mpsc::channel();
        let worker = Arc::clone(&engine);
        let join = thread::spawn(move || tx.send(worker.send(&sender, vec![1], 0)).unwrap());
        assert!(rx.recv_timeout(Duration::from_secs(1)).unwrap().is_ok());
        drop(held);
        join.join().unwrap();
    }

    #[test]
    fn repeated_new_does_not_consume_pass_and_expiry_frees_namespace() {
        let e = engine(Limits {
            idle_hours: 1,
            ..Limits::default()
        });
        let r = id(5);
        let s = e.open(r, &[1; 16], 1, 0).unwrap();
        assert_eq!(e.open(r, &[1; 16], 1, 0), Ok(s));
        for _ in 0..100 {
            e.maintain(1, 256);
        }
        assert_eq!(e.open(s, &[1; 16], 1, 1), Ok(pass::sender_id(&s)));
    }
    #[test]
    fn cleanup_work_is_not_diluted_by_empty_shards() {
        for shards in [1, 16, 256] {
            let l = Limits {
                blob_ttl_hours: 1,
                ..Limits::default()
            };
            let e = Engine::new(l, Budget::new(l.memory_bytes), shards);
            let sender = e.open(id(1), &[1; 16], 10, 0).unwrap();
            for _ in 0..1000 {
                e.send(&sender, vec![1], 0).unwrap();
            }
            let mut calls = 0;
            while e.total_bytes() != 0 && calls < 32 {
                e.maintain(1, 256);
                calls += 1;
            }
            assert_eq!(e.total_bytes(), 0, "shards={shards}, calls={calls}");
            assert!(calls <= 20, "shards={shards}, calls={calls}");
            let state = e.maintenance.lock().unwrap();
            assert!(state.ready.len() <= shards + 1);
        }
    }
    #[test]
    fn maintenance_skips_locked_store_and_recovers() {
        let l = Limits {
            blob_ttl_hours: 1,
            ..Limits::default()
        };
        let e = Engine::new(l, Budget::new(l.memory_bytes), 16);
        let hot = e.open(id(1), &[1; 16], 100, 0).unwrap();
        let r = (2..1000)
            .map(id)
            .find(|r| e.index(&pass::sender_id(r)) != e.index(&hot))
            .unwrap();
        let other = e.open(r, &[1; 16], 100, 0).unwrap();
        e.send(&hot, vec![1], 0).unwrap();
        e.send(&other, vec![2], 0).unwrap();
        let held = e.shard(&hot);
        assert!(e.maintain(1, 256));
        assert_eq!(e.shard(&other).total_bytes(), 0);
        assert_eq!(held.total_bytes(), 1);
        drop(held);
        for _ in 0..4 {
            e.maintain(1, 256);
        }
        assert_eq!(e.total_bytes(), 0);
    }
    #[test]
    fn single_fetch_route_preserves_cursor_and_errors() {
        let e = engine(Limits::default());
        let r = id(1);
        let route = e.route(&r);
        assert!(matches!(e.fetch_one(&route, 0), Err(StoreError::NoQueue)));
        let s = e.open(r, &[1; 16], 100, 0).unwrap();
        assert!(e.fetch_one(&route, 0).unwrap().is_none());
        e.send(&s, vec![1], 0).unwrap();
        assert!(e.fetch_one(&route, 0).unwrap().is_some());
        assert_eq!(e.fetch_start(&route), 1);
    }
}

/// Регрессия RA-05 (аудит 2026-10-15, cd74127): очистка 1000 истёкших блобов
/// не должна дорожать от числа пустых data shards. До #97 бюджет размазывался
/// по всем shard'ам: 32/266/4013 вызовов `maintain(256)` при 1/16/256.
#[cfg(test)]
mod cleanup_budget {
    use super::*;

    fn drain_rounds(shards: usize) -> usize {
        let l = Limits {
            memory_bytes: 1 << 30,
            ..Limits::default()
        };
        let e = Engine::new(l, Budget::new(l.memory_bytes), shards);
        let sid = e.open([42; 32], &[1; 16], 100, 0).unwrap();
        for _ in 0..1000 {
            e.send(&sid, vec![7], 0).unwrap();
        }
        let mut rounds = 0usize;
        while e.total_bytes() > 0 {
            e.maintain(168, 256);
            rounds = rounds.saturating_add(1);
            assert!(rounds < 10_000, "очистка не завершилась за 10000 вызовов");
        }
        rounds
    }

    #[test]
    fn cleanup_budget_does_not_scale_with_idle_shards() {
        let baseline = drain_rounds(1);
        for shards in [16, 256] {
            let rounds = drain_rounds(shards);
            assert!(
                rounds <= baseline * 2,
                "очистка зависит от числа пустых shards: {rounds} вызовов при {shards} против {baseline} при 1"
            );
        }
    }
}
