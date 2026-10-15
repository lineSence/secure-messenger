//! Shared admission across Store shards. These counters enforce limits; they are not exported.
use crate::store::Limits;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

pub(crate) struct Quotas {
    queues: AtomicUsize,
    messages: AtomicUsize,
    bytes: AtomicUsize,
    limits: Limits,
}
pub(crate) struct Reservation {
    pool: Arc<Quotas>,
    queues: usize,
    messages: usize,
    bytes: usize,
}
fn take(counter: &AtomicUsize, n: usize, max: usize) -> bool {
    if n == 0 {
        return true;
    }
    counter
        .try_update(Ordering::AcqRel, Ordering::Acquire, |v| {
            v.checked_add(n).filter(|&v| v <= max)
        })
        .is_ok()
}
fn give(counter: &AtomicUsize, n: usize) {
    if n != 0 {
        counter.fetch_sub(n, Ordering::AcqRel);
    }
}
impl Quotas {
    pub(crate) fn new(limits: Limits) -> Arc<Self> {
        Arc::new(Self {
            queues: AtomicUsize::new(0),
            messages: AtomicUsize::new(0),
            bytes: AtomicUsize::new(0),
            limits,
        })
    }
    pub(crate) fn reserve(
        self: &Arc<Self>,
        queues: usize,
        messages: usize,
        bytes: usize,
    ) -> Option<Reservation> {
        if !take(&self.queues, queues, self.limits.queues) {
            return None;
        }
        if !take(&self.messages, messages, self.limits.messages) {
            give(&self.queues, queues);
            return None;
        }
        if !take(&self.bytes, bytes, self.limits.total_bytes) {
            give(&self.queues, queues);
            give(&self.messages, messages);
            return None;
        }
        Some(Reservation {
            pool: Arc::clone(self),
            queues,
            messages,
            bytes,
        })
    }
    pub(crate) fn release(&self, queues: usize, messages: usize, bytes: usize) {
        give(&self.queues, queues);
        give(&self.messages, messages);
        give(&self.bytes, bytes);
    }
}
impl Reservation {
    pub(crate) fn commit(mut self) {
        self.queues = 0;
        self.messages = 0;
        self.bytes = 0;
    }
}
impl Drop for Reservation {
    fn drop(&mut self) {
        self.pool.release(self.queues, self.messages, self.bytes);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rollback_commit_zero_and_release_are_exact() {
        let p = Quotas::new(Limits {
            queues: 1,
            messages: 1,
            total_bytes: 1,
            ..Limits::default()
        });
        assert!(p.reserve(1, 2, 0).is_none());
        assert!(p.reserve(1, 1, 2).is_none());
        let reservation = p.reserve(1, 1, 1).unwrap();
        drop(reservation);
        p.reserve(1, 1, 1).unwrap().commit();
        p.reserve(0, 0, 0).unwrap().commit();
        p.release(0, 0, 0);
        assert!(p.reserve(1, 0, 0).is_none());
        assert!(p.reserve(0, 1, 0).is_none());
        assert!(p.reserve(0, 0, 1).is_none());
        p.release(1, 1, 1);
        assert!(p.reserve(1, 1, 1).is_some());
    }
    #[test]
    fn concurrent_reservations_never_exceed_cap() {
        let p = Quotas::new(Limits {
            queues: 7,
            messages: 7,
            total_bytes: 7,
            ..Limits::default()
        });
        let b = Arc::new(std::sync::Barrier::new(32));
        let threads: Vec<_> = (0..32)
            .map(|_| {
                let p = Arc::clone(&p);
                let b = Arc::clone(&b);
                std::thread::spawn(move || {
                    b.wait();
                    let reservation = p.reserve(1, 1, 1);
                    b.wait();
                    reservation
                })
            })
            .collect();
        let reservations: Vec<_> = threads
            .into_iter()
            .filter_map(|t| t.join().unwrap())
            .collect();
        assert_eq!(reservations.len(), 7);
        drop(reservations);
        assert_eq!(p.bytes.load(Ordering::Acquire), 0);
        assert_eq!(p.messages.load(Ordering::Acquire), 0);
        assert_eq!(p.queues.load(Ordering::Acquire), 0);
    }
}
