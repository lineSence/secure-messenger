# Read-only аудит UX/UI чатов secure-messenger

**SHA:** `0ccfbaa80eba483ea6c2cf6b83a6b80026235bae`. **Дата:** 2026-10-14.

## Итог

8 подтверждённых статическим анализом дефектов: **P1 — 0, P2 — 6, P3 — 2**. Подтверждение здесь означает доказанную ветвь/несогласованность в коде, **не** воспроизведение на Android. Для каждой находки ниже приведены условия воспроизведения и предлагаемый регрессионный тест; тесты приложения не запускались.

Клон `/data/secure-messenger-audit` прочитан без изменений; HEAD проверен дважды и равен SHA выше, `git status --porcelain` и `git diff --stat` пусты. Никакие изменения, коммиты, PR, комментарии или issues в репозитории не создавались. Отчёт находится вне клона.

### Метод и границы

- Через GitHub MCP после обязательного получения схем `get_file_contents`/`get_commit` прочитаны `AGENTS.md`, `wiki/architecture/ux.md`, `wiki/rules/ui.md`, список корня; `get_commit` не вызывался. Дальнейшее чтение MCP было остановлено ошибками discovery и HTTP 429.
- После явного разрешения координатора использован read-only локальный fallback через bash. Kotlin исследован **локально**, не следует представлять его как успешно полученный через MCP.
- Дополнительно прочитаны `wiki/architecture/android.md`, `wiki/workflows/releases.md`, релевантные строки `wiki/architecture/decisions.md` (ADR-40/63/143/151), правила кода/CI/ревью, release notes, релевантные участки принятого `design/mockups/ui-v14.html`.
- Основной UX baseline — принятый v14. Координатор уточнил, что PR91 с v16 onboarding уже слит, а wiki отстаёт: наличие v16 кода не классифицировалось как дефект, onboarding вне области этого отчёта.
- Прослежены `ChatScreens`, `ChatHub`, `MessageStore`, `MessageInfo`, `Notifier`, `NetStatus`, `MessagePathLabel`, маршруты `MainActivity`, `ContactRemoval`, `ContactBook`, `DeliveryStore`, связанные тесты статусов и фрагменты `InviteFlow`.
- Приложение, эмулятор, Roborazzi/UI-shots и Android-тесты не запускались. Пиксельное соответствие, реальные разрешения/уведомления, TalkBack и поведение OEM не подтверждены. Read-only требование исключало сборку, пишущую результаты в клон.
- Отдельно выполнена **синтетическая модель SQL в SQLite :memory:**, не тест реального `MessageStore`/Keystore: 601 входящее → запрос истории возвращает 500 (минимальный id 102); 1 входящее + 500 исходящих при readMark=0 → модель unread возвращает 0, реальное количество входящих в модели — 1.

### Приоритеты

P1 — критическая потеря/нарушение основного сценария без разумного обхода; P2 — значимый дефект действующего сценария, в том числе условная потеря сообщения при ошибке; P3 — локальная дезинформация/UX-несогласованность без потери данных. P1 по исследованным путям не доказан; это не гарантия его отсутствия.

| ID | Приоритет | Область | Находка |
|---|---|---|---|
| CHAT-01 | P2 | Уведомления | Оставшийся в composition чат подавляет уведомления после ухода приложения в фон |
| CHAT-02 | P2 | История/непрочитанное | Последние 500 строк — единственная доступная история; прочтение закрывает и невидимую часть |
| CHAT-03 | P2 | Экран чата | Любая глобальная revision сбрасывает прокрутку к последнему сообщению |
| CHAT-04 | P2 | Отправка | Черновик стирается до успешной записи; сбой записи теряет текст без сообщения об ошибке |
| CHAT-05 | P2 | Удаление | Неуспешное удаление уже уничтожает outbox, хотя UI обещает «Ничего не изменено» |
| CHAT-06 | P2 | Экран чата | Ошибка загрузки истории показывается как пустой чат/старое содержимое без ошибки |
| CHAT-07 | P3 | Список чатов | Failed изображается часами ожидания, как продолжающаяся доставка |
| CHAT-08 | P3 | Экран чата | Контакт по интернет-ссылке объявляется добавленным по Bluetooth |

## CHAT-01 — P2: уведомления подавляются в фоне для последнего открытого чата

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:405–418`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L405-L418); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:131–132`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L131-L132); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:399–409`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L399-L409); [`android/app/src/main/java/dev/sm/messenger/MainActivity.kt:108–116`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L108-L116).

**Факт из кода.** `visibleChat` устанавливается в `DisposableEffect` и очищается только при `onDispose`, а не при `ON_STOP`/потере видимости Activity. В `onStop` только `store.flush()`. Получение нового текста вызывает `notifier.message` лишь когда адрес отличается от `visibleChat`. Проверка всех обращений `visibleChat` в main-source не обнаружила lifecycle-коррекции. Остановка Activity сама по себе не означает удаления `ChatScreen` из composition.

**Условия/воспроизведение:** открыть чат A; оставить процесс и фоновую связь работающими; нажать Home или заблокировать экран без закрытия Activity; получить новое сообщение от A. Для контроля получить сообщение от B.

**Expected:** когда Activity не видна, сообщение A создаёт уведомление так же, как B (если системные уведомления разрешены). Невидимый чат не погашает непрочитанное автоматически.

**Actual по коду:** A остаётся в `visibleChat`, ветка создания его уведомления не выполняется. Дополнительно effect на `revision` не ограничен lifecycle и может вызвать `markRead` для фонового экрана; фактическое расписание recomposition в фоне требует проверки на устройстве, поэтому этот дополнительный эффект не заявляется как воспроизведённый.

**Рекомендация:** учитывать состояние `RESUMED`/действительную видимость; устанавливать и очищать активный адрес при lifecycle-переходах и dispose; разрешать `markRead` только видимому экрану. Выбрать и явно проверить политику многооконного режима.

**Регрессионный тест (предложен):** ActivityScenario/Robolectric — открыть A, перейти в STARTED/CREATED без уничтожения, принять A → ровно одно уведомление, readMark не сдвинут; вернуться в RESUMED → уведомление очищено; видимый A подавляет уведомление; B уведомляет. На устройстве повторить Home/lock с работающей службой.

## CHAT-02 — P2: история обрезана до 500 строк без пути к более старым; непрочитанное теряется

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt:88–119`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt#L88-L119); [`android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt:121–125`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt#L121-L125); [`android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt:222–225`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/MessageStore.kt#L222-L225); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:473–489`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L473-L489); [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:409–418`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L409-L418); [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:490–505`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L490-L505).

**Факт из кода.** `history` по умолчанию делает `ORDER BY id DESC LIMIT 500`, затем reverse; `ChatHub.history` не передаёт иной limit/курсор. UI рисует только полученные `lines`, загрузки предыдущей страницы нет. `markRead` перед загрузкой ставит отметку на `MAX(id)` всей истории. `unread` тоже просматривает только последние 500 **всех** строк и лишь после этого считает входящие.

**Условия/воспроизведение:** сохранить 601 входящее от A, не открывая чат; увидеть счётчик в списке, открыть чат и прокрутить к началу.

**Expected:** все сохранённые сообщения достижимы через постраничную загрузку, либо ограничение явно обозначено и предложен доступ к предыдущим; счётчик не выдаёт усечённое значение за точное; прочтение не скрывает незагруженные непрочитанные сообщения.

**Actual по коду:** доступны только id 102…601; первые 101 остаются в базе, но не достижимы в UI; readMark уже 601. Счётчик до открытия равен 500, после — 0. Это **не физическое удаление** истории. В варианте «старое входящее + 500 более новых исходящих, все после readMark» unread может дать 0 при наличии входящего.

**Рекомендация:** keyset-пагинация по id (`beforeRowId`), подгрузка у начала, сохранение якоря; отдельное корректное вычисление входящих или явный saturating badge `500+`, не маскирующий наличие входящих за исходящими; продумать отметку прочтения/переход к первому непрочитанному.

**Регрессионный тест (предложен):** 601 входящее, все сообщения доступны через paging; переход назад/перезапуск не теряет доступ; unread=601 либо честное `500+`; отдельный кейс 1 входящее + 500 исходящих не возвращает 0. SQL-модель ограничения воспроизведена отдельно, но Android/шифрованное хранилище здесь не запускались.

## CHAT-03 — P2: глобальное обновление безусловно прерывает чтение истории

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:409–418`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L409-L418); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:259–262`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L259-L262); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:399–415`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L399-L415); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:588–590`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L588-L590).

**Факт из кода.** Ключ effect — глобальная `chat.revision`, не поток только текущего чата. После любого успешного чтения непустой истории вызывается `scrollToItem(lines.size - 1)`. Нет проверки «пользователь у конца», новой строки, текущего адреса события или изменения содержимого.

**Условия/воспроизведение:** в длинном чате A прокрутить вверх; получить подтверждение доставки в A, новое сообщение в другом чате B либо обновление `onRelaySent` любого контакта.

**Expected:** оставаться на читаемом сообщении. Автопрокрутка — при открытии, собственной отправке или приходе нового текста, если пользователь уже у конца; иначе индикатор новых сообщений.

**Actual по коду:** revision → перезагрузка A → безусловный прыжок вниз, даже если содержимое A не изменилось. Это не только эффект входящего сообщения, а общий invalidation.

**Рекомендация:** отделить первоначальный scroll от обновлений, сохранять якорь по rowId; ограничить invalidation контактами; скроллить вниз только при явно выбранной политике «follow tail».

**Регрессионный тест (предложен):** Compose-тест с историей 100 сообщений, якорь на 20-м; изменить delivery state, затем revision другого контакта → якорь сохраняется. При отправке из конца новое сообщение остаётся видимым. При приходе сообщения во время чтения выше конца появляется индикатор без прыжка.

## CHAT-04 — P2: ошибка записи отправки безвозвратно очищает введённый текст

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:442–445`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L442-L445); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:180–211`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L180-L211); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:592–610`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L592-L610).

**Факт из кода.** UI вызывает асинхронный `send`, немедленно присваивает `draft = ""`; метод не имеет callback/result для UI. При `SQLiteException`/ошибке Keystore/Signal guard возвращает null, `ok=false`; остаётся лишь запись диагностики и `bump`. Если ошибка произошла до commit `db.atomic`, истории и send-журнала с текстом нет. UI не получает причину и не восстанавливает черновик.

**Условия/воспроизведение:** ввести уникальный текст; внедрить ошибку SQLite/Keystore при `addOutgoing` или `db.kvPut(SEND, ...)` до фиксации; нажать отправку.

**Expected:** текст сохраняется в редакторе до подтверждения durable enqueue либо восстанавливается/доступен как failed-draft с понятной ошибкой и повтором. После durable enqueue можно очистить редактор без ожидания сети.

**Actual по коду:** поле пустое, транзакция откатилась, сообщения в истории нет; только диагностическое событие не заменяет пользовательскую обратную связь. Это отдельный случай от штатного переполнения outbox: там в коде есть строка `Failed`, и этот путь не заявляется как молчаливая потеря текста.

**Рекомендация:** вернуть typed result о durable enqueue, очистить соответствующую версию черновика после успеха; при ошибке не затереть текст, введённый после нажатия; показать понятный retry. Не ждать подтверждения получателя, чтобы разблокировать ввод.

**Регрессионный тест (предложен):** fake storage бросает до commit → черновик и ошибка видны, строка/очередь отсутствуют; retry создаёт ровно одно сообщение. Успех с задержкой и новым вводом в редакторе не стирает новый текст. Переполненная очередь сохраняет текст в доступной failed-строке.

## CHAT-05 — P2: неуспешное удаление контакта уже очищает очередь, но UI обещает откат всего

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:499–532`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L499-L532); [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:747–755`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L747-L755); [`android/app/src/main/java/dev/sm/messenger/relay/ContactRemoval.kt:19–24`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/ContactRemoval.kt#L19-L24); [`android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt:91–98`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L91-L98); [`android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt:118–133`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/store/SecureDb.kt#L118-L133).

**Факт из кода.** `dropContact` сначала удаляет сообщения контакта из outbox через `confirm` и сохраняет снимок (`saveOutboxNow`), и лишь затем начинает транзакцию удаления сессии/истории/контакта. Ошибка внутри этой транзакции откатывает SQLite, но не уже изменённую очередь в памяти/файле. Guard возвращает null, callback сообщает failure, UI выводит «Не удалось удалить контакт. Ничего не изменено». При failure `refreshWaiting` тоже не вызывается. У обычного уже сохранённого сообщения send-журнал ранее удалён, поэтому на его автоматическое восстановление полагаться нельзя.

**Условия/воспроизведение:** контакт A с долговечно сохранённым queued-сообщением (без записи chat_send); начать удаление; после успешного `saveOutboxNow` внедрить сбой `sessions.forget` или другой SQLite-операции внутри транзакции.

**Expected:** либо неуспех оставляет контакт, историю и очередь прежними, либо завершение согласованно восстанавливается из долговечного журнала, а UI честно сообщает частичное состояние.

**Actual по коду:** контакт и история остаются, исходящее всё ещё выглядит Queued, но запись из outbox уже удалена; UI утверждает отсутствие изменений. Сетевое закрытие очереди узла на этом пути не запускается. На реальном устройстве сбой не внедрялся; это доказанный порядок неатомарных операций и условный сценарий повреждения состояния.

**Рекомендация:** durable tombstone/журнал удаления с идемпотентными стадиями и recovery либо сохранение/восстановление outbox при abort; не обещать полный откат, пока он не обеспечен. После каждого исхода согласовывать waiting/историю/очередь. Одна SQLite-транзакция не охватывает отдельный снимок outbox.

**Регрессионный тест (предложен):** fault injection до/после сохранения outbox и на каждой операции SQLite; при отказе контакт + queued-сообщение остаются реально доставляемыми либо удаление гарантированно завершается после restart. Проверить процесс между стадиями. Успешное удаление при недоступном узле остаётся допустимым сценарием v14, не ошибкой.

## CHAT-06 — P2: отказ загрузки истории маскируется под отсутствие сообщений

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:399–418`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L399-L418); [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:490–497`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L490-L497); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:480–489`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L480-L489); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:592–610`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L592-L610).

**Факт из кода.** Начальные `lines=emptyList`, `broken=0`; если `chat.history` вернул null из guard, effect не меняет ничего и не выставляет error state. Content при пустом списке пишет «Сообщений пока нет. Напишите первым…». Если данные раньше загрузились, показывается старый снимок без признака ошибки. `markRead` вызывается до попытки получения истории и не связан с её успешностью.

**Условия/воспроизведение:** открыть непустой существующий чат, внедрив SQLite/Keystore-ошибку именно в `history`; вариант — успешная первая загрузка и ошибка следующей. Для markRead — дать его записи пройти, а затем уронить history.

**Expected:** отдельные loading/empty/error/stale состояния; сообщение о недоступном хранилище и повтор; не утверждать отсутствие переписки, когда загрузка не удалась; не сбрасывать unread/уведомление до успешного отображения.

**Actual по коду:** ложное пустое состояние либо молча устаревшая история; уведомление очищено вызовом markRead, а метка прочтения могла уже сдвинуться. В отличие от частично повреждённых строк, для которых существует `broken`, полный отказ запроса вообще не отражён в UI.

**Рекомендация:** явная модель `Loading/Loaded/Error(previous)`; отличать пустоту от null и показывать retry; обновлять read marker после успешной загрузки для действительно видимой истории.

**Регрессионный тест (предложен):** непустая БД + history failure → видна ошибка, нет текста «Сообщений пока нет», read marker не изменён, уведомление не погашено; retry показывает историю. Partial-corruption по-прежнему показывает broken count, не маскируется общим empty.

## CHAT-07 — P3: в списке чатов невозможная отправка выглядит как продолжающаяся

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:345–358`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L345-L358); [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:576–589`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L576-L589); [`android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt:197–201`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatHub.kt#L197-L201).

**Факт из кода.** `ChatRow` проверяет только `last.state == Delivered`; любой другой исходящий статус, включая Failed, получает Clock, текст доступности «ещё не доставлено» и обычный tint. Внутри чата Failed получает Warn и красное «не доставлено». Failed реально выставляется при переполненной очереди и в других отказах, а не является плановой заглушкой.

**Условия/воспроизведение:** последнее исходящее A получает Failed, например из-за полной очереди; вернуться в список чатов.

**Expected:** список сохраняет различие «ждём связь, попробуем сами» и «отправка невозможна»; failed заметен, пользователь не ждёт несуществующий retry.

**Actual по коду:** одинаковая пиктограмма и описание для Queued и Failed. Претензия здесь именно к Failed; представление промежуточного onNode в списке не объявляется отдельным дефектом, поскольку принятый HTML `lastIcon` сам упрощает ряд состояний.

**Рекомендация:** общая status presentation модель для списка/чата: Failed → Warn + корректная семантика, Queued → Clock, Delivered → circle-check, onNode — по согласованному варианту макета.

**Регрессионный тест (предложен):** параметризованный тест ChatRow для Queued/Failed/Delivered и Compose semantics/screenshot: Failed не имеет часов и текста «ещё не доставлено», использует отличимый статус ошибки. Не полагаться только на цвет.

## CHAT-08 — P3: ложное происхождение контакта в баннере безопасности

**Доказательства:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:480–483`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L480-L483); [`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:305–311`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L305-L311); [`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:365–374`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L365-L374).

**Факт из кода.** Каждый `!contact.verified` получает баннер «Контакт добавлен по Bluetooth рядом и ещё не сверен». `InviteFlow` сохраняет контакты по интернет-ссылке с `verified=false`, то есть условие этого баннера истинно и без Bluetooth. Логика не использует источник добавления.

**Условия/воспроизведение:** добавить ранее отсутствовавший контакт по ссылке через узел, Bluetooth выключен; открыть чат до сверки.

**Expected:** нейтральное «Ключ контакта ещё не сверен» либо фактический источник, если он действительно хранится. Баннер безопасности не должен выдумывать личную близость/канал знакомства.

**Actual по коду:** сообщается добавление по Bluetooth, которого не было; сама метка «не сверен» корректна, отправка не объявляется заблокированной.

**Рекомендация:** убрать происхождение из общей строки; хранить/показывать его только при наличии надёжных данных. Это исправление текста действующего баннера, не требование реализовать непринятый onboarding.

**Регрессионный тест (предложен):** создать несверенный контакт через InviteFlow, открыть ChatContent → баннер не содержит Bluetooth; сверенный контакт → баннера нет. Отдельно несверенный BLE-контакт сохраняет корректное предупреждение о сверке.

## Риски и дополнительные проверки — НЕ подтверждённые дефекты устройства

1. **Нажатие уведомления не передаёт адрес чата.** [`android/app/src/main/java/dev/sm/messenger/chat/Notifier.kt:29–48`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/Notifier.kt#L29-L48) строит общий launcher Intent с requestCode=0 без contact extras. [`android/app/src/main/java/dev/sm/messenger/MainActivity.kt:182–203`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L182-L203) использует внутренний route. Доказано отсутствие адресной маршрутизации в этих путях; не установлено требование v14 открывать конкретный чат по уведомлению, поэтому это **кандидат UX-улучшения**, не отдельный P2. Проверить warm/cold task и уведомления от двух контактов, согласовать ожидаемый маршрут.
2. **Lockscreen/privacy.** [`android/app/src/main/java/dev/sm/messenger/chat/Notifier.kt:36–48`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/Notifier.kt#L36-L48) использует PRIVATE + обезличенную publicVersion — это положительный факт кода, не доказательство поведения всех системных настроек Android. Проверить скрытие имени/текста при разных настройках блокировки, разрешениях, версии ОС и канале. Отсутствие плановых настроек приватности не объявляется багом.
3. **Accessibility/крупный шрифт/IME.** Метаданные сообщения кликабельны в небольшой визуальной строке, а часть иконок декоративна ([`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:620–642`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L620-L642)). Нельзя вывести реальную область касания и дерево TalkBack только из высоты видимого текста: проверить фактическое расширение touch target, label/role и доступность сведений, fontScale 2.0, длинный текст, клавиатуру и landscape. Не заявляется доказанное нарушение 48 dp.
4. **Долгая доставка.** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:580–585`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L580-L585) вычисляет возраст через `System.currentTimeMillis` при composition, собственного таймера в этом участке нет. Зависимые обновления экрана могут приводить к recomposition, поэтому точное запаздывание 10-минутной подсказки не измерено. Кроме того, расширенные действия UX-29 документированы как «позже»: отсутствие LoRa/файла/QR/меню удаления сообщения не включалось в баги.
5. **Визуальное соответствие.** Парные картинки макет/приложение не сняты. Точные различия отступов, теней, цветов, status icon и контраста не выдаются за установленные. Для последующих UI-исправлений требуется UI-RENDER-CHECK с парами до/после и проверкой владельцем на телефоне.

## Что намеренно НЕ объявлено багами

- Плановые поиск, папки/группы, вложения, emoji-панель, реакции/ответы, per-chat mute, исчезающие сообщения, оформление, блокировка и сверка QR, если они честно помечены «Скоро».
- Отсутствие уведомления собеседнику об удалении — ADR-143 предложен, опционален; отсутствие очистки копии у собеседника — принятый v14.
- Неуспешная однократная попытка закрытия очереди на недоступном узле после успешного локального удаления — оговорённое ограничение, не CHAT-05.
- Различие пути текста и обратного ACK: `MessagePath` в текущей модели прямо определён как путь подтверждения, не как журнал маршрута всех копий сообщения. Без трассы двух транспортов нельзя доказать пользовательский дефект маршрута.
- Уже исправленные #89: queued без подтверждённого пути не получает выдуманную Bluetooth-подпись ([`android/app/src/main/java/dev/sm/messenger/chat/MessagePathLabel.kt:3–8`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/MessagePathLabel.kt#L3-L8)), node idle не изображается рабочим зелёным узлом ([`android/app/src/main/java/dev/sm/messenger/chat/NetStatus.kt:34–60`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/NetStatus.kt#L34-L60)). `MessageInfoContent` для queued допускает оба пути, без заявления о выбранном ([`android/app/src/main/java/dev/sm/messenger/chat/MessageInfo.kt:62–67`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/MessageInfo.kt#L62-L67)). Связанные `MessagePathLabelTest`/`NetStatusTest` прочитаны, не перезапускались. Старые проблемы обмена адресами после QR и блокировки HTTPS по BLE-метке повторно не заявлялись.
- Сведения о сообщении реально реализованы; устаревший комментарий ChatScreens о «заглушке» не использовался как доказательство её отсутствия. Доступ только по времени **своего** сообщения соответствует текущей android-документации.
- Слитый PR91/v16: факт текущего кода принят во внимание по уточнению координатора, хотя таблица wiki ещё считает макет предложенным; функциональность onboarding не аудировалась.

## Рекомендуемый порядок дальнейшей работы

1. CHAT-04/05: защитить пользовательский текст и состояние очереди при отказах хранения, с fault-injection тестами.
2. CHAT-01/06: lifecycle-aware видимость и честные состояния загрузки/прочтения.
3. CHAT-02/03: paging истории, корректное unread и стабильная прокрутка.
4. CHAT-07/08: единая модель статусов и правдивые тексты безопасности.
5. Затем воспроизвести все сценарии на двух телефонах и выполнить визуальную сверку v14 для изменённых экранов. Предложенные тесты не выдавать за уже выполненные.

## Контроль источников

Все ссылки на код в отчёте закреплены полным SHA; номера строк взяты из `nl -ba` локального клона. Документация — источник ожидаемого поведения и scope, но не заменяет факты реализации.

- [AGENTS.md](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/AGENTS.md)
- [UX и принятие макетов](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/architecture/ux.md)
- [UI-правила и фиксы #89](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/rules/ui.md)
- [Android-архитектура](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/architecture/android.md)
- [План релизов](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/workflows/releases.md)
- [ADR-40/63/143/151](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/architecture/decisions.md)
- [Принятый базовый макет v14](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/design/mockups/ui-v14.html)

**Статус:** статический аудит перечисленных путей завершён; динамическая/визуальная приёмка и проверка реальных устройств не выполнены. Первоначальная запись о блокировке MCP заменена этим отчётом после разрешённого локального fallback.
