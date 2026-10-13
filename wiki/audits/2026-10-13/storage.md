> Приложение: исходный отчёт отдельного аудитора. Итоговые уровни, дедупликация, статус сессий и область применимости определены в [сводке](report.md). Локальные `/data/...` — пути временного окружения, не файлы репозитория.

> Сессия аудитора завершилась `failed` после сохранения этого отчёта. Координатор восстановил его из файла и повторно запустил стенды. Их платформенные модели не равны Android instrumentation.

# Независимый security audit: delivery и storage

## 1. Рамки и краткий результат

- Репозиторий: `lineSence/secure-messenger`.
- Единственный исследованный снимок: **`c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`**. GitHub MCP `get_commit` подтвердил SHA; `get_file_contents` использован для корневого списка этого SHA. Плавающий `main` для чтения исходников не использовался.
- Дата прохода: 2026-10-13. Это отчёт об аудите, **не release verdict** и не решение о выпуске.
- GitHub и Notion не изменялись. Субагенты не запускались. Исходный снимок также не правился. Временные исходники стендов, зависимости и результаты находятся только в собственной папке `/data/audit-storage`; этот отчёт создан отдельно по запрошенному пути.
- Найдено **9 проблем: 1 High, 6 Medium, 2 Low**. Это в основном нарушения доступности и crash consistency, не утверждения о взломе шифрования.
- После завершения независимого прохода выполнена сверка со старыми отчётами. STO-9 — подтверждение сохранившейся части старой находки; STO-2 и STO-5 имеют общий класс ошибки со старыми находками, но другие актуальные цепочки вызовов.

| ID | Severity | Кратко | Статус |
|---|---|---|---|
| STO-1 | Medium | Ошибки Signal storage превращаются в разрешение удалить входящий блоб с relay | Воспроизведено тестом: изолированные исходные Kotlin-методы, платформенные заглушки |
| STO-2 | Medium | `sendMailbox` возвращает успех без долговечной записи; `own/told` подавляют повторное объявление | Воспроизведено тестом: исходный метод с отказом записи снимка |
| STO-3 | High | Удаление контакта инвертирует порядок SQLite / SignalGate / ContactBook | Доказано по коду; расписание Gate/SQL воспроизведено на модели замка SQLite |
| STO-4 | Medium | Исходящая очередь стирается до успешного удаления контакта в SQLite | Воспроизведено тестом: исходный `dropContact`, отказ SQL после записи пустого outbox |
| STO-5 | Medium | Приглашение фиксирует шаг Signal отдельно от durable stage и контакта | Доказано по коду; изолированный PoC с моделью одноразового приёма |
| STO-6 | Low | Почтовый адрес упорядочивается по неограниченным стеночным часам отправителя | Воспроизведено тестом: исходные `get/accept`, проверка подписи узла заменена успешной заглушкой |
| STO-7 | Medium | Первая пополняемая inbox-очередь блокирует получение остальных | Воспроизведено нативным Rust-тестом с реальным Store и исходным `fetch_any` |
| STO-8 | Low | Outbox принимает пустой blob, но затем отвергает собственный снимок | Воспроизведено нативным Rust-тестом реальных `push/encode/restore` |
| STO-9 | Medium | DeliveryStore читает враждебные файлы целиком до AEAD и лимитов Rust | Доказано по коду; Android OOM-тест не запускался |

Severity оценивает последствия при указанных предпосылках. High у STO-3 — зависание общего пути сообщений и сопряжения до перезапуска процесса. Low у STO-6/STO-8 — ограниченный сценарий повреждения маршрута или некорректного вызова библиотечного API, без показанной атаки внешнего неаутентифицированного отправителя на текущий Android-путь.

## 2. Методика, происхождение снимка и документы

Фиксированный tarball скачан по адресу `https://codeload.github.com/lineSence/secure-messenger/tar.gz/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`.

SHA-256 архива:

```text
d46e31e7ce5eaad16668d5dc49d52bdcff76ffdc70760338f147aec7dc99f312
```

Контроль содержимого: **283 файла** извлечённого снимка сравнены с архивом; изменений не обнаружено. Компиляция производилась с выходными файлами вне дерева снимка. SHA-256 архива является локальным отпечатком загруженного снимка, а не независимой криптографической аттестацией GitHub.

Прочитаны `AGENTS.md` (в снимке других AGENTS.md не найдено), релевантные разделы `wiki/architecture/{overview,android,core,signal,relay}.md`, `wiki/references/crypto.md`, `wiki/rules/code.md`, `wiki/workflows/ci.md`, решения ADR-40/63/82/134/137/151. После независимого прохода — старые находки в `releases/v0.0.1-alpha.md`, `.github/ai-review/releases/v0.0.1-alpha.json` (12 записей), инструкции и справка AI-review. Не все материалы в wiki и не все несвязанные старые находки исследовались.

Актуальная реализация имеет приоритет над устаревшими комментариями: `sm-ffi/chat.rs` и часть архитектурной документации ещё описывают шифрование на каждой попытке, тогда как настоящий `ChatHub` использует `FrameCache` и один сохранённый ciphertext. Это не основание повторно заявлять старую уязвимость продвижения ratchet на каждой отправке. См. [crates/sm-ffi/src/chat.rs:1–11](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/chat.rs#L1-L11), [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:30–78](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L30-L78), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:264–306](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L264-L306).

Независимые кандидаты STO-1…6 записаны в `/data/audit-storage/independent-pass.md` до чтения старых отчётов. STO-7 и STO-8 получены дополнительной проверкой уже выбранных Rust-путей. Новые установки и расширение проверки прекращены после просьбы пользователя завершить проход.

## 3. Реальные цепочки вызовов и границы транзакций

### 3.1 Обычная отправка текста

```text
ChatHub.send [sm-chat]
  chatEncode
  SecureDb.atomic
    MessageStore.addOutgoing -> insert + seal/update
    kvPut(chat_send, SendEntry)
  COMMIT SQLite
  OutboxQueue.push -> Rust Outbox.push [RAM]
  DeliveryStore.saveOutboxNow -> encode -> seal -> AtomicFile
  при успехе: kvDelete(chat_send)
  при неуспехе: журнал остаётся, changed планирует снимок
```

У этого пути есть durable журнал, в отличие от служебного `sendMailbox`. `recoverQueue` читает `chat_send`, возвращает отсутствующие записи в outbox и удаляет их после сохранения снимка. Доказательства: [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:174–235](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L174-L235), [android/app/src/main/java/dev/sm/messenger/chat/SendEntry.kt:3–35](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/SendEntry.kt#L3-L35), [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:91–105](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L91-L105).

### 3.2 Подготовка ciphertext и retry

```text
collect / relayFrames -> gather
  AckQueue.drain -> FrameCache.ackFrame
  due(now) -> FrameCache.outFrame
    уже есть CT -> прежний кадр / tombstone
    иначе SignalGate.atomic -> SQLite transaction
      SignalGate.encrypt -> Rust SignalSessions mutex
        Stores over PlatformKv -> SignalKv -> SQL writes
      kvPut(chat_ct или chat_ack)
    COMMIT перед выдачей кадра
  Outbox.attempted [RAM] -> следующий срок
  changed -> будущий снимок
```

Замок записи DeliveryStore охватывает и получение snapshot, и его запись: более старый snapshot не должен перезаписать более новый, полученный через тот же путь. Native Rust outbox возвращает записи в порядке добавления, удаляет только по scoped `(mailbox,id)` и сохраняет ожидающие сообщения после транспортной отправки. [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:246–311](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L246-L311), [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:45–78](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L45-L78), [android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:25–62](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L25-L62), [crates/sm-ffi/src/signal.rs:675–695](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/signal.rs#L675-L695), [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:55–105](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L55-L105), [crates/sm-core/src/delivery.rs:176–305](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-core/src/delivery.rs#L176-L305).

Bluetooth выдаёт готовый кадр в `BleHub.flush`; relay получает те же кадры, разбирает внешний frame и посылает blob через `HttpsRelay.send`. Если передача ACK не удалась, повтор исходного ciphertext восстанавливает ACK через `chat_rx`. Отсутствие отдельного durable ACK-outbox само по себе здесь не заявляется потерей текста: это предусмотренный протоколом механизм повторов. [android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt:241–245](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt#L241-L245), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:335–342](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L335-L342), [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:58–77](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L58-L77), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:529–536](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L529-L536).

### 3.3 Приём текста и подтверждения

```text
BleLink parseFrame -> BleHub.onFrameReceived -> ChatHub.onFrame
ИЛИ RelayHub.receiveOnce -> onRelayFrame -> io.submit(openFrame)
  book.list [до транзакции]
  SignalGate.atomic
    receive -> SignalGate.open [вложенная atomic той же SQL-транзакции]
      Rust decrypt_from_any -> libsignal -> PlatformKv
      unpad -> Message.decode
    Text: addIncoming + remember(chat_rx)
    Delivered: tombstone(chat_ct) + state update
    Mailbox: peers.accept + remember(chat_rx)
  COMMIT SQL
  Text: seen.firstTime [RAM, не источник истины истории] + ACK [RAM]
  Delivered: outbox.confirm [RAM]
  relay handled=true -> HTTPS ACK -> Store.ack удаляет голову
```

Основной text receive атомарен по SQL: сессия, история и RX фиксируются вместе. Важное исключение — преобразование `SignalFfiException` в `null` и затем в `handled=true` (STO-1). Native Rust `decrypt_from_any` сам отличает storage callback failure от «не тот контакт»; ошибка теряется выше, в Kotlin. [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:357–441](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L357-L441), [android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt:119–134](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt#L119-L134), [crates/sm-signal/src/contact.rs:519–551](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/contact.rs#L519-L551), [crates/sm-ffi/src/chat.rs:182–195](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/chat.rs#L182-L195), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:259–269](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L259-L269), [crates/sm-relay/src/store.rs:334–365](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/store.rs#L334-L365).

### 3.4 Dedup / replay

- История: `mid = HMAC(direction || contactAddress || separator || messageId)`, уникальный `mid`, отдельные направления IN/OUT; ID одного контакта не подавляет историю другого. [android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt:46–52](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt#L46-L52), [android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt:127–146](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt#L127-L146), [android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt:67–77](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt#L67-L77).
- Exact ciphertext replay: SHA-256 blob → `chat_rx` → адрес/id; libsignal уже не открывает ciphertext, но replay-ветка снова ставит cached ACK. [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:58–89](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L58-L89), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:533–536](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L533-L536).
- `Delivered` не ACK-ится; durable tombstone подавляет повторное шифрование сообщения из старого outbox snapshot. [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:385–390](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L385-L390), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:425–429](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L425-L429), [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:45–56](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L45-L56).
- Rust `Seen` имеет независимое ограниченное окно на mailbox. Android не использует его результат как основание показа истории: durable уникальность лежит в SQLite. [crates/sm-core/src/delivery.rs:373–422](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-core/src/delivery.rs#L373-L422), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:375–383](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L375-L383).

### 3.5 Удаление и приглашения

Удаление: сначала `outbox.confirm/saveOutboxNow`, затем **обычная `db.atomic`**, внутри которой вызывается `sessions.forget` и синхронизированный `book.delete`. Это два отдельных вида дефекта: инверсия замков (STO-3) и предварительная необратимая очистка outbox (STO-4). [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:473–505](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L473-L505).

Приглашения: `gate.acceptStart/openReply/acceptLinkCard/replyFrame` завершают свои SignalGate-транзакции до изменения `InviteLinks`/`ContactBook`; для `Start` существует ещё отправка в сеть между шагом Signal и `putOut(Started)`. Это не тот же атомарный path, что text receive. [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:302–314](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L302-L314), [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:359–373](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L359-L373), [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:388–422](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L388-L422).

## 4. Находки

### STO-1 — Storage failure в Signal ошибочно подтверждается узлу

**Severity:** Medium. **Статус:** воспроизведено тестом на изолированных исходных Kotlin-методах. **Уверенность:** высокая в control-flow; Android/нативная libsignal в PoC не исполнялись.

**Основное место:** `android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:412-417`, `:367-371`. Permalinks: [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:412–417](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L412-L417), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:367–371](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L367-L371). Потребитель результата: [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:259–267](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L259-L267). Аналогичная ошибка есть в обработке invitation `Reply`/`Start`/`LinkCard`: [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:362–369](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L362-L369), [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:397–401](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L397-L401), [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:413–417](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L413-L417).

**Предпосылки:** правильный входящий ciphertext находится на relay; во время Signal операции возникает временная ошибка SQLite, Keystore, повреждённой записи или callback storage. В Kotlin `SignalKv` превращает эти ошибки в `SignalFfiException.Store`; FFI также явно отображает storage callback failure в Store. [android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt:32–49](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt#L32-L49), [crates/sm-ffi/src/signal.rs:53–67](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/signal.rs#L53-L67).

**Сценарий:**

1. `receive` ловит **весь** `SignalFfiException`, включая Store, и возвращает `null`.
2. Внешняя `SignalGate.atomic` откатывает SQL, но исключение уже потеряно.
3. `openFrame` для `got == null` вызывает replay-проверку и возвращает `true`, даже если RX отсутствует.
4. `RelayHub.receiveOnce` делает ACK и удаляет blob с сервера.

**Последствия:** нарушен контракт «storage failure оставляет blob на relay». Обычный Text обычно ещё может восстановиться из outbox отправителя, поэтому **не утверждается неизбежная окончательная потеря каждого текста**. Но появляются лишние сетевые повторы и задержка до следующего retry. У invitation кадров нет общего text outbox: их удаление на Store error может оборвать добавление контакта. В этой ветке ciphertext не был успешно обработан/зафиксирован, а узлу сказано обратное.

**PoC:** `AuditHub.kt` содержит извлечённые без изменения тела `openFrame`/`receive`. Gate заглушка выбрасывает Store. Assertions: результат `true`, число успешно завершённых внешних SQL-операций `0`; контрольный Protocol тоже возвращает `true`. `AuditInvite.onIn` дополнительно подтвердил `Store -> true`. Это проверка ошибочного решения об ACK, не интеграционный запуск настоящего relay.

**Исправление:** разделить невалидный кадр и временный отказ. Store/Callback пропускать наружу или возвращать typed Retry, после чего `guard`/`onWorker` дают `false`; только успешно зафиксированный приём, известный повтор или окончательно невалидный input дают ACK. Проверить все широкие catches SignalFfiException в InviteFlow. Добавить интеграционный тест: storage fail → blob сохранён → после восстановления тот же blob принят и только затем ACK.

### STO-2 — Служебный Mailbox теряется, но маршрут отмечен объявленным

**Severity:** Medium. **Статус:** воспроизведено тестом изолированного исходного `sendMailbox`. **Уверенность:** высокая; влияние `own/told` доказано по реальной вызывающей цепочке.

**Места:** [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:340–353](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L340-L353), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:277–295](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L277-L295), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:302–308](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L302-L308), [android/app/src/main/java/dev/sm/messenger/relay/AnnouncedNodes.kt:21–28](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/AnnouncedNodes.kt#L21-L28), [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:95–98](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L95-L98).

**Предпосылки:** первая выдача своей очереди или смена записи узла; запись snapshot outbox не удалась, но маленькие записи SQLite `own/told` проходят. Например, ошибка именно создания/записи AtomicFile, а не полный отказ всех хранилищ. Процесс погибает до удачного отложенного save.

**Сценарий:**

1. `sendMailbox` добавляет padded Mailbox только в RAM outbox.
2. `saveOutboxNow()==false` приводит лишь к `changed()`, затем метод возвращает `true`.
3. `ensureQueues` сохраняет `OwnQueue` и `announced.mark`; `recreate` имеет тот же паттерн.
4. После смерти процесса старый snapshot не содержит Mailbox, `chat_send` для него никогда не создавался.
5. В текущем периоде own queue уже есть и node отмечен current, поэтому объявление не ставится снова.

**Последствия:** получатель не узнает новый sender ID или адрес узла; обещание повторять служебное сообщение перестаёт выполняться. Если это единственный доступный маршрут, доставка в направлении этой входящей очереди блокируется. Смена периода/node или новый обмен могут снять состояние, но автоматического восстановления из потерянной записи нет; срок 30 дней относится к ротации периода, не гарантированному времени восстановления. Обычный `send(Text)` с журналом этим конкретным дефектом не поражён.

**PoC:** исходный `sendMailbox` с `canSave=false`: `true`, RAM содержит сообщение, durable snapshot не содержит. Изменение `own/told` не моделировалось как полный relay integration — его порядок подтверждён кодом `ensureQueues`.

**Исправление:** durable journal для всех исходящих body types, включая Mailbox. Записывать сообщение и intent объявления в SQLite атомарно; не считать объявление поставленным, пока нет durable записи. Повторить recovery из журнала после crash. Синхронный save без журнала и без явного false не даёт требуемого контракта. Добавить kill/fault test между отказом AtomicFile и `own/told`.

### STO-3 — Deadlock при удалении контакта: SQL → Gate против Gate → SQL

**Severity:** High. **Статус:** доказано по коду; расписание замков подтверждено изолированным тестом с настоящим исходным классом SignalGate и моделью транзакционного SQL-замка. **Уверенность:** высокая; на Android-устройстве не воспроизводилось.

**Места:** [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:487–500](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L487-L500), [android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:25–31](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L25-L31), [android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:57–62](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L57-L62), [android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt:124–134](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt#L124-L134), [android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt:63–88](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt#L63-L88), [android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt:112–119](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt#L112-L119). Достижимые конкурентные Signal вызовы pairing worker: [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:497–498](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L497-L498), [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:513–516](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L513-L516).

**Предпосылки:** пользователь удаляет контакт, пока отдельный поток сопряжения/приглашения вызывает SignalGate; либо конкурентный поток держит монитор ContactBook и ждёт базы. ChatHub использует один `sm-chat`, но pairing и invitation worker — другие потоки, поэтому один chat executor не исключает гонку.

**Расписание:**

```text
T1 sm-chat/delete: db.atomic -> владеет SQL-транзакцией
T2 pairing:       SignalGate.atomic -> владеет ReentrantLock Gate
T2 pairing:       db.atomic -> ждёт SQL у T1
T1 sm-chat:       sessions.forget -> ждёт Gate у T2
```

`dropContact` — исключение из нормального порядка Gate → SQL → Rust mutex, описанного в SignalGate. Отдельная инверсия с ContactBook: `db.atomic -> book.delete` против `@Synchronized book.list/save/delete -> обращение к db`. Даже исправив Gate-ветку, нельзя оставить произвольное взятие book monitor внутри долгой транзакции, если другой поток держит его и ждёт SQL.

**Последствия:** бессрочно заблокированы sm-chat и конкурирующий worker; relay вызовы, ожидающие futures ChatHub, также перестают обслуживаться. Это availability failure, а не захват ключей. На показанном расписании восстановление требует перезапуска процесса.

**PoC:** `Locking.kt` содержит класс SignalGate из исследованного commit. T1 берёт model SQL transaction lock, T2 входит Gate и ждёт SQL, T1 вызывает `forget`. Обе нити остаются `WAITING`; stack traces содержат SignalGate.atomic/SecureDb.atomic. Нити daemon, поэтому тестовый процесс завершается, не оставляя зависших рабочих процессов. Модель не проверяет детали Android SQLite connection pool.

**Исправление:** единый порядок Gate → SQL для удаления и pairing. Если удаление затрагивает Signal, начинать верхнюю операцию через SignalGate.atomic, а не через db.atomic с последующим Gate. DB helpers ContactBook/InviteLinks, вызываемые внутри транзакций, должны быть без monitor-over-DB либо под единым согласованным locking protocol. Не заменять решение таймаутом. Нужен Android двухпоточный регрессионный тест с барьерами в местах beginTransaction/входа Gate.

### STO-4 — Отказ удаления уже уничтожил outbox

**Severity:** Medium. **Статус:** воспроизведено тестом изолированного исходного `dropContact`. **Уверенность:** высокая.

**Места:** [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:480–502](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L480-L502), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:473–477](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L473-L477), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:179–200](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L179-L200), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:218–235](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L218-L235).

**Предпосылки:** есть недоставленные сообщения с успешно сохранённым snapshot и уже очищенным `chat_send`. Пользователь удаляет контакт, запись уменьшенного snapshot успешна, затем SQL операция удаления отказывает или процесс погибает до её commit.

**Сценарий:** `outbox.confirm` и `saveOutboxNow` выполняются **до** удаления сессии/истории/контакта в db.atomic. Отказ SQL оставляет контакт и Queued-историю, но сообщения уже удалены из долговечного outbox. Синхронный save делает это окно особенно явным. Если save не прошёл, следующая асинхронная запись также может зафиксировать уже очищенную RAM-очередь до отказа/commit SQL.

**Последствия:** UI может сообщить «не удалось удалить», контакт сохраняется, история остаётся «в очереди», но retry этих сообщений прекращён. `recoverQueue` не восстанавливает каждую Queued-историю, а читает `chat_send`, который после старого успешного save уже удалён. Это не намеренное уничтожение сообщений при успешном удалении — нарушение атомарности отказавшей операции.

**PoC:** исходный `dropContact`, durable outbox моделируется в Delivery заглушке. Устанавливается SQL failure при начале поздней транзакции. Метод бросает SQLiteException; durable outbox уже пуст. Реальная SQLite/AtomicFile не запускались, порядок исходных инструкций сохранён.

**Исправление:** SQL intent/cancellation, Signal state, история и контакт должны фиксироваться первым атомарным шагом; outbox mutation — после commit, с durable cancellation/tombstone для suppression старого snapshot при crash. При SQL rollback исходящий outbox не трогать. Простое перемещение confirm после commit без защиты от восстановления старого snapshot тоже не завершает решение. Fault-injection в каждую границу удаления.

### STO-5 — Шаг Signal и этап invitation сохраняются раздельно

**Severity:** Medium. **Статус:** доказано по коду; сценарий дополнительно выполнен на исходном `onIn` с моделью одноразового приёма. **Уверенность:** высокая в границе SQL, средне-высокая в полном crash/replay-сценарии; настоящая libsignal не запускалась.

**Места:** [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:410–422](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L410-L422), [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:359–373](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L359-L373), [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:388–407](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L388-L407), [android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:29–31](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L29-L31), [android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:50–55](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L50-L55), [android/app/src/main/java/dev/sm/messenger/relay/InviteLinks.kt:100–106](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteLinks.kt#L100-L106), [crates/sm-signal/src/contact.rs:411–433](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/contact.rs#L411-L433).

**Предпосылки:** текущий InLink имеет `Sent`, relay держит правильный `Start`; после successful `acceptStart` происходит ошибка putIn либо смерть процесса. Аналогично OutLink `Started`, `Reply`, успешный `openReply`, затем отказ contact/stage write.

**Сценарий A:**

1. `gate.acceptStart` выполняет и коммитит Signal операцию в собственной atomic.
2. `links.putIn(...Ask, hello...)` выполняется позже, без общей внешней SignalGate транзакции.
3. Сбой оставляет изменённое Signal storage, но stage по-прежнему Sent и decoded Hello не сохранён.
4. Relay может вернуть тот же ciphertext; это уже повтор израсходованного сообщения. В onIn ошибка Signal превращается в true (STO-1), stage не восстанавливается, blob ACK-ится.

**Сценарий B:** `openReply` commit → ошибка book.save/putOut(Done); invitation остаётся Started, но прежний Reply уже потреблён. В `acceptCard` дополнительно есть окно Signal commit → отправка `Start` → `putOut(Started)`: при crash локальный этап может оставаться Active, хотя другая сторона уже получила старт. Не утверждается, что каждое из этих окон одинаково завершится отказом на каждой реализации libsignal; общий неатомарный durable state доказан.

**Последствия:** сторона остаётся в неверном этапе invitation, контакт создаётся только с одной стороны либо не создаётся; обычный retry ciphertext не восстанавливает утраченный decoded Hello/этап. Пользователю может потребоваться новая ссылка или повторное добавление. Однотранзакционный text receive эту ошибку уже предотвращает; invitation этот механизм не использует.

**PoC:** в `AuditInvite` извлечён исходный onIn. Модель Signal отмечает ciphertext consumed, links.putIn бросает. Повтор возвращает true, stage остаётся Sent. Это **не** эмпирическое доказательство поведения настоящих prekey/ratchet после crash: соответствующий тест не запускался.

**Исправление:** фиксировать шаг Signal + decoded handshake data + новый durable stage в одной верхней транзакции SignalGate.atomic; предварительные book lookups и DB helpers — без инверсии замков STO-3. Исходящие Start/Reply кэшировать вместе с состоянием до передачи в сеть; посылать только после commit. Invitation должен повторять прежний кадр, а не неявно создавать новый шаг сессии. Отдельные native libsignal + Android fault tests нужны для каждого перехода Active/Started/Done/Sent/Ask.

### STO-6 — Перевод часов закрепляет устаревший PeerMailbox

**Severity:** Low. **Статус:** воспроизведено тестом исходных методов get/accept. **Уверенность:** высокая в условии сравнения; availability consequence требует смены маршрута.

**Места:** [android/app/src/main/java/dev/sm/messenger/relay/PeerMailboxes.kt:28–44](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/PeerMailboxes.kt#L28-L44), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:431–433](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L431-L433), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:340–345](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L340-L345), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:335–340](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L335-L340).

**Предпосылки:** аутентифицированный контакт хотя бы однажды прислал Mailbox с сильно будущим положительным `sentAt`; это возможно и без злоумышленника — его часы были переведены вперёд. Node record корректно подписан и валиден в момент приёма. После восстановления часов выпускается действительно более новый sender ID или node.

**Сценарий:** old.sentAt >= new.sentAt приводит к отказу обновления. Сейчас нет монотонного mailbox version/epoch, проверки разумного skew или механизма выхода из timestamp high-water mark. Даже истечение срока старой записи узла не меняет это сравнение: `get` возвращает сохранённую строку, а `sendTo` затем отвергает истёкшую node record.

**Последствия:** устаревший маршрут может закрепиться до ручного сброса/пересоздания контакта или пока его timestamp не будет превзойдён. Текстовый sentAt тоже недоверен, но именно Mailbox использует его для управления маршрутом. Это не возможность одного контакта изменить маршрут другого: Signal связывает источник с contact address, поэтому severity Low.

**PoC:** `PeerClock.kt` копирует реальные get/accept, storage — карта, node signature verification — успешная заглушка для моделирования двух валидных node records. Сначала принят timestamp `Long.MAX_VALUE`, затем нормальная более поздняя по реальному времени запись отклонена, прежний senderId остался. Экстремальное значение демонстрирует отсутствие ограничения; меньший скачок даёт конечное, но длительное окно.

**Исправление:** аутентифицированная монотонная revision маршрута, сохраняемая отправителем и принимающая явные смены node/period; равные timestamps не должны случайно потерять реальное обновление. Одно лишь сравнение с локальными часами может отбрасывать честные контакты: нужна продуманная skew/recovery policy. Отдельно тестировать rollback часов и смену sender ID в одной миллисекунде.

### STO-7 — Голодание inbox других контактов

**Severity:** Medium. **Статус:** воспроизведено нативным Rust-тестом. **Уверенность:** высокая для очередей и scheduler; end-to-end flooding через TLS/Android не запускалось.

**Места:** [crates/sm-relay/src/server.rs:402–421](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/server.rs#L402-L421), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:220–227](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L220-L227), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:250–269](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L250-L269), [crates/sm-relay/src/store.rs:300–331](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/store.rs#L300-L331).

**Предпосылки:** атакующий знает sender ID одной ранней очереди получателя — например, выданный ему как контакту. Получатель опрашивает эту очередь вместе с чужими. Атакующий способен поддерживать раннюю очередь непустой (refill не медленнее чтения); криптографически валидный Signal blob не обязателен для серверного выбора первой очереди. Лимиты 1000 blobs/32 MiB ограничивают текущий размер, не суммарный refill после ACK.

**Сценарий:** fetch_any всегда перебирает IDs с начала и возвращает первый blob. Android round изменяется только на пустом ответе, а при successful handled/ACK returns 0 и опрашивает снова тот же part и порядок. Invited boxes добавлены первыми; current-period contact queues тоже имеют стабильный порядок. Если A не пустеет, ожидающий B не выбирается.

**Последствия:** контакт, имеющий только свою sender capability, задерживает сообщения других контактов и может сохранять starvation при непрерывном потоке. Это межконтактный availability DoS, а не ожидаемая возможность отправителя перегрузить только собственный чат. Конечный burst лишь задерживает остальные; для бессрочного starvation нужен refill. Невалидные кадры, которые считаются handled, могут дополнительно тратить дешифрование и RTT.

**PoC:** Rust-тест `sto_7_refilled_first_queue_starves_second`: реальный `sm-relay::Store`, два mailbox, B содержит blob, цикл 1000 раз send(A) → исходный fetch_any([A,B]) → ACK(A). На каждой итерации выбран index 0; B по окончании остался непрочитанным. Для реализации Caller использована структурно точная заглушка, не участвующая в send/fetch/ack; sm-core types и Store — настоящие.

**Исправление:** bounded per-queue processing и round-robin, в том числе продвижение client cursor после непустого ответа. Можно менять начало списка/части и сохранять last-served cursor; invitation очередь не должна безусловно получать неограниченный приоритет. Не решать через удаление незафиксированных сообщений. Регрессионный критерий: постоянно непустой A, но B выдаётся в ограниченное число итераций. Рассмотреть и head-of-line temporary storage failure, чтобы удержание одного blob не останавливало остальные queues.

### STO-8 — Пустой blob принят Outbox, но не восстанавливается

**Severity:** Low. **Статус:** воспроизведено нативным Rust-тестом реального sm-core. **Уверенность:** высокая; достижимость из обычного Android chatEncode не показана и не заявляется.

**Места:** [crates/sm-core/src/delivery.rs:176–204](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-core/src/delivery.rs#L176-L204), [crates/sm-core/src/delivery/store.rs:132–143](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-core/src/delivery/store.rs#L132-L143), [crates/sm-ffi/src/delivery.rs:159–167](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/delivery.rs#L159-L167).

**Предпосылки:** библиотечный или FFI caller передал пустой blob. Outbox.push проверяет duplicate/limits, но не `blob.is_empty`; FFI push просто делегирует. Текущий Android чат кодирует непустое padded сообщение, поэтому это API consistency defect, а не продемонстрированная удалённая атака на приложение.

**Сценарий:** push(empty) succeeds → encode succeeds → restore того же снимка получает len=0 и отказывает Wire(BadValue).

**Последствия:** библиотека позволяет создать persisted состояние, которое собственный parser считает недопустимым. Один такой элемент делает непригодным весь outbox snapshot; Android recovery при таком snapshot выберет lost branch. Это не значит, что обычный сетевой пустой SEND автоматически попадает в Android outbox.

**PoC:** `sto_8_empty_outbox_breaks_own_snapshot_restore`, actual Outbox/OutboxLimits/MessageId/MailboxId, asserts success push+encode и failure restore. Тест прошёл.

**Исправление:** согласовать invariant push/encode/restore. Предпочтительно отвергать empty blob на входе отдельной ошибкой, если спецификация требует непустой; при необходимости поддержать пустой, тогда изменить restore/spec. Добавить property-test `для каждого успешно принятого состояния restore(encode(state)) успешен`.

### STO-9 — Unbounded read снимков перед AEAD

**Severity:** Medium. **Статус:** доказано по коду; Android OOM не воспроизводился. **Уверенность:** высокая в отсутствии лимита; фактический порог зависит от heap устройства.

**Места:** [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:108–117](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L108-L117), [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:58–72](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L58-L72), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:148–150](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L148-L150), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:218–223](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L218-L223). Это подтверждение **сохранившейся snapshot-части** старой находки, а не перенос её целиком: KeyFiles уже ограничивает чтение. [android/app/src/main/java/dev/sm/messenger/keys/DeviceKeys.kt:46–59](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/keys/DeviceKeys.kt#L46-L59).

**Предпосылки:** принятая проектом модель враждебных данных с диска; подменён/повреждён `noBackupFilesDir/sm/outbox.v1` или `seen.v1` размером больше свободного heap. Нужен доступ к файлам приложения/компрометированный диск; не показано создание такого файла обычным удалённым Signal отправителем.

**Сценарий:** DeliveryStore.load вызывает AtomicFile.readFully прежде чем SnapshotCipher.open и OutboxQueue/SeenWindow.restore. Ни размер файла, ни количество прочитанных bytes здесь не ограничены. Rust max_bytes/count проверяются после загрузки массива и не защищают этот earlier allocation. IOException handling не является memory bound; OutOfMemoryError не превращается в контролируемый Broken.

**Последствия:** процесс может погибнуть при восстановлении очереди и повторять это при запуске/attach. Подделывать AES-GCM tag для такой памяти не нужно. Для seen snapshot отсутствует ещё и общий лимит числа peers; ограничение window capacity само по себе не ограничивает весь файл.

**PoC для дальнейшей проверки:** на изолированном Android профиле заменить snapshot большим/разреженным файлом и открыть ключи, измерив heap/отказ. В этом проходе **не выполнялось**; не создавались файлы в профиле другого пользователя и не тестировался OOM настоящего приложения.

**Исправление:** bounded streaming read через AtomicFile.openRead с проверкой реально прочитанных bytes; одного stat недостаточно. Outbox bound вывести из max_bytes + max_messages × overhead + header + AEAD envelope. Seen должен получить явный глобальный bound по peers/bytes. Oversize → контролируемая ошибка/отложенный broken файл, не full allocation. Использовать уже имеющийся паттерн ограниченного KeyFiles.load как образец.

## 5. Что подтверждено положительно; что не следует повторять как старую находку

1. Сохранение contact insert/update уже в db.atomic; старую «пустую contact row между insert и seal» на этом commit не подтверждаю. [android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt:84–103](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt#L84-L103).
2. Обычный Text send имеет chat_send journal; старая потеря любого исходящего текста до первой delayed snapshot закрыта именно этим путём. STO-2 относится к Mailbox без журнала. [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:179–200](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L179-L200).
3. Ciphertext/ACK cache write объединён с Signal encryption через SignalGate.atomic. Старое безусловное новое шифрование на каждой попытке не соответствует настоящему коду. [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:73–78](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L73-L78), [android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:29–31](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L29-L31).
4. Text receive объединяет Signal state + history + RX; STO-1 — ошибочная ACK-классификация отказа, не утверждение, что Text записывается в прежних разных SQL-транзакциях. STO-5 относится к другой handshake цепочке. [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:365–367](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L365-L367), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:421–439](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L421-L439).
5. CT tombstone после Delivered переживает запаздывающий outbox snapshot. [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:55–56](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L55-L56), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:298–302](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L298-L302).
6. MessageStore AAD уже содержит contactRow и rowId; dedup scoped direction/contact/id. Нет доказательства межконтактной подмены ACK или истории одним чужим ID. [android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt:144–173](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt#L144-L173).
7. Outbox/Seen restore проверяет канонический порядок, длины и фактические остатки до выделения памяти в Rust; native тесты прошли. Защита не отменяет earlier Kotlin readFully (STO-9). [crates/sm-core/src/delivery/store.rs:109–163](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-core/src/delivery/store.rs#L109-L163), [crates/sm-core/src/delivery/store.rs:195–235](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-core/src/delivery/store.rs#L195-L235).
8. DeliveryStore сериализует encode+write через writeLock, а AtomicFile использует старый/новый файл для snapshot write. Это не общая транзакция со sm.db и не устраняет STO-2/4. [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:95–105](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L95-L105), [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:127–142](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L127-L142).
9. Известное ограничение cached ciphertext при старении receiver chains/message keys и повторном сопряжении уже описано в коде/старой документации. Настоящие пределы libsignal в данном проходе не проверялись runtime-тестом, не выдаю эту известную оговорку за новую находку. [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:68–72](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L68-L72), [releases/v0.0.1-alpha.md:39–45](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/releases/v0.0.1-alpha.md#L39-L45).
10. ADR-151 уже фиксирует отсутствие начального relay address exchange после QR и suppression HTTPS по одной BLE-метке. Его реализация не входила в новый PoC и эти известные маршрутизационные недостатки не увеличивают finding count этого storage-аудита. Код действительно имеет `isNear` по обнаружению и проверку перед sendTo: [android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt:120](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt#L120), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:322–341](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L322-L341), [wiki/architecture/decisions.md:157](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/wiki/architecture/decisions.md#L157).

## 6. Реально выполненные проверки и инструменты

### 6.1 Итог тестов

| Набор | Что реально исполнялось | Результат | Ограничение |
|---|---|---|---|
| sm-core native | Неизменённый `crates/sm-core/src/lib.rs`, rustc --test, zeroize 1.8.2; включая delivery/store/relay/keystore tests | **79 passed, 0 failed** | Прямой rustc, не cargo workspace/CI; workspace lints/deny/vet не воспроизводились |
| FrameCache / AckQueue | Неизменённые FrameCache.kt и FrameCacheTest.kt, Kotlin compiler + JUnit 4.13.2 | **20 passed, 0 failed** | JVM, in-memory fake Signal/kv из самих repository tests; без Android SQLite |
| relay Store + Rust PoC | Неизменённый Store, точная структурная Caller заглушка, извлечённые fetch_any/store_status; native actual sm-core | **12 passed, 0 failed** | 9 repository Store tests + 3 добавленных probes; не весь sm-relay HTTP/TLS server |
| Kotlin audit PoC | Исходные тела openFrame/receive/sendMailbox/dropContact/onIn/get/accept; SignalGate целиком | **Все запущенные assertions прошли** | Persistence/Signature/Signal runtime заглушки; проверяют control-flow, не устройство/libsignal |

Три добавленных Rust probes: STO-7 starvation, STO-8 empty-blob roundtrip и исследовательский ACK incarnation probe. Последний **не является подтверждённой exploitable находкой** — см. ограничения.

Финальный повторный прогон `tests/run-tests.sh` завершился exit 0 после просьбы завершить текущий проход. До успешного прогона были ошибки компиляции стенда (visibility/конструктор SQLiteException, затем отсутствующий store_status и Copy у Caller); исправлялись только стенды, не исходный commit. Эти ранние неуспехи не скрываются и не выдаются за удачные tests.

### 6.2 Файлы воспроизведения

```text
/data/audit-storage/tests/run-tests.sh
/data/audit-storage/tests/build_harness.py
/data/audit-storage/tests/build_extra.py
/data/audit-storage/tests/build_rust_harness.py
/data/audit-storage/tests/AuditHub.kt
/data/audit-storage/tests/Runner.kt
/data/audit-storage/tests/Locking.kt
/data/audit-storage/tests/PeerClock.kt
/data/audit-storage/tests/relay_store_tests.rs
/data/audit-storage/tests/all-tests.log
/data/audit-storage/tests/core-tests.log
/data/audit-storage/tests/frame-tests.log
/data/audit-storage/tests/relay-store-tests.log
/data/audit-storage/tests/run.log
```

Для повторения на этом компьютере, только из собственной папки:

```bash
bash /data/audit-storage/tests/run-tests.sh
```

Различия стендов задокументированы выше. В build_extra сохраняются исходные тела проверяемых Kotlin методов; для onIn меняется тип Gate на тестовый, сохраняя тело. Signature verification в PeerClock принудительно успешна, чтобы изолировать timestamp ordering. SQL model представляет сериализацию транзакций замком, не Android driver. Исследовательские native Rust wrappers используют настоящий Store и Outbox, не их Python-переписывание.

### 6.3 Инструменты

GitHub MCP read-only (`get_commit`, `get_file_contents`), фиксированный codeload tarball, curl, tar, sha256sum/Python hashlib, rg/grep, sed/nl, Python 3 для извлечения методов и проверки архива, Java 25, Kotlin compiler embeddable 2.2.21, Kotlin stdlib 2.2.21, JUnit 4.13.2, rustc 1.99.0 с rust-std. Kotlin compiler не равен версии Gradle-проекта; собирались только выбранные совместимые исходники, не APK.

Rust archives сверены с SHA-256, указанными в stable channel manifest; это transport/source integrity check, не самостоятельный supply-chain аудит инструментов. Toolchain и JVM dependency jars установлены локально в `/data/audit-storage/tools`, глобальные rustup/cargo/Gradle/Android config не изменялись. Для tests sm-core использован локальный zeroize 1.8.2 — та же закреплённая версия, но cargo-vet проверка скачанного инструментария не выполнялась.

## 7. Покрытие и недоведённые проверки

**Глубоко прослежены:** Android ChatHub, FrameCache/AckQueue, MessageStore, SendEntry, SignalGate/SignalKv, ContactBook, SecureDb, DeliveryStore/SnapshotCipher; relay sender/receiver/announcement цепочки и invitation stage transitions; Rust Outbox/Seen + snapshot restore, FFI delivery bridge, Signal PlatformKv callbacks/session lock/decrypt candidates, Rust relay send/fetch/ack/expiry и fetch_any fairness. Связанные BLE/Pairing/HttpsRelay участки прочитаны для достижимости, не как полный аудит транспорта/UI.

**Ограничения:**

- Не запускались APK, Android instrumentation/Robolectric tests, реальные Keystore/AtomicFile/fsync crash tests, Android SQL pool contention, kill-process по каждому instruction boundary.
- Не собирались sm-signal/sm-ffi со всеми libsignal зависимостями; не запускались реальные PQXDH/ratchet/prekey crash/replay tests. Внешний source code закреплённой libsignal глубоко не аудировался. STO-5 оставлен в статусе code proof, а не «воспроизведено на libsignal».
- Не запускались полный `cargo test --workspace`, Gradle tasks, clippy, detekt, Semgrep, Kani, cargo-fuzz, cargo-audit/deny/vet/OSV. Успех выбранных tests не означает успех CI.
- Не проверялись power-loss atomicity, flash wear/corruption, rollback обоих зашифрованных файлов/SQLite на старые валидные версии, hardware-backed key rollback resistance. AEAD не равен freshness — но конкретный эксплуатационный сценарий полного rollback в этом проходе не доказан.
- **ACK incarnation / ABA:** нативный исследовательский probe показал: после delete/create того же recipient ID seq начинается с 0; ACK с seq старой очереди может удалить голову новой. Исходные факты: [crates/sm-relay/src/store.rs:183–194](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/store.rs#L183-L194), [crates/sm-relay/src/store.rs:354–364](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/store.rs#L354-L364). Однако расписание доставки такого stale ACK в текущем Android с единственным relay receiver не доказано; нужны concurrent recipient/реинкарнация/задержанный запрос. Поэтому **не добавлено как STO finding** и не заявлена удалённая атака на текущий клиент.
- **Пропавший snapshot без corruption:** FileNotFoundException даёт Missing, outboxLost остаётся false; Queued история может зависнуть, если outbox файл был отдельно утрачен и SendEntry уже удалён. Не доказано появление такого состояния при штатном AtomicFile crash, поэтому это recovery gap для дальнейшего fault test, не новый finding. [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:58–68](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L58-L68), [android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:109–117](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L109-L117).
- После удаления контакта chat_ct/chat_ack/chat_rx не очищаются в dropContact; lifecycle cached state, replay после повторного добавления и bounded growth не доведены до отдельного exploit/проверки. Не утверждается recovery дефект лишь из факта сохранения этих строк.
- Полный анализ quota exhaustion истории/RX/ACK, adversarial malformed authenticated message ratchet advancement, key/prekey ID collision и cleanup не завершён.
- Развёрнутый relay/nginx, TLS pinning, supply-chain, бинарный ABI UniFFI, контакты/UI и threat model за пределами delivery/storage глубоко не проверялись.
- Не было brute-force/random fuzzing новых stand-alone harnesses. Нативные tests — конечный набор, не доказательство отсутствия других дефектов.

## 8. Приоритет исправлений для родительского отчёта

1. **STO-3:** убрать инверсии всех замков в удалении; Android barrier test параллельно с pairing/invitation.
2. **STO-1/5:** typed processing outcome и общей транзакцией фиксировать invitation Signal+stage; временные storage ошибки никогда не давать relay ACK.
3. **STO-2/4:** durable journal для служебных сообщений и durable cancellation для удаления. Не использовать успешную RAM мутацию как подтверждение долговечного состояния.
4. **STO-7:** bounded fair scheduler inbox, включая invitation priority и nonempty parts.
5. **STO-9:** bounded snapshot input; **STO-6/8:** согласовать маршрут revision и snapshot API invariants.

Это предложения исправлений, не изменения кода. Report/PoC предназначены для объединения родителем. GitHub/Notion commits, PR, issues и release verdicts не создавались.

## Архив исходных локальных стендов

Сохранены для проверки доказательств; пути привязаны к временному окружению, необходимы адаптация ROOT/A и зависимости при повторении. Это не регрессионные тесты production/CI. Исходные snapshot-файлы не изменялись.

### run-tests.sh

```bash
#!/usr/bin/env bash
set -euo pipefail
A=/data/audit-storage
ROOT="$A/secure-messenger-c0c9059b041a787a2d2ac4aa1823b99ed1aaee68"
R="$A/tools/rust/bin/rustc"
CP=$(find "$A/tools" -maxdepth 1 -name '*.jar' | paste -sd:)
python3 "$A/tests/build_extra.py"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d "$A/tests/audit.jar" "$A/tests/"{AuditHub,FrameCache,Runner,PeerClock,Locking}.kt > "$A/tests/compile.log" 2>&1
for runner in dev.sm.messenger.chat.RunnerKt audit.peer.PeerClockKt audit.locks.LockingKt; do java -cp "$A/tests/audit.jar:$CP" "$runner"; done | tee "$A/tests/run.log"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d "$A/tests/frame-tests.jar" "$ROOT/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt" "$ROOT/android/app/src/test/java/dev/sm/messenger/chat/FrameCacheTest.kt" > "$A/tests/frame-compile.log" 2>&1
java -cp "$A/tests/frame-tests.jar:$CP" org.junit.runner.JUnitCore dev.sm.messenger.chat.FrameCacheTest dev.sm.messenger.chat.AckQueueTest | tee "$A/tests/frame-tests.log"
"$R" --edition=2021 --crate-name zeroize --crate-type rlib --cfg 'feature="alloc"' "$A/tools/zeroize-1.8.2/src/lib.rs" -o "$A/tests/libzeroize.rlib"
"$R" --edition=2024 --crate-name sm_core --crate-type rlib "$ROOT/crates/sm-core/src/lib.rs" --extern zeroize="$A/tests/libzeroize.rlib" -L dependency="$A/tests" -o "$A/tests/libsm_core.rlib"
"$R" --edition=2024 --crate-name sm_core --test "$ROOT/crates/sm-core/src/lib.rs" --extern zeroize="$A/tests/libzeroize.rlib" -L dependency="$A/tests" -o "$A/tests/core-tests"
"$A/tests/core-tests" > "$A/tests/core-tests.log"
tail -3 "$A/tests/core-tests.log"
python3 "$A/tests/build_rust_harness.py"
"$R" --edition=2024 --test "$A/tests/relay_store_tests.rs" --extern sm_core="$A/tests/libsm_core.rlib" -L dependency="$A/tests" -o "$A/tests/relay-store-tests" > "$A/tests/relay-compile.log" 2>&1
"$A/tests/relay-store-tests" | tee "$A/tests/relay-store-tests.log"

```

### build_harness.py

```python
from pathlib import Path
root=next(Path('/data/audit-storage').glob('secure-messenger-*'))
base=root/'android/app/src/main/java/dev/sm/messenger'
out=Path('/data/audit-storage/tests')
def method(file,name):
 s=(base/file).read_text(); i=s.index('    '+name); start=s.index('{',i); level=1; j=start+1
 # selected functions have balanced braces even in comments/strings
 while level:
  if s[j]=='{': level+=1
  elif s[j]=='}': level-=1
  j+=1
 return s[i:j]
(out/'FrameCache.kt').write_text((base/'chat/FrameCache.kt').read_text())
methods=[method(Path('chat/ChatHub.kt'),x) for x in ['fun sendMailbox','private fun openFrame','private fun receive','private fun dropContact']]
s='''package dev.sm.messenger.chat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
class SQLiteException(message:String="sqlite"): RuntimeException(message)
sealed class SignalFfiException: RuntimeException() { class Store: SignalFfiException(); class Protocol: SignalFfiException() }
class DeliveryFfiException: RuntimeException()
fun ByteArray.direct(): ByteBuffer = ByteBuffer.wrap(this)
fun contactMailbox(a: String): ByteArray = a.toByteArray()
fun messageIdNew(): ByteArray = ByteArray(16){7}
fun chatEncode(m: ChatMessage): ByteArray = byteArrayOf(1)
sealed class ChatBody { class Text(val text:String): ChatBody(); class Delivered(val target:ByteArray): ChatBody(); class Mailbox(val senderId:ByteArray,val node:String):ChatBody(); class Other:ChatBody() }
data class ChatMessage(val id:ByteArray,val sentAtMs:ULong,val body:ChatBody)
data class Opened(val contact:String,val message:ChatMessage)
data class Contact(val rowId:Long,val address:String)
enum class MessageState { Queued,Delivered,Failed,Received }
enum class MessagePath { Ble,Node }
enum class DiagnosticEvent { ChatFrame,ChatDecrypt,ChatDelivered }
enum class DiagnosticResult { NoKey,Ignored,Signal,Failed,Ok }
object Diagnostics { fun begin(e:DiagnosticEvent)=0L;fun record(e:DiagnosticEvent,r:DiagnosticResult?=null,a:Long=0) {} }
class SecureDb {
 class CorruptRow:RuntimeException()
 val lock=ReentrantLock(); var commits=0; var fail=false
 fun <T:Any> atomic(b:()->T?):T? = lock.withLock { if(fail)throw SQLiteException();val r=b();if(r!=null)commits++;r }
 fun kvDelete(t:String,id:ByteArray) {}
 fun kvAll(t:String):List<ByteArray> = emptyList()
}
class SignalGate(val db:SecureDb) {
 var error:SignalFfiException?=null
 fun <T:Any> atomic(b:()->T?):T?=db.atomic(b)
 fun open(o:List<String>,b:ByteBuffer):Opened? {error?.let{throw it};return null}
 fun forget(a:String) {}
}
class Book {val contacts=listOf(Contact(1,"peer"));fun list()=contacts;fun delete(r:Long) {} }
class Messages {fun setState(a:String,id:ByteArray,s:MessageState,v:MessagePath?=null)=false;fun addIncoming(r:Long,a:String,id:ByteArray,t:String,s:Long)=true;fun deleteContact(r:Long){} }
class Outbox {var present=false;fun push(b:ByteBuffer,id:ByteBuffer,blob:ByteArray,n:ULong){present=true};fun due(n:ULong):List<Due> = if(present)listOf(Due(Key("peer".toByteArray(),ByteArray(16))))else emptyList();fun confirm(b:ByteBuffer,id:ByteBuffer){present=false} }
class Key(val mailbox:ByteArray,val messageId:ByteArray)
class Due(val key:Key)
class Seen {fun firstTime(b:ByteBuffer,id:ByteBuffer){};fun forget(b:ByteBuffer){} }
class Delivery {val outbox=Outbox();val seen=Seen();var canSave=false;var diskPresent=false;fun saveOutboxNow():Boolean {if(canSave)diskPresent=outbox.present;return canSave};fun changed(){};fun now()=1uL}
class Peers {fun accept(a:String,id:ByteArray,n:String,s:Long,now:Long)=true}
class Queue(val recipientId:ByteArray)
class Own {fun get(a:String):List<Queue> = emptyList()}
class Account {val own=Own()}
class Link(val inviter:String="peer",val contact:String="peer",val queue:Queue=Queue(ByteArray(32)),val recipientId:ByteArray=ByteArray(32),val secret:ByteArray=ByteArray(16))
class Invites {fun ins():List<Link> = emptyList();fun outs():List<Link> = emptyList();fun removeIn(a:String){};fun removeOut(s:ByteArray){} }
class SendEntry(val address:String,val id:ByteArray) {companion object {fun decode(b:ByteArray):SendEntry?=null} }
class Notifier {fun message(c:Contact,t:String){};fun clear(c:Contact){} }
internal class AuditHub {
 val db=SecureDb();val io=Executors.newSingleThreadExecutor();var sessions:SignalGate?=SignalGate(db)
 val book=Book();val messages=Messages();val delivery=Delivery();val peers=Peers();val account=Account();val invites=Invites();val acks=AckQueue(256);val notifier=Notifier();var visibleChat:String?=null
 val frames=FrameCache(object:FrameKv {override fun get(t:String,id:ByteArray):ByteArray?=null;override fun put(t:String,id:ByteArray,v:ByteArray){};override fun <T:Any> atomic(b:()->T?)=db.atomic(b)}){a,p->p}
 fun ack(a:String,id:ByteArray){};fun bump(){};fun refreshWaiting(){};fun wake(a:String){};fun replayed(d:ByteArray,c:List<Contact>){}
 fun <T> guard(b:()->T):T?=try{b()}catch(e:SQLiteException){null}catch(e:SignalFfiException){null}
 fun testOpen()=openFrame(byteArrayOf(3),listOf("peer"),MessagePath.Node)
 fun testDrop()=dropContact(book.contacts[0])
 companion object {const val SEND="chat_send";const val READ="chat_read";const val MS_PER_S=1000L}
'''+ '\n\n'.join(methods)+'\n}\n'
(out/'AuditHub.kt').write_text(s)

```

### build_extra.py

```python
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parent))
from build_harness import method,base,out
s=(out/'AuditHub.kt').read_text().replace('ChatFrame,ChatDecrypt,ChatDelivered','ChatFrame,ChatDecrypt,ChatDelivered,InviteStart,InviteFrame').replace('NoKey,Ignored,Signal,Failed,Ok','NoKey,Ignored,Signal,Failed,Ok,Start,AskContact')
s+='''
enum class InStage { Sent,Ask }
class InLink(val inviter:String,val identityKey:ByteArray,val inviterSender:ByteArray,val inviterNode:String,val expires:Long,val queue:Queue,val stage:InStage,val name:String="",val beaconKey:ByteArray=ByteArray(32))
sealed class PairMessage {class Start(val ciphertext:ByteArray):PairMessage()}
class Hello(val name:String="peer",val beaconKey:ByteArray=ByteArray(32))
class Accepted(val hello:Hello=Hello())
class InviteGate {var consumed=false;var storeError=false;fun acceptStart(b:ByteBuffer,k:ByteArray):Accepted {if(storeError)throw SignalFfiException.Store();if(consumed)throw SignalFfiException.Protocol();consumed=true;return Accepted()}}
class InviteLinks {var fail=true;var stage=InStage.Sent;fun putIn(l:InLink){if(fail)throw SQLiteException();stage=l.stage}}
class Outcome
class AuditInvite {val links=InviteLinks();fun publish(o:Outcome){}
'''+method(Path('relay/InviteFlow.kt'),'private fun onIn').replace('gate: SignalGate','gate: InviteGate')+'''
 fun run(g:InviteGate,l:InLink)=onIn(g,l,PairMessage.Start(byteArrayOf(1)))
}
'''
(out/'AuditHub.kt').write_text(s)
# PeerMailboxes get/accept methods copied verbatim; verified signed node simulated only.
s='''package audit.peer
import java.nio.ByteBuffer
class NodeFfiException:RuntimeException()
fun nodeRecordOpen(node:String,now:ULong){}
class SecureDb {val rows=HashMap<String,ByteArray>();fun kvGet(t:String,k:ByteArray)=rows[String(k)];fun kvPut(t:String,k:ByteArray,b:ByteArray){rows[String(k)]=b}}
class PeerMailbox(val senderId:ByteArray,val node:String,val sentAt:Long)
class PeerMailboxes(val db:SecureDb) {
'''+method(Path('relay/PeerMailboxes.kt'),'fun get')+'\n'+method(Path('relay/PeerMailboxes.kt'),'fun accept')+'''
 private fun key(a:String)=a.toByteArray()
 companion object {const val TABLE="relay_peer";const val HEAD=40;const val ID_LEN=32}
}
fun main(){
 val p=PeerMailboxes(SecureDb());val old=byteArrayOf(1)+ByteArray(31);val new=byteArrayOf(2)+ByteArray(31)
 check(p.accept("peer",old,"signed-node",Long.MAX_VALUE,1))
 check(!p.accept("peer",new,"signed-node",1791878400000,1))
 check(p.get("peer")!!.senderId.contentEquals(old))
 println("STO-6 PASS: authenticated future timestamp pins old mailbox; normal-time update rejected")
}
'''
(out/'PeerClock.kt').write_text(s)
# Actual SignalGate class; platform persistence represented by a reentrant transaction lock.
s='''package audit.locks
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
class SignalFfiException {class Store:RuntimeException()}
class StartedSession
class AcceptedSession
class OpenedMessage
class ContactHello
class SecureDb {val tx=ReentrantLock();val gateAtSql=CountDownLatch(1);fun <T:Any> atomic(b:()->T?):T? {if(Thread.currentThread().name=="pairing")gateAtSql.countDown();return tx.withLock(b)}}
class SignalSessions {
 fun cardFrame(b:ByteArray?)=ByteArray(1)
 fun acceptCard(b:ByteBuffer,p:ByteArray?,s:ByteArray?,h:ContactHello)=StartedSession()
 fun linkCardFrame(b:ByteBuffer,id:ByteBuffer,n:String)=ByteArray(1)
 fun acceptLinkCard(b:ByteBuffer,p:ByteBuffer,id:ByteBuffer,n:String,s:ByteBuffer,h:ContactHello)=StartedSession()
 fun acceptStart(b:ByteBuffer,id:ByteArray?)=AcceptedSession()
 fun replyFrame(c:String,h:ContactHello)=ByteArray(1)
 fun openReply(c:String,b:ByteBuffer)=ContactHello()
 fun forget(c:String){}
 fun encrypt(c:String,b:ByteBuffer)=ByteArray(1)
 fun open(c:List<String>,b:ByteBuffer):OpenedMessage?=null
}
'''
g=(base/'contacts/SignalGate.kt').read_text();s+=g[g.index('class SignalGate'):]
s+='''
fun main(){
 val db=SecureDb();val gate=SignalGate(SignalSessions(),db)
 val sqlOwned=CountDownLatch(1)
 val deletion=Thread({db.atomic {sqlOwned.countDown();check(db.gateAtSql.await(2,TimeUnit.SECONDS));gate.forget("peer");true}},"chat-delete").apply{isDaemon=true}
 deletion.start();check(sqlOwned.await(2,TimeUnit.SECONDS))
 val pairing=Thread({gate.cardFrame(null)},"pairing").apply{isDaemon=true};pairing.start()
 Thread.sleep(300)
 check(deletion.isAlive && pairing.isAlive)
 val stacks=deletion.stackTrace.joinToString("\n")+pairing.stackTrace.joinToString("\n")
 check(stacks.contains("SignalGate.atomic"));check(stacks.contains("SecureDb.atomic"))
 println("STO-3 PASS: actual SignalGate + modeled SQL lock: DB->Gate versus Gate->DB deadlock")
 println("delete="+deletion.state+" pairing="+pairing.state)
}
'''
# Escape literal line separators inside joinToString Kotlin.
s=s.replace('joinToString("\n")','joinToString("\\n")')
(out/'Locking.kt').write_text(s)

```

### build_rust_harness.py

```python
from pathlib import Path
root=next(Path('/data/audit-storage').glob('secure-messenger-*'))
s=(root/'crates/sm-relay/src/server.rs').read_text();a=s.index('fn fetch_any(');i=s.index('{',a);depth=1;j=i+1
while depth:
 if s[j]=='{':depth+=1
 elif s[j]=='}':depth-=1
 j+=1
fetch=s[a:j]
a=s.index('fn store_status(');i=s.index('{',a);depth=1;j=i+1
while depth:
 if s[j]=='{':depth+=1
 elif s[j]=='}':depth-=1
 j+=1
fetch+='\n'+s[a:j]
p='''use sm_core::relay::Status;
mod pass {
 #[derive(Debug,Clone,Copy,PartialEq,Eq)]
 pub struct Caller {pub id:[u8;16],pub per_hour:u16,pub parent:[u8;16],pub expires:u64}
}
#[path="'''+str(root/'crates/sm-relay/src/store.rs')+'''"]
mod store;
use store::{Store,QueueId,StoreError};
'''+fetch+'''
#[test]
fn sto_7_refilled_first_queue_starves_second() {
 let mut s=Store::new(store::Limits::default());let a=[1;32];let sa=[2;32];let b=[3;32];let sb=[4;32];
 s.create(a,sa,1).unwrap();s.create(b,sb,1).unwrap();s.send(&sb,vec![99],1).unwrap();
 for _ in 0..1000 {s.send(&sa,vec![7],1).unwrap();let (status,data)=fetch_any(&mut s,&[a,b],1).unwrap();assert_eq!(status,Status::Ok);assert_eq!(data[0],0);let seq=u64::from_be_bytes(data[1..9].try_into().unwrap());s.ack(&a,seq,1).unwrap();}
 assert_eq!(s.fetch(&b,1).unwrap().unwrap().1,vec![99]);
}
#[test]
fn investigation_ack_has_no_queue_incarnation() {
 let mut s=Store::new(store::Limits::default());let a=[1;32];let sa=[2;32];s.create(a,sa,1).unwrap();s.send(&sa,vec![1],1).unwrap();let seq=s.fetch(&a,1).unwrap().unwrap().0;
 s.delete(&a,1).unwrap();s.create(a,sa,1).unwrap();s.send(&sa,vec![2],1).unwrap();s.ack(&a,seq,1).unwrap();assert!(s.fetch(&a,1).unwrap().is_none());
}
#[test]
fn sto_8_empty_outbox_breaks_own_snapshot_restore() {
 use sm_core::{delivery::{Outbox,OutboxLimits},message::MessageId,transport::MailboxId};
 let mut o=Outbox::new(OutboxLimits::DEFAULT);o.push((MailboxId([1;32]),MessageId([2;16])),vec![],0).unwrap();let snap=o.encode(1).unwrap();assert!(Outbox::restore(&snap,OutboxLimits::DEFAULT,1,0).is_err());
}
'''
Path('/data/audit-storage/tests/relay_store_tests.rs').write_text(p)

```

### Runner.kt

```kotlin
package dev.sm.messenger.chat
fun main() {
 val h=AuditHub()
 h.sessions!!.error=SignalFfiException.Store()
 check(h.testOpen()) {"expected buggy relay ACK eligibility"}
 check(h.db.commits==0)
 println("STO-1 PASS: Store failure => handled=true, SQL receive committed nothing; relay may ACK")
 h.sessions!!.error=SignalFfiException.Protocol()
 check(h.testOpen());println("CONTROL PASS: invalid frame also handled=true")
 check(h.sendMailbox("peer",ByteArray(32),"node"))
 check(h.delivery.outbox.present&&!h.delivery.diskPresent)
 println("STO-2 PASS: sendMailbox=true with no durable outbox; caller can persist own/told")
 h.delivery.canSave=true;h.delivery.outbox.present=true
 h.db.fail=true
 check(runCatching{h.testDrop()}.exceptionOrNull() is SQLiteException);check(!h.delivery.diskPresent)
 println("STO-4 PASS: SQL deletion failed but durable outbox already empty")
 val q=AckQueue(256);q.add("peer",byteArrayOf(1));val frames=mutableListOf<ByteArray>()
 q.drain("peer",frames,32){byteArrayOf(9)}
 check(q.pending().isEmpty());check(frames.size==1)
 println("CONTROL PASS: ACK drain is volatile; retry requires original frame replay")
 val invite=AuditInvite();val gate=InviteGate()
 val l=InLink("peer",ByteArray(33),ByteArray(32),"node",999,Queue(ByteArray(32)),InStage.Sent)
 check(runCatching{invite.run(gate,l)}.exceptionOrNull() is SQLiteException)
 check(gate.consumed && invite.links.stage==InStage.Sent)
 invite.links.fail=false
 check(invite.run(gate,l));check(invite.links.stage==InStage.Sent)
 println("STO-5 PASS: Signal commit then invite-write failure; replay handled=true, stage remains Sent (ratchet stub)")
 val failed=InviteGate();failed.storeError=true
 check(invite.run(failed,l))
 println("STO-1 INVITE PASS: Store failure in acceptStart is also handled=true")
 h.io.shutdown()
}

```

### Locking.kt

```kotlin
package audit.locks
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
class SignalFfiException {class Store:RuntimeException()}
class StartedSession
class AcceptedSession
class OpenedMessage
class ContactHello
class SecureDb {val tx=ReentrantLock();val gateAtSql=CountDownLatch(1);fun <T:Any> atomic(b:()->T?):T? {if(Thread.currentThread().name=="pairing")gateAtSql.countDown();return tx.withLock(b)}}
class SignalSessions {
 fun cardFrame(b:ByteArray?)=ByteArray(1)
 fun acceptCard(b:ByteBuffer,p:ByteArray?,s:ByteArray?,h:ContactHello)=StartedSession()
 fun linkCardFrame(b:ByteBuffer,id:ByteBuffer,n:String)=ByteArray(1)
 fun acceptLinkCard(b:ByteBuffer,p:ByteBuffer,id:ByteBuffer,n:String,s:ByteBuffer,h:ContactHello)=StartedSession()
 fun acceptStart(b:ByteBuffer,id:ByteArray?)=AcceptedSession()
 fun replyFrame(c:String,h:ContactHello)=ByteArray(1)
 fun openReply(c:String,b:ByteBuffer)=ContactHello()
 fun forget(c:String){}
 fun encrypt(c:String,b:ByteBuffer)=ByteArray(1)
 fun open(c:List<String>,b:ByteBuffer):OpenedMessage?=null
}
class SignalGate internal constructor(private val sessions: SignalSessions, private val db: SecureDb) {
    private val lock = ReentrantLock()

    /** [block] под замком сессий и в одной транзакции: исключение или `null` — откат. */
    internal fun <T : Any> atomic(block: () -> T?): T? = lock.withLock { db.atomic(block) }

    private fun <T : Any> call(block: () -> T): T = atomic(block) ?: throw SignalFfiException.Store()

    fun cardFrame(qrSecret: ByteArray?): ByteArray = call { sessions.cardFrame(qrSecret) }

    fun acceptCard(card: ByteBuffer, proof: ByteArray?, qrSecret: ByteArray?, hello: ContactHello): StartedSession =
        call { sessions.acceptCard(card, proof, qrSecret, hello) }

    fun linkCardFrame(linkSecret: ByteBuffer, senderId: ByteBuffer, node: String): ByteArray =
        call { sessions.linkCardFrame(linkSecret, senderId, node) }

    fun acceptLinkCard(
        card: ByteBuffer,
        proof: ByteBuffer,
        senderId: ByteBuffer,
        node: String,
        linkSecret: ByteBuffer,
        hello: ContactHello,
    ): StartedSession = call { sessions.acceptLinkCard(card, proof, senderId, node, linkSecret, hello) }

    fun acceptStart(ciphertext: ByteBuffer, expectedIdentity: ByteArray?): AcceptedSession =
        call { sessions.acceptStart(ciphertext, expectedIdentity) }

    fun replyFrame(contact: String, hello: ContactHello): ByteArray = call { sessions.replyFrame(contact, hello) }

    fun openReply(contact: String, ciphertext: ByteBuffer): ContactHello = call { sessions.openReply(contact, ciphertext) }

    fun forget(contact: String) = call { sessions.forget(contact) }

    fun encrypt(contact: String, plaintext: ByteBuffer): ByteArray = call { sessions.encrypt(contact, plaintext) }

    /** Сообщение от одного из [candidates] или `null` (ничего не записано). */
    fun open(candidates: List<String>, ciphertext: ByteBuffer): OpenedMessage? = atomic { sessions.open(candidates, ciphertext) }
}

fun main(){
 val db=SecureDb();val gate=SignalGate(SignalSessions(),db)
 val sqlOwned=CountDownLatch(1)
 val deletion=Thread({db.atomic {sqlOwned.countDown();check(db.gateAtSql.await(2,TimeUnit.SECONDS));gate.forget("peer");true}},"chat-delete").apply{isDaemon=true}
 deletion.start();check(sqlOwned.await(2,TimeUnit.SECONDS))
 val pairing=Thread({gate.cardFrame(null)},"pairing").apply{isDaemon=true};pairing.start()
 Thread.sleep(300)
 check(deletion.isAlive && pairing.isAlive)
 val stacks=deletion.stackTrace.joinToString("\n")+pairing.stackTrace.joinToString("\n")
 check(stacks.contains("SignalGate.atomic"));check(stacks.contains("SecureDb.atomic"))
 println("STO-3 PASS: actual SignalGate + modeled SQL lock: DB->Gate versus Gate->DB deadlock")
 println("delete="+deletion.state+" pairing="+pairing.state)
}

```

### PeerClock.kt

```kotlin
package audit.peer
import java.nio.ByteBuffer
class NodeFfiException:RuntimeException()
fun nodeRecordOpen(node:String,now:ULong){}
class SecureDb {val rows=HashMap<String,ByteArray>();fun kvGet(t:String,k:ByteArray)=rows[String(k)];fun kvPut(t:String,k:ByteArray,b:ByteArray){rows[String(k)]=b}}
class PeerMailbox(val senderId:ByteArray,val node:String,val sentAt:Long)
class PeerMailboxes(val db:SecureDb) {
    fun get(address: String): PeerMailbox? {
        val b = db.kvGet(TABLE, key(address)) ?: return null
        if (b.size <= HEAD) return null
        val buf = ByteBuffer.wrap(b)
        val sentAt = buf.long
        val id = ByteArray(ID_LEN).also { buf.get(it) }
        return PeerMailbox(id, String(b, HEAD, b.size - HEAD, Charsets.US_ASCII), sentAt)
    }
    fun accept(address: String, senderId: ByteArray, node: String, sentAt: Long, nowSecs: Long): Boolean {
        if (senderId.size != ID_LEN) return false
        try {
            nodeRecordOpen(node, nowSecs.toULong())
        } catch (_: NodeFfiException) {
            return false
        }
        val old = get(address)
        if (old != null && old.sentAt >= sentAt) return false
        val text = node.toByteArray(Charsets.US_ASCII)
        val row = ByteBuffer.allocate(HEAD + text.size).putLong(sentAt).put(senderId).put(text).array()
        db.kvPut(TABLE, key(address), row)
        return true
    }
 private fun key(a:String)=a.toByteArray()
 companion object {const val TABLE="relay_peer";const val HEAD=40;const val ID_LEN=32}
}
fun main(){
 val p=PeerMailboxes(SecureDb());val old=byteArrayOf(1)+ByteArray(31);val new=byteArrayOf(2)+ByteArray(31)
 check(p.accept("peer",old,"signed-node",Long.MAX_VALUE,1))
 check(!p.accept("peer",new,"signed-node",1791878400000,1))
 check(p.get("peer")!!.senderId.contentEquals(old))
 println("STO-6 PASS: authenticated future timestamp pins old mailbox; normal-time update rejected")
}

```

### Результаты повторного прогона координатором

```text
STO-1 PASS: Store failure => handled=true, SQL receive committed nothing; relay may ACK
CONTROL PASS: invalid frame also handled=true
STO-2 PASS: sendMailbox=true with no durable outbox; caller can persist own/told
STO-4 PASS: SQL deletion failed but durable outbox already empty
CONTROL PASS: ACK drain is volatile; retry requires original frame replay
STO-5 PASS: Signal commit then invite-write failure; replay handled=true, stage remains Sent (ratchet stub)
STO-1 INVITE PASS: Store failure in acceptStart is also handled=true
STO-6 PASS: authenticated future timestamp pins old mailbox; normal-time update rejected
STO-3 PASS: actual SignalGate + modeled SQL lock: DB->Gate versus Gate->DB deadlock
delete=WAITING pairing=WAITING
JUnit version 4.13.2
....................
Time: 0.168

OK (20 tests)


test result: ok. 79 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.11s


running 12 tests
test investigation_ack_has_no_queue_incarnation ... ok
test sto_8_empty_outbox_breaks_own_snapshot_restore ... ok
test sto_7_refilled_first_queue_starves_second ... ok
test store::tests::debug_hides_contents ... ok
test store::tests::ids_are_not_interchangeable ... ok
test store::tests::limits ... ok
test store::tests::open_is_idempotent_and_limited_per_pass ... ok
test store::tests::per_queue_bytes_and_new_rate ... ok
test store::tests::send_fetch_ack_in_order ... ok
test store::tests::tickets_expire_cost_pass_limit_and_repeat_the_same_pass ... ok
test store::tests::ttl_and_idle_expiry ... ok
test store::tests::tickets_are_capped ... ok

test result: ok. 12 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s


```
