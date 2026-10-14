# Реализация UX/UI-аудита по принятому v18

Дата: 2026-10-14. PR: https://github.com/lineSence/secure-messenger/pull/92. База реализации: `852951a93674c7b22de045d78e56d6d37f50b028`; исходный аудит — `0ccfbaa80eba483ea6c2cf6b83a6b80026235bae`. Владелец согласовал v18: «Принято, реализуй». v17 отклонён, онбординг v16 не откатывается.

**Статус: изменения кода внесены для всех 30 ID; это не заявление о полной приёмке на телефонах.** Нативный стенд рисует production Compose с синтетическими данными. Физический Bluetooth, NotificationManager/TalkBack/IME, аппаратный Keystore, настоящий process kill и сети требуют отдельного прогона.

## Реестр

| ID | Реализация | Новая проверка / граница доказательства |
|---|---|---|
| CHAT-01 | RESUMED leases; STARTED/Home/lock не считаются просмотром | LifecycleRegistry, ранее добавленные 4 теста; системные уведомления на телефоне отдельно |
| CHAT-02 | Keyset paging; unread без LIMIT; raw-row cursor учитывает повреждённые строки; транзакционная метка не закрывает пропущенные входящие | MessageStoreTest: настоящая Android SQLite, 601 входящее и входящее перед 500 исходящими, граница прочтения |
| CHAT-03 | Стабильные rowId; чужой revision/статус не скроллит; загруженный ранний диапазон остаётся на месте; новые строки не уводят читающего вверх | Merge/stable-key unit test и early-unread Android render; полноценный жест/IME на телефоне отдельно |
| CHAT-04 | Результат send после SQL commit; отказ сохраняет ввод; поздний callback не стирает новый текст | UxStateTest, AES-GCM key failure/SQLite rollback в MessageStoreTest, native send-error с непустым вводом и реальным Rust textOk |
| CHAT-05 | Signal lock → SQL; удаление истории/Signal/контакта/связанных строк и cleanup intent атомарно; snapshot outbox чистится только после commit; intent снимается только после AtomicFile success, повтор при attach | SQLite rollback + reopen до cleanup, RemovalRecoveryTest до/после persist; полный kill с Android outbox/Signal/Keystore на телефоне отдельно |
| CHAT-06 | Loading/error/retry вместо ложной пустой истории; ошибка сохраняет старые строки; не отмечает прочтение | Native history-error; SQLite read paths; аппаратный read fault отдельно |
| CHAT-07 | Failed: предупреждение и «Не отправлено» в превью, не часы ожидания | deliveryDescription unit test, native preview-failed |
| CHAT-08 | Ключ не сверен — нейтрально к QR/Bluetooth/ссылке | Native chat-anya, сравнение до/после |
| C-01 | Входящий Ask/started учитывается в onLost; reset поколения отбрасывает поздние кадры | NearExchangeLostTest; реальное радио/таймеры отдельно |
| C-02 | Асинхронная ошибка запуска остаётся Failed с повтором, а не кратким toast | Код onError/reset и существующая вкладка Near; Bluetooth callbacks на телефоне отдельно |
| C-03 | Контакт уже сохранён → Saved, отдельно readiness адресов; чат можно открыть до окончания mailbox exchange | Native near-saved; существующие PairMailboxExchangeTest; потеря ACK на радио отдельно |
| C-04 | MadeLink несёт реальный факт наличия ticket; предупреждение без гостевого доступа до share/copy | Native link-no-guest; отказ TICKET реального узла отдельно |
| C-05 | Лимит учитывает retained Done; текст не предлагает отозвать отсутствующую активную ссылку | Существующие InviteLinksTest, typed Full и отключение создания |
| C-06 | Network / SQLite / Signal store / Keystore различаются; ввод и постоянная ошибка остаются для повтора; importNode storage не превращается в NoNetwork/NoNode | Классификатор linkProblem и importer unit tests; end-to-end fault узла отдельно |
| S01 | Приглашение/код в Activity ViewModel, не SavedStateHandle/Bundle; новый процесс получает пустой ввод | ViewModelStore retention/clear test; настоящий Activity recreate/process kill отдельно |
| S02 | Opt-in перед первым runtime request; отказ не открывает Settings автоматически; runtime/app/channel перечитываются после результата и RESUME; фон отдельно | Полная таблица notificationAccess; системный диалог и возврат из Settings на устройстве отдельно |
| S03 | Любой устойчивый сбой receive/ensureQueues меняет link; onLost отменяет канал и будит цикл; epoch блокирует stale success | Код сетевого цикла; реальные NEW failure/onLost/poll sleep отдельно |
| S04 | Idle — нейтральная принятая запись, без фиктивного запроса/подтверждения доставки | NetStatusTest + native node-idle |
| S05 | Saved/OlderThanCurrent/Expired/Invalid/StorageFailure/Revoked — типизированные итоги; ошибка записи сохраняет предварительную проверку и повтор | AccountImportTest с injected validation/KV, native node-storage; реальные подписи не подменены production-кодом |
| S06 | Успех импорта говорит «Запись узла принята», а не «сеть работает» | Текст/typed importer, native node-idle |
| S07 | Проверка revoked до kv.put; ранее работавший пропуск не заменяется отозванным | AccountImportTest: revoked и storage failure сохраняют прежний pass |
| S08 | Ограниченный refresh только RESUMED debug-экрана; frozen export не заменяется; нет fsync на кадр | Существующие journal tests/native debug-экраны; живой callback на телефоне отдельно |
| UI-01 | Один toggleable Role.Switch на строку; декоративный внутренний switch не даёт второго действия | Native Compose semantics: один switch, один callback; TalkBack отдельно |
| UI-02 | stateDescription отличает waiting/onNode/delivered/failed; label действия сведений о сообщении | deliveryDescription и Bubble semantics; TalkBack отдельно |
| UI-03 | Тёмнее secondary/metadata/primary в светлой теме; читаемый foreground danger в обеих темах | Exact-token contrast ≥4.5 для 5 пар × 2 темы; native light/dark |
| UI-04 | Тело удаления скроллится независимо от действий, имя переносится | Native 320×500 dp/200%, landscape 640×320 dp/200%; scroll-to «Останется» и доступные действия |
| UI-05 | Кнопки растут и переносят текст; share/copy равной ширины/высоты, при 200% вертикально; тело ссылки скроллится | Native adaptive/landscape; сравнение linkmade до/после |
| UI-06 | selectableGroup/Role.Tab/selected для вкладок и нижней панели | Native semantics выбранной вкладки; adaptive nav |
| UI-07 | Default verify не переписывает manifest; native hashes/dimensions; обязательный Android CI gate и PNG artifact | Повторный полный verify и намеренная native-регрессия фиксируются ниже |
| NAV-01 | Loading/ReadError/Missing для восстановленного адреса; retry/back, без сброса ключей; повреждённые contacts не трактуются как исчезновение | UxStateTest + native route-error; Activity restoration на телефоне отдельно |

## Порядок восстановления удаления

1. Монитор приглашений берётся до замка Signal/SQLite. Внутри SQL не берётся монитор ContactBook: deleteWithinTransaction используется только владельцем транзакции.
2. В одной транзакции удаляются строки и записывается зашифрованный `chat_delete` intent (значение — адрес; ключ — mailbox HMAC). Если транзакция откатывается, очередь snapshot ещё не тронута.
3. После commit snapshot очищается и записывается AtomicFile. Ошибка записи оставляет intent; он повторяется при attach после открытия ключей. Повтор очистки идемпотентен.
4. Удалённый контакт не объявляется оставшимся из-за ошибки post-commit cleanup. Закрытие очереди на сервере остаётся best effort; ограничения честно описаны в листе.
5. Тестовые reopen/fault проверяют границы SQLite и алгоритм удаления intent, **не являются настоящим аварийным завершением Android с радио/Signal/outbox**.

## Проверки

Тулчейн репозитория: Rust 1.99.0, JDK 17, Gradle 9.8.1 с проверкой SHA256 из CI, Android SDK 37.0/build-tools 37.0.0, NDK 28.2.13676358. Штатные fetch-protoc/build-android-core.sh release и UniFFI; JNI arm64 собран. Версии не менялись.

- `assembleDebug lintDebug testDebugUnitTest testReleaseUnitTest`: успешный финальный прогон; debug — 108, release — 94 теста, без failures/errors. Lint-ошибки ContextCastToActivity исправлены через LocalActivity, исключения не добавлялись.
- detekt 1.23.8 с SHA256 из CI, cargo fmt --all --check, check-crypto-bans, SEK lint (16 правил, 10/20 inbox), git diff --check — успешно.
- До правок: immutable worktree `33bfad196c605db78c38978b56405425f0aa90e6`, штатный native-рендер, 104 PNG.
- После: production Compose, 138 PNG; 148 Robolectric/native SQLite/semantics/render тестов без ошибок. Матрица: 368×754 dp обе темы; 320×500 dp/200%; landscape 640×320 dp/200%; адаптивная навигация, прокрутка нижнего листа и видимые действия.
- Exact-token contrast тест: 5 пар × обе темы, ≥4.5:1, включая danger. Светлый danger затемнён до #D23B35; тёмный использует тёмный текст на ярком danger, как в v18.
- Intentional regression: **только build-in копия** Theme.kt, #2479C3 → #168438; настоящий native-тест ScreensLight.chats; verify завершился ненулевым кодом и назвал ровно `l/chats.png`. Эталоны не перезаписывались. После восстановления копии штатным run.sh полный default verify прошёл: `Native UI goldens match: 138 images`.
- Визуально осмотрены ключевые native-состояния: ошибка/ранняя история и непрочитанное, сохранённый черновик, failed preview, Saved contact, созданная ссылка без гостевого доступа обеих тем, удаление/retry обеих тем и adaptive scroll, крупные кнопки/навигация, landscape. При осмотре исправлены скрытая четвёртая вкладка и неполная/неравновысокая share/copy. Парные linkmade/chat-anya до/после подготовлены отдельно. **Не заявляется ручной осмотр каждого из 138 изображений.**
- Промежуточные параллельные Gradle-процессы перегрузили 4 GiB среду; остановлены и повторены последовательно с локальным heap 768 MiB. Проверки и CI не ослаблялись. Lint UAST упал на выражении чтения: переписано явными локальными переменными; финальный lint прошёл. Непустой черновик выявил необходимость host JNA той же версии для настоящего Rust textOk; валидатор не отключался.
- Обязательный native verify подключён к Android CI; PNG и test reports публикуются в `sm-native-ui`. Все прежние проверки сохраняются. **Статус удалённого ci-ok проверяется на финальном SHA PR, не подменяется локальным прогоном.**

Полная приёмка на телефонах остаётся открытой: физическое радио/потеря ACK/асинхронный start failure; реальные permissions/channel/уведомления/Home/lock; Activity recreate/process kill; аппаратный Keystore/полное аварийное восстановление SQL+Signal+outbox; сеть/NEW/onLost; TalkBack/IME. PR не сливать до зелёного ci-ok и требуемой приёмки.
