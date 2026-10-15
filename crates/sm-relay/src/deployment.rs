//! Versioned, bounded local configuration. No transport discovery, traffic history or secrets output.
use libsignal_core::curve::PublicKey;
use sm_core::node::{ROOT_KEYS, ROOT_PUBLIC_LEN};
use sm_relay::{pass::GuestKey, server::Config, settings, store::PassId};
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    io::{self, Read as _, Write as _},
    net::SocketAddr,
    path::{Path, PathBuf},
};

const MAX_CONFIG: u64 = 16 << 10;
const MAX_GUEST_KEY: u64 = 128;
const MAX_ROOTS: usize = 8;
const RESOURCE_KEYS: &[&str] = &[
    "SM_RELAY_IO_WORKERS",
    "SM_RELAY_STORE_SHARDS",
    "SM_RELAY_MAX_CONNECTIONS",
    "SM_RELAY_MAX_QUEUES",
    "SM_RELAY_MEMORY_BYTES",
    "SM_RELAY_MAX_MESSAGES",
    "SM_RELAY_MAX_TICKETS",
    "SM_RELAY_BLOBS_PER_QUEUE",
    "SM_RELAY_TOTAL_BYTES",
    "SM_RELAY_BYTES_PER_QUEUE",
    "SM_RELAY_NEW_PER_HOUR",
    "SM_RELAY_BLOB_TTL_HOURS",
    "SM_RELAY_IDLE_HOURS",
    "SM_RELAY_TIMEOUT_MS",
    "SM_RELAY_WAIT_MS",
];

fn invalid() -> io::Error {
    io::ErrorKind::InvalidInput.into()
}
fn read_bounded(path: &Path, max: u64) -> io::Result<String> {
    let mut text = String::new();
    File::open(path)?
        .take(max.saturating_add(1))
        .read_to_string(&mut text)?;
    if u64::try_from(text.len()).map_err(|_| invalid())? > max {
        return Err(invalid());
    }
    Ok(text)
}
fn unhex<const N: usize>(text: &str) -> io::Result<[u8; N]> {
    if text.len() != N.saturating_mul(2) || !text.is_ascii() {
        return Err(invalid());
    }
    let mut out = [0; N];
    for (dst, pair) in out.iter_mut().zip(text.as_bytes().as_chunks::<2>().0) {
        *dst = u8::from_str_radix(std::str::from_utf8(pair).map_err(|_| invalid())?, 16)
            .map_err(|_| invalid())?;
    }
    Ok(out)
}
fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    bytes.iter().fold(String::new(), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    })
}
fn list<const N: usize>(text: &str, max: usize) -> io::Result<Vec<[u8; N]>> {
    let mut values = Vec::new();
    for value in text.split(',') {
        if values.len() >= max {
            return Err(invalid());
        }
        let value = unhex(value.trim())?;
        if values.contains(&value) {
            return Err(invalid());
        }
        values.push(value);
    }
    Ok(values)
}

pub(super) struct Prepared {
    config: Config,
    roots: Vec<[u8; ROOT_PUBLIC_LEN]>,
    revoked: Vec<PassId>,
    guest: Option<GuestKey>,
}
impl Prepared {
    pub(super) fn into_config(mut self) -> Config {
        // One process-lifetime configuration, not a per-request allocation/cache.
        self.config.roots = Box::leak(self.roots.into_boxed_slice());
        self.config.revoked = Box::leak(self.revoked.into_boxed_slice());
        self.config.guest_key = self.guest.map(|key| &*Box::leak(Box::new(key)));
        self.config
    }
}

fn parse(text: &str) -> io::Result<BTreeMap<String, String>> {
    let mut values = BTreeMap::new();
    for line in text
        .strip_prefix('\u{feff}')
        .unwrap_or(text)
        .lines()
        .map(str::trim)
    {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let (key, value) = line.split_once('=').ok_or_else(invalid)?;
        let (key, value) = (key.trim(), value.trim());
        let known = RESOURCE_KEYS.contains(&key)
            || matches!(
                key,
                "SM_RELAY_CONFIG_VERSION"
                    | "SM_RELAY_ROOT_KEYS"
                    | "SM_RELAY_LISTEN"
                    | "SM_RELAY_MODE"
                    | "SM_RELAY_REVOKED"
                    | "SM_RELAY_GUEST_KEY_FILE"
            );
        if !known
            || value.is_empty()
            || value.chars().any(char::is_control)
            || values.insert(key.to_owned(), value.to_owned()).is_some()
        {
            return Err(invalid());
        }
    }
    if values.get("SM_RELAY_CONFIG_VERSION").map(String::as_str) != Some("1") {
        return Err(invalid());
    }
    Ok(values)
}

pub(super) fn load(path: &Path) -> io::Result<Prepared> {
    let values = parse(&read_bounded(path, MAX_CONFIG)?)?;
    let roots = list(
        values.get("SM_RELAY_ROOT_KEYS").ok_or_else(invalid)?,
        MAX_ROOTS,
    )?;
    for root in &roots {
        PublicKey::deserialize(root).map_err(|_| invalid())?;
    }
    let addr: SocketAddr = values
        .get("SM_RELAY_LISTEN")
        .map_or("127.0.0.1:7400", String::as_str)
        .parse()
        .map_err(|_| invalid())?;
    if !addr.ip().is_loopback() {
        return Err(invalid());
    }
    let mut config = Config::new(addr);
    config.http = match values.get("SM_RELAY_MODE").map_or("http", String::as_str) {
        "http" => true,
        "tcp" => false,
        _ => return Err(invalid()),
    };
    config = settings::apply(config, |key| values.get(key).cloned())?;
    let revoked = values
        .get("SM_RELAY_REVOKED")
        .map(|v| list(v, sm_core::node::MAX_REVOKED))
        .transpose()?
        .unwrap_or_default();
    let guest = values
        .get("SM_RELAY_GUEST_KEY_FILE")
        .map(|name| {
            let key_path = path.parent().unwrap_or_else(|| Path::new(".")).join(name);
            GuestKey::from_hex(&read_bounded(&key_path, MAX_GUEST_KEY)?).ok_or_else(invalid)
        })
        .transpose()?;
    Ok(Prepared {
        config,
        roots,
        revoked,
        guest,
    })
}

pub(super) fn init(path: &Path) -> io::Result<()> {
    let roots: Vec<_> = ROOT_KEYS.iter().map(|r| hex(r)).collect();
    let text = format!(
        "# Local settings only. No messages or private owner key.\n\
        # Replace ROOT_KEYS with the trusted owner's PUBLIC key (33-byte HEX).\n\
        SM_RELAY_CONFIG_VERSION=1\nSM_RELAY_ROOT_KEYS={}\n\
        SM_RELAY_LISTEN=127.0.0.1:7400\nSM_RELAY_MODE=http\n\
        SM_RELAY_MEMORY_BYTES=402653184\nSM_RELAY_MAX_CONNECTIONS=64\n",
        roots.join(",")
    );
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt as _;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    file.write_all(text.as_bytes())?;
    file.sync_all()
}

#[derive(Debug, PartialEq, Eq)]
pub(super) enum Command {
    Legacy,
    Init(PathBuf),
    Check(PathBuf),
    Start(PathBuf),
    Help,
    Version,
}
impl Command {
    pub(super) fn parse(args: impl IntoIterator<Item = std::ffi::OsString>) -> io::Result<Self> {
        let mut it = args.into_iter();
        let Some(cmd) = it.next() else {
            return Ok(Self::Legacy);
        };
        let command = match cmd.to_str() {
            Some("--help" | "-h") => Self::Help,
            Some("--version" | "-V") => Self::Version,
            Some("init" | "check" | "start") => {
                if it.next().as_deref() != Some(std::ffi::OsStr::new("--config")) {
                    return Err(invalid());
                }
                let path = it.next().filter(|p| !p.is_empty()).ok_or_else(invalid)?;
                match cmd.to_str() {
                    Some("init") => Self::Init(path.into()),
                    Some("check") => Self::Check(path.into()),
                    _ => Self::Start(path.into()),
                }
            }
            _ => return Err(invalid()),
        };
        if it.next().is_some() {
            return Err(invalid());
        }
        Ok(command)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Temp(PathBuf);
    impl Temp {
        fn new() -> io::Result<Self> {
            use rand::TryRngCore as _;
            let mut id = [0; 16];
            rand::rngs::OsRng
                .try_fill_bytes(&mut id)
                .map_err(|_| io::Error::other("rng"))?;
            let path = std::env::temp_dir().join(format!("sm-relay-test-{}", hex(&id)));
            std::fs::create_dir(&path)?;
            Ok(Self(path))
        }
        fn path(&self) -> PathBuf {
            self.0.join("relay.conf")
        }
    }
    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    fn text(root: &[u8]) -> String {
        format!(
            "SM_RELAY_CONFIG_VERSION=1\nSM_RELAY_ROOT_KEYS={}\n",
            hex(root)
        )
    }
    #[test]
    fn template_loads_and_existing_config_is_not_overwritten() {
        let t = Temp::new().unwrap();
        init(&t.path()).unwrap();
        let before = std::fs::read(t.path()).unwrap();
        assert!(init(&t.path()).is_err());
        assert_eq!(std::fs::read(t.path()).unwrap(), before);
        let p = load(&t.path()).unwrap();
        assert_eq!(p.roots, ROOT_KEYS);
        assert!(p.config.http);
        assert!(p.config.listen.ip().is_loopback());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt as _;
            assert_eq!(
                std::fs::metadata(t.path()).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
    }
    #[test]
    fn roots_replace_defaults_without_rebuilding_and_require_valid_signature() {
        let t = Temp::new().unwrap();
        let owner = crate::common::Root::new();
        let stranger = crate::common::Root::new();
        std::fs::write(t.path(), text(&owner.roots[0])).unwrap();
        let config = load(&t.path()).unwrap().into_config();
        assert_eq!(config.roots, owner.roots);
        assert!(sm_relay::pass::check(&owner.pass(1, 10), config.roots, &[], 0).is_some());
        assert!(sm_relay::pass::check(&stranger.pass(1, 10), config.roots, &[], 0).is_none());
    }
    #[test]
    fn malformed_duplicate_unknown_and_missing_fields_fail_closed() {
        let t = Temp::new().unwrap();
        let base = text(&ROOT_KEYS[0]);
        for body in [
            String::new(),
            "SM_RELAY_CONFIG_VERSION=1\n".to_owned(),
            base.replace("VERSION=1", "VERSION=2"),
            base.replace("VERSION=1", "VERSION=01"),
            format!("{base}SM_RELAY_CONFIG_VERSION=1\n"),
            format!("{base}UNKNOWN=canary\n"),
            format!("{base}SM_RELAY_MODE=\n"),
            format!("{base}SM_RELAY_MODE=udp\n"),
            format!("{base}SM_RELAY_LISTEN=0.0.0.0:7400\n"),
            format!("{base}SM_RELAY_MEMORY_BYTES=1\n"),
            format!("{base}SM_RELAY_MAX_CONNECTIONS=0\n"),
            format!("{base}SM_RELAY_MAX_QUEUES=184467440737095516160\n"),
            format!("{base}SM_RELAY_STORE_SHARDS=3\n"),
            format!("{base}SM_RELAY_MODE=tcp\0\n"),
            base.replace(&hex(&ROOT_KEYS[0]), ""),
            base.replace(&hex(&ROOT_KEYS[0]), &"ab".repeat(33)),
        ] {
            std::fs::write(t.path(), body).unwrap();
            assert!(load(&t.path()).is_err());
        }
    }
    #[test]
    fn duplicate_roots_and_excess_lists_are_rejected() {
        let root = hex(&ROOT_KEYS[0]);
        assert!(list::<33>(&format!("{root},{root}"), MAX_ROOTS).is_err());
        assert!(list::<33>(&root, 0).is_err());
        assert!(list::<33>(&format!("{root},"), MAX_ROOTS).is_err());
        let ids: Vec<_> = (0u8..33).map(|i| hex(&[i; 16])).collect();
        assert!(list::<16>(&ids.join(","), 32).is_err());
        assert_eq!(list::<16>(&ids[..32].join(","), 32).unwrap().len(), 32);
    }
    #[test]
    fn utf8_bom_crlf_and_relative_guest_key_are_supported() {
        let t = Temp::new().unwrap();
        let key = t.0.join("ключ.txt");
        std::fs::write(&key, "ab".repeat(32)).unwrap();
        let content = format!(
            "\u{feff}# comment\r\n{}SM_RELAY_GUEST_KEY_FILE=ключ.txt\r\nSM_RELAY_MODE=tcp\r\n",
            text(&ROOT_KEYS[0]).replace('\n', "\r\n")
        );
        std::fs::write(t.path(), content).unwrap();
        let prepared = load(&t.path()).unwrap();
        assert!(prepared.guest.is_some());
        assert!(!prepared.config.http);
        std::fs::write(&key, "ab".repeat(65)).unwrap();
        assert!(load(&t.path()).is_err());
    }
    #[test]
    fn file_reads_are_bounded_and_invalid_utf8_is_rejected() {
        let t = Temp::new().unwrap();
        std::fs::write(t.path(), vec![b'#'; 16 << 10]).unwrap();
        assert_eq!(read_bounded(&t.path(), MAX_CONFIG).unwrap().len(), 16 << 10);
        std::fs::write(t.path(), vec![b'#'; (16 << 10) + 1]).unwrap();
        assert!(load(&t.path()).is_err());
        std::fs::write(t.path(), [255]).unwrap();
        assert!(load(&t.path()).is_err());
    }
    #[test]
    fn command_line_is_explicit_and_rejects_extra_arguments() {
        let parse = |args: &[&str]| Command::parse(args.iter().map(std::ffi::OsString::from));
        assert_eq!(parse(&[]).unwrap(), Command::Legacy);
        assert_eq!(parse(&["--help"]).unwrap(), Command::Help);
        assert_eq!(parse(&["--version"]).unwrap(), Command::Version);
        assert_eq!(
            parse(&["start", "--config", "a b.conf"]).unwrap(),
            Command::Start("a b.conf".into())
        );
        for args in [
            vec!["start"],
            vec!["--config", "a"],
            vec!["init", "a"],
            vec!["--help", "extra"],
            vec!["check", "--config", ""],
            vec!["start", "--config", "a", "extra"],
        ] {
            assert!(parse(&args).is_err());
        }
    }
}
