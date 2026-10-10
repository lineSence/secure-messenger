//! Нарезка кадра на части для транспортов с маленьким пакетом и сборка обратно (ADR-49).
//!
//! Первый пользователь — Bluetooth LE: значение атрибута GATT — не больше 512 байт, поэтому
//! кадр «ящик + блоб» режется на части. Части идут по одному соединению по порядку (запись
//! GATT с подтверждением), поэтому сборщик принимает их **только по порядку**: память растёт
//! по мере прихода данных и не выделяется по числам из заголовка; любое нарушение порядка
//! сбрасывает незаконченный кадр. Один [`Reassembler`] — на одно соединение; при разрыве его
//! удаляют вместе с незаконченным кадром: и длина, и выделенная под него память — не больше
//! [`MAX_FRAME`] байт.
//!
//! Часть: `[версия u8 = 1][номер передачи u16 BE][номер части u16 BE][всего частей u16 BE][данные]`.
//! Кадр: `[ящик 32 байта][блоб]`. Целостность и подлинность блоба — забота шифрования
//! (`libsignal`); здесь только границы.

use crate::relay::{ID_LEN, MAX_BLOB};
use crate::transport::MailboxId;
use crate::wire::{Reader, WireError};

/// Версия формата части.
pub const VERSION: u8 = 1;
/// Заголовок части, байт.
pub const HEADER: usize = 7;
/// Наибольшая часть, байт: предел значения атрибута GATT.
pub const MAX_CHUNK: usize = 512;
/// Наибольший кадр: ящик + блоб узла.
pub const MAX_FRAME: usize = ID_LEN + MAX_BLOB;

/// Ошибка части из сети.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChunkError {
    /// Часть короче заголовка, без данных или длиннее [`MAX_CHUNK`].
    BadLength,
    /// Неизвестная версия.
    BadVersion,
    /// Ноль частей или номер части не меньше их числа.
    BadIndex,
    /// Часть не по порядку: другой номер передачи, пропуск или повтор.
    OutOfOrder,
    /// Кадр вышел бы больше [`MAX_FRAME`].
    TooLarge,
}

/// Режет кадр на части не длиннее `chunk_len` (с заголовком).
///
/// `None` — пустой кадр, кадр больше [`MAX_FRAME`] или `chunk_len` вне
/// `HEADER + 1..=MAX_CHUNK`.
#[must_use]
pub fn split(frame: &[u8], transfer: u16, chunk_len: usize) -> Option<Vec<Vec<u8>>> {
    if frame.is_empty() || frame.len() > MAX_FRAME || !(HEADER + 1..=MAX_CHUNK).contains(&chunk_len)
    {
        return None;
    }
    let payload = chunk_len.checked_sub(HEADER)?;
    let count = u16::try_from(frame.len().div_ceil(payload)).ok()?;
    let mut out = Vec::with_capacity(usize::from(count));
    for (index, data) in (0..count).zip(frame.chunks(payload)) {
        let mut c = Vec::with_capacity(HEADER.checked_add(data.len())?);
        c.push(VERSION);
        c.extend_from_slice(&transfer.to_be_bytes());
        c.extend_from_slice(&index.to_be_bytes());
        c.extend_from_slice(&count.to_be_bytes());
        c.extend_from_slice(data);
        out.push(c);
    }
    Some(out)
}

#[derive(Debug)]
struct Partial {
    transfer: u16,
    next: u16,
    count: u16,
    data: Vec<u8>,
}

/// Сборщик частей одного соединения.
#[derive(Debug, Default)]
pub struct Reassembler {
    partial: Option<Partial>,
}

impl Reassembler {
    /// Пустой сборщик.
    #[must_use]
    pub const fn new() -> Self {
        Self { partial: None }
    }

    /// Есть ли незаконченный кадр.
    #[must_use]
    pub const fn in_progress(&self) -> bool {
        self.partial.is_some()
    }

    /// Принимает часть; возвращает кадр, когда пришла последняя.
    ///
    /// Часть с номером 0 всегда начинает новый кадр (незаконченный отбрасывается) — так
    /// отправитель продолжает после сбоя без отдельной команды.
    ///
    /// # Errors
    /// [`ChunkError`]; незаконченный кадр при этом отбрасывается.
    pub fn push(&mut self, chunk: &[u8]) -> Result<Option<Vec<u8>>, ChunkError> {
        let r = self.accept(chunk);
        if r.is_err() {
            self.partial = None;
        }
        r
    }

    fn accept(&mut self, chunk: &[u8]) -> Result<Option<Vec<u8>>, ChunkError> {
        if chunk.len() <= HEADER || chunk.len() > MAX_CHUNK {
            return Err(ChunkError::BadLength);
        }
        let mut r = Reader::new(chunk);
        let head = (r.u8(), r.u16(), r.u16(), r.u16());
        let (Ok(version), Ok(transfer), Ok(index), Ok(count)) = head else {
            return Err(ChunkError::BadLength);
        };
        if version != VERSION {
            return Err(ChunkError::BadVersion);
        }
        if index >= count {
            return Err(ChunkError::BadIndex);
        }
        let data = r.rest(MAX_CHUNK).map_err(|_| ChunkError::BadLength)?;
        let mut p = match self.partial.take() {
            _ if index == 0 => Partial {
                transfer,
                next: 0,
                count,
                data: Vec::new(),
            },
            Some(p) if p.transfer == transfer && p.next == index && p.count == count => p,
            _ => return Err(ChunkError::OutOfOrder),
        };
        if p.data.len().saturating_add(data.len()) > MAX_FRAME {
            return Err(ChunkError::TooLarge);
        }
        // Ёмкость растёт вдвое, но не больше MAX_FRAME: выделенная память, а не только длина,
        // остаётся в пределе (находка ревью PR #20).
        let need = p.data.len().saturating_add(data.len());
        if need > p.data.capacity() {
            let target = need.max(p.data.capacity().saturating_mul(2).min(MAX_FRAME));
            p.data
                .try_reserve_exact(target.saturating_sub(p.data.len()))
                .map_err(|_| ChunkError::TooLarge)?;
        }
        p.data.extend_from_slice(data);
        // index < count ≤ u16::MAX, поэтому index + 1 не переполняется.
        p.next = index.saturating_add(1);
        if p.next == p.count {
            return Ok(Some(p.data));
        }
        self.partial = Some(p);
        Ok(None)
    }
}

/// Собирает кадр «ящик + блоб».
///
/// `None` — пустой блоб или блоб больше [`MAX_BLOB`].
#[must_use]
pub fn encode_frame(mailbox: &MailboxId, blob: &[u8]) -> Option<Vec<u8>> {
    if blob.is_empty() || blob.len() > MAX_BLOB {
        return None;
    }
    let mut f = Vec::with_capacity(ID_LEN.checked_add(blob.len())?);
    f.extend_from_slice(&mailbox.0);
    f.extend_from_slice(blob);
    Some(f)
}

/// Разбирает кадр «ящик + блоб».
///
/// # Errors
/// [`WireError`]: кадр короче ящика, пустой блоб или блоб больше [`MAX_BLOB`].
pub fn parse_frame(frame: &[u8]) -> Result<(MailboxId, &[u8]), WireError> {
    let mut r = Reader::new(frame);
    let id = MailboxId(r.array::<ID_LEN>()?);
    let blob = r.rest(MAX_BLOB)?;
    if blob.is_empty() {
        return Err(WireError::Truncated);
    }
    Ok((id, blob))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(len: usize) -> Vec<u8> {
        (0..len).map(|i| u8::try_from(i % 251).unwrap()).collect()
    }

    fn assemble(chunks: &[Vec<u8>]) -> Option<Vec<u8>> {
        let mut r = Reassembler::new();
        let mut out = None;
        for c in chunks {
            out = r.push(c).unwrap();
        }
        out
    }

    #[test]
    fn roundtrip_sizes() {
        for len in [1, 504, 505, 506, 1010, 4096, MAX_FRAME] {
            let f = frame(len);
            for chunk_len in [HEADER + 1, 20, 185, MAX_CHUNK] {
                if len > 4096 && chunk_len < 185 {
                    continue;
                }
                let parts = split(&f, 7, chunk_len).unwrap();
                assert!(parts.iter().all(|c| c.len() <= chunk_len));
                assert_eq!(
                    assemble(&parts),
                    Some(f.clone()),
                    "len {len}, chunk {chunk_len}"
                );
            }
        }
    }

    #[test]
    fn split_limits() {
        assert_eq!(split(&[], 0, MAX_CHUNK), None);
        assert_eq!(split(&frame(MAX_FRAME + 1), 0, MAX_CHUNK), None);
        assert_eq!(split(&[1], 0, HEADER), None);
        assert_eq!(split(&[1], 0, MAX_CHUNK + 1), None);
        // При 1 байте данных на часть кадр в 70 000 байт не уложится в u16 частей.
        assert_eq!(split(&frame(70_000), 0, HEADER + 1), None);
    }

    #[test]
    fn rejects_bad_chunks() {
        let mut r = Reassembler::new();
        assert_eq!(r.push(&[1, 0, 0, 0, 0, 0, 1]), Err(ChunkError::BadLength));
        assert_eq!(
            r.push(&[2, 0, 0, 0, 0, 0, 1, 9]),
            Err(ChunkError::BadVersion)
        );
        assert_eq!(
            r.push(&[1, 0, 0, 0, 0, 0, 0, 9]),
            Err(ChunkError::BadIndex),
            "0 частей"
        );
        assert_eq!(r.push(&[1, 0, 0, 0, 2, 0, 2, 9]), Err(ChunkError::BadIndex));
        assert_eq!(r.push(&[0; MAX_CHUNK + 1]), Err(ChunkError::BadLength));
    }

    #[test]
    fn out_of_order_drops_partial() {
        let parts = split(&frame(2000), 1, 100).unwrap();
        let mut r = Reassembler::new();
        assert_eq!(r.push(&parts[0]), Ok(None));
        assert_eq!(r.push(&parts[2]), Err(ChunkError::OutOfOrder), "пропуск");
        assert!(!r.in_progress());
        assert_eq!(r.push(&parts[1]), Err(ChunkError::OutOfOrder), "без начала");
        assert_eq!(r.push(&parts[0]), Ok(None));
        assert_eq!(r.push(&parts[0]), Ok(None), "повтор начала — новый кадр");
        assert_eq!(r.push(&parts[1]), Ok(None));
        assert_eq!(
            r.push(&parts[1]),
            Err(ChunkError::OutOfOrder),
            "повтор части"
        );
        // Чужая передача посреди кадра.
        let other = split(&frame(2000), 2, 100).unwrap();
        assert_eq!(r.push(&parts[0]), Ok(None));
        assert_eq!(r.push(&other[1]), Err(ChunkError::OutOfOrder));
    }

    #[test]
    fn restart_after_failure() {
        let a = split(&frame(1500), 1, 512).unwrap();
        let b = split(&frame(700), 2, 512).unwrap();
        let mut r = Reassembler::new();
        assert_eq!(r.push(&a[0]), Ok(None));
        assert_eq!(
            r.push(&b[0]),
            Ok(None),
            "новый кадр вытесняет незаконченный"
        );
        assert_eq!(r.push(&b[1]), Ok(Some(frame(700))));
        assert!(!r.in_progress());
    }

    #[test]
    fn count_mismatch_rejected() {
        let mut r = Reassembler::new();
        assert_eq!(r.push(&[1, 0, 5, 0, 0, 0, 3, 9]), Ok(None));
        assert_eq!(
            r.push(&[1, 0, 5, 0, 1, 0, 2, 9]),
            Err(ChunkError::OutOfOrder)
        );
    }

    #[test]
    fn frame_too_large_rejected() {
        // Много частей по 505 байт данных: на превышении MAX_FRAME — отказ.
        let mut r = Reassembler::new();
        let mut c = vec![0u8; MAX_CHUNK];
        c[0] = VERSION;
        c[5..7].copy_from_slice(&u16::MAX.to_be_bytes());
        let mut got = Err(ChunkError::BadLength);
        for i in 0..u16::MAX {
            c[3..5].copy_from_slice(&i.to_be_bytes());
            got = r.push(&c);
            if got.is_err() {
                break;
            }
        }
        assert_eq!(got, Err(ChunkError::TooLarge));
        assert!(!r.in_progress());
    }

    /// Находка ревью PR #20: выделенная память кадра не больше `MAX_FRAME`.
    #[test]
    fn capacity_within_limit() {
        for len in [MAX_FRAME, MAX_FRAME - 1, 70_000, 65_537] {
            let parts = split(&frame(len), 1, MAX_CHUNK).unwrap();
            let mut r = Reassembler::new();
            let mut out = None;
            for c in &parts {
                out = r.push(c).unwrap();
                if let Some(p) = &r.partial {
                    assert!(p.data.capacity() <= MAX_FRAME);
                }
            }
            let f = out.unwrap();
            assert_eq!(f.len(), len);
            assert!(f.capacity() <= MAX_FRAME, "len {len}: {}", f.capacity());
        }
    }

    #[test]
    fn frame_roundtrip() {
        let id = MailboxId([3; 32]);
        let f = encode_frame(&id, &[1, 2, 3]).unwrap();
        assert_eq!(parse_frame(&f), Ok((id, &[1u8, 2, 3][..])));
        assert_eq!(encode_frame(&id, &[]), None);
        assert_eq!(encode_frame(&id, &vec![0; MAX_BLOB + 1]), None);
        assert_eq!(parse_frame(&[0; ID_LEN]), Err(WireError::Truncated));
        assert_eq!(parse_frame(&[0; 5]), Err(WireError::Truncated));
        assert!(parse_frame(&vec![1; ID_LEN + MAX_BLOB + 1]).is_err());
    }
}
