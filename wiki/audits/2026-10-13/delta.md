# Дополнение аудита: изменения PR #89

Дата: 2026-10-13. Первичный кодовый срез: `c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`; повторно проверена delta до **`9595f2e127b96dd37fa81fd32d41f18bd48e9896`**. PR #89 уже слит коммитом `d0eb60ef8e70dc10607105ece7fc68fd710be822`. Это новый временной срез; старые выводы не переписываются задним числом. [PR89](https://github.com/lineSence/secure-messenger/pull/89), [новый SHA](https://github.com/lineSence/secure-messenger/commit/9595f2e127b96dd37fa81fd32d41f18bd48e9896).

Четыре отдельные read-only сессии завершились штатно. Проверены 21 изменённый Android-файл и связанные границы. После дедупликации добавлены **6 находок: 4 Medium, 2 Low**. Общий исторический реестр: **23 ID — 1 High, 16 Medium, 6 Low**. Это не количество удалённых exploits и не release verdict.

| Новый ID | Severity | Находка | Исходные ID | Доказательство |
|---|---|---|---|---|
| AUD-18 | Medium | Full при replay удаляет journal не восстановленного Mailbox | DELTA-STO-1 | Исходные методы на JVM, quota/persistence модели |
| AUD-19 | Medium | Поздний NEW/mailbox prepare после удаления создаёт orphan SEND/own/told без fencing incarnation | DELTA-SIG-1 + DELTA-STO-2 | Достижимое расписание + JVM barrier model; STO оценил Low, итог Medium учитывает новый lifecycle-path |
| AUD-20 | Low | Failure tombstone считается настоящим Signal Delivered и даёт ложный mailbox ready | DELTA-SIG-2 + DELTA-STO-3 + DELTA-PLAT-1 | Настоящие FrameCache/PairMailboxExchange и fault probe |
| AUD-21 | Medium | Failed relay consumes shared retry до SEND и подавляет исправный BLE | DELTA-NET-1 | Native Outbox + заданный scheduler, не Android race reproduction |
| AUD-22 | Medium | Blocking mailbox prepare под общим lock/worker удерживает глобальные BLE permits после UI timeout | DELTA-NET-2 | Настоящий state machine/budget + executor/port model |
| AUD-23 | Low | Sender backoff через sleep не прерывается wake/сменой сети | DELTA-NET-3 | Control-flow по коду, не Android timing |

Источники условий/точных SHA-permalinks/исправлений: [Signal delta](delta-signal.md), [Storage delta](delta-storage.md), [Network delta](delta-network.md), [Platform delta](delta-platform.md).

## Статус прежних находок

- **AUD-11:** исходная потеря служебного Mailbox при failed snapshot mitigated SEND-журналом. Не считать полностью закрытой: AUD-18 демонстрирует потерю этого журнала при Full во время recovery.
- **AUD-09:** mailbox happy-path усилен Signal ACK, но контакт сохранён до него и timeout/no-node/loss всё ещё завершает старый путь. Только partial mitigation, не bilateral handshake fix.
- **AUD-01/02/03/04/05/06/07/08/10/12/13/14/15/16/17:** соответствующие корневые механизмы не закрыты этим diff. Ограниченный аудит каждого направления не равен полному повторному покрытию всей базы.
- RISK-DEBUG и HYP не исчезли; пределы raw callbacks/HTTPS runtime отдельно перечислены, не повышены в подтверждённые remote High.

## Проверки и независимость

Signal: 10 JVM checks; storage: 31 regression tests и fault/race probes; network: 37 JVM tests + native fallback/scheduler и budget probes; platform: 40 JVM tests + buffer/tombstone probe. Наборы пересекаются — **не суммировать** их как независимое покрытие. Без Android/Keystore/SQLite/радио/TLS end-to-end. Checkout и GitHub во время этих четырёх аудитов не менялись. Секреты/живые узлы не использовались.

Исправления идут отдельно в одной ветке/PR. Доказательства audit-снимка и статус proposed/fixed/tested нельзя смешивать: кодовая правка сама по себе не закрывает ID без регрессии и проверки интеграции.
