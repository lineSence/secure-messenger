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
    counter
        .try_update(Ordering::AcqRel, Ordering::Acquire, |v| {
            v.checked_add(n).filter(|&v| v <= max)
        })
        .is_ok()
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
            self.queues.fetch_sub(queues, Ordering::AcqRel);
            return None;
        }
        if !take(&self.bytes, bytes, self.limits.total_bytes) {
            self.queues.fetch_sub(queues, Ordering::AcqRel);
            self.messages.fetch_sub(messages, Ordering::AcqRel);
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
        self.queues.fetch_sub(queues, Ordering::AcqRel);
        self.messages.fetch_sub(messages, Ordering::AcqRel);
        self.bytes.fetch_sub(bytes, Ordering::AcqRel);
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
