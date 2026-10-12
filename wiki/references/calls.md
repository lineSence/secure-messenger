# Звонки: как делают другие и что выбрать (факты и расчёты)

Ключевые слова: звонок, голос, Opus, WebRTC, RingRTC, SFU, P2P, TURN, качество, трафик, битрейт, jitter, DTX, CBR.
По [DOCS-SOURCE-DATE]. Дата сбора: 2026-10-12. Решения — ADR-149, экраны — UX-31 (`design/mockups/ui-v15.html`). Раздел «Расчёт» и «Выводы» — наши, не внешние факты.

## Факты: приложения
| Факт | Источник · дата |
|---|---|
| Signal: звонки на форке WebRTC; RingRTC — Rust-библиотека поверх WebRTC, лицензия AGPL-3.0 | [signalapp/ringrtc](https://github.com/signalapp/ringrtc) · на 2026-10-12 |
| Signal: по умолчанию звонок может идти напрямую (раскрывает IP собеседнику); IP не отправляется тому, кто не в контактах; «Always Relay Calls» не отдаёт IP вообще | [Signal blog](https://signal.org/blog/signal-video-calls) · на 2026-10-12; [Tenable](https://medium.com/tenable-techblog/turning-signal-app-into-a-coarse-tracking-device-643eb4298447) · на 2026-10-12 |
| Signal Android: «Use less data for calls» — Never / Mobile data only / Wi-Fi and mobile data | [Signal Support](https://support.signal.org/hc/en-us/articles/360056044831-Data-Usage-Options-Wi-Fi-Cellular) · на 2026-10-12 |
| Signal: при канале 32 кбит/с звонок рваный, даже с «Use less data»; WhatsApp и Telegram на том же канале работают (жалобы 2021–2023, разработчик подтвердил, что есть работа над этим) | [Signal-Android #10758](https://github.com/signalapp/Signal-Android/issues/10758) · на 2026-10-12 (слабый источник: отзывы пользователей) |
| Signal: групповые звонки — SFU, который не видит содержимого; кадры шифруют клиенты (RingRTC); SFU выбирает слои по оценке полосы (googcc, transport-cc) | [Signal blog](https://signal.org/blog/how-to-build-encrypted-group-calls) · на 2026-10-12 |
| Threema: Opus 48 кГц; в звонках 1:1 принудительно постоянный битрейт (из-за утечек у VBR); видео VP9/VP8/H.264; групповые — через SFU, участники шифруют всё между собой | [Threema Cryptography Whitepaper](https://threema.com/assets/documents/threema-cryptography-whitepaper.pdf) · на 2026-10-12 |
| Telegram: «Use Less Data» режет расход звонка ещё на 25–30% ценой качества; нейросеть подстраивает качество под связь | [Telegram blog](https://telegram.org/blog/calls) · 2017-03-30 |
| Telegram: звонки на tgcalls (WebRTC); libtgvoip в официальном Android-клиенте не поддерживается с июня 2022 | [sip.tg](https://www.sip.tg/en/docs/gateway/tg2sip) · на 2026-10-12 (слабый источник) |
| SimpleX: звонки WebRTC; по умолчанию через TURN-реле, чтобы скрыть IP от собеседника (реле видит длительность); P2P — опция; можно указать свои ICE-серверы | [SimpleX docs](https://simplex.chat/docs/guide/audio-video-calls.html) · на 2026-10-12 |
| Element: MatrixRTC + LiveKit (SFU), шифрование между участниками | [Element blog](https://element.io/blog/end-to-end-encrypted-voice-and-video-for-self-hosted-community-users/) · 2025-04-04 |
| WhatsApp: MLow — по умолчанию для звонков 1:1, обычный Opus — запасной вариант (черновик сообщества WACRG, не официальная спецификация) | [WACRG](https://wacrg.org/spec/encodings/opus) · на 2026-10-12 (слабый источник) |
| Meta MLow: 6 кбит/с в широкой полосе, примерно вдвое выше оценка POLQA, чем у Opus; Opus на 6 кбит/с — только узкая полоса; встроенная коррекция потерь у Opus нужна от ≈19 кбит/с при 10% потерь. Кодек проприетарный | [Meta Engineering](https://engineering.fb.com/2024/06/13/web/mlow-metas-low-bitrate-audio-codec) · 2024-06-13 |
| Telegram Desktop: голосовые сообщения на 64 кбит/с, ≈0,5 МБ/мин (просьба снизить) | [tdesktop #6708](https://github.com/telegramdesktop/tdesktop/issues/6708) · на 2026-10-12 (слабый источник) |

## Факты: кодек и транспорт
| Факт | Источник · дата |
|---|---|
| Opus: 6–510 кбит/с, кадры 2,5–60 мс, постоянный (CBR) и переменный (VBR) битрейт; стандарт IETF | [opus-codec.org](https://www.opus-codec.org/) · на 2026-10-12; [RFC 6716](https://datatracker.ietf.org/doc/html/rfc6716) · 2012-09 |
| Opus 1.5: нейро-маскировка потерь (+≈1 МБ, ≈1% ядра), улучшители LACE/NoLACE на 6–12 кбит/с (+≈1,6 МБ, 0,15%/0,75% ядра), DRED — до 1 с избыточности за 12–32 кбит/с (+≈2 МБ); DRED не стандартизован, версия 1.5 не совместима с будущей и требует доработки jitter-буфера | [Opus 1.5](https://opus-codec.org/demo/opus-1.5) · на 2026-10-12 |
| Длины пакетов при VBR выдают произнесённые фразы: в среднем 50% точности, у части фраз выше 90%; рекомендации по VBR с SRTP | [Wright и др., IEEE S&P 2008](https://ieeexplore.ieee.org/document/4531143/) · 2008; [RFC 6562](https://datatracker.ietf.org/doc/rfc6562/) · 2012-03 |
| WebRTC поверх TCP/TLS: TURN/TLS на 443 — самый совместимый путь; TCP при потерях задерживает весь поток (блокировка очереди), поздний звук отбрасывается; RFC 8835 тоже предупреждает о блокировке очереди | [Daily docs](https://docs.daily.co/docs/guides/privacy-and-security/corporate-firewalls-nats-allowed-ip-list) · на 2026-10-12; [RFC 8835](https://datatracker.ietf.org/doc/html/rfc8835) · 2021-01; [LiveKit community](https://community.livekit.io/t/seeking-strategies-to-optimize-connection-reliability-and-audio-quality-for-users-in-strict-gcc-network-environments-experiencing-aggressive-udp-throttling/1655) · на 2026-10-12 (слабый источник) |
| Android 14+: разрешение полноэкранного уведомления (экран входящего звонка поверх блокировки) Google Play выдаёт сам только приложениям для звонков и будильников; остальным — разрешение пользователя | [Android Open Source Project](https://source.android.com/docs/core/permissions/fsi-limits) · на 2026-10-12; [Play Console Help](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en) · с 2025-01-22 |
| libwebrtc: готовая сборка Android AAR — 46,9 МБ (несколько архитектур) | [webrtc-sdk/libwebrtc](https://github.com/webrtc-sdk/libwebrtc/releases) · 2026-08-24 |
| str0m — WebRTC на Rust без потоков и блокировок (sans-IO); отдельный крейт ICE | [str0m](https://github.com/algesten/str0m) · на 2026-10-12 |
| РКН с 2025-08-13 частично ограничивает звонки в Telegram и WhatsApp | [Интерфакс](https://www.interfax.ru/russia/1054011) · 2025-08-13 |

Контекст сети в РФ (полностью зашифрованный UDP режется на МегаФоне, настоящий TLS проходит; белые списки) — `transport-ru-2026.md`.

## Расчёт трафика (наш, не замер)
Допущения: Opus с постоянным битрейтом, без пауз (DTX); говорят оба; считаются оба направления вместе; заголовки IPv4 (IPv6 — ещё +20 байт на пакет). Накладные на пакет: UDP + свой AEAD ≈ 48 байт, RTP/SRTP ≈ 50, TLS/TCP ≈ 62 + подтверждения TCP.

| Кадр | 8 кбит/с | 16 кбит/с | 24 кбит/с | 32 кбит/с |
|---|---|---|---|---|
| 20 мс | 0,41 МБ/мин | 0,53 | 0,65 | 0,77 |
| 40 мс | 0,26 | 0,38 | 0,50 | 0,62 |
| 60 мс | 0,22 | 0,34 | 0,46 | 0,58 |

Через TLS/TCP — примерно на 25% больше (0,29 / 0,49 / 0,87 для «Экономия» 8@60, «Обычное» 16@40, «Высокое» 24@20).

## Выводы (наши)
- При 8 кбит/с и кадре 20 мс заголовки в несколько раз больше голоса: снижение битрейта в 4 раза (32 → 8) даёт экономию только ×1,9. Главный рычаг экономии — длина кадра (60 мс), цена — задержка выше ≈40 мс и заметнее потери.
- Постоянный битрейт во всех режимах: экономия — выбором ступени, а не VBR/DTX. «Авто» переключает ступени, а не плавно меняет битрейт.
- Звонок через узел — основной путь (сети РФ, IP не виден собеседнику); P2P — исключение по решению владельца 2026-10-12, на проверке.
- Входящий звонок при опросе 2→15 мин (ADR-85) невозможен: нужен режим «Принимать звонки» (постоянный `WAIT`).
- Узлу голос почти ничего не стоит (≈25–50 МБ за час звонка через узел); не хватает потокового режима (сейчас только `POST` с `Content-Length`, `WAIT` до 25 с).

## Не проверено
- Реальные МБ/мин и расход батареи — замерить на телефонах.
- Доля успешных прямых соединений по операторам и Wi-Fi; проходит ли UDP звонка у МегаФона, МТС, Билайна, Т2; сигнатуры WebRTC у ТСПУ.
- Эхо- и шумоподавление на Android (системные средства или отдельный модуль WebRTC) — не изучено.
- Лицензия самого репозитория совместима с AGPL (RingRTC, libsignal — ADR-2) — подтвердить перед использованием кода RingRTC.
