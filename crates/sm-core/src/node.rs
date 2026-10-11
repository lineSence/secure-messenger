//! Запись узла (ADR-81, ADR-86): как клиенту достучаться до своего узла по HTTPS (ADR-84, ADR-90).
//!
//! Здесь только формат тела и проверка полей — без криптографии: подпись корневым ключом
//! владельца ставит и проверяет `sm-signal::node`. Формат тела (числа — big-endian):
//!
//! ```text
//! [версия u8 = 2][выпущена u64, с][действует до u64, с][порт u16][длина пути u8][путь]
//! [доменов u8]{[длина u8][домен][пинов u8]{[SHA-256 SPKI 32 байта]}}
//! [отозванных пропусков u8]{[ID пропуска 16 байт]}
//! ```
//!
//! У каждого домена свой ключ TLS и свои пины — основной и запасной (ADR-135); отозванные
//! пропуска на `NEW` — ADR-136.
//!
//! Разбор строгий и канонический: тело, собранное заново, совпадает с разобранным байт в байт.
//! Домены — имена DNS из `a-z0-9-` (без IP, порта, пользователя), путь — `/` и `A-Za-z0-9-_`.

use crate::wire::{Reader, WireError};

/// Версия формата тела: 2 — пины по доменам и отозванные пропуска (ADR-135, ADR-136).
pub const VERSION: u8 = 2;
/// Длина открытого корневого ключа владельца: `0x05` ‖ ключ Curve25519.
pub const ROOT_PUBLIC_LEN: usize = 33;
/// Открытые корневые ключи владельца (ADR-81, ADR-127): ими клиент проверяет запись узла
/// (`sm-signal::node`), а узел — пропуск на `NEW` (ADR-136). Пока пусто — ни запись, ни пропуск
/// не принимаются; ключ добавляет владелец после `sm-node-tool keygen`.
pub const ROOT_KEYS: &[[u8; ROOT_PUBLIC_LEN]] = &[];
/// Доменов на узел (ADR-86 — 2–3; запас на ротацию).
pub const MAX_DOMAINS: usize = 8;
/// Пинов на домен: основной и запасной ключ, запас на смену.
pub const MAX_PINS: usize = 4;
/// Отозванных пропусков в записи: с ними текст записи укладывается в
/// `message::MAX_NODE_TEXT`.
pub const MAX_REVOKED: usize = 32;
/// Длина пина — SHA-256.
pub const PIN_LEN: usize = 32;
/// Самый длинный путь.
pub const MAX_PATH: usize = 128;
/// Самое длинное имя домена (RFC 1035).
pub const MAX_HOST: usize = 253;
/// Самая длинная метка домена.
const MAX_LABEL: usize = 63;

/// Запись узла без подписи.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NodeRecord {
    /// Когда выпущена, секунды Unix: из двух верных записей берут более новую.
    pub issued: u64,
    /// До какого момента действует, секунды Unix; позже — не принимается.
    pub expires: u64,
    /// Порт HTTPS.
    pub port: u16,
    /// Секретный путь API (ADR-90).
    pub path: String,
    /// Домены с тем же адресом (ADR-86), у каждого — свои пины (ADR-135).
    pub domains: Vec<NodeDomain>,
    /// Отозванные пропуска на `NEW` (ADR-136).
    pub revoked: Vec<[u8; crate::pass::ID_LEN]>,
}

/// Домен узла и пины его сертификата.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NodeDomain {
    /// Имя DNS.
    pub name: String,
    /// SHA-256 ключей (SPKI) сертификата: основной и запасной (ADR-72, ADR-135).
    pub pins: Vec<[u8; PIN_LEN]>,
}

/// Имя DNS: метки `a-z0-9-` через точку, не с `-` по краям; последняя метка — не число (не IP).
#[must_use]
pub fn is_host_name(s: &str) -> bool {
    let b = s.as_bytes();
    if b.is_empty() || b.len() > MAX_HOST {
        return false;
    }
    let mut labels = 0usize;
    let mut last_numeric = false;
    for label in b.split(|&c| c == b'.') {
        let ok = !label.is_empty()
            && label.len() <= MAX_LABEL
            && label.first() != Some(&b'-')
            && label.last() != Some(&b'-')
            && label
                .iter()
                .all(|&c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-');
        if !ok {
            return false;
        }
        labels = labels.saturating_add(1);
        last_numeric = label.iter().all(u8::is_ascii_digit);
    }
    labels >= 2 && !last_numeric
}

/// Путь: начинается с `/`, длина 2…[`MAX_PATH`], только `/`, `A-Za-z0-9`, `-`, `_`.
#[must_use]
pub fn is_path(s: &str) -> bool {
    let b = s.as_bytes();
    (2..=MAX_PATH).contains(&b.len())
        && b.first() == Some(&b'/')
        && b.iter()
            .all(|&c| c.is_ascii_alphanumeric() || c == b'/' || c == b'-' || c == b'_')
}

impl NodeRecord {
    /// Поля допустимы: домены и путь по правилам выше, порт не 0, домены и отозванные не
    /// повторяются, 1…[`MAX_DOMAINS`] доменов, у каждого 1…[`MAX_PINS`] пинов, не больше
    /// [`MAX_REVOKED`] отозванных, срок позже выпуска.
    #[must_use]
    pub fn is_valid(&self) -> bool {
        let names_unique = self
            .domains
            .iter()
            .enumerate()
            .all(|(i, d)| !self.domains.iter().take(i).any(|e| e.name == d.name));
        let revoked_unique = self
            .revoked
            .iter()
            .enumerate()
            .all(|(i, r)| !self.revoked.iter().take(i).any(|e| e == r));
        self.port != 0
            && self.expires > self.issued
            && is_path(&self.path)
            && (1..=MAX_DOMAINS).contains(&self.domains.len())
            && self
                .domains
                .iter()
                .all(|d| is_host_name(&d.name) && (1..=MAX_PINS).contains(&d.pins.len()))
            && names_unique
            && self.revoked.len() <= MAX_REVOKED
            && revoked_unique
    }

    /// Тело записи. `None` — поля недопустимы ([`NodeRecord::is_valid`]).
    #[must_use]
    pub fn encode(&self) -> Option<Vec<u8>> {
        if !self.is_valid() {
            return None;
        }
        let mut out = Vec::new();
        out.push(VERSION);
        out.extend_from_slice(&self.issued.to_be_bytes());
        out.extend_from_slice(&self.expires.to_be_bytes());
        out.extend_from_slice(&self.port.to_be_bytes());
        put_u8_len(&mut out, self.path.as_bytes())?;
        out.push(u8::try_from(self.domains.len()).ok()?);
        for d in &self.domains {
            put_u8_len(&mut out, d.name.as_bytes())?;
            out.push(u8::try_from(d.pins.len()).ok()?);
            for p in &d.pins {
                out.extend_from_slice(p);
            }
        }
        out.push(u8::try_from(self.revoked.len()).ok()?);
        for r in &self.revoked {
            out.extend_from_slice(r);
        }
        Some(out)
    }

    /// Разбирает тело записи целиком.
    ///
    /// # Errors
    /// [`WireError`] — другая версия, обрыв, лишние байты или недопустимые поля.
    pub fn decode(body: &[u8]) -> Result<Self, WireError> {
        let mut r = Reader::new(body);
        if r.u8()? != VERSION {
            return Err(WireError::BadValue);
        }
        let issued = r.u64()?;
        let expires = r.u64()?;
        let port = r.u16()?;
        let path = read_text(&mut r, MAX_PATH)?;
        let n = count(&mut r, MAX_DOMAINS)?;
        let mut domains = Vec::with_capacity(n);
        for _ in 0..n {
            let name = read_text(&mut r, MAX_HOST)?;
            let k = count(&mut r, MAX_PINS)?;
            let mut pins = Vec::with_capacity(k);
            for _ in 0..k {
                pins.push(r.array::<PIN_LEN>()?);
            }
            domains.push(NodeDomain { name, pins });
        }
        let n = count(&mut r, MAX_REVOKED)?;
        let mut revoked = Vec::with_capacity(n);
        for _ in 0..n {
            revoked.push(r.array()?);
        }
        r.finish()?;
        let rec = Self {
            issued,
            expires,
            port,
            path,
            domains,
            revoked,
        };
        if rec.is_valid() {
            Ok(rec)
        } else {
            Err(WireError::BadValue)
        }
    }
}

/// Число элементов `u8`, не больше `max`.
fn count(r: &mut Reader<'_>, max: usize) -> Result<usize, WireError> {
    let n = usize::from(r.u8()?);
    if n > max {
        return Err(WireError::TooLong { len: n, max });
    }
    Ok(n)
}

fn put_u8_len(out: &mut Vec<u8>, b: &[u8]) -> Option<()> {
    out.push(u8::try_from(b.len()).ok()?);
    out.extend_from_slice(b);
    Some(())
}

/// Строка `[длина u8][ASCII]`; что именно допустимо — решает [`NodeRecord::is_valid`].
fn read_text(r: &mut Reader<'_>, max: usize) -> Result<String, WireError> {
    let len = usize::from(r.u8()?);
    if len > max {
        return Err(WireError::TooLong { len, max });
    }
    let b = r.bytes(len)?;
    if !b.is_ascii() {
        return Err(WireError::BadText);
    }
    String::from_utf8(b.to_vec()).map_err(|_| WireError::BadText)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> NodeRecord {
        NodeRecord {
            issued: 1_790_000_000,
            expires: 1_797_776_000,
            port: 443,
            path: "/k9_x-Q2".into(),
            domains: vec![
                NodeDomain {
                    name: "a.example.org".into(),
                    pins: vec![[1; 32], [2; 32]],
                },
                NodeDomain {
                    name: "b-2.example.net".into(),
                    pins: vec![[3; 32], [4; 32]],
                },
            ],
            revoked: vec![[9; 16]],
        }
    }

    #[test]
    fn roundtrip_is_canonical() {
        let r = sample();
        let body = r.encode().unwrap();
        assert_eq!(NodeRecord::decode(&body), Ok(r.clone()));
        assert_eq!(NodeRecord::decode(&body).unwrap().encode().unwrap(), body);
    }

    #[test]
    fn rejects_truncated_trailing_and_version() {
        let body = sample().encode().unwrap();
        for n in 0..body.len() {
            assert!(NodeRecord::decode(&body[..n]).is_err(), "обрыв на {n}");
        }
        let mut long = body.clone();
        long.push(0);
        assert_eq!(NodeRecord::decode(&long), Err(WireError::TrailingBytes(1)));
        let mut v1 = body;
        v1[0] = 1;
        assert_eq!(NodeRecord::decode(&v1), Err(WireError::BadValue));
    }

    /// Самая большая запись с подписью в HEX (`SMN1:` + HEX) укладывается в сообщение
    /// «адрес почты».
    #[test]
    fn largest_record_fits_mailbox_message() {
        let label = "a".repeat(63);
        let r = NodeRecord {
            path: format!("/{}", "p".repeat(MAX_PATH - 1)),
            domains: (0..MAX_DOMAINS)
                .map(|i| NodeDomain {
                    name: format!("{i}{}.{label}.{label}.{}", &label[1..], &label[..61]),
                    pins: vec![[0; 32]; MAX_PINS],
                })
                .collect(),
            revoked: (0..MAX_REVOKED)
                .map(|i| [u8::try_from(i).unwrap(); 16])
                .collect(),
            ..sample()
        };
        assert!(r.domains.iter().all(|d| d.name.len() == MAX_HOST));
        let body = r.encode().unwrap();
        assert!(5 + 2 * (body.len() + 64) <= crate::message::MAX_NODE_TEXT);
    }

    #[test]
    fn host_names() {
        for ok in ["a.example", "x-1.y2.example.org", "1a.example"] {
            assert!(is_host_name(ok), "{ok}");
        }
        for bad in [
            "",
            "example",
            "1.2.3.4",
            "A.example",
            "-a.example",
            "a-.example",
            "a..example",
            "a.example.",
            "user@a.example",
            "a.example:443",
            "a_b.example",
            "а.example",
        ] {
            assert!(!is_host_name(bad), "{bad}");
        }
        assert!(!is_host_name(&format!("{}.example", "a".repeat(64))));
    }

    #[test]
    fn paths() {
        assert!(is_path("/a"));
        assert!(is_path("/k9_x-Q2/v1"));
        for bad in ["", "/", "a", "/a?b", "/a#b", "/%2e", "/../a", "/a b"] {
            assert!(!is_path(bad), "{bad}");
        }
        assert!(!is_path(&format!("/{}", "a".repeat(MAX_PATH))));
    }

    #[test]
    fn invalid_fields_do_not_encode_or_decode() {
        let cases: Vec<fn(&mut NodeRecord)> = vec![
            |r| r.port = 0,
            |r| r.expires = r.issued,
            |r| r.path = "nope".into(),
            |r| r.domains.clear(),
            |r| r.domains.push(r.domains[0].clone()),
            |r| {
                r.domains = (0..=MAX_DOMAINS)
                    .map(|i| NodeDomain {
                        name: format!("d{i}.example"),
                        pins: vec![[0; 32]],
                    })
                    .collect();
            },
            |r| r.domains[1].pins.clear(),
            |r| r.domains[0].pins = vec![[0; 32]; MAX_PINS + 1],
            |r| r.domains[0].name = "10.0.0.1".into(),
            |r| r.revoked.push([9; 16]),
            |r| {
                r.revoked = (0..=MAX_REVOKED)
                    .map(|i| [u8::try_from(i).unwrap(); 16])
                    .collect();
            },
        ];
        for (i, f) in cases.into_iter().enumerate() {
            let mut r = sample();
            f(&mut r);
            assert_eq!(r.encode(), None, "случай {i}");
        }
        // Недопустимое поле в байтах тоже не проходит разбор: порт 0.
        let mut body = sample().encode().unwrap();
        body[17] = 0;
        body[18] = 0;
        assert_eq!(NodeRecord::decode(&body), Err(WireError::BadValue));
    }
}
