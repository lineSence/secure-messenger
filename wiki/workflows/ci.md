# Конвейер проверки (шаг 0)

Файл: `.github/workflows/ci.yml`. Итоговая проверка — `ci-ok` (зелёная, только если зелёные все задачи). Решения: ADR-15, ADR-20, ADR-26.

| Задача | Что делает | Блокирует |
|---|---|---|
| rust | `cargo fmt --check`, `cargo clippy -D warnings` (строгие линты в `Cargo.toml`: `unsafe_code = forbid`, без `unwrap`/индексации/переполнений вне тестов), `cargo test` | да |
| supply-chain | cargo-deny (уязвимости, лицензии, источники, запрет openssl), cargo-audit (оба Cargo.lock), cargo-vet, OSV-Scanner | да |
| semgrep | свои правила `.semgrep/rules.yml` (своя крипто, слабый RNG, срез по длине из данных, процессы, http://, секреты в логах) + реестр `p/rust` | да |
| fuzz | cargo-fuzz, каждая цель 120 с | да |
| docs | `node scripts/sek-lint.mjs` | да |
| ai-review | `scripts/ai-review.mjs`: diff PR → 2 модели через OpenRouter (`.github/ai-review.json`); critical/high — блок | да, только в PR |

## Локально (перед PR)
```
cargo fmt --all && cargo clippy --workspace --all-targets --locked -- -D warnings && cargo test --workspace --locked
cargo deny --locked check && cargo vet --locked
semgrep scan --config .semgrep/ --config p/rust --error --metrics=off .
cd fuzz && cargo +nightly fuzz run frame_parse -- -max_total_time=60
node scripts/ai-review.mjs --dry-run
```

## Что нужно от владельца
1. Секрет `OPENROUTER_API_KEY`: Settings → Secrets and variables → Actions → New repository secret. Без него задача ai-review красная (так задумано: проверка не отключается молча).
2. По желанию — GitHub Pro: тогда включить защиту `main` с обязательной проверкой `ci-ok`. Без Pro действует правило [CODE-CI-GREEN] (`wiki/rules/code.md`).

## Как агенту узнать результат
Токен GitHub MCP не читает check runs, поэтому задача `ci-ok` пишет в PR комментарий: таблица задач и выдержки из логов упавших. Читать: `pull_request_read` → `get_comments`. Быстрый признак — `mergeable_state` PR: `clean` — всё зелёное, `unstable` — что-то упало.

## Как агенту чинить красный CI
- clippy / тест / фаззер — исправить код; находку фаззера сохранить как юнит-тест.
- cargo-deny / audit / OSV — обновить или заменить зависимость; игнор уязвимости — только через ADR.
- cargo-vet — `cargo vet certify` после реального просмотра кода или импорт аудитов доверенных организаций; `cargo vet add-exemption` — только с обоснованием в PR.
- ИИ-ревью — исправить с воспроизводящим тестом; ложное срабатывание — тест, доказывающий безопасность, и запись в `.github/ai-review-waivers.json`.

## Бюджет
Бесплатный план GitHub: 2 000 минут Actions в месяц для приватных репозиториев (`ai-security-review.md`). Один прогон — примерно 6 задач по 2–8 минут; следить за расходом, при нехватке — сократить фаззинг в PR и перенести длинный фаззинг в ночной запуск.
