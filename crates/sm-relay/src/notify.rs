//! Bounded, transient WAIT subscriptions; no history, counters or telemetry.
use crate::store::QueueId;
use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, PoisonError, Weak};
use std::time::Duration;

#[derive(Default)]
pub(crate) struct Registry(Mutex<HashMap<QueueId, Vec<Weak<Signal>>>>);
#[derive(Default)]
struct Signal {
    ready: Mutex<bool>,
    changed: Condvar,
}
pub(crate) struct Subscription<'a> {
    registry: &'a Registry,
    ids: Vec<QueueId>,
    signal: Arc<Signal>,
}
impl Registry {
    pub(crate) fn subscribe(&self, ids: &[QueueId]) -> Subscription<'_> {
        let signal = Arc::new(Signal::default());
        let mut unique = ids.to_vec();
        unique.sort_unstable();
        unique.dedup();
        let mut all = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        for id in &unique {
            all.entry(*id).or_default().push(Arc::downgrade(&signal));
        }
        Subscription {
            registry: self,
            ids: unique,
            signal,
        }
    }
    pub(crate) fn notify(&self, id: &QueueId) {
        let all = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(waiters) = all.get(id) {
            for signal in waiters.iter().filter_map(Weak::upgrade) {
                *signal.ready.lock().unwrap_or_else(PoisonError::into_inner) = true;
                signal.changed.notify_one();
            }
        }
    }
}
impl Subscription<'_> {
    pub(crate) fn wait(&self, timeout: Duration) -> bool {
        let mut ready = self
            .signal
            .ready
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        if !*ready {
            ready = self
                .signal
                .changed
                .wait_timeout(ready, timeout)
                .map_or_else(|e| e.into_inner().0, |(g, _)| g);
        }
        std::mem::replace(&mut *ready, false)
    }
}
impl Drop for Subscription<'_> {
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
    #[test]
    fn unrelated_queues_do_not_wake_and_drop_removes_entries() {
        let r = Registry::default();
        let s = r.subscribe(&[[1; 32], [1; 32], [2; 32]]);
        r.notify(&[3; 32]);
        assert!(!s.wait(Duration::ZERO));
        r.notify(&[2; 32]);
        assert!(s.wait(Duration::ZERO));
        assert!(!s.wait(Duration::ZERO));
        drop(s);
        assert!(r.0.lock().unwrap().is_empty());
    }
    #[test]
    fn notification_before_sleep_is_not_lost() {
        let r = Registry::default();
        let s = r.subscribe(&[[1; 32]]);
        r.notify(&[1; 32]);
        assert!(s.wait(Duration::ZERO));
    }
}
