#![no_main]
use libfuzzer_sys::fuzz_target;
fuzz_target!(|data: &[u8]| {
    let _ = sm_core::relay_batch::decode_request(data);
    let _ = sm_core::relay_batch::decode_response(data);
    if let Ok(header) = <&[u8; sm_core::relay::REQUEST_HEADER]>::try_from(data) {
        let _ = sm_core::relay_batch::parse_header(header);
    }
});
