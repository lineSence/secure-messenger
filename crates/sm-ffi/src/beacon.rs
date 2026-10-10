//! Метки поиска Bluetooth LE для Kotlin (ADR-61): обёртки над `sm_signal::beacon`.

use sm_signal::beacon;

/// Длина эпохи меток в миллисекундах (15 минут).
#[uniffi::export]
#[must_use]
pub fn ble_epoch_ms() -> u64 {
    beacon::EPOCH_MS
}

/// Метка экрана добавления (общая для всех) в эпоху `epoch`; 16 байт. `None` — HMAC не посчитан.
#[uniffi::export]
#[must_use]
pub fn ble_add_nearby_token(epoch: u64) -> Option<Vec<u8>> {
    beacon::add_nearby_token(epoch).ok().map(|t| t.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_is_16_bytes_and_rotates() {
        let a = ble_add_nearby_token(1).unwrap();
        assert_eq!(a.len(), 16);
        assert_ne!(a, ble_add_nearby_token(2).unwrap());
        assert_eq!(ble_epoch_ms(), 900_000);
    }
}
