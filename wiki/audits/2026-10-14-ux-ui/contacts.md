# Read-only UX/UI-аудит: добавление контактов и приглашения

Дата: 2026-10-14. Репозиторий: `lineSence/secure-messenger`.
Проверенный SHA: **0ccfbaa80eba483ea6c2cf6b83a6b80026235bae**.

## Итог

**6 статически подтверждённых UX-дефектов: 4 × P2, 2 × P3. P1 в этой области не доказан.** Из них C-06 — ранее документированная проблема диагностики, не новая security-находка. Отдельно перечислены риски, для которых нужны Android/телефоны; они не входят в эти шесть.

Область: «Мой QR», «Сканировать», «Рядом», создание/приём/отзыв приглашения, код из четырёх слов, ошибки, отмена и жизненный цикл. Не проверялись заново криптографическая стойкость, весь транспорт и доставка чата.

P1 — критичный массовый срыв/потеря/компрометация; P2 — сломанный реализованный сценарий или существенно неверное состояние; P3 — ограниченный дефект обратной связи/восстановления. Это приоритет исправления UX, не CVSS.

## База сравнения и ограничения доказательств

- Прочитаны `AGENTS.md`, `wiki/architecture/ux.md`, `wiki/rules/ui.md`, релевантные части `wiki/architecture/android.md`, `requirements.md`, ADR-61/62/83/133/142/145/146/150/151, `wiki/rules/code.md`, `wiki/references/ux-guidelines.md`, `wiki/workflows/releases.md`, `releases/v0.0.1-alpha.md`, пояснения v16, исходник макетов v14/v16 и предыдущий аудит `wiki/audits/2026-10-13/`.
- `git rev-parse HEAD` подтвердил указанный SHA. `git log` подтвердил merge #91 и коммиты `fadc34b` (v16) / `b8cebf2` (секреты вне saved state). Поэтому отсутствие всего v16 не объявлялось дефектом. Статус согласования v16 **не установлен**: wiki всё ещё называет его предложенным, а код уже содержит часть реализации. Сравнение — с фактическим кодом этого SHA и реализуемыми состояниями v16; v14 — прежняя визуальная основа, не утверждение о текущем коде.
- Статически прослежены `AddContactScreen`, `Pairing`, `PairLink`, `QrCode`, `InviteFlow`, `InviteLinks`, связанные участки `MainActivity`, `RelayHub`, `HttpsRelay`, `PairMailboxExchange`; прочитаны существующие тесты обмена адресами и состояния screenshot-стенда.
- **Не запускались** APK, эмулятор, Gradle/JVM-тесты, UI screenshot-стенд или физические телефоны. Нет утверждений о фактическом контрасте, размерах касаний, TalkBack, работе конкретного BLE-адаптера или доставки на телефонах. В каждом пункте ниже «тест» — предлагаемый регрессионный тест, а не выполненный прогон.
- Клон только читался; результат записан вне него в `/data/audit-contacts.md`. Действий записи через GitHub не было.

## Сводка

| ID | Severity | Подтверждённая проблема | Статус относительно старого аудита |
|---|---|---|---|
| C-01 | P2 | Получатель запроса «Рядом» не выходит из подтверждения/ожидания после разрыва | Отдельная UX-цепочка; не повтор старой GATT-ACK находки |
| C-02 | P2 | Ошибка запуска поиска/видимости BLE остаётся Toast, экран продолжает показывать рабочий поиск/QR | Отдельный UX-дефект |
| C-03 | P2 | После сохранения QR/«Рядом»-контакта UI не различает служебный обмен и частичную готовность | Остаточный UX-пробел после #89, не утверждение, что #89 отсутствует |
| C-04 | P2 | Создатель ссылки не узнаёт, что гостевой билет не выдан | Не отсутствие запланированного backend: fallback уже реализован |
| C-05 | P3 | Скрытые завершённые ссылки исчерпывают лимит, ошибка предлагает недоступный отзыв | Лимит допустим; дефект — недостоверное восстановление |
| C-06 | P3 | Локальные ошибки ключей/БД показываются как отсутствие сети | Уже отмечено в ADR-150; повторно подтверждено, не новая security-находка |

## C-01 — P2: «Рядом» зависает после отключения инициатора на принимающем телефоне

**Сценарий.** Оба телефона запустили поиск. A нажимает «Добавить B». На B появляется «Добавить A?». A закрывает экран/разрывает соединение до ответа B. Затем B пытается принять оставшееся предложение.

**Ожидается.** На B предложение снимается или переходит в устойчивое состояние «Телефон отключился» с повторным поиском. Нельзя предлагать принять уже отсутствующего собеседника. Ожидание обмена имеет конечный срок.

**Фактически и почему это доказано.** Входящая карточка запоминается в `askCard`, но `target` получателя не задаётся. Обработчик `onLost` завершает `Waiting/Ask` только при `address == target`; на B это условие ложно. Карточка и экран `Ask` остаются. «Добавить» переводит B в `Waiting`, а отправка в исчезнувший peer выдаёт только `onError`/Toast. Единственный 10-минутный таймер режима Near обрабатывает только `Searching`: для `Ask` и `Waiting` он ничего не делает. В UI Waiting нет собственного действия отмены; выйти можно общей навигацией.

**Точные места:**
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:667–680` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L667-L680]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:700–720` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L700-L720]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:237–263` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L237-L263]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:319–328` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L319-L328]
`android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:218–223` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L218-L223]; `android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:350–371` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L350-L371]

**Исправление.** Хранить участника текущего обмена отдельно от исходящего `target`; при потере `askCard.first`/`accepted.first`/активного `started` закрывать незавершённое предложение, освобождать временную сессию и переходить к доступному повтору. Добавить deadline для Ask/Waiting и явную отмену. Не удалять уже сохранённый контакт на вспомогательном этапе #89.

**Регрессионный тест (не запускался).** Fake PairLink + управляемый main looper: доставить входящие Intro/Card → `Ask`; вызвать `onLost(device)` → проверить, что Ask исчез и есть повтор. Вариант: разрыв между нажатием «Добавить» и отправкой Start → не остаётся Waiting. Отдельно продвинуть часы за `NEARBY_LIFETIME_MS` в Waiting/Ask. На двух телефонах затем проверить реальный callback разрыва; само условие пропуска уже видно по коду.

## C-02 — P2: BLE не запустился, но основной экран продолжает утверждать, что поиск идёт

**Сценарий.** Пользователь нажимает «Искать рядом» или «Подготовить Bluetooth» для QR. Android асинхронно вызывает `onScanFailed` либо `onStartFailure`.

**Ожидается.** Постоянное состояние ошибки с понятным повтором/настройками. Нельзя показывать «Ищем…» после отказа запуска сканера или QR как готовый к обмену при провале видимости. Для частичного отказа нужно честно различать, какая сторона радио работает.

**Фактически.** `PairLink.start` возвращает успех до результата асинхронных callback; `Pairing` устанавливает `Searching`/`Showing`. Оба callback передают лишь `listener.onError`. `Pairing.onError` изменяет только `notice`, которое UI показывает как короткий Toast и сразу очищает. Состояние не становится Failed, поиск не перезапускается. Для пустого списка остаётся «Ищем…» без кнопки повтора; Near уйдёт в Idle лишь по десятиминутному таймеру, QR будет ждать обновления. Утверждение относится к ветке callback ошибки, а не к частоте её появления на конкретном телефоне.

**Точные места:**
`android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:155–163` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L155-L163]
`android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:548–569` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L548-L569]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:723–725` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L723-L725]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:163–176` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L163-L176]; `android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:219–226` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L219-L226]
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:166–171` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L166-L171]; `android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:335–349` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L335-L349]

**Исправление.** Разделить фатальную ошибку запуска и информационное уведомление. Callback запуска должен переводить соответствующий текущий сеанс в Failed/Degraded, останавливать его ресурсы и давать повтор. Привязать callback к поколению сеанса, чтобы поздний отказ старого поиска не ломал новый.

**Регрессионный тест (не запускался).** После успешного возврата `start` инъецировать scan failure и advertising failure. Проверить устойчивый баннер/Failed и доступный retry; не должно оставаться ложного «Ищем…». Повтор из ошибки создаёт один новый сеанс. Поздний callback предыдущего поколения игнорируется. Аппаратный тест нужен для перечня реально встречающихся ошибок, не для доказательства текущей UI-ветки.

## C-03 — P2: сохранённый контакт скрыт за прежним прогрессом, готовность почтового обмена теряется для UI

**Сценарий.** QR или «Рядом» уже сохранил контакт. Дополнительный обмен адресами через тот же PairLink ещё ждёт подтверждения, завершается по тайм-ауту, ошибке узла или разрыву. Пользователь нажимает Назад, считая, что добавление не закончено, либо ждёт итог.

**Ожидается.** После durable save — явно «Контакт сохранён», отдельно «Подготавливаем связь / адреса ещё не получены». При частичном результате объяснить, что уже сохранено, чего не хватает и что не нужно добавлять контакт повторно. Возврат/отмена после сохранения не должны выглядеть как отмена всего добавления. Это согласуется с направлением v16, но находка основана на различимом состоянии, которое уже существует в реализации, а не на требовании реализовать весь прототип.

**Фактически.** #89 действительно сохраняет контакт и запускает `PairMailboxExchange` до 60 секунд, не удаляя контакт при ошибке. Однако `startMailbox` не меняет `PairState`: QR продолжает показывать «Обмениваемся ключами…», Near — «Ждём подтверждения на втором телефоне…». При `MailboxStep.Complete` значение `ready` записывается только в Diagnostics, а `finishMailbox` всегда вызывает одинаковый Done(name, verified). Поэтому UI не различает готовые адреса и timeout/ошибку после сохранения. Success Toast/onAdded появляются только на Done. При уходе во время вспомогательного этапа close/reset сохраняет контакт, но отменяет UI-завершение — сообщение о сохранении не показывается. Повторное добавление этого контакта отвергается Known.

**Точные места:**
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:524–531` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L524-L531]; `android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:654–660` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L654-L660]; `android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:683–689` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L683-L689]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:348–364` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L348-L364]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:384–401` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L384-L401]
`android/app/src/main/java/dev/sm/messenger/contacts/PairMailboxExchange.kt:25–40` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/PairMailboxExchange.kt#L25-L40]; `android/app/src/main/java/dev/sm/messenger/contacts/PairMailboxExchange.kt:51–53` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/PairMailboxExchange.kt#L51-L53]
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:174–179` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L174-L179]; `android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:270–277` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L270-L277]; `android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:350–351` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L350-L351]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:287–317` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L287-L317]; `android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:198–205` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L198-L205]
`design/mockups/ui-v16.html:1193` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/design/mockups/ui-v16.html#L1193]

**Исправление.** Передавать в UI отдельные `SavedPreparing` и результат готовности маршрута (`ready`/pending/error), не привязывать факт добавления к завершению служебной почты. Дать безопасно закончить/продолжить позже. Сохранить фиксы #89: bounded exchange, очередь повторов, проверку Signal и сохранённый контакт. Не заявлять успешную интернет-доставку из одного факта добавления.

**Регрессионный тест (не запускался).** Сохранить контакт → блокировать mailbox ack: UI сразу сообщает «сохранён»; через 60 с результат остаётся частичным, не «адреса готовы». Варианты `prepare=null`, ошибка узла, `Complete(true)` и `Complete(false)` должны различаться для UI. Нажать Back после save → контакт/сессия остаются, нет предложения повторно добавить. Существующие `PairMailboxExchangeTest` проверяют транспортные результаты, но не эту их проекцию в PairState/Compose.

## C-04 — P2: fallback «ссылка без гостевого билета» не отражён у создателя

**Сценарий.** У создателя есть узел и пропуск. Узел принимает NEW, но отклоняет TICKET (например, без гостевого ключа или по лимиту). Создатель отправляет ссылку человеку без своего пропуска.

**Ожидается.** Создать такую ссылку допустимо по ADR-146, но в результате нужно явно предупредить: «Гостевой доступ не включён; получателю потребуется отдельно получить пропуск у организатора». Не выдавать отсутствие билета за гарантированное подключение. Именно это состояние показано и в v16.

**Фактически.** `ticketFor` при Rejected возвращает null; создание успешно продолжается. `MadeLink` содержит только текст и код, без результата выдачи билета. `LinkMadeContent` показывает одинаковый успех и «кто откроет первым — тот и добавится». Получатель без пропуска детерминированно остановится на `NoPass`: `guestPass` сразу возвращает null, если ticket отсутствует. Информация появляется только у получателя после пересылки; создатель её не видел. Это **не** баг самого fallback и не утверждение, что гостевые пропуска ещё не реализованы.

**Точные места:**
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:58–59` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L58-L59]; `android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:166–177` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L166-L177]
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:180–192` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L180-L192]
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:257–268` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L257-L268]; `android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:285–286` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L285-L286]
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:775–801` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L775-L801]
`android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt:70–77` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L70-L77]
`design/mockups/ui-v16.html:1190` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/design/mockups/ui-v16.html#L1190]

**Исправление.** Передавать `hasGuestTicket`/тип доступа в `MadeLink` и лист результата. При отсутствии билета — предупреждение до копирования/отправки с конкретным следующим шагом. Для получателя сохранить точный NoPass и переход к вводу доступа, не предлагать отключать проверку подписи.

**Регрессионный тест (не запускался).** Fake relay: NEW success, TICKET Rejected → ссылка создана, payload без ticket, результат отмечен `requiresSeparateAccess`, UI показывает предупреждение. TICKET success → предупреждения нет. Открыть первый payload в состоянии no-pass → показать NoPass, не обещать успешное добавление. Физическое радио для этого теста не требуется.

## C-05 — P3: лимит заняли скрытые Done-ссылки, а UI предлагает отозвать несуществующие в списке

**Сценарий.** В хранилище восемь своих недавно завершённых приглашений (`Done`). Пользователь пытается создать ещё одно. Завершённые ссылки сохраняются намеренно для чтения очереди; этот дизайн здесь не оспаривается.

**Ожидается.** Лимит должен иметь достоверное объяснение и доступный путь восстановления: например, «Недавно использованные ссылки временно занимают лимит, следующую можно создать после …». Либо отдельно ограничивать новые/активные ссылки, сохраняя завершённые очереди по протоколу.

**Фактически.** `create` проверяет размер всех `links.outs()`; Done входит в лимит 8 и хранится до `expires + 7 суток`. Но `publish` фильтрует Done из `InviteView.outs`. Поэтому список «Действующие ссылки» может быть пустым, а нажатие «Создать» выдаёт Full: «Слишком много ссылок сразу — отзовите ненужные». Отзывать в таком состоянии нечего. Аналогично Started занимает слот, но UI не даёт его отозвать. Нельзя лечить это удалением всех Done: очередь ещё используется контактами.

**Точные места:**
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:150–156` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L150-L156]; `android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:54` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L54]
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:521–527` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L521-L527]
`android/app/src/main/java/dev/sm/messenger/relay/InviteLinks.kt:118–129` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteLinks.kt#L118-L129]; `android/app/src/main/java/dev/sm/messenger/relay/InviteLinks.kt:227–241` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteLinks.kt#L227-L241]
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:576–579` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L576-L579]
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:621–637` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L621-L637]

**Исправление.** Отдельные сообщения/счётчики active/started/retained и срок освобождения. Если нужен новый лимит — пересмотреть его отдельно от retention и не ломать доставку через очередь завершённой ссылки. Если отзыв Started допустим текущим протоколом, согласовать и открыть соответствующее UI-действие.

**Регрессионный тест (не запускался).** Засеять MAX_OUT записей Done → попытка create: сообщение объясняет retention, не предлагает недоступный отзыв. Варианты: 7 Done + 1 Active, все Started. После prune на точной границе срока кнопка/лимит актуализируются. В число находок не входит само наличие квоты.

## C-06 — P3, известное: ошибки локального сохранения показываются как «Нет связи с узлом»

**Сценарий.** При создании/открытии/подтверждении приглашения локальное хранилище бросает SQLiteException либо Keystore — GeneralSecurityException. Интернет при этом доступен.

**Ожидается.** Различать сетевой отказ, повреждение/недоступность хранилища, ошибку защиты ключей; сообщать, какой шаг не завершился и можно ли безопасно повторить. Не советовать ждать сеть, если это не причина.

**Фактически.** `guard` превращает RelayException, SQLiteException, GeneralSecurityException, CorruptRow и SignalFfiException в null. `act` заменяет любой null на `Outcome(LinkProblem.NoNetwork)`; UI показывает один Toast «Нет связи с узлом — попробуйте позже». Внутренние Diagnostics различают причины, но пользовательский экран — нет. Это уже прямо описанная предпосылка ADR-150, а не новая находка об отсутствии debug-журнала: журнал #88 существует.

**Точные места:**
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:461–468` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L461-L468]
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:495–514` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L495-L514]
`android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:49` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L49]; `android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:463–466` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L463-L466]
`wiki/architecture/decisions.md:156` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/architecture/decisions.md#L156]

**Исправление.** Типизированный результат операции (Network/Storage/Keystore/Invalid/Corrupt), постоянная inline-ошибка рядом с текущим шагом, безопасный retry. Не выводить текст исключения, ключи, ссылки и код в журнал/UI. Сохранность полусохранённых шагов — отдельная security/storage работа, не обещание этого UX-исправления.

**Регрессионный тест (не запускался).** Fault injection по каждому виду исключения в create/open/confirm: проверить разный безопасный текст и сохранение применимого ввода, отсутствие секретов. RelayException → только сетевой текст. Проверить соответствующий UI в обеих темах.

## Риски и проверки, требующие Android/телефона — не входят в подтверждённые находки

1. **Граница видимости при Home/блокировке.** Код останавливает Pairing при disposal экрана, а MainActivity.onStop только flush; отдельного обработчика STOP для Pairing не видно. Это доказывает отсутствие явного cleanup на этом пути, но не доказывает, как долго конкретный Android реально продолжит BLE-объявления. Проверить: начать Near/QR → Home/экран блокировки → со второго телефона сканировать до/после timeout → вернуться. Если продолжается, предупреждение «пока открыт этот экран» требует либо lifecycle-stop, либо точного текста и явного управления видимостью. Не объявляю аппаратное воспроизведение выполненным.
`android/app/src/main/java/dev/sm/messenger/MainActivity.kt:108–116` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L108-L116]; `android/app/src/main/java/dev/sm/messenger/MainActivity.kt:228–232` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L228-L232]
`android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt:287–299` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/Pairing.kt#L287-L299]; `android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:172–185` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L172-L185]
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:379–388` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L379-L388]

2. **Камера, поздний provider callback, privacy toggle и отозванные разрешения.** В QrScanner callback вызывает get/bindToLifecycle без отдельного UI-исхода ошибки; dispose проверяет только isDone. Полный сбой/утечка/краш не воспроизведены. Проверить: открыть сканер и сразу выйти, повернуть устройство, отключить камеру системным переключателем, отозвать разрешение в Android и вернуться; ожидать освобождение камеры, безопасную ошибку и повтор. Не путать с исправленной в #91 цепочкой начального запроса Camera/BLE.
`android/app/src/main/java/dev/sm/messenger/contacts/QrCode.kt:78–108` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/QrCode.kt#L78-L108]

3. **Потеря UI-ввода при recreation.** `firstInvite`, локальный текст и код теперь намеренно в `remember`, не `rememberSaveable` (#91/b8cebf2). Не записывать секреты в Bundle — правильно; отсутствие plaintext saved state не дефект. На телефоне проверить поворот/process death во время ввода кода, объяснимое восстановление/повторную вставку и сохранность уже durable приглашения. Компромисс решать через безопасный владелец состояния/пояснение, не возврат секретов в Bundle.
`android/app/src/main/java/dev/sm/messenger/MainActivity.kt:128–133` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L128-L133]
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:506–515` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L506-L515]

4. **Крупный шрифт/TalkBack и узкие вкладки.** По четырём одинаковым весам и maxLines=1 нельзя честно утверждать фактическое обрезание/размер цели без рендера. Нужны 200% font scale, 360 dp, обе темы, TalkBack, IME поверх ввода четырёх слов. Попарная визуальная сверка по UI-RENDER-CHECK в этом аудите не выполнялась; дефекты «на глаз» не добавлены.
`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:205–224` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L205-L224]

## Дедупликация и что намеренно не названо багом

- Предыдущий аудит проверял другой SHA. Его AUD-05 (неаутентифицированный Decline), AUD-07 (prekeys), AUD-08 (Signal/SQLite atomicity), AUD-09 (GATT ACK до semantic save) не пересчитаны как новые UX-находки. C-01 — другой пропущенный переход на принимающем телефоне, C-03 — отображение результата уже существующего mailbox-этапа. Статусы закрытия прежних security-находок не выводились из одного текста старого отчёта.
`wiki/audits/2026-10-13/report.md:29–48` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/audits/2026-10-13/report.md#L29-L48]
- Учтён #89 / ADR-151: служебные адреса теперь действительно передаются по PairLink, обмен ограничен 60 с, ошибка после save не стирает контакт, BLE-метка не блокирует HTTPS, Idle узла не называется доказательством работающей сети. Не утверждаю обратное. Часть проверки — чтение существующих тестов, не их прогон.
`wiki/architecture/decisions.md:157` [^https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/architecture/decisions.md#L157]
- Учтён #91: подготовка QR по явному действию, последовательный запрос разрешений, редактируемый черновик приглашения и отсутствие секретов в saved state. Не воспроизвожу как актуальные старые проблемы автоматического открытия камеры/потери первого запроса.
- QR-сверка уже добавленного контакта, второе устройство, поиск, вложения, звонки и прочие помеченные «Скоро» не объявлены багами только потому, что пока не реализованы. Показ четырёх слов только один раз и передача ссылки без кода в Android share соответствуют принятому сценарию; отдельного бага на этом основании нет.
- Не заявляю по этому аудиту причину конкретных прежних отказов владельца «не работает ссылка»/«пусто Рядом»: для их причин нужны события с обоих телефонов.

## Рекомендуемый порядок

1. C-01/C-02: явные завершающие состояния и доступное восстановление Near/QR.
2. C-03/C-04: показывать уже известный частичный результат — сохранение контакта отдельно от маршрута; наличие гостевого билета отдельно от текста ссылки.
3. C-05/C-06: честные ограничения и ошибки с доступными следующими шагами.
4. После изменения состояний — unit/fault tests, рендер пар v16/Compose в обеих темах, затем два Android-телефона. Сам репозиторий в рамках аудита не изменять.
