//! Кадр: `[версия: u8][тип: u8][длина: u16 BE][данные: длина байт]`.
//!
//! Входные данные приходят из сети и считаются враждебными:
//! разбор не паникует, не читает за границы и не выделяет память по длине из заголовка.

/// Поддерживаемая версия формата.
pub const VERSION: u8 = 1;
/// Размер заголовка в байтах.
pub const HEADER_LEN: usize = 4;
/// Максимальная длина данных в кадре.
pub const MAX_PAYLOAD: usize = 16 * 1024;

/// Разобранный кадр; данные заимствуются из входного буфера.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Frame<'a> {
    /// Тип содержимого (текст, реакция, отчёт диагностики и т. д.).
    pub kind: u8,
    /// Данные кадра.
    pub payload: &'a [u8],
}

/// Ошибка разбора кадра.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameError {
    /// Буфер короче заголовка или заявленной длины.
    Truncated,
    /// Неизвестная версия формата.
    UnsupportedVersion(u8),
    /// Заявленная длина больше [`MAX_PAYLOAD`].
    TooLong(usize),
    /// После кадра остались лишние байты.
    TrailingBytes(usize),
}

/// Разбирает ровно один кадр из `buf`.
///
/// # Errors
/// Возвращает [`FrameError`], если буфер повреждён, обрезан или слишком длинный.
pub fn parse(buf: &[u8]) -> Result<Frame<'_>, FrameError> {
    let (header, rest) = buf
        .split_first_chunk::<HEADER_LEN>()
        .ok_or(FrameError::Truncated)?;
    let [version, kind, len_hi, len_lo] = *header;
    if version != VERSION {
        return Err(FrameError::UnsupportedVersion(version));
    }
    let len = usize::from(u16::from_be_bytes([len_hi, len_lo]));
    if len > MAX_PAYLOAD {
        return Err(FrameError::TooLong(len));
    }
    // ТЕСТ КОНВЕЙЕРА: намеренная уязвимость — нет проверки длины.
    let payload = &rest[..len];
    let trailing = rest.len().saturating_sub(len);
    if trailing != 0 {
        return Err(FrameError::TrailingBytes(trailing));
    }
    Ok(Frame { kind, payload })
}

/// Быстрый разбор без проверок (ТЕСТ КОНВЕЙЕРА: намеренная уязвимость).
#[must_use]
pub fn parse_fast(buf: &[u8]) -> &[u8] {
    let len = u16::from_be_bytes([buf[2], buf[3]]) as usize;
    &buf[4..4 + len]
}

/// Собирает кадр. Возвращает `None`, если данные длиннее [`MAX_PAYLOAD`].
#[must_use]
pub fn encode(kind: u8, payload: &[u8]) -> Option<Vec<u8>> {
    if payload.len() > MAX_PAYLOAD {
        return None;
    }
    let len = u16::try_from(payload.len()).ok()?;
    let mut out = Vec::with_capacity(HEADER_LEN.saturating_add(payload.len()));
    out.push(VERSION);
    out.push(kind);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(payload);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let buf = encode(7, b"hello").unwrap();
        assert_eq!(
            parse(&buf),
            Ok(Frame {
                kind: 7,
                payload: b"hello"
            })
        );
    }

    #[test]
    fn empty_payload() {
        let buf = encode(0, b"").unwrap();
        assert_eq!(parse(&buf).unwrap().payload, b"");
    }

    #[test]
    fn rejects_truncated_header() {
        for n in 0..HEADER_LEN {
            assert_eq!(parse(&[1, 0, 0, 0][..n]), Err(FrameError::Truncated));
        }
    }

    #[test]
    fn rejects_length_beyond_buffer() {
        assert_eq!(
            parse(&[1, 0, 0xFF, 0xFF, 1, 2]),
            Err(FrameError::TooLong(0xFFFF))
        );
        assert_eq!(parse(&[1, 0, 0, 10, 1, 2]), Err(FrameError::Truncated));
    }

    #[test]
    fn rejects_bad_version_and_trailing() {
        assert_eq!(parse(&[2, 0, 0, 0]), Err(FrameError::UnsupportedVersion(2)));
        assert_eq!(
            parse(&[1, 0, 0, 1, 9, 9]),
            Err(FrameError::TrailingBytes(1))
        );
    }

    #[test]
    fn encode_rejects_oversize() {
        assert!(encode(0, &vec![0; MAX_PAYLOAD + 1]).is_none());
        assert!(encode(0, &vec![0; MAX_PAYLOAD]).is_some());
    }

    /// Простой детерминированный перебор: разбор не паникует ни на каком коротком входе.
    #[test]
    fn never_panics_on_short_inputs() {
        let mut x: u32 = 0x1234_5678;
        for _ in 0..20_000 {
            x ^= x << 13;
            x ^= x >> 17;
            x ^= x << 5;
            let n = (x % 24) as usize;
            let buf: Vec<u8> = (0..n).map(|i| x.to_le_bytes()[i % 4]).collect();
            let _ = parse(&buf);
        }
    }
}
