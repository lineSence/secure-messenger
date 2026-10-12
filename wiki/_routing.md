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
    keywords: [android, ios, apk, google play, app store, верификация разработчиков, sideload, adb, бэкап, устройства, OONI, SMS-разрешения, ограниченные настройки, Wi-Fi Aware, NAN, Wi-Fi Direct, P2P]
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
    keywords: [транспорт, блокировки, ТСПУ, DPI, белые списки, VLESS, Reality, XHTTP, Xray, sing-box, TURN, Tor, Snowflake, Hysteria, AmneziaWG, dnstt, DNS-туннель, slipstream, MasterDnsVPN, НСДИ, Delta Chat, Briar, Meshtastic, SMS, СМС, охлаждение SIM, Яндекс, Yandex Cloud, дата-центр, ЦОД, VK Cloud, Selectel, Cloud.ru, MWS, K2 Cloud, S3, transport]
    load: [wiki/references/transport-ru-2026.md]
  - id: crypto
    keywords: [криптография, шифрование, Signal, libsignal, PQXDH, SPQR, MLS, OpenMLS, vodozemac, SimpleX, ключи, метаданные, sealed sender, crypto, sm-signal, protoc]
    load: [wiki/references/crypto.md, wiki/architecture/signal.md]
  - id: calls
    keywords: [звонок, звонки, голос, голосовые, аудио, Opus, WebRTC, RingRTC, jitter, качество звонка, трафик звонка, P2P, ICE, STUN, SFU, принимать звонки]
    load: [wiki/references/calls.md, wiki/architecture/decisions.md]
  - id: server
    keywords: [сервер, хостинг, VPS, ретранслятор, relay, облако, санкции, server, hosting, узел, sm-relay, очередь, TTL, деплой, запуск узла, systemd, swap, SSH-туннель]
    load: [wiki/references/server-hosting.md, wiki/architecture/relay.md, wiki/workflows/relay-deploy.md]
  - id: censorship-world
    keywords: [цензура, другие страны, Китай, GFW, Великий файрвол, Иран, Туркменистан, Беларусь, Мьянма, Пакистан, Афганистан, Geedge, отключения интернета, Freedom House, Access Now]
    load: [wiki/references/censorship-world.md]
  - id: forensics
    keywords: [изъятие, криминалист, Мобильный криминалист, МКО, Oxygen, Cellebrite, UFED, GrayKey, BFU, AFU, граница, досмотр телефона, принуждение, стирание, setUnlockedDeviceRequired, FLAG_SECURE]
    load: [wiki/references/forensics.md, wiki/references/crypto.md]
  - id: legal
    keywords: [закон, право, ОРИ, ФСБ, штраф, СОРМ, законопроект, legal]
    load: [wiki/references/legal-ru.md]
  - id: mvp
    keywords: [план, MVP, этапы, шаги, roadmap, разработка, релиз, версия, alpha]
    load: [wiki/workflows/mvp.md, wiki/workflows/releases.md]
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
