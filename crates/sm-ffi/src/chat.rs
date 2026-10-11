//! Сообщения 1:1 для Kotlin (пункт 6 плана 0.0.1-alpha): сборка, выравнивание, расшифровка.
//!
//! Отправка: [`chat_encode`] (формат `sm_core::message` + `padding::pad`) → очередь
//! `OutboxQueue` хранит **открытое** выровненное сообщение (на диске — под `sm.store`) →
//! на каждой попытке [`SignalSessions::encrypt`]. Шифруем при каждой попытке, а не один раз:
//! libsignal отвергает повтор того же шифротекста, и потерянное подтверждение навсегда
//! оставило бы сообщение недоставленным; новый шифротекст получатель расшифрует, дубль
//! отсечёт `SeenWindow` по id и подтвердит снова (ADR-40).
//!
//! Приём: [`SignalSessions::open`] — расшифровка ([`contact::decrypt_from_any`]) →
//! `padding::unpad` → `message::parse`.

use sm_core::message::{Body, Message, MessageId};
use sm_core::padding;
use sm_signal::contact;

use crate::signal::{SignalFfiError, SignalSessions};

/// Длина ящика (`MailboxId`) и ключа личности без байта типа.
const MAILBOX_LEN: usize = 32;

/// Тело сообщения для Kotlin.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ChatBody {
    /// Текст.
    Text {
        /// Текст в UTF-8, не длиннее `message::MAX_TEXT`.
        text: String,
    },
    /// Подтверждение доставки сообщения `target` (ADR-40).
    Delivered {
        /// Id доставленного сообщения (16 байт).
        target: Vec<u8>,
    },
    /// Адрес почты контакта на узле (ADR-82): куда класть ему сообщения.
    Mailbox {
        /// ID отправителя его входящей очереди (32 байта).
        sender_id: Vec<u8>,
        /// Запись узла `SMN1:…`; проверять `node_record_open` перед использованием.
        node: String,
    },
    /// Тип, который эта версия приложения пока не показывает (ответ, реакция, …).
    Other {
        /// Код типа.
        kind: u16,
    },
}

/// Сообщение для Kotlin.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatMessage {
    /// Id (16 байт).
    pub id: Vec<u8>,
    /// Время отправки по часам отправителя, мс UNIX. Недоверенное.
    pub sent_at_ms: u64,
    /// Содержимое.
    pub body: ChatBody,
}

/// Расшифрованное сообщение и его отправитель.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OpenedMessage {
    /// Адрес контакта-отправителя.
    pub contact: String,
    /// Сообщение.
    pub message: ChatMessage,
}

fn id16(b: &[u8]) -> Result<MessageId, SignalFfiError> {
    Ok(MessageId(
        b.try_into().map_err(|_| SignalFfiError::BadInput)?,
    ))
}

/// Новый id сообщения (16 случайных байт).
#[uniffi::export]
#[must_use]
pub fn message_id_new() -> Vec<u8> {
    contact::new_message_id().to_vec()
}

/// Собирает и выравнивает сообщение — это кладётся в очередь и шифруется на каждой попытке.
///
/// # Errors
/// [`SignalFfiError::BadInput`] — id не 16 байт, текст длиннее предела, тип `Other`.
#[uniffi::export]
pub fn chat_encode(message: ChatMessage) -> Result<Vec<u8>, SignalFfiError> {
    let body = match message.body {
        ChatBody::Text { text } => Body::Text(text),
        ChatBody::Delivered { target } => Body::Delivered {
            target: id16(&target)?,
        },
        ChatBody::Mailbox { sender_id, node } => Body::Mailbox {
            sender_id: sender_id
                .as_slice()
                .try_into()
                .map_err(|_| SignalFfiError::BadInput)?,
            node,
        },
        ChatBody::Other { .. } => return Err(SignalFfiError::BadInput),
    };
    let m = Message {
        id: id16(&message.id)?,
        sent_at_ms: message.sent_at_ms,
        body,
    };
    let encoded = m.encode().map_err(|_| SignalFfiError::BadInput)?;
    padding::pad(&encoded).map_err(|_| SignalFfiError::BadInput)
}

/// Ящик контакта для очереди и окна дублей: его ключ личности без байта типа (32 байта).
///
/// # Errors
/// [`SignalFfiError::BadInput`] — не адрес контакта.
#[uniffi::export]
pub fn contact_mailbox(contact: &str) -> Result<Vec<u8>, SignalFfiError> {
    // Адрес — ключ личности в hex: `05` (тип Curve25519) + 32 байта.
    let hex = contact
        .strip_prefix("05")
        .filter(|h| h.len() == MAILBOX_LEN * 2)
        .ok_or(SignalFfiError::BadInput)?;
    hex.as_bytes()
        .as_chunks::<2>()
        .0
        .iter()
        .map(|&[hi, lo]| Some(nibble(hi)?.checked_mul(16)? | nibble(lo)?))
        .collect::<Option<Vec<u8>>>()
        .ok_or(SignalFfiError::BadInput)
}

/// Цифра hex; только строчные — как пишет `contact_id`.
fn nibble(c: u8) -> Option<u8> {
    if c.is_ascii_uppercase() {
        return None;
    }
    char::from(c)
        .to_digit(16)
        .and_then(|d| u8::try_from(d).ok())
}

fn to_chat(m: Message) -> ChatMessage {
    let body = match m.body {
        Body::Text(text) => ChatBody::Text { text },
        Body::Delivered { target } => ChatBody::Delivered {
            target: target.0.to_vec(),
        },
        Body::Mailbox { sender_id, node } => ChatBody::Mailbox {
            sender_id: sender_id.to_vec(),
            node,
        },
        Body::Reply { .. } => ChatBody::Other {
            kind: sm_core::message::KIND_REPLY,
        },
        Body::Reaction { .. } => ChatBody::Other {
            kind: sm_core::message::KIND_REACTION,
        },
        Body::Diagnostic(_) => ChatBody::Other {
            kind: sm_core::message::KIND_DIAGNOSTIC,
        },
        Body::Unknown { kind, .. } => ChatBody::Other { kind },
    };
    ChatMessage {
        id: m.id.0.to_vec(),
        sent_at_ms: m.sent_at_ms,
        body,
    }
}

#[uniffi::export]
impl SignalSessions {
    /// Расшифровывает кадр одного из контактов `candidates` (отправитель кадра Bluetooth не
    /// подписан — пробуем по очереди; сначала — те, кто сейчас рядом). `None` — не от контакта,
    /// подделка или повтор того же шифротекста.
    ///
    /// # Errors
    /// [`SignalFfiError::BadInput`] — кадр не разобрался или расшифрованное — не сообщение
    /// (сессия при этом уже сдвинулась: отправитель с ошибкой); [`SignalFfiError::Store`].
    #[expect(
        clippy::needless_pass_by_value,
        reason = "UniFFI передаёт списки только по значению"
    )]
    pub fn open(
        &self,
        candidates: Vec<String>,
        ciphertext: &[u8],
    ) -> Result<Option<OpenedMessage>, SignalFfiError> {
        let Some((contact, plain)) = self.decrypt_from_any(&candidates, ciphertext)? else {
            return Ok(None);
        };
        let unpadded = padding::unpad(&plain).map_err(|_| SignalFfiError::BadInput)?;
        let m = Message::decode(unpadded).map_err(|_| SignalFfiError::BadInput)?;
        Ok(Some(OpenedMessage {
            contact,
            message: to_chat(m),
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_round_trips_through_padding() {
        let m = ChatMessage {
            id: message_id_new(),
            sent_at_ms: 1_700_000_000_000,
            body: ChatBody::Text {
                text: "привет".into(),
            },
        };
        let buf = chat_encode(m.clone()).unwrap();
        assert_eq!(buf.len(), padding::MIN_BUCKET);
        let back = Message::decode(padding::unpad(&buf).unwrap()).unwrap();
        assert_eq!(to_chat(back), m);

        let ack = ChatMessage {
            id: message_id_new(),
            sent_at_ms: 1,
            body: ChatBody::Delivered { target: m.id },
        };
        let back = Message::decode(padding::unpad(&chat_encode(ack.clone()).unwrap()).unwrap());
        assert_eq!(to_chat(back.unwrap()), ack);

        let mailbox = ChatMessage {
            id: message_id_new(),
            sent_at_ms: 2,
            body: ChatBody::Mailbox {
                sender_id: vec![4; 32],
                node: "SMN1:00".into(),
            },
        };
        let back = Message::decode(padding::unpad(&chat_encode(mailbox.clone()).unwrap()).unwrap());
        assert_eq!(to_chat(back.unwrap()), mailbox);
        let short_id = ChatMessage {
            body: ChatBody::Mailbox {
                sender_id: vec![4; 31],
                node: "SMN1:00".into(),
            },
            ..mailbox
        };
        assert_eq!(chat_encode(short_id), Err(SignalFfiError::BadInput));
    }

    #[test]
    fn bad_messages_are_rejected() {
        let text = |id: Vec<u8>, t: String| ChatMessage {
            id,
            sent_at_ms: 0,
            body: ChatBody::Text { text: t },
        };
        assert!(chat_encode(text(vec![0; 15], "a".into())).is_err());
        let long = "a".repeat(sm_core::message::MAX_TEXT + 1);
        assert!(chat_encode(text(vec![0; 16], long)).is_err());
        let other = ChatMessage {
            id: vec![0; 16],
            sent_at_ms: 0,
            body: ChatBody::Other { kind: 2 },
        };
        assert!(chat_encode(other).is_err());
        let ack = ChatMessage {
            id: vec![0; 16],
            sent_at_ms: 0,
            body: ChatBody::Delivered {
                target: vec![1; 17],
            },
        };
        assert!(chat_encode(ack).is_err());
        assert_ne!(message_id_new(), message_id_new());
    }

    #[test]
    fn mailbox_is_identity_without_type_byte() {
        let key: Vec<u8> = core::iter::once(5).chain(1..=32).collect();
        let id = sm_signal::contact::contact_id(&key);
        assert_eq!(contact_mailbox(&id).unwrap(), key[1..]);
        assert!(contact_mailbox(&id.to_uppercase()).is_err());
        assert!(contact_mailbox(&id[..64]).is_err());
        assert!(contact_mailbox(&format!("06{}", &id[2..])).is_err());
        assert!(contact_mailbox(&format!("{}zz", &id[..64])).is_err());
    }
}
