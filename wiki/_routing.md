# Маршрутизация знаний (L1)

Полная таблица «ключевые слова → файлы». Таблица триггеров в AGENTS.md — её подмножество.

```yaml
triggers:
  - id: architecture
    keywords: [архитектура, устройство, компоненты, угрозы, модель угроз, клиент, android, формат сообщений, architecture]
    load: [wiki/architecture/overview.md]
  - id: core
    keywords: [ядро, sm-core, формат сообщения, хранилище ключей, keystore, транспорт, плагин, выравнивание, padding, kani]
    load: [wiki/architecture/core.md]
  - id: requirements
    keywords: [требования, ответы владельца, пользователи, устройства, R-1, requirements, диагностика, тест доступности, несколько устройств, синхронизация, установка]
    load: [wiki/architecture/requirements.md]
  - id: ux
    keywords: [UI, UX, интерфейс, экран, макет, дизайн, навигация, тема, mockup, иконки, эмодзи, гайдлайн, Material, Telegram, контраст, доступность]
    load: [wiki/architecture/ux.md, wiki/references/ux-guidelines.md, wiki/rules/ui.md]
  - id: decisions
    keywords: [решение, выбор, ADR, почему так, decision]
    load: [wiki/architecture/decisions.md]
  - id: platforms
    keywords: [android, ios, apk, google play, app store, верификация разработчиков, sideload, adb, бэкап, устройства, OONI]
    load: [wiki/references/platforms.md]
  - id: mesh
    keywords: [mesh, meshtastic, meshcore, reticulum, lxmf, lora, sideband, rnode, heltec, без интернета]
    load: [wiki/references/mesh.md]
  - id: ai-dev
    keywords: [ИИ, агент, код ИИ, генерация кода, уязвимости, LLM, качество кода]
    load: [wiki/references/ai-dev.md]
  - id: ai-review
    keywords: [ревью, проверка кода, CI, конвейер, PR, слияние, clippy, cargo-deny, OSV, фаззинг, линтер, CodeQL, Semgrep, cargo-audit, cargo-vet, Kani, MobSF, модель для ревью, бенчмарк, CyberGym, воспроизводимая сборка]
    load: [wiki/workflows/ci.md, wiki/rules/code.md, wiki/references/ai-security-review.md, wiki/references/ai-dev.md]
  - id: transport
    keywords: [транспорт, блокировки, ТСПУ, DPI, белые списки, VLESS, Reality, XHTTP, Xray, sing-box, TURN, Tor, Snowflake, Hysteria, AmneziaWG, dnstt, Delta Chat, Briar, Meshtastic, transport]
    load: [wiki/references/transport-ru-2026.md]
  - id: crypto
    keywords: [криптография, шифрование, Signal, libsignal, PQXDH, SPQR, MLS, OpenMLS, vodozemac, SimpleX, ключи, метаданные, sealed sender, crypto]
    load: [wiki/references/crypto.md]
  - id: server
    keywords: [сервер, хостинг, VPS, ретранслятор, relay, облако, санкции, server, hosting]
    load: [wiki/references/server-hosting.md]
  - id: legal
    keywords: [закон, право, ОРИ, ФСБ, штраф, СОРМ, законопроект, legal]
    load: [wiki/references/legal-ru.md]
  - id: mvp
    keywords: [план, MVP, этапы, шаги, roadmap, разработка]
    load: [wiki/workflows/mvp.md]
  - id: docs-rules
    keywords: [источник, дата, факт, исследование, актуальность, проверь в интернете]
    load: [wiki/rules/docs.md]
  - id: work
    keywords: [ожидание, ждать, долгая задача, фон, параллельно, простой, пока идёт CI]
    load: [wiki/rules/work.md]
  - id: sek
    keywords: [sek, знания, inbox, promote, revoke, правило, wiki, lint]
    load: [wiki/workflows/sek.md]
```
