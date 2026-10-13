> Приложение: исходный отчёт отдельного аудитора. Итоговые уровни, дедупликация, статус сессий и область применимости определены в [сводке](report.md). Локальные `/data/...` — пути временного окружения, не файлы репозитория.

# Независимый security audit: Signal, pairing и trust

**Репозиторий:** `lineSence/secure-messenger`  
**Единственный проверяемый commit:** `c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`  
**Дата:** 2026-10-13  
**Метод:** статический анализ исходников и реальных цепочек вызовов.  
**Изменения GitHub/Notion:** отсутствуют. Субагенты не запускались. Стандартные release verdicts не записывались.

## Итог

Зафиксированы **шесть находок: пять Medium и одна Low**. Основные проблемы находятся не в математике Signal, а в отзыве доступа, жизненном цикле ключей и Android-координации операций.

| ID | Severity | Находка | Статус |
|---|---|---|---|
| SIG-1 | Medium | Удаление контакта не отзывает ключ BLE-маяка и внешнего шифрования частей | Доказано по коду |
| SIG-2 | Medium | Неаутентифицированный Decline необратимо отменяет начатое приглашение | Доказано по коду |
| SIG-3 | Medium | Обратный порядок SQLite/SignalGate допускает взаимную блокировку | Доказано по коду; расписание не проверялось на Android |
| SIG-4 | Medium | Отменённые карточки оставляют приватные prekeys без уборки и квоты | Доказано по коду |
| SIG-5 | Medium | Signal decrypt и состояние приглашения фиксируются раздельно: сбой теряет возможность повторного приёма | Доказано по коду |
| SIG-6 | Low | GATT ACK принимается за успешное завершение сопряжения до проверки и сохранения Reply | Доказано по коду |

**Ни одна находка не помечена «воспроизведено тестом».** Rust/Kotlin/Android-тесты не запускались: `cargo`, `rustc`, `kotlinc` в окружении отсутствуют. Ни APK, ни реальное Bluetooth-соединение, ни рабочий relay не использовались. Ниже есть минимальные сценарии и один подготовленный, но не запущенный Rust-тест.

Не найден доказанный сценарий расшифровки текста посторонним, обхода проверки подписи Signal или подмены ключа в существующем контакте через штатный последовательный QR-поток. Это ограниченный результат статического прохода, а не гарантия отсутствия таких сценариев.

## Объём и происхождение исходников

Скачан tarball именно указанного SHA с `codeload.github.com`, без переключения на движущийся `main`. SHA-256 скачанного архива:

`d46e31e7ce5eaad16668d5dc49d52bdcff76ffdc70760338f147aec7dc99f312`

Исходники находятся в отдельном `/data/audit-signal/source`; закреплённая зависимость libsignal — в `/data/audit-signal/deps/libsignal`. Хеш архива фиксирует полученный артефакт, но не является независимой проверкой подписи Git commit. Утверждение, что этот SHA по-прежнему текущий `main`, отдельно не проверялось: для аудита использован предоставленный фиксированный SHA.

Прочитаны `AGENTS.md`, `CLAUDE.md`, архитектура Signal/core/Android, модель угроз, требования и релевантные ADR (23, 56, 61–63, 83, 92, 133–134, 142, 145–146), правила кода. Старый список находок `.github/ai-review/releases/v0.0.1-alpha.json` прочитан **после** фиксации независимых шести выводов в `/data/audit-signal/independent-pass.txt`.

### Фактическое покрытие

- `sm-signal`: ключ личности и проверка public/private, карточки и подписи prekeys, начало/ответ сессии, адрес контакта, decrypt/decrypt_from_any, forget, Signal store callbacks.
- QR и PairFrame: длины, версии, разбор, proof, ограничения кадров, Intro/Hello и валидация имени.
- Invite: версии 1/2, base64url, expiry, AES-GCM/AAD, получение ключа кода; link proof и привязка обратного адреса/записи узла.
- BLE: HMAC-маяки, ключи частей, AEAD до выдачи plaintext, связь с ContactBook/ContactBeacon, PairLink и transport ACK.
- Android: Pairing state machine, SignalGate, SignalKv, SecureDb.atomic, ContactBook, InviteFlow/InviteLinks, удаление контакта через ChatHub/ContactRemoval, ручная сверка кода безопасности.
- Связанные участки ChatHub/RelayHub: границы транзакций, dispatch приглашений, решение об ACK, перечитывание BLE-ключей.
- Закреплённая libsignal `4beb029d8a941f81e7d9c6d8af1ed25a677569a8`: process_prekey_bundle, message_decrypt_prekey, запись identity/prekeys/session, отклонение дубликатов. Это проверка необходимого поведения зависимости, не полный аудит libsignal.

Использованы `curl`, `tar`, `find`, `rg`, `sed`, `nl`, `sha256sum`, Python для ограниченной агрегации старых заголовков. GitHub MCP был доступен, но для экономии запросов использован разрешённый tarball. Semgrep, cargo test/clippy/fuzz/Kani, Gradle/JVM-тесты и dependency vulnerability scanners **не запускались**.

---

## SIG-1 — Отзыв контакта не отзывает ключ BLE-маяка

**Severity:** Medium — нарушение отзыва доступа к метаданным и наружному BLE-слою.  
**Статус:** доказано по коду.  
**Уверенность:** высокая.

### Точные места

- `android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt:122–125`: свой beacon берётся из постоянной записи `self/beacon`, генерируется только при её отсутствии. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt#L122-L125).
- `android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:480–506`: действительная цепочка удаления чистит Signal/историю/контакт/почту, но не меняет beacon. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L480-L506).
- `android/app/src/main/java/dev/sm/messenger/ble/Beacon.kt:81–95`: реклама и ключ входящих частей продолжают производиться из `ownKey`. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/ble/Beacon.kt#L81-L95).
- `crates/sm-signal/src/beacon.rs:84–104` и `crates/sm-signal/src/ble_part.rs:61–68`: HMAC-токены и ключ частей детерминированы старым beacon. [Маяк](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/beacon.rs#L84-L104), [ключ частей](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/ble_part.rs#L61-L68).

### Реальная цепочка и сценарий

При сопряжении `hello()` передаёт `book.ownBeaconKey()` внутри Signal. Контакт Б законно узнаёт beacon А. Затем А удаляет Б через `ContactRemoval → ChatHub.removeContact → dropContact`. Сессия и строка Б действительно удаляются, но `self/beacon` остаётся. Новый запуск/перечитывание контактов также читает прежний beacon.

**Предпосылки:** Б был контактом и сохранил полученный beacon; А продолжает объявляться по BLE; Б либо его сканеры находятся в радиусе наблюдения. Доступ к ОС А не требуется.

**Влияние:** Б продолжает вычислять метки всех будущих эпох, узнавать устройство и связывать наблюдения после удаления. Ему остаётся известен и ключ наружного шифрования входящих BLE-частей. Это позволяет открывать этот внешний слой и создавать корректные внешние части, но **не** расшифровывать текст Signal и **не** подделывать внутренний Signal MAC.

### Минимальный PoC / тест

На тестовых устройствах сохранить beacon А только внутри тестового harness, не выводя его в журнал. Удалить Б у А. Проверить, что `ownBeaconKey()` равен прежнему значению и что рассчитанные Б `bleContactToken(oldKey, epoch)` совпадают с рекламой А в следующую эпоху, в том числе после перезапуска. Для внешнего слоя — сравнить `blePartKeyContact(oldKey)` с ключом входящего reassembler А. **Не запускалось.**

### Исправление

Ротировать beacon при удалении/компрометации контакта; распространить новую версию оставшимся контактам в аутентифицированных Signal-сообщениях. Переключить рекламу, GATT и reassembler, убрать старый ключ из кэша. Нужны версии/повторы доставки и ясный момент прекращения приёма старого ключа, иначе бессрочное переходное окно сохраняет доступ удалённому контакту. Это соответствует обещанию ротации в ADR-61 п.2.

---

## SIG-2 — Поддельный Decline удаляет начатое приглашение

**Severity:** Medium — неаутентифицированный необратимый переход состояния.  
**Статус:** доказано по коду.  
**Уверенность:** высокая.

### Точные места

- `crates/sm-signal/src/pairing.rs:175` и `257–260`: Decline — ровно байт `0x04`, без подписи/MAC. [Кодирование](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/pairing.rs#L175), [разбор](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/pairing.rs#L257-L260).
- `android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:377–381`: в Started вызываются forget и removeOut без проверки отправителя. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L377-L381).
- `android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:259–267`: результат true приводит к ACK блоба узлу. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L259-L267).

### Реальная цепочка и сценарий

Законная LinkCard проходит proof, приглашение становится Started. Злоумышленник подкладывает `04` в очередь приглашения до настоящего Reply. `pairFrameDecode → onOut` считает это отказом собеседника, забывает незавершённую сессию и удаляет приглашение. Последующий настоящий Reply уже не завершит этот обмен.

**Предпосылки:** атакующий — контролирующий relay/его выдачу блобов, либо знает sender ID очереди приглашения и может отправить туда сообщение. Sender ID доступен в открытой ссылке v1. Узлу не нужен secret ссылки; для сценария с защищённой ссылкой v2 атакующий-узел всё равно может подложить блоб в обслуживаемую очередь.

**Влияние:** одноразовый обмен отменяется локально без подлинного отказа; нужна новая ссылка/ручное восстановление. Расшифровка переписки или добавление чужого ключа не доказаны. Relay и так способен задерживать/терять сообщения; дополнительная проблема — превращение неаутентифицированного входа в локальное удаление состояния, а не просто сетевой сбой.

### Минимальный PoC / тест

В fixture с `OutLink(stage=Started)` передать в `InviteFlow.onFrame` `byteArrayOf(0x04)`. Проверить исчезновение OutLink и вызов forget. Для сетевого PoC — отправить однобайтовый blob `04` в известную очередь после LinkCard. Использовать только свой тестовый relay. **Не запускалось.**

### Исправление

После установления сессии передавать отказ внутри Signal с привязкой к идентификатору обмена. До сессии — proof по secret приглашения и контексту обмена. Старый raw Decline не должен удалять Started-состояние: игнорировать его или отображать сетевой сбой, не трактуя как решение пользователя.

---

## SIG-3 — Взаимная блокировка при удалении и параллельном сопряжении

**Severity:** Medium — остановка операций Signal/сопряжения до перезапуска процесса.  
**Статус:** доказано по коду как допустимое расписание захвата замков; на Android не воспроизведено.  
**Уверенность:** высокая в инверсии порядка; средне-высокая в практической воспроизводимости на конкретном SQLite/устройстве.

### Точные места

- `android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:25–31`: `lock.withLock { db.atomic(block) }`. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L25-L31).
- `android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:473–488`: io-поток открывает db.atomic и внутри вызывает sessions.forget, который входит в SignalGate. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L473-L488).
- `android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt:124–134`: транзакция удерживается до окончания block. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt#L124-L134).

### Реальная цепочка и сценарий

Обычный Signal-вызов: **SignalGate lock → SQLite transaction**. Удаление: **SQLite transaction → SignalGate lock**.

Допустимое расписание:

1. Поток D (`sm-chat`/io удаления) начинает транзакцию SQLite и останавливается перед `sessions.forget`.
2. Поток P (worker Pairing или `sm-invite`) входит в SignalGate, берёт lock и ждёт write-транзакцию SQLite.
3. D вызывает forget и ждёт lock, который держит P.

Возникает цикл D → P → D. `SecureDb.atomic` проверяет вложенность только текущего потока; транзакция другого потока эту проблему не устраняет. Комментарий SignalGate о едином порядке замков не соответствует пути удаления.

**Предпосылки:** параллельное удаление контакта и операция Signal из отдельного pairing/invite worker. Один последовательный io-поток чата сам по себе не достаточен; важен именно второй worker. Специальные ключи или права атакующему не нужны; это прежде всего ошибка конкурентности, которую может усилить приход кадра в нужный момент.

**Влияние:** зависают удаление и worker сопряжения; последующие Signal-вызовы ждут занятый lock. Коррупция криптографического состояния этим расписанием не доказана.

### Минимальный воспроизводящий тест

Instrumentation-тест: latch после `beginTransaction()` в удалении; второй latch после получения SignalGate lock перед входом в db.atomic. Запустить D, затем P, затем разрешить D продолжить; убедиться по timeout/thread dump, что D ждёт ReentrantLock, а P — SQLite-транзакцию. Обязательно выполнять на тестовой БД и завершать тестовый процесс после deadlock. **Не запускалось.**

### Исправление

Удаление должно получать SignalGate lock **до** начала транзакции, как все остальные операции. Не достаточно заменить `beginTransaction` на nonexclusive: две write-транзакции всё равно конфликтуют. Учитывать и монитор ContactBook: нельзя просто обернуть существующий dropContact в SignalGate.atomic и оставить book.delete внутри, поскольку SignalGate прямо запрещает захватывать ContactBook-monitor под транзакцией. Нужны единый координатор и операции над строками без инверсии дополнительных замков.

---

## SIG-4 — Карточки оставляют неограниченно сохраняемые приватные prekeys

**Severity:** Medium — накопление постоянного состояния и отсутствие жизненного цикла секретов.  
**Статус:** доказано по коду.  
**Уверенность:** высокая в удержании записей; средняя в скорости практического исчерпания диска.

### Точные места

- `crates/sm-signal/src/contact.rs:151–183`: каждая new_card создаёт и сохраняет PreKey, SignedPreKey и KyberPreKey. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/contact.rs#L151-L183).
- `crates/sm-signal/src/contact.rs:553–562`: forget удаляет только Session и Identity. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/contact.rs#L553-L562).
- `android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:266–286,428–432`: reset не удаляет карточки; каждый onReady сканирующего вызывает новую cardFrame. [Reset](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L266-L286), [onReady](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L428-L432).
- `crates/sm-signal/src/store.rs:216–252`: SignedPreKey store имеет чтение/запись, но не удаление/expiry; удаления одноразовых prekeys происходят только при успешном использовании. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/store.rs#L216-L252).

### Реальная цепочка и сценарий

Scan/nearby/invite создаёт карточку и фиксирует три приватные записи. Обмен отменяется или peer исчезает до Start. Никакого pairing/card handle для удаления этих записей нет; reset/forget не знают их IDs. На успешном приёме libsignal удаляет одноразовые EC/Kyber, но SignedPreKey остаётся. В проверенной реализации не найден сборщик таких записей или квота.

**Предпосылки:** повторяющиеся отменённые/неуспешные добавления. Сетевой вариант требует открытого Scan-потока: ложный peer с нужной меткой периодически подключается, получает новую карточку и отключается; находиться в радиусе BLE и знать/наблюдать метку достаточно для обнаружения, получения настоящего Start не требуется. Окно Scan ограничено timeout, поэтому не утверждается мгновенный неограниченный DoS за один экран.

**Влияние:** БД растёт независимо от числа контактов; остаются неиспользованные приватные ключи. Со временем возможен отказ хранения. Это не доказательство взлома forward secrecy: успешные одноразовые Kyber/EC действительно удаляются, а сохранение SignedPreKey само по себе обычный элемент Signal. Ошибка — отсутствие срока, ownership и уборки.

### Подготовленный Rust-тест

```rust
use sm_signal::{DeviceIdentity, contact, store::{MemKv, Stores, Table}};
#[test]
fn cancelled_cards_keep_all_private_prekeys() {
    let identity = DeviceIdentity::generate();
    let kv = MemKv::default();
    let stores = Stores::new(&identity, &kv);
    for _ in 0..20 {
        let _discarded_card = contact::new_card(&stores).unwrap();
    }
    assert_eq!(kv.count(Table::PreKey), 20);
    assert_eq!(kv.count(Table::SignedPreKey), 20);
    assert_eq!(kv.count(Table::KyberPreKey), 20);
}
```

Файл: `/data/audit-signal/poc/prekey_retention.rs`. Это тест фактического API, но **не скомпилирован и не запущен**. Его можно добавить как integration test в отдельной тестовой копии checkout и выполнить `cargo test -p sm-signal --test prekey_retention` при наличии совместимого toolchain/dependencies/protoc.

### Исправление

Возвращать вместе с карточкой handle её prekey IDs, связывать записи с обменом и временем жизни. Удалять при отмене/expiry, убирать orphan-карточки после crash, вводить квоту и ограничивать выпуск карточек при переподключениях. Для SignedPreKey нужен безопасный ограниченный срок хранения с учётом уже выданных карточек, а не безусловное немедленное удаление.

---

## SIG-5 — Сбой после decrypt теряет состояние приглашения без возможности replay

**Severity:** Medium — невосстановимый незавершённый обмен при локальном сбое записи.  
**Статус:** доказано по коду, включая поведение закреплённой libsignal на дубликате.  
**Уверенность:** высокая.

### Точные места

- `android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:410–422`: `gate.acceptStart` отдельно от `links.putIn(InStage.Ask, hello...)`. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L410-L422).
- Там же `363–375`: `gate.openReply` отдельно от ContactBook/peer/OutStage.Done. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L363-L375).
- `android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt:29–31,50–55`: каждый acceptStart/openReply заканчивает собственную транзакцию до возврата. [Транзакция](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L29-L31), [вызовы](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L50-L55).

### Реальная цепочка и сценарий

1. У получателя `InLink` находится в Sent.
2. Приходит подлинный Start; acceptStart успешно расшифровывает его и фиксирует session/prekey-изменения.
3. Следующая запись `links.putIn(...Ask...)` получает SQLite/Keystore-сбой. Первая транзакция уже завершена; guard возвращает failure, блоб пока не ACK-нут.
4. Повтор того же Start попадает к libsignal с уже израсходованным message key. Дубликат отвергается. Catch SignalFfiException возвращает **true**, после чего RelayHub ACK-нет и удалит блоб.
5. Durable InLink остался Sent, а расшифрованные имя/beacon не сохранены. Обмен застрял.

Та же граница существует у пригласившего: Reply уже прочитан, но `book.save`/peer metadata/Done ещё не записаны. Их частичные результаты могут различаться в зависимости от точки сбоя.

Удаление prekeys и сохранение session после успешного decrypt подтверждено в закреплённой зависимости: `rust/protocol/src/session_management.rs:254–290`; дубликат current state отвергается на `432`. [Запись](https://github.com/signalapp/libsignal/blob/4beb029d8a941f81e7d9c6d8af1ed25a677569a8/rust/protocol/src/session_management.rs#L254-L290), [дубликат](https://github.com/signalapp/libsignal/blob/4beb029d8a941f81e7d9c6d8af1ed25a677569a8/rust/protocol/src/session_management.rs#L417-L433).

**Предпосылки:** одно успешное decrypt, затем локальная ошибка записи либо crash между двумя коммитами. Компрометация peer не требуется. Сам по себе контролирующий relay не даёт атакующему управления локальным Keystore/SQLite-сбоем.

**Влияние:** приглашение нельзя завершить простым повтором блоба; необходимо отменить и создать новый обмен. Возможны уже созданная сессия без контакта или контакт с незавершёнными relay metadata. Конфиденциальность текста этим сценарием не нарушена.

### Минимальный fault-injection тест

В тестовой копии внедрить RelayKv, который однократно бросает SQLiteException на записи `relay_invite` **после** успешного acceptStart. Доставить корректный Start дважды. Ожидаемый текущий результат: первый вызов false; второй true, InLink всё ещё Sent. Аналогично инъекция на book.save после openReply у пригласившего. Проверить также crash/restart в этих точках. **Не запускалось.**

### Исправление

Атомарно фиксировать криптографический шаг и durable результат обработки: stage, Hello и необходимые metadata. SignalGate уже поддерживает внешний atomic, но нельзя брать ContactBook-monitor под его lock/SQLite-транзакцией. Вариант — под общей транзакцией сохранить pairing-result journal/stage, затем идемпотентно материализовать ContactBook; при сбое повтор должен находить ранее сохранённый результат, а не пытаться снова decrypt одноразовый кадр. ACK допускается только после durable recoverable результата.

---

## SIG-6 — Transport ACK не подтверждает обработку и сохранение Reply

**Severity:** Low — ложное успешное окончание и рассогласование контактов при сбое принимающей стороны.  
**Статус:** доказано по коду.  
**Уверенность:** высокая.

### Точные места

- `android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:424–436`: GATT_SUCCESS отправляется после reassembly, до `listener.onFromClient`. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L424-L436).
- Там же `275–279,326–334`: успешный write последней части вызывает `sent`. [Write callback](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L275-L279), [sent](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L326-L334).
- `android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:384–392,512–518`: клиент сохраняет контакт по sent, сервер только позже openReply/book.save. [Клиент](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L384-L392), [сервер](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L512-L518).

### Реальная цепочка и сценарий

Б сканирует QR А, принимает подлинный Start и отправляет Reply. А собирает Reply и отправляет transport ACK, а проверку Signal/сохранение ставит в отдельную работу. У А затем отказывает openReply или book.save. Б уже получил ACK, сохранил А как verified и показал Done. У А Б отсутствует; обычное повторное добавление у Б блокируется проверкой known.

**Предпосылки:** сбой обработки/хранилища у А после transport ACK либо crash в этой точке; Б должен успеть получить ACK и сохранить свой контакт. Похожая проблема есть в nearby-потоке.

**Влияние:** односторонний контакт и сообщение об успешном сопряжении без durable подтверждения другой стороны. Это **не** доказательство, что Б сверил неверный identity key: ключ А был проверен по QR. Нарушено подтверждение завершения, а не сама проверка ключа.

### Минимальный тест

В тестовом GATT fixture пропустить reassembly и ACK Reply, затем однократно заставить серверный openReply/book.save бросить исключение. Проверить у Б Done и присутствие контакта, а у А — отсутствие контакта. **Не запускалось.**

### Исправление

Разделить transport ACK и аутентифицированный application-level результат. Сохранять промежуточное состояние до отправки завершения; повторять/идемпотентно обрабатывать confirmation по идентификатору обмена. Абсолютную синхронность двух устройств при произвольном обрыве обещать нельзя: нужен восстановимый протокол с понятным состоянием «завершение ожидается», а не только ещё один неидемпотентный ACK.

---

## Проверенные защитные свойства и невыданные находки

1. **Привязка identity в обычном Signal-потоке.** Адрес получается из сериализованного identity; expected_identity сверяется до decrypt, а PreKey decrypt известного контакта сверяет identity с адресом. Проверка ключа из QR/ссылки не является просто комментарием: её вызывает Android через FFI. [contact.rs:411–428](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/contact.rs#L411-L428), [486–497](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/contact.rs#L486-L497).
2. **Карточки не принимаются лишь по имени.** Signed/Kyber signatures идут в process_prekey_bundle; proof QR проверяется до начала сессии. Link proof включает card length/card/sender ID/node, поэтому простой перенос карточки на иной обратный адрес не сохраняет proof. [signal.rs:742–749](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/signal.rs#L742-L749), [beacon.rs:153–177](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/beacon.rs#L153-L177).
3. **Ссылки не повышают trust до verified.** Оба направления InviteFlow сохраняют verified=false; ручное повышение — пользовательская сверка 60 цифр. Само наличие одинакового self-reported name не является проверкой личности. [InviteFlow.kt:301–309](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L301-L309), [VerifyScreen](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L647-L668).
4. **Ошибки SignalKv не преобразуются в пустые ключи.** SignalGate оборачивает отдельные криптооперации в транзакцию. SIG-5 относится к следующему прикладному коммиту, а не к отсутствию транзакций вообще. [SignalGate.kt:25–31](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/SignalGate.kt#L25-L31).
5. **Внешний BLE AEAD не отдаёт plaintext до проверки tag.** Предсказуемый nearby-key — явно публичный режим обнаружения, а не заявленный секрет E2E. Не выдана находка «Signal сломан, потому что nearby-key общий». [ble_part.rs:80–85,113–129](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/ble_part.rs#L80-L129).
6. **Ключ личности при загрузке проверяется на соответствие private/public.** [identity.rs:78–112](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/identity.rs#L78-L112).

### Оставшиеся риски/гипотезы, не включённые в шесть доказанных находок

- **QR — предъявительский секрет.** У показавшего QR verified означает, что peer знал secret, а не что приложение проверило его гражданскую/социальную личность. Если посторонний сфотографировал QR и первым выполнил обмен, он может стать verified на показывающем телефоне. Это архитектурное ограничение/предпосылка доверия к визуальному каналу, а не обнаруженный обход HMAC. Нужна явная UX-политика для ситуации с несколькими наблюдателями; улучшение — взаимная сверка обоих ключей. Тест перехвата QR не выполнялся. [Pairing.kt:492–518](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L492-L518).
- **Параллельные незавершённые обмены с одной identity.** FFI хранит сессию по identity, не по обмену; несколько OutLink и Bluetooth-flow могут пересекаться, пока ContactBook ещё пуст. Сброс/forget одного обмена способен затронуть другой. Конкретный сценарий с фактическим восстановлением archived session libsignal не доведён до доказанного результата; не включён как подтверждённая подмена ключей.
- **Прямые пользователи Rust/FFI API.** Kv/PlatformKv не предоставляют begin/commit/rollback, и SignalSessions.with даёт mutex, но не транзакцию. Android защищает это SignalGate. Иные будущие клиенты обязаны реализовать транзакционные границы самостоятельно; аудит не подтверждает fail-atomicity сырого API при отказе нескольких последовательных put/delete. [signal.rs:675–684](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/signal.rs#L675-L684).

## Сверка с прежними находками

После независимого прохода проверен старый файл ревью alpha, содержащий записи для **других SHA**. Он не использован как доказательство состояния проверяемого commit.

- SIG-1 независимо совпал с прежней темой «удалённый контакт отслеживает BLE». В текущем коде часть старого утверждения «сессия не удаляется» уже неверна: dropContact вызывает forget. **Неисправленной остаётся ротация beacon.**
- Старые последовательные сценарии повторного добавления/отмены, портившие существующий контакт, теперь имеют known-проверки и forgetIfNew. Это не доказывает отсутствие параллельных гонок, но переносить старую находку целиком на текущий SHA нельзя.
- Старые обходы имени/bidi не перенесены: Hello/Intro теперь идут через check_name/wire::text.
- Старое закрытие Bluetooth сразу после постановки Reply в очередь заменено ожиданием transport ACK. SIG-6 — более узкая оставшаяся граница: ACK не подтверждает обработку и durable commit.
- Из прежних замечаний об отсутствии транзакций нельзя сделать вывод, что SignalGate отсутствует: он есть. SIG-3 и SIG-5 основаны на конкретных вызывающих путях, которые нарушают его порядок/границы.

[Старый список только для сопоставления](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/.github/ai-review/releases/v0.0.1-alpha.json).

## Пропуски и рекомендуемая следующая проверка

Не проведены: полный аудит криптопримитивов libsignal/SPQR/JNI, анализ APK и ABI, реальные Android Keystore/SQLite/GATT scheduling, аппаратная BLE-атака, фаззинг парсеров, формальные доказательства, полный аудит relay/HTTPS, supply chain/CI/release signing, forensic extraction/zeroization, полная матрица reboot/crash/concurrency. Часть документов описывает планы; покрытие определяется прочитанным кодом, не заявлениями об архитектуре или старыми зелёными CI.

Рекомендуемый порядок работ:

1. Отозвать доступ к beacon при удалении и добавить regression-тест SIG-1.
2. Устранить инверсию замков, затем детерминированный concurrency-test SIG-3.
3. Ввести durable/idempotent результаты pairing и fault-injection SIG-5/6.
4. Аутентифицировать отмену приглашения SIG-2.
5. Ввести ownership/expiry/quota для карточек и проверить SIG-4 на отмене, crash и reconnect.

Это список конкретных исправлений и проверок, **не стандартный release verdict**. Отчёт сохранён локально; в репозиторий и Notion ничего не записано.
