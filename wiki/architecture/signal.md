# Шифрование 1:1 — `sm-signal` (libsignal)

Код: `crates/sm-signal/`. Решения: ADR-1, ADR-2, ADR-47, ADR-55, ADR-60. Пункт 3 плана `0.0.1-alpha` (`wiki/workflows/releases.md`).

| Что | Как |
|---|---|
| Библиотека | `libsignal-protocol` из `signalapp/libsignal`, тег v0.105.0 = коммит `4beb029d8a94…` (в `Cargo.toml` — `rev`, cargo не даёт указать тег и коммит вместе); постквантовый ратчет `spqr` v1.6.0 из `signalapp/SparsePostQuantumRatchet` |
| Протокол | начало сессии — PQXDH (X25519 + Kyber1024), сообщения — Double Ratchet со SPQR; своя криптография не пишется (ADR-1) |
| Случайные числа | только `OsRng` (`rand` 0.9, функции `os_rng`) |
| Асинхронность | трейты хранилищ libsignal асинхронные; наши хранилища синхронные, поэтому будущее выполняется сразу (`now_or_never`), исполнителя нет; «не готово» — ошибка `Pending` |
| Самопроверка | `self_check()` — полный круг в памяти: набор ключей Боба, сессия Алисы, сообщение и ответ; входит в `core_self_check()` в `sm-ffi` — при сбое приложение не открывает экраны (`CoreState.Broken`) |
| Сборка | `protoc` нужен `prost-build`: `scripts/fetch-protoc.sh` скачивает protobuf v36.2 и сверяет sha256 (CI — задача `rust`; Android — `build-android-core.sh`) |
| Проверка поставок | `deny.toml`: `allow-git` — два репозитория Signal; примитивы — только как прямые зависимости крейтов Signal и внутренних связей RustCrypto (`wrappers`); граф — только наших платформ (ADR-60). `cargo vet`: импорт аудитов, доверие издателям, которым доверяют Mozilla/ISRG/Bytecode Alliance или уже доверяет проект, остальное — исключения «аудит не проведён» (ADR-55) |

## Метки поиска BLE (ADR-61, ADR-62)
`sm_signal::beacon`: метка 16 байт = первые 16 байт HMAC-SHA256(ключ, домен ‖ эпоха u64 BE), эпоха — 15 минут по времени Unix; HMAC — `CryptographicMac` из `signal-crypto` (тот же репозиторий и коммит libsignal; в графе уже был как зависимость `libsignal-protocol`). Домены: `sm-ble/v1` (ключ маяка контакта, 32 байта), `sm-pair/v1` (секрет из QR, 16 байт), `sm-add/v1` (экран «Добавить рядом», открытый ключ). `contact_tokens_around` — эпохи t−1, t, t+1. `pairing_proof`/`pairing_proof_ok` — HMAC набора ключей секретом из QR, сравнение без раннего выхода. Тест — известный ответ RFC 4231. В `sm-ffi` пока только `ble_epoch_ms` и `ble_add_nearby_token`.

## Следующие шаги
1. Ключи устройства: личный ключ и наборы ключей в `KeyStore` через `KeyWrapper` (`sm.keys`, ADR-56); свои хранилища сессий и ключей libsignal поверх него.
2. Добавление контакта (Bluetooth и QR), сверка кода безопасности (ADR-23, ADR-59).
3. Шифрование сообщения перед `Outbox`, расшифровка, подтверждение доставки.
