> Приложение: исходный отчёт отдельного аудитора. Итоговые уровни, дедупликация, статус сессий и область применимости определены в [сводке](report.md). Локальные `/data/...` — пути временного окружения, не файлы репозитория.

# Независимый платформенный security audit secure-messenger

**Репозиторий:** `lineSence/secure-messenger`  
**Единственный проверяемый commit:** `c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`  
**Дата:** 2026-10-13  
**Область:** Android Manifest/components/backups/network security/deeplinks; Keystore, секреты, логи, буферы; граница UniFFI Rust–Kotlin; CI, подпись релиза, dependency verification и supply chain.

[Проверенный commit](https://github.com/lineSence/secure-messenger/commit/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68).

## 1. Итог

В просмотренных путях **не подтверждена новая удалённая critical/high-уязвимость release-приложения**. Это ограниченный вывод по проверенному коду, не сертификат безопасности, не вердикт выпуска и не доказательство отсутствия ошибок.

| ID | Severity | Статус | Классификация |
|---|---|---|---|
| PLAT-1 | **High, условно и только для debug** | **Доказано по коду**; возможность подписи синтетического содержимого воспроизведена | Опубликованный закрытый debug signing key допускает подделку обновления debug-приложения. Нужна установка такого APK пользователем/ADB. Release не затронут |
| PLAT-2 | **Low** | **Доказано по коду** для отсутствия очистки конкретных копий; извлечение действительного секрета из остаточной памяти — гипотеза | Неполная гигиена секретов/открытых данных при копировании Rust ↔ Kotlin и в direct ByteBuffer. Самостоятельная удалённая цепочка не показана |

Отдельно описаны **не находки-уязвимости**, а ограничения защиты: ручной release gate, отсутствие защиты экранов от снимков, ожидаемая доступность Keystore после первой разблокировки, частичное доверие аудитам зависимостей, чтение снимков очереди без предварительного ограничения размера. Ни одному из них не присвоен high только за отсутствие hardening.

**Не изменялись GitHub, Notion, release verdicts или исходники проекта. Субагенты не запускались. Живые узлы/транспортные сервисы не проверялись. Содержимое секретов не выводилось.**

## 2. Происхождение материалов и методика

### Фиксация исходников

Через GitHub MCP выполнены read-only `get_commit` и `get_file_contents` с полным SHA, а не с `main`. Commit существует; MCP вернул именно запрошенный SHA. Сообщение коммита: документационное решение ADR-151. Не утверждаю, что `main` оставался на этом SHA во время всего аудита: актуальную ветку повторно не опрашивал.

Для экономии контекста скачан GitHub codeload tarball **этого SHA**. Снимок разложен только в собственный каталог `/data/audit-platform/src`; исходный архив — `/data/audit-platform/source.tar.gz`. Корень архива содержал полный заданный SHA.

SHA-256 скачанного архива:

```
d46e31e7ce5eaad16668d5dc49d52bdcff76ffdc70760338f147aec7dc99f312
```

Git blob SHA-1 локального `AGENTS.md` совпал с SHA файла, возвращённым GitHub MCP:

```
a9f7a9d01718af90001c67ecec6bbb2f0b9cfd75
```

SHA-256 архива здесь — идентификатор реально использованных байтов, не отдельная внешняя подпись GitHub. Полное побайтовое сравнение каждого файла с API GitHub не выполнялось.

### Правила и независимость

Прочитаны `AGENTS.md`, `CLAUDE.md`, профильные архитектурные документы Android/core/Signal, модель требований, правила кода, документы CI и выпуска, релевантные строки ADR-46/56/63/72/76/81/132/142/147/150. После основной проверки отдельно прочитаны инструкция ревью, выбранные профильные сведения и сводка прежних находок в `releases/v0.0.1-alpha.md`. Сохранены локальные заметки `independent-notes.md` до этой отдельной сверки.

**Важная оговорка о порядке:** запрошенные профильные документы сами содержат сводки прошлых ревью; при чтении `wiki/workflows/releases.md` до завершения всего независимого прохода стали видны такие сводки. Есть также ссылки на прежние ревью в комментариях исходников. Поэтому требование о полностью слепом проходе выполнено **не идеально**; я не называю этот аудит полностью blind. Отдельные прежние JSON-вердикты с находками не читались до прохода; у release JSON позднее проверен лишь тип корня, без использования находок. Выводы ниже опираются на текущие исходники и локальные проверки, а не на прежний severity.

Инструкция проектного PR-ревью требует JSON и запись verdict в GitHub; это другой workflow. По явному заданию пользователя здесь используется русский отчёт, только read-only аудит, без записи verdict и без ограничения лишь diff.

### Модель угроз для оценки

Различаются:

1. внешний сетевой противник/чужое Android-приложение без дополнительных полномочий;
2. противник, убедивший установить APK с совпадающей подписью;
3. противник с чтением памяти процесса/root/доступом к приватным файлам;
4. субъект с правом тегировать репозиторий или изменять release workflow;
5. компрометация upstream-поставщика/сборочного инструмента.

Не считаю наличие возможностей из пункта 3 или 4 автоматически доказанной цепочкой для пункта 1. R-4 откладывает защиту при изъятии телефона; ADR-56 намеренно разрешает фоновую работу Keystore без повторной аутентификации. Это учтено в severity.

## 3. PLAT-1 — Подделка обновления debug с опубликованным signing key

**Severity:** High по возможному влиянию на реальные данные **debug**; требует дополнительного действия установки, не удалённая автоматическая атака.  
**Статус:** доказано по коду. Воспроизведена только возможность подписать произвольный синтетический payload и проверить подпись. Полная установка обновления APK на Android не воспроизводилась.  
**Уверенность:** высокая для доступности закрытого signing key и конфигурации приложения; высокая для условной цепочки обновления, но устройство/PackageManager в этом аудите не тестировались.

### Точные места

- `android/app/debug-signing.p12.b64:1` — хранилище закрытого debug signing key, доступное в исходниках. Его содержимое в отчёте не приводится. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/debug-signing.p12.b64#L1).
- `android/app/build.gradle.kts:13–29,60–74` — декодирование хранилища, параметры доступа и использование для debug; отдельный пакет `.debug`. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/build.gradle.kts#L60-L75).
- `android/app/build.gradle.kts:32–39` — debug versionCode из номера CI; это порядок обновлений, а не аутентификация происхождения. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/build.gradle.kts#L32-L39).
- `android/app/src/main/java/dev/sm/messenger/store/SnapshotCipher.kt:32–60` — доступ приложения к ключам своего Keystore без запроса аутентификации. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/SnapshotCipher.kt#L32-L60).
- `android/app/src/main/java/dev/sm/messenger/keys/DeviceKeys.kt:20–35,103–114` — раскрытие собственной записи identity через Keystore при загрузке. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/keys/DeviceKeys.kt#L20-L35).

### Условия и цепочка атаки

1. На телефоне установлена текущая debug-сборка `app.uncensored.debug`, содержащая реальные контакты/переписку/ключи.
2. Противник получает опубликованное хранилище из репозитория и использует параметры, уже присутствующие в сборочном файле.
3. Готовит APK с тем же applicationId, совпадающей подписью и подходящим versionCode. Знать фактический номер CI не является секретом; рост versionCode не заменяет секретный ключ подписи.
4. Пользователь принимает APK как «новую debug-сборку» либо устанавливает его через ADB. Без этого действия установка не происходит; чужое обычное приложение не получает sandbox автоматически.
5. Замещающий код работает от имени того же пакета/UID и получает доступ к сохранённым данным и Keystore этого приложения. Шифрование файлов не отделяет данные от кода легитимно установленного обновления.

### Влияние

Чтение/изменение переписки, использование собственных Keystore-ключей для расшифровки базы и identity, отправка сообщений от имени пользователя, изменение контактов и транспортных настроек **в debug**. Может быть затронута не только тестовая функциональность: debug использует те же main-source хранилища и протоколы.

**Граница:** release имеет другой applicationId и подписывается другим секретным ключом. Публичный debug key не позволяет подписать обновление `app.uncensored` и не доказывает утечку release signing key. См. `build.gradle.kts:60–75` и `release.yml:81–123`.

### Безопасный локальный PoC

`tests/DebugSignerProbe.java` загружает опубликованное PKCS#12 непосредственно из Base64, получает private-key entry, подписывает только вымышленную строку `AUDIT SYNTHETIC NON-APK PAYLOAD`, проверяет подпись публичной частью и выводит лишь PASS. Ключ, сертификат, подпись и пароли не печатаются; APK не создаётся, устройство не используется.

Запуск:

```
cd /data/audit-platform/src
java /data/audit-platform/tests/DebugSignerProbe.java android/app/debug-signing.p12.b64
```

Результат: `PASS: repository debug private-key entry signs arbitrary synthetic payload; signature verifies. No APK/install test performed.`

Это воспроизведение доступности signing capability, **не** полный Android exploit. Для полной проверки в отдельной изолированной лаборатории: только тестовые данные, benign APK-обновление того же debug-пакета с более высоким versionCode, проверка сохранения тестовой строки/доступа к тестовому alias; затем очистка устройства. Здесь такой тест не запускался.

### Исправление

- Если в debug допускаются реальные данные — хранить постоянный **закрытый** test signing key вне репозитория, подписывать отдельным доверенным шагом/у владельца; public certificate можно публиковать.
- Уже опубликованный ключ нельзя вновь сделать секретным удалением файла. Нужны новый закрытый ключ и миграционная стратегия/чистая переустановка debug; не обещать обычное обновление старой подписи новым ключом без анализа Android signing lineage.
- Либо сохранить публичный debug key как намеренную тестовую модель, но использовать исключительно одноразовые лабораторные контакты/данные и ясно предупреждать: подпись debug не удостоверяет происхождение APK.
- Номер запуска CI и проверка размера APK не являются исправлением.

### Сверка с проектом

ADR-147 уже прямо предупреждает, что этим ключом любой может подписать APK debug. В `decisions.md:153` ADR помечен **«предложено»**, хотя реализация присутствует. Это не скрытая новая утечка, а **действующая, документированная граница доверия**. Аудит оценивает последствия применения такой схемы к реальным сообщениям; high не относится к release и не является вердиктом о выпуске.

## 4. PLAT-2 — Незатираемые дополнительные копии секретов

**Severity:** Low, defense-in-depth / гигиена памяти.  
**Статус:** доказано по коду для конкретных обычных буферов и отсутствия cleanup; чтение действительного секрета после освобождения — гипотеза, требующая отдельного теста.  
**Уверенность:** высокая для наличия копий; средняя для времени их остаточной доступности в конкретном ART/native allocator; удалённая эксплуатация не установлена.

### Точные места и факты

1. `crates/sm-signal/src/identity.rs:53–59`: `IdentityKeyPair::serialize()` даёт временный `pair`, затем bytes копируются в другой `Vec`; временный результат сериализации в этом коде не завёрнут в `Zeroizing`. Это утверждение об обёртке проекта, не утверждение о внутренней реализации libsignal. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/identity.rs#L53-L59).
2. `crates/sm-ffi/src/keys.rs:100–111`: в callback `wrap` уходит дополнительный `secret.to_vec()`, а возврат из `unwrap` оборачивается в `Secret` только на Rust-стороне. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/keys.rs#L100-L111).
3. `android/.../keys/DeviceKeys.kt:23–35`: массив аргумента `wrap` действительно очищается в `finally` — это правильно. Но `unwrap` возвращает новый plaintext `ByteArray`; здесь нет протокола подтверждения, после которого Kotlin мог бы очистить именно возвратную копию. Нельзя просто очистить её перед `return`: это уничтожит нужный результат. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/keys/DeviceKeys.kt#L23-L35).
4. `crates/sm-ffi/src/signal.rs:103–112` и `android/.../contacts/ContactBook.kt:32–37`: значения Signal KV копируются в `value.to_vec()` и через Kotlin передаются в шифрование базы; Kotlin `put` не очищает полученный plaintext `value` после записи. [Rust permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-ffi/src/signal.rs#L103-L112), [Kotlin permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/ContactBook.kt#L32-L37).
5. `android/.../ble/BleLink.kt:581–585`: `ByteArray.direct()` выделяет отдельный direct ByteBuffer и копирует данные; не возвращает владельца, обеспечивающего очистку. `InviteFlow.kt:157–176,234–242` передаёт таким образом KDF key и секреты приглашения, без `finally`-очистки исходного ключа или его direct-копии. [Buffer permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/ble/BleLink.kt#L577-L585), [creation permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L157-L176), [opening permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L227-L242).
6. `InviteCode.kt:42,55–65`: UTF-8 password массив создаётся прямо в вызове Argon2; вызывающий не удерживает его для очистки. Само поведение внутренних буферов Argon2 AAR здесь не установлено. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteCode.kt#L41-L65).

Для сравнения: `sm_core::keystore::Secret` действительно использует `Zeroizing<Vec<u8>>` (`keystore.rs:17–47`). Но затирание этого одного владельца не охватывает все перечисленные копии. Очистка QR secret в `Pairing.kt:273` также не гарантирует очистку прежних direct/FFI-копий.

### Условия атаки и влияние

Нужно получить доступ к памяти приложения/native heap, например через отдельно найденную memory-disclosure ошибку, отладчик с разрешённым доступом или привилегированный анализ устройства. Только чтение зашифрованных файлов либо перехват HTTPS не раскрывают эти копии. Если копии доступны, могут раскрыться identity material, состояние сессий, KDF key или plaintext сообщений/приглашений.

Это **не** доказанный обход Keystore, не удалённое исполнение кода и не доказательство, что ключ «всегда лежит» по фиксированному адресу. Противник, уже способный исполнять произвольный код от UID приложения, обычно может пользоваться Keystore независимо от этих остатков; поэтому не повышаю severity по одному гипотетическому доступу к памяти.

### Тест/PoC

`tests/BufferCopyProbe.java` воспроизводит с **синтетическим массивом** тот же JDK-механизм: копирование в `allocateDirect`, обнуление исходного `byte[]`, проверка, что direct-копия не изменилась, затем очистка самой копии. Результат PASS.

```
java /data/audit-platform/tests/BufferCopyProbe.java
```

Это демонстрация независимости буферов, **не** runtime-тест Android/UniFFI и не извлечение настоящего ключа. Generated Kotlin bindings и native зависимость UniFFI в архиве отсутствуют; детали всех её внутренних временных буферов не проверены и не выдаются за доказанный дефект upstream.

### Исправление

- Обернуть временную сериализацию identity в `Zeroizing`, а не только итоговый массив.
- Для синхронных секретных ByteBuffer-вызовов использовать scoped-обёртку с `try/finally`, очищающую **всю выделенную копию** после возврата; обычный `ByteBuffer.clear()` только меняет позиции, не стирает память.
- Очищать KDF key и доступный password `ByteArray` в `finally`, включая ошибки, отмену и ранний возврат. Не мутировать общий массив, пока он нужен другому владельцу.
- `SignalKv.put` может очищать свой временный аргумент после успешной/ошибочной записи, если подтверждено отсутствие дальнейшего использования и алиасинга. Не применять это вслепую к разделяемым долгоживущим ключам.
- Возвратные callback-копии `unwrap/get` требуют изменения ownership/protocol или поддерживаемого sensitive custom binding; очистка перед завершением передачи сломает корректность. Проверить **генерируемый код точно UniFFI 0.32.2**, добавить тесты с вымышленными маркерами для ошибок и success-path.
- JVM `String` с кодом/ссылкой нельзя гарантированно стереть; сократить lifetime и избегать лишних преобразований. Не обещать полной zeroization ART одной правкой `fill(0)`.

### Сверка с прежними находками

Незатираемая identity-копия уже отмечена как low в `releases/v0.0.1-alpha.md:80`; это не новая независимая находка о том же участке. Текущая проверка подтверждает остаток и уточняет дополнительные Kotlin/direct/KDF/KV-пути. Содержимое generated UniFFI или upstream libsignal не исследовано достаточно для отдельной upstream-находки.

## 5. Проверенные защиты и наблюдения, не являющиеся подтверждёнными уязвимостями

### 5.1 Manifest, components, deeplinks, backups

- `AndroidManifest.xml:36–66`: `allowBackup=false`, `dataExtractionRules`, `usesCleartextTraffic=false`; launcher MainActivity экспортирована ожидаемо, BleService — `exported=false`, provider отсутствует. BootReceiver экспортирован, но код `BootReceiver.kt:17–18` принимает только два системных действия и игнорирует другие. На проверенной исходной декларации произвольного внешнего управляющего service/provider API не найдено. [Manifest](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/AndroidManifest.xml#L36-L66).
- VIEW/BROWSABLE intent-filter и обработка `intent.data`/`onNewIntent` в MainActivity не найдены. Приглашение «по ссылке» в этом SHA — ввод внутри UI, не экспортированная Android deep-link поверхность. Сам факт поддержки формата приглашения не означает внешнего deeplink handler.
- `data_extraction_rules.xml:4–17` исключает root/file/database/sharedpref/external и для cloud backup, и для device transfer. Identity и снимки — в CE `noBackupFilesDir`; не найдена запись этих секретов во внешний каталог. [Backup rules](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/res/xml/data_extraction_rules.xml#L4-L17).
- **Ограничение:** проверена исходная декларация, не merged manifest APK. Компоненты, добавляемые зависимостями через manifest merge, поведение OEM backup/transfer, защищённость broadcast на устройстве и фактическое debuggable release не проверены динамически.

### 5.2 TLS и узлы

- `network_security_config.xml:7–10`: запрет cleartext, trust anchors только `system`, без debug-overrides/user CA.
- `Pinning.kt:32–35`: сначала системная цепочка, затем SHA-256 SPKI **leaf** по пинам узла; `HttpsRelay.kt:165–183`: HttpsURLConnection, отдельная pinned factory, стандартная проверка hostname не заменена, redirects выключены, тело ответа ограничивается `relayReplyMax()` до передачи парсеру. [Pinning](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/Pinning.kt#L32-L55), [exchange](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L164-L221).
- `NodeEndpoint.kt:15–49`: ограничения hostname/port/path/pin lengths, исключающие userinfo, query/fragment и произвольные URL scheme в этом пути. `sm-ffi/node.rs:67–68` вызывает проверку подписанной записи через `ROOT_KEYS`; клиентские пути загрузки используют `nodeRecordOpen`. Непроверенная запись не становится endpoint непосредственно.
- `guest_pass_open` проверяет формат/срок, но не подпись гостевого пропуска (`node.rs:125–142`). Это **не самостоятельная authentication vulnerability**: на клиент он приходит по pinned TLS, право `NEW` должен проверить сервер. Полная проверка серверного authorization в этот платформенный аудит не входила.
- Не выполнялись реальные DNS/TLS запросы, hostname mismatch/user-CA tests, истечение/ротация пинов или MITM на эмуляторе. Наличие конфигурации не выдаётся за runtime-доказательство.

### 5.3 Keystore, хранение и экраны

- `SnapshotCipher.kt:47–87`: AES-256-GCM, IV генерируется провайдером, AAD привязывает имя, неправильная версия/длина/ошибка проверки — отказ. Создание ключа под общим замком. StrongBox fallback только на `StrongBoxUnavailableException`.
- `SecureDb.kt:80–117,149–179`: шифрование KV с AAD table/key, HMAC индексы ключом Keystore; AAD messages связывает строку с контактом. SQL queries параметризованы в просмотренных путях; открытые метаданные (время/порядок/внутренний contact id) — не обещание полной защиты метаданных.
- `DeviceKeys.kt:46–59`: файл identity ограничен до 16 KiB до принятия целиком; ошибка не создаёт новый identity молча.
- Нет `setUserAuthenticationRequired` / `setUnlockedDeviceRequired`: это реализованное ADR-56 для фона после первой разблокировки. Не сообщается как новая уязвимость. Защита от ОС, уже контролирующей приложение, не обещается.
- `MainActivity.kt:110–120` и поиск по Android source: `FLAG_SECURE` не найден. Снимки экрана/превью задач/захват QR и кода — **отсутствие privacy hardening**. Без permission/accessibility/user-consent цепочка чтения экрана чужим приложением не доказана. Если scope расширится на изъятие/запись экрана, применить флаг хотя бы на чувствительных экранах и проверить UX на устройстве.
- `DeliveryStore.kt:109–117` использует `AtomicFile.readFully()` до расшифровки; предел очереди Rust не предотвращает предварительную загрузку гигантского локального файла. Это **локальный robustness/hardening gap**, требующий доступа на запись в private directory; удалённая запись такого файла не найдена. Рекомендуется bounded stream по максимальному размеру формата + AEAD overhead. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/store/DeliveryStore.kt#L109-L117).

### 5.4 Логи, clipboard, уведомления

- В Android source поиск не обнаружил `Log.*`, `println`, `printStackTrace`. API debug diagnostics принимает enum/числа, без произвольного текста exceptions/body. Журнал зашифрован `sm.store`, ограничен, имеет срок и отдельный writer; release source содержит no-op заглушки. `ReleaseDiagnosticsTest.kt:8–16` проверяет no-op и отсутствие классов журнала, **но этот JUnit-тест здесь не запускался**.
- Ручной debug TXT-export идёт через системный CreateDocument, с предупреждением о plaintext и времени событий (`DebugTools.kt:81,217`). Это сознательный экспорт после действия пользователя, не скрытая утечка. Автоотправки журнала в просмотренных путях нет.
- `AddContactScreen.kt:703–706` помещает приглашение в clipboard с sensitive flag. Флаг скрытия preview не превращает clipboard в криптографически закрытое хранилище. Автоочистки на стороне приложения не найдено; на API 31–32 это стоит рассмотреть как hardening. Нужен foreground/IME/иной разрешённый доступ к clipboard; не установлена возможность произвольного фонового приложения читать его без условий. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/AddContactScreen.kt#L697-L708).
- `Notifier.kt:35–48`: immutable PendingIntent, public notification без имени/текста; private notification содержит preview. Приложение с отдельно выданным notification-listener доступом всё равно относится к другой границе доверия. Settings канала/lockscreen на реальном устройстве не тестировались.

### 5.5 UniFFI boundary

- Собственных `unsafe {}` в `sm-ffi/src` статический поиск не обнаружил. Это не распространяется на сгенерированный scaffolding, JNA, JNI Argon2 или upstream.
- Версия runtime и bindgen закреплена одинаково `=0.32.2`, generator запускается с `--locked`; .so и bindings создаются из одной сборки.
- Идентификаторы преобразуются в фиксированные массивы через `try_from`, ошибки типизированы; relay парсер проверяет точную длину; Signal callback errors преобразуются в отказ. Проверены ветви callback и основные export-методы, не каждый upstream lift/lower.
- SignalSessions использует Mutex, poison — отказ. Kotlin SignalGate задаёт внешний порядок замков и transaction; SecureDb.atomic откатывает null/exception. Не обнаружен явный обход transaction в просмотренных основных входах.
- ChunkReassembler сбрасывает состояние при неоткрывшейся части; BLE source ограничивает peers/frames/bytes, prepared/offset writes отклоняются. Это не полное исследование BLE радиоканала или pairing state machine.
- Не найден проектный сетевой путь, позволяющий вызвать FFI с произвольным огромным Java ByteBuffer в обход transport limits. Отсутствие общего лимита на все локальные FFI аргументы само по себе не доказывает удалённый OOM. Гонки мутации direct ByteBuffer при native borrow не тестировались; просмотренные вызовы создают свежую копию.

### 5.6 CI, release signing и supply chain

- Все **внешние action references** в трёх workflows закреплены 40-символьными SHA. Checkout имеет `persist-credentials:false`; PR build не получает release signing secrets; обнаружен `pull_request`, не `pull_request_target` с checkout враждебного кода. `ci-ok` имеет PR write для комментария, но не checkout/исполнение проекта в этой задаче.
- Release build без signing secrets; sign требует `build` и `kani`, в environment `release`, без checkout. Secret key записывается с umask 077 и удаляется trap; пароли передаются apksigner через env, не литералами командной строки. Артефакт — один APK, подпись v2/v3 проверяется. Это конкретное устранение прежней совместной build/sign-задачи. [Permalink](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/.github/workflows/release.yml#L81-L149).
- **Ограничение release gate, не внешняя уязвимость:** `release.yml:9–11,81–86` запускается на `v*`, проверяет build/Kani, но не проверяет автоматически `ci-ok` конкретного SHA, принадлежность кандидата main, matching reviewed_head или отсутствие open critical/high в JSON. Документы требуют ручного процесса, после подписи создаётся draft. Право поставить тег и/или ошибка владельца являются необходимым условием; без проверки фактических environment/rulesets настроек нельзя объявить это bypass. Подпись артефакта не равна аттестации прохождения всех CI checks. Для повышения assurance добавить отдельный read-only preflight exact-SHA CI/review gate перед разрешением signing environment.
- Actual GitHub environment reviewers, разрешённые tags, no-admin-bypass, branch/tag protections, секреты и runner fleet **не доступны как факт из этих workflows и здесь не проверялись**. Комментарий «одобрение владельца» не доказывает настройку environment.
- SHA-256 фиксирован для скачиваемых Gradle/protoc/detekt/actionlint архивов. Cargo build/bindgen — `--locked`; Cargo.lock фиксирует libsignal на `4beb029d8a941f81e7d9c6d8af1ed25a677569a8`, SPQR на `06959b4708f9b7b1e94d0f8cc835f3958c077e94` (manifest tag закрепляется lockfile).
- Gradle verification: `verify-metadata=true`, `verify-signatures=true`, нет `trusted-artifacts` bypass. Статически найдены 600 artifact entries, 599 имеют SHA-256; единственный PGP-only — `com.google.auto:auto-parent:7`, `auto-parent-7.pom`. Он не «без проверки». Отдельные ignored-keys означают fallback на checksum, а не глобальное отключение verification. Metadata не заменяет аудит кода, и факт её наличия не означает, что оба механизма всегда независимо применяются к каждому артефакту во время Gradle resolution.
- `cargo-vet` config содержит **97 exemption entries**; часть прямо говорит «аудит не проведён». Поэтому зелёный vet не надо интерпретировать как независимый аудит всех зависимостей. Импорты аудитов используют mutable main upstream. Это явная модель доверия, не установленная компрометация.
- `.cargo/audit.toml` / `osv-scanner.toml` имеют узкое исключение `RUSTSEC-2026-0173` с документированным cfg(hax) обоснованием; OSV срок указан. Достижимость в actual Android binary не вычислялась. Наличие advisory/unmaintained dependency в lockfile не автоматически runtime-exploit проекта.
- Плавающий `ubuntu-latest`, SDK tools и установка некоторых build tools без отдельного project checksum расширяют TCB. Это hardening/reproducibility gap; компрометация runner/upstream не показана. Release не использует PR Gradle cache, что уменьшает cache-poisoning поверхность.
- **Не установлена ни одна новая upstream CVE с доказанной достижимой цепочкой в приложении.** Не выполнялись live OSV/RustSec запросы; актуальный advisory verdict по всем зависимостям не выдаётся.

## 6. Сверка с прежними находками

Отдельная сверка выполнена после фиксации основных выводов; оговорка о раннем чтении встроенных сводок дана выше.

| Прежний профильный риск | Что видно на текущем SHA | Что это не доказывает |
|---|---|---|
| Общая задача сборки и signing | Build/sign разделены; sign без checkout | Не доказывает секретность реального environment или отсутствие ошибок apksigner |
| Гонка создания Keystore key | Общий KEY_LOCK в SnapshotCipher/RowIndex | Не тест на реальном TEE/StrongBox |
| Файл identity читается без лимита | Бounded чтение MAX_RECORD + 1 | Не распространяется на DeliveryStore.readFully |
| AAD истории не связывает контакт | `messageAad(contactRow,id)` и миграция версии DB | Не runtime-тест миграции и rollback |
| Неатомарные Signal операции | SignalGate + SecureDb.atomic | Не fault-injection всех crash windows |
| Незатираемые identity copies | Остаток сохраняется, PLAT-2 | Не новая high remote vulnerability |
| Публичный debug key | Прямо описан ADR-147, реализация есть | Не компрометация release key |

Не копировал старые номера строк как текущие доказательства и не объявлял все исторические находки закрытыми. Полная повторная проверка всех прежних pairing/BLE/delivery находок — вне выполненного прохода.

## 7. Покрытие, запускавшиеся проверки, пропуски

### Что реально просмотрено

- Исходный AndroidManifest, XML backup/network policy; entry/activity/receiver/service и PendingIntent notification.
- SnapshotCipher, DeviceKeys, SecureDb/RowIndex, selected DeliveryStore/ContactBook/SignalGate paths.
- HttpsRelay, Pinning, NodeEndpoint; входы проверки узла/пропуска; clipboard, invite KDF/create/open и selected pairing cleanup paths.
- Debug diagnostics core/writer/storage/export, release заглушки и определения release tests.
- Все восемь `sm-ffi/src` файлов охвачены поиском/просмотром профильных production ветвей; это не построчное чтение каждого теста и не аудит всей криптографии sm-signal.
- Все три workflow целиком, build-android-core/fetch-protoc, root/generator manifests/lockfiles, Gradle verification configuration, audit/OSV исключения, deny/vet configuration.

### Что запускалось

| Проверка | Результат / граница |
|---|---|
| GitHub MCP get_commit/get_file_contents | Read-only; полный заданный SHA |
| curl codeload, tar, sha256sum; git-blob hash AGENTS | Снимок фиксированного SHA; совпадение AGENTS с MCP |
| `java tests/DebugSignerProbe.java ...` | PASS: синтетическая подпись доступным debug private-key entry; **не APK exploit** |
| `java tests/BufferCopyProbe.java` | PASS: очистка исходного массива не очищает direct-копию; **не Android runtime test** |
| `python tests/static_checks.py` | PASS: source policies/components/action pins/verification assertions |
| Python XML/TOML инвентаризация | 600 Gradle artifacts, 1 PGP-only; 97 vet exemptions; три lockfile и git pins |
| `node scripts/check-crypto-bans.mjs` | PASS: 82 крейта, deny/оба Semgrep списка согласованы |
| `node scripts/sek-lint.mjs` | PASS: 15 правил, 7/20 в inbox; ничего не исправлял |
| find/rg/nl/sed/wc, просмотр available commands | Статический поиск и чтение исходников/документов |

Артефакты локальных проверок находятся в `/data/audit-platform/tests/`; ключи или настоящий plaintext в результаты не записаны. Снимок кода содержит публичный debug keystore как часть исходников, но дополнительных извлечённых private-key файлов не создавалось.

### Что не запускалось и почему

На компьютере найден Java, но не найдены `cargo`, `rustc`, `kotlinc`, `gradle`, `adb`. Не устанавливал toolchain/SDK и не запускал сборочные скрипты, способные скачивать большой граф dependencies и выполнять build scripts.

**Не запускались:** cargo test/clippy/audit/deny/vet/Kani/fuzz, Semgrep/detekt/actionlint/zizmor, Gradle сборка и JUnit, эмулятор/устройство, apksigner на APK, runtime Keystore/StrongBox, backup/device transfer, MITM/TLS, memory dumps/fault injection. Наличие этих задач в CI не означает, что они выполнены мной или прошли именно для проверяемого SHA.

**Не покрыто полностью:** generated UniFFI scaffolding/JNA internals и lifetime contract на Android; upstream libsignal/Argon2/native libraries; реальный merged manifest/APK; полный server-side auth/guest/ticket и криптопротоколы; все pairing/delivery race/crash windows; GitHub settings и лог предыдущих Actions; provenance каждого action SHA; воспроизводимость binary/source; независимый аудит всех dependency sources; глубокий поиск секретов по истории git. Archive содержит только один commit, истории нет.

Обращения в сеть ограничивались чтением исходников через GitHub MCP и скачиванием codeload archive. Живые сервисы приложения и чужие данные не трогались.

## 8. Приоритет действий без изменения verdicts

1. Уточнить модель использования debug: одноразовая лаборатория или реальные сообщения. Во втором случае убрать публичную подпись из доверенной цепочки обновлений; это PLAT-1.
2. Добавить scoped cleanup для KDF/direct/KV и временной сериализации identity, затем проверить generated UniFFI ownership; не обещать полную очистку JVM Strings. Это PLAT-2.
3. В отдельной тестовой среде проверить exact-SHA APK: merged manifest, release-debug separation, signing сертификат, component access, backup/transfer и TLS negative tests.
4. Проверить реальные signing environment rules и привязку review/CI к exact SHA. Это повышение assurance, не найденный удалённый exploit.
5. Для памяти/crash/allocator tests использовать только вымышленные ключи и сообщения; для DeliveryStore добавить bounded read.

**Release verdicts не создавались и не менялись. Отчёт предназначен для родителя/владельца; решение о сохранении в репозитории и выпуске не принималось.**
