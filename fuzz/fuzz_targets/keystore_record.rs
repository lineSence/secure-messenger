#![no_main]
//! Фаззинг записи хранилища ключей: без паник; успешный разбор канонический.

use libfuzzer_sys::fuzz_target;
use sm_core::keystore;

fuzz_target!(|data: &[u8]| {
    if let Ok((label, wrapped)) = keystore::decode_record(data) {
        let again = keystore::encode_record(&label, wrapped).expect("разобранное кодируется");
        assert_eq!(again.as_slice(), data);
    }
});
