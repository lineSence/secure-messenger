# Криптография: протоколы, библиотеки, аудиты (факты)

По [DOCS-SOURCE-DATE]: источник и дата у каждого факта. Дата сбора: 2026-10-09.

## Signal Protocol / libsignal (переписка 1:1)

| Факт | Источник · дата |
|---|---|
| PQXDH — начальный обмен ключами с постквантовой защитой (Kyber), в Signal с 2023 | [Wikipedia](https://en.wikipedia.org/wiki/Post-Quantum_Extended_Diffie%E2%80%93Hellman) · на 2026-10-09 |
| Спецификация PQXDH: ревизия 3 от 2023-05-24, обновлена 2024-01-23 | [signal.org](https://signal.org/docs/specifications/pqxdh) · 2024-01-23 |
| Формальная проверка PQXDH (ProVerif, CryptoVerif) | [USENIX Security 2024](https://www.usenix.org/conference/usenixsecurity24/presentation/bhargavan) · 2024 |
| SPQR — постквантовый «храповик»; вместе с Double Ratchet = Triple Ratchet; FS и PCS против квантового противника | [Signal blog](https://signal.org/blog/spqr); [PQShield](https://pqshield.com/diving-into-signals-new-pq-protocol) · 2025-10-02 |
| Научная статья Triple Ratchet | [IACR ePrint 2025/078](https://eprint.iacr.org/2025/078.pdf) · 2025 |
| libsignal — AGPL-3.0; «Use outside of Signal is unsupported», API меняются без предупреждения | [README libsignal](https://github.com/signalapp/libsignal) · на 2026-10-09 |
| Signal использует 64-битные MAC в сообщениях | [Matrix о vodozemac](https://matrix.org/blog/2026/02/analysis-of-reported-issues-in-vodozemac/) · 2026-02-18 |
| Автоматическая проверка ключей (key transparency) в Signal; аудиторы — Cloudflare и Trail of Bits | [Signal blog](https://signal.org/blog/automatic-key-verification/) · 2026-08-11 |

## MLS / OpenMLS (группы)

| Факт | Источник · дата |
|---|---|
| MLS — RFC 9420 | [RFC 9420](https://www.ietf.org/ietf-ftp/rfc/rfc9420.pdf) · 2023-07 |
| Архитектура MLS — RFC 9750 | [RFC 9750](https://www.rfc-editor.org/info/rfc9750/) · 2025 |
| MLS сам по себе не прячет метаданные — нужны Tor / MASQUE / VPN; служебные метаданные на сервере хранить зашифрованными, ключи — у клиентов | [MLS architecture](https://messaginglayersecurity.rocks/mls-architecture/draft-ietf-mls-architecture.html) · на 2026-10-09 |
| Аудит OpenMLS (SRLabs): 8 проблем, 1 High (неправильная проверка MAC); исправлено в 8.1 и 7.3, кроме одной Low | [OpenMLS blog](https://blog.openmls.tech/posts/2026-05-27-independent-audit/) · 2026-05-27 |

## vodozemac (Matrix)

| Факт | Источник · дата |
|---|---|
| В Olm 3DH нет проверки нулевого выхода X25519; MAC в Olm v1 укорочены до 64 бит; Matrix считает практической атаки нет; Olm v2 не стандартизирован | [Matrix.org](https://matrix.org/blog/2026/02/analysis-of-reported-issues-in-vodozemac/) · 2026-02-18 |
| CVE-2024-34063: затирание секретов сломалось из-за смены feature-флага в зависимостях Dalek (0.5.0–0.5.1) | [NVD](https://nvd.nist.gov/vuln/detail/CVE-2024-34063) · 2024-05 |
| CVE-2024-40640: base64 не за постоянное время при импорте ключей (до 0.7.0) | [NVD](https://nvd.nist.gov/vuln/detail/cve-2024-40640) · 2024 |
| Аудит vodozemac (Least Authority) | [Отчёт](https://matrix.org/media/Least%20Authority%20-%20Matrix%20vodozemac%20Final%20Audit%20Report.pdf) · 2022-03-30 |

## SimpleX (готовая основа)

| Факт | Источник · дата |
|---|---|
| Аудит реализации Trail of Bits — ноябрь 2022; ревью дизайна протоколов — июль 2024; аудит реализации запланирован на июнь 2026 | [SimpleX security](https://github.com/simplex-chat/simplex-chat/security) · на 2026-10-09 |
| Аудит 2022: 4 проблемы (2 Medium, 2 Low), среди них «ключи в незакреплённой памяти, не затираются» | [Trail of Bits](https://trailofbits.com/library/simplex-chat) · 2022-10 |
| Публикация ревью дизайна | [SimpleX blog](https://simplex.chat/blog/20241014-simplex-network-v6-1-security-review-better-calls-user-experience.html) · 2024-10-14 |
| Приватная маршрутизация (2 узла): скрывает IP и сессию отправителя от сервера получателя (v5.8) | [SimpleX blog](https://simplex.chat/blog/20240604-simplex-chat-v5.8-private-message-routing-chat-themes.html) · 2024-06-04 |
| SimpleX заблокирован в РФ | [Wikipedia](https://ru.wikipedia.org/wiki/SimpleX_Chat) · блокировка 2024-09 |

## Метаданные

| Факт | Источник · дата |
|---|---|
| Sealed sender: сервер может установить отправителя статистикой (время + автоматические отчёты о доставке) за несколько сообщений | [NDSS 2021](https://www.cs.umd.edu/users/kaptchuk/publications/ndss21.pdf) · 2021 |
| Анализ трафика sealed sender | [arXiv 2305.09799](https://arxiv.org/html/2305.09799v1) · 2023-05 |
| Signal сам признаёт: корреляция по времени и IP — открытая задача | [Signal blog](http://signal.org/blog/sealed-sender) · 2018-10 |

## Устройство и изъятие

| Факт | Источник · дата |
|---|---|
| Cellebrite заявляла поддержку Signal; среди клиентов — Россия | [Signal blog](https://signal.org/blog/cellebrite-vulnerabilities/) · 2021-04-21 |
| На Android ключ базы Signal — в Keystore; при root/downgrade базу расшифровывают, есть открытые инструменты | [Signal-Forensics](https://github.com/AvillaDaniel/Signal-Forensics) · на 2026-10-09 |
