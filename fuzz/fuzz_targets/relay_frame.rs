//! Фаззинг разбора кадров узла-реле: заголовки запроса и ответа из враждебных данных.
#![no_main]

use libfuzzer_sys::fuzz_target;
use sm_core::relay::{
    MAX_BLOB, MAX_RESPONSE_DATA, REQUEST_HEADER, RESPONSE_HEADER, encode_request, encode_response,
    parse_request_header, parse_response_header,
};

fuzz_target!(|data: &[u8]| {
    if let Some((head, body)) = data.split_first_chunk::<REQUEST_HEADER>()
        && let Ok(h) = parse_request_header(head)
    {
        assert!(h.len <= MAX_BLOB);
        // Каноничность: кадр, собранный из разобранного заголовка и данных, совпадает с исходным.
        if let Some(body) = body.get(..h.len) {
            let frame = encode_request(h.cmd, &h.id, body).unwrap();
            assert_eq!(frame.get(..REQUEST_HEADER), Some(&head[..]));
        }
    }
    if let Some((head, body)) = data.split_first_chunk::<RESPONSE_HEADER>()
        && let Ok((status, len)) = parse_response_header(head)
    {
        assert!(len <= MAX_RESPONSE_DATA);
        if let Some(body) = body.get(..len) {
            let frame = encode_response(status, body).unwrap();
            assert_eq!(frame.get(..RESPONSE_HEADER), Some(&head[..]));
        }
    }
});
