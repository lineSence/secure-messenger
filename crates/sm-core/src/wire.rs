//! Безопасное чтение и запись двоичных полей.
//!
//! [`Reader`] читает из враждебного буфера: каждый метод проверяет границы
//! и возвращает [`WireError`] вместо паники. Память по длине из данных не выделяется:
//! все срезы заимствуются из входного буфера.

/// Ошибка разбора двоичных данных.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WireError {
    /// Данные закончились раньше, чем ожидалось.
    Truncated,
    /// После разбора остались лишние байты.
    TrailingBytes(usize),
    /// Поле длиннее допустимого предела.
    TooLong {
        /// Заявленная или фактическая длина.
        len: usize,
        /// Предел.
        max: usize,
    },
    /// Строка — не UTF-8 или содержит запрещённые символы.
    BadText,
    /// Недопустимое значение поля.
    BadValue,
}

/// Курсор чтения поверх заимствованного буфера. `Debug` показывает только длину остатка.
#[derive(Clone)]
pub struct Reader<'a> {
    rest: &'a [u8],
}

impl core::fmt::Debug for Reader<'_> {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        write!(f, "Reader {{ remaining: {} }}", self.rest.len())
    }
}

impl<'a> Reader<'a> {
    /// Начинает чтение буфера.
    #[must_use]
    pub const fn new(buf: &'a [u8]) -> Self {
        Self { rest: buf }
    }

    /// Сколько байт осталось.
    #[must_use]
    pub const fn remaining(&self) -> usize {
        self.rest.len()
    }

    /// Читает `n` байт.
    ///
    /// # Errors
    /// [`WireError::Truncated`], если осталось меньше `n` байт.
    pub fn bytes(&mut self, n: usize) -> Result<&'a [u8], WireError> {
        if n > self.rest.len() {
            return Err(WireError::Truncated);
        }
        let (head, tail) = self.rest.split_at(n);
        self.rest = tail;
        Ok(head)
    }

    /// Читает массив фиксированного размера.
    ///
    /// # Errors
    /// [`WireError::Truncated`], если данных не хватает.
    pub fn array<const N: usize>(&mut self) -> Result<[u8; N], WireError> {
        let (head, tail) = self
            .rest
            .split_first_chunk::<N>()
            .ok_or(WireError::Truncated)?;
        self.rest = tail;
        Ok(*head)
    }

    /// Читает `u8`.
    ///
    /// # Errors
    /// [`WireError::Truncated`].
    pub fn u8(&mut self) -> Result<u8, WireError> {
        self.array::<1>().map(|[b]| b)
    }

    /// Читает `u16` (big-endian).
    ///
    /// # Errors
    /// [`WireError::Truncated`].
    pub fn u16(&mut self) -> Result<u16, WireError> {
        self.array().map(u16::from_be_bytes)
    }

    /// Читает `u64` (big-endian).
    ///
    /// # Errors
    /// [`WireError::Truncated`].
    pub fn u64(&mut self) -> Result<u64, WireError> {
        self.array().map(u64::from_be_bytes)
    }

    /// Читает поле `[длина: u16 BE][байты]` не длиннее `max`.
    ///
    /// # Errors
    /// [`WireError::TooLong`] до чтения данных, если длина больше `max`; [`WireError::Truncated`].
    pub fn bytes_u16(&mut self, max: usize) -> Result<&'a [u8], WireError> {
        let len = usize::from(self.u16()?);
        if len > max {
            return Err(WireError::TooLong { len, max });
        }
        self.bytes(len)
    }

    /// Забирает все оставшиеся байты (не длиннее `max`).
    ///
    /// # Errors
    /// [`WireError::TooLong`].
    pub fn rest(&mut self, max: usize) -> Result<&'a [u8], WireError> {
        let len = self.rest.len();
        if len > max {
            return Err(WireError::TooLong { len, max });
        }
        Ok(core::mem::take(&mut self.rest))
    }

    /// Проверяет, что буфер прочитан до конца.
    ///
    /// # Errors
    /// [`WireError::TrailingBytes`].
    pub const fn finish(self) -> Result<(), WireError> {
        match self.rest.len() {
            0 => Ok(()),
            n => Err(WireError::TrailingBytes(n)),
        }
    }
}

/// Проверяет текст из сети: UTF-8, не длиннее `max` байт, без NUL, без невидимых символов
/// ([`forbidden_char`]) и без теговых символов вне флага (подмена отображаемого текста).
///
/// # Errors
/// [`WireError::TooLong`], [`WireError::BadText`].
pub fn text(bytes: &[u8], max: usize) -> Result<&str, WireError> {
    if bytes.len() > max {
        return Err(WireError::TooLong {
            len: bytes.len(),
            max,
        });
    }
    let s = core::str::from_utf8(bytes).map_err(|_| WireError::BadText)?;
    if s.chars().any(forbidden_char) || !tags_only_in_flags(s) {
        return Err(WireError::BadText);
    }
    Ok(s)
}

/// Символы, которые запрещены в тексте: NUL и невидимые символы —
/// знаки направления письма U+061C (ALM), U+200E (LRM), U+200F (RLM), U+202A–U+202E,
/// U+2066–U+2069; U+00AD (мягкий перенос), U+200B (ZWSP), U+FEFF (BOM),
/// U+2060–U+2064 (word joiner, невидимые операторы).
/// U+200C (ZWNJ) и U+200D (ZWJ) разрешены: они нужны письменностям и составным эмодзи.
/// Теговые символы U+E0000–U+E007F проверяет [`text`]: они допустимы только внутри флага.
#[must_use]
pub const fn forbidden_char(c: char) -> bool {
    matches!(
        c,
        '\0' | '\u{00AD}'
            | '\u{061C}'
            | '\u{200B}'
            | '\u{200E}'
            | '\u{200F}'
            | '\u{202A}'..='\u{202E}'
            | '\u{2060}'..='\u{2064}'
            | '\u{2066}'..='\u{2069}'
            | '\u{FEFF}'
    )
}

/// Чёрный флаг — начало флага региона: 🏴 + теги U+E0020–U+E007E + U+E007F (конец).
const BLACK_FLAG: char = '\u{1F3F4}';
const TAG_END: char = '\u{E007F}';

/// Теговые символы (ими можно незаметно спрятать ASCII-текст) — только в флаге региона:
/// 🏴, затем хотя бы один тег U+E0020–U+E007E, затем U+E007F. Остальные U+E0000–U+E007F запрещены.
fn tags_only_in_flags(s: &str) -> bool {
    // after_flag — предыдущий символ 🏴; in_tags — идёт последовательность тегов флага.
    let (mut after_flag, mut in_tags) = (false, false);
    for c in s.chars() {
        match c {
            '\u{E0020}'..='\u{E007E}' if after_flag || in_tags => {
                (after_flag, in_tags) = (false, true);
            }
            TAG_END if in_tags => (after_flag, in_tags) = (false, false),
            '\u{E0000}'..='\u{E007F}' => return false,
            _ if in_tags => return false,
            _ => (after_flag, in_tags) = (c == BLACK_FLAG, false),
        }
    }
    !in_tags
}

/// Дописывает поле `[длина: u16 BE][байты]`. Возвращает `None`, если длина больше `max` или `u16::MAX`.
#[must_use]
pub fn put_bytes_u16(out: &mut Vec<u8>, bytes: &[u8], max: usize) -> Option<()> {
    if bytes.len() > max {
        return None;
    }
    let len = u16::try_from(bytes.len()).ok()?;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(bytes);
    Some(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_fields_and_checks_bounds() {
        let buf = [1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 3, 0, 2, b'h', b'i'];
        let mut r = Reader::new(&buf);
        assert_eq!(r.u8(), Ok(1));
        assert_eq!(r.u16(), Ok(2));
        assert_eq!(r.u64(), Ok(3));
        assert_eq!(r.bytes_u16(8), Ok(&b"hi"[..]));
        assert_eq!(r.finish(), Ok(()));
    }

    #[test]
    fn rejects_truncated_and_too_long() {
        let mut r = Reader::new(&[0, 5, 1]);
        assert_eq!(
            r.clone().bytes_u16(4),
            Err(WireError::TooLong { len: 5, max: 4 })
        );
        assert_eq!(r.bytes_u16(5), Err(WireError::Truncated));
        assert_eq!(Reader::new(&[1]).u16(), Err(WireError::Truncated));
        assert_eq!(
            Reader::new(&[1, 2]).finish(),
            Err(WireError::TrailingBytes(2))
        );
        assert_eq!(
            Reader::new(&[1, 2, 3]).rest(2),
            Err(WireError::TooLong { len: 3, max: 2 })
        );
    }

    #[test]
    fn text_rules() {
        assert_eq!(text("привет".as_bytes(), 64), Ok("привет"));
        assert_eq!(text(b"\xff", 8), Err(WireError::BadText));
        assert_eq!(text(b"a\0b", 8), Err(WireError::BadText));
        assert_eq!(text("a\u{202E}b".as_bytes(), 8), Err(WireError::BadText));
        for c in [
            '\u{061C}', '\u{200E}', '\u{200F}', '\u{2066}', '\u{2069}', '\u{00AD}', '\u{200B}',
            '\u{FEFF}', '\u{2060}', '\u{2061}', '\u{2062}', '\u{2063}', '\u{2064}',
        ] {
            assert_eq!(
                text(c.to_string().as_bytes(), 8),
                Err(WireError::BadText),
                "{c:?}"
            );
        }
        assert!(text("👨\u{200D}👩".as_bytes(), 16).is_ok());
        assert!(text("می\u{200C}خواهم".as_bytes(), 32).is_ok());
        assert_eq!(
            text("ID 123\u{200B}456".as_bytes(), 16),
            Err(WireError::BadText)
        );
        assert_eq!(text(b"abc", 2), Err(WireError::TooLong { len: 3, max: 2 }));
    }

    /// Флаг Англии: 🏴 + теги «gbeng» + конец тега.
    const ENGLAND: &str = "\u{1F3F4}\u{E0067}\u{E0062}\u{E0065}\u{E006E}\u{E0067}\u{E007F}";

    #[test]
    fn tag_chars_only_inside_flag() {
        let ok = |s: &str| text(s.as_bytes(), 256).is_ok();
        assert!(ok(ENGLAND));
        assert!(ok(&format!("go {ENGLAND}{ENGLAND}!")));
        assert!(ok("\u{1F3F4}"), "чёрный флаг без тегов — обычный эмодзи");
        for bad in [
            "a\u{E0061}b",                          // тег без флага: спрятанный текст
            "\u{E0001}",                            // тег языка
            "\u{E0000}",                            // не назначен
            "\u{1F3F4}\u{E0067}\u{E0062}",          // нет конца тега
            "\u{1F3F4}\u{E0067}x\u{E007F}",         // посторонний символ внутри
            "\u{1F3F4}\u{E007F}",                   // конец без тегов
            "a\u{E007F}",                           // конец без флага
            &format!("{ENGLAND}{}", '\u{E0067}'),   // тег после закрытого флага
            "\u{1F3F4}\u{E0067}\u{E007F}\u{E007F}", // лишний конец
        ] {
            assert!(!ok(bad), "{bad:?}");
        }
    }

    #[test]
    fn debug_hides_buffer() {
        let s = format!("{:?}", Reader::new(b"secret"));
        assert_eq!(s, "Reader { remaining: 6 }");
    }

    #[test]
    fn put_respects_limit() {
        let mut out = Vec::new();
        assert_eq!(put_bytes_u16(&mut out, b"abc", 2), None);
        assert_eq!(out.len(), 0);
        assert_eq!(put_bytes_u16(&mut out, b"ab", 2), Some(()));
        assert_eq!(out, [0, 2, b'a', b'b']);
    }
}
