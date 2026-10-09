//! Выравнивание длины перед шифрованием: размер шифротекста не выдаёт точную длину текста.
//!
//! Схема ISO/IEC 7816-4: к данным дописывается `0x80`, затем нули до размера корзины.
//! Корзины: 64, 128, 256 … байт (степени двойки) до [`MAX_PADDED`].
//! Снятие строгое: длина должна быть ровно размером корзины для данных — иначе ошибка.

/// Наименьшая корзина.
pub const MIN_BUCKET: usize = 64;
/// Наибольший размер после выравнивания.
pub const MAX_PADDED: usize = 128 * 1024;

/// Ошибка выравнивания.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaddingError {
    /// Данные не помещаются в [`MAX_PADDED`].
    TooLong,
    /// Нет маркера `0x80` или длина не совпадает с корзиной.
    Malformed,
}

/// Размер корзины для данных длины `len` (с учётом байта-маркера).
#[must_use]
pub fn bucket(len: usize) -> Option<usize> {
    let need = len.checked_add(1)?;
    let b = need.checked_next_power_of_two()?.max(MIN_BUCKET);
    (b <= MAX_PADDED).then_some(b)
}

/// Выравнивает данные.
///
/// # Errors
/// [`PaddingError::TooLong`].
pub fn pad(data: &[u8]) -> Result<Vec<u8>, PaddingError> {
    let b = bucket(data.len()).ok_or(PaddingError::TooLong)?;
    let mut out = Vec::with_capacity(b);
    out.extend_from_slice(data);
    out.push(0x80);
    out.resize(b, 0);
    Ok(out)
}

/// Снимает выравнивание; результат заимствуется из входа.
///
/// # Errors
/// [`PaddingError`], если вход не получен через [`pad`].
pub fn unpad(buf: &[u8]) -> Result<&[u8], PaddingError> {
    if buf.len() > MAX_PADDED {
        return Err(PaddingError::TooLong);
    }
    let marker = buf
        .iter()
        .rposition(|&b| b != 0)
        .ok_or(PaddingError::Malformed)?;
    let (data, tail) = buf.split_at(marker);
    if tail.first() != Some(&0x80) || bucket(data.len()) != Some(buf.len()) {
        return Err(PaddingError::Malformed);
    }
    Ok(data)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_buckets() {
        for n in [0, 1, 63, 64, 65, 1000, MAX_PADDED - 1] {
            let data = vec![0xAB; n];
            let p = pad(&data).unwrap();
            assert!(p.len().is_power_of_two() && p.len() >= MIN_BUCKET && p.len() > n);
            assert_eq!(unpad(&p), Ok(&data[..]));
        }
        assert_eq!(pad(&vec![0; MAX_PADDED]), Err(PaddingError::TooLong));
    }

    #[test]
    fn data_ending_with_zeros_survives() {
        let data = [1, 0, 0, 0x80, 0];
        assert_eq!(unpad(&pad(&data).unwrap()), Ok(&data[..]));
    }

    #[test]
    fn rejects_non_canonical() {
        assert_eq!(unpad(&[]), Err(PaddingError::Malformed));
        assert_eq!(unpad(&[0; 64]), Err(PaddingError::Malformed));
        let mut p = pad(b"hi").unwrap();
        p.push(0);
        assert_eq!(unpad(&p), Err(PaddingError::Malformed));
        let mut q = pad(b"hi").unwrap();
        q[2] = 0x81;
        assert_eq!(unpad(&q), Err(PaddingError::Malformed));
        let mut big = pad(b"hi").unwrap();
        big.resize(128, 0);
        assert_eq!(unpad(&big), Err(PaddingError::Malformed));
    }
}
