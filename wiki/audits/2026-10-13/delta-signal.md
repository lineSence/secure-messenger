# Read-only delta security audit — Signal / pairing

База: `c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`; проверенная голова: `9595f2e127b96dd37fa81fd32d41f18bd48e9896`. Дата: 2026-10-13. Локальный checkout и предоставленный diff; без изменений кода, GitHub/Notion, субагентов и установки инструментов.

## Краткая таблица (сохранена до деталей)

| ID | Уровень | Вывод | Доказательство / статус |
|---|---|---|---|
| DELTA-SIG-1 | Medium | Отмена mailbox-этапа не отменяет его durable side effects; подготовка может записать почту/own/announced уже после удаления контакта | Достижимое расписание по коду; детализация ниже |
| DELTA-SIG-2 | Low | Failure-tombstone принимается за Signal ACK: возможен `ready=true` без подтверждения peer | Настоящие FrameCache/PairMailboxExchange, isolated JVM probe passed |
| AUD-09 | Low, открыт / частично mitigated | Signal ACK улучшает mailbox-подэтап, но не меняет сохранение контакта по GATT ACK Reply; timeout/loss/no-node всё равно заканчиваются Done | Не считать исправленным |
| AUD-11 | Существенно mitigated | Добавлен SEND-журнал служебного сообщения до push; crash-consistency всего механизма не подтверждена Android-тестом | Не полный release verdict |
| AUD-04/05/06/07/08/10/12/13/16/17 | Не закрыты этим delta | Соответствующие корневые механизмы не исправлены в проверенной области | Подробнее ниже |

Ограниченный проход завершён; результаты изолированных тестов и пределы доказательств приведены ниже.

## DELTA-SIG-1 — Нет fencing durable side effects после отмены/удаления

**Severity: Medium**, локальная ошибка согласованности/отзыва, не доказанное чтение чужой переписки. **Доказательство: достижимое расписание по исходникам**, не Android reproduction. Новый путь `preparePairMailbox` вызывается отдельным pairing-worker и продолжает работу после закрытия/тайм-аута; старый relay-путь тоже имеет сходное окно удаления, но delta добавляет новый конкретный источник stale writes.

### Места
- [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:293–302](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L293-L302)
- [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:367–401](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L367-L401)
- [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:304–319](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L304-L319)
- [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:345–365](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L345-L365)
- [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:506–528](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L506-L528)
- [android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt:111–119](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt#L111-L119)

### Условия и цепочка
1. Контакт уже сохранён; pairing-worker входит в `preparePairMailbox`, читает own и блокируется на `NEW` (или перед queueMailbox). `mailboxLock` не захватывается удалением контакта.
2. `finishMailbox`/`reset` закрывает экран/канал. Timeout и loss не меняют generation; reset меняет, но проверка generation стоит лишь после `exchange.step`, на выдаче результата в UI, не перед durable записями.
3. Пользователь удаляет этот контакт. `dropContact` на ChatHub.io успешно стирает Signal/контакт/почту/SEND/outbox. В расписании NEW ещё ожидает; поэтому старый AUD-06 deadlock не необходим.
4. NEW завершается. `queueMailbox` не проверяет существование или incarnation контакта: на том же io после удаления пишет новый SEND и outbox. Затем `own.put` и `announced.mark` заново создают удалённые строки. Даже если устаревший UI callback отвергнут, эти записи уже произошли.

**Влияние:** durable мусор/очередь для удалённого контакта, незакрытая удалённая очередь, потенциальное использование stale адресного состояния при следующем добавлении того же identity. Контактная строка и забытая Signal-сессия этим сценарием сами не восстанавливаются; отправку читаемого сообщения удалённому peer не утверждаю. Просто закрыть экран после успешного pairing допустимо по ADR-151; дефект — не само продолжение доставки, а отсутствие сериализации с удалением и проверки жизненного цикла контакта.

**Исправление:** fencing по incarnation контакта, проверка существования/версии и запись SEND/own/announced через один координатор с удалением. Сетевой NEW — вне DB/Signal locks; перед локальным commit повторно проверить incarnation, при stale выполнить компенсирующий DEL. Не исправлять только проверкой generation перед UI post и не вводить новый SQL→Signal lock inversion.

**Регрессия:** barrier на NEW, закрыть экран, удалить контакт до снятия barrier, завершить NEW; assert отсутствие SEND/outbox/own/told и учёт компенсации удалённой очереди. Эта полная интеграционная регрессия здесь не запускалась.

## DELTA-SIG-2 — Failure-tombstone выдаётся за authenticated delivery

**Severity: Low**, ложная диагностика готовности при локальной порче кэша; не обход Signal MAC. Входящее Signal Delivered здесь не требуется.

### Места
- [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:287–299](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L287-L299)
- [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:371–372](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L371-L372)
- [android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt:55–60](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/chat/FrameCache.kt#L55-L60)
- [android/app/src/main/java/dev/sm/messenger/contacts/PairMailboxExchange.kt:33–40](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/PairMailboxExchange.kt#L33-L40)

**Условия:** mailbox уже попал в outbox; сохранённый CT-кадр для этого ID повреждён и чтение даёт `CorruptFrame`; у контакта имеется входящий peer route. Например, другая сторона успела прислать свой Mailbox, но наше сообщение не приняла. Это локальная fault-ветка, не доступ обычного BLE-атакующего к приватной SQLite.

**Доказательство:** `gather` ловит CorruptFrame, удаляет сообщение из outbox и вызывает `frames.delivered(address,id)` как механизм запрета повторного шифрования. Для служебного Mailbox строки истории нет. Новое `isDelivered` интерпретирует то же пустое значение CT как настоящий ACK. При следующем пустом gather `PairMailboxExchange` возвращает Complete(true), и Pairing пишет ChatMailbox/Ok. На настоящем пути ACK был бы `receive → ChatBody.Delivered → frames.delivered`; здесь он не выполнялся.

**Исправление:** различать типизированные durable terminal states DeliveredByPeer и Failed/Discarded; mailboxDelivered должен принимать только первый. При corrupt ciphertext сохранять запрет повторного шифрования, но возвращать false/failed, а не delivery success.

**Тест:** изолированный JVM probe использует настоящие `FrameCache` и `PairMailboxExchange`, создаёт tombstone тем же вызовом, что fault-ветка gather, без Signal ACK; ожидаемое ошибочное Complete(true) подтверждается. Сам ChatHub/Android SQL/Keystore в probe не исполняется.

## AUD-09: исправлен ли handshake completion?

**Нет. Частично mitigated только новый вспомогательный mailbox-подэтап.**

- [android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:430–442](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L430-L442) по-прежнему отправляет GATT_SUCCESS до вызова listener и Signal `openReply`/`book.save` сервера.
- [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:524–532](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L524-L532) сохраняет клиентский контакт в callback GATT Reply, ещё до доказательства durable сохранения Reply на сервере. Server-side сохранение находится в [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:654–660](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L654-L660).
- Новый mailbox ACK действительно проходит Signal decrypt и сохраняет CT tombstone в `SignalGate.atomic`: [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:393–417](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L393-L417), [android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:452–460](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L452-L460). Happy-path получение такого ACK означает, что другая сторона имеет работающую сессию и обработала сообщение; это полезное более сильное свидетельство, чем GATT. Replay обрабатывается сохранённым digest, а не повторным ratchet decrypt.
- Но контакты сохранены **до** mailbox ACK. Complete(false), отсутствие node/pass, 60-секундный timeout и `onLost` вызывают тот же `finishMailbox → done`: [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:396–401](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L396-L401), [android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:700–703](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L700-L703). Если сервер упал/не сохранил Reply после GATT_SUCCESS, клиент всё равно может оказаться с контактом и Done. Это ровно исходная асимметрия AUD-09; новый ACK не является обязательным handshake commit.
- `hasPeer` означает наличие любой записи, не принятие актуального маршрута; `peers.accept` возвращает false для отвергнутого/старого node, но ChatHub всё равно remembers и ACKs Mailbox. Поэтому ACK доказывает обработку, не согласие использовать новый маршрут. См. [android/app/src/main/java/dev/sm/messenger/relay/PeerMailboxes.kt:36–51](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/relay/PeerMailboxes.kt#L36-L51) и receive выше. DELTA-SIG-2 дополнительно опровергает безусловное равенство tombstone=ACK.

**Рекомендация:** отдельно моделировать local-added, peer-confirmed handshake и mailbox-ready. Если offline-сохранение по ADR-151 намеренно, не заявлять bilateral completion; для исходного AUD-09 нужен durable authenticated Reply-completion с идентификатором обмена и recoverable retry, а не обязательная работоспособность relay.

## Изменение статуса прежних AUD-n

| Старый ID | Статус в этом delta |
|---|---|
| AUD-04 | Открыт: ротация self/beacon не добавлена. |
| AUD-05 | Открыт: InviteFlow изменён только wake; raw Decline / удаление Started не аутентифицированы. |
| AUD-06 | Открыт: dropContact остаётся DB.atomic → sessions.forget; SignalGate порядок не изменён. |
| AUD-07 | Открыт: ownership/expiry/quota prekeys не появились. |
| AUD-08 | Открыт: openReply и book.save остаются отдельными durable шагами; mailbox не даёт crash recovery handshake. |
| AUD-09 | Открыт, happy-path последствия уменьшены; подробно выше. |
| AUD-10 | Открыт: receive продолжает ловить весь SignalFfiException; typed transient-store outcome не добавлен. |
| AUD-11 | Исходная дырка «успех после failed snapshot без journal» исправлена по коду: queueMailbox пишет SEND до push, success snapshot удаляет journal, failure оставляет. recoverQueue читает SEND, в том числе Mailbox. Android kill/fsync verification не проведена; не заявляю исправление всех recovery/full-outbox сценариев. |
| AUD-12 | Открыт: outbox очищается/сохраняется до DB-транзакции удаления. |
| AUD-13 | Открыт: sentAt сравнение и фиксация future route не изменены. |
| AUD-16 | Открыт: confidentiality LinkCard/relay pre-session этим diff не менялась. |
| AUD-17 | Не исправлен в проверенных pairing-путях; полного повторного memory-hygiene аудита нет. |
| AUD-01/02/03/14/15 | Вне данного ограниченного направления: не переоценены и не закрыты. |

## Budgets, cancellation, trust, ACK/replay — ограничения

- Новые completed mailbox-кадры ограничены глобальным InboundBudget: 32 кадра / 1 MiB; worker release в finally и generation-filter при reset. `onPairFrame` проверяет zero mailbox и использует только сохранённый выбранный контакт, не все Signal-сессии. По просмотренной цепочке новый plaintext/trust bypass не найден. verified флаг наследуется от исходного QR/nearby пути, mailbox его не повышает.
- Лимит стоит **после** копирования и main.post в PairLink. Raw GATT callbacks/части и обычные PairMessage work-задачи этим механизмом не ограничены. Прежний HYP-PAIR-BUDGET не закрыт; flood/OOM/ANR тест не выполнен, отдельным новым подтверждённым finding не считается.
- MAX_FRAMES=8 ограничивает один batch, не доказывает свободные 8 слотов PairLink; очередь могла уже содержать другие кадры. written учитывает token batch; duplicate callback внутри того же batch не имеет per-frame identity. Реальная возможность лишних callbacks от Android/peer не доказана, поэтому это не новый exploit.
- 60 секунд — deadline UI state machine, **не** общего выполнения. `prepare`/`frames`/`delivered` блокируют worker; UI timeout не прерывает HTTP или Future.get. `preparePairMailbox` и ensureQueues держат общий mailboxLock во время сетевого NEW и ChatHub Future.get. Медленный relay способен задержать не только этот экран, но и подготовку очередей других контактов.
- HttpsRelay сохраняет per-read timeout, без total deadline: [android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt:164–207](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L164-L207), [android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt:211–219](https://github.com/lineSence/secure-messenger/blob/9595f2e127b96dd37fa81fd32d41f18bd48e9896/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L211-L219). Slow-body/lock-contention тест здесь не проводился. Старый HYP-HTTPS-DEADLINE получает новый путь влияния на pairing; это ограничение/кандидат, не доказанный отдельный удалённый DoS.
- done/timeout не меняет generation, поэтому уже поставленные mailboxFrame задачи могут обработаться после закрытия канала; после reset generation проверяется до входа в ChatHub, но уже исполняющийся вызов не отменяется. Это не само по себе trust bypass, но требует incarnation fencing (DELTA-SIG-1).

## Покрытие и реальные пределы

Прочитаны AGENTS.md/CLAUDE.md, rules/code, references/ai-security-review, architecture/signal и overview, ADR-151, прежний report и релевантное signal-приложение. Изучены delta и актуальные Pairing/PairLink/PairMailboxExchange, ChatHub/FrameCache, preparePairMailbox/ensureQueues, PeerMailboxes/OwnQueues, удаление контактов и HttpsRelay. Предоставленный new-code.diff байт-в-байт совпал с `git diff BASE HEAD -- android` (cmp=0). Полный git delta дополнительно включает wiki/memory/tools; они не являются расширением заявленной области security-аудита.

Read-only означает: checkout не редактировался; файлы отчёта и isolated harness находятся вне repo. Не запускались Gradle/APK, реальные устройства/GATT, relay/TLS traffic, Keystore/SQLite instrumentation, crash-kill tests, libsignal full audit или release verdict. Прочие AUD-n не превращены в «исправлены» из-за зелёных unit tests. Репозиторный lint не запускался: wiki не менялась, требование read-only приоритетно.

### Выполненные локальные проверки

- Kotlin compiler 2.2.21 / JUnit из уже существующего `/data/audit-storage/tools`; ничего не установлено и не скачано. Собственный каталог: `/data/security-fix/delta-sig-tests.5yzvoO`.
- Скомпилированы неизменённые текущие FrameCache.kt и PairMailboxExchange.kt, 9 существующих PairMailboxExchangeTest и один DeltaProbe. **OK (10 tests)**.
- DeltaProbe — characterization test: подтверждает ошибочное Complete(true) без Signal ACK; это не passing security invariant. Полный `gather` заменён его конкретным вызовом `frames.delivered` из CorruptFrame-ветки; эта граница явно ограничивает доказательство.
- Логи: `compile.log`, `run.log`, исходник `DeltaProbe.kt`, артефакт `tests.jar` в указанном temp-каталоге.
- DELTA-SIG-1 остаётся статически доказанным расписанием; isolated cancellation/Android regression не выполнена.
- `git status --porcelain` после теста: пусто. HEAD совпал с заданным SHA. SHA-256 new-code.diff: `4fbe1b032080324479d22bf5611e27a3ac581087815f9b74c11f72aaa9b2740f`.

Итого: **2 delta findings (Medium по коду, Low с ограниченным JVM probe)**. AUD-09 не закрыт; AUD-11 устранён в исходном сценарии по коду, без аппаратного crash-verdict. Это не одобрение релиза.
