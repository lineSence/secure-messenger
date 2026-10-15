//! Explicit resource configuration; no telemetry or persistence is enabled.
use crate::server::{Config, WAIT_MAX};
use std::io;
use std::time::Duration;

/// Apply optional unsigned decimal settings, rejecting invalid and zero limits.
///
/// # Errors
/// Invalid values, inconsistent queue/payload budgets, or WAIT above wire v1's maximum.
pub fn apply(mut c: Config, lookup: impl Fn(&str) -> Option<String>) -> io::Result<Config> {
    let read = |key: &str, default: usize| -> io::Result<usize> {
        lookup(key).map_or(Ok(default), |v| {
            v.parse::<usize>()
                .ok()
                .filter(|&n| n > 0)
                .ok_or_else(|| io::ErrorKind::InvalidInput.into())
        })
    };
    c.max_connections = read("SM_RELAY_MAX_CONNECTIONS", c.max_connections)?;
    c.limits.queues = read("SM_RELAY_MAX_QUEUES", c.limits.queues)?;
    c.limits.messages = read("SM_RELAY_MAX_MESSAGES", c.limits.messages)?;
    c.limits.tickets = read("SM_RELAY_MAX_TICKETS", c.limits.tickets)?;
    c.limits.blobs_per_queue = read("SM_RELAY_BLOBS_PER_QUEUE", c.limits.blobs_per_queue)?;
    c.limits.total_bytes = read("SM_RELAY_TOTAL_BYTES", c.limits.total_bytes)?;
    c.limits.bytes_per_queue = read("SM_RELAY_BYTES_PER_QUEUE", c.limits.bytes_per_queue)?;
    c.limits.new_per_hour = read("SM_RELAY_NEW_PER_HOUR", c.limits.new_per_hour)?;
    let read_u64 = |key: &str, default: u64| -> io::Result<u64> {
        lookup(key).map_or(Ok(default), |v| {
            v.parse::<u64>()
                .ok()
                .filter(|&n| n > 0)
                .ok_or_else(|| io::ErrorKind::InvalidInput.into())
        })
    };
    c.limits.blob_ttl_hours = read_u64("SM_RELAY_BLOB_TTL_HOURS", c.limits.blob_ttl_hours)?;
    c.limits.idle_hours = read_u64("SM_RELAY_IDLE_HOURS", c.limits.idle_hours)?;
    c.idle_timeout = Duration::from_millis(read_u64(
        "SM_RELAY_TIMEOUT_MS",
        u64::try_from(c.idle_timeout.as_millis()).map_err(|_| io::ErrorKind::InvalidInput)?,
    )?);
    c.wait = Duration::from_millis(read_u64(
        "SM_RELAY_WAIT_MS",
        u64::try_from(c.wait.as_millis()).map_err(|_| io::ErrorKind::InvalidInput)?,
    )?);
    validate(&c)?;
    Ok(c)
}

pub(crate) fn validate(c: &Config) -> io::Result<()> {
    let l = c.limits;
    if c.max_connections == 0
        || c.idle_timeout.is_zero()
        || c.wait.is_zero()
        || c.wait > WAIT_MAX
        || l.queues == 0
        || l.messages == 0
        || l.tickets == 0
        || l.blobs_per_queue == 0
        || l.total_bytes == 0
        || l.bytes_per_queue == 0
        || l.bytes_per_queue > l.total_bytes
        || l.new_per_hour == 0
        || l.blob_ttl_hours == 0
        || l.idle_hours == 0
    {
        return Err(io::ErrorKind::InvalidInput.into());
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn config() -> Config {
        Config::new(([127, 0, 0, 1], 0).into())
    }
    #[test]
    fn accepts_defaults_and_rejects_bad_limits() {
        assert!(apply(config(), |_| None).is_ok());
        for v in ["0", "-1", "NaN", "184467440737095516160"] {
            assert!(
                apply(config(), |key| (key == "SM_RELAY_MAX_MESSAGES")
                    .then(|| v.to_owned()))
                .is_err()
            );
        }
        assert!(
            apply(config(), |key| (key == "SM_RELAY_WAIT_MS")
                .then(|| "25001".to_owned()))
            .is_err()
        );
    }
    #[test]
    fn larger_resource_profile_is_supported() {
        let c = apply(config(), |key| match key {
            "SM_RELAY_MAX_CONNECTIONS" => Some("1024".into()),
            "SM_RELAY_MAX_MESSAGES" => Some("1000000".into()),
            _ => None,
        })
        .unwrap();
        assert_eq!(c.max_connections, 1024);
        assert_eq!(c.limits.messages, 1_000_000);
    }
}
