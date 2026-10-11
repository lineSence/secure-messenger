//! Минимальный приём HTTP/1.1 от nginx (ADR-90): только то, что шлёт `proxy_pass` на
//! секретном пути. Запрос — `POST / HTTP/1.1`, тело — ровно один кадр [`sm_core::relay`]
//! с `Content-Length`; без `Transfer-Encoding` (chunked), без продолжения строк заголовков.
//! Всё остальное — отказ `400` и закрытие соединения: узел слушает только `127.0.0.1`,
//! и говорить с ним должен только nginx.
//!
//! Разбор — чистая функция над байтами заголовка: её проверяют тесты, фаззер `relay_http`
//! и Kani (`http_head_total` по частям: `http_field_total`, `http_kind_total`, `http_length_total`,
//! `http_close_total`).

use sm_core::relay::{MAX_BLOB, REQUEST_HEADER};

/// Предел заголовка запроса вместе с завершающей пустой строкой.
pub const MAX_HEAD: usize = 2048;
/// Предел числа строк заголовков.
pub const MAX_FIELDS: usize = 32;
/// Самое большое тело: заголовок кадра и самый большой блоб.
pub const MAX_BODY: usize = REQUEST_HEADER + MAX_BLOB;
/// Конец заголовка.
pub const HEAD_END: &[u8] = b"\r\n\r\n";

/// Разобранный заголовок запроса.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Head {
    /// Длина тела, уже проверенная по [`MAX_BODY`].
    pub content_length: usize,
    /// Клиент попросил закрыть соединение после ответа (`Connection: close`).
    pub close: bool,
}

/// Почему запрос отвергнут (на всё — ответ `400` и закрытие).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HttpError {
    /// Не `POST / HTTP/1.1`.
    RequestLine,
    /// Строка заголовка не по правилам: не тот байт, пробел перед `:`, продолжение строки.
    Field,
    /// Строк заголовков больше [`MAX_FIELDS`].
    TooManyFields,
    /// Нет `Content-Length`, их несколько или значение не число.
    ContentLength,
    /// Тело больше [`MAX_BODY`].
    TooBig,
    /// `Transfer-Encoding` (chunked и прочее) не принимается.
    TransferEncoding,
}

/// Позиция начала [`HEAD_END`] в `buf`, если заголовок уже пришёл целиком.
#[must_use]
pub fn head_end(buf: &[u8]) -> Option<usize> {
    buf.windows(HEAD_END.len()).position(|w| w == HEAD_END)
}

/// Символ имени поля (`tchar`, RFC 9110 п. 5.6.2).
const fn is_tchar(b: u8) -> bool {
    b.is_ascii_alphanumeric()
        || matches!(
            b,
            b'!' | b'#'
                | b'$'
                | b'%'
                | b'&'
                | b'\''
                | b'*'
                | b'+'
                | b'-'
                | b'.'
                | b'^'
                | b'_'
                | b'`'
                | b'|'
                | b'~'
        )
}

/// Байт значения поля: видимые ASCII, пробел и табуляция. CR, LF, NUL и не-ASCII — отказ.
const fn is_value_byte(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | 0x21..=0x7e)
}

/// `Content-Length`: только цифры, без знака и пробелов внутри, не больше [`MAX_BODY`].
pub(crate) fn parse_length(v: &[u8]) -> Result<usize, HttpError> {
    if v.is_empty() {
        return Err(HttpError::ContentLength);
    }
    let mut n: usize = 0;
    for &b in v {
        if !b.is_ascii_digit() {
            return Err(HttpError::ContentLength);
        }
        n = n
            .checked_mul(10)
            .and_then(|n| n.checked_add(usize::from(b.wrapping_sub(b'0'))))
            .filter(|&n| n <= MAX_BODY)
            .ok_or(HttpError::TooBig)?;
    }
    Ok(n)
}

/// Есть ли в списке через запятую значение `close` (без учёта регистра).
pub(crate) fn has_close(v: &[u8]) -> bool {
    v.split(|&b| b == b',')
        .any(|t| t.trim_ascii().eq_ignore_ascii_case(b"close"))
}

/// Строка поля без CRLF → (имя, значение без пробелов по краям).
/// Имя — непустое, только `tchar`; значение — только видимые ASCII, пробел и табуляция.
pub(crate) fn field(line: &[u8]) -> Result<(&[u8], &[u8]), HttpError> {
    let colon = line
        .iter()
        .position(|&b| b == b':')
        .ok_or(HttpError::Field)?;
    let (name, value) = line.split_at_checked(colon).ok_or(HttpError::Field)?;
    let value = value.get(1..).ok_or(HttpError::Field)?;
    // Пустое имя, пробел перед `:` или продолжение строки (obs-fold) — не tchar.
    if name.is_empty() || !name.iter().all(|&b| is_tchar(b)) {
        return Err(HttpError::Field);
    }
    if !value.iter().all(|&b| is_value_byte(b)) {
        return Err(HttpError::Field);
    }
    Ok((name, value.trim_ascii()))
}

/// Поля, которые разбор различает; остальные пропускаются.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    ContentLength,
    TransferEncoding,
    Connection,
    Other,
}

/// Имя поля без учёта регистра → [`Kind`].
pub(crate) fn kind(name: &[u8]) -> Kind {
    if name.eq_ignore_ascii_case(b"content-length") {
        Kind::ContentLength
    } else if name.eq_ignore_ascii_case(b"transfer-encoding") {
        Kind::TransferEncoding
    } else if name.eq_ignore_ascii_case(b"connection") {
        Kind::Connection
    } else {
        Kind::Other
    }
}

/// Разбирает заголовок запроса — байты до [`HEAD_END`] (без него).
///
/// # Errors
/// [`HttpError`] — запрос не из узкого подмножества, которое шлёт nginx.
pub fn parse_head(head: &[u8]) -> Result<Head, HttpError> {
    let mut length = None;
    let mut close = false;
    // Строк с начала заголовка, вместе со строкой запроса.
    let mut lines = 0usize;
    let mut start = 0usize;
    // Один проход по байтам: строки делит `\n`, у последней его нет. Без вложенных циклов —
    // так разбор по силам Kani.
    for end in 0..=head.len() {
        let last = match head.get(end) {
            Some(&b'\n') => false,
            Some(_) => continue,
            None => true,
        };
        let line = head.get(start..end).ok_or(HttpError::Field)?;
        start = end.saturating_add(1);
        lines = lines.saturating_add(1);
        if lines == 1 {
            if line != b"POST / HTTP/1.1\r" {
                return Err(HttpError::RequestLine);
            }
            continue;
        }
        if lines > MAX_FIELDS.saturating_add(1) {
            return Err(HttpError::TooManyFields);
        }
        // Строки — только через CRLF; у последней CR нет: его срезал HEAD_END.
        let line = if last {
            line
        } else {
            line.strip_suffix(b"\r").ok_or(HttpError::Field)?
        };
        let (name, value) = field(line)?;
        match kind(name) {
            Kind::ContentLength => {
                if length.is_some() {
                    return Err(HttpError::ContentLength);
                }
                length = Some(parse_length(value)?);
            }
            Kind::TransferEncoding => return Err(HttpError::TransferEncoding),
            Kind::Connection => close |= has_close(value),
            Kind::Other => {}
        }
    }
    Ok(Head {
        content_length: length.ok_or(HttpError::ContentLength)?,
        close,
    })
}

/// Заголовок ответа `200` для тела длиной `len`.
#[must_use]
pub fn ok_head(len: usize, close: bool) -> Vec<u8> {
    format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {len}\r\n\
         Cache-Control: no-store\r\n{}\r\n",
        if close { "Connection: close\r\n" } else { "" }
    )
    .into_bytes()
}

/// Ответ `400` без тела; соединение после него закрывается.
pub const BAD_REQUEST: &[u8] =
    b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

#[cfg(test)]
mod tests {
    use super::*;

    fn head(s: &str) -> Result<Head, HttpError> {
        let b = s.as_bytes();
        let end = head_end(b).unwrap();
        parse_head(&b[..end])
    }

    #[test]
    fn accepts_what_nginx_sends() {
        let h = head(
            "POST / HTTP/1.1\r\nHost: 127.0.0.1:7400\r\nContent-Length: 38\r\n\
             Content-Type: application/octet-stream\r\nConnection: \r\n\r\n",
        );
        assert_eq!(
            h,
            Ok(Head {
                content_length: 38,
                close: false
            })
        );
        let h = head(
            "POST / HTTP/1.1\r\ncontent-length:\t40 \r\nCONNECTION: keep-alive, Close\r\n\r\n",
        );
        assert_eq!(
            h,
            Ok(Head {
                content_length: 40,
                close: true
            })
        );
        assert_eq!(head_end(b"POST / HTTP/1.1\r\n"), None);
    }

    #[test]
    fn rejects_everything_else() {
        let cases = [
            (
                "GET / HTTP/1.1\r\nContent-Length: 0\r\n\r\n",
                HttpError::RequestLine,
            ),
            (
                "POST /x HTTP/1.1\r\nContent-Length: 0\r\n\r\n",
                HttpError::RequestLine,
            ),
            (
                "POST / HTTP/1.0\r\nContent-Length: 0\r\n\r\n",
                HttpError::RequestLine,
            ),
            (
                "POST /  HTTP/1.1\r\nContent-Length: 0\r\n\r\n",
                HttpError::RequestLine,
            ),
            (
                "POST / HTTP/1.1\nContent-Length: 0\r\n\r\n",
                HttpError::RequestLine,
            ),
            (
                "POST / HTTP/1.1\r\nHost: a\r\n\r\n",
                HttpError::ContentLength,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\n",
                HttpError::ContentLength,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length: +1\r\n\r\n",
                HttpError::ContentLength,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length: 1 2\r\n\r\n",
                HttpError::ContentLength,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length: \r\n\r\n",
                HttpError::ContentLength,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length: 99999999999999999999999\r\n\r\n",
                HttpError::TooBig,
            ),
            (
                "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\n",
                HttpError::TransferEncoding,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length : 1\r\n\r\n",
                HttpError::Field,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length: 1\r\n folded\r\n\r\n",
                HttpError::Field,
            ),
            (
                "POST / HTTP/1.1\r\nContent-Length: 1\nX: y\r\n\r\n",
                HttpError::Field,
            ),
            (
                "POST / HTTP/1.1\r\nX: a\rb\r\nContent-Length: 1\r\n\r\n",
                HttpError::Field,
            ),
            (
                "POST / HTTP/1.1\r\nX: \u{e9}\r\nContent-Length: 1\r\n\r\n",
                HttpError::Field,
            ),
            ("POST / HTTP/1.1\r\nNoColon\r\n\r\n", HttpError::Field),
            ("POST / HTTP/1.1\r\n: v\r\n\r\n", HttpError::Field),
        ];
        for (s, e) in cases {
            assert_eq!(head(s), Err(e), "{s:?}");
        }
        let max = format!("POST / HTTP/1.1\r\nContent-Length: {MAX_BODY}\r\n\r\n");
        assert_eq!(head(&max).map(|h| h.content_length), Ok(MAX_BODY));
        let over = format!(
            "POST / HTTP/1.1\r\nContent-Length: {}\r\n\r\n",
            MAX_BODY + 1
        );
        assert_eq!(head(&over), Err(HttpError::TooBig));
        let many = format!(
            "POST / HTTP/1.1\r\n{}Content-Length: 1\r\n\r\n",
            "X: y\r\n".repeat(MAX_FIELDS)
        );
        assert_eq!(head(&many), Err(HttpError::TooManyFields));
    }

    #[test]
    fn response_heads() {
        assert_eq!(
            ok_head(6, false),
            b"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 6\r\nCache-Control: no-store\r\n\r\n"
        );
        assert!(
            String::from_utf8(ok_head(6, true))
                .unwrap()
                .ends_with("Connection: close\r\n\r\n")
        );
    }
}
