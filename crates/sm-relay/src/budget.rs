//! Admission reservations, not telemetry. Leases follow owned memory until its final release.
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

#[derive(Debug)]
pub(crate) struct Budget {
    limit: usize,
    used: AtomicUsize,
}
#[derive(Debug)]
pub(crate) struct Lease {
    budget: Arc<Budget>,
    bytes: usize,
}
impl Budget {
    pub(crate) fn new(limit: usize) -> Arc<Self> {
        Arc::new(Self {
            limit,
            used: AtomicUsize::new(0),
        })
    }
    pub(crate) fn reserve(self: &Arc<Self>, bytes: usize) -> Option<Lease> {
        self.used
            .try_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                n.checked_add(bytes).filter(|&v| v <= self.limit)
            })
            .ok()?;
        Some(Lease {
            budget: Arc::clone(self),
            bytes,
        })
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        self.budget.used.fetch_sub(self.bytes, Ordering::AcqRel);
    }
}
/// High-water reservations for retained hash-table capacity. Never released per deletion.
#[derive(Default)]
pub(crate) struct IndexBudget {
    slots: usize,
    leases: Vec<Lease>,
}
impl IndexBudget {
    pub(crate) fn ensure(
        &mut self,
        budget: &Arc<Budget>,
        items: usize,
        per_slot: usize,
    ) -> Option<()> {
        if items <= self.slots {
            return Some(());
        }
        let next = items.max(self.slots.checked_mul(2)?).max(1);
        let charge = next.checked_sub(self.slots)?.checked_mul(per_slot)?;
        let lease = budget.reserve(charge)?;
        self.leases.push(lease);
        self.slots = next;
        Some(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reservation_fails_closed_and_is_released_exactly_once() {
        let b = Budget::new(10);
        let l = b.reserve(7).unwrap();
        assert!(b.reserve(4).is_none());
        assert!(b.reserve(usize::MAX).is_none());
        drop(l);
        assert!(b.reserve(10).is_some());
    }
}
