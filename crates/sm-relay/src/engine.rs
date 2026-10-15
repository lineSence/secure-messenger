//! Independent data shards with one small admission/ticket control plane.
use crate::{
    budget::Budget,
    namespace::Namespace,
    pass::{self, Caller},
    quota::Quotas,
    store::{Limits, PassId, QueueId, SharedBlob, Store, StoreError},
};
use std::sync::{
    Arc, Mutex, MutexGuard, PoisonError,
    atomic::{AtomicUsize, Ordering},
};

pub(crate) struct Engine {
    shards: Vec<Mutex<Store>>,
    control: Mutex<Store>,
    names: Arc<Namespace>,
    maintenance_turn: AtomicUsize,
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
                    Mutex::new(Store::with_resources(
                        shard_limits,
                        Arc::clone(&budget),
                        Arc::clone(&quotas),
                    ))
                })
                .collect(),
            control: Mutex::new(Store::with_budget(limits, Arc::clone(&budget))),
            names: Namespace::new(budget),
            maintenance_turn: AtomicUsize::new(0),
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

    fn shard(&self, sender: &QueueId) -> MutexGuard<'_, Store> {
        // Config validates a nonempty power-of-two shard count; fallback remains fail-closed.
        self.shards
            .get(self.index(sender))
            .unwrap_or(&self.control)
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
    }
    pub(crate) fn control(&self) -> MutexGuard<'_, Store> {
        self.control.lock().unwrap_or_else(PoisonError::into_inner)
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
    pub(crate) fn fetch(&self, recipient: &QueueId, now: u64) -> Result<SharedBlob, StoreError> {
        let mut s = self.shard(&pass::sender_id(recipient));
        s.maintain(now, 1);
        s.fetch_shared(recipient, now)
    }
    pub(crate) fn fetch_start(&self, recipient: &QueueId) -> usize {
        self.shard(&pass::sender_id(recipient))
            .fetch_start(recipient)
    }
    pub(crate) fn advance_fetch(&self, recipient: &QueueId, next: usize) {
        self.shard(&pass::sender_id(recipient))
            .advance_fetch(recipient, next);
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
        // Round robin across shards AND control; no all-shard lock and no full population scan.
        for _ in 0..steps {
            let i = self
                .maintenance_turn
                .fetch_add(1, Ordering::Relaxed)
                .checked_rem(self.shards.len().saturating_add(1))
                .unwrap_or(0);
            let target = self.shards.get(i).unwrap_or(&self.control);
            target
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .maintain(now, 1);
        }
        self.shards
            .iter()
            .chain(std::iter::once(&self.control))
            .any(|s| {
                s.lock()
                    .unwrap_or_else(PoisonError::into_inner)
                    .maintenance_pending(now)
            })
    }
    #[cfg(test)]
    pub(crate) fn total_bytes(&self) -> usize {
        self.shards
            .iter()
            .map(|s| {
                s.lock()
                    .unwrap_or_else(PoisonError::into_inner)
                    .total_bytes()
            })
            .sum()
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
}
