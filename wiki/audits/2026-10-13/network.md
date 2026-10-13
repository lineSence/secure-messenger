# Сетевой аудит: восстановленный отчёт

Дата: 2026-10-13. Проверенный commit: `c0c9059b041a787a2d2ac4aa1823b99ed1aaee68`.

**Происхождение:** сетевой субагент сохранил независимые заметки, рабочие PoC и логи, но его сессия завершилась `failed` до финального ответа. Координатор восстановил этот документ из этих артефактов, перепроверил ниже указанные исходники и повторно запустил шесть PoC. Это не финальный ответ исходного аудитора и не свидетельство полного завершения всех запланированных проверок.

## Краткий результат

| ID | Уровень координатора | Результат |
|---|---|---|
| NET-1 | High | Пустой SEND создаёт неудаляемую для текущего Android receive голову очереди; серверная часть воспроизведена по TCP и HTTP |
| NET-2 | Medium | Первая постоянно непустая очередь голодает остальные; воспроизведено на настоящем сервере; объединено с STO-7 |
| NET-3 | Medium | BLE слоты можно удерживать без успешной аутентификации частей; доказано по коду, аппаратный тест не выполнен |
| NET-5 | Medium | Beacon не ротируется при удалении контакта; объединено с SIG-1 |
| NET-6 | Medium | Raw Decline отменяет Started invitation; объединено с SIG-2; формат воспроизведён, Android переход проверен по коду |
| NET-7 | Medium | LinkCard раскрывает устойчивый identity и обратный адрес узлу; roundtrip формата воспроизведён |
| NET-4 | Гипотеза, не в числе находок | Небюджетированные очереди raw PairLink callbacks; практический ANR/OOM не измерен |
| NET-8 | Ограничение, не в числе находок | Read timeout без общего deadline для HTTPS; атака требует контроля pinned endpoint, runtime не проверен |

## NET-1 — Пустой блоб блокирует очередь

**Уверенность:** высокая в несовместимости сервера и клиента. **Граница теста:** настоящий sm-relay по loopback TCP и HTTP, но не Android/HTTPS/TLS.

Сервер допускает `SEND` с пустыми данными и хранит его как обычную голову FIFO. FETCH/WAIT возвращает `[индекс очереди:1][seq:8]` без данных — ровно 9 байт. Клиент требует более 9 байт, бросает исключение до извлечения seq и до ACK. Голова остаётся на сервере; последующие правильные сообщения не выдаются. Повторы чтения и перезапуск клиента не удаляют эту голову. Это конечная блокировка до серверного TTL/административного восстановления или удаления очереди, не обещание вечного удержания без TTL.

**Предпосылки:** знание sender ID существующей очереди (например, законный или удалённый контакт, обладатель открытой ссылки, скомпрометированный узел); guessing 256-bit capability не заявляется. Signal key, подпись владельца и доступ к Android не нужны. Даже один пустой SEND достаточен. В пакетном receive могут задержаться и другие очереди; масштаб зависит от порядка/разбиения списка. High — оценка доступности и устойчивости single-packet DoS, не утверждение о раскрытии ключей и не CVSS.

**Источники:** [crates/sm-relay/src/store.rs:300–331](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/store.rs#L300-L331), [crates/sm-relay/src/store.rs:334–365](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/store.rs#L334-L365), [android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt:103–116](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L103-L116), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:220–269](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L220-L269).

**PoC:** создать очередь на тестовом сервере; послать пустой SEND, затем обычный блоб; четыре FETCH каждый раз дают 9 байт. Только ручной ACK первого seq позволяет прочитать второй блоб. Прогон выполнен по TCP и HTTP. Код теста ниже.

**Исправление:** запретить пустой blob на сервере и на клиентских SEND API; клиент должен уметь безопасно разбирать корректный envelope с пустым payload и подтверждать окончательно невалидный input по известному seq, не подтверждая temporary storage failure. Учесть уже сохранённые пустые головы. Регрессия должна проверять доставку следующего валидного кадра, а не только отказ нового SEND.

## NET-2 — Голодание других очередей

`fetch_any` возвращает первую непустую очередь, а клиент сдвигает round лишь на EMPTY. Воспроизведено 200 итераций refill первой очереди с ожиданием легитимного блоба во второй. Storage-аудитор независимо подтвердил 1000 итераций на Store/извлечённом fetch_any.

**Предпосылки:** sender capability первой очереди и скорость refill не ниже чтения. Конечный burst даёт задержку, не бесконечный DoS. **Исправление:** fair round-robin и ограниченный бюджет на очередь/итерацию; продвигать cursor и после успешного получения. Подробности: STO-7 в [storage.md](storage.md).

**Источники:** [crates/sm-relay/src/server.rs:402–421](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-relay/src/server.rs#L402-L421), [android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt:220–269](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/RelayHub.kt#L220-L269).

## NET-3 — Удержание BLE слотов неаутентифицированным трафиком

BleLink вызывает `slotFor(address)` до `ChunkReassembler.push`/AEAD. Уже существующему адресу `at` обновляется сразу, даже если последующая криптографическая проверка отвергнет часть. Шесть соседей, присылающих мусор чаще 5 секунд, способны не дать вытеснить свои слоты. PairLink создаёт максимум шесть Peer при CONNECTED и удаляет их при disconnect, без проверки подлинности контакта и без idle eviction в этом пути.

**Предпосылки:** несколько BLE-соединений поблизости и доступность GATT; фактический предел одновременных соединений/поведение радио зависит от устройства. Не доказано, что один обычный телефон создаст все нужные адреса. **Статус:** порядок обновления lease и отсутствие eviction проверены по коду; реальные телефоны не использовались.

**Влияние:** отказ приёма BLE либо добавления контакта в ограниченном окне, без взлома внутреннего Signal. **Исправление:** не продлевать lease только от отвергнутого AEAD; отделить bounded provisional pool от аутентифицированных peers; disconnect/idle timeout и admission budget. Начальное provisional место всё равно нужно до проверки первой части — безлимитный предварительный пул не подходит.

**Источники:** [android/app/src/main/java/dev/sm/messenger/ble/BleLink.kt:317–355](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/ble/BleLink.kt#L317-L355), [android/app/src/main/java/dev/sm/messenger/ble/BleLink.kt:561–565](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/ble/BleLink.kt#L561-L565), [android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:365–378](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L365-L378), [android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:580](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L580).

## NET-5 / NET-6 — Независимые совпадения

NET-5: сохранённый общий beacon доступен удалённому контакту; ключ будущих меток и внешних частей остаётся прежним. Это SIG-1 в [signal.md](signal.md), не повторно посчитанная находка.

NET-6: `Decline` — один байт `04`; PoC подтвердил encode/decode без proof. При Started Android удаляет invitation и незавершённую сессию. Это SIG-2 в [signal.md](signal.md). Тест формата сам по себе не является полным сетевым exploit; переход проверен координатором по коду.

**Источники:** [crates/sm-signal/src/pairing.rs:257–260](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/pairing.rs#L257-L260), [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:377–381](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L377-L381).

## NET-7 — LinkCard не скрывает identity и обратный адрес от relay

Открывающий ссылку создаёт `linkCardFrame`, который содержит карточку с открытым identity key, proof, sender ID обратной очереди и node record; затем посылает frame через relay. HMAC доказывает знание секрета, но не шифрует поля. TLS скрывает это от стороннего сетевого наблюдателя, не от обслуживающего узла.

**Предпосылки:** контроль/изъятие обслуживающего relay либо наблюдение plaintext внутри него. **Влияние:** устойчивый псевдоним invitee связывается с приглашением, обратной очередью и узлом; возможна корреляция последующих обменов. Это не раскрытие текста и не социальная идентификация человека без дополнительных данных; «нет логов» не препятствует чтению живой памяти/трафика узла. Оценка Medium относится к заявленной защите метаданных, а не к отсутствию E2E у текста.

**Тест:** оригинальный PairFrame::LinkCard encode/decode восстановил identity, sender ID и node без какого-либо секрета. Android сетевой путь прослежен по исходникам.

**Источники:** [android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt:263–269](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/InviteFlow.kt#L263-L269), [crates/sm-signal/src/pairing.rs:176–191](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/crates/sm-signal/src/pairing.rs#L176-L191).

**Исправление:** защищать pre-session payload отдельным AEAD-конвертом с корректным KDF/context из секрета приглашения; получатель должен владеть секретом, которого не имеет узел. Для открытого v1 необходимо честно описать его bearer/metadata ограничения, а не обещать защиту от узла, уже узнавшего секрет. Проверить, какие поля обязаны оставаться для маршрутизации, и версионировать формат через ADR.

## NET-4 / NET-8 — Не подтверждённые эксплуатационные сценарии

- **NET-4:** PairLink копирует сырые callbacks и ставит их в main queue до проверок; Pairing использует singleThreadExecutor. В этих местах нет аналога InboundBudget. Однако объём, радио-flow-control и фактический ANR/OOM не измерены. Это кандидат на bounded admission/flood test, не подтверждённая High. Источники: [android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:408–431](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L408-L431), [android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt:287–298](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/contacts/PairLink.kt#L287-L298).
- **NET-8:** HttpsRelay ставит connect/read timeout и bounded размер readBody, но не общий wall-clock deadline. Trickle от контролируемого pinned endpoint может занимать worker долго. Возможность «любой MITM делает это» не доказана: системные CA + pinning ограничивают endpoint. HTTP runtime/slow-response test не выполнен; оставлено ограничением availability, не самостоятельной подтверждённой находкой. Источники: [android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt:164–183](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L164-L183), [android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt:210–221](https://github.com/lineSence/secure-messenger/blob/c0c9059b041a787a2d2ac4aa1823b99ed1aaee68/android/app/src/main/java/dev/sm/messenger/relay/HttpsRelay.kt#L210-L221).

## Реальные запуски и ограничения

В изолированной папке сетевого аудитора установлены Rust 1.99 и protoc, использованы закреплённые зависимости. Первый запуск sm-signal был неуспешен из-за отсутствия protoc; после fetch-protoc сборка и проверки прошли. Исходники проверенного commit не правились.

- `cargo test -p sm-core --locked`: 79 тестов.
- `cargo test -p sm-relay --locked`: 17 unit + 7 guest + 7 HTTP + 5 two_clients = 36 тестов.
- `cargo test -p sm-signal --locked`: 54 теста.
- Standalone PoC с зависимостями на исходный checkout: 6 тестов. Координатор повторно выполнил его с `--locked`: все 6 прошли.
- Один из PoC проверяет 30 000 детерминированных buffers в парсерах. Это **smoke test, не coverage-guided fuzzing** и не доказательство отсутствия паник на всех данных.
- BLE replay PoC подтвердил: внешний слой принимает повтор, а Signal отвергает повтор ciphertext. Повторяемость наружного AEAD сама по себе не объявлена поломкой E2E.

Не выполнены аппаратные BLE, Android instrumentation/HTTPS, полный workspace/FFI, Gradle, cargo-deny/audit/vet/OSV, clippy, Semgrep, Kani и cargo-fuzz. Не проверялись живые узлы. Наличие конфигурации CI не выдаётся за её успешный прогон.

Независимые заметки зафиксированы до чтения старых findings. Комментарии кода содержат ссылки на старые исправления, поэтому абсолютного ослепления к истории не было. После сбоя сессии координатор использовал только сохранённые материалы и точечную сверку кода; незавершённые кандидаты не повышались до подтверждённых.

## Архив исходных PoC

Это точный текст успешно проверенного локального стенда. Абсолютный `#[path]` и относительные path dependencies привязаны к временному checkout; при повторении нужно указать свой путь к **тому же SHA**. Стенд не добавлен в production или CI.

### Cargo.toml

```toml
[package]
name = "audit-network-poc"
version = "0.0.0"
edition = "2024"
rust-version = "1.99"
[workspace]
# Строгие линты для всего кода (ADR-15, ADR-20). Менять только через PR с объяснением.
[workspace.lints.rust]
unsafe_code = "forbid"
missing_docs = "warn"
unexpected_cfgs = { level = "warn", check-cfg = ["cfg(kani)"] }

[workspace.lints.clippy]
all = { level = "deny", priority = -1 }
pedantic = { level = "warn", priority = -1 }
# Разбор недоверенных данных: никаких паник и переполнений.
unwrap_used = "deny"
expect_used = "deny"
panic = "deny"
indexing_slicing = "deny"
arithmetic_side_effects = "deny"
cast_possible_truncation = "deny"
cast_possible_wrap = "deny"
cast_sign_loss = "deny"
todo = "deny"
unimplemented = "deny"
dbg_macro = "deny"
print_stdout = "deny"
print_stderr = "deny"
mem_forget = "deny"

[lints]
workspace = true
[dependencies]
sm-core = { path = "../source/crates/sm-core", version = "=0.0.1" }
sm-relay = { path = "../source/crates/sm-relay", version = "=0.0.1" }
sm-signal = { path = "../source/crates/sm-signal", version = "=0.0.1" }
libsignal-core = { version = "=0.1.0", git = "https://github.com/signalapp/libsignal", rev = "4beb029d8a941f81e7d9c6d8af1ed25a677569a8" }
rand = { version = "=0.9.4", default-features = false, features = ["os_rng", "std"] }

```

### src/lib.rs

```rust
//! Локальные PoC: production файлы не изменены. Только loopback.
#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{SocketAddr, TcpStream};
    use std::time::Duration;
    use sm_core::relay::{Command, Status, encode_request, parse_response_header};
    use sm_relay::server::{Config, Server};
    use sm_signal::{DeviceIdentity, contact, pairing::PairFrame, store::{MemKv, Stores}};
    #[path = "/data/audit-network/source/crates/sm-relay/tests/common/mod.rs"]
    mod common;

    fn connect(s: &Server) -> TcpStream {
        let c = TcpStream::connect(s.addr()).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(3))).unwrap(); c
    }
    fn exchange(c: &mut TcpStream, http: bool, cmd: Command, id: &[u8;32], data: &[u8]) -> (Status, Vec<u8>) {
        let f = encode_request(cmd, id, data).unwrap();
        if http { c.write_all(format!("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: {}\r\n\r\n",f.len()).as_bytes()).unwrap(); }
        c.write_all(&f).unwrap();
        if http { let mut head=Vec::new(); while !head.ends_with(b"\r\n\r\n") {let mut b=[0];c.read_exact(&mut b).unwrap();head.push(b[0]);} assert!(head.starts_with(b"HTTP/1.1 200")); }
        let mut h=[0;6];c.read_exact(&mut h).unwrap();let (st,len)=parse_response_header(&h).unwrap();
        let mut body=vec![0;len];c.read_exact(&mut body).unwrap();(st,body)
    }
    #[test]
    fn net1_empty_send_poisons_android_receive_predicate_tcp_and_http() {
        for http in [false,true] {
            let root=common::Root::new();let mut cfg=root.config(Config::new(SocketAddr::from(([127,0,0,1],0))));cfg.http=http;
            let s=Server::start(cfg).unwrap();let mut c=connect(&s);let rid=[1;32];
            let (st,sid)=exchange(&mut c,http,Command::New,&rid,&root.pass(1,10));assert_eq!(st,Status::Ok);let sid:[u8;32]=sid.try_into().unwrap();
            assert_eq!(exchange(&mut c,http,Command::Send,&sid,&[]).0,Status::Ok);
            assert_eq!(exchange(&mut c,http,Command::Send,&sid,b"later legitimate blob").0,Status::Ok);
            for _ in 0..4 {let (st,d)=exchange(&mut c,http,Command::Fetch,&rid,&[]);assert_eq!(st,Status::Ok);assert_eq!(d.len(),9);assert!(d.len()<=1+8);}
            // Android HttpsRelay.kt: <=9 => throw BEFORE seq extraction/ACK.
            // Manual recovery demonstrates later blob was queued but unreachable to that flow.
            exchange(&mut c,http,Command::Ack,&rid,&0u64.to_be_bytes());
            let (_,d)=exchange(&mut c,http,Command::Fetch,&rid,&[]);assert_eq!(&d[9..],b"later legitimate blob");
            drop(c);s.stop();
        }
    }
    #[test]
    fn net2_first_queue_starves_other_queue_200_iterations() {
        let root=common::Root::new();let s=Server::start(root.config(Config::new(SocketAddr::from(([127,0,0,1],0))))).unwrap();let mut c=connect(&s);
        let ids=[[1;32],[2;32]];let mut senders=Vec::new();
        for r in &ids { let (_,sid)=exchange(&mut c,false,Command::New,r,&root.pass(1,10));senders.push(<[u8;32]>::try_from(sid).unwrap()); }
        exchange(&mut c,false,Command::Send,&senders[0],b"junk");exchange(&mut c,false,Command::Send,&senders[1],b"legitimate");
        for _ in 0..200 {
            let (st,d)=exchange(&mut c,false,Command::Fetch,&ids[0],&ids[1]);assert_eq!(st,Status::Ok);assert_eq!(d[0],0);
            exchange(&mut c,false,Command::Ack,&ids[0],&d[1..9]);exchange(&mut c,false,Command::Send,&senders[0],b"junk");
        }
        let (_,d)=exchange(&mut c,false,Command::Fetch,&ids[1],&[]);assert_eq!(&d[9..],b"legitimate");drop(c);s.stop();
    }
    #[test]
    fn net6_decline_is_valid_one_byte_without_proof() {
        assert_eq!(PairFrame::decode(&[4]).unwrap(),PairFrame::Decline);
        assert_eq!(PairFrame::Decline.encode().unwrap(),vec![4]);
    }
    #[test]
    fn net7_linkcard_discloses_identity_and_return_mailbox() {
        let b=DeviceIdentity::generate();let kv=MemKv::default();let stores=Stores::new(&b,&kv);let card=contact::new_card(&stores).unwrap();
        let sender=[5;32];let node=b"signed node record bytes".to_vec();
        let frame=PairFrame::LinkCard{card,proof:[7;32],sender_id:sender,node:node.clone()}.encode().unwrap();
        let PairFrame::LinkCard{card,sender_id,node:found,..}=PairFrame::decode(&frame).unwrap() else {panic!("wrong tag")};
        assert_eq!(contact::card_identity(&card).unwrap(),b.public_key());assert_eq!(sender_id,sender);assert_eq!(found,node);
    }
    #[test]
    fn ble_outer_replay_accepted_inner_signal_replay_rejected() {
        let a=DeviceIdentity::generate();let b=DeviceIdentity::generate();let akv=MemKv::default();let bkv=MemKv::default();let aa=Stores::new(&a,&akv);let bb=Stores::new(&b,&bkv);
        let card=contact::new_card(&bb).unwrap();let st=contact::start_session(&aa,&card,b"hi").unwrap();
        let key=sm_signal::ble_part::contact_key(&[6;32]).unwrap();let frame=sm_core::chunk::encode_frame(&sm_core::transport::MailboxId([0;32]),&st.ciphertext).unwrap();
        let parts=sm_signal::ble_part::split_sealed(&key,&frame,128).unwrap().unwrap();
        for n in 0..2 {let mut r=sm_core::chunk::Reassembler::new();let mut f=None;for part in &parts {f=r.push(&sm_signal::ble_part::open(&key,part).unwrap()).unwrap();}
            let f=f.unwrap();let (_,blob)=sm_core::chunk::parse_frame(&f).unwrap();let result=contact::accept_session(&bb,blob,Some(&a.public_key()));assert_eq!(result.is_ok(),n==0);}
    }
    #[test]
    fn deterministic_parser_smoke_30000_buffers() {
        let mut x=12345u64;
        for i in 0..30000 {let mut b=vec![0u8;i%512];for a in &mut b {x=x.wrapping_mul(6364136223846793005).wrapping_add(1);*a=(x>>32) as u8;}
            let _=sm_core::message::Message::decode(&b);let _=sm_core::node::NodeRecord::decode(&b);let _=sm_core::padding::unpad(&b);let _=PairFrame::decode(&b);
            let _=sm_core::pass::QueuePass::parse(&b);let _=sm_core::pass::GuestPass::parse(&b);let _=sm_relay::http::parse_head(&b);
            if let Ok(h)=<[u8;38]>::try_from(b.as_slice()){let _=sm_core::relay::parse_request_header(&h);}
            let mut r=sm_core::chunk::Reassembler::new();let _=r.push(&b);
        }
    }
}

```
