//! Кадры узла для плагина HTTPS в Kotlin (ADR-84, ADR-90): тело запроса и ответа — те же кадры
//! [`sm_core::relay`], что и по TCP. Kotlin кадры не собирает и не разбирает сам — так клиент
//! и узел делят один разбор (`relay.md`).

use sm_core::relay::{self, Command, ID_LEN, MAX_RESPONSE_DATA, RESPONSE_HEADER, Status};

/// Команда запроса к узлу (`sm_core::relay::Command`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RelayCommand {
    /// Создать очередь; ответ — ID получателя и ID отправителя.
    New,
    /// Положить блоб (ID отправителя).
    Send,
    /// Самый старый блоб (ID получателя).
    Fetch,
    /// Удалить блоб с номером (ID получателя).
    Ack,
    /// Удалить очередь (ID получателя).
    Del,
    /// Проверка связи.
    Ping,
    /// Как `Fetch`, но при пустой очереди узел ждёт блоб до 25 с (ADR-82).
    Wait,
    /// Билет для ссылки по пропуску (ADR-146); ответ — билет, 32 байта.
    Ticket,
    /// Обмен билета (в поле ID) на гостевой пропуск (ADR-146); ответ — гостевой пропуск.
    Guest,
}

impl From<RelayCommand> for Command {
    fn from(c: RelayCommand) -> Self {
        match c {
            RelayCommand::New => Self::New,
            RelayCommand::Send => Self::Send,
            RelayCommand::Fetch => Self::Fetch,
            RelayCommand::Ack => Self::Ack,
            RelayCommand::Del => Self::Del,
            RelayCommand::Ping => Self::Ping,
            RelayCommand::Wait => Self::Wait,
            RelayCommand::Ticket => Self::Ticket,
            RelayCommand::Guest => Self::Guest,
        }
    }
}

/// Код ответа узла (`sm_core::relay::Status`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RelayStatus {
    /// Выполнено.
    Ok,
    /// Очередь пуста.
    Empty,
    /// Очереди нет (узел перезапускался или очередь удалена).
    NoQueue,
    /// Блоб больше предела.
    TooBig,
    /// Превышен предел узла.
    Quota,
    /// Неверный запрос.
    BadRequest,
    /// Узел временно недоступен.
    Unavailable,
    /// Пропуск на `NEW` не принят (ADR-136).
    Denied,
}

impl From<Status> for RelayStatus {
    fn from(s: Status) -> Self {
        match s {
            Status::Ok => Self::Ok,
            Status::Empty => Self::Empty,
            Status::NoQueue => Self::NoQueue,
            Status::TooBig => Self::TooBig,
            Status::Quota => Self::Quota,
            Status::BadRequest => Self::BadRequest,
            Status::Unavailable => Self::Unavailable,
            Status::Denied => Self::Denied,
        }
    }
}

/// Ответ узла.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RelayReply {
    /// Код.
    pub status: RelayStatus,
    /// Данные: для `New` — два ID по 32 байта, для `Fetch`/`Wait` — номер очереди в списке (u8),
    /// номер блоба u64 BE и блоб; для `NoQueue` — номер пропавшей очереди в списке.
    pub data: Vec<u8>,
}

/// Кадр запроса. `None` — ID не 32 байта или данные не подходят команде.
/// Для `New`, `Ticket` и `Ping` ID не используется — передают 32 нуля; для `Guest` в ID — билет. Для `Fetch`/`Wait` данные — ещё
/// до 63 ID очередей подряд (ADR-114).
#[uniffi::export]
#[must_use]
pub fn relay_request(cmd: RelayCommand, id: &[u8], data: &[u8]) -> Option<Vec<u8>> {
    let id = <[u8; ID_LEN]>::try_from(id).ok()?;
    relay::encode_request(cmd.into(), &id, data)
}

/// Самое длинное тело ответа узла: заголовок и данные (номер блоба и блоб). Длиннее — не узел.
#[uniffi::export]
#[must_use]
pub fn relay_reply_max() -> u32 {
    u32::try_from(RESPONSE_HEADER.saturating_add(MAX_RESPONSE_DATA)).unwrap_or(u32::MAX)
}

/// Разбирает тело ответа — ровно один кадр. `None` — чужая версия или код, длина в заголовке
/// не совпала с телом или больше предела.
#[uniffi::export]
#[must_use]
pub fn relay_reply_parse(body: &[u8]) -> Option<RelayReply> {
    let (head, data) = body.split_at_checked(RESPONSE_HEADER)?;
    let head = <&[u8; RESPONSE_HEADER]>::try_from(head).ok()?;
    let (status, len) = relay::parse_response_header(head).ok()?;
    (data.len() == len).then(|| RelayReply {
        status: status.into(),
        data: data.to_vec(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_matches_core() {
        let id = [7u8; 32];
        assert_eq!(
            relay_request(RelayCommand::Send, &id, b"blob"),
            relay::encode_request(Command::Send, &id, b"blob")
        );
        assert_eq!(
            relay_request(RelayCommand::Wait, &id, b"").map(|f| f[1]),
            Some(7)
        );
        assert_eq!(relay_request(RelayCommand::Send, &id[..31], b"blob"), None);
        assert_eq!(relay_request(RelayCommand::Ack, &id, b"short"), None);
        assert_eq!(relay_request(RelayCommand::Wait, &id, b"x"), None);
        // Билет в поле ID, данных нет; пропуск для билета — владельца или гостевой.
        assert_eq!(
            relay_request(RelayCommand::Guest, &id, b"").map(|f| f[1]),
            Some(9)
        );
        assert_eq!(relay_request(RelayCommand::Guest, &id, b"x"), None);
        assert!(relay_request(RelayCommand::Ticket, &id, &[0; 91]).is_some());
        assert!(relay_request(RelayCommand::Ticket, &id, &[0; 107]).is_some());
        assert_eq!(relay_request(RelayCommand::Ticket, &id, &[0; 100]), None);
    }

    #[test]
    fn reply_max_fits_largest_reply() {
        let big = vec![0u8; MAX_RESPONSE_DATA];
        let body = relay::encode_response(Status::Ok, &big).unwrap();
        assert_eq!(usize::try_from(relay_reply_max()).unwrap(), body.len());
    }

    #[test]
    fn reply_parses_exactly_one_frame() {
        let body = relay::encode_response(Status::Ok, b"data").unwrap();
        assert_eq!(
            relay_reply_parse(&body),
            Some(RelayReply {
                status: RelayStatus::Ok,
                data: b"data".to_vec()
            })
        );
        let empty = relay::encode_response(Status::Empty, b"").unwrap();
        assert_eq!(
            relay_reply_parse(&empty).map(|r| r.status),
            Some(RelayStatus::Empty)
        );
        let mut long = body.clone();
        long.push(0);
        assert_eq!(relay_reply_parse(&long), None);
        assert_eq!(relay_reply_parse(&body[..body.len() - 1]), None);
        assert_eq!(relay_reply_parse(&body[..3]), None);
        let mut bad = body;
        bad[0] = 9;
        assert_eq!(relay_reply_parse(&bad), None);
    }
}
