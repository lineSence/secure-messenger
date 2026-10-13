//! Bounded ownership journal. All operations require caller serialization / a Kv transaction.
use super::{KEY_ID_MASK, Reader, free_id};
use crate::{
    SignalError,
    store::{Kv, Table},
};
use sm_core::keystore::Secret;

pub(super) const LIMIT: usize = 32;
pub(super) const RETENTION: u64 = 14 * 24 * 3600;
const DAY: u64 = 24 * 3600;
const KEY: &[u8] = b"cards-v1";
pub(super) const RESERVED: u8 = 0;
pub(super) const ISSUED: u8 = 1;
pub(super) const PINNED: u8 = 2;
const CONSUMED: u8 = 3;

#[derive(Clone)]
pub(super) struct Entry {
    pub ids: [u32; 3],
    expires: u64,
    pub state: u8,
}

#[derive(Default)]
pub(super) struct Registry {
    seen: u64,
    history: Vec<u64>,
    pub entries: Vec<Entry>,
}

impl Registry {
    pub fn load<K: Kv>(kv: &K) -> Result<Self, SignalError> {
        let Some(bytes) = kv
            .get(Table::CardRegistry, KEY)
            .map_err(|_| SignalError::Store)?
        else {
            return Ok(Self::default());
        };
        Self::decode(&bytes).map_err(|_| SignalError::Store)
    }

    fn decode(bytes: &[u8]) -> Result<Self, SignalError> {
        // Reject oversized values before allocating entries; strict version/length/count/IDs.
        if bytes.len() > 11 + LIMIT * (21 + 8) {
            return Err(SignalError::Store);
        }
        let mut r = Reader(bytes);
        if r.u8()? != 1 {
            return Err(SignalError::Store);
        }
        let seen = u64::from_be_bytes(r.array()?);
        let count = usize::from(r.u8()?);
        let history_count = usize::from(r.u8()?);
        if count > LIMIT || history_count > LIMIT {
            return Err(SignalError::Store);
        }
        let mut entries: Vec<Entry> = Vec::with_capacity(count);
        for _ in 0..count {
            let ids = [r.u32()?, r.u32()?, r.u32()?];
            let expires = u64::from_be_bytes(r.array()?);
            let state = r.u8()?;
            if state > CONSUMED
                || expires < RETENTION
                || expires > seen.saturating_add(RETENTION)
                || ids.iter().any(|id| *id == 0 || *id > KEY_ID_MASK)
                || entries
                    .iter()
                    .any(|e| e.ids.iter().zip(ids).any(|(a, b)| *a == b))
            {
                return Err(SignalError::Store);
            }
            entries.push(Entry {
                ids,
                expires,
                state,
            });
        }
        let mut history = Vec::with_capacity(history_count);
        for _ in 0..history_count {
            let at = u64::from_be_bytes(r.array()?);
            if at > seen || history.last().is_some_and(|prev| *prev > at) {
                return Err(SignalError::Store);
            }
            history.push(at);
        }
        r.end()?;
        Ok(Self {
            seen,
            history,
            entries,
        })
    }

    pub fn save<K: Kv>(&self, kv: &K) -> Result<(), SignalError> {
        let mut bytes = vec![1];
        bytes.extend_from_slice(&self.seen.to_be_bytes());
        bytes.push(u8::try_from(self.entries.len()).map_err(|_| SignalError::Store)?);
        bytes.push(u8::try_from(self.history.len()).map_err(|_| SignalError::Store)?);
        for e in &self.entries {
            for id in e.ids {
                bytes.extend_from_slice(&id.to_be_bytes());
            }
            bytes.extend_from_slice(&e.expires.to_be_bytes());
            bytes.push(e.state);
        }
        for at in &self.history {
            bytes.extend_from_slice(&at.to_be_bytes());
        }
        kv.put(Table::CardRegistry, KEY, &bytes)
            .map_err(|_| SignalError::Store)
    }

    pub fn cleanup<K: Kv>(&mut self, kv: &K, at: u64) -> Result<(), SignalError> {
        if at < self.seen {
            return Err(SignalError::Store);
        }
        self.seen = at;
        self.history
            .retain(|issued| at.saturating_sub(*issued) < DAY);
        let mut keep = Vec::new();
        for index in 0..self.entries.len() {
            let mut e = self.entries.get(index).ok_or(SignalError::Store)?.clone();
            if e.state == ISSUED {
                let [pre_id, _, kyber_id] = e.ids;
                // Missing one-time keys means libsignal may already have used this card.
                // Never age-delete its signed key, even after a partial failed decrypt.
                let pre = kv
                    .get(Table::PreKey, &pre_id.to_be_bytes())
                    .map_err(|_| SignalError::Store)?
                    .map(Secret::new);
                let kyber = kv
                    .get(Table::KyberPreKey, &kyber_id.to_be_bytes())
                    .map_err(|_| SignalError::Store)?
                    .map(Secret::new);
                if pre.is_none() || kyber.is_none() {
                    e.state = CONSUMED;
                }
            }
            if e.state == RESERVED || (e.state == ISSUED && at >= e.expires) {
                // Journal reclamation BEFORE deleting: a failed delete/final journal write
                // must remain retryable, not be mistaken for a consumed/live card.
                self.entries.get_mut(index).ok_or(SignalError::Store)?.state = RESERVED;
                self.save(kv)?;
                delete_keys(kv, &e)?;
            } else {
                keep.push(e);
            }
        }
        self.entries = keep;
        self.save(kv)
    }

    pub fn reserve<K: Kv>(&mut self, kv: &K, at: u64) -> Result<[u32; 3], SignalError> {
        self.cleanup(kv, at)?;
        if self.entries.len() >= LIMIT || self.history.len() >= LIMIT {
            return Err(SignalError::Store);
        }
        let mut ids = [0; 3];
        for (index, table) in [Table::PreKey, Table::SignedPreKey, Table::KyberPreKey]
            .into_iter()
            .enumerate()
        {
            let mut selected = None;
            for _ in 0..16 {
                let id = free_id(kv, table)?;
                if !self.entries.iter().any(|e| e.ids.get(index) == Some(&id)) {
                    selected = Some(id);
                    break;
                }
            }
            *ids.get_mut(index).ok_or(SignalError::Store)? = selected.ok_or(SignalError::Store)?;
        }
        let expires = at.checked_add(RETENTION).ok_or(SignalError::Store)?;
        self.entries.push(Entry {
            ids,
            expires,
            state: RESERVED,
        });
        self.history.push(at);
        // Write ownership before any private key: failed issuance is recoverable and quota-charged.
        self.save(kv)?;
        Ok(ids)
    }

    pub fn consumed<K: Kv>(kv: &K, kyber: u32) -> Result<(), SignalError> {
        let mut registry = Self::load(kv)?;
        if let Some(e) = registry
            .entries
            .iter_mut()
            .find(|e| e.ids.last() == Some(&kyber))
        {
            e.state = CONSUMED;
            registry.save(kv)?;
        }
        // Legacy keys have no owner journal: do not guess or mass-delete other records.
        Ok(())
    }
}

pub(super) fn delete_keys<K: Kv>(kv: &K, e: &Entry) -> Result<(), SignalError> {
    for (table, id) in [Table::PreKey, Table::SignedPreKey, Table::KyberPreKey]
        .into_iter()
        .zip(e.ids)
    {
        kv.delete(table, &id.to_be_bytes())
            .map_err(|_| SignalError::Store)?;
    }
    Ok(())
}
