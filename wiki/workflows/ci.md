# Конвейер проверки (шаг 0)

Файл: `.github/workflows/ci.yml`. Итоговая проверка — `ci-ok` (зелёная, только если зелёные все задачи). Решения: ADR-15, ADR-20, ADR-26, ADR-27, ADR-28.

| Задача | Что делает | Блокирует |
|---|---|---|
| rust | `cargo fmt --check`, `cargo clippy -D warnings` (строгие линты в `Cargo.toml`: `unsafe_code = forbid`, без `unwrap`/индексации/переполнений вне тестов), `cargo test` | да |
| supply-chain | cargo-deny (уязвимости, лицензии, источники, запрет openssl и своей/запрещённой криптографии — ADR-1; запреты `[bans]` проверяются и для отдельного workspace `fuzz/`), cargo-audit (оба Cargo.lock), cargo-vet, OSV-Scanner | да |
| semgrep | свои правила `.semgrep/rules.yml` (своя крипто, слабый RNG, срез по длине из данных, процессы, http://, секреты в логах) + реестр `p/rust`; до них `scripts/check-crypto-bans.mjs` — списки запрещённых крейтов в `deny.toml` и в правилах semgrep совпадают | да |
| fuzz | cargo-fuzz, каждая цель 120 с | да |
| docs | `node scripts/sek-lint.mjs` | да |
| changes | решает, нужен ли полный прогон: push в `main`, где изменена только документация (`wiki/`, `design/`, `memory/`, `*.md` в корне), → rust, supply-chain, semgrep, fuzz, kani пропускаются (ADR-27). В PR — полный прогон, кроме одного случая: push изменил только вердикты (`.github/ai-review/manual/`), а свежих ревью меньше двух → не запускается ничего, `ci-ok` пишет «⏸ ждём второе ревью» и остаётся красной (ADR-28). Порог `min_models_ok` берётся из базовой ветки; битый `pr-N.json` → предупреждение и полный прогон (причину назовёт ai-review) | да |
| ai-review | `scripts/ai-review.mjs` (берётся из базовой ветки): правила — `.github/ai-review/INSTRUCTIONS.md`; 2 режима — вердикт чата или 2 модели Dahl по API (`.github/ai-review.json`); critical/high — блок | да, только в PR |

## Локально (перед PR)
```
cargo fmt --all && cargo clippy --workspace --all-targets --locked -- -D warnings && cargo test --workspace --locked
cargo deny --locked check && cargo deny --manifest-path fuzz/Cargo.toml --config deny.toml --locked check bans && cargo vet --locked
node scripts/check-crypto-bans.mjs
semgrep scan --config .semgrep/ --config p/rust --error --metrics=off .
cd fuzz && cargo +nightly fuzz run frame_parse -- -max_total_time=60
node scripts/ai-review.mjs --dry-run
```

## Что нужно от владельца
1. Для режима API — секрет `DAHL_API_KEY`: Settings → Secrets and variables → Actions → New repository secret. Без секрета и без вердикта чата задача ai-review красная (так задумано: проверка не отключается молча).
2. GitHub Pro не нужен (решение владельца 2026-10-09): защиты ветки нет, действует core-правило [CODE-CI-GREEN] (`AGENTS.md`).

## ИИ-ревью: два режима
- **API.** Задан секрет `DAHL_API_KEY` → скрипт отправляет diff моделям `deepseek-ai/DeepSeek-V4-Flash-0731` и `zai-org/GLM-5.3-Flash` (Dahl, OpenAI-совместимый API).
- **Чат (без API).** Владелец пишет модели с доступом к GitHub: «Прочитай `.github/ai-review/INSTRUCTIONS.md` в `lineSence/secure-messenger` и выполни ревью PR #N». Модель записывает вердикт в `.github/ai-review/manual/pr-N.json` (или выдаёт JSON — тогда его коммитит агент). Нужны вердикты 2 разных моделей для одного `reviewed_head`. Если после ревью изменился код — вердикт устарел.
- Приоритет: свежий вердикт чата → иначе API → иначе красный.
- **Второе ревью (ADR-28).** Коммит первого вердикта CI не запускает: полный прогон начинается, когда в `pr-N.json` есть 2 свежих ревью по одному коду. Прогон, начатый на коммите кода, коммит вердикта отменяет (`cancel-in-progress`) — так и задумано: без двух ревью `ci-ok` всё равно красная.
- **Сейчас `mode: "manual"`** (решение владельца 2026-10-09): API не вызывается, нужен вердикт чата. Причины: у бесплатного аккаунта Dahl — 429 и обрыв потока через ~300 с; GLM не отключает рассуждения; провайдер кэширует одинаковые запросы (`ai-security-review.md`). Вернуть `auto`, когда API заработает.
- Агент-автор кода **не пишет** файлы в `.github/ai-review/manual/` сам — только коммитит JSON, полученный владельцем из чата, без правок. Ограничение: CI не может проверить, какая модель на самом деле дала вердикт; режим API надёжнее.
- Настройки и правила ревью читаются из базовой ветки: PR не может ослабить ревью сам себе.

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
