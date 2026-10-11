# Изъятие телефона и криминалистическое извлечение (факты)

По [DOCS-SOURCE-DATE]. Дата сбора: 2026-10-11. Связано: `crypto.md` («Устройство и изъятие»), `platforms.md` (незаметность приложения), ADR-56, ADR-63, ADR-71.

## Кто и чем извлекает в РФ

| Факт | Источник · дата |
|---|---|
| Cellebrite прекратила продажи и обслуживание в России и Беларуси 2021-03-18 | [Cellebrite](https://cellebrite.com/en/resources/press-releases/cellebrite-stops-selling-its-digital-intelligence-offerings-in-russian-federation-and-belarus) · 2021-03-18 |
| Citizen Lab: экспертный центр МВД по заказу СК извлёк данные iPhone правозащитника Пивоварова через UFED Physical Analyzer и UFED 4PC — WhatsApp, Telegram, Viber; искали «Открытую Россию» и имена оппозиционеров. Cellebrite: использование в РФ после марта 2021 «не санкционировано» | [Citizen Lab](https://citizenlab.ca/research/russia-breaks-into-human-rights-activists-phone-with-cellebrite/), [TechCrunch](https://techcrunch.com/2026/06/25/cellebrite-said-it-cut-off-russia-but-russia-used-is-tools-anyway) · 2026-06-25 |
| На границе ФСБ использует Cellebrite UFED (по данным «Первого отдела» — закупки на чёрном рынке) и «Мобильный криминалист»: делают полную копию телефона и автоматически ищут «триггеры» — украинские номера, имена, организации, названия Wi-Fi-сетей; копию сохраняют и возвращаются к ней, когда для прошивки появится обход | [Meduza](https://meduza.io/feature/2026/08/28/na-granitse-mogut-sdelat-polnuyu-kopiyu-vashego-telefona-i-tochechnaya-chistka-perepisok-v-etom-sluchae-ne-pomozhet-a-chto-pomozhet) · 2026-08-28 |
| Копия телефона в состоянии BFU (после включения пароль ещё не вводили) «практически бесполезна» — данные зашифрованы; совет: подходить к контролю с выключенным телефоном, отключить разблокировку по лицу и отпечатку, длинный буквенно-цифровой пароль | там же · 2026-08-28 |
| «Мобильный криминалист» разрабатывает «МКО Системы» (бывш. «Оксиджен Софтвер», Oxygen Forensics); клиенты — МВД, ФСБ, ФСО, СК, Минюст | [The Moscow Times](https://ru.themoscowtimes.com/2026/02/20/ssha-zakupili-oborudovanie-dlya-vzloma-telefonov-u-kompanii-svyazannoi-s-veteranami-fsb-a187773) · 2026-02-20; [1С-МСсофт](http://mssoft.ru/Makers/Oxygen_Software) · на 2026-10-11 |
| «МК Эксперт Плюс»: 35 400+ моделей устройств, 719 приложений (49 700+ версий), 104 облачных сервиса; запуск по USB-ключу | [mko-systems.ru](https://mko-systems.ru/mobile-expert) · на 2026-10-11 |
| «МКО Системы» в 2026 предложила заказчикам новые способы разблокировки и извлечения для iOS до iPhone 16 и Android до 16 включительно | [Telegram @mk_software](https://t.me/s/mk_software) · 2026-08 |
| Метод «MTK Android»: выключенный телефон на MediaTek переводят в режим Boot ROM, загружают свой загрузчик (DA-файл), снимают физический образ и аппаратные ключи шифрования; для Samsung, Huawei, Motorola — замыкание контактов на плате | [docs.mko-systems.ru](https://docs.mko-systems.ru/tpost/vnfatr8ii1-izvlechenie-dannih-iz-ustroistva-metodom) · 2024-09-04 |
| Граница: по поправкам к ст. 30 закона «О государственной границе» пограничники вправе требовать устройства для осмотра; отказ трактуют как неповиновение (ст. 18.7 КоАП: штраф 5000–7000 ₽ или арест до 15 суток). ОВД-Инфо: требование разблокировать незаконно; ст. 51 Конституции | [Настоящее время](https://www.currenttime.tv/a/rossiya-pogranichniki-proverka-telefon/33734497.html) · 2026-04; [ОВД-Инфо](https://ovdinfo.legal/instruction/border) · на 2026-10-11 |
| Методичка для следователей допускает физическую силу и разблокировку лицом или пальцем владельца | [The Moscow Times](https://ru.themoscowtimes.com/2026/01/21/sledovatelyam-razreshili-primenyat-silu-dlya-razblokirovki-telefonov-rossiyan-a185043) · 2026-01-21 |

## Как взламывают Android

| Факт | Источник · дата |
|---|---|
| BFU без отдельного защищённого чипа (Secure Element): уязвимость Boot ROM или режим загрузки (MediaTek Download, Qualcomm EDL) → правка TEE → перебор пароля офлайн на своём железе; PIN из 6 цифр и короче подбирается «тривиально», устойчив только длинный буквенно-цифровой пароль | [Osservatorio Nessuno](https://osservatorionessuno.org/blog/2026/05/demystifying-phone-unlocking-tools-a-technical-overview/) · 2026-05 |
| С Secure Element (Weaver; Pixel 6+ — Titan M2) перебор только на самом телефоне, с нарастающей задержкой; по матрице Cellebrite (февраль 2025) перебор для Pixel с Titan M в целом недоступен | там же · 2026-05 |
| AFU (пароль вводили после включения): ключи хранилища CE в памяти, экран блокировки — «просто интерфейс»; атака через USB — около 200 драйверов ядра доступны при заблокированном экране; Cellebrite использовала CVE-2024-53104 (UVC), CVE-2024-53197 (ALSA), CVE-2024-50302 (HID) | там же · 2026-05; [Amnesty](https://www.amnesty.org/en/documents/eur70/9118/2025/en/) · 2025-02-28 |
| Большинство Android-телефонов, кроме Pixel и Samsung, считаются вскрываемыми; Pixel со стоковой прошивкой устойчив к BFU, но AFU-извлечение файловой системы возможно; GrapheneOS устойчива к обоим | [Osservatorio Nessuno](https://osservatorionessuno.org/blog/2026/05/demystifying-phone-unlocking-tools-a-technical-overview/) · 2026-05 |
| Утечка матрицы Cellebrite: Pixel 6–9 на стоковой прошивке извлекаются в BFU, AFU и разблокированном состоянии (без перебора пароля); GrapheneOS — только сборки до конца 2022 | [Ars Technica](https://arstechnica.com/gadgets/2025/10/leaker-reveals-which-pixels-are-vulnerable-to-cellebrite-phone-hacking/) · 2025-10 |
| Cellebrite Inseyets (весна 2026) — «Safeguard Mode» удерживает AFU, мешая автоперезагрузке; GrayKey Preserve подавляет автоперезагрузку и удалённое стирание | [Osservatorio Nessuno](https://osservatorionessuno.org/blog/2026/05/demystifying-phone-unlocking-tools-a-technical-overview/) · 2026-05 |
| Cellebrite Chat Capture: на разблокированном телефоне сам листает чаты и делает снимки экрана с распознаванием текста (Signal, WhatsApp и «любое приложение» в общем режиме) | [Cellebrite](https://cellebrite.com/en/ask-the-expert/chat-capture-collect-data-from-android-devices/) · на 2026-10-11 |
| ФБР извлекло удалённые сообщения Signal из базы push-уведомлений iPhone — текст сохранялся, т. к. превью показывались | [Schneier / 404 Media](https://www.schneier.com/blog/archives/2026/04/fbi-extracts-deleted-signal-messages-from-iphone-notification-database.html) · 2026-04-23 |
| Android «История уведомлений»: система пишет уведомления в журнал на диске, по умолчанию хранит 24 ч; при выключении история удаляется | [AOSP: Notification history](https://source.android.com/docs/core/display/notification-history) · на 2026-10-11 |
| Gboard хранит набранные слова и подсказки в своих базах — их разбирают криминалисты | [Swift Forensics](http://www.swiftforensics.com/2021/01/gboard-has-some-interesting-data.html) · 2021-01 |

## Чем защищаться (механизмы)

| Факт | Источник · дата |
|---|---|
| Android 12+: ключи `UNLOCKED_DEVICE_REQUIRED` защищены криптографически, пока экран заблокирован, — даже при взломе Keystore. При блокировке «суперключ» стирается из памяти, если включены только PIN/графический ключ/пароль; при сильной биометрии (класс 3) — доступен по отпечатку в течение 15 с | [AOSP: Keystore features](https://source.android.com/docs/security/features/keystore/features) · на 2026-10-11 |
| Если включена слабая биометрия (класс 1–2, на многих телефонах — разблокировка по лицу) или Smart Lock (часы, доверенные места), суперключ хранится открытым — криптографической защиты нет | там же · на 2026-10-11 |
| Ключ с `setUnlockedDeviceRequired(true)` можно импортировать при заблокированном экране (после первой разблокировки), но использовать — только после разблокировки; рекомендуемая схема: зашифровать данные программно, импортировать ключ с этим флагом, обнулить исходный | [KeyProtection.Builder](https://developer.android.com/reference/kotlin/android/security/keystore/KeyProtection.Builder) · на 2026-10-11 |
| Android 16, «Расширенная защита»: перезагрузка после 72 ч блокировки и защита USB при заблокированном экране | [Google Help](https://support.google.com/android/answer/16339980?hl=en) · на 2026-10-11 |
| `FLAG_SECURE`: снимок экрана окна получается пустым (запрет — на уровне компоновщика SurfaceFlinger) | [Android Developers](https://developer.android.com/security/fraud-prevention/activities) · на 2026-10-11 |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` (API 26) — просьба к клавиатуре не сохранять историю набора и не обучать личную модель; пример — чаты в особом режиме | [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo) · на 2026-10-11 |
| Molly (форк Signal): база зашифрована парольной фразой; при автоблокировке ключ стирается из памяти, оперативная память перезаписывается | [Molly wiki](https://github.com/mollyim/mollyim-android/wiki/Data-Encryption-At-Rest), [molly.im](https://molly.im/) · на 2026-10-11 |
| GrapheneOS: PIN/пароль под принуждением безвозвратно стирает телефон и eSIM без перезагрузки, прервать нельзя | [GrapheneOS features](https://grapheneos.org/features) · на 2026-10-11 |
| Wasted (F-Droid): через Device Administration API блокирует и стирает телефон — по тревоге, при USB-подключении данных на заблокированном экране, если долго не разблокировали, по паролю под принуждением | [Wasted](https://github.com/x13a/Wasted) · на 2026-10-11 |

## Выводы для дизайна (наши, не факты)

- Главная угроза для нас — не взлом шифрования, а копия телефона в AFU (USB-эксплойт) или разблокированного телефона (Chat Capture, «триггеры»).
- ADR-56 (3) не ставит `setUnlockedDeviceRequired`: при AFU-извлечении база ADR-63 расшифровывается тем же Keystore. На Android 12+ это можно закрыть без потери приёма в фоне, если входящие складывать нерасшифрованными до разблокировки.
- Телефоны на MediaTek без Secure Element: при изъятии выключенного телефона всё решает длина пароля экрана.
