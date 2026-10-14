# Read-only UI-аудит Uncensored

**Репозиторий:** lineSence/secure-messenger
**SHA:** `0ccfbaa80eba483ea6c2cf6b83a6b80026235bae`
**Дата:** 2026-10-14
**Итог:** P1 — 0 доказанных; P2 — 5; P3 — 2. Это статический аудит с локальным рендером HTML-макета, **не** выполненная визуальная приёмка Android-приложения.

## Основание и метод

Прочитаны AGENTS.md, wiki/architecture/ux.md, wiki/rules/ui.md через GitHub MCP, также через MCP получены v14 и tools/ui-shots/run.sh. После передачи координатором локального клона остальные исходники читались read-only из `/data/secure-messenger-audit`; `git rev-parse HEAD` совпал с SHA выше, `git status --porcelain` пуст до и после чтения. Исходный клон, GitHub, Notion не изменялись. Все вспомогательные файлы — `/data/audit-visual-work`; этот отчёт — `/data/audit-visual.md`.

**Эталон — v14**, не максимальный номер файла: [`wiki/architecture/ux.md:21–23`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/architecture/ux.md#L21-L23). v15 (звонки) и v16 (новый первый запуск) предложены, не приняты. Для состояния конкретного SHA это важнее устаревших заголовков внутри накопительного HTML.

Приоритеты: P1 — блокирующий/критический; P2 — функционально значимый дефект доступности или адаптивности; P3 — недостаток семантики или защиты от регрессий. «Доказано» ниже означает, что свойство установлено исходником/расчётом, а не что проведён TalkBack или инструментальный Android-прогон. Условия и границы доказательства указаны отдельно.

### Что реально запускалось

- Изолированный Playwright + установленный Chromium, **без shared browser**, на копии `ui-v14.html`; светлый/тёмный чат Ани и светлый лист созданной ссылки. Успешный рендер, три PNG; светлый чат и лист просмотрены. Скрипт: `/data/audit-visual-work/render.cjs`.
- Артефакты: `mock-v14-chat-light.png`, `mock-v14-chat-dark.png`, `mock-v14-linkmade-light.png` в том же каталоге. Это **только макет**, не приложение. Размер DOM `#scr` — 368×778 CSS px, включая нарисованную HTML статус-полосу; для корректной пары нужно согласовать crop/status/nav insets с Android, а не сравнивать внешний корпус телефона.
- Расчёт контраста из точных токенов sRGB, без извлечения цветов из сглаженных букв.
- Android/Robolectric/Roborazzi **не запускались**: в окружении обнаружен Java 25, отсутствовали команды Gradle/adb/rustc/protoc в PATH и переменные JAVA17_HOME/GRADLE/ANDROID_SDK_ROOT/PROTOC. Это проверка доступности, не доказательство отсутствия SDK во всех возможных каталогах. Не скачивались SDK/зависимости, не производилась сборка. Требования стенда — [`tools/ui-shots/run.sh:1–16`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/tools/ui-shots/run.sh#L1-L16). Координатор дополнительно подтвердил отсутствие SDK/Gradle/Rust и сообщил, что его штатный run.sh остановился на JAVA17_HOME; это не Android-рендер.

## Доказанные замечания

### UI-01 · P2 · Переключатели не публикуют checked-state и роль Switch

**Доказательство:** [`android/app/src/main/java/dev/sm/messenger/ui/Components.kt:376–389`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ui/Components.kt#L376-L389); Bluetooth — [`android/app/src/main/java/dev/sm/messenger/ble/BleScreen.kt:143–150`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ble/BleScreen.kt#L143-L150); защита ссылки — [`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:682–705`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L682-L705).

**Условия:** TalkBack/Switch Access, экран «Связь» → Bluetooth или «Новая ссылка» → «Защитить кодом», любое состояние и тема.

**Actual:** SmSwitch — Box + clickable; checked определяет только цвет и сдвиг кружка. Нет toggleable/Role.Switch/ToggleableState/stateDescription. У Bluetooth есть contentDescription, но нет состояния на узле управления; соседний текст «включено/выключено» не заменяет checked-state этого узла. У переключателя ссылки нет собственного имени, родительская строка и внутренний переключатель оба clickable (раздельные действия/фокусы).

**Expected:** один логический озвучиваемый переключатель с именем, ролью и состоянием; действие меняет состояние и отражает его в semantics.

**Рекомендация:** toggleable(checked, role=Role.Switch) на всей строке, визуальный thumb без отдельного click; либо доступный Switch с оформлением макета. Не добавлять дублирующий contentDescription поверх уже правильно подписанной строки.

**Тесты:** assertIsToggleable/assertIsOn/assertIsOff, проверка имени; одна фокусируемая цель на строку; ручной TalkBack ON→OFF→ON. Визуальный track можно оставить 44×26, внешнюю цель обеспечить ≥48×48 dp. Сам размер 44×26 **не** выдаётся здесь за доказанный размер фактической touch-hit области: Compose может расширять hit bounds.

### UI-02 · P2 · Значок доставки сообщения скрыт от accessibility

**Доказательство:** [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:568–589`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L568-L589), [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:618–643`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L618-L643), [`android/app/src/main/java/dev/sm/messenger/chat/MessagePathLabel.kt:3–8`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/MessagePathLabel.kt#L3-L8).

**Условия:** своё сообщение через узел, возраст менее 10 минут, сравнить Queued+onNode=true и Delivered+via=Node при одинаковом at.

**Actual:** обе подписи — одно и то же время + «Интернет»; значки Check и Delivered — единственный различитель «на узле» и «доставлено». Bubble рисует Icon с contentDescription=null, семантического описания состояния на пузыре/метаданных нет. Для невизуального пользователя состояния неразличимы без открытия отдельного листа. В списке чатов аналогичные значки, напротив, имеют description (ChatScreens.kt:350–355).

**Expected:** актуальный шаг доставки доступен при фокусе на сообщении/метаданных; понятное действие «Сведения о сообщении», не только безымянное действие на время.

**Рекомендация:** семантический label/stateDescription: «На телефоне, ждёт связи» / «На узле, ждёт получателя» / «Доставлено» / «Отправить не удалось», плюс onClickLabel для сведений. Не добавлять «прочитано».

**Тесты:** три шага + failed, Bluetooth без промежуточного шага; проверить различие semantics при одном timestamp, одну озвучиваемую подпись без дублей. Отсутствие описания доказано статически; точная фраза TalkBack на устройстве не проверялась.

### UI-03 · P2 · Контраст мелкого текста светлой темы ниже установленного порога

**Доказательство:** цвета — [`android/app/src/main/java/dev/sm/messenger/ui/Theme.kt:70–79`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ui/Theme.kt#L70-L79); применение 11 sp метаданных — [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:618–643`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L618-L643); кнопки 14 sp — [`android/app/src/main/java/dev/sm/messenger/ui/Components.kt:132–151`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ui/Components.kt#L132-L151); собственный guideline ≥4.5:1 — [`wiki/references/ux-guidelines.md:10–13`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/references/ux-guidelines.md#L10-L13).

**Условия:** светлая тема, обычный масштаб, активные primary-кнопки и метаданные сообщений.

| Текст / фон | sRGB | Контраст | Требуется для небольшого текста |
|---|---|---:|---:|
| Исходящие время/канал / пузырь | #4FAE4E / #E1F8CF | 2.472:1 | 4.5:1 |
| Входящее время / пузырь | #8A939E / #FFFFFF | 3.112:1 | 4.5:1 |
| Текст primary-кнопки / акцент | #FFFFFF / #2F8AE5 | 3.567:1 | 4.5:1 |

**Actual/expected:** малый текст не проходит принятый в проекте accessibility-порог; должен проходить ≥4.5:1. Измерение вычислительное, точные цвета взяты из SHA. Для сопоставления тёмные исходящие метаданные #9FC6EC/#2B5278 дают 4.558:1; это не утверждение, что вся тёмная тема проверена.

**Важно:** цвета **совпадают с v14** ([`design/mockups/ui-v14.html:265–266`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/design/mockups/ui-v14.html#L265-L266)). Это унаследованный дефект доступности согласованной палитры, **не** отклонение реализации от макета и не требование самовольно заменить дизайн.

**Рекомендация:** согласовать более тёмный цвет малых метаданных и пару акцент/текст; синхронно обновить макет и тему. Disabled-кнопки и сглаженные края букв в расчёт не включены.

**Тесты:** автоматический тест контрастных пар обеих тем, включая вторичный текст, warning/error и фон после alpha-composite; снимки чата/кнопок; проверка на устройстве. Формула воспроизводится скриптом `contrast.py` в каталоге аудита.

### UI-04 · P2 · Лист удаления не прокручивается при нехватке высоты

**Доказательство:** обёртка — [`android/app/src/main/java/dev/sm/messenger/ui/Components.kt:341–354`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ui/Components.kt#L341-L354); тело и кнопки — [`android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt:784–827`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/chat/ChatScreens.kt#L784-L827).

**Условия:** низкое окно, например landscape с доступной высотой около 360 dp; увеличение fontScale дополнительно ухудшает ситуацию. То же при multi-window с высотой меньше тела листа.

**Actual:** ModalBottomSheet содержит обычную Column без verticalScroll/LazyColumn; весь длинный текст и обе кнопки идут подряд. Сам ModalBottomSheet обеспечивает перетаскивание листа, но не прокрутку тела Column. При измерении в ограниченную высоту поздние дети сжимаются/не помещаются; прокрутить текст к концу и получить нормальные кнопки нельзя. Это структурное свойство дерева, не результат Android-снимка.

Минимальная запрошенная высота уже **≥418 dp** при fontScale=1, даже если каждый пункт умещается в одну строку: handle 26 + title 26 + muted 34 + отступ 4 + два подзаголовка 36 + отступы двух Bullets 24 + шесть пунктов (18+8)×6=156 + кнопки 96 + низ 16. Навигационные insets и переносы увеличивают её. Следовательно при доступных 360 dp контент заведомо не помещается.

**Expected:** предупреждение полностью читается; подтверждение и отмена достижимы прокруткой либо вынесены в постоянную нижнюю область; крупный текст не лишает пользователя действий.

**Рекомендация:** ограничить высоту и сделать тело scrollable, предпочтительно отделить footer действий. Проверять реальный SmSheet, не только SheetOver.

**Тесты:** 360×640 portrait, landscape высота 360, split-window, fontScale 1/1.3/2; performScrollTo последнего предупреждения и обеих кнопок, assertIsDisplayed, открыть/закрыть/повернуть. Содержание длинного имени также не должно вытеснять действия.

### UI-05 · P2 · Однострочные фиксированные кнопки не адаптируют длинные подписи

**Доказательство:** общий SmButton — [`android/app/src/main/java/dev/sm/messenger/ui/Components.kt:143–151`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ui/Components.kt#L143-L151); длинная полноширинная кнопка — [`android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt:241–248`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt#L241-L248); две кнопки без весов/переноса — [`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:799–802`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L799-L802).

**Условия:** узкое окно 320 dp и крупный текст (например, fontScale=2); первый запуск → «Как перейти из другого мессенджера», либо лист созданной ссылки → «Поделиться ссылкой» + «Копировать».

**Actual:** внешний clip + фиксированная высота 44/36 dp, текст maxLines=1, нет adaptive/FlowRow/вертикальной раскладки. Для подписи, ширина которой превышает доступную, неизбежна потеря видимого текста вместо переноса; у листа первый unweighted button также расходует место второго. Не утверждается, что Android-снимком установлена точная граница обрезки на 368 dp.

**Expected:** полные подписи и достижимые действия при увеличенном системном тексте. HTML v14 допускает перенос подписи «Поделиться ссылкой»: это наблюдалось в локальном рендере при ширине #scr=368 (кнопки приблизительно 185 и 143 px). Compose запрещает такой перенос явно.

**Рекомендация:** heightIn(min=...), multiline label с достаточными вертикальными полями; для пары действий адаптивная Column/FlowRow либо согласованные веса и перенос. Это отдельный layout-дефект от переполнения длинного тела листа UI-04.

**Тесты:** минимальная ширина 320/360/368, fontScale 1/1.3/2; assert text layout hasVisualOverflow=false, обе подписи/действия видны, семантический и физический размер цели ≥48 dp. Проверить не только короткие демо-имена/тексты.

### UI-06 · P3 · Выбранная вкладка обозначена только визуально

**Доказательство:** нижняя навигация — [`android/app/src/main/java/dev/sm/messenger/ui/Components.kt:406–425`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ui/Components.kt#L406-L425); вкладки контакта — [`android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt:205–224`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L205-L224).

**Условия:** навигация с TalkBack/Switch Access по «Чаты · Связь · Настройки», а также «Мой QR · Сканировать · Рядом · Ссылка».

**Actual:** selected влияет только на цвет/фон/жирность/линию; clickable не получает selected и роль Tab. Программно текущая вкладка не обозначена.

**Expected:** роль и выбранное состояние доступны вспомогательным технологиям.

**Рекомендация:** selectableGroup + selectable(selected, role=Role.Tab), либо штатные NavigationBarItem/Tab со стилем макета.

**Тесты:** assertIsSelected/assertIsNotSelected для всех вкладок после клика, корректный Back, TalkBack озвучивает выбранную вкладку. Не оценивалось точное озвучивание позиции в коллекции.

### UI-07 · P3 · ui-shots — запись примеров, не проверка UI-регрессий

**Доказательство:** [`tools/ui-shots/lib/src/test/kotlin/Screens.kt:93–102`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/tools/ui-shots/lib/src/test/kotlin/Screens.kt#L93-L102), [`tools/ui-shots/lib/build.gradle.kts:31–37`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/tools/ui-shots/lib/build.gradle.kts#L31-L37), [`android/app/src/main/java/dev/sm/messenger/ui/Components.kt:357–366`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/ui/Components.kt#L357-L366), [`tools/ui-shots/run.sh:17–20`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/tools/ui-shots/run.sh#L17-L20).

**Условия:** разработчик запускает штатный tools/ui-shots/run.sh после изменения UI.

**Actual:** 52 @Test-метода в базовом классе × две темы = 104 тестовых сценария (это подсчёт объявлений, **не** отчёт о выполнении). Стенд принудительно record=true, out перед прогоном удаляется; эталонных сравнений/порогов расхождения и accessibility/layout assertions нет. Размер один — w368dp-h754dp-xhdpi, SDK35; нет matrix fontScale/ориентации/IME. Листы снимаются через SheetOver, а не реальный ModalBottomSheet — оконные insets, anchors и поведение клавиатуры этим не проверяются. Плавающие System.currentTimeMillis/Calendar и оставленные анимации также затрудняют будущий deterministic diff.

**Expected:** не называть успешную запись PNG проверкой соответствия; regression-gate должен падать на проверяемом дефекте и покрывать критические конфигурации.

**Рекомендация:** сохранить ручной record-режим отдельно; добавить reviewed baselines и verify-режим, фиксированные часы/locale/timezone, контролируемые анимации; минимальную matrix и assertions semantics/overflow; интеграционный сценарий реальных листов и IME. Не заменять Android эталоны HTML-пикселями без нормализации.

**Тесты защиты:** намеренно сместить отступ/цвет в тестовой ветке (не в этом аудите) и убедиться, что verify падает; проверить что fontScale=2 и открытая IME действительно применяются. Стенд в текущей форме полезен для ручного сопоставления картинок, а не бесполезен.

## Покрытие и пробелы

| Область | Что установлено | Что нельзя считать проверенным |
|---|---|---|
| Согласованный дизайн | v14, базовые токены/размеры и структура сопоставлены с кодом; HTML действительно отрисован | Пиксельная эквивалентность Android, тени и шрифты ОС |
| Темы | SmTheme следует isSystemInDarkTheme, обе явные темы есть в ui-shots; базовые палитры повторяют v14 | Живое переключение системы, все alpha-сочетания |
| Типографика | sp используется, базовые 15/14/13/12/11 и заголовок20 отражают макет; UI-03/UI-05 | Реальный Roboto/line metrics на устройстве, все экраны 200% |
| Размеры окна | Одиночный стенд368×754; доказан недостаток адаптации листа/кнопок | Tablet/foldable, split-screen, landscape, cutout |
| Клавиатура/insets | В ChatContent есть navigationBarsPadding+imePadding (475); AddFrame и ObFrame имеют navigationBarsPadding, без imePadding; SmSheet задаёт contentWindowInsets=WindowInsets(0) | Нельзя без runtime объявить конкретное перекрытие IME или двойной inset: поведение зависит от окна/платформы. Нужен отдельный тест, не новый доказанный P2 |
| Semantics | UI-01/UI-02/UI-06; SmIconButton имеет подписи у основных действий | Полный TalkBack traversal, keyboard focus, live regions, доступность камеры |
| Поля ввода | BasicTextField с отдельным placeholder, часто без постоянной семантической метки: AddContactScreen.kt:604–618, Onboarding.kt:269–303 | Точное дерево semantics и label после заполнения не выгружались. Проверить label/error на editable-узле; не записано как доказанный user-visible дефект |
| Touch targets | IconButton48, FAB56, rows≥60/72; кастомные button44/36 и switch44×26 требуют контроля внешней цели | Малый нарисованный размер ≠ малый hit target в Compose. Не доказано массовое нарушение 48 dp только grep по size; нужны bounds/тест клика снаружи визуальной границы |
| ui-shots | Есть чаты/пустой/нет связи, удаление+busy, QR/scan/near, ссылки+код+ошибка, settings/debug, profile/privacy, node и часть onboarding в обеих темах | Не покрыты реальная IME, реальные sheets, разные viewport/fontScale; нет всей matrix on-node/long-wait/failed и runtime разрешений/камеры |

### Минимальный следующий прогон, если toolchain доступен

1. Рабочая копия SHA в отдельном каталоге, ничего не генерировать в исходном клоне; штатный run.sh очищает build-in/out.
2. Сначала неизменённые 104 сценария, затем отдельная тестовая matrix 320×640, 368×754, landscape, fontScale 1/1.3/2, обе темы.
3. Реальный Activity + реальные SmSheet: фокус поля, IME show/hide, последнее сообщение и composer, ввод ссылки/кода, NameStep/InvitationEntry, системные полосы (жесты/3-кнопки), поворот.
4. TalkBack: переключатели, три шага доставки, выбранные вкладки, заполненные поля, ошибки; layout assertions для всех кнопок/конца предупреждения.
5. Пары «v14 | Android» одинакового состояния и полезной области; никаких заявлений «на телефоне проверено», пока не было телефона.

## Осознанные отличия и статус дизайна — не смешаны с дефектами

- «Скоро» у групп/папок/вложений/реакций/настроек оформления и других запланированных возможностей — часть выбранного релизного подхода v10→v14, не автоматически UI-дефект.
- Реальные имена/счётчики/даты/состояния узла могут отличаться от демонстрационных. Правило ui.md уже фиксирует более компактные отступы/типографику после #88/#89; без пар Android-картинок новый pixel-diff дефект не заявляется. Источник: [`wiki/rules/ui.md:14–17`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/wiki/rules/ui.md#L14-L17).
- Текущий код **уже содержит элементы предложенного v16**: Welcome с тремя ролями, InvitationEntry, комментарии firstRoute/firstInvite в MainActivity. Это подтверждается [`android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt:224–280`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/onboarding/Onboarding.kt#L224-L280) и [`android/app/src/main/java/dev/sm/messenger/MainActivity.kt:135–154`](https://github.com/lineSence/secure-messenger/blob/0ccfbaa80eba483ea6c2cf6b83a6b80026235bae/android/app/src/main/java/dev/sm/messenger/MainActivity.kt#L135-L154), хотя таблица ux.md говорит «код приложения не изменён». Координатор в ходе аудита подтвердил: main уже реализует v16 через PR91, wiki устарела. Это **осознанное изменение и устаревшая документация**, а не доказанный функциональный дефект нового потока; автоматический откат к v14 не рекомендуется. База визуальной сверки по заданию остаётся v14, но осознанные изменения онбординга исключены из списка отклонений. В рейтинг P1/P2/P3 это расхождение не включено; сведения о PR91 получены от координатора, сам PR в этом проходе не загружался.
- Контраст UI-03 унаследован от v14, но доступность имеет собственный явный критерий; это отдельно обозначено, а не выдано за ошибку переноса макета.

## Ограничение заключения

Доказанные пункты основаны на конкретном SHA и не требуют предположений о работе криптографии/сети. Android-приложение, TalkBack и инструментарий ui-shots здесь не выполнялись. Поэтому отсутствуют доказанные P1, утверждения о фактических IME-перекрытиях, полный список пиксельных расхождений и заключение «UI соответствует макету». Никаких изменений в репозиторий не внесено.
