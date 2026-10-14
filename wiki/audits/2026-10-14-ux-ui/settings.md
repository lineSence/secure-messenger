# Независимый read-only UX/UI аудит: настройки, первый запуск и связь

- Репозиторий: `lineSence/secure-messenger`.
- Проверенный SHA: `0ccfbaa80eba483ea6c2cf6b83a6b80026235bae`; HEAD — merge PR #91 (`feat/ui-v16-onboarding`). Дата аудита: 2026-10-14.
- Область: onboarding / разрешения / восстановление; вкладка «Связь»; запись узла и пропуск; настройки / профиль; debug-журнал; правдивость сетевого состояния и восстановление после ошибок.
- Итог: **P1 — 0 подтверждённых; P2 — 5; P3 — 3.** Это статические дефекты/несогласованности кода, не результаты запуска APK. Отсутствие P1 в этой области не является заключением о безопасности всего приложения.
- Ничего в репозитории не изменено, ничего не закоммичено. В начале исследования проверены HEAD и пустой `git status --porcelain`; при сохранении — отсутствие изменений tracked-файлов относительно HEAD. Позже в общем клоне появилась только чужая untracked-папка публикации `wiki/audits/2026-10-14-ux-ui/`, она не использована как исходник аудита.

## Метод и ограничения

Прочитаны `AGENTS.md`, `wiki/architecture/ux.md`, `wiki/rules/ui.md` через GitHub MCP после получения schema `get_file_contents`, всегда с закреплённым SHA. После discovery failures и HTTP 429 дальнейшие обращения к MCP прекращены. По явному разрешению заказчика код исследован read-only в `/data/secure-messenger-audit`. Дополнительно прочитаны `wiki/architecture/android.md`, `wiki/workflows/releases.md`, ADR-150/151 и соответствующие Kotlin-реализации, включая изменения #89 и фактический onboarding #91.

**Приоритет источников:** код этого SHA — факт реализации; согласованные v14 и последующие принятые ADR — контекст. Wiki всё ещё называет v16 предложенным и описывает старый onboarding, но в этом SHA новый поток уже реализован. Это не повод оценивать отсутствующие старые экраны как отдельные дефекты: ниже проверяется именно код PR91. Непринятие v16 в устаревшей wiki не используется как UX-находка. См. [wiki/architecture/ux.md:24–29](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/architecture/ux.md#L24-L29) и [android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt:67–85](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt#L67-L85).

Все сценарии ниже **выведены из конкретных ветвей кода**, а не воспроизведены на Android. JVM/Compose/инструментальные тесты, Gradle, рендер Roborazzi и физические телефоны в этом аудите не запускались. Поэтому визуальная точность, производительность, системные окна и OEM-поведение не объявляются доказанными. Тесты в карточках — предлагаемые регрессионные проверки, не отметки «прошёл».

Шкала: P1 — блокирующая потеря данных/доступа либо опасное нарушение базового сценария; P2 — существенный срыв сценария, ложное состояние или отсутствие понятного восстановления; P3 — локальная путаница/неполнота обратной связи с обходным путём.

## Сводка

| ID | Приоритет | Находка |
|---|---|---|
| S01 | P2 | Приглашение теряется при пересоздании Activity, в том числе после нового onboarding |
| S02 | P2 | В новом обычном потоке нет запроса уведомлений и нет предупреждения об их отсутствии |
| S03 | P2 | Ошибка подготовки почты не переводит сетевой статус в ошибку; потеря сети также не будит обновление |
| S04 | P2 | Idle на общей полосе превращается в бесконечное «Подключение…» |
| S05 | P2 | Любая ошибка сохранения записи узла объясняется несуществующей более новой записью |
| S06 | P3 | Сохранение записи узла объявляется состоявшимся подключением к интернету |
| S07 | P3 | Заведомо отозванный пропуск можно импортировать с сообщением «Пропуск принят» |
| S08 | P3 | Debug-журнал не обновляет список и доступность выгрузки при поступлении событий |

## Подтверждено статически

### S01 · P2 · Потеря текста приглашения при пересоздании Activity

**Точные места:** [android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt:82–86](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt#L82-L86); [android/app/src/main/java/dev/sm/messenger/MainActivity.kt:129–154](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L129-L154); [android/app/src/main/java/dev/sm/messenger/MainActivity.kt:228–241](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L228-L241); [android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:500–518](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L500-L518).

**Сценарий:** свежая установка → «Мне прислали приглашение» → вставить текст → повернуть устройство на вводе или следующем шаге. Второй вариант: пройти создание ключей → «Проверить приглашение» → получить ошибку / открыть настройку узла → пересоздать Activity и вернуться к ссылке.

**Expected:** введённое приглашение остаётся доступным для проверки/исправления при обычной конфигурационной смене. Секретный текст не должен автоматически попадать в незашифрованный Bundle или logcat.

**Actual:** `step`, `intent` и `name` — saveable, а `invitation` — только `remember`. После onboarding текст живёт в `MainActivity.firstInvite`, тоже только `remember`. Во вкладке ссылок этот непостоянный текст передаётся как controlled-state (`initialText` + callback), поэтому локальный запасной текст не спасает ситуацию. При пересоздании состояния текст становится пустым, хотя маршрут/шаг может восстановиться. Комментарий «Текст хранится в saveable-состоянии» у `InvitationEntry` не соответствует объявлению состояния. Исходная ссылка не отзывалась, контакты не теряются — это потеря пользовательского ввода, не криптографических данных. См. [android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt:258–280](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt#L258-L280).

**Рекомендация:** один scoped ViewModel для черновика onboarding/add-link с явной очисткой после успеха/отмены; переживать rotation без сериализации секрета. Для process death либо защищённый временный черновик с TTL, либо честное объяснение, что ссылку нужно вставить заново; не советовать просто `rememberSaveable` для чувствительного содержимого без решения о хранении.

**Тест:** Compose/ActivityScenario: вставить canary-текст, `recreate()` на шаге 5 и после перехода в `add:Link`, сверить поле; затем посетить узел и вернуться. Проверить, что canary отсутствует в saved-state Bundle, логах и незашифрованных файлах. Отдельный kill/relaunch-тест должен соответствовать выбранной политике process death.

### S02 · P2 · Уведомления остаются выключенными без контекстного запроса или предупреждения

**Точные места:** полный выбор шагов нового onboarding — [android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt:98–180](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt#L98-L180); единственное рабочее место запроса `POST_NOTIFICATIONS` — [android/app/src/main/java/dev/sm/messenger/MainActivity.kt:334–363](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L334-L363); строки настроек без фактического состояния — [android/app/src/main/java/dev/sm/messenger/MainActivity.kt:419–421](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L419-L421); отправка уведомления — [android/app/src/main/java/dev/sm/messenger/chat/Notifier.kt:29–54](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/Notifier.kt#L29-L54).

**Сценарий:** чистая установка на Android 13+ → любой новый onboarding → добавить контакт → свернуть приложение → получить сообщение. Пользователь отдельно не открывал «Настройки → Уведомления».

**Expected:** в подходящий момент первого рабочего чата/включения фоновой связи объяснить уведомления и предложить разрешить или пропустить. При отсутствии разрешения — понятная отметка/баннер с исправлением, без ложного обещания уведомлений.

**Actual:** `finish()` завершает onboarding сразу в чатах или добавлении контакта. `PermissionsContent` существует, но не вызывается новым потоком. Поиск call sites показывает запрос уведомлений только при ручном клике строки настроек; проверки/баннера текущего разрешения в обычном сценарии нет. `Notifier` строит уведомление, а отказ `SecurityException` подавляет; собственный комментарий класса прямо предусматривает, что Android без разрешения ничего не показывает. Строка настроек неизменно говорит «Разрешить показ новых сообщений», не показывает «не разрешено». Наличие фоновой доставки этим не доказывается: находка именно про недоступный пользователю сигнал нового сообщения. Принятый UX-18 требует объяснение и индикацию отказа, но дефект не сводится к требованию вернуть старый обязательный шаг.

**Рекомендация:** контекстный opt-in с возможностью отказа и проверка `areNotificationsEnabled` / runtime permission / состояния канала. После отказа не принуждать немедленным переводом в Android Settings; сохранять понятное действие «Разрешить уведомления» и актуальный статус после `onResume`. Аналогично отдельно показывать ограничения работы в фоне, не смешивая их с разрешением уведомлений.

**Тест:** Android 13+ fresh install: без ручного посещения настроек пройти до первого чата; проверить контекстное предложение и «Позже», отказ → заметная статусная подсказка; выдать разрешение в системных настройках → вернуться и проверить обновление UI. Фактический показ уведомления/поведение канала проверить на устройстве отдельно.

### S03 · P2 · Сетевой статус не отражает сбой подготовки почты и может застывать после потери сети

**Точные места:** callback потери сети — [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:126–144](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L126-L144); обработка исключений — [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:138–170](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L138-L170); подготовка очередей до обработчика `receive` — [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:209–251](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L209-L251); сетевой `NEW` — [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:279–299](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L279-L299) и [android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt:59–67](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L59-L67).

**Сценарий A (детерминированная ветвь):** есть валидные запись/пропуск и контакт без очереди текущего периода; узел недоступен или отвергает `NEW` (например, лимит/отзыв на сервере). `ensureQueues()` вызывает `relay.newQueue()` и получает `RelayException`.

**Expected:** пользователь видит причину остановки подготовки почты и соответствующее действие: сеть/узел недоступен → повтор/ожидание; пропуск отклонён → получить другой пропуск. Нельзя бесконечно изображать продолжающееся подключение либо старое «Связь есть».

**Actual:** `ensureQueues()` вызывается вне `try` вокруг `relay.receive()`. Только catch последнего выставляет `NodeLink.Down`; catch верхнего `loop()` пишет фиксированный debug-код и возвращает retry delay, не меняя `link`. Первичная попытка остаётся `Connecting`, а ранее успешный цикл может сохранить `Ok`. Ошибка хранения/ключа в подготовке очередей столь же не имеет собственного UI-состояния. В release debug-журнал не заменяет обратную связь пользователю.

**Сценарий B (расширяет ту же проблему актуальности):** после успешного мобильного FETCH выключить сеть во время ожидания следующего опроса. `onLost()` — `Unit`; он не обновляет `link` и не будит `bell`. До следующего шага старое `Ok` продолжает читаться интерфейсом. Если потеря сети произошла перед входом в `nap()`, `bell.take()` может ждать появления сети, так и не выставив `Offline`. Это доказательство возможного пути по коду, не измерение задержки на телефоне.

**Рекомендация:** централизовать обновление типизированного receive/account status для всех стадий цикла, включая `ensureQueues`, ACK и ошибки хранилища. Различать `Rejected` и недоступность сети. `onLost` должен инициировать пересчёт состояния с учётом текущей default network и будить receive-loop; сохранить UX-задержку 2 с против мигания на уровне отображения, а не маскировать устойчивый сбой.

**Тест:** fake relay: `NEW` бросает NoNetwork/Rejected, при начальных Connecting и Ok — после шага не остаётся ложного Ok/вечного Connecting, UI даёт корректное действие. Fake connectivity/clock: потеря сети во время poll-sleep и непосредственно перед `nap`, затем восстановление; проверить переход Offline и пробуждение без ожидания обычного опроса. Никакого удаления контактов/очереди при временной ошибке.

### S04 · P2 · Idle исправлен в узле (#89), но не в общей полосе

**Точные места:** [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:224–232](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L224-L232); [android/app/src/main/java/dev/sm/messenger/chat/NetStatus.kt:41–64](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/NetStatus.kt#L41-L64); для сравнения исправленный экран узла — [android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt:210–216](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt#L210-L216) и [android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt:353–366](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt#L353-L366).

**Сценарий:** принять валидную запись узла, не иметь контактов/активных очередей ссылок, вернуться в пустые чаты. `receiveOnce` обнаруживает пустой набор очередей и выставляет `Idle`.

**Expected:** «Узел принят · связь ещё не проверена / добавьте контакт», без индикации выполняющегося запроса и без зелёного подтверждения сети.

**Actual:** `netStatus()` не обрабатывает Idle явно: он попадает в `else` → «Подключение…», «сообщения ждут на телефоне», даже в пустом списке без сообщений. Пояснение «подключится, когда появится контакт…» есть на экране узла, а в глобальном постоянно видимом индикаторе его нет. Это не старый уже исправленный дефект «Idle зелёный»: #89 учтён; остаток — различная семантика одного состояния в двух местах.

**Рекомендация:** явное нейтральное/предупреждающее отображение Idle в общей функции статуса, с понятным следующим действием. Не превращать Idle в Ok и не добавлять фиктивную сетевую проверку ради красивого индикатора.

**Тест:** расширить `NetStatusTest`: Idle × Off/Scan/Conn/Lost; без доказанной работающей сети нет «Связь есть», без запроса нет «Подключение…». Compose empty-chats + Idle должен предлагать первый контакт, а не утверждать наличие ждущих сообщений.

### S05 · P2 · Сбой сохранения узла выдается за более новую запись

**Точные места:** [android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt:146–156](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt#L146-L156); причины `false` и запись в хранилище — [android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt:41–52](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt#L41-L52).

**Сценарий:** корректная подписанная запись открыта в предпросмотре → «Принять»; запись `kv.put` не удалась (SQLite/Keystore/CorruptRow) либо срок записи успел закончиться между предпросмотром и нажатием.

**Expected:** достоверное сообщение о причине: «Не удалось сохранить, прежняя запись не изменена, попробуйте снова» либо «Срок записи вышел». Текст для новой попытки/предпросмотр желательно сохранить. «Есть более новая» — только когда это действительно установлено.

**Actual:** `runCatching(...).getOrNull()` превращает исключения в null. Любой результат, кроме true, после `imp = null` показывает «На телефоне уже есть запись этого узла новее. Оставили её». `setNode` может вернуть false не только из-за issued-order и может бросить при `kv.put`. Более новой/вообще прежней записи при этом может не быть. Ложная причина направляет пользователя за другим QR вместо исправления хранения/повтора.

**Рекомендация:** вернуть типизированный результат (Saved / OlderThanCurrent / Expired / Invalid / StorageFailure), исключения не сводить к Older. При временной ошибке оставить preview или защищённый временный ввод и кнопку повторить; не обещать сохранность старой записи без проверки результата.

**Тест:** fault injection `kv.put` → исключение на пустом account и на существующем; UI не содержит утверждения «есть запись новее», повтор работает и не создаёт новых ключей. Отдельно истечение срока между preview/accept и реальный old-issued отказ.

### S06 · P3 · Toast обещает подключение, хотя сохранена лишь запись

**Точное место:** [android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt:148–155](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt#L148-L155); отсутствие проверки сети/пропуска в `setNode` — [android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt:45–52](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt#L45-L52).

**Сценарий:** режим без сети или полностью недоступный сервер; импортировать валидную запись узла, в том числе без пропуска.

**Expected:** «Запись узла принята»; о работающей связи говорить только по сетевому подтверждению, а о недостающем пропуске — отдельно.

**Actual:** сразу после локального `setNode` + `wake` показано «Узел подключён · сообщения пойдут через интернет». Сетевой ответ не ожидается; `setNode` его не делает. Сам экран может затем показать «Нет сети»/«Узел принят»/«Нет пропуска», но первый итог уже ложный. Это остаточная формулировка, не отмена исправления Idle в #89.

**Рекомендация:** заменить локальный успех на «Запись узла принята»; сетевой статус и готовность почты показывать раздельно, по реальному состоянию.

**Тест:** accept при Offline/Idle/без пропуска: только подтверждение сохранения, без обещания интернет-доставки; при последующем реальном ответе узла состояние меняется независимо.

### S07 · P3 · Импорт отозванного пропуска заканчивается успехом

**Точные места:** [android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt:113–124](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/NodeScreen.kt#L113-L124); [android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt:60–64](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt#L60-L64); [android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt:89–94](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/RelayAccount.kt#L89-L94).

**Сценарий:** в принятой подписанной записи узла ID пропуска находится в revoked; пользователь вставляет этот ещё не истёкший и корректно подписанный `SMP1:`.

**Expected:** «Пропуск отозван — попросите новый», без утверждения, что им можно пользоваться. Не обходить отзыв.

**Actual:** `setPass()` проверяет подпись/срок через `openPass` и сохраняет, но не проверяет revoked. Поэтому UI сообщает «Пропуск принят». Затем `pass()` правильно отбрасывает его по revoked, и экран показывает общую строку «Нет пропуска». Защита не обходится, однако причина отказа скрыта, повторный ввод того же пропуска не поможет. При наличии другого гостевого пропуска может использоваться он — факт успешной вставки всё равно не объясняет судьбу импортированного SMP.

**Рекомендация:** различать «сохранён» и «пригоден», при известном отзыве отклонять/показывать точную причину до успеха. Не затирать действующий owner-pass неработоспособным импортом.

**Тест:** подписанный node с revoked ID + соответствующий SMP; проверить, что UI не показывает успех пригодного пропуска, даёт действие получить новый; `pass()` продолжает не возвращать отозванный credential. Повторить с доступным гостевым пропуском и без него.

### S08 · P3 · Открытый debug-журнал остаётся старым снимком

**Точные места:** [android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt:69–107](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt#L69-L107); [android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt:190–205](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt#L190-L205); источник событий/снимков — [android/app/src/debug/java/dev/sm/messenger/diagnostics/JournalWriter.kt:40–64](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/debug/java/dev/sm/messenger/diagnostics/JournalWriter.kt#L40-L64).

**Сценарий:** включить сбор → очистить журнал → оставить экран «Debug-логи» открытым; в фоне проходят события relay. Либо открыть экран пустого журнала при уже включённом сборе и дождаться событий.

**Expected:** видно, что сбор работает; число/последние события обновляются или явно написано время снимка и есть «Обновить». После появления событий доступна выгрузка без поиска обходного действия.

**Actual:** `view` загружается `LaunchedEffect(Unit)` и после ручных control actions/подготовки сохранения; подписки/периодического snapshot нет. Writer не публикует reactive state. На экране остаётся «Журнал пуст», Save и Clear выключены по старому `view.entries.isEmpty()`, хотя события уже записаны. Выйти и зайти или переключить сбор — обходной путь. При непустом старом снимке Save берёт свежий snapshot, так что это не утверждение о потере новых событий из самого экспортируемого файла.

**Рекомендация:** ограниченное по частоте обновление только видимого экрана / flow из writer либо явная кнопка «Обновить» с датой снимка. Не делать fsync snapshot на каждый кадр ради UI; сохранить неблокирующую очередь и лимиты ADR-150.

**Тест:** fake writer: открыть пустой view, добавить событие без toggle/reentry → через выбранный механизм счётчик растёт и Save доступен. Отдельно проверить сохранение свежего снимка и что частота обновлений ограничена.

## Что проверено и НЕ объявляется дефектом

- **#89:** Idle уже отображён жёлтым «принят» в `NodeRowView`/`NodeConnected`; не повторяю прежнюю жалобу на зелёный Idle. Доставка после QR и резервная отправка через узел не объявляются сломанными по старому коду. S04 — конкретный оставшийся consumer Idle, S03 — конкретные непокрытые ветви ошибок.
- **Восстановление ключа:** `DeviceKeys.load/create` не создаёт молча новую личность при ошибке; broken-state блокирует доступ. Это корректный fail-closed. [android/app/src/main/java/dev/sm/messenger/keys/DeviceKeys.kt:108–128](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/keys/DeviceKeys.kt#L108-L128).
- **Честность резервной копии:** `KeysDone` прямо предупреждает, что копии на сервере нет и потерянную переписку восстановить нельзя; другие устройства — будущая возможность. Не требую несуществующий backup/recovery. [android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt:368–374](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt#L368-L374).
- **Debug export:** предупреждение об открытом TXT есть; снимок до системного picker, отмена не чистит журнал; `JournalExport.write` возвращает успех после закрытия потока. Release UI — no-op. Это положительные свойства реализации, а не найденные утечки. [android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt:81–97](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt#L81-L97), [android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt:119–133](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt#L119-L133), [android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt:214–224](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/debug/java/dev/sm/messenger/diagnostics/DebugTools.kt#L214-L224), [android/app/src/debug/java/dev/sm/messenger/diagnostics/JournalExport.kt:6–19](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/debug/java/dev/sm/messenger/diagnostics/JournalExport.kt#L6-L19), [android/app/src/release/java/dev/sm/messenger/diagnostics/DebugTools.kt:5–9](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/release/java/dev/sm/messenger/diagnostics/DebugTools.kt#L5-L9).
- **Плановые заглушки:** переименование, оформление, блокировка, конфиденциальность, устройства/восстановление переносом, LoRa, альтернативные пути и полная диагностика с меткой «Скоро» — не дефекты текущего аудита. Их отсутствие не увеличивает число находок. См. [android/app/src/main/java/dev/sm/messenger/MainActivity.kt:303–310](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L303-L310), [android/app/src/main/java/dev/sm/messenger/MainActivity.kt:413–423](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L413-L423) и `wiki/workflows/releases.md`.
- **Рендер:** размеров/контрастов/touch targets/расхождений v14 с Compose без пар изображений не утверждаю. Настоящее заключение не является визуальной приёмкой UI.

## Отдельно: риски физического телефона / проверки, ещё НЕ подтверждённые

1. **Android permission lifecycle:** поворот во время BLE/camera permission request, возврат после permanent denial, grant/revoke в системных настройках. В `AddContactScreen` continuation-лямбды и permission-flags хранятся в `remember`; нужно проверить отсутствие зависшего/утраченного действия. Не записано как доказанный телефонный сбой. [android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:119–163](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L119-L163).
2. **BLE off после выдачи permissions:** на вкладке «Связь» начальный switch сначала просит разрешения; callback вызывает `turnOn()` напрямую. При выключенном адаптере остаётся Toast «Включите Bluetooth в системе» вместо следующего системного enable-dialog; повтор switch уже пойдёт в `askEnable`. Нужен UX-прогон Android 12/13+, без вывода о сломанном GATT. [android/app/src/main/java/dev/sm/messenger/ble/BleScreen.kt:67–92](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ble/BleScreen.kt#L67-L92), [android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt:105–108](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt#L105-L108), [android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt:129–140](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt#L129-L140).
3. **Фоновая работа:** OEM Doze/запрет FGS/убийство процесса, BLE off, сеть Wi-Fi→mobile→airplane→mobile. `BleHub.startService` сообщает об отказе только кратким notice; `BleService.enterForeground` может вернуть false. Проверить, не остаётся ли пользователь с видимым «включено», но без стойкой индикации ограничения фона. Фактическое выполнение в фоне не измерялось. [android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt:163–170](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ble/BleHub.kt#L163-L170), [android/app/src/main/java/dev/sm/messenger/ble/BleService.kt:42–71](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ble/BleService.kt#L42-L71).
4. **Storage/Keystore recovery:** временный отказ Keystore и действительное повреждение ключа оба ведут к Broken. `KeysBroken` предлагает удаление данных Android, не даёт retry и не объясняет отдельно судьбу локальной истории. Нельзя утверждать, что восстановимый ключ реально потерян: проверить recovery после перезапуска и понятность предупреждения до любого разрушительного действия. Принцип не создавать новый ключ молча сохранить. [android/app/src/main/java/dev/sm/messenger/MainActivity.kt:482–496](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L482-L496).
5. **Debug CreateDocument:** поворот/уход со страницы во время записи, storage provider failure, заполненное хранилище, длительный cloud-provider. Проверить корректность busy-state/итога и сохранность журнала; не утверждать, что файл уже теряется. ViewModel сохраняет pending-снимок при picker, но это не заменяет lifecycle-тестов.
6. **Доступность:** малый экран, 200% font scale, TalkBack, клавиатура на поле приглашения и кнопки внизу. `ObFrame` разделяет scroll-content и нижний actions; оценка их видимости требует рендера/IME-теста, не только чтения.

## Порядок исправлений и критерий приёмки

1. S03 и S04 — единая модель состояния: запись принята / сеть доступна / запрос выполняется / почта готова / сбой хранения или пропуска. Согласовать тексты/макет, затем точечная правка и UI-render-check по правилам проекта.
2. S01 и S02 — надёжный первый запуск: защищённый черновик при пересоздании; контекстный запрос уведомлений и видимое ограничение после отказа.
3. S05–S07 — типизированные итоги импорта, никаких сетевых обещаний после одного сохранения.
4. S08 — обновление видимого debug-снимка; повторить исходный пользовательский сбой с логами с обоих телефонов.

Приёмка: предлагаемые регрессионные тесты проходят; рендер до/после в обеих темах для изменённых экранов сопоставлен с согласованным макетом; затем два физических телефона проверяют разрешения, смену сети, фон и ошибки хранения/экспорта. До этого не писать «на телефоне проверено» и не объявлять исходную причину сбоев ссылок/поиска «Рядом» установленной.
