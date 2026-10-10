//! Обёртка ядра для Kotlin через `UniFFI` (ADR-46).
//!
//! Здесь только тонкий слой: типы и функции для Kotlin, логика — в `sm-core`.
//! Код связки (`extern "C"`) генерирует макрос `UniFFI`; своего `unsafe` здесь нет.

use sm_core::message::{Body, Message, MessageId};

uniffi::setup_scaffolding!();

/// Версия ядра.
#[uniffi::export]
#[must_use]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// Самопроверка при запуске: сообщение кодируется и разбирается обратно без потерь.
/// `false` — сборка ядра повреждена, приложение не должно работать с ключами.
#[uniffi::export]
#[must_use]
pub fn core_self_check() -> bool {
    let m = Message {
        id: MessageId([7; 16]),
        sent_at_ms: 0,
        body: Body::Text("проверка".to_owned()),
    };
    m.encode()
        .ok()
        .and_then(|buf| Message::decode(&buf).ok())
        .is_some_and(|back| back == m)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn self_check_passes() {
        assert!(core_self_check());
        assert_eq!(core_version(), "0.0.1");
    }
}
