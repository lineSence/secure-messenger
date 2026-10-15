//! Bounded readiness-based relay. TLS remains outside the loopback listener.
use crate::{
    budget::{Budget, Lease},
    engine::{Engine, Route},
    http,
    notify::{Registry, Subscription, WakeQueue},
    pass::{self, GuestKey},
    store::{Limits, PassId, QueueId, Redeem, StoreError},
};
use mio::{Events, Interest, Poll, Token, Waker};
use sm_core::{
    node::{ROOT_KEYS, ROOT_PUBLIC_LEN},
    relay::{
        Command, ID_LEN, MAX_RESPONSE_DATA, REQUEST_HEADER, RequestHeader, Status, VERSION,
        parse_request_header,
    },
};
use std::{
    collections::{BTreeSet, HashMap, HashSet, VecDeque},
    io::{self, IoSlice, Read, Write},
    net::{SocketAddr, TcpListener},
    sync::{
        Arc, Mutex, PoisonError,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    thread::JoinHandle,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

/// Maximum WAIT, below the reverse proxy timeout.
pub const WAIT_MAX: Duration = Duration::from_secs(25);
const WORKER_STACK: usize = 2 << 20;
const CONNECTION_MEMORY: usize = 4 * sm_core::relay::MAX_BLOB + (64 << 10);
pub(crate) fn runtime_memory(connections: usize, workers: usize, shards: usize) -> Option<usize> {
    let bytes = connections
        .checked_mul(CONNECTION_MEMORY)?
        .checked_add(workers.checked_mul(WORKER_STACK)?)?
        .checked_add(shards.checked_mul(2048)?)?
        .checked_add(8 << 20)?;
    #[cfg(windows)]
    let bytes = bytes.checked_add(handoff_memory(connections, workers)?)?;
    Some(bytes)
}
#[cfg(windows)]
fn handoff_memory(connections: usize, workers: usize) -> Option<usize> {
    let slots = connections
        .checked_add(workers.checked_sub(1)?)?
        .checked_div(workers)?;
    // Bounded std channel slots, sequence tags and per-channel bookkeeping.
    slots
        .checked_add(1)?
        .checked_mul(std::mem::size_of::<Accepted>().checked_add(64)?)?
        .checked_add(4096)?
        .checked_mul(workers)
}
/// Настройки сервера.
#[derive(Debug, Clone, Copy)]
pub struct Config {
    /// Адрес; только loopback.
    pub listen: SocketAddr,
    /// Пределы хранилища.
    pub limits: Limits,
    /// Одновременных соединений; лишние закрываются сразу.
    pub max_connections: usize,
    /// Fixed readiness workers; not one thread per connection.
    pub io_workers: usize,
    /// Independent data stores; queue and sender capability route to the same store.
    pub store_shards: usize,
    /// Срок на весь кадр запроса (с начала ожидания) и на отправку ответа; потом соединение закрывается.
    pub idle_timeout: Duration,
    /// Сколько `WAIT` ждёт блоб при пустой очереди.
    pub wait: Duration,
    /// Приём HTTP/1.1 от nginx вместо кадров прямо по TCP.
    pub http: bool,
    /// Открытые корневые ключи владельца для пропусков на `NEW` (ADR-136).
    pub roots: &'static [[u8; ROOT_PUBLIC_LEN]],
    /// Отозванные пропуска (ADR-136).
    pub revoked: &'static [PassId],
    /// Постоянный ключ узла для гостевых пропусков (ADR-146); без него `TICKET` — `Unavailable`.
    pub guest_key: Option<&'static GuestKey>,
}

impl Config {
    /// Настройки по умолчанию из `relay.md` для адреса `listen`.
    #[must_use]
    pub fn new(listen: SocketAddr) -> Self {
        Self {
            listen,
            limits: Limits::default(),
            max_connections: 64,
            io_workers: 2,
            store_shards: 16,
            idle_timeout: Duration::from_secs(30),
            wait: WAIT_MAX,
            http: false,
            roots: ROOT_KEYS,
            revoked: &[],
            guest_key: None,
        }
    }
}

/// Running fixed-worker reactor. No per-connection OS threads.
pub struct Server {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    workers: Vec<JoinHandle<()>>,
    wakers: Vec<Arc<Waker>>,
    #[cfg(test)]
    shared: Arc<Shared>,
    #[cfg(test)]
    probes: Vec<Arc<AtomicUsize>>,
}
impl std::fmt::Debug for Server {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Server").finish_non_exhaustive()
    }
}
// Synthetic unit-test shutdown phases; no state or writes exist in production.
macro_rules! test_phase {
    ($reactor:expr, $phase:expr) => {
        #[cfg(test)]
        $reactor.probe.store($phase, Ordering::Release);
    };
}
impl Server {
    /// Bind a loopback listener and start a bounded set of reactors.
    /// # Errors
    /// Invalid configuration or OS resource failure; started workers are stopped on error.
    pub fn start(config: Config) -> io::Result<Self> {
        if !config.listen.ip().is_loopback() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        crate::settings::validate(&config)?;
        let budget = Budget::new(config.limits.memory_bytes);
        let runtime = runtime_memory(
            config.max_connections,
            config.io_workers,
            config.store_shards,
        )
        .and_then(|n| budget.reserve(n))
        .ok_or(io::ErrorKind::InvalidInput)?;
        let listener = TcpListener::bind(config.listen)?;
        listener.set_nonblocking(true)?;
        let addr = listener.local_addr()?;
        #[cfg(windows)]
        let mut listener = Some(listener);
        #[cfg(not(windows))]
        let listener = Arc::new(listener);
        let stop = Arc::new(AtomicBool::new(false));
        let shared = Arc::new(Shared {
            engine: Engine::new(config.limits, budget, config.store_shards),
            _runtime: runtime,
            arrived: Arc::new(Registry::default()),
            issuing: Mutex::new(()),
            active: Arc::new(AtomicUsize::new(0)),
            waiters: Arc::new(AtomicUsize::new(0)),
        });
        let mut reactors = Vec::new();
        let mut wakers = Vec::new();
        for i in 0..config.io_workers {
            let r = Reactor::new(
                #[cfg(windows)]
                listener.take(),
                #[cfg(not(windows))]
                Arc::clone(&listener),
                Arc::clone(&shared),
                Arc::clone(&stop),
                config,
                i == 0,
            )?;
            wakers.push(Arc::clone(&r.ready.waker));
            reactors.push(r);
        }
        #[cfg(windows)]
        {
            let targets = reactors.iter().map(|r| r.target.clone()).collect();
            if let Some(acceptor) = reactors.first_mut() {
                acceptor.targets = targets;
            }
        }
        #[cfg(test)]
        let probes = reactors.iter().map(|r| Arc::clone(&r.probe)).collect();
        let workers = Self::spawn_workers(reactors, &stop, &wakers)?;
        Ok(Self {
            addr,
            stop,
            workers,
            wakers,
            #[cfg(test)]
            shared,
            #[cfg(test)]
            probes,
        })
    }
    fn spawn_workers(
        reactors: Vec<Reactor>,
        stop: &Arc<AtomicBool>,
        wakers: &[Arc<Waker>],
    ) -> io::Result<Vec<JoinHandle<()>>> {
        let mut workers = Vec::new();
        for mut r in reactors {
            #[cfg(test)]
            let probe = Arc::clone(&r.probe);
            match std::thread::Builder::new()
                .name("relay-io".into())
                .stack_size(WORKER_STACK)
                .spawn(move || {
                    r.run();
                    test_phase!(r, 9);
                    drop(r);
                    #[cfg(test)]
                    probe.store(10, Ordering::Release);
                }) {
                Ok(h) => workers.push(h),
                Err(e) => {
                    stop.store(true, Ordering::Release);
                    for w in wakers {
                        let _ = w.wake();
                    }
                    for h in workers {
                        let _ = h.join();
                    }
                    return Err(e);
                }
            }
        }
        Ok(workers)
    }
    /// Actual bound address.
    #[must_use]
    pub const fn addr(&self) -> SocketAddr {
        self.addr
    }
    /// Wait for workers (the executable runs until externally stopped).
    pub fn wait(mut self) {
        for h in self.workers.drain(..) {
            let _ = h.join();
        }
    }
    /// Wake and join all fixed workers; dropping sockets cancels pending I/O and WAIT.
    pub fn stop(mut self) {
        self.stop.store(true, Ordering::Release);
        for w in &self.wakers {
            let _ = w.wake();
        }
        for h in self.workers.drain(..) {
            let _ = h.join();
        }
    }
}
struct Shared {
    engine: Engine,
    _runtime: Lease,
    arrived: Arc<Registry>,
    issuing: Mutex<()>,
    active: Arc<AtomicUsize>,
    waiters: Arc<AtomicUsize>,
}
struct Permit(Arc<AtomicUsize>);
impl Permit {
    fn take(counter: &Arc<AtomicUsize>, limit: usize) -> Option<Self> {
        counter
            .try_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                (n < limit).then(|| n.saturating_add(1))
            })
            .ok()?;
        Some(Self(Arc::clone(counter)))
    }
}
impl Drop for Permit {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}
fn deadline_in(t: Duration) -> Instant {
    Instant::now().checked_add(t).unwrap_or_else(Instant::now)
}
fn wait_limit(max: usize) -> usize {
    max.saturating_sub((max / 4).max(1))
}

struct Parked {
    routes: Vec<Route>,
    subscription: Subscription,
    _permit: Permit,
    http_close: Option<bool>,
    hour: u64,
}
struct Output {
    head: Vec<u8>,
    data: ReplyData,
    sent: usize,
    close: bool,
}
impl Output {
    fn new(reply: Reply, http_close: Option<bool>, close: bool, version: u8) -> io::Result<Self> {
        let (status, data) = reply;
        let (a, b) = data.parts();
        let len = a
            .len()
            .checked_add(b.len())
            .filter(|&n| n <= MAX_RESPONSE_DATA)
            .ok_or(io::ErrorKind::InvalidData)?;
        let mut head = http_close.map_or_else(Vec::new, |c| {
            http::ok_head(len.saturating_add(6), c || close)
        });
        head.extend_from_slice(&[version, status.code()]);
        head.extend_from_slice(
            &u32::try_from(len)
                .map_err(|_| io::ErrorKind::InvalidData)?
                .to_be_bytes(),
        );
        Ok(Self {
            head,
            data,
            sent: 0,
            close,
        })
    }
    // None: would-block; Some(false): bounded work exhausted; Some(true): fully sent.
    fn flush(&mut self, stream: &mut mio::net::TcpStream) -> io::Result<Option<bool>> {
        for _ in 0..16 {
            let (a, b) = self.data.parts();
            let total = self
                .head
                .len()
                .saturating_add(a.len())
                .saturating_add(b.len());
            if self.sent == total {
                return Ok(Some(true));
            }
            let mut skip = self.sent;
            let mut slices = [IoSlice::new(&[]); 3];
            let mut count = 0usize;
            for part in [self.head.as_slice(), a, b] {
                if skip >= part.len() {
                    skip = skip.saturating_sub(part.len());
                    continue;
                }
                let tail = part.get(skip..).ok_or(io::ErrorKind::InvalidData)?;
                if let Some(slot) = slices.get_mut(count) {
                    *slot = IoSlice::new(tail);
                }
                count = count.saturating_add(1);
                skip = 0;
            }
            match stream.write_vectored(slices.get(..count).ok_or(io::ErrorKind::InvalidData)?) {
                Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
                Ok(n) => self.sent = self.sent.checked_add(n).ok_or(io::ErrorKind::InvalidData)?,
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => return Ok(None),
                Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
                Err(e) => return Err(e),
            }
        }
        Ok(Some(false))
    }
}
enum Parsed {
    Need(usize),
    Frame(RequestHeader, Vec<u8>, Option<bool>, u8),
    BadHttp,
    BadWire,
}
#[derive(Clone, Copy)]
enum DecodeState {
    Head(usize),
    Wire {
        offset: usize,
        length: Option<usize>,
        close: Option<bool>,
    },
    Body {
        req: RequestHeader,
        header_end: usize,
        end: usize,
        close: Option<bool>,
        version: u8,
    },
}
struct Decoder(DecodeState);
impl Default for Decoder {
    fn default() -> Self {
        Self(DecodeState::Head(0))
    }
}
impl Decoder {
    fn parse(&mut self, input: &mut Vec<u8>, http_mode: bool) -> Parsed {
        if let DecodeState::Head(from) = self.0 {
            let (offset, length, close) = if http_mode {
                let Some(end) = input
                    .get(from..)
                    .and_then(http::head_end)
                    .and_then(|n| from.checked_add(n))
                else {
                    self.0 = DecodeState::Head(input.len().saturating_sub(3));
                    return if input.len() >= http::MAX_HEAD {
                        Parsed::BadHttp
                    } else {
                        Parsed::Need(1024.min(http::MAX_HEAD.saturating_sub(input.len())))
                    };
                };
                let offset = end.saturating_add(http::HEAD_END.len());
                if offset > http::MAX_HEAD {
                    return Parsed::BadHttp;
                }
                let Some(raw) = input.get(..end) else {
                    return Parsed::BadHttp;
                };
                let Ok(head) = http::parse_head(raw) else {
                    return Parsed::BadHttp;
                };
                if head.content_length < REQUEST_HEADER {
                    return Parsed::BadHttp;
                }
                (offset, Some(head.content_length), Some(head.close))
            } else {
                (0, None, None)
            };
            self.0 = DecodeState::Wire {
                offset,
                length,
                close,
            };
        }
        if let DecodeState::Wire {
            offset,
            length,
            close,
        } = self.0
        {
            let header_end = offset.saturating_add(REQUEST_HEADER);
            let Some(raw) = input.get(offset..header_end) else {
                return Parsed::Need(header_end.saturating_sub(input.len()));
            };
            let Ok(raw) = <[u8; REQUEST_HEADER]>::try_from(raw) else {
                return Parsed::BadWire;
            };
            let version = raw.first().copied().unwrap_or(0);
            let parsed = if version == sm_core::relay_batch::VERSION {
                sm_core::relay_batch::parse_header(&raw).map(|len| RequestHeader {
                    cmd: Command::Ping,
                    id: [0; 32],
                    len,
                })
            } else {
                parse_request_header(&raw)
            };
            let Ok(req) = parsed else {
                return if http_mode {
                    Parsed::BadHttp
                } else {
                    Parsed::BadWire
                };
            };
            if length.is_some_and(|n| REQUEST_HEADER.checked_add(req.len) != Some(n)) {
                return Parsed::BadHttp;
            }
            self.0 = DecodeState::Body {
                req,
                header_end,
                end: header_end.saturating_add(req.len),
                close,
                version,
            };
        }
        let DecodeState::Body {
            req,
            header_end,
            end,
            close,
            version,
        } = self.0
        else {
            return Parsed::BadWire;
        };
        if input.len() < end {
            return Parsed::Need(end.saturating_sub(input.len()));
        }
        let remainder = input.split_off(end);
        let mut body = std::mem::replace(input, remainder);
        body.drain(..header_end);
        *self = Self::default();
        Parsed::Frame(req, body, close, version)
    }
}
struct Connection {
    stream: mio::net::TcpStream,
    input: Vec<u8>,
    decoder: Decoder,
    interest: Interest,
    output: Option<Output>,
    parked: Option<Parked>,
    deadline: Instant,
    _permit: Permit,
}
impl Connection {
    #[cfg(windows)]
    fn finish_windows_close(&mut self, scratch: &mut [u8]) -> io::Result<()> {
        // Unread bytes at close can reset Winsock and discard the error reply.
        // Half-close and drain bounded available input; never wait on a peer.
        let _ = self.stream.shutdown(std::net::Shutdown::Write);
        let mut remaining = 64usize << 10;
        for _ in 0..16 {
            let n = remaining.min(scratch.len());
            let buf = scratch.get_mut(..n).ok_or(io::ErrorKind::InvalidData)?;
            match self.stream.read(buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => remaining = remaining.saturating_sub(n),
            }
            if remaining == 0 {
                break;
            }
        }
        Ok(())
    }
    fn parse(&mut self, http_mode: bool) -> Parsed {
        self.decoder.parse(&mut self.input, http_mode)
    }
    fn reply(
        &mut self,
        reply: Reply,
        http_close: Option<bool>,
        close: bool,
        config: Config,
    ) -> io::Result<()> {
        self.parked.take();
        self.output = Some(Output::new(
            reply,
            http_close,
            close || http_close == Some(true),
            VERSION,
        )?);
        self.deadline = deadline_in(config.idle_timeout);
        Ok(())
    }
    fn dispatch(
        &mut self,
        frame: (RequestHeader, Vec<u8>, Option<bool>, u8),
        shared: &Shared,
        ready: &Arc<WakeQueue>,
        token: usize,
        config: Config,
    ) -> io::Result<()> {
        let (req, body, close, version) = frame;
        if version == sm_core::relay_batch::VERSION {
            let reply = handle_batch(shared, &body, &config);
            let error = reply.0 == Status::BadRequest;
            self.output = Some(Output::new(
                reply,
                close,
                error || close == Some(true),
                version,
            )?);
            self.deadline = deadline_in(config.idle_timeout);
            return Ok(());
        }

        if req.cmd == Command::Wait {
            let ids = queues(&req, &body);
            if let Some(reply) = fetch_any(&shared.engine, &ids, now_hour()) {
                self.reply(reply, close, false, config)?;
                return Ok(());
            }
            let Some(permit) = Permit::take(&shared.waiters, wait_limit(config.max_connections))
            else {
                self.reply(plain(Status::Unavailable, Vec::new()), close, true, config)?;
                return Ok(());
            };
            // Register before rechecking to close SEND-vs-park lost-wake window.
            let subscription = shared.arrived.subscribe(&ids, Arc::clone(ready), token);
            self.deadline = deadline_in(config.wait);
            self.parked = Some(Parked {
                routes: ids.iter().map(|id| shared.engine.route(id)).collect(),
                subscription,
                _permit: permit,
                http_close: close,
                hour: now_hour(),
            });
            if let Some(wait) = &self.parked
                && let Some(reply) = fetch_prepared(&shared.engine, &wait.routes, now_hour())
            {
                self.reply(reply, close, false, config)?;
            }
        } else {
            let reply = handle(shared, &req, body, &config);
            let must_close = reply.0 == Status::BadRequest;
            self.reply(reply, close, must_close, config)?;
        }

        Ok(())
    }

    // false = no immediate work; true = budget exhausted, schedule another turn.
    fn drive(
        &mut self,
        shared: &Shared,
        ready: &Arc<WakeQueue>,
        token: usize,
        config: Config,
        scratch: &mut [u8],
    ) -> io::Result<bool> {
        let mut read_budget = 256usize << 10;
        for _ in 0..32 {
            if let Some(out) = self.output.as_mut() {
                match out.flush(&mut self.stream)? {
                    Some(true) => {
                        let close = out.close;
                        self.output.take();
                        if close {
                            #[cfg(windows)]
                            self.finish_windows_close(scratch)?;
                            return Err(io::ErrorKind::ConnectionAborted.into());
                        }
                        self.deadline = deadline_in(config.idle_timeout);
                    }
                    Some(false) => return Ok(true),
                    None => return Ok(false),
                }
                continue;
            }
            if let Some(wait) = self.parked.as_mut() {
                let now = now_hour();
                if wait.subscription.take_ready() || wait.hour != now {
                    wait.hour = now;
                    if let Some(reply) = fetch_prepared(&shared.engine, &wait.routes, now) {
                        let close = wait.http_close;
                        self.reply(reply, close, false, config)?;
                        continue;
                    }
                }
                if Instant::now() >= self.deadline {
                    let close = wait.http_close;
                    self.reply(plain(Status::Empty, Vec::new()), close, false, config)?;
                    continue;
                }
                // Readiness/EOF, not periodic socket peeking. One pending frame is bounded.
                let capacity = http::MAX_BODY
                    .saturating_add(http::MAX_HEAD)
                    .saturating_add(1024)
                    .saturating_sub(self.input.len());
                if capacity == 0 {
                    return Err(io::ErrorKind::InvalidData.into());
                }
                let n = capacity.min(scratch.len()).min(1024);
                match self
                    .stream
                    .read(scratch.get_mut(..n).ok_or(io::ErrorKind::InvalidData)?)
                {
                    Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
                    Ok(n) => {
                        self.input
                            .extend_from_slice(scratch.get(..n).ok_or(io::ErrorKind::InvalidData)?);
                        return Ok(true);
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => return Ok(false),
                    Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Err(e) => return Err(e),
                }
            }
            if Instant::now() >= self.deadline {
                return Err(io::ErrorKind::TimedOut.into());
            }
            match self.parse(config.http) {
                Parsed::BadHttp => {
                    self.output = Some(Output {
                        head: http::BAD_REQUEST.to_vec(),
                        data: ReplyData::Plain(Vec::new()),
                        sent: 0,
                        close: true,
                    });
                    self.deadline = deadline_in(config.idle_timeout);
                }
                Parsed::BadWire => {
                    self.reply(plain(Status::BadRequest, Vec::new()), None, true, config)?;
                }
                Parsed::Need(want) => {
                    if read_budget == 0 {
                        return Ok(true);
                    }
                    let n = want.min(scratch.len()).min(read_budget);
                    match self
                        .stream
                        .read(scratch.get_mut(..n).ok_or(io::ErrorKind::InvalidData)?)
                    {
                        Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
                        Ok(n) => {
                            read_budget = read_budget.saturating_sub(n);
                            self.input.extend_from_slice(
                                scratch.get(..n).ok_or(io::ErrorKind::InvalidData)?,
                            );
                        }
                        Err(e) if e.kind() == io::ErrorKind::WouldBlock => return Ok(false),
                        Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
                        Err(e) => return Err(e),
                    }
                }
                Parsed::Frame(req, body, close, version) => {
                    self.dispatch((req, body, close, version), shared, ready, token, config)?;
                }
            }
        }
        Ok(true)
    }
}
#[cfg(windows)]
struct Accepted {
    stream: mio::net::TcpStream,
    permit: Permit,
}
#[cfg(windows)]
#[derive(Clone)]
struct AcceptTarget {
    sender: std::sync::mpsc::SyncSender<Accepted>,
    waker: Arc<Waker>,
}
struct Reactor {
    #[cfg(test)]
    probe: Arc<AtomicUsize>,
    poll: Poll,
    #[cfg(windows)]
    listener: Option<mio::net::TcpListener>,
    #[cfg(not(windows))]
    listener: mio::net::TcpListener,
    // Mio/Windows may retain SIO_BASE_HANDLE from the original socket, not its clone.
    // Keep that handle alive until this reactor and its registered listener are gone.
    #[cfg(not(windows))]
    _listener_source: Arc<TcpListener>,
    #[cfg(windows)]
    target: AcceptTarget,
    #[cfg(windows)]
    incoming: std::sync::mpsc::Receiver<Accepted>,
    #[cfg(windows)]
    targets: Vec<AcceptTarget>,
    #[cfg(windows)]
    next_worker: usize,
    #[cfg(windows)]
    incoming_pending: bool,
    ready: Arc<WakeQueue>,
    shared: Arc<Shared>,
    stop: Arc<AtomicBool>,
    config: Config,
    connections: HashMap<usize, Connection>,
    timers: BTreeSet<(Instant, usize)>,
    todo: HashSet<usize>,
    spare: HashSet<usize>,
    wakeups: VecDeque<usize>,
    next_token: usize,
    maintenance: Option<Instant>,
    hour: u64,
    accept_pending: bool,
}
impl Reactor {
    fn new(
        #[cfg(windows)] listener: Option<TcpListener>,
        #[cfg(not(windows))] listener: Arc<TcpListener>,
        shared: Arc<Shared>,
        stop: Arc<AtomicBool>,
        config: Config,
        maintain: bool,
    ) -> io::Result<Self> {
        let poll = Poll::new()?;
        #[cfg(not(windows))]
        let source = listener;
        #[cfg(not(windows))]
        let mut listener = mio::net::TcpListener::from_std(source.try_clone()?);
        #[cfg(windows)]
        let mut listener = listener.map(mio::net::TcpListener::from_std);
        #[cfg(not(windows))]
        poll.registry()
            .register(&mut listener, Token(0), Interest::READABLE)?;
        #[cfg(windows)]
        if let Some(listener) = listener.as_mut() {
            poll.registry()
                .register(listener, Token(0), Interest::READABLE)?;
        }
        let waker = Arc::new(Waker::new(poll.registry(), Token(1))?);
        #[cfg(windows)]
        let (sender, incoming) =
            std::sync::mpsc::sync_channel(config.max_connections.div_ceil(config.io_workers));
        Ok(Self {
            #[cfg(test)]
            probe: Arc::new(AtomicUsize::new(0)),
            poll,
            listener,
            #[cfg(not(windows))]
            _listener_source: source,
            #[cfg(windows)]
            target: AcceptTarget {
                sender,
                waker: Arc::clone(&waker),
            },
            #[cfg(windows)]
            incoming,
            #[cfg(windows)]
            targets: Vec::new(),
            #[cfg(windows)]
            next_worker: 0,
            #[cfg(windows)]
            incoming_pending: true,
            ready: WakeQueue::new(waker),
            shared,
            stop,
            config,
            connections: HashMap::new(),
            timers: BTreeSet::new(),
            todo: HashSet::new(),
            spare: HashSet::new(),
            wakeups: VecDeque::new(),
            next_token: 2,
            maintenance: maintain.then(Instant::now),
            hour: now_hour(),
            accept_pending: false,
        })
    }
    fn accept(&mut self) {
        self.accept_pending = true;
        for _ in 0..64 {
            #[cfg(not(windows))]
            let accepted = self.listener.accept();
            #[cfg(windows)]
            let accepted = if let Some(listener) = self.listener.as_ref() {
                listener.accept()
            } else {
                self.accept_pending = false;
                return;
            };
            match accepted {
                Ok((stream, _)) => {
                    #[cfg(windows)]
                    let stream = {
                        // Explicitly enforce the reactor precondition on accepted sockets;
                        // do not rely on inheritance through a duplicated listener.
                        let stream = std::net::TcpStream::from(stream);
                        if stream.set_nonblocking(true).is_err() {
                            continue;
                        }
                        mio::net::TcpStream::from_std(stream)
                    };
                    let Some(permit) =
                        Permit::take(&self.shared.active, self.config.max_connections)
                    else {
                        continue;
                    };
                    #[cfg(windows)]
                    self.distribute(Accepted { stream, permit });
                    #[cfg(not(windows))]
                    self.attach(stream, permit);
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                    self.accept_pending = false;
                    break;
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
                Err(_) => {
                    self.accept_pending = false;
                    break;
                }
            }
        }
    }
    fn attach(&mut self, mut stream: mio::net::TcpStream, permit: Permit) {
        let Some(next) = self.next_token.checked_add(1) else {
            return;
        };
        let token = self.next_token;
        self.next_token = next;
        if stream.set_nodelay(true).is_err()
            || self
                .poll
                .registry()
                .register(&mut stream, Token(token), Interest::READABLE)
                .is_err()
        {
            return;
        }
        let deadline = deadline_in(self.config.idle_timeout);
        self.connections.insert(
            token,
            Connection {
                stream,
                input: Vec::new(),
                decoder: Decoder::default(),
                interest: Interest::READABLE,
                output: None,
                parked: None,
                deadline,
                _permit: permit,
            },
        );
        self.timers.insert((deadline, token));
        self.todo.insert(token);
    }
    #[cfg(windows)]
    fn distribute(&mut self, mut accepted: Accepted) {
        // One acceptor, multiple I/O reactors. try_send never waits for a slow worker.
        for _ in 0..self.targets.len() {
            let index = self.next_worker;
            self.next_worker = index
                .saturating_add(1)
                .checked_rem(self.targets.len())
                .unwrap_or(0);
            let Some(target) = self.targets.get(index) else {
                break;
            };
            match target.sender.try_send(accepted) {
                Ok(()) => {
                    let _ = target.waker.wake();
                    return;
                }
                Err(
                    std::sync::mpsc::TrySendError::Full(item)
                    | std::sync::mpsc::TrySendError::Disconnected(item),
                ) => accepted = item,
            }
        }
        // All queues full/closed: dropping the socket also returns its global permit.
    }
    #[cfg(windows)]
    fn receive_connections(&mut self) {
        self.incoming_pending = true;
        for _ in 0..64 {
            if let Ok(item) = self.incoming.try_recv() {
                self.attach(item.stream, item.permit);
            } else {
                self.incoming_pending = false;
                break;
            }
        }
    }
    fn poll_timeout(&self) -> Duration {
        let now = Instant::now();
        let mut timeout = Duration::from_secs(3600u64.saturating_sub(now_secs() % 3600));
        if let Some((deadline, _)) = self.timers.first() {
            timeout = timeout.min(deadline.saturating_duration_since(now));
        }
        if let Some(at) = self.maintenance {
            timeout = timeout.min(at.saturating_duration_since(now));
        }
        #[cfg(windows)]
        if self.incoming_pending {
            timeout = Duration::ZERO;
        }
        if !self.todo.is_empty() || self.accept_pending {
            timeout = Duration::ZERO;
        }
        timeout
    }
    fn service_deadlines(&mut self) {
        let hour = now_hour();
        if self.hour != hour {
            self.hour = hour;
            self.todo.extend(
                self.connections
                    .iter()
                    .filter_map(|(&t, c)| c.parked.as_ref().map(|_| t)),
            );
        }
        let now = Instant::now();
        while let Some(&(at, token)) = self.timers.first() {
            if at > now {
                break;
            }
            self.timers.pop_first();
            self.todo.insert(token);
        }
        if self.maintenance.is_some_and(|at| at <= now) {
            let backlog = self.shared.engine.maintain(hour, 256);
            self.maintenance = Some(deadline_in(if backlog {
                Duration::from_millis(10)
            } else {
                Duration::from_secs(1)
            }));
        }
    }
    fn run(&mut self) {
        let mut events = Events::with_capacity(128);
        // One initialized scratch per worker, charged by the existing worker-stack reserve.
        let mut scratch = [0; 16 << 10];
        while !self.stop.load(Ordering::Acquire) {
            test_phase!(self, 1);
            if self.accept_pending {
                self.accept();
            }
            let timeout = self.poll_timeout();
            test_phase!(self, 2);
            match self.poll.poll(&mut events, Some(timeout)) {
                Ok(()) => {}
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
            #[cfg(windows)]
            self.receive_connections();
            test_phase!(self, 3);
            for e in &events {
                let token = e.token().0;
                if token == 0 {
                    self.accept_pending = true;
                } else if token != 1 {
                    if (e.is_error() || e.is_write_closed() || e.is_read_closed())
                        && self
                            .connections
                            .get(&token)
                            .is_some_and(|c| c.parked.is_some())
                    {
                        self.remove(token);
                    } else {
                        self.todo.insert(token);
                    }
                }
            }
            self.ready.drain_into(&mut self.wakeups);
            self.todo.extend(self.wakeups.drain(..));
            test_phase!(self, 4);
            self.service_deadlines();
            let mut todo = std::mem::take(&mut self.spare);
            std::mem::swap(&mut todo, &mut self.todo);
            for token in todo.drain() {
                let Some(c) = self.connections.get_mut(&token) else {
                    continue;
                };
                let old_deadline = c.deadline;
                test_phase!(self, 5);
                let driven = if c.output.is_some() && Instant::now() >= c.deadline {
                    Err(io::ErrorKind::TimedOut.into())
                } else {
                    c.drive(&self.shared, &self.ready, token, self.config, &mut scratch)
                };
                test_phase!(self, 6);
                let mut failed = false;
                match driven {
                    Ok(again) => {
                        let interest = if c.output.is_some() {
                            Interest::WRITABLE
                        } else {
                            Interest::READABLE
                        };
                        if (interest != c.interest || !cfg!(target_os = "linux"))
                            && self
                                .poll
                                .registry()
                                .reregister(&mut c.stream, Token(token), interest)
                                .is_err()
                        {
                            failed = true;
                        } else {
                            c.interest = interest;
                            if again {
                                self.todo.insert(token);
                            }
                        }
                    }
                    Err(_) => {
                        failed = true;
                    }
                }
                if failed {
                    self.timers.remove(&(old_deadline, token));
                    self.remove(token);
                } else if old_deadline != c.deadline {
                    self.timers.remove(&(old_deadline, token));
                    self.timers.insert((c.deadline, token));
                }
            }
            self.spare = todo;
        }
        test_phase!(self, 7);
        self.connections.clear();
        test_phase!(self, 8);
    }
    fn remove(&mut self, token: usize) {
        if let Some(mut c) = self.connections.remove(&token) {
            self.timers.remove(&(c.deadline, token));
            let _ = self.poll.registry().deregister(&mut c.stream);
        }
    }
}
enum ReplyData {
    Plain(Vec<u8>),
    Blob {
        prefix: [u8; 9],
        bytes: Arc<crate::store::Payload>,
    },
}
impl From<Vec<u8>> for ReplyData {
    fn from(v: Vec<u8>) -> Self {
        Self::Plain(v)
    }
}
impl ReplyData {
    fn parts(&self) -> (&[u8], &[u8]) {
        match self {
            Self::Plain(v) => (v, &[]),
            Self::Blob { prefix, bytes } => (prefix, bytes),
        }
    }
}
type Reply = (Status, ReplyData);
fn plain(status: Status, data: Vec<u8>) -> Reply {
    (status, data.into())
}

/// Текущий час с начала эпохи Unix (точность хранения времени — до часа).
fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

fn now_hour() -> u64 {
    now_secs() / 3600
}

fn store_status(e: StoreError) -> Status {
    match e {
        StoreError::NoQueue => Status::NoQueue,
        StoreError::TooBig => Status::TooBig,
        StoreError::Quota | StoreError::RateLimited => Status::Quota,
        StoreError::Collision | StoreError::Unavailable => Status::Unavailable,
        StoreError::BadRequest => Status::BadRequest,
    }
}

/// Очереди запроса `FETCH`/`WAIT` (ADR-114): ID из заголовка, затем ID из данных.
fn queues(req: &RequestHeader, body: &[u8]) -> Vec<QueueId> {
    let mut ids = vec![req.id];
    ids.extend(body.as_chunks::<ID_LEN>().0.iter().copied());
    ids
}

/// FIFO внутри очереди, round-robin между очередями списка: данные `[номер в списке][номер блоба][блоб]`;
/// очереди нет — `NO_QUEUE` с её номером в списке.
fn fetch_any(store: &Engine, ids: &[QueueId], now: u64) -> Option<Reply> {
    fetch_routes(
        store,
        ids.len(),
        |i| ids.get(i).map(|id| store.route(id)),
        now,
    )
}
fn fetch_prepared(store: &Engine, routes: &[Route], now: u64) -> Option<Reply> {
    fetch_routes(store, routes.len(), |i| routes.get(i).copied(), now)
}
fn blob_reply(index: u8, seq: u64, bytes: Arc<crate::store::Payload>) -> Reply {
    let mut prefix = [0; 9];
    if let Some(first) = prefix.first_mut() {
        *first = index;
    }
    if let Some(rest) = prefix.get_mut(1..) {
        rest.copy_from_slice(&seq.to_be_bytes());
    }
    (Status::Ok, ReplyData::Blob { prefix, bytes })
}
fn fetch_routes(
    store: &Engine,
    count: usize,
    route_at: impl Fn(usize) -> Option<Route>,
    now: u64,
) -> Option<Reply> {
    let anchor = route_at(0)?;
    if count == 1 {
        return match store.fetch_one(&anchor, now) {
            Ok(Some((seq, blob))) => Some(blob_reply(0, seq, blob)),
            Ok(None) => None,
            Err(StoreError::NoQueue) => Some(plain(Status::NoQueue, vec![0])),
            Err(e) => Some(plain(store_status(e), Vec::new())),
        };
    }
    let turn = store.fetch_start(&anchor);
    for offset in 0..count {
        let i = turn.wrapping_add(offset).checked_rem(count).unwrap_or(0);
        let route = if i == 0 { anchor } else { route_at(i)? };
        let Ok(index) = u8::try_from(i) else {
            return Some(plain(Status::BadRequest, Vec::new()));
        };
        match store.fetch_routed(&route, now) {
            Ok(Some((seq, blob))) => {
                store.advance_fetch(&anchor, i.wrapping_add(1));
                return Some(blob_reply(index, seq, blob));
            }
            Ok(None) => {}
            Err(StoreError::NoQueue) => return Some(plain(Status::NoQueue, vec![index])),
            Err(e) => return Some(plain(store_status(e), Vec::new())),
        }
    }
    None
}

fn issue_guest(shared: &Shared, key: &GuestKey, ticket: &QueueId, now: u64) -> Reply {
    let lock = || shared.engine.control();
    let _issuing = shared
        .issuing
        .lock()
        .unwrap_or_else(PoisonError::into_inner);
    let redeemed = lock().redeem_ticket(ticket, now);
    match redeemed {
        None => plain(Status::Denied, Vec::new()),
        Some(Redeem::Done(g)) => plain(Status::Ok, g),
        Some(Redeem::Fresh(grant)) => match pass::issue_guest(key, &grant, now_secs()) {
            Some(g) => {
                let mut store = lock();
                if store.redeem_ticket(ticket, now_hour()).is_none() {
                    return plain(Status::Denied, Vec::new());
                }
                if let Err(e) = store.finish_ticket(ticket, g.clone()) {
                    return plain(store_status(e), Vec::new());
                }
                plain(Status::Ok, g)
            }
            None => plain(Status::Unavailable, Vec::new()),
        },
    }
}

fn handle(shared: &Shared, req: &RequestHeader, body: Vec<u8>, config: &Config) -> Reply {
    let Config {
        roots,
        revoked,
        guest_key,
        ..
    } = *config;
    let now = now_hour();
    let done = |r: Result<(), StoreError>| {
        r.map_or_else(
            |e| plain(store_status(e), Vec::new()),
            |()| plain(Status::Ok, Vec::new()),
        )
    };
    match req.cmd {
        Command::Ping => plain(Status::Ok, Vec::new()),
        Command::New => {
            let Some(p) = pass::check_any(&body, roots, guest_key, revoked, now_secs()) else {
                return plain(Status::Denied, Vec::new());
            };
            match shared.engine.open(req.id, &p.id, p.per_hour, now) {
                Ok(sid) => plain(Status::Ok, sid.to_vec()),
                Err(e) => plain(store_status(e), Vec::new()),
            }
        }
        Command::Ticket => {
            if guest_key.is_none() {
                return plain(Status::Unavailable, Vec::new());
            }
            let Some(by) = pass::check_any(&body, roots, guest_key, revoked, now_secs()) else {
                return plain(Status::Denied, Vec::new());
            };
            let Some(ticket) = pass::new_ticket() else {
                return plain(Status::Unavailable, Vec::new());
            };
            match shared.engine.issue_ticket(ticket, &by, now) {
                Ok(()) => plain(Status::Ok, ticket.to_vec()),
                Err(e) => plain(store_status(e), Vec::new()),
            }
        }
        Command::Guest => {
            let Some(key) = guest_key else {
                return plain(Status::Unavailable, Vec::new());
            };
            issue_guest(shared, key, &req.id, now)
        }
        Command::Send => match shared.engine.send(&req.id, body, now) {
            Ok(id) => {
                if let Some(id) = id {
                    shared.arrived.notify(&id);
                }
                done(Ok(()))
            }
            Err(e) => done(Err(e)),
        },
        // WAIT is parked by the reactor, never executed synchronously here.
        Command::Wait => plain(Status::Unavailable, Vec::new()),
        Command::Fetch => fetch_any(&shared.engine, &queues(req, &body), now)
            .unwrap_or(plain(Status::Empty, Vec::new())),
        Command::Ack => {
            let Ok(seq) = <[u8; 8]>::try_from(body.as_slice()) else {
                return plain(Status::BadRequest, Vec::new());
            };
            done(shared.engine.ack(&req.id, u64::from_be_bytes(seq), now))
        }
        Command::Del => {
            let result = shared.engine.delete(&req.id, now);
            if result.is_ok() {
                shared.arrived.notify(&req.id);
            }
            done(result)
        }
    }
}

fn handle_batch(shared: &Shared, body: &[u8], config: &Config) -> Reply {
    let Ok(items) = sm_core::relay_batch::decode_request(body) else {
        return plain(Status::BadRequest, Vec::new());
    };
    let count = items.len();
    let Ok(wire_count) = u8::try_from(count) else {
        return plain(Status::BadRequest, Vec::new());
    };
    let mut out = vec![wire_count];
    for (index, item) in items.into_iter().enumerate() {
        let (mut status, data) = handle(shared, &item.header, item.data.to_vec(), config);
        let (a, b) = data.parts();
        let len = a.len().saturating_add(b.len());
        let reserved = count
            .saturating_sub(index)
            .saturating_sub(1)
            .saturating_mul(5);
        let fits = out
            .len()
            .saturating_add(5)
            .saturating_add(len)
            .saturating_add(reserved)
            <= MAX_RESPONSE_DATA;
        let size = if fits {
            len
        } else {
            status = Status::TooBig;
            0
        };
        out.push(status.code());
        out.extend_from_slice(&u32::try_from(size).unwrap_or(0).to_be_bytes());
        if fits {
            out.extend_from_slice(a);
            out.extend_from_slice(b);
        }
    }
    plain(Status::Ok, out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_shutdown_probe() {
        use std::net::TcpStream;
        use std::sync::mpsc;
        for workers in [1, 2, 4] {
            for turn in 0..64 {
                let mut config = Config::new(([127, 0, 0, 1], 0).into());
                config.io_workers = workers;
                let server = Server::start(config).unwrap();
                let sender = server
                    .shared
                    .engine
                    .open([1; 32], &[1; 16], 10, now_hour())
                    .unwrap();
                server
                    .shared
                    .engine
                    .send(&sender, vec![7; 1024], now_hour())
                    .unwrap();
                let idle = TcpStream::connect(server.addr()).unwrap();
                let mut active = TcpStream::connect(server.addr()).unwrap();
                active
                    .set_read_timeout(Some(Duration::from_secs(2)))
                    .unwrap();
                active
                    .set_write_timeout(Some(Duration::from_secs(2)))
                    .unwrap();
                let ping = sm_core::relay::encode_request(Command::Ping, &[0; 32], &[]).unwrap();
                active.write_all(&ping).unwrap();
                let mut head = [0; 6];
                active.read_exact(&mut head).unwrap();
                assert_eq!(head, [VERSION, 0, 0, 0, 0, 0]);
                active.write_all(&ping[..turn % ping.len()]).unwrap();
                let probes = server.probes.clone();
                let (tx, rx) = mpsc::sync_channel(1);
                std::thread::spawn(move || {
                    server.stop();
                    let _ = tx.send(());
                });
                let done = rx.recv_timeout(Duration::from_secs(2)).is_ok();
                if !done {
                    let phases: Vec<_> = probes.iter().map(|p| p.load(Ordering::Acquire)).collect();
                    // Test-only CI annotation: phase IDs contain no user data or node telemetry.
                    let detail =
                        format!("shutdown workers={workers} turn={turn} phases={phases:?}");
                    std::io::stderr()
                        .write_all(format!("::error::{detail}\n").as_bytes())
                        .unwrap();
                    assert!(done, "{detail}");
                }
                drop(idle);
                drop(active);
            }
        }
    }

    #[test]
    fn refuses_non_loopback() {
        let e = Server::start(Config::new(SocketAddr::from(([0, 0, 0, 0], 0)))).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn runtime_budget_rejects_impossible_configuration_and_overflow() {
        let mut c = Config::new(([127, 0, 0, 1], 0).into());
        c.limits.memory_bytes =
            runtime_memory(c.max_connections, c.io_workers, c.store_shards).unwrap();
        assert_eq!(
            Server::start(c).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
        c.max_connections = usize::MAX;
        assert_eq!(
            Server::start(c).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[test]
    fn idle_server_reclaims_expired_payload_without_client_traffic() {
        let mut c = Config::new(([127, 0, 0, 1], 0).into());
        c.limits.blob_ttl_hours = 1;
        let server = Server::start(c).unwrap();
        let r = [1; ID_LEN];
        let now = now_hour();
        {
            let sid = server
                .shared
                .engine
                .open(r, &[1; 16], 2, now.saturating_sub(2))
                .unwrap();
            server
                .shared
                .engine
                .send(&sid, vec![42], now.saturating_sub(2))
                .unwrap();
        }
        let deadline = deadline_in(Duration::from_secs(2));
        while server.shared.engine.total_bytes() != 0 && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(server.shared.engine.total_bytes(), 0);
        server.stop();
    }
    #[test]
    fn decoder_handles_every_fragment_boundary_and_pipeline() {
        let body = vec![42; sm_core::relay::MAX_BLOB];
        let wire = sm_core::relay::encode_request(Command::Send, &[1; 32], &body).unwrap();
        for http_mode in [false, true] {
            let mut request = if http_mode {
                format!(
                    "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: {}\r\n\r\n",
                    wire.len()
                )
                .into_bytes()
            } else {
                Vec::new()
            };
            request.extend_from_slice(&wire);
            let mut decoder = Decoder::default();
            let mut input = Vec::new();
            // Bytewise headers, then deliberately irregular body fragments.
            for (i, b) in request.iter().enumerate() {
                input.push(*b);
                if i < 160 || i % 4093 == 0 {
                    assert!(matches!(
                        decoder.parse(&mut input, http_mode),
                        Parsed::Need(_)
                    ));
                }
            }
            input.extend_from_slice(&request);
            for _ in 0..2 {
                match decoder.parse(&mut input, http_mode) {
                    Parsed::Frame(req, data, _, version) => {
                        assert_eq!(req.cmd, Command::Send);
                        assert_eq!(data, body);
                        assert_eq!(version, sm_core::relay::VERSION);
                    }
                    _ => panic!("complete frame was not decoded"),
                }
            }
            assert_eq!(input, [] as [u8; 0]);
        }
    }
}
