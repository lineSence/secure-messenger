# Mesh-сети без интернета (факты)

По [DOCS-SOURCE-DATE]. Дата сбора: 2026-10-09.

| Факт | Источник · дата |
|---|---|
| Meshtastic: управляемый флуд, до 7 хопов, все узлы ретранслируют; MeshCore: маршрутизация от источника, до 64 хопов, ретранслируют только репитеры, есть хранение истории (Room Servers) | [Seeed Studio](https://www.seeedstudio.com/blog/2026/03/23/meshcore-vs-meshtastic/) · 2026-03-23 |
| Meshtastic: публичный канал — общеизвестный ключ; групповой трафик — общий ключ канала; DM не E2E в смысле Signal | [simeononsecurity](https://simeononsecurity.com/articles/meshcore-vs-meshtastic-comparison-guide/) · на 2026-10-09 |
| CVE-2025-52464: Meshtastic 2.5.0–2.6.10 — дубли ключей у части производителей и слабая энтропия; DM с такими ключами можно расшифровать | [NVD](https://nvd.nist.gov/vuln/detail/CVE-2025-52464) · 2025-06 |
| Регион RU в Meshtastic: 868,7–869,2 МГц, 20 дБм | [Meshtastic LoRa config](https://meshtastic.org/docs/configuration/radio/lora/) · на 2026-10-09 |
| Reticulum — сетевой стек с криптографией в основе; LXMF — протокол доставки сообщений, устойчивый к задержкам и разрывам | [GitHub Reticulum](https://github.com/markqvist/reticulum) · на 2026-10-09 |
| Актуальная версия руководства Reticulum — 1.5.5 | [Reticulum Manual](https://reticulum.network/manual/Reticulum%20Manual.pdf) · на 2026-10-09 |
| Клиенты LXMF: Sideband (APK на GitHub, обновления из приложения), Columba, MeshChatX, Nomad Network; есть голос (LXST, Network Telephone) | [Sideband](https://github.com/markqvist/sideband); [Reticulum manual](https://markqvist.github.io/Reticulum/manual/) · на 2026-10-09 |

## Выводы (наши, не факты)
- Шифрование Meshtastic не заменяет наше E2E: по mesh передаём уже зашифрованный libsignal/MLS блоб.
- Reticulum ближе всего к нашей модели (store-and-forward, криптоидентичности) — кандидат №1 для mesh-плагина.
