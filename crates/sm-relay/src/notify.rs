//! Addressed readiness notifications. A subscription queues at most one wake, never a history.
use crate::store::QueueId;
use mio::Waker;
use std::{
    collections::{HashMap, VecDeque},
    sync::{
        Arc, Mutex, PoisonError, Weak,
        atomic::{AtomicBool, Ordering},
    },
};

pub(crate) struct WakeQueue {
    pub(crate) waker: Arc<Waker>,
    pending: Mutex<VecDeque<usize>>,
}
impl WakeQueue {
    pub(crate) fn new(waker: Arc<Waker>) -> Arc<Self> {
        Arc::new(Self {
            waker,
            pending: Mutex::new(VecDeque::new()),
        })
    }
    #[cfg(test)]
    pub(crate) fn drain(&self) -> Vec<usize> {
        self.pending
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .drain(..)
            .collect()
    }
    pub(crate) fn drain_into(&self, empty: &mut VecDeque<usize>) {
        // The caller drains its previous batch before swapping; both capacities are reused.
        let mut pending = self.pending.lock().unwrap_or_else(PoisonError::into_inner);
        std::mem::swap(&mut *pending, empty);
    }
}
#[derive(Default)]
pub(crate) struct Registry(Mutex<HashMap<QueueId, Vec<Weak<Signal>>>>);
struct Signal {
    ready: AtomicBool,
    queue: Arc<WakeQueue>,
    token: usize,
}
pub(crate) struct Subscription {
    registry: Arc<Registry>,
    ids: Vec<QueueId>,
    signal: Arc<Signal>,
}
impl Registry {
    pub(crate) fn subscribe(
        self: &Arc<Self>,
        ids: &[QueueId],
        queue: Arc<WakeQueue>,
        token: usize,
    ) -> Subscription {
        let signal = Arc::new(Signal {
            ready: AtomicBool::new(false),
            queue,
            token,
        });
        let mut unique = ids.to_vec();
        unique.sort_unstable();
        unique.dedup();
        let mut all = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        for id in &unique {
            all.entry(*id).or_default().push(Arc::downgrade(&signal));
        }
        Subscription {
            registry: Arc::clone(self),
            ids: unique,
            signal,
        }
    }
    pub(crate) fn notify(&self, id: &QueueId) {
        let all = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(waiters) = all.get(id) {
            for s in waiters.iter().filter_map(Weak::upgrade) {
                if !s.ready.swap(true, Ordering::AcqRel) {
                    s.queue
                        .pending
                        .lock()
                        .unwrap_or_else(PoisonError::into_inner)
                        .push_back(s.token);
                    let _ = s.queue.waker.wake();
                }
            }
        }
    }
}
impl Subscription {
    pub(crate) fn take_ready(&self) -> bool {
        self.signal.ready.swap(false, Ordering::AcqRel)
    }
}
impl Drop for Subscription {
    fn drop(&mut self) {
        let mut all = self
            .registry
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        for id in &self.ids {
            if let Some(waiters) = all.get_mut(id) {
                waiters.retain(|w| w.upgrade().is_some_and(|s| !Arc::ptr_eq(&s, &self.signal)));
                if waiters.is_empty() {
                    all.remove(id);
                }
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn setup() -> (mio::Poll, Arc<Registry>, Arc<WakeQueue>) {
        let p = mio::Poll::new().unwrap();
        let q = WakeQueue::new(Arc::new(Waker::new(p.registry(), mio::Token(1)).unwrap()));
        (p, Arc::new(Registry::default()), q)
    }
    #[test]
    fn addressed_and_deduplicated_wakes() {
        let (_p, r, q) = setup();
        let s = r.subscribe(&[[1; 32], [1; 32], [2; 32]], Arc::clone(&q), 3);
        r.notify(&[3; 32]);
        assert_eq!(q.drain(), Vec::<usize>::new());
        for _ in 0..1000 {
            r.notify(&[2; 32]);
        }
        assert_eq!(q.drain(), vec![3]);
        assert!(s.take_ready());
        assert!(!s.take_ready());
        r.notify(&[1; 32]);
        assert_eq!(q.drain(), vec![3]);
        drop(s);
        assert!(r.0.lock().unwrap().is_empty());
    }
    #[test]
    fn notify_before_park_is_not_lost() {
        let (_p, r, q) = setup();
        let s = r.subscribe(&[[1; 32]], Arc::clone(&q), 7);
        r.notify(&[1; 32]);
        assert!(s.take_ready());
        assert_eq!(q.drain(), vec![7]);
    }
    #[test]
    fn reusable_drain_keeps_new_notifications() {
        let (_p, r, q) = setup();
        let s = r.subscribe(&[[1; 32]], Arc::clone(&q), 7);
        let mut batch = VecDeque::new();
        for _ in 0..1000 {
            r.notify(&[1; 32]);
            q.drain_into(&mut batch);
            assert_eq!(batch.pop_front(), Some(7));
            assert!(batch.is_empty());
            assert!(s.take_ready());
        }
        q.drain_into(&mut batch);
        assert!(batch.is_empty());
    }
}
