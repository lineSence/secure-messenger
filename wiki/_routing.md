# Маршрутизация знаний (L1)

Полная таблица «ключевые слова → файлы». Таблица триггеров в AGENTS.md — её подмножество.

```yaml
triggers:
  - id: architecture
    keywords: [архитектура, устройство, компоненты, угрозы, модель угроз, клиент, android, формат сообщений, architecture]
    load: [wiki/architecture/overview.md]
  - id: decisions
    keywords: [решение, выбор, ADR, почему так, decision]
    load: [wiki/architecture/decisions.md]
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
  - id: sek
    keywords: [sek, знания, inbox, promote, revoke, правило, wiki, lint]
    load: [wiki/workflows/sek.md]
```
