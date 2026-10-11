//! Инструмент владельца для записи узла (ADR-81, ADR-115). Запускать на своём компьютере
//! без сети; закрытый корневой ключ не должен попадать ни на сервер, ни в репозиторий.
//!
//! ```text
//! sm-node-tool keygen <файл>          новый корневой ключ; печатает открытый для ROOT_KEYS
//! sm-node-tool sign <файл> --domain <имя> [--domain …] --port <N> --path </путь>
//!              --pin <HEX 64> [--pin …] --days <N>      печатает запись SMN1:…
//! sm-node-tool verify <открытый HEX> <SMN1:…>           проверяет запись и печатает поля
//! ```
//!
//! Пин — SHA-256 ключа (SPKI) сертификата узла; его считает `openssl` (`relay-deploy.md`).

use std::fs::OpenOptions;
use std::io::{self, Write as _};
use std::os::unix::fs::OpenOptionsExt as _;
use std::process::ExitCode;
use std::time::{SystemTime, UNIX_EPOCH};

use sm_core::node::{NodeRecord, PIN_LEN};
use sm_signal::node::{self, ROOT_PRIVATE_LEN, ROOT_PUBLIC_LEN};

const SECONDS_PER_DAY: u64 = 86_400;
const MAX_DAYS: u64 = 3_650;

fn hex(b: &[u8]) -> String {
    use std::fmt::Write as _;
    b.iter().fold(String::new(), |mut s, x| {
        let _ = write!(s, "{x:02X}");
        s
    })
}

fn unhex(s: &str) -> Option<Vec<u8>> {
    let s = s.trim().as_bytes();
    s.chunks(2)
        .map(|p| match p {
            [h, l] => {
                let h = char::from(*h).to_digit(16)?;
                let l = char::from(*l).to_digit(16)?;
                u8::try_from(h.checked_mul(16)?.checked_add(l)?).ok()
            }
            _ => None,
        })
        .collect()
}

fn now() -> Result<u64, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .map_err(|_| "часы компьютера раньше 1970 года".into())
}

fn out(line: &str) -> Result<(), String> {
    writeln!(io::stdout().lock(), "{line}").map_err(|e| e.to_string())
}

fn keygen(path: &str) -> Result<(), String> {
    let (private, public) = node::root_keygen();
    // Только владелец читает файл; уже существующий не перезаписываем.
    let mut f = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .map_err(|e| format!("{path}: {e}"))?;
    writeln!(f, "{}", hex(&private)).map_err(|e| e.to_string())?;
    f.sync_all().map_err(|e| e.to_string())?;
    out(&format!("Открытый корневой ключ: {}", hex(&public)))?;
    let bytes: Vec<String> = public.iter().map(|b| format!("0x{b:02x}")).collect();
    out(&format!(
        "Для sm-signal ROOT_KEYS:\n    [{}],",
        bytes.join(", ")
    ))
}

fn read_private(path: &str) -> Result<Vec<u8>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    let key = unhex(&text).ok_or("файл ключа — не HEX")?;
    if key.len() != ROOT_PRIVATE_LEN {
        return Err("файл ключа — не закрытый ключ".into());
    }
    Ok(key)
}

fn sign(path: &str, args: &[String]) -> Result<(), String> {
    let mut domains = Vec::new();
    let mut pins = Vec::new();
    let (mut port, mut route, mut days) = (None, None, None);
    let mut it = args.iter();
    while let Some(flag) = it.next() {
        let value = it.next().ok_or(format!("{flag}: нет значения"))?;
        match flag.as_str() {
            "--domain" => domains.push(value.clone()),
            "--port" => port = Some(value.parse::<u16>().map_err(|_| "--port: число 1–65535")?),
            "--path" => route = Some(value.clone()),
            "--pin" => {
                let pin: [u8; PIN_LEN] = unhex(value)
                    .and_then(|p| p.try_into().ok())
                    .ok_or("--pin: 64 знака HEX (SHA-256)")?;
                pins.push(pin);
            }
            "--days" => days = Some(value.parse::<u64>().map_err(|_| "--days: число")?),
            _ => return Err(format!("неизвестный флаг {flag}")),
        }
    }
    let days = days.ok_or("нужен --days")?;
    if days == 0 || days > MAX_DAYS {
        return Err(format!("--days: 1–{MAX_DAYS}"));
    }
    let issued = now()?;
    let expires = days
        .checked_mul(SECONDS_PER_DAY)
        .and_then(|s| issued.checked_add(s))
        .ok_or("--days: слишком много")?;
    let record = NodeRecord {
        issued,
        expires,
        port: port.ok_or("нужен --port")?,
        path: route.ok_or("нужен --path")?,
        domains,
        pins,
    };
    if !record.is_valid() {
        return Err(
            "поля записи недопустимы: домены a-z0-9-, путь /A-Za-z0-9-_, 1–8 доменов, 1–4 пина"
                .into(),
        );
    }
    let text = node::sign(&record, &read_private(path)?).map_err(|e| format!("подпись: {e:?}"))?;
    out(&text)
}

fn verify(public: &str, text: &str) -> Result<(), String> {
    let root: [u8; ROOT_PUBLIC_LEN] = unhex(public)
        .and_then(|k| k.try_into().ok())
        .ok_or("открытый ключ — 66 знаков HEX")?;
    let r = node::open(text, &[root], now()?).map_err(|e| format!("запись не принята: {e:?}"))?;
    out(&format!(
        "Выпущена: {} (Unix)\nДействует до: {} (Unix)",
        r.issued, r.expires
    ))?;
    out(&format!("Порт: {}\nПуть: {}", r.port, r.path))?;
    for d in &r.domains {
        out(&format!("Домен: {d}"))?;
    }
    for p in &r.pins {
        out(&format!("Пин: {}", hex(p)))?;
    }
    Ok(())
}

fn run(args: &[String]) -> Result<(), String> {
    match args {
        [cmd, file] if cmd == "keygen" => keygen(file),
        [cmd, file, rest @ ..] if cmd == "sign" => sign(file, rest),
        [cmd, key, text] if cmd == "verify" => verify(key, text),
        _ => Err("использование: sm-node-tool keygen <файл> | sign <файл> --domain … --port … --path … --pin … --days … | verify <ключ> <запись>".into()),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            let _ = writeln!(io::stderr().lock(), "{e}");
            ExitCode::FAILURE
        }
    }
}
