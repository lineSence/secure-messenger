# Аудит безопасности main — 2026-10-13

## Итог и область применимости

Проверен код `lineSence/secure-messenger` на **`c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`**, текущем `main` на момент начала аудита. [Проверенный commit](https://github.com/lineSence/secure-messenger/commit/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68).

Перед публикацией `main` уже был **`666d6dd00ce612b8926b8b859538bbb8f5756845`**. Сравнение SHA-256 всех **283 файлов** двух архивов показало единственное отличие: `memory/inbox.md`; исходники, конфигурация сборки, lockfiles и прочие документы аудита идентичны. Поэтому выводы относятся также к коду этой новой головы. Позднейшие изменения автоматически не покрыты. [База публикации](https://github.com/lineSence/secure-messenger/commit/666d6dd00ce612b8926b8b859538bbb8f5756845).

После дедупликации: **17 основных находок — 1 High, 12 Medium, 4 Low**. Отдельно: **известный условный High для debug** и **2 не доведённых сетевых кандидата**. Среди основных находок есть как security-дефекты, так и ошибки сохранности/доступности при локальных сбоях; число не означает 17 независимых удалённых exploits.

Не найдено доказательства чтения текста Signal посторонним или подделки его внутреннего MAC. Это не гарантия отсутствия таких ошибок, не сертификация криптографии и **не release verdict**. Не все находки новые: известные риски/повторно подтверждённые ошибки помечены в приложениях. Код, защиты CI и прежние release verdicts этим коммитом не менялись.

## Как выполнялась проверка

Запущены одновременно четыре субагента текущей модели, с отдельным контекстом и одним SHA: Signal/сопряжение, доставка/хранилище, сеть/BLE, Android/FFI/поставка. Это **не ансамбль четырёх разных моделей** и не четыре полных независимых аудита всей базы: направления различались, а часть границ проверена несколькими аудиторами.

Signal и platform сессии завершились штатно. Storage и network сессии завершились `failed` без финального сообщения; первая успела сохранить отчёт, вторая — независимые заметки, исходники PoC и успешные логи. Координатор восстановил эти результаты, проверил ключевые цепочки, повторно выполнил storage-стенды и шесть network-PoC, не выдавая незавершённую область за полное покрытие. Network-приложение явно обозначено как восстановленное. Не скрывается и ограничение слепоты: platform увидел встроенные старые сводки при чтении документации; исходники всех направлений содержали комментарии о прежних исправлениях.

### Уровни доказательств

- **Нативный PoC:** настоящее Rust API/сервер на loopback. Это ещё не Android end-to-end.
- **Изолированный JVM-тест:** настоящие тела Kotlin-методов + явно описанные заглушки Android/SQLite/Signal. Доказывает control-flow, не работу реального Keystore/радио/SQLite pool.
- **По коду:** прослежена достижимая цепочка; аппаратный/интеграционный тест ещё нужен.
- **Гипотеза:** недостаточно доказательств для основного счёта; не объявляется подтверждённой уязвимостью.

## Сводный реестр без дублей

| ID | Уровень | Находка и предпосылка | Доказательство | Источники аудиторов |
|---|---|---|---|---|
| AUD-01 | **High** | Пустой SEND блокирует голову relay-очереди; нужен sender ID существующей очереди | Native server TCP/HTTP PoC; клиентский reject-before-ACK по коду | NET-1, [network](network.md#net-1--пустой-блоб-блокирует-очередь) |
| AUD-02 | Medium | Первая непрерывно пополняемая очередь голодает остальные контакты; нужна её sender capability | Native server/Store, 200 и 1000 итераций | NET-2 + STO-7, [network](network.md), [storage](storage.md) |
| AUD-03 | Medium | Мусорные BLE-части продлевают lease до AEAD; шесть соседей занимают admission slots | По коду; зависит от радио/устройства | NET-3, [network](network.md) |
| AUD-04 | Medium | Удалённый контакт продолжает узнавать устройство по beacon и открывать внешний BLE-слой | По коду; нужен ранее полученный beacon | SIG-1 + NET-5, [signal](signal.md) |
| AUD-05 | Medium | Однобайтовый неаутентифицированный Decline удаляет Started invitation | Rust encode/decode + Android control-flow; нужен sender ID/контроль relay | SIG-2 + NET-6, [signal](signal.md), [network](network.md) |
| AUD-06 | Medium | Удаление контакта берёт SQL→Gate/Book, другие операции Gate/Book→SQL; возможен deadlock | Два независимых анализа + JVM модель SQL lock; Android не проверен | SIG-3 + STO-3, [signal](signal.md), [storage](storage.md) |
| AUD-07 | Medium | Отменённые карточки оставляют prekeys без ownership/expiry/quota | По коду; накопление при повторных незавершённых обменах | SIG-4, [signal](signal.md) |
| AUD-08 | Medium | Signal handshake commit отделён от durable invitation stage; crash/failure ломает retry | По коду + JVM модель replay; закреплённая libsignal отдельно изучена SIG | SIG-5 + STO-5, [signal](signal.md), [storage](storage.md) |
| AUD-09 | Low | GATT ACK трактуется как завершение до проверки/сохранения Reply у другой стороны | По коду; ошибка/crash после transport ACK | SIG-6, [signal](signal.md) |
| AUD-10 | Medium | Signal Store failure теряется в широком catch и разрешает relay ACK | Исходные Kotlin control-flow тесты со storage failure | STO-1, [storage](storage.md) |
| AUD-11 | Medium | sendMailbox сообщает успех без durable outbox, затем own/told подавляют повтор | Изолированный JVM-тест; crash после неудачного snapshot save | STO-2, [storage](storage.md) |
| AUD-12 | Medium | При неуспешном удалении контакта outbox уже стёрт и сохранён отдельно от SQL | Изолированный JVM-тест; поздний SQL failure/kill | STO-4, [storage](storage.md) |
| AUD-13 | Low | Будущий sentAt закрепляет устаревший mailbox после исправления часов | Исходные get/accept на JVM, подпись замещена success stub | STO-6, [storage](storage.md) |
| AUD-14 | Low | Outbox принимает empty blob, но restore отвергает собственный snapshot | Native sm-core тест; достижимость из штатного Android send не показана | STO-8, [storage](storage.md) |
| AUD-15 | Medium | Snapshot readFully до AEAD/размерных лимитов позволяет OOM при враждебном файле | По коду; нужен доступ к приватному файлу, не обычный network input | STO-9, [storage](storage.md) |
| AUD-16 | Medium | Открытый LinkCard даёт relay устойчивый identity и обратную очередь | Native roundtrip формата + сетевой путь по коду; контролируемый relay | NET-7, [network](network.md) |
| AUD-17 | Low | Некоторые копии секретов Rust/Kotlin/direct buffer не затираются | По коду + синтетическая независимость буферов; нужен доступ к памяти | PLAT-2, [platform](platform.md) |

**Разрешение расхождений severity:** STO-3 назвал deadlock High, SIG-3 — Medium. Сводная оценка AUD-06 — **Medium**: требует локального действия удаления и определённого конкурентного расписания, полного Android reproduction нет. Приоритет исправления всё равно высокий. Оценка AUD-01 High относится к дешёвому устойчивому DoS очереди при известных capability; это не оценка C/I и не числовой CVSS. Приложения сохраняют исходные оценки, итоговым считается этот реестр.

## Известные риски и кандидаты, не увеличивающие основной счёт

### RISK-DEBUG — условный High только для debug (PLAT-1)

Публичный постоянный debug signing key позволяет подписать поддельное обновление **`app.uncensored.debug`**. Нужно, чтобы пользователь/ADB установил его поверх debug с реальными данными. Release — другой package и другой ключ; компрометация его подписи не показана. Возможность подписать синтетический payload воспроизведена, APK update не тестировался. Это уже описано ADR-147, а не новая утечка production signing secret. В проверенном документе ADR-147 имеет статус **«предложено»**, поэтому аудит не утверждает, что владелец принял этот риск. Подробности и ограничения: [platform.md](platform.md).

### HYP-PAIR-BUDGET / HYP-HTTPS-DEADLINE

Неограниченная постановка сырых PairLink callbacks и отсутствие общего deadline HTTPS оставлены для отдельного flood/slow-response теста. ANR/OOM и конкретный endpoint attack не воспроизведены; они **не входят** в 17 findings. ACK-incarnation/ABA, orphan caches, rollback файлов и другие незавершённые вопросы перечислены в storage/signal приложениях, без автоматического объявления новых vulnerabilities.

## Что реально запускалось

| Набор | Результат | Не означает |
|---|---|---|
| Network cargo sm-core | 79 passed | Это не весь workspace |
| Network cargo sm-relay | 36 passed: 17 unit + 7 guest + 7 HTTP + 5 two_clients | Не аудит живого TLS/nginx |
| Network cargo sm-signal | 54 passed | Не полный аудит upstream libsignal |
| Network standalone PoC | 6 passed; координатор повторил с --locked | Не Android/BLE end-to-end; 30k buffers — smoke, не cargo-fuzz |
| Storage native sm-core | 79 passed, прямой rustc | Дублирует core-набор, не прибавлять как новое покрытие |
| Storage FrameCache/AckQueue JUnit | 20 passed | JVM, не SQLite/Keystore instrumentation |
| Storage Store/probes | 12 passed: 9 существующих + 3 probes | Один ABA probe оставлен исследовательским, не finding |
| Storage Kotlin fault/scheduling PoC | Assertions прошли; координатор повторил | Заглушки ratchet/signature/persistence явно описаны |
| Platform | Синтетическая подпись, буферы, XML assertions, crypto-bans, SEK lint | Не проверка установки APK или всей supply chain |
| Документация | SEK lint после сборки отчёта | Не зелёный CI кода |

Нельзя складывать эти числа как число независимо подтверждённых уязвимостей. Успешный smoke/юнит-тест не отрицает выявленные несогласованности протокола.

## Приоритет исправлений

1. **Сначала AUD-01 / AUD-02 / AUD-10:** убрать empty-blob poison, обеспечить fairness очередей и typed ACK outcome. Не исправлять доступность удалением входа при временном storage failure.
2. **AUD-06 / AUD-08:** унифицировать порядок всех замков, атомарно сохранять Signal-шаг вместе с recoverable handshake stage. Android barrier/fault tests обязательны; только новая обёртка вокруг book monitor может создать другую инверсию.
3. **AUD-11 / AUD-12:** durable journal служебных сообщений и durable cancellation при удалении, включая crash между SQL и AtomicFile.
4. **AUD-04 / AUD-05 / AUD-16:** отзыв beacon, аутентифицированная отмена, конфиденциальность pre-session карточки. Изменения форматов/ключей требуют ADR и миграции.
5. **AUD-03 / AUD-07 / AUD-15:** admission/lifetime/input budgets; затем Low-находки, debug policy и полные аппаратные проверки.

Для каждой проблемы использовать failing regression с честной областью: native protocol tests для AUD-01/02/14; настоящие Android SQL/Keystore fault/kill tests для транзакций и deadlock; два телефона/сниффер для BLE и отзыва. Кодовые изменения — отдельно через PR с зелёным ci-ok, проверки не ослаблять.

## Приложения, покрытие и ограничения

- [Signal, pairing и trust](signal.md): исходный отчёт, 6 findings, статические цепочки и проверенные свойства.
- [Delivery и storage](storage.md): исходный отчёт, 9 findings до дедупликации, границы транзакций, модели и архив источников стендов.
- [Network/BLE](network.md): восстановленный отчёт, 6 основных кандидатов до дедупликации + 2 ограничения, оригинальный Rust PoC.
- [Android/FFI/CI/supply chain](platform.md): исходный отчёт, debug-risk и memory hygiene, hardening отдельно.

Глубоко проверены выбранные сетевые парсеры/очереди, Signal wrappers/store callbacks, pairing/invite state transitions, Android persistence/Keystore/Manifest и workflows. **Не заявляется просмотр каждой строки всех 283 файлов.** Не проведены APK/Gradle/full workspace, instrumentation/реальные телефоны, runtime Keystore/backup/TLS, аппаратные crash/fsync tests, полный аудит libsignal/SPQR/JNI, актуальная проверка advisory databases/scanners, Kani/coverage-guided fuzzing, полный независимый повторный проход всей базы.

Секретные значения и ключи в отчёты не включены; опубликованный debug-key payload не копировался. PoC используют синтетические данные/loopback. Этот документ — документационный audit snapshot, не одобрение выпуска и не исправление найденных ошибок.

## Дополнительный аудит после PR #89 и исправления

Проверена delta до `9595f2e127b96dd37fa81fd32d41f18bd48e9896` четырьмя субагентами. Добавлены AUD-18…AUD-23; прежние доказательства выше относятся к исходному срезу. Смотри [дополнение с дедупликацией](delta.md), [план единого PR](remediation-plan.md) и четыре delta-приложения. Исторический реестр теперь содержит 23 ID; фактическое закрытие проверяется отдельно после интеграции правок.
