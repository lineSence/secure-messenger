# secure-messenger — инструкция агента (L0)

Отвечай на русском. Владелец — один человек, проект для личного пользования; разбирается в технике, ждёт выводов по делу, без воды и жаргона без пояснений.
Проект: мессенджер со сквозным шифрованием для общения в РФ во время блокировок, приложение **Uncensored** (`app.uncensored`). Пользователи — 2–5 человек на Android; код пишут только ИИ-агенты.
Состояние: первый выпуск `v0.0.1-alpha` — переписка 1:1 по Bluetooth рядом, только текст; что дальше — заглушки «Скоро» в `releases/v0.0.1-alpha.md` и пункты в `wiki/workflows/releases.md`. Код: ядро на Rust (`crates/`: `sm-core` — форматы сообщений и кадров, очередь, хранилище ключей, интерфейс транспортов; `sm-signal` — libsignal; `sm-ffi` — привязки UniFFI; `sm-relay` — узел-реле с очередями зашифрованных блобов, в приложение пока не подключён), приложение — Kotlin/Compose (`android/`), фаззинг — `fuzz/`. Документация — `wiki/`, макеты — `design/`.
Порядок: документация (`wiki/`, `design/`, `*.md`) — прямо в `main` (ADR-27); код — ветка и PR, слияние после зелёного `ci-ok` (ADR-79: тесты JVM, detekt, Semgrep, actionlint, zizmor); релиз — ИИ-ревью по ADR-58 и подпись по тегу (ADR-76).

## Знания (SEK)
Категории: rules (правила с ID), references (факты с источником и датой), architecture, workflows.
Знания грузятся по требованию, не заранее.
1. Задача совпала с триггером ниже → читай указанный файл.
2. Не совпала → `wiki/_routing.md`.
3. Не нашёл → `wiki/_index.md`.
4. Перед новым правилом: `grep -rn "\[ТЕГ" wiki/` и grep по ключевым словам темы — не дублируй.

| Триггер | Файл |
|---|---|
| архитектура, устройство, компоненты, угрозы, модель угроз | `wiki/architecture/overview.md` |
| требования, ответы владельца, устройства, диагностика | `wiki/architecture/requirements.md` |
| решение, выбор, ADR, почему так | `wiki/architecture/decisions.md` |
| UI, UX, интерфейс, экран, макет, дизайн, иконки, эмодзи | `wiki/architecture/ux.md` |
| транспорт, блокировки, ТСПУ, белые списки, VLESS, Reality, XHTTP, TURN, Tor | `wiki/references/transport-ru-2026.md` |
| криптография, шифрование, Signal, MLS, ключи, метаданные | `wiki/references/crypto.md` |
| код, PR, CI, конвейер, ревью, фаззинг, модель для ревью | `wiki/workflows/ci.md`, `wiki/rules/code.md`, `wiki/references/ai-security-review.md` |
| Android, приложение, Bluetooth, BLE, Kotlin, история, хранилище | `wiki/architecture/android.md` |
| ядро, Rust, FFI, UniFFI, форматы кадров | `wiki/architecture/core.md`, `wiki/architecture/signal.md` |
| релиз, версия, тег, подпись APK, ИИ-ревью релиза | `wiki/workflows/releases.md`, `releases/` |
| сервер, хостинг, VPS, ретранслятор, relay | `wiki/references/server-hosting.md` |
| закон, ОРИ, ФСБ, штраф, СОРМ | `wiki/references/legal-ru.md` |
| источник, дата, факт, исследование, проверь в интернете | `wiki/rules/docs.md` |
| sek, знания, inbox, promote, revoke, правило, lint | `wiki/workflows/sek.md` |

## Самообучение (SEK), полный протокол — `wiki/workflows/sek.md`
- REGISTER: неожиданность / обход / недокументированное поведение / поправка владельца → 3 строки в `memory/inbox.md`.
- PROMOTE draft → validated (сам): повтор или 3+ записи → правило с ID и «Переносимость» в `wiki/rules/` или факт в `wiki/references/`, удалить из inbox. В ответе: `📚 Promoted: [TAG] → файл`.
- validated → core: только после явного «да» владельца. Этот файл сам не редактируй.
- Правило младше 7 дней впервые применяется → `🆕 Using recently promoted: [TAG]`. `/revoke [TAG]` → удалить, перенести текст в `memory/revoked.md`.
- `.sek/manifest.json` ведёт библиотека sek-library — не редактируй.

## Critical Rules (core)
<!-- Формат строки: - [AREA-NAME] правило + короткое «почему». Добавляется только с явного «да» владельца. -->
- [DOCS-SOURCE-DATE] Факт о внешнем мире — только со ссылкой и датой (для живых страниц — «на ГГГГ-ММ-ДД»); без них в wiki не вносить и в ответе не приводить. Почему: требование владельца, 2026-10-09.
- [UI-MOCKUP-FIRST] Вариант UI/UX (экран, поток, навигация) не принимается без интерактивного макета: кликабельный HTML в `design/mockups/`, проверенный в браузере; решение по UI — только после того, как владелец посмотрел макет. Почему: требование владельца, 2026-10-09.
- [CODE-CI-GREEN] Код попадает в `main` только через PR с зелёным `ci-ok`; красную проверку не обходить, а исправлять причину; документацию можно коммитить напрямую. Почему: защиты ветки нет (без GitHub Pro), да владельца 2026-10-09; подробно — `wiki/rules/code.md`.
- Перед завершением задачи: ссылки и даты у новых фактов в `wiki/references/` на месте и `node scripts/sek-lint.mjs`.
