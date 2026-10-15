# Переносимый запуск домашнего узла: этап H1

Дата лаборатории: 2026-10-15 UTC. Архитектура — `wiki/architecture/relay-home.md`, ADR-154. **Первый пакет H1 реализован и слит после зелёного полного CI; комплекты готовы к локальным испытаниям, не являются подписанным публичным релизом.** Он не включает внешнюю защищённую точку входа, CGNAT/reverse tunnel, HA и новый клиент. Не публиковать открытый TCP/HTTP relay наружу.

## Запуск первого пакета

Linux: `./sm-relay init --config relay.conf`, затем `./sm-relay check --config relay.conf` и `./sm-relay start --config relay.conf`.
Windows PowerShell: `./sm-relay.exe init --config relay.conf`, затем `./sm-relay.exe check --config relay.conf` и `./sm-relay.exe start --config relay.conf`.

`init` создаёт только новый публичный config, существующий не перезаписывает, секретов не генерирует. Заменить SM_RELAY_ROOT_KEYS на открытый ключ своего доверенного владельца (33 байта HEX с начальным 05), не root.key. Пустой/неверный список не разрешает запуск. Стандартный template сохраняет текущий проектный открытый root, не создаёт нового владельца. Отдельный sm-node-tool выдаёт пропуска офлайн; Windows-версия этого инструмента в H1 не обещана.

`check` только читает и проверяет конфигурацию/заданный guest key, не открывает listener и не измеряет сеть. Успех — exit 0, без вывода. `start` использует тот же parser/проверки, далее прежний loopback сервер. `--help`/`--version` показывают только фиксированную справку/версию, не конфигурацию, ключи или данные трафика. Ошибки краткие, без дампа исходной строки/пути/значений.

## Формат relay.conf v1

UTF-8, допускается начальный BOM/CRLF. Один KEY=VALUE на строку; пустые строки и строки с # пропускаются. Без shell expansion, кавычек, include и подстановок переменных. Неизвестные ключи/дубликаты — ошибка. Файл ≤16 KiB, список roots ≤8, revoked ≤32. Требуются SM_RELAY_CONFIG_VERSION=1 и непустой SM_RELAY_ROOT_KEYS. Общие resource keys — из существующего settings.rs и relay-deploy.md; те же проверки нулей/overflow/совместимости RAM.

SM_RELAY_LISTEN — только loopback, SM_RELAY_MODE — tcp/http. SM_RELAY_GUEST_KEY_FILE необязателен; относительный путь считается от файла config, не CWD. Guest key — отдельный файл ≤128 байт, после trim 64 HEX-знака; его права администратора/профиль deployment не заменяются проверкой config.

В режиме --config источником является **только файл**: SM_RELAY_* из среды и CREDENTIALS_DIRECTORY не смешиваются с ним. Без аргументов сохранён прежний env/systemd запуск. Миграция VPS не обязательна и не происходит автоматически.

## Что не делает этот этап

- Не открывает порт/файрвол/UPnP, не меняет DNS, swap/pagefile/hibernation, службы и автозапуск ОС.
- Не гарантирует strict OS RAM-only на обычном домашнем ПК. Для Windows остаётся блокер приёмки H5; на Linux действуют прежние требования отключения swap/core и внешних лимитов.
- Не создаёт приглашение нового транспортного формата, не обеспечивает удалённую связность сам по себе.
- Не содержит закрытого ключа владельца или преднастроенного стороннего ретранслятора.

## Сборки и происхождение

Реализованы native CI jobs Ubuntu 22.04 x64 (GNU, не musl/static) и Windows 2022 x64 (MSVC, static CRT). sm-relay собирается из Cargo.lock после clippy/tests обеих ОС; archive содержит executable, инструкцию и VERSION.txt с source SHA, SHA256SUMS. SHA256 — проверка целостности, не цифровая подпись. До прохождения релизной процедуры/подписи это CI test artifacts, для изолированного испытания владельцем; не рекомендовать знакомым как готовый публичный релиз. Для распространения portable binaries действует ADR-58/76 без обхода ревью.

## Статус H1: сборка завершена, Windows-зависание устранено

Проверенный source head: `c59c0ef02aeffb8173445ba70583a3623568054b`. [PR #98](https://github.com/lineSence/secure-messenger/pull/98) слит в main, merge `c0e38e674802b56cdac80a5d1f4573adeeee31ec`, только после зелёного ci-ok точного head. [Полный CI](https://github.com/lineSence/secure-messenger/actions/runs/38798808592) — success. Дата лаборатории: 2026-10-15 UTC.

### Скачать испытательный комплект

- [Windows x64, MSVC](https://github.com/lineSence/secure-messenger/actions/runs/38798808592/artifacts/12002568980) — артефакт `sm-relay-test-x86_64-pc-windows-msvc`.
- [Linux x64, GNU](https://github.com/lineSence/secure-messenger/actions/runs/38798808592/artifacts/12002898304) — артефакт `sm-relay-test-x86_64-unknown-linux-gnu`.

GitHub artifact содержит архив комплекта (`.zip` для Windows / `.tar.gz` для Linux) и companion `.sha256`. Распаковать сам комплект; внутри executable, README.txt, VERSION.txt с полным source SHA и SHA256SUMS. Проверять checksum до запуска; SHA256 не подтверждает личность издателя и не заменяет подпись. Retention CI artifacts — 14 дней. Это результат данного CI, не тег/релиз; для распространения людям по-прежнему нужны ADR-58/76 и H5.

### Причина и исправление Windows hang

До исправления диагностический library unit-тест на head `d2750511192157f937029085251e81a0598eea0b` воспроизвёл `shutdown workers=2 turn=0 phases=[10, 1]`: один reactor завершён, второй остался в ветке accept. [Падающий CI](https://github.com/lineSence/secure-messenger/actions/runs/38795812678) и [выдержка](https://github.com/lineSence/secure-messenger/pull/98#issuecomment-6200866850). Ошибка локализована в схеме конкурентного accept копий одного Windows listener в разных Poll; внутренний механизм Winsock/Mio отдельным стеком не установлен, библиотека не заменялась.

Теперь на Windows исходный listener имеет **одного владельца/acceptor**. Уже принятые сокеты распределяются round-robin между несколькими I/O reactors через bounded sync channels; сообщения после установления соединения обрабатываются своим reactor, не через общий dispatcher. Linux сохраняет multi-listener путь. Число workers не ограничено единицей, формат кадров, подписи и клиент не менялись.

- Вместимость каждой очереди — ceil(max_connections/io_workers); места/служебные данные включены в runtime memory reservation. Очередь не растёт бесконечно, try_send не ждёт медленного worker, при full/disconnected пробуются остальные workers, затем конечный отказ.
- Global connection Permit захватывается до handoff, остаётся с pending/active socket и возвращается при отказе, закрытии, истечении или остановке. Deadline начинается при accept и не сбрасывается ожиданием передачи; просроченный pending socket отклоняется.
- Принятые Windows sockets явно nonblocking. Windows half-close с ограниченным drain доступных входных байтов предотвращает потерю error reply при закрытии в проверенных HTTP-сценариях; ожидания peer/неограниченного drain нет.
- Tar executable mode задаётся явно 0755, а не выводится из Windows chmod/stat; в tar не попадают uid/gid/uname runner. Это устранило следующий packaging failure после исправления Rust hang.

### Проверки окончательной версии

| Проверка | Linux Ubuntu 22.04 x64 | Windows Server 2022 x64 |
|---|---|---|
| Strict clippy / полный native relay-набор | success | success |
| Отдельная shutdown-регрессия | 5 × 192 = 960 циклов | 5 × 192 = 960 циклов |
| 1/2/4 workers, idle/partial client, keepalive, остановка с открытыми соединениями | success | success |
| Guest chain/restart/revocation, batch, HTTP 400/pipeline/MAX_BLOB, fairness/stale ACK/WAIT/resilience | success | success |
| 2 Python packaging-теста (ZIP и tar, checksums, no-overwrite) | success | success |
| Release executable build и внешний CLI smoke | success | success |
| Archive / source SHA / checksums / artifact upload | success | success |

Отдельная shutdown-регрессия проходит 64 сценария для каждого из 1/2/4 workers за один запуск; CI повторяет её пять раз, затем запускает полный native-набор с обычной параллельностью. Unit-проверки full/closed handoff возврата permits, истечения queued deadline и overflow memory также включены на Windows. Диагностические phase IDs существуют только под cfg(test) в library unit tests; в исполняемом узле их нет. Никакой telemetry в рабочий процесс не добавлено.

Локально 262 workspace Rust-теста и 2 Python-теста, fmt/strict workspace clippy, Windows cross-clippy, Linux release smoke, Semgrep, actionlint/zizmor и SEK прошли. Native Windows execution и release smoke подтверждены CI, не подменены cross-check. Все остальные CI gates (supply-chain, fuzz, Android, workflows, docs) также success. Падающие тесты не отключались/не сериализовались, защитный 3-минутный лимит проверки не повышался ради зелёного CI.

H1 завершает локальные переносимые комплекты, **не H2–H5**: CGNAT/reverse tunnel, endpoint/identity без домена, NAT traversal, HA, OS RAM-only, службы/автообновление и подписанный публичный релиз остаются вне этой приёмки. Домашние Windows 10/11 и реальные разные сети не объявлены проверенными по тесту Windows Server 2022 CI. ПК/роутеры/VPS и системные настройки пользователя не изменялись; окончательная фиксация узла не выполнена.
