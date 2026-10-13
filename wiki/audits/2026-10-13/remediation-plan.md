# План единого PR по результатам аудита

База: `9595f2e127b96dd37fa81fd32d41f18bd48e9896`; ветка `fix/security-audit-2026-10-13`. Один итоговый PR, без слияния в main агентами.

## Параллельные пакеты с непересекающимися файлами

| Пакет | ID и цель | Владение |
|---|---|---|
| Rust queues | AUD01, AUD14: nonempty invariants, regression next blob, snapshot roundtrip | sm-core delivery/relay, sm-relay store/tests |
| Android network | AUD01/02/03/13/19/21/22/23: envelope recovery, fairness, admission, route clocks, incarnation-fenced metadata, transport retry, bounded prepare и wakes | RelayHub/HttpsRelay/PeerMailboxes, BleLink/BleHub, helpers/tests |
| Persistence | AUD06/10/12/15/18/20: единый lock order, typed Store retry, durable cancellation, bounded read, retain failed journal, separate ACK/failure | ChatHub/FrameCache/ContactBook/SignalGate/DeliveryStore/SecureDb |
| Invitations | AUD05/08/10: failclosed raw Decline, transactional recoverable state, cached handshake send after commit | InviteFlow/InviteLinks, helpers/tests |
| Secrets/prekeys | AUD07/17: bounded ownership/expiry/cleanup, controllable secret copies | sm-signal identity/contact/store, sm-ffi keys, DeviceKeys |
| Pairing | AUD03/09/19/20/22: bounded callbacks, lifecycle cancellation, local-added versus peer-confirmed, no false ready | Pairing/PairLink/PairMailboxExchange, helpers/tests |

Среда допускает пять одновременно работающих fix-сессий: шестой пакет запускается в освобождённый слот. Каждый использует отдельный git worktree, оставляет patch и тестовый журнал; удалённые push/PR выполняет только координатор. Новые audit-сессии закончились до запуска исправлений. Авторы не пишут release verdicts.

## Общие интерфейсы и последовательные шаги

- Одна долговечная outbox и один ciphertext. Transport eligibility не должна расходоваться другим неудачным маршрутом.
- SignalGate → SQLite; не брать ContactBook monitor под SQL и не ждать сеть под DB/mailbox locks.
- Contact incarnation создаётся при новой записи, сохраняется при update. Late NEW проверяется атомарно с SEND/own/told commit; stale response компенсируется cleanup.
- Frame terminal state различает DeliveredByPeer и Failed/Cancelled; только первый даёт Signal-success.
- Любое исходящее handshake-подтверждение сохраняется до отправки и повторяется идемпотентно после crash.

**AUD04 и AUD16** требуют отдельного последовательного интеграционного пакета: отзыв/распространение нового beacon и конфиденциальность pre-session payload. Он затрагивает протокольные типы и уже занятые файлы; выполняется после первой интеграции. Нельзя без предупреждения сломать совместимость старых клиентов, стереть данные или ослабить проверки. Выбранный формат/миграция описываются в PR; если требуется решение владельца, остаются явным блокером, а не объявляются закрытыми. Известный публичный debug signing key не заменяется молча, чтобы не заставить пользователя потерять тестовые данные.

## Приёмка

1. Собрать patches на указанной базе, проверить ownership/diff/конфликты.
2. Тесты, которые воспроизводили ошибки, должны проверять исправленный invariant; не удалить их ради green CI.
3. Rust fmt/clippy/workspace tests и Android JVM/lint при доступной среде, все ограничения явно записать.
4. Независимый integration pass, особенно lock graph, stale commits и actual-versus-modeled proof.
5. Один PR содержит код, regression tests, audit addendum, план и finding-to-change статус. Main не меняется напрямую; не merge, не обходить красный ci-ok.

Документ — план, не утверждение о завершении правок. Итоговый статус находится в remediation-status.md после интеграции.
