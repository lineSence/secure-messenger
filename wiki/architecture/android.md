# Android-клиент

Код: `android/` (приложение), `crates/sm-ffi/` (обёртка ядра), `tools/uniffi-bindgen/` (генератор привязок), `scripts/build-android-core.sh` (сборка ядра). Решения: ADR-45, ADR-46, ADR-48, ADR-49; интерфейс — `ux.md` (UX-1…22, макет v5).

## Устройство
| Слой | Что | Где |
|---|---|---|
| Интерфейс | Kotlin + Jetpack Compose, Material 3; тема по системе | `android/app/src/main/java/dev/sm/messenger/` |
| Привязки | Kotlin-код UniFFI, генерируется при сборке, в git не хранится; вызовы через JNA | `android/app/build/generated/uniffi/` |
| Ядро | `libsm_ffi.so` (`sm-ffi` → `sm-core`), только `arm64-v8a` | `android/app/build/generated/jniLibs/` |

- `sm-ffi` — тонкая обёртка: наружу только типы UniFFI, логика — в `sm-core`. Те же строгие линты рабочего пространства (ADR-15, ADR-20), `unsafe` запрещён (код FFI генерирует макрос UniFFI).
- Генератор привязок той же версии UniFFI (`=0.32.2`) — отдельный workspace: инструмент сборки не попадает в граф зависимостей приложения; в CI его граф проверяют `cargo deny` (bans) и `cargo audit`, как `fuzz/`.
- Минимальная версия — Android 12 (API 31); компилируется с API 37 (этого требует Compose BOM 2026.09), `targetSdk` — 36: поведение API 37 включим отдельно после проверки BLE.
- Зависимости UniFFI в `cargo vet` — по ADR-50 (импорт аудитов, доверие издателям, исключения); лицензия UniFFI MPL-2.0 добавлена в `deny.toml`.
- Резервное копирование выключено (`allowBackup=false`, `data_extraction_rules`): ключи и переписка не уходят в облако и на другое устройство.

## Сборка
```
export ANDROID_NDK_HOME=<NDK r28>
rustup target add aarch64-linux-android
bash scripts/build-android-core.sh        # .so + привязки Kotlin
cd android && gradle assembleDebug        # Gradle 9.8.1
```
- Gradle-wrapper в репозитории нет (бинарники не храним) — версия Gradle задана в CI (`gradle/actions/setup-gradle`).
- CI (задача `android`, ADR-48): APK собирается только в PR, где изменены `android/`, `crates/`, `Cargo.*`, генератор или сборка; иначе задача успешно завершается без сборки (пропуск задачи в PR роняет `ci-ok`). APK — артефакт прогона `sm-debug-apk` на 14 дней.

## Следующие шаги
1. BLE-транспорт телефон-телефон (ADR-49) и проверка на двух телефонах.
2. libsignal 1:1 (ADR-47).
3. Хранилище клиента: очередь `Outbox` и окно `Seen` между запусками; ключи через Android Keystore (`KeyWrapper`).
