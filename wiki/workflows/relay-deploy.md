# Запуск узла `sm-relay` на VPS (шаг 2 плана MVP)

Архитектура и требования — `wiki/architecture/relay.md`; факты о хостинге — `wiki/references/server-hosting.md`. Провайдер не фиксируется (решение владельца 2026-10-09).

## 1. Выбор VPS (делает владелец)
- Не санкционный провайдер, сервер вне России (ADR-9), ≤ $10/мес (R-15).
- Linux с systemd ≥ 247 (Debian 12 / Ubuntu 24.04), 1 vCPU, от 1 ГиБ RAM, 1 IPv4.
- Вход только по SSH-ключу. Агенту нужны: IP, имя пользователя с sudo, отпечаток ключа хоста (из панели провайдера — сверить при первом входе).

## 2. Подготовка сервера
```
sudo apt update && sudo apt full-upgrade -y && sudo apt install -y unattended-upgrades
sudo swapoff -a && sudo sed -i '/\sswap\s/s/^/#/' /etc/fstab   # блобы не попадают на диск
swapon --show                                                   # пусто
# sshd: PasswordAuthentication no, PermitRootLogin no → sudo systemctl reload ssh
```
Файрвол: входящий — только 22/tcp. Порт 7400 наружу не открывается (узел и так слушает только 127.0.0.1).

## 3. Сборка и установка
Бинарник собирается из `main` при зелёном `ci-ok`, на любой Linux-машине (не на сервере: компилятор там не нужен):
```
rustup target add x86_64-unknown-linux-musl
cargo build --release --locked -p sm-relay --target x86_64-unknown-linux-musl
sha256sum target/x86_64-unknown-linux-musl/release/sm-relay
scp target/x86_64-unknown-linux-musl/release/sm-relay crates/sm-relay/deploy/sm-relay.service USER@HOST:
```
На сервере:
```
sha256sum sm-relay                                # совпадает с локальным
sudo install -m 0755 sm-relay /usr/local/bin/sm-relay
sudo install -m 0644 sm-relay.service /etc/systemd/system/sm-relay.service
sudo systemctl daemon-reload && sudo systemctl enable --now sm-relay
systemctl is-active sm-relay                      # active
systemd-analyze security sm-relay                 # оценка песочницы, для отчёта
```

## 4. Проверка
- PING (на сервере или через туннель): ответ `01 00 00 00 00 00` (версия 1, `OK`, длина 0). Проверено локально 2026-10-10.
```
python3 -c "import socket;s=socket.create_connection(('127.0.0.1',7400));s.sendall(bytes([1,6])+bytes(36));print(s.recv(6).hex(' '))"
```
- Туннель для клиента: `ssh -N -L 7400:127.0.0.1:7400 USER@HOST`, далее клиент ходит на `127.0.0.1:7400`.
- Критерии шага 2: два клиента обмениваются блобами через туннель; `sudo systemctl restart sm-relay` → очередей нет (`FETCH` → `NO_QUEUE`); счёт провайдера ≤ $10/мес.
- Логов нет: `journalctl -u sm-relay` содержит только старт/стоп от systemd.

## 5. Обновление и переезд
Повторить шаг 3 (`systemctl restart` стирает очереди — клиенты создают их заново с теми же ID по своим пропускам, ADR-134, relay.md). Отзыв пропуска: добавить его ID в `Environment=SM_RELAY_REVOKED=ID1,ID2` (`sudo systemctl edit sm-relay`) и перезапустить узел. Переезд к другому провайдеру — шаги 2–4 на новом сервере, старый удалить.

## 6. Запись узла (владелец, ADR-81, ADR-127)
Инструмент — `crates/sm-node-tool`, запускать на своём компьютере; закрытый корневой ключ не кладётся ни на сервер, ни в репозиторий, ни в облако.
1. Один раз: `cargo run --release -p sm-node-tool -- keygen root.key` — файл `root.key` (права 0600, существующий не перезаписывается) и открытый ключ. Открытый ключ (строка «Для sm-signal ROOT_KEYS») добавить в `ROOT_KEYS` в `crates/sm-core/src/node.rs` через PR — это не секрет. Копию `root.key` хранить офлайн (USB в надёжном месте). Сделано владельцем 2026-10-12 (открытый ключ в `ROOT_KEYS`, #78).
2. Пины — на каждый домен свои (у каждого домена свой ключ и сертификат, ADR-135): основной — ключ сертификата Let's Encrypt этого домена (certbot с `--reuse-key`), запасной — заранее созданный ключ на случай смены:
```
openssl x509 -in /etc/letsencrypt/live/ДОМЕН/cert.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out backup.key
openssl pkey -in backup.key -pubout -outform der | openssl dgst -sha256
```
3. Подписать: `sm-node-tool sign root.key --domain ДОМЕН1 --pin ПИН1_ОСН --pin ПИН1_ЗАП --domain ДОМЕН2 --pin ПИН2_ОСН --pin ПИН2_ЗАП --port 443 --path /СЕКРЕТНЫЙ_ПУТЬ [--revoke ID_ПРОПУСКА …] --days 90` — `--pin` относится к последнему `--domain` перед ним; `--revoke` — ID отозванного пропуска (до 32, тот же список — в `SM_RELAY_REVOKED` на узле, §5). Печатает `SMN1:…` (это и есть запись: её текстом или QR получают свои).
4. Проверить: `sm-node-tool verify ОТКРЫТЫЙ_КЛЮЧ 'SMN1:…'` — печатает поля и срок.
5. До конца срока — выпустить новую запись (приложение берёт более новую по «выпущена»).
6. Пропуск на создание очередей (ADR-136): `sm-node-tool pass root.key --per-hour 20 --days 365` — печатает `SMP1:…` и ID пропуска (ID записать: по нему отзыв). Пропуск получают свои вместе с записью узла; один пропуск — на человека, чтобы отзывать по одному. Отзыв — §5.
