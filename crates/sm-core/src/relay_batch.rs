//! Explicit wire v2 batching. Wire v1 parsing and command semantics are unchanged.
//! A batch is ordered, not transactional; malformed batches execute no items.
use crate::{
    relay::{self, Command, REQUEST_HEADER, RequestHeader, Status},
    wire::{Reader, WireError},
};
/// Version of the batch envelope (not a hidden v1 extension).
pub const VERSION: u8 = 2;
/// Batch envelope command byte.
pub const BATCH: u8 = 10;
/// Maximum subcommands per envelope.
pub const MAX_ITEMS: usize = 32;
/// Complete request-body cap; an item near `MAX_BLOB` must be sent as v1 instead.
pub const MAX_DATA: usize = relay::MAX_BLOB;
/// A validated subcommand borrowing its body, without copying ciphertext.
pub struct Item<'a> {
    /// Existing v1 command and capability.
    pub header: RequestHeader,
    /// Validated body.
    pub data: &'a [u8],
}
fn supported(cmd: Command) -> bool {
    matches!(
        cmd,
        Command::Send | Command::Fetch | Command::Ack | Command::Del | Command::Ping
    )
}
/// Validate v2 envelope header; the reserved ID must be zero.
/// # Errors
/// Wrong version/command/ID or excessive body length.
pub fn parse_header(head: &[u8; REQUEST_HEADER]) -> Result<usize, WireError> {
    let mut r = Reader::new(head);
    if r.u8()? != VERSION || r.u8()? != BATCH || r.array::<32>()? != [0; 32] {
        return Err(WireError::BadValue);
    }
    let len = usize::try_from(u32::from_be_bytes(r.array()?)).map_err(|_| WireError::BadValue)?;
    if len == 0 || len > MAX_DATA {
        return Err(WireError::BadValue);
    }
    Ok(len)
}
/// Parse every item before any state changes. No nested batch, WAIT, NEW or signing commands.
/// # Errors
/// Invalid count/frame/command/body or trailing bytes.
pub fn decode_request(data: &[u8]) -> Result<Vec<Item<'_>>, WireError> {
    if data.len() > MAX_DATA {
        return Err(WireError::TooLong {
            len: data.len(),
            max: MAX_DATA,
        });
    }
    let mut r = Reader::new(data);
    let count = usize::from(r.u8()?);
    if count == 0 || count > MAX_ITEMS {
        return Err(WireError::BadValue);
    }
    let mut items = Vec::with_capacity(count);
    for _ in 0..count {
        let header = relay::parse_request_header(&r.array()?)?;
        if !supported(header.cmd) {
            return Err(WireError::BadValue);
        }
        let data = r.bytes(header.len)?;
        items.push(Item { header, data });
    }
    r.finish()?;
    Ok(items)
}
/// Encode a canonical batch frame for a reference client.
/// # Errors
/// Unsupported/invalid items or envelope above `MAX_DATA`.
pub fn encode_request(items: &[Item<'_>]) -> Result<Vec<u8>, WireError> {
    if items.is_empty() || items.len() > MAX_ITEMS {
        return Err(WireError::BadValue);
    }
    let mut body = vec![u8::try_from(items.len()).map_err(|_| WireError::BadValue)?];
    for item in items {
        if !supported(item.header.cmd) || item.header.len != item.data.len() {
            return Err(WireError::BadValue);
        }
        let size = body
            .len()
            .checked_add(REQUEST_HEADER)
            .and_then(|n| n.checked_add(item.data.len()))
            .ok_or(WireError::BadValue)?;
        if size > MAX_DATA {
            return Err(WireError::TooLong {
                len: size,
                max: MAX_DATA,
            });
        }
        body.extend_from_slice(
            &relay::encode_request(item.header.cmd, &item.header.id, item.data)
                .ok_or(WireError::BadValue)?,
        );
    }
    let mut frame = vec![VERSION, BATCH];
    frame.extend_from_slice(&[0; 32]);
    frame.extend_from_slice(
        &u32::try_from(body.len())
            .map_err(|_| WireError::BadValue)?
            .to_be_bytes(),
    );
    frame.extend_from_slice(&body);
    Ok(frame)
}
/// Decode a batch response body, preserving per-item statuses in request order.
/// # Errors
/// Unknown status, bad count, truncated/oversized body or trailing bytes.
pub fn decode_response(data: &[u8]) -> Result<Vec<(Status, &[u8])>, WireError> {
    if data.len() > relay::MAX_RESPONSE_DATA {
        return Err(WireError::BadValue);
    }
    let mut r = Reader::new(data);
    let count = usize::from(r.u8()?);
    if count == 0 || count > MAX_ITEMS {
        return Err(WireError::BadValue);
    }
    let mut items = Vec::with_capacity(count);
    for _ in 0..count {
        let status = Status::from_code(r.u8()?).ok_or(WireError::BadValue)?;
        let len =
            usize::try_from(u32::from_be_bytes(r.array()?)).map_err(|_| WireError::BadValue)?;
        items.push((status, r.bytes(len)?));
    }
    r.finish()?;
    Ok(items)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn golden_and_v1_is_not_extended() {
        let items = [Item {
            header: RequestHeader {
                cmd: Command::Ping,
                id: [0; 32],
                len: 0,
            },
            data: &[],
        }];
        let frame = encode_request(&items).unwrap();
        assert_eq!(frame.len(), REQUEST_HEADER + 1 + REQUEST_HEADER);
        assert_eq!(&frame[..2], &[2, 10]);
        assert_eq!(&frame[34..38], &[0, 0, 0, 39]);
        assert!(relay::parse_request_header(frame[..38].try_into().unwrap()).is_err());
        assert_eq!(parse_header(frame[..38].try_into().unwrap()), Ok(39));
        assert_eq!(decode_request(&frame[38..]).unwrap().len(), 1);
    }
    #[test]
    fn rejects_truncation_trailing_and_stateful_control_commands() {
        let mut body = vec![1];
        body.extend_from_slice(&relay::encode_request(Command::Wait, &[1; 32], &[]).unwrap());
        assert!(decode_request(&body).is_err());
        let frame = encode_request(&[Item {
            header: RequestHeader {
                cmd: Command::Ack,
                id: [1; 32],
                len: 8,
            },
            data: &[0; 8],
        }])
        .unwrap();
        for end in 0..frame.len().saturating_sub(38) {
            assert!(decode_request(&frame[38..38 + end]).is_err());
        }
        let mut body = frame[38..].to_vec();
        body.push(0);
        assert!(decode_request(&body).is_err());
        body[0] = 33;
        assert!(decode_request(&body).is_err());
    }
    #[test]
    fn response_validation_and_count_bound() {
        assert_eq!(
            decode_response(&[2, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0]).unwrap(),
            vec![(Status::Ok, &[][..]), (Status::NoQueue, &[][..])]
        );
        for bad in [
            &[0][..],
            &[33][..],
            &[1, 255, 0, 0, 0, 0][..],
            &[1, 0, 255, 255, 255, 255][..],
        ] {
            assert!(decode_response(bad).is_err());
        }
    }
}
