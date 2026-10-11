//! Формат сообщения v1 (ADR-14, ADR-24) — открытый текст внутри сквозного шифрования.
//!
//! ```text
//! [версия: u8 = 1][тип: u16 BE][id сообщения: 16 байт][время отправки, мс UNIX: u64 BE][тело]
//! ```
//!
//! Тело зависит от типа:
//! - `1` текст — UTF-8 до [`MAX_TEXT`] байт;
//! - `2` ответ — `[id исходного: 16][текст]`;
//! - `3` реакция — `[id исходного: 16][эмодзи: UTF-8 до MAX_REACTION байт]`, пустая — снять реакцию;
//! - `4` отчёт диагностики (ADR-19) — непрозрачные байты до [`MAX_DIAGNOSTIC`];
//! - `5` подтверждение доставки (ADR-40) — ровно `[id доставленного: 16]`;
//! - `6` адрес почты (ADR-82 п.1) — `[ID отправителя очереди на узле: 32][запись узла]`: куда
//!   класть сообщения этому контакту, когда его нет рядом; запись узла — текст `SMN1:…` (ADR-81),
//!   печатные ASCII до [`MAX_NODE_TEXT`] байт, подпись проверяет приложение (`sm-signal::node`);
//! - другой тип — [`Body::Unknown`]: сохраняется как есть, интерфейс предлагает обновиться.
//!
//! Кодирование каноническое: `decode(x) == Ok(m)` ⇒ `m.encode() == Ok(x)`.
//! Новая версия формата (`версия != 1`) не разбирается — [`MessageError::UnsupportedVersion`].

use core::fmt;

use crate::wire::{self, Reader, WireError};

/// Версия формата.
pub const VERSION: u8 = 1;
/// Размер заголовка: версия + тип + id + время.
pub const HEADER_LEN: usize = 1 + 2 + 16 + 8;
/// Максимум текста в байтах.
pub const MAX_TEXT: usize = 16 * 1024;
/// Максимум реакции в байтах.
pub const MAX_REACTION: usize = 32;
/// Максимум отчёта диагностики.
pub const MAX_DIAGNOSTIC: usize = 8 * 1024;
/// Максимум текста записи узла в адресе почты: тело записи с пределами `node` и подпись в HEX —
/// около 4,8 КБ, с запасом.
pub const MAX_NODE_TEXT: usize = 8 * 1024;
/// Длина ID очереди на узле (`relay::ID_LEN`).
pub const QUEUE_ID_LEN: usize = 32;
/// Максимум тела неизвестного типа.
pub const MAX_UNKNOWN: usize = 64 * 1024;
/// Максимальный размер закодированного сообщения.
pub const MAX_MESSAGE: usize = HEADER_LEN + MAX_UNKNOWN;

/// Тип: текст.
pub const KIND_TEXT: u16 = 1;
/// Тип: ответ.
pub const KIND_REPLY: u16 = 2;
/// Тип: реакция.
pub const KIND_REACTION: u16 = 3;
/// Тип: отчёт диагностики.
pub const KIND_DIAGNOSTIC: u16 = 4;
/// Тип: подтверждение доставки (ADR-40).
pub const KIND_DELIVERED: u16 = 5;
/// Тип: адрес почты на узле (ADR-82).
pub const KIND_MAILBOX: u16 = 6;

/// Идентификатор сообщения: 16 случайных байт от отправителя.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct MessageId(pub [u8; 16]);

/// Тело сообщения. `Debug` не показывает содержимое (открытый текст не попадает в логи).
#[derive(Clone, PartialEq, Eq)]
pub enum Body {
    /// Обычный текст.
    Text(String),
    /// Ответ на сообщение.
    Reply {
        /// На что отвечаем.
        target: MessageId,
        /// Текст ответа.
        text: String,
    },
    /// Реакция на сообщение; пустая строка — снять реакцию.
    Reaction {
        /// На что реакция.
        target: MessageId,
        /// Эмодзи.
        emoji: String,
    },
    /// Отчёт диагностики (формат — шаг 6).
    Diagnostic(Vec<u8>),
    /// Подтверждение доставки: получатель принял сообщение `target` (ADR-40).
    Delivered {
        /// Какое сообщение доставлено.
        target: MessageId,
    },
    /// Адрес почты на узле: класть сообщения отправителю этого сообщения (ADR-82).
    Mailbox {
        /// ID отправителя его входящей очереди.
        sender_id: [u8; QUEUE_ID_LEN],
        /// Запись узла `SMN1:…` (не проверена).
        node: String,
    },
    /// Тип, который эта версия не знает.
    Unknown {
        /// Код типа.
        kind: u16,
        /// Тело как есть.
        body: Vec<u8>,
    },
}

/// Сообщение.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    /// Идентификатор.
    pub id: MessageId,
    /// Время отправки по часам отправителя, мс от эпохи UNIX. Недоверенное.
    pub sent_at_ms: u64,
    /// Содержимое.
    pub body: Body,
}

/// Ошибка формата сообщения.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageError {
    /// Повреждённые данные.
    Wire(WireError),
    /// Версия формата новее или старше поддерживаемой.
    UnsupportedVersion(u8),
    /// Код типа зарезервирован (0) или не совпадает с телом.
    BadKind(u16),
}

impl From<WireError> for MessageError {
    fn from(e: WireError) -> Self {
        Self::Wire(e)
    }
}

impl fmt::Debug for Body {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let len = match self {
            Self::Text(t) | Self::Reply { text: t, .. } | Self::Reaction { emoji: t, .. } => {
                t.len()
            }
            Self::Diagnostic(b) | Self::Unknown { body: b, .. } => b.len(),
            Self::Delivered { .. } => 0,
            Self::Mailbox { node, .. } => node.len(),
        };
        redacted(f, self.kind(), len)
    }
}

impl fmt::Debug for BodyRef<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let (kind, len) = match *self {
            Self::Text(t) => (KIND_TEXT, t.len()),
            Self::Reply { text, .. } => (KIND_REPLY, text.len()),
            Self::Reaction { emoji, .. } => (KIND_REACTION, emoji.len()),
            Self::Diagnostic(b) => (KIND_DIAGNOSTIC, b.len()),
            Self::Delivered { .. } => (KIND_DELIVERED, 0),
            Self::Mailbox { node, .. } => (KIND_MAILBOX, node.len()),
            Self::Unknown { kind, body } => (kind, body.len()),
        };
        redacted(f, kind, len)
    }
}

fn redacted(f: &mut fmt::Formatter<'_>, kind: u16, len: usize) -> fmt::Result {
    write!(f, "Body {{ kind: {kind}, len: {len}, .. }}")
}

impl Body {
    /// Код типа.
    #[must_use]
    pub const fn kind(&self) -> u16 {
        match self {
            Self::Text(_) => KIND_TEXT,
            Self::Reply { .. } => KIND_REPLY,
            Self::Reaction { .. } => KIND_REACTION,
            Self::Diagnostic(_) => KIND_DIAGNOSTIC,
            Self::Delivered { .. } => KIND_DELIVERED,
            Self::Mailbox { .. } => KIND_MAILBOX,
            Self::Unknown { kind, .. } => *kind,
        }
    }
}

const fn is_known(kind: u16) -> bool {
    matches!(
        kind,
        KIND_TEXT | KIND_REPLY | KIND_REACTION | KIND_DIAGNOSTIC | KIND_DELIVERED | KIND_MAILBOX
    )
}

/// Текст записи узла: 1..=[`MAX_NODE_TEXT`] печатных ASCII без пробела.
fn node_text(b: &[u8]) -> Result<&str, WireError> {
    if b.len() > MAX_NODE_TEXT {
        return Err(WireError::TooLong {
            len: b.len(),
            max: MAX_NODE_TEXT,
        });
    }
    if b.is_empty() || !b.iter().all(u8::is_ascii_graphic) {
        return Err(WireError::BadText);
    }
    core::str::from_utf8(b).map_err(|_| WireError::BadText)
}

fn target(r: &mut Reader<'_>) -> Result<MessageId, WireError> {
    r.array().map(MessageId)
}

/// Тело сообщения без копирования: ссылки во входной буфер. `Debug` не показывает содержимое.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum BodyRef<'a> {
    /// Текст.
    Text(&'a str),
    /// Ответ.
    Reply {
        /// На что отвечаем.
        target: MessageId,
        /// Текст ответа.
        text: &'a str,
    },
    /// Реакция.
    Reaction {
        /// На что реакция.
        target: MessageId,
        /// Эмодзи.
        emoji: &'a str,
    },
    /// Отчёт диагностики.
    Diagnostic(&'a [u8]),
    /// Подтверждение доставки.
    Delivered {
        /// Какое сообщение доставлено.
        target: MessageId,
    },
    /// Адрес почты на узле.
    Mailbox {
        /// ID отправителя его входящей очереди.
        sender_id: [u8; QUEUE_ID_LEN],
        /// Запись узла `SMN1:…` (не проверена).
        node: &'a str,
    },
    /// Неизвестный тип.
    Unknown {
        /// Код типа.
        kind: u16,
        /// Тело как есть.
        body: &'a [u8],
    },
}

/// Разобранное сообщение без копирования.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MessageRef<'a> {
    /// Идентификатор.
    pub id: MessageId,
    /// Время отправки, мс UNIX. Недоверенное.
    pub sent_at_ms: u64,
    /// Содержимое.
    pub body: BodyRef<'a>,
}

/// Разбирает сообщение без выделения памяти. Именно эта функция проверяется Kani.
///
/// # Errors
/// [`MessageError`], если буфер повреждён, слишком длинный или версия не поддерживается.
pub fn parse(buf: &[u8]) -> Result<MessageRef<'_>, MessageError> {
    if buf.len() > MAX_MESSAGE {
        return Err(WireError::TooLong {
            len: buf.len(),
            max: MAX_MESSAGE,
        }
        .into());
    }
    let mut r = Reader::new(buf);
    let version = r.u8()?;
    if version != VERSION {
        return Err(MessageError::UnsupportedVersion(version));
    }
    let kind = r.u16()?;
    let id = MessageId(r.array()?);
    let sent_at_ms = r.u64()?;
    let body = match kind {
        0 => return Err(MessageError::BadKind(0)),
        KIND_TEXT => BodyRef::Text(wire::text(r.rest(MAX_TEXT)?, MAX_TEXT)?),
        KIND_REPLY => {
            let target = target(&mut r)?;
            let text = wire::text(r.rest(MAX_TEXT)?, MAX_TEXT)?;
            BodyRef::Reply { target, text }
        }
        KIND_REACTION => {
            let target = target(&mut r)?;
            let emoji = wire::text(r.rest(MAX_REACTION)?, MAX_REACTION)?;
            BodyRef::Reaction { target, emoji }
        }
        KIND_DIAGNOSTIC => BodyRef::Diagnostic(r.rest(MAX_DIAGNOSTIC)?),
        KIND_DELIVERED => BodyRef::Delivered {
            target: target(&mut r)?,
        },
        KIND_MAILBOX => {
            let sender_id = r.array()?;
            let node = node_text(r.rest(MAX_NODE_TEXT)?)?;
            BodyRef::Mailbox { sender_id, node }
        }
        other => BodyRef::Unknown {
            kind: other,
            body: r.rest(MAX_UNKNOWN)?,
        },
    };
    r.finish()?;
    Ok(MessageRef {
        id,
        sent_at_ms,
        body,
    })
}

impl MessageRef<'_> {
    /// Копирует в [`Message`].
    #[must_use]
    pub fn to_owned_message(&self) -> Message {
        let body = match self.body {
            BodyRef::Text(t) => Body::Text(t.to_owned()),
            BodyRef::Reply { target, text } => Body::Reply {
                target,
                text: text.to_owned(),
            },
            BodyRef::Reaction { target, emoji } => Body::Reaction {
                target,
                emoji: emoji.to_owned(),
            },
            BodyRef::Diagnostic(d) => Body::Diagnostic(d.to_vec()),
            BodyRef::Delivered { target } => Body::Delivered { target },
            BodyRef::Mailbox { sender_id, node } => Body::Mailbox {
                sender_id,
                node: node.to_owned(),
            },
            BodyRef::Unknown { kind, body } => Body::Unknown {
                kind,
                body: body.to_vec(),
            },
        };
        Message {
            id: self.id,
            sent_at_ms: self.sent_at_ms,
            body,
        }
    }
}

impl Message {
    /// Разбирает сообщение из расшифрованного буфера ([`parse`] + копирование).
    ///
    /// # Errors
    /// [`MessageError`], если буфер повреждён, слишком длинный или версия не поддерживается.
    pub fn decode(buf: &[u8]) -> Result<Self, MessageError> {
        parse(buf).map(|m| m.to_owned_message())
    }

    /// Кодирует сообщение, проверяя те же ограничения, что и [`Message::decode`].
    ///
    /// # Errors
    /// [`MessageError`], если поле длиннее предела, текст недопустим или
    /// `Unknown` использует код известного типа.
    pub fn encode(&self) -> Result<Vec<u8>, MessageError> {
        let kind = self.body.kind();
        let mut out = Vec::with_capacity(HEADER_LEN.saturating_add(64));
        out.push(VERSION);
        out.extend_from_slice(&kind.to_be_bytes());
        out.extend_from_slice(&self.id.0);
        out.extend_from_slice(&self.sent_at_ms.to_be_bytes());
        match &self.body {
            Body::Text(t) => out.extend_from_slice(wire::text(t.as_bytes(), MAX_TEXT)?.as_bytes()),
            Body::Reply { target, text } => {
                out.extend_from_slice(&target.0);
                out.extend_from_slice(wire::text(text.as_bytes(), MAX_TEXT)?.as_bytes());
            }
            Body::Reaction { target, emoji } => {
                out.extend_from_slice(&target.0);
                out.extend_from_slice(wire::text(emoji.as_bytes(), MAX_REACTION)?.as_bytes());
            }
            Body::Diagnostic(d) => out.extend_from_slice(Reader::new(d).rest(MAX_DIAGNOSTIC)?),
            Body::Delivered { target } => out.extend_from_slice(&target.0),
            Body::Mailbox { sender_id, node } => {
                out.extend_from_slice(sender_id);
                out.extend_from_slice(node_text(node.as_bytes())?.as_bytes());
            }
            Body::Unknown { kind, body } => {
                if *kind == 0 || is_known(*kind) {
                    return Err(MessageError::BadKind(*kind));
                }
                out.extend_from_slice(Reader::new(body).rest(MAX_UNKNOWN)?);
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: MessageId = MessageId([7; 16]);
    const T: MessageId = MessageId([9; 16]);

    fn msg(body: Body) -> Message {
        Message {
            id: ID,
            sent_at_ms: 1_791_000_000_000,
            body,
        }
    }

    #[test]
    fn roundtrip_all_kinds() {
        for body in [
            Body::Text("Привет".into()),
            Body::Text(String::new()),
            Body::Reply {
                target: T,
                text: "да".into(),
            },
            Body::Reaction {
                target: T,
                emoji: "👍".into(),
            },
            Body::Reaction {
                target: T,
                emoji: String::new(),
            },
            Body::Diagnostic(vec![1, 2, 3]),
            Body::Delivered { target: T },
            Body::Mailbox {
                sender_id: [3; QUEUE_ID_LEN],
                node: "SMN1:0A0B".into(),
            },
            Body::Unknown {
                kind: 900,
                body: vec![5; 10],
            },
        ] {
            let m = msg(body);
            let buf = m.encode().unwrap();
            assert_eq!(Message::decode(&buf), Ok(m.clone()));
        }
    }

    #[test]
    fn mailbox_node_text_is_checked() {
        let m = |node: &str| {
            msg(Body::Mailbox {
                sender_id: [1; QUEUE_ID_LEN],
                node: node.into(),
            })
        };
        for bad in ["", "SMN1: 0A", "SMN1:\u{416}", "a\n"] {
            assert_eq!(m(bad).encode(), Err(MessageError::Wire(WireError::BadText)));
        }
        assert!(m(&"A".repeat(MAX_NODE_TEXT + 1)).encode().is_err());
        let ok = m(&"A".repeat(MAX_NODE_TEXT)).encode().unwrap();
        // Без записи узла (только ID) или с обрезанным ID — не разбирается.
        let short = &ok[..HEADER_LEN + QUEUE_ID_LEN];
        assert_eq!(
            Message::decode(short),
            Err(MessageError::Wire(WireError::BadText))
        );
        assert!(Message::decode(&ok[..HEADER_LEN + 5]).is_err());
        // Узнаваемый тип нельзя прислать как Unknown.
        assert_eq!(
            msg(Body::Unknown {
                kind: KIND_MAILBOX,
                body: vec![]
            })
            .encode(),
            Err(MessageError::BadKind(KIND_MAILBOX))
        );
    }

    #[test]
    fn header_layout_is_fixed() {
        let buf = msg(Body::Text("a".into())).encode().unwrap();
        assert_eq!(buf.len(), HEADER_LEN + 1);
        assert_eq!(buf[..3], [1, 0, 1]);
        assert_eq!(buf[3..19], [7; 16]);
    }

    #[test]
    fn rejects_bad_input() {
        let good = msg(Body::Text("a".into())).encode().unwrap();
        for n in 0..HEADER_LEN {
            assert_eq!(
                Message::decode(&good[..n]),
                Err(MessageError::Wire(WireError::Truncated))
            );
        }
        let mut v2 = good.clone();
        v2[0] = 2;
        assert_eq!(
            Message::decode(&v2),
            Err(MessageError::UnsupportedVersion(2))
        );
        let mut k0 = good.clone();
        k0[2] = 0;
        assert_eq!(Message::decode(&k0), Err(MessageError::BadKind(0)));
        let mut bad = good;
        bad.push(0xff);
        assert_eq!(
            Message::decode(&bad),
            Err(MessageError::Wire(WireError::BadText))
        );
    }

    #[test]
    fn enforces_limits_both_ways() {
        let big = "a".repeat(MAX_TEXT + 1);
        assert!(msg(Body::Text(big.clone())).encode().is_err());
        assert!(msg(Body::Text(big[1..].to_owned())).encode().is_ok());
        assert!(
            msg(Body::Reaction {
                target: T,
                emoji: "x".repeat(MAX_REACTION + 1)
            })
            .encode()
            .is_err()
        );
        let mut raw = msg(Body::Text(String::new())).encode().unwrap();
        raw.extend(std::iter::repeat_n(b'a', MAX_TEXT + 1));
        assert!(matches!(
            Message::decode(&raw),
            Err(MessageError::Wire(WireError::TooLong { .. }))
        ));
        assert!(Message::decode(&vec![1; MAX_MESSAGE + 1]).is_err());
    }

    #[test]
    fn delivered_is_exactly_one_id() {
        let good = msg(Body::Delivered { target: T }).encode().unwrap();
        assert_eq!(good.len(), HEADER_LEN + 16);
        assert_eq!(
            Message::decode(&good[..HEADER_LEN + 15]),
            Err(MessageError::Wire(WireError::Truncated))
        );
        let mut long = good;
        long.push(0);
        assert!(Message::decode(&long).is_err());
        assert_eq!(
            msg(Body::Unknown {
                kind: KIND_DELIVERED,
                body: vec![]
            })
            .encode(),
            Err(MessageError::BadKind(KIND_DELIVERED))
        );
    }

    #[test]
    fn unknown_cannot_impersonate_known_kind() {
        assert_eq!(
            msg(Body::Unknown {
                kind: KIND_TEXT,
                body: vec![]
            })
            .encode(),
            Err(MessageError::BadKind(KIND_TEXT))
        );
        assert_eq!(
            msg(Body::Unknown {
                kind: 0,
                body: vec![]
            })
            .encode(),
            Err(MessageError::BadKind(0))
        );
    }

    #[test]
    fn debug_hides_plaintext() {
        let m = msg(Body::Reply {
            target: T,
            text: "секрет".into(),
        });
        let s = format!("{m:?}");
        assert!(!s.contains("секрет"), "{s}");
        assert!(s.contains("kind: 2"), "{s}");
        let buf = m.encode().unwrap();
        let r = format!("{:?}", parse(&buf).unwrap());
        assert!(!r.contains("секрет"), "{r}");
    }

    #[test]
    fn rejects_bidi_override_in_text_and_reply() {
        assert!(msg(Body::Text("a\u{202E}b".into())).encode().is_err());
        assert!(
            msg(Body::Reply {
                target: T,
                text: "\u{2066}".into()
            })
            .encode()
            .is_err()
        );
    }
}
