//! Корневой ключ и пропуск на `NEW` для тестов (ADR-136).
#![allow(clippy::unwrap_used, dead_code)]

use libsignal_core::curve::KeyPair;
use rand::TryRngCore as _;
use rand::rngs::OsRng;
use sm_core::node::ROOT_PUBLIC_LEN;
use sm_core::pass::{QueuePass, signed_message};
use sm_relay::server::Config;

/// Корневой ключ теста и пропуск им: срок — далеко впереди, `per_hour` очередей в час.
pub struct Root {
    pub roots: &'static [[u8; ROOT_PUBLIC_LEN]],
    kp: KeyPair,
}

impl Root {
    pub fn new() -> Self {
        let kp = KeyPair::generate(&mut OsRng.unwrap_err());
        let public: [u8; ROOT_PUBLIC_LEN] = kp.public_key.serialize()[..].try_into().unwrap();
        Self {
            roots: Box::leak(Box::new([public])),
            kp,
        }
    }

    pub fn pass(&self, id: u8, per_hour: u16) -> Vec<u8> {
        let p = QueuePass {
            id: [id; 16],
            expires: u64::MAX,
            per_hour,
        };
        let sig = self
            .kp
            .private_key
            .calculate_signature(&signed_message(&p.body()), &mut OsRng.unwrap_err())
            .unwrap();
        p.with_signature(&sig[..].try_into().unwrap())
    }

    pub fn config(&self, c: Config) -> Config {
        Config {
            roots: self.roots,
            ..c
        }
    }
}
