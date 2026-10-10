# Платформы и распространение приложения (факты)

По [DOCS-SOURCE-DATE]. Дата сбора: 2026-10-09, дополнено 2026-10-10.

| Факт | Источник · дата |
|---|---|
| Google: с 2026-09-30 приложения незарегистрированных разработчиков не ставятся из участвующих магазинов в отдельных регионах; глобально — в 2027 | [FAQ Android developer verification](https://developer.android.com/developer-verification/guides/faq) · обновлено 2026-05-11, 2026-06-18, 2026-07-15 |
| Прямая установка APK и сторонние магазины не из списка в первой фазе не затронуты | там же · 2026-06-18 |
| «Расширенный режим» для установки от незарегистрированных разработчиков — с августа 2026, включается один раз, с ожиданием 24 ч | там же · 2026-06-18, 2026-03-23 |
| Установка через ADB не меняется, 24-часовое ожидание к ней не относится | там же · 2026-03-23 |
| Объявление программы и «advanced flow» | [Android Developers Blog](https://android-developers.googleblog.com/2026/03/android-developer-verification.html) · 2026-03 |
| F-Droid против программы проверки разработчиков | [F-Droid](https://f-droid.org/en/2026/02/24/open-letter-opposing-developer-verification.html) · 2026-02-24 |
| App Store в РФ доступен ~50% с 2026-07-16; совпало с истечением ультиматума ФАС к Apple; установка в обход App Store в РФ официально невозможна | [Meduza](https://meduza.io/en/feature/2026/07/20/roskomnadzor-says-it-isn-t-blocking-the-app-store-but-russia-s-outage-started-the-day-after-its-ultimatum-to-apple-expired) · 2026-07-20 |
| Max и RuStore обязательны к предустановке с 2025-09-01 | [Meduza](https://meduza.io/news/2025/08/21/messendzher-max-stanut-predustanavlivat-na-vse-smartfony-v-rossii-s-1-sentyabrya) · 2025-08-21 |
| При досмотре телефона внимание привлекают редкие программы шифрования и приложения, маскирующиеся под системные | [Газета.Ru](https://www.gazeta.ru/business/news/2026/05/05/28392697.shtml) · 2026-05-05 |
| OONI Probe — открытый инструмент проверки блокировок; веб-тест ограничен 90 с, чтобы не тратить мобильный трафик | [OONI](https://ooni.org/support/ooni-probe-mobile/) · на 2026-10-09 |
| OONI предупреждает: это не инструмент приватности, использование может нести риски в отдельных странах | [OONI testing instructions](https://ooni.org/support/ooni-outreach-kit/files/OONI%20Probe%20testing%20instructions%20%28Interactive%29.pdf) · на 2026-10-09 |
| Signal Secure Backups: зашифрованный бэкап, 64-символьный ключ восстановления хранится только у пользователя | [Signal blog](https://signal.org/blog/introducing-secure-backups/) · 2025-09-08 |
| Signal: каждое связанное устройство — отдельный получатель; сервер хранит до 1000 недоставленных сообщений на устройство, удаляет старше 60 дней | [Security StackExchange](https://security.stackexchange.com/questions/246598/signal-messaging-app-how-does-sync-on-connected-devices-work) · ответ 2022-11 (неофициально) |
| Приложение для Android 12 (API 31) и новее объявляет `BLUETOOTH_SCAN` (поиск), `BLUETOOTH_ADVERTISE` (видимость), `BLUETOOTH_CONNECT` (связь); это разрешения времени выполнения — пользователь подтверждает их в приложении | [Android Developers: Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) · на 2026-10-10 |
| Значение атрибута BLE — не больше 512 байт; максимальный MTU на Android — 517 байт | [Punch Through: BLE throughput, part 4](https://punchthrough.com/ble-throughput-part-4/) · на 2026-10-10 |
| UniFFI — генератор привязок Rust для Kotlin, Swift, Python; на crates.io есть версия 0.31.1 | [crates.io: uniffi](https://crates.io/crates/uniffi/0.31.1), [руководство UniFFI](https://mozilla.github.io/uniffi-rs) · на 2026-10-10 |
| Rust-код протокола libsignal — лицензия AGPL-3.0-only (SPDX в исходниках) | [libsignal: rust/protocol/src/protocol.rs](https://github.com/signalapp/libsignal/blob/main/rust/protocol/src/protocol.rs) · на 2026-10-10 |
| libsignal не публикуется на crates.io: запрос на публикацию открыт в репозитории | [signalapp/libsignal#490](https://github.com/signalapp/libsignal/issues/490) · на 2026-10-10 |
| libsignal v0.105.0 (коммит `4beb029d8a94…`): `libsignal-protocol` зависит от `spqr` (постквантовый ратчет) из отдельного git-репозитория `signalapp/SparsePostQuantumRatchet`, тег v1.6.0 (коммит `06959b4708f9…`); `spqr` на crates.io нет | [libsignal v0.105.0: Cargo.toml](https://github.com/signalapp/libsignal/blob/v0.105.0/Cargo.toml), [crates.io index: spqr — 404](https://index.crates.io/sp/qr/spqr) · на 2026-10-10 |
| `libsignal-protocol` и `spqr` при сборке генерируют код из `.proto` через `prost-build`, которому нужна программа `protoc`; без неё сборка падает | [libsignal v0.105.0: rust/protocol/build.rs](https://github.com/signalapp/libsignal/blob/v0.105.0/rust/protocol/build.rs), [prost-build: sourcing protoc](https://docs.rs/prost-build/#sourcing-protoc) · проверено сборкой 2026-10-10 |
| Граф `libsignal-protocol` v0.105.0 — 178 крейтов; из запрещённых ADR-1 напрямую тянут: `libsignal-core` (`sha2`, `curve25519-dalek`, `x25519-dalek`), `libsignal-protocol` (`aes`, `aes-gcm-siv`, `ctr`, `hkdf`, `hmac`, `sha2`), `signal-crypto` (`aes`, `cbc`, `ctr`, `hkdf`, `hmac`, `sha1`, `sha2`), `spqr` (`hkdf`, `sha2`); ML-KEM — `libcrux-ml-kem` 0.0.10 | `cargo tree` по тегу v0.105.0 · 2026-10-10 |
