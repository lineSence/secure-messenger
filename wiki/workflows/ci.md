# Конвейер проверки (шаг 0)

Файл: `.github/workflows/ci.yml`. Итоговая проверка — `ci-ok` (зелёная, только если зелёные все задачи). Решения: ADR-15, ADR-20, ADR-26, ADR-27, ADR-48, ADR-52 (заменил ADR-28), ADR-53, ADR-54, ADR-79, ADR-105.

| Задача | Что делает | Блокирует |
|---|---|---|
| rust | `cargo fmt --check`, `cargo clippy -D warnings` (строгие линты в `Cargo.toml`: `unsafe_code = forbid`, без `unwrap`/индексации/переполнений вне тестов), `cargo test` | да |
| supply-chain | cargo-deny (уязвимости, лицензии, источники, запрет openssl и своей/запрещённой криптографии — ADR-1; запреты `[bans]` проверяются и для отдельных workspace `fuzz/` и `tools/uniffi-bindgen/`), cargo-audit (все три Cargo.lock), cargo-vet, OSV-Scanner | да |
| semgrep | свои правила `.semgrep/rules.yml` (своя крипто, слабый RNG, срез по длине из данных, процессы, http://, секреты в логах) и `.semgrep/kotlin.yml` (логи, слабый ГСЧ, http://, режим JCA кроме AES/GCM/NoPadding, MODE_WORLD_* и внешнее хранилище, SharedPreferences, JS в WebView; исключения — `nosemgrep` с причиной) + реестры `p/rust` и `p/kotlin` (ADR-79); до них `scripts/check-crypto-bans.mjs` — списки запрещённых крейтов в `deny.toml` и в правилах semgrep совпадают | да |
| detekt | detekt-cli 1.23.8 (jar по sha256) по `android/app/src`: наборы potential-bugs, exceptions, performance, coroutines, empty-blocks, с необязательными правилами (`--all-rules`); оформление, сложность, имена, комментарии выключены (`android/detekt.yml`). Без разрешения типов (ADR-79) | да |
| workflows | actionlint 1.7.12 (tar по sha256, с shellcheck) и zizmor 1.30.1 с онлайн-проверками (`github.token`) по `.github/workflows/` (ADR-79) | да |
| fuzz-targets, fuzz | список целей — из `[[bin]]` в `fuzz/Cargo.toml` (число должно совпасть с файлами `fuzz/fuzz_targets/*.rs`), затем по задаче на цель параллельно, каждая 120 с (ADR-52) | да |
| android | APK (ADR-48): только в PR, где изменены `android/`, `crates/`, `Cargo.*`, `tools/uniffi-bindgen/`, `scripts/build-android-core.sh` или `ci.yml` — `scripts/build-android-core.sh` (ядро `arm64-v8a` + привязки UniFFI), затем `gradle assembleDebug lintDebug testDebugUnitTest` (тесты JVM `android/app/src/test`, JUnit 4, ADR-79; Gradle 9.8.1, зависимости сверяются с `android/gradle/verification-metadata.xml`); ошибка Android Lint роняет задачу (ADR-64, исключения — `android/app/lint.xml`: только `NewApi` в привязках UniFFI), отчёт при провале — артефакт `sm-lint-report`; APK — артефакт `sm-debug-apk` на 14 дней. Иначе задача успешно завершается без сборки (в PR пропуск задачи = провал `ci-ok`) | да |
| docs | `node scripts/sek-lint.mjs` | да |
| kani | не в PR (ADR-105): `.github/workflows/kani.yml` — `cargo kani -p sm-core -Z stubbing`, `cargo kani -p sm-relay -Z stubbing`; вызывается из `release.yml` по тегу (подпись APK ждёт успеха) и вручную (Actions → kani → Run workflow) | релиз |
| changes | решает, нужен ли полный прогон: push в `main`, где изменена только документация (`wiki/`, `design/`, `memory/`, `*.md` в корне), → rust, supply-chain, semgrep, fuzz пропускаются (ADR-27). В PR — полный прогон на каждый push, в том числе с одними вердиктами (ADR-52) | да |
| ai-review | в PR нет (ADR-53): ИИ-ревью — только ручное и только на релизах, см. «ИИ-ревью релиза» ниже | — |

## Локально (перед PR)
```
export PROTOC="$(bash scripts/fetch-protoc.sh)"   # для libsignal (ADR-55)
cargo fmt --all && cargo clippy --workspace --all-targets --locked -- -D warnings && cargo test --workspace --locked
cargo deny --locked check && cargo deny --manifest-path fuzz/Cargo.toml --config deny.toml --locked check bans && cargo vet --locked
cargo deny --manifest-path tools/uniffi-bindgen/Cargo.toml --config deny.toml --locked check bans
ANDROID_NDK_HOME=<NDK r28> bash scripts/build-android-core.sh && (cd android && gradle assembleDebug lintDebug testDebugUnitTest)
node scripts/check-crypto-bans.mjs
semgrep scan --config .semgrep/ --config p/rust --config p/kotlin --error --metrics=off .
java -jar detekt-cli-1.23.8-all.jar --input android/app/src --config android/detekt.yml --build-upon-default-config --all-rules
actionlint && zizmor .github/workflows/
cd fuzz && cargo +nightly fuzz run frame_parse -- -max_total_time=60
node scripts/ai-review.mjs --dry-run
```

## Что нужно от владельца
1. Ревью релиза: один промпт агента, 8 прогонов, каждый — в новом чате, модели любые (ADR-53, ADR-57; порядок — ADR-58). Первое ревью — на релизе `0.0.1-alpha`.
2. GitHub Pro не нужен (решение владельца 2026-10-09): защиты ветки нет, действует core-правило [CODE-CI-GREEN] (`AGENTS.md`).

## ИИ-ревью релиза (ADR-53, ADR-57; порядок — ADR-58)
- В PR ИИ-ревью нет: слияние — по зелёному `ci-ok` (rust, supply-chain, semgrep, fuzz, android, docs); Kani — только к релизу (ADR-105).
- Ревью — только ручное (чат), только на релизе, первое — на `0.0.1-alpha`: 8 прогонов в разных чатах с чистым контекстом — 8 вердиктов по одному коду; модели не указываются (ADR-57). critical/high блокируют релиз.
- API Dahl (`scripts/ai-review.mjs`, режим `auto`) не используется: 429 и обрыв потока через ~300 с у бесплатного аккаунта, GLM не отключает рассуждения, провайдер кэширует одинаковые запросы (`ai-security-review.md`). Правила ревью по-прежнему в `.github/ai-review/INSTRUCTIONS.md`; режим релиза в нём и проверка вердиктов в CI — отдельным PR (ADR-58 п.5).
- Объём релиза (ADR-58): один промпт на весь diff кода от прошлого тега, без деления на части (ADR-54 отклонён).
- Агент-автор кода вердиктов не пишет — только коммитит JSON, полученный владельцем из чата, без правок. Ограничение: CI не может проверить, какая модель на самом деле дала вердикт.
- Тестовые debug-APK (артефакт `sm-debug-apk`) — для проверки владельцем без ИИ-ревью; людям — только сборка релиза.

## Как агенту узнать результат
Токен GitHub MCP не читает check runs, поэтому задача `ci-ok` пишет в PR комментарий: таблица задач и выдержки из логов упавших. Читать: `pull_request_read` → `get_comments`. Быстрый признак — `mergeable_state` PR: `clean` — всё зелёное, `unstable` — что-то упало.

## Как агенту чинить красный CI
- clippy / тест / фаззер — исправить код; находку фаззера сохранить как юнит-тест.
- cargo-deny / audit / OSV — обновить или заменить зависимость; игнор уязвимости — только через ADR.
- cargo-vet — `cargo vet certify` после реального просмотра кода или импорт аудитов доверенных организаций; `cargo vet add-exemption` — только с обоснованием в PR.
- ИИ-ревью — исправить с воспроизводящим тестом; ложное срабатывание — тест, доказывающий безопасность, и запись в `.github/ai-review-waivers.json`.

## Бюджет
Коммит документации в `main` тратит ~1 минуту (changes + docs + ci-ok) вместо полного прогона (ADR-27).
Бесплатный план GitHub: 2 000 минут Actions в месяц для приватных репозиториев (`ai-security-review.md`). Один прогон — примерно 6 задач по 2–8 минут; следить за расходом, при нехватке — сократить фаззинг в PR и перенести длинный фаззинг в ночной запуск.
