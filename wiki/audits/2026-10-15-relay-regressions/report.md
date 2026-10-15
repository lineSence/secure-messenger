# Аудит регрессий reactor/shards и дальнейшей оптимизации

Дата измерений: **2026-10-15 UTC**, по часам лаборатории. Основание — поручение владельца провести тщательный аудит выявленных регрессий и иных способов оптимизации.

**Вердикт:** CPU-регрессия пакета PR #96 воспроизводится на 4-KiB сообщениях. Это не доказательство, что событийная модель хуже потоковой: новая реализация добавила устранимую работу на запрос. Простой переход с двух worker на один её не устраняет. MAX_BLOB-регрессия и прежний размер выигрыша p99 под 32 клиентами не воспроизведены как стабильные величины. Дополнительно подтверждено размытие бюджета очистки между пустыми шардами.

Рабочий код узла, wire, клиент, CI и VPS этим аудитом **не изменены**. Никакой телеметрии/логирования в узел не добавлено; состояние доставки остаётся RAM-only. Только лаборатория с фиктивными данными, внешний perf/strace и локальный тест-проба. Исправления из реестра ниже ещё не реализованы.

## 1. Проверенная версия и объём

- Проверенный main: `cd74127524e5b8279d35d5add2d2dfea5f27efe1`. Это документационный коммит после PR #96; код relay тот же, что в `a756a3d28d1031d72940fb29693c2c2982e4f53e`.
- База сравнения: `435d40a40eda487ede80da372b785ed8f02cc6d7`.
- Повторно проверены SHA256 прежних release lab-бинарников:
  - до: `1817797127b33d51a6266a21058fdbda458953d43f36d9f48048cfd561558797`;
  - после: `468671ab3c0c785c7aff1e79f822d09e0a1ea2b2f0fd8f52a2916d0a173f36f9`.
- Прочитаны server/reactor, Engine, Store, Budget/Quotas/Namespace, notifications, HTTP, passes, batch codec, settings/main и тесты; сверены контракт, прежние аудиты и стабилизация.
- 27 **неинструментированных** прогонов: 3 профиля × 3 варианта × 3 повтора по 10 секунд, всего 810,000 проверенных циклов SEND→FETCH→ACK. Все циклы предложенного плана выполнены, ошибок/несовпадений содержимого нет.
- Отдельно 4 perf-профиля по 20 секунд и 4 strace-прогона. Они не подмешаны в сравнение производительности.
- Повторно прошли 243 штатных Rust-теста workspace и одна новая **локальная лабораторная** проверка продвижения очистки; последняя также выполнена в release. Нового Android/fuzz-прогона этим аудитом не было, проверка PR #96 — [предыдущий CI](https://github.com/lineSence/secure-messenger/actions/runs/38775393340).
- Проверка очистки добавлялась только как `cfg(test)` в отдельном worktree, не в рабочий бинарник/репозиторий. Исходник сохранён в `reproduce.md`.

P1/P2/P3 ниже — приоритет работ, **не** рейтинг уязвимостей. Наличие затрат по коду/в профиле не означает доказанного процента ускорения после исправления.

## 2. Что показали повторы

Два видимых CPU, тот же sandbox, Python-генератор и узел на одном хосте, localhost HTTP v1. Предложение: 2000 циклов/с при 5 клиентах × 4096 B; 6000 при 32 × 4096 B; 1000 при 16 × 131136 B. По 10 разогревочных циклов на клиент до общего барьера. Порядок вариантов менялся с фиксированными seed. Вариант `after-1worker` отличается только `SM_RELAY_IO_WORKERS=1`, число Store shards остаётся 16.

CPU — user+system delta процесса через `/proc`, делённая на завершённые циклы. Здесь медианы трёх **показателей прогона**, а не квантили объединённой выборки. p99 с графиком включает задержку от запланированного начала; отдельно сохранён service p99. Дискретность CPU — 10 мс.

| Профиль | CPU до, мкс/цикл | CPU после, мкс/цикл | Δ CPU | p99 с графиком до → после, мс |
|---|---:|---:|---:|---:|
| 4 KiB · 5 клиентов | 56.50 | 69.00 | +22.1% | 0.318 → 0.340 |
| 4 KiB · 32 клиента | 48.00 | 53.83 | +12.2% | 0.265 → 0.232 |
| MAX_BLOB · 16 | 133.00 | 134.00 | +0.8% | 0.588 → 0.545 |

### Диапазоны обязательны для интерпретации

| Профиль | Версия | CPU медиана [min; max], мкс/цикл | p99 медиана [min; max], мс |
|---|---|---:|---:|
| 4 KiB · 5 клиентов | before | 56.50 [53.50; 60.00] | 0.318 [0.294; 0.343] |
| 4 KiB · 5 клиентов | after | 69.00 [66.00; 71.50] | 0.340 [0.332; 0.356] |
| 4 KiB · 5 клиентов | after-1worker | 70.50 [62.00; 72.50] | 0.364 [0.328; 0.366] |
| 4 KiB · 32 клиента | before | 48.00 [45.17; 49.00] | 0.265 [0.209; 0.277] |
| 4 KiB · 32 клиента | after | 53.83 [52.00; 55.83] | 0.232 [0.225; 9.649] |
| 4 KiB · 32 клиента | after-1worker | 54.83 [52.00; 62.00] | 0.265 [0.218; 7.804] |
| MAX_BLOB · 16 | before | 133.00 [130.00; 146.00] | 0.588 [0.572; 0.627] |
| MAX_BLOB · 16 | after | 134.00 [133.00; 156.00] | 0.545 [0.517; 0.700] |
| MAX_BLOB · 16 | after-1worker | 160.00 [127.00; 160.00] | 0.613 [0.508; 0.620] |

- Рост CPU на 4 KiB сохраняется во всех трёх повторных парах. Но **+22.1%/+12.2% — результаты этих коротких профилей**, не новая универсальная оценка вместо +11.2% прежнего 300-секундного смешанного окна.
- Вариант с одним worker дал медианы 70.50/54.83/160.00 мкс на цикл соответственно; это не системное средство устранить регрессию. Число worker следует настраивать по профилю, не объявлять «один всегда быстрее».
- Прежние +3.7% MAX_BLOB scheduled p99 **не подтверждены как устойчивое ухудшение**: в новых повторах медиана после меньше, диапазоны пересекаются.
- Прежнее снижение busy p99 на 55.6% было фактом конкретного окна, но не переносится на каждый повтор. В одном новом after-прогоне scheduled p99 достиг 9.649 мс; в остальных двух — 0.232 и 0.225 мс. До — 0.277/0.209/0.265 мс. Выброс не удалён и не замаскирован медианой.
- Для busy after-0 все 1084 цикла с scheduled latency >5 мс имели плановое начало в первых 0.218 с. Значительная часть отставания относится к началу генерации/обслуживания. Это **не доказывает**, что виноват только генератор или только узел. В варианте одного worker также был выброс, но уже в середине окна. Нужны временные метки и отдельный генератор; нельзя автоматически исключать «разогрев».
- Это не saturation-тест, не доверительный интервал и не измерение рабочего VPS. Клиентские/WAN задержки из этих долей миллисекунды не выводятся.

## 3. Профиль CPU и системные вызовы

`perf record -e cpu-clock:u -F 499 --call-graph dwarf,8192`: **software user-space samples**, не аппаратные cycles/instructions и не распределение всего user+kernel CPU. Аппаратные счётчики на стенде не поддержаны. Число samples ограничено: 238 до/light, 409 после/light, 941 после/busy, 512 после/MAX_BLOB; потерянных samples в отчётах нет. Проценты — self-samples, без суммирования parent/child.

| Символ / доля sampled user CPU после | Light | Busy | MAX_BLOB |
|---|---:|---:|---:|
| SHA-256 software compress | 11.25% | 9.99% | 4.30% |
| HTTP parse_head | 6.36% | 6.27% | 14.65% |
| memmove | 5.62% | 5.42% | 20.31% |
| Reactor::run | 8.07% | 8.18% | 9.38% |

Это подтверждает горячие направления, но **не объясняет этими четырьмя числами весь рост CPU**. System CPU существенен, а данный sampling его не раскладывает. `memmove` включает разные копирования/перемещения; нельзя весь его процент приписать одной строке drain. На проверенном CPU в `/proc/cpuinfo` нет sha_ni, присутствуют AVX2/AVX512F; software SHA в профиле не является доказательством ошибки сборки.

В busy perf-прогоне user-samples между двумя `relay-io` разделились 50.37%/49.63%. Перекос accept/нагрузки остаётся сценарием проверки, **не объяснением этого прогона**.

`strace -f -c`, один клиент, 2000 циклов 4 KiB или 400 MAX_BLOB; кроме них в счётчиках есть startup/NEW/10 warmup:

| Системный вызов | До, 4 KiB | После, 4 KiB | До, MAX_BLOB | После, MAX_BLOB |
|---|---:|---:|---:|---:|
| setsockopt | 14067 | 3 | 15267 | 3 |
| recvfrom | 8033 | 8183 | 14033 | 14115 |
| writev | 6031 | 6031 | 1231 | 1231 |
| epoll_ctl | 0 | 763 | 0 | 595 |
| epoll_wait | 0 | 760 | 0 | 591 |

Событийный runtime убрал прежние многократные socket timeout setsockopt, но добавил poll bookkeeping. **Нельзя объявлять общее увеличение системных вызовов** — старые тоже исчезли. Ptrace меняет планирование/коалесцирование; эпизодов epoll может быть меньше, чем запросов. Его время/%time, особенно futex у спящих потоков, **не доля CPU и не benchmark**. EAGAIN/прерванный futex при завершении не считаются ошибками протокола.

## 4. Реестр находок и безопасных оптимизаций

### RA-01 / P1 — повторный разбор уже принятого заголовка

**Подтверждено кодом и профилем.** `Connection::parse` на каждом недостающем фрагменте заново ищет HTTP HEAD_END, разбирает поля и wire header. Тело читается по 4096 B, первый HTTP read — до 1024 B. При небольшом заголовке максимального SEND это порядка 33 повторов parse_head; TCP-фрагментация может добавить работу. HTTP parser дал 14.65% user-samples MAX_BLOB.

Решение: явные фазы NeedHttpHead → NeedWireHead → NeedBody → Ready; после валидации сохранять проверенные длину/version/cmd/ID/close и offset. Не переобходить заголовок при чтении тела. **Не расширять** HTTP-подмножество, не доверять непроверенной Content-Length.

Отдельная связанная проблема: 131136 B тела не укладывается в 32 итерации drive при 4-KiB чтениях; появляется дополнительный todo/poll/reregister проход. Увеличивать надо не слепо число итераций, а адаптивный размер чтения + конечный бюджет байтов/команд/времени на ход.

Проверки: все разбиения CRLF/wire header/body, long header, fragment 1 B, pipelining, close, противоречивые длины, batch/v1, absolute deadline. Источник: [server.rs L317–374](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L317-L374), [L451–551](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L451-L551).

### RA-02 / P1 — повторные SHA-256 и блокировки FETCH

**Подтверждено кодом и новым hotspot профиля.** Одиночный FETCH вызывает fetch_start, fetch, advance_fetch: трижды `sender_id(recipient)` и трижды один и тот же shard mutex. Затем ACK вычисляет sender_id ещё раз. Итого **четыре SHA-256 на успешный цикл SEND→FETCH→ACK**, не считая NEW/более длинных списков. До шардирования таких вычислений в этом data path не было. SHA-функция ещё и создаёт/финализирует CryptographicHash.

Решение: fast path одного ID — один вычисленный маршрут и один guard для выбора/получения/курсора. Для нескольких ID — вычислять маршрут ID один раз за операцию, при парковке WAIT хранить ограниченную подготовленную структуру маршрутов. Не делать бесконечный кеш ID/обращений. Не менять формулу capability или криптобиблиотеку.

Проверки: fairness стабильного списка, NO_QUEUE index, TTL/DEL/повтор ACK, recreated queue, глобальные квоты. Нельзя захватывать несколько shard mutex в произвольном порядке. Источник: [Engine L93–113](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/engine.rs#L93-L113), [fetch_any](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L819-L856), [sender_id](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/pass.rs#L170-L182).

### RA-03 / P1 — лишняя работа планировщика на каждое событие

**Подтверждено кодом, символами профиля и наличием epoll_ctl.** После любого drive выполняется reregister, даже если READABLE/WRITABLE не изменился. Connection удаляется/вставляется в HashMap; таймер удаляется/вставляется даже при прежнем deadline. `mem::take(todo)` отдаёт HashSet на уничтожение; следующий набор готовности снова требует ёмкости/rehash. Профиль видит Reactor::run, HashMap remove, HashSet insert/reserve_rehash и allocator.

Решение: сохранять interest и менять регистрацию только по реальному переходу, **с проверкой семантики каждого поддерживаемого Mio backend**; повторное обслуживание недочитанного события обеспечивать собственным bounded ready queue. Сохранять capacity ready-структуры, обновлять таймер только при смене срока, не перемещать connection между HashMap без необходимости. Возможен индексированный слот с generation, но это отдельное изменение, не повод вводить unsafe/неограниченный slab.

Проверки: readiness drained-to-WouldBlock, частичные записи, pipeline, slow-reader, FIN/RST, stale token, отсутствие hot spin/lost event и stop. «Просто удалить reregister» без этих тестов — не готовое исправление. Источник: [server L718–747](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L718-L747).

### RA-04 / P2 — атомарные операции над нулевыми квотами

**Подтверждено кодом; вклад в проценты CPU отдельно не измерен.** reserve(0,1,len) делает CAS и над queues=0. commit обнуляет поля, после чего Drop всё равно выполняет три fetch_sub(0). ACK release(0,1,len) снова изменяет queues на ноль. На успешную SEND/ACK-пару — **9 quota RMW**, из них **5 с нулевым аргументом**, плюс clone/drop Arc пула. Счёт не включает Budget и payload Arc.

Решение: пропускать нулевые измерения и disarmed Drop, сохранить RAII rollback при отказе любого ненулевого резерва. Затем отдельно проверить contention/cache-line placement общих quota/Budget на большем числе CPU. Не превращать общую квоту в «квота × shards» и не менять Ordering вслепую.

Проверки: concurrent reserve/release, предел ±1, ошибка на втором/третьем измерении, rollback, ACK/TTL/DEL и последний outgoing Arc. Источник: [quota.rs](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/quota.rs).

### RA-05 / P1 — бюджет фоновой очистки размыт по пустым шардам

**Подтверждено детерминированной лабораторной пробой.** Engine делает 256 одноступенчатых посещений всех data shards **и control**, включая пустые. Внутри Store ещё четыре линии обслуживания. Для 1000 истёкших 1-B блобов одной очереди, без foreground traffic:

| Data shards | Вызовов Engine::maintain(now,256) до освобождения блобов |
|---:|---:|
| 1 | 32 |
| 16 | 266 |
| 256 | 4013 |

Это **не замеры секунд**. При backlog сервер дополнительно ждёт 10 мс между вызовами; загрузка/локи и первоначальная idle-пауза добавляются. Количество shard само уменьшает долю полезной очистки горячего раздела. engine maintenance_pending также проходит до S+1 mutex; 256 шагов не ограничивают этот обход. Reactor 0 может блокироваться на Store, занятым другим worker.

Решение: ограниченный планировщик активных/due shards с одним состоянием на shard; тратить бюджет преимущественно на реальные due/retired работы, сохраняя fairness control lanes. Разделить **лимит посещений, полезной работы и elapsed-time budget**. Для фоновой работы рассмотреть try_lock+fair bounded retry, а не ожидание занятого шарда внутри I/O loop. Не лишать пустые шарды проверки навсегда.

Проверки: 1/16/256 shards, один горячий и равномерный backlog, TTL/idle/DEL/ticket/pass lanes, quota recovery, control PING latency при уборке. Нельзя ускорять выдачей протухших сообщений. Источник: [Engine maintain](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/engine.rs#L121-L150), [Store maintain](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/store.rs#L677-L760).

### RA-06 / P2 — уведомления адресные, но общий lock остался

**По коду; массовый fan-out отдельно не измерен.** Каждый SEND берёт один глобальный Registry mutex даже без подписчиков. При наличии подписчиков lock удерживается во время Weak upgrade, работы с pending mutex и waker.wake для каждого signal. Разные подписки одного reactor могут дать несколько системных wake вместо одного.

Решение: шардировать registry/сократить guard, собирать ограниченные готовые signals, отпускать registry до вызова I/O wake. Коалесцировать wake по переходу очереди reactor empty→nonempty, использовать reusable drain/swap buffer. Сохранить subscribe-before-recheck и совместную синхронизацию enqueue/drain.

Проверки: 1/48/сотни WAIT, 1/64 ID, общий горячий ID и чужой SEND, timeout/cancel/DEL, subscribe/send race, многократная переподписка/stale token, bounded retained capacity. Нет оснований возвращать notify_all. Источник: [notify.rs L22–102](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/notify.rs#L22-L102).

### RA-07 / P2 — выделение deque на каждый одиночный цикл

**По коду, allocator виден в профиле.** Когда ACK опустошает очередь, blobs всегда заменяется новым VecDeque. Для типичного depth=1 это free/allocate каждый цикл, а не только освобождение редкого высокого пика.

Решение: удерживать маленькую **конечную и заранее оплаченную** ёмкость, крупную отпускать по порогу/политике. Нельзя сохранять любую пиковую deque без удержания memory charge; per-message lease после ACK уже освобождён.

Проверки: depth=1/4/1000, миллион коротких циклов, churn, отказ памяти, ACK/TTL/DEL, RSS после пика. Источник: [Store ACK L603–620](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/store.rs#L603-L620).

### RA-08 / P2 — копирование входящего payload и очистка scratch

**Подтверждено кодом; memmove/memset присутствуют в профиле, но точная атрибуция неизвестна.** Vec растёт при чтении; split_off создаёт хвост, drain переносит всё тело к началу. Scratch `[0;4096]` инициализируется на каждом read, в том числе малом ACK. Максимальный SEND всё ещё проходит большое перемещение, хотя исходящий FETCH уже разделяемый и vectored.

Решение: once-validated frame с offset/range, постепенно оплачиваемая capacity, reusable ограниченный scratch на worker, адаптивные чтения. Более сложное хранение backing buffer+range потребует учёта фактически удерживаемой capacity и последнего Arc. Не выделять MAX_BLOB каждому медленно приславшему длину клиенту.

Проверки: редкий большой запрос после маленьких, shrink/retention, медленная подача, pipeline, ответ после ACK/DEL/Store destruction, без out-of-bounds/uninitialised bytes/unsafe. Источник: [server parse/read](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L317-L374), [Store charge](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/store.rs#L533-L547).

### RA-09 / P1 — синхронная криптография/ожидание mutex внутри I/O worker

**По коду, flood-отказ в этом аудите не воспроизводился.** Проверка NEW/TICKET выполняется до rate/admission, GUEST ещё использует общий issuing mutex. При двух workers дорогая/ожидающая операция задерживает все принадлежащие worker соединения, даже других Store shards. Шардирование данных само эту head-of-line границу не устраняет.

Решение: ограниченная CPU work queue/pool и короткая state machine публикации GUEST по билету, с конечной длиной очереди/памятью и отказом перегрузки. До этого — отдельный auth-flood/control-latency стенд. Проверку подписи/срока/отзыва не выключать и не заменять неограниченным кешем авторизаций.

У гостевого ключа **публичная часть уже подготовлена** (`key.1`); старую находку о повторном её выводе не повторяем как текущий дефект. Корневые PublicKey и set отзыва можно готовить при старте при росте их количества, это ниже по приоритету.

Проверки: ошибочная подпись, повтор GUEST одного билета, expiry во время вычисления, один публикуемый результат, PING/ACK под auth-нагрузкой. Источник: [handle/issue_guest](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L859-L929), [pass check](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/pass.rs#L80-L137).

### RA-10 / P2 — batch ограничен по размеру, но умножает вложенную работу

**По коду; batch throughput/latency не измерены.** За один handle_batch можно выполнить 32 операции, каждая SEND/ACK/DEL может вызвать до 64 maintenance steps: до 2048 foreground проверок обслуживания в одном batch. 32 FETCH по 64 ID — до 2048 queue probes. Это конечные пределы, но не хороший бюджет задержки I/O turn.

Оболочка codec заимствует body, однако server вызывает item.data.to_vec для каждой команды, а FETCH копирует payload в общий batch-out Vec. Внешний batch не убирает отдельные Store/route/notify расходы.

Решение: weighted command/byte/time budget с продолжением уже провалидированного batch между ходами, без промежуточной отправки неполного ответа и без изменения ordered/nontransactional семантики; borrowed dispatch для немутирующих/малых тел; bounded pre-reserve output. По необходимости отдельно проектировать vectored slices с тем же response cap.

Проверки: max-count/min-body, 32×64 FETCH, expired backlog, несколько hot clients, overflow FETCH без потери сообщения, malformed late item без ранней мутации, потерянный ответ/повторы. Новый wire или снятие 32/count/byte cap не нужны. Источник: [handle_batch](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L960-L995), [codec](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-core/src/relay_batch.rs#L45-L67).

### RA-11 / P1 до фиксации — полный memory/retention/lifecycle предел не доказан

**Непроверенная граница, не объявленная утечка.** Runtime admission — консервативная формула, а не абсолютный RSS. Reusable buffers из предлагаемых оптимизаций нельзя вводить бесплатно. У каждого reactor остаются high-water HashMap/ready capacities; при смене распределения соединений одновременный global cap C не сам по себе доказывает retained capacity sum=C. Отдельно проверить worst-case по workers, а не только по активным соединениям.

Store payload lease следует последнему Arc — это сохраняем. Пустые/retired deque, индексы, allocator, FD/kernel buffers, worker/CPU queue stacks требуют общего расчёта и внешнего MemoryMax. Завершение с заполненным Store также отличается от теста stop на 500 **пустых** соединениях: уничтожение всей популяции при выходе не было проверено этим тестом.

Проверки: маленькие сообщения до отказа, максимальные ответы+ACK/DEL, все соединения с неполным кадром, worker redistribution, churn/TTL/retired, allocator retention, shutdown на заполненном Store. Не уменьшать запас памяти ради красивого benchmark без этого стенда. Источник: [runtime_memory](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L30-L39), [Budget](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/budget.rs), [scale test](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/tests/reactor_scale.rs).

### RA-12 / P2 — load placement и предсказуемость shard

**Риск по устройству, не установленная причина текущих регрессий.** Listener клонируется на все reactors, accept берёт до 64; равновесие длительно живущих соединений по их работе не гарантируется. В единственном разобранном busy perf случае user CPU был примерно 50/50, поэтому обвинять перекос в результате нельзя.

Shard = публично вычислимые биты sender_id mod S; клиент может искать recipient ID, попадающие в один shard, ценой порядка S попыток при равномерном хеше. Глобальные квоты ограничивают объём, но не обеспечивают равномерность. Одна горячая очередь в любом случае последовательна.

Решение: измерить connection ramp/hot queue/skew; при необходимости bounded handoff/load placement и внутренний per-process keyed shard selection проверенными средствами std. **Не менять** SID capability или делать round-robin запросов между независимыми Store. Источник: [accept](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/server.rs#L598-L647), [Engine index](https://github.com/lineSence/secure-messenger/blob/cd74127524e5b8279d35d5add2d2dfea5f27efe1/crates/sm-relay/src/engine.rs#L42-L52).

### RA-13 / P3 — малые аллокации/форматирование/часы, затем build flags

**По коду/редким user-samples, не готовый процент экономии.** Vec списка IDs для одиночного FETCH, Vec малых ответов, HTTP format/переаллокация header, частые SystemTime/Instant и хеширование HashMap. Сначала убрать повторную алгоритмическую работу RA-01/02/03; затем bounded small buffers/итераторы, повторное использование заголовков, осторожное переиспользование уже вычисленного now для одного bounded шага.

Нельзя кэшировать время так, чтобы пропускать expiry/отзыв/absolute deadline. LTO/codegen/allocator/native CPU и выравнивание атомиков — только отдельные A/B с размером бинарника, RSS и переносимостью на другой сервер, без новой небезопасной зависимости или своей криптографии.

### RA-14 / P1 — методика пока не отделяет узел от генератора/ядра/сети

**Подтверждено ограничениями и новым хвостовым выбросом.** Общий Python-генератор на 2 CPU и короткий стартовый catch-up влияют на scheduled tail; один смешанный A/B не локализует причину регрессии. Один цикл содержит 3 сетевых обмена. Batch/RTT и client scheduling — отдельные плоскости, не исправляемые только CAS/парсером.

Следующий приёмочный стенд: отдельный генератор/CPU/хост, timestamps для schedule/start/end каждого запроса и стадий без телеметрии в узле, повторные чередующиеся более длинные окна, нагрузка до насыщения, нормализация CPU/goodput, ошибка/необслуженный план в знаменателе. Затем proxy+TLS/WAN/телефоны и однофакторные ablation-сборки; текущий perf сам не доказывает долю **причины** роста CPU. Не исключать выбросы без независимого основания.

## 5. Рекомендуемая последовательность

1. Отдельный PR **RA-01 + только связанный parser I/O budget**: кеш фаз/валидации, проверка frame deadline и fairness. Сравнить light/MAX_BLOB и fragment/slow-client.
2. Отдельный PR **RA-02**, один-ID fast path/precomputed bounded routes. Проверить capability/FIFO/fairness/expiry.
3. Отдельный PR **RA-03**, registration/timer/ready reuse. Отдельные lost-wake/slow-reader/stop тесты на каждом backend.
4. Малый PR **RA-04**, zero-delta atomics/disarmed rollback; отдельно измерить, не смешивать с ослаблением memory ordering.
5. **RA-05** — до приёмки больших ресурсов: fair due-shard scheduler и границы фонового lock/time budget, прежде чем повышать число shards.
6. По профилю **RA-06/07/08/10**, затем **RA-09/11/12** с adversarial/resource тестами. Подготовить постоянный lab harness, не product instrumentation.

Критерий успеха PR: прежний внешний контракт, все проверки поставок/CI, изолированное сравнение одного изменения, отсутствие нового tail/RSS/восстановительного ухудшения в других профилях. Нельзя суммировать проценты user-samples и обещать такое же ускорение.

**Не рекомендуем:** откатить reactor вслепую; выставить один worker как универсальный fix; просто поднять drive=32; убрать подписи/квоты/deadline; сохранять неограниченные buffers/cache; ввести дисковый journal или telemetry; выдавать неподтверждённый SEND ради скорости HA.

## 6. Границы завершения

Аудит завершён, **исправления ещё не выполнены**. Многоузловый routing и HA 2/3 отсутствуют и не объясняют CPU текущего standalone бинарника; их нельзя объявить готовыми или ослабить quorum ради benchmark. Полная память, реальная сеть и окончательная фиксация контракта остаются открытыми.

Данные: `runs.csv`, `summary.csv`, `syscalls.csv`, `user-profile.csv`, `evidence.json`, `raw-manifest.json`. Сырой комплект (JSON, f64, perf.data, strace, test logs, source harness) передан владельцу архивом; крупные binaries/profiles не добавлены в git.

Проверка данных: nested JSON не представился таблицей напрямую, без изменения raw извлечено 35 строк × 29 колонок. Пропусков/дубликатов нет. Ожидаемые константы error_count=0 и initial user CPU=0.00 после короткого разогрева/10-мс квантования сохранены. worker override нормализован в текстовую метку, не смешан с числами. F64 проверены по длине/finite/неотрицательности; p99 независимо пересчитаны через struct+sorted, CPU median через statistics.median из исходных JSON. Context switch поля `/proc/PID/status` относятся к main thread и из выводов исключены. Скрипты и команды — `reproduce.md`.


## Продолжение после аудита

Выбранные владельцем RA-01…RA-05 реализованы в PR #97; исходный текст выше отражает состояние **на дату этого аудита до исправлений**. Новые проверки и ограничения результата — `wiki/audits/2026-10-15-relay-hotpath/report.md`. RA-06…RA-14 не объявлены полностью реализованными.
