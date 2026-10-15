# Источники поставок событийного runtime

Проверено на 2026-10-15; относится к пакету reactor/shards, не к HA.

- [Mio 1.2.4 metadata](https://crates.io/api/v1/crates/mio/1.2.4): дата публикации 2026-10-03, published_by id 6025 (Thomasdezeeuw). В проекте отключены default features, включены net/os-poll; Linux feature tree содержит libc, без log от Mio.
- [Bytecode Alliance audits/trust](https://raw.githubusercontent.com/bytecodealliance/wasmtime/main/supply-chain/audits.toml) и [Mozilla](https://raw.githubusercontent.com/mozilla/supply-chain/main/audits.toml), на 2026-10-15: опубликовано доверие Mio user-id 6025 по критерию safe-to-deploy. Локальная запись ограничена интервалом 2026-10-03–2026-10-04, не распространяется на будущие релизы.
- [wasi 0.11.1+wasi-snapshot-preview1 metadata](https://crates.io/api/v1/crates/wasi/0.11.1+wasi-snapshot-preview1): дата публикации 2025-06-10, user-id 1 (alexcrichton). Существующее доверие издателю wasi есть в тех же импортируемых источниках; локальный интервал сужен до 2025-06-10–2025-06-11.
- Проверка `cargo vet --locked` нового графа: 90 fully audited, 7 partially audited, 91 ранее существовавших exempted; новых exemptions не добавлялось. Доверие издателю не является утверждением о независимом полном аудите релиза. Cargo-vet/остальные проверки CI не отключались.
