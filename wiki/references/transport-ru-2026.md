# Транспорт: блокировки в РФ и способы доставки (факты)

Каждый факт — с источником и датой публикации; для живых страниц — «на ГГГГ-ММ-ДД» (дата проверки) по [DOCS-SOURCE-DATE].
Дата сбора: 2026-10-09. Источники — в основном отчёты сообщества (Хабр, ntc.party, GitHub), не официальные данные; ТСПУ настроена по-разному у разных операторов — всё проверять у себя.

## Обстановка

| Факт | Источник · дата |
|---|---|
| ~60% мобильных интернет-сессий идут по «белым спискам»; в Белгородской, Брянской, Курской областях ~88% (на 31.07.2026) | [CNews](https://www.cnews.ru/news/top/2026-08-05_vmesto_milliarda_sajtov) · 2026-08-05 |
| В белом списке >500 российских сервисов; условие — все вычислительные мощности в РФ | [Интерфакс](https://www.interfax.ru/digital/1085608) · 2026 (апрель, см. [Comss](https://www.comss.ru/page.php?id=20393) · 2026-04-23) |
| При белых списках разрешение идёт по IP, а не только по DNS; Tor подключается, но веб не работает | [Tor anti-censorship notes](https://forum.torproject.org/t/tor-project-anti-censorship-team-meeting-notes-2026-08-27/22044) · 2026-08-27 |
| Минцифры официально: белый список применяется только при ограничениях мобильного интернета, проводной работает штатно | [Sber Developers со ссылкой на Минцифры](https://developers.sber.ru/help/business-development/white-lists) · на 2026-10-09; [Lenta.ru](https://lenta.ru/articles/2026/05/26/belyy-spisok-interneta) · 2026-05-26 |
| Слухи о белых списках для проводного интернета | [Metaratings](https://cybersport.metaratings.ru/articles/kak-mogut-rabotat-belye-spiski-dlya-domashnego-interneta) · 2026-03-21 (опровергнуто Минцифры, см. выше) |
| ТСПУ «замораживает» соединение примерно на 120 с при совпадении признаков: подозрительная подсеть сервера + TLS-отпечаток + залп параллельных TLS-рукопожатий к одному SNI; блок привязан к (IP клиента, SNI); ретраи продлевают блок | [Хабр 1047442](https://habr.com/ru/articles/1047442) · 2026-06-15 (реконструкция по одному источнику) |
| С конца мая 2026 под подозрение попали и некоторые российские облака | там же · 2026-06-15 |
| SSH на нестандартном порту поведенческий фильтр не трогает | там же · 2026-06-15 |
| К IP Cloudflare ТСПУ пропускает первые ~16 КБ и рвёт соединение | [youtubeUnblock #356](https://github.com/Waujito/youtubeUnblock/issues/356) · создан 2026-01-31 |
| Подключения из РФ к сайтам за Cloudflare замедляются с 9 июня 2025 | [Cloudflare blog](https://blog.cloudflare.com/russian-internet-users-are-unable-to-access-the-open-internet/) · 2025 |
| Мощность ТСПУ планируют поднять до 954 Тбит/с; в 2025 систем ТСПУ 1406 | [The Moscow Times](https://ru.themoscowtimes.com/2026/03/25/vlasti-reshili-kratno-povisit-moschnost-sredstv-blokirovki-i-slezhki-za-internet-trafikom-a190745) · 2026-03-25 |
| Постановление №1667 о маршрутизации трафика через ТСПУ действует с 01.03.2026 до 01.03.2032 | [ppt.ru](https://ppt.ru/obzory/vstupaet-v-silu/postanovlenie-pravitelstva-rf-27-10-2025-1667) · принято 2025-10-27 |
| Минцифры попросило хостинг- и CDN-провайдеров вынести IP белого списка в отдельные подсети, чтобы туда не попадали VPN; поддержано ФСБ | [Forbes](https://www.forbes.ru/tekhnologii/567562-mincifry-soglasilos-vydelit-ip-adresa-belogo-spiska-dla-bor-by-s-narkosopami-i-vpn) · 2026-08-31 |

## Статус транспортов

| Транспорт | Статус | Источник · дата |
|---|---|---|
| VLESS + Reality | Массовые сбои в феврале и июне 2026 | [Рамблер](https://news.rambler.ru/tech/56604520-vless-reality-bolshe-ne-magiya-tspu-bet-po-povedeniyu/) · 2026 (июнь) |
| VLESS + Reality | В основном работает; мешает блок за ~6 рукопожатий подряд; лечится gRPC/XHTTP с `max_connections: 1`; маскироваться лучше под свой домен | [Хабр 1009542, комментарии](https://habr.com/en/articles/1009542/comments) · 2026-03-13 |
| VLESS + XHTTP (+Reality) | Лучшая устойчивость к поведенческому анализу по оценке автора; XHTTP работает через CDN | [Хабр 1009542](https://habr.com/ru/articles/1009542/) · 2026 (март) |
| WS/gRPC через Cloudflare | Почти не работает (обрыв 16 КБ); при белых списках — нет | см. Cloudflare выше; [Хабр 1021160](https://habr.com/ru/articles/1021160/) · 2026 |
| Delta Chat / chatmail (почта) | Блокировали по TLS-отпечатку клиента; исправлено в v2.56; пуши на iOS не работают | [Delta Chat forum](https://support.delta.chat/t/chatmail-self-hosted-server-stopped-working-in-russia-fingerprint-prior-to-v2-56/5561) · 2026-07-20 |
| Tor Snowflake | Блокировка DTLS по отпечатку с 2026-03-30 | [net4people #603](https://github.com/net4people/bbs/issues/603) · 2026-03-31 |
| Tor WebTunnel | Часть мостов заблокирована | [Tor forum](https://forum.torproject.org/t/russia-rolled-out-whitelists-and-snowflake-some-webtunnels-and-more-than-half-of-internet-is-complete-dead-and-no-one-seems-to-notice/21105) · 2026-01-17 |
| Hysteria2 (QUIC) | Работает по Wi-Fi, на мобильных сетях — нет | [hysteria #1683](https://github.com/HyNetworks/hysteria/issues/1683) · 2026-09-17 |
| Hysteria2 обфускация | Salamander / Gecko против блокировки QUIC | [Документация Hysteria](https://v2.hysteria.network/ru/docs/advanced/Full-Server-Config) · на 2026-10-09 |
| AmneziaWG 2.0 | Работает на своём VPS по отзывам (70% «работает», 262 из 374 голосов) | [vpnstatus.site](https://vpnstatus.site/vpn/amneziavpn) · отзыв 2026-06-16, на 2026-10-09 |
| WireGuard, OpenVPN | Заблокированы на мобильных сетях | [vpneher](https://app.vpneher.ru/guide/alternativy-vpn-russia-2026) · 2026 |
| Российский relay в Yandex Cloud | Ненадёжно: AS Yandex.Cloud ≠ AS Яндекса, VM режутся при белых списках в ряде регионов | [vpn-setup README](https://github.com/Sergei-thinker/vpn-setup) · на 2026-10-09 |
| Российский relay в VK Cloud | IP перестали пинговаться при белых списках | [russia-whitelist #21](https://github.com/kort0881/russia-whitelist/discussions/21) · 2026-01-31 |
| TURN видеозвонков VK | Работает, но ограничение скорости и капча; Телемост закрыли | [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) · на 2026-10-09 |
| WebRTC-туннели через SFU, с iOS и ботом пересоздания звонков | Существуют, развиваются | [Хабр 1039008](https://habr.com/ru/articles/1039008) · 2026-05-25; [Lionheart, Хабр 1017410](https://habr.com/ru/articles/1017410/) · 2026 |
| DNS-туннель (dnstt) через российские резолверы | Используется как «чёрный день»; низкая скорость | [vpn-configs-for-russia #233](https://github.com/igareck/vpn-configs-for-russia/issues/233) · на 2026-10-09 |
| Meshtastic (LoRa) | Короткие сообщения на километры без сотовой сети, модули покупать заранее | [SecurityLab](https://www.securitylab.ru/blog/personal/Techno_Obzor/361409.php) · 2026 |
| Briar | Bluetooth / Wi-Fi без интернета, Tor при интернете | там же · 2026 |
| Yandex API Gateway (`*.apigw.yandexcloud.net`) | Адреса шлюза «почти всегда в белых списках», свой сайт через шлюз открывался при включённых белых списках; но шлюз терминирует соединение, режет часть заголовков, WS в serverless теряет и переставляет пакеты — для VPN «как dial-up» | [NTC-тред](https://ntc.rkn.quest/t/%D0%BA%D0%B0%D0%BA-%D0%BE%D0%B1%D0%BE%D0%B9%D1%82%D0%B8-%D0%B1%D0%B5%D0%BB%D1%8B%D0%B5-%D1%81%D0%BF%D0%B8%D1%81%D0%BA%D0%B8-%D0%B8%D1%81%D0%BF%D0%BE%D0%BB%D1%8C%D0%B7%D1%83%D1%8F-wss-%D0%B8-yandex-cloud/24735) · 2026-05-13…2026-06-02 (отчёты сообщества, одна тема) |
| Виртуалка в Yandex Cloud | «IP YC в белых списках» — миф: AS Yandex.Cloud ≠ AS Яндекса, слой убран автором архитектуры; в той же теме NTC доступность различается по операторам и городам (Рязань Т2 и МТС — проходят, Петербург МТС, Мегафон, Йота, Т-мобайл — нет) | [Хабр 1021160](https://habr.com/ru/articles/1021160/) · 2026-04-17 (обновление автора); NTC-тред выше · 2026-05-23 |
| STUN как скрытый канал | Произвольный трафик переносится в comprehension-optional атрибутах STUN — двунаправленный канал, рецензируемая работа | [Computers & Security (Gorrochategui)](https://www.sciencedirect.com/science/article/pii/S0167404826002191) · 2026-06-25 |
| Бинарные SMS с Android | `SmsManager.sendDataMessage` шлёт двоичные SMS на порт приложения; ~140 байт на сегмент | [Android Developers](https://developer.android.com/reference/android/telephony/SmsManager) · на 2026-10-10 |
| Wi-Fi Aware (NAN) на Android | Обнаружение устройств и обмен сообщениями без точки доступа и интернета | [Android Developers](https://developer.android.com/develop/connectivity/wifi/wifi-aware) · на 2026-10-10 |
| Состав белого списка | VK, Яндекс, Авито и другие сервисы с пользовательским контентом; список расширяется волнами | [Российская газета](https://rg.ru/2026/04/23/rasshiren-belyj-spisok-sajtov-dostupnyh-bez-mobilnogo-interneta.html) · 2026-04-23 |

## Выводы для дизайна (наши, не факты)
- Один долгоживущий мультиплексированный канал, без залпов рукопожатий.
- Экспоненциальный backoff с джиттером; при признаках заморозки — смена транспорта, а не повтор.
- uTLS-отпечаток реального браузера (не «randomized»); свой домен.
- Лестница транспортов по состоянию сети — `wiki/architecture/overview.md`.
