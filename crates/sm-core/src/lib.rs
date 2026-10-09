//! Ядро мессенджера (шаг 1 плана MVP).
//!
//! - [`message`] — формат сообщения v1 с типом и версией (ADR-14, ADR-24);
//! - [`padding`] — выравнивание длины перед шифрованием;
//! - [`keystore`] — хранилище ключей поверх обёртки платформы (ADR-1);
//! - [`transport`] — интерфейс транспортных плагинов (ADR-12, ADR-13);
//! - [`wire`] — безопасное чтение недоверенных данных.
//!
//! Шифрование (`libsignal`, `OpenMLS`) подключается на шагах 3 и 8.

pub mod keystore;
pub mod message;
pub mod padding;
pub mod transport;
pub mod wire;

#[cfg(kani)]
mod proofs;
