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

## Ключ личности устройства (пункт 4)
`sm_signal::identity::DeviceIdentity`: `IdentityKeyPair` из libsignal (`OsRng`) и номер регистрации (14 бит, не ноль). Секрет: `[версия 1][номер регистрации u32 BE][IdentityKeyPair::serialize()]`; при разборе открытый ключ должен получаться из закрытого — иначе отказ. Хранение — `sm_core::keystore` под меткой `identity`, обёртка — платформа (`sm-ffi`: `PlatformKeyWrapper`, `PlatformKeyStorage`; объект `DeviceIdentity` с `load`/`create`, наружу — только открытый ключ 33 байта и номер регистрации). `create` никогда не перезаписывает запись: если она есть (даже повреждённая) — `Exists`.

## Следующие шаги
1. Наборы ключей (подписанный, Kyber, одноразовые) и свои хранилища сессий и ключей libsignal (ADR-63).
2. Добавление контакта (Bluetooth и QR), сверка кода безопасности (ADR-23, ADR-59).
3. Шифрование сообщения перед `Outbox`, расшифровка, подтверждение доставки.
