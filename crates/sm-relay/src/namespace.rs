//! Capability namespace admission. Never hold this lock while acquiring a Store lock.
use crate::{
    budget::{Budget, IndexBudget},
    store::{QueueId, StoreError},
};
use std::{
    collections::HashSet,
    sync::{Arc, Mutex, PoisonError},
};
struct State {
    ids: HashSet<QueueId>,
    index: IndexBudget,
}
pub(crate) struct Namespace {
    state: Mutex<State>,
    budget: Arc<Budget>,
}
pub(crate) struct Names {
    owner: Arc<Namespace>,
    recipient: QueueId,
    sender: QueueId,
}
impl Namespace {
    pub(crate) fn new(budget: Arc<Budget>) -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(State {
                ids: HashSet::new(),
                index: IndexBudget::default(),
            }),
            budget,
        })
    }
    pub(crate) fn reserve(
        self: &Arc<Self>,
        recipient: QueueId,
        sender: QueueId,
    ) -> Result<Names, StoreError> {
        let mut s = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        if recipient == sender || s.ids.contains(&recipient) || s.ids.contains(&sender) {
            return Err(StoreError::Collision);
        }
        let count = s.ids.len().saturating_add(2);
        s.index
            .ensure(&self.budget, count, 128)
            .ok_or(StoreError::Quota)?;
        s.ids.insert(recipient);
        s.ids.insert(sender);
        Ok(Names {
            owner: Arc::clone(self),
            recipient,
            sender,
        })
    }
}
impl Drop for Names {
    fn drop(&mut self) {
        let mut s = self
            .owner
            .state
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        s.ids.remove(&self.recipient);
        s.ids.remove(&self.sender);
    }
}
