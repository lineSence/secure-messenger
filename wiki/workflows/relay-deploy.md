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
Повторить шаг 3 (`systemctl restart` стирает очереди — клиенты создают их заново, relay.md, решение 1). Переезд к другому провайдеру — шаги 2–4 на новом сервере, старый удалить.
