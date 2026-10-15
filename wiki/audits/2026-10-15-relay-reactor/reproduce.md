# Воспроизведение reactor A/B

Дата: 2026-10-15. Результаты и ограничения — `report.md`. Исходные raw-файлы не менять. Нужны Rust 1.99.0, protobuf compiler, Python с NumPy/Pandas. Пути скриптов абсолютные `/data/...`, для другого стенда заменить только пути. Нельзя запускать компиляцию/другую нагрузку параллельно измерительным окнам.

## Сборка и запуск

```sh
export PATH="$HOME/.cargo/bin:$PATH"
# checkout BEFORE и AFTER в разные worktree
# BEFORE=435d40a40eda487ede80da372b785ed8f02cc6d7
# AFTER=a756a3d28d1031d72940fb29693c2c2982e4f53e
# Поместить одинаковый relay_lab.rs в crates/sm-relay/examples/ каждого.
export PROTOC="$(bash scripts/fetch-protoc.sh)"
CARGO_TARGET_DIR=/data/relay-before-target cargo build --release --locked -p sm-relay --example relay_lab --manifest-path /data/relay-baseline/Cargo.toml
CARGO_TARGET_DIR=/data/relay-next-target cargo build --release --locked -p sm-relay --example relay_lab --manifest-path /data/secure-messenger/Cargo.toml
mkdir -p /data/relay-reactor-ab/binaries /data/relay-reactor-ab/helpers /data/raw/relay-reactor-ab
cp /data/relay-before-target/release/examples/relay_lab /data/relay-reactor-ab/binaries/before
cp /data/relay-next-target/release/examples/relay_lab /data/relay-reactor-ab/binaries/after
# Не задавать отличающиеся SM_RELAY_* переменные; тест использовал defaults.
python3 /data/relay-reactor-ab/run_ab.py
python3 /data/analysis/analyze_reactor.py
```

Шаблон вывода helper предназначен только для лаборатории; нельзя публиковать production-пропуска/ключи или переносить его println в узел. Пример не входит в код поставляемого бинарника.


## helpers/relay_lab.rs

```rust
use libsignal_core::curve::PrivateKey;
use rand::{TryRngCore, rngs::OsRng};
use sm_core::pass::{QueuePass, signed_message};
use sm_relay::{server::{Config, Server}, pass::GuestKey};
use std::{net::SocketAddr, time::Duration};
fn hex(v: &[u8]) -> String { v.iter().map(|b| format!("{b:02x}")).collect() }
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let private = PrivateKey::deserialize(&[42;32]).unwrap();
    let public: [u8;33] = private.public_key().unwrap().serialize()[..].try_into().unwrap();
    let mut c = Config::new(SocketAddr::from(([127,0,0,1], args[1].parse().unwrap())));
    c.roots = Box::leak(Box::new([public]));
    c.http = args[2] == "http";
    c.guest_key = Some(Box::leak(Box::new(GuestKey::from_hex(&"ab".repeat(32)).unwrap())));
    if args.get(3).is_some_and(|s| s == "short") { c.idle_timeout = Duration::from_secs(2); c.wait = Duration::from_secs(1); }
    let mut passes = Vec::new();
    for (id, per_hour, expires) in [(1u8,100u16,u64::MAX),(2,2,u64::MAX),(3,100,1)] {
        let p = QueuePass {id:[id;16], expires, per_hour};
        let sig = private.calculate_signature(&signed_message(&p.body()), &mut OsRng.unwrap_err()).unwrap();
        passes.push(hex(&p.with_signature(&sig[..].try_into().unwrap())));
    }
    c = sm_relay::settings::apply(c, |key| std::env::var(key).ok()).unwrap();
    let server = Server::start(c).unwrap();
    println!("{{\"address\":\"{}\",\"passes\":[\"{}\",\"{}\",\"{}\"]}}",server.addr(),passes[0],passes[1],passes[2]);
    server.wait();
}
```

## helpers/client.py

```python
import socket,struct
Z=bytes(32)
class Client:
    def __init__(self,port,mode='http'):
        self.s=socket.create_connection(('127.0.0.1',port),timeout=40); self.s.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1); self.f=self.s.makefile('rb'); self.mode=mode
    def __enter__(self): return self
    def __exit__(self,*a): self.close()
    def close(self): self.f.close(); self.s.close()
    def frame(self,cmd,id=Z,data=b''): return bytes([1,cmd])+id+struct.pack('!I',len(data))+data
    def send(self,cmd,id=Z,data=b''):
        frame=self.frame(cmd,id,data)
        if self.mode=='http': frame=f'POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: {len(frame)}\r\n\r\n'.encode()+frame
        self.s.sendall(frame)
    def read(self):
        if self.mode=='http':
            status=self.f.readline(); assert b'200' in status,status
            headers={}
            while True:
                line=self.f.readline()
                if line==b'\r\n':break
                assert line,line
                k,v=line.decode().split(':',1); headers[k.lower()]=v.strip()
            body=self.f.read(int(headers['content-length']))
        else:
            h=self.f.read(6); assert len(h)==6,h
            body=h+self.f.read(struct.unpack('!I',h[2:])[0])
        assert len(body)>=6 and body[0]==1,body[:6]
        assert len(body)==6+struct.unpack('!I',body[2:6])[0]
        return body[1],body[6:]
    def call(self,cmd,id=Z,data=b''):self.send(cmd,id,data);return self.read()
```

## run_ab.py

```python
import sys,time,json,socket,subprocess,os,threading,hashlib,struct,platform,array,traceback
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent/'helpers'))
from client import Client
R=Path('/data/relay-reactor-ab'); RAW=Path('/data/raw/relay-reactor-ab')
PHASES=[('light_4k',5,4096,2000,0),('busy_4k',32,4096,6000,0),('large_max',16,131136,1000,0),('wait48_4k',5,4096,2000,48),('small_1k',16,1024,6000,0)]
def proc(pid):
 st=Path(f'/proc/{pid}/stat').read_text().split();v={l.split(':',1)[0]:l.split(':',1)[1].strip() for l in Path(f'/proc/{pid}/status').read_text().splitlines() if ':' in l}
 return {'cpu_s':(int(st[13])+int(st[14]))/os.sysconf('SC_CLK_TCK'),'rss_kib':int(v['VmRSS'].split()[0]),'threads':int(v['Threads'])}
def one(version):
 binary=R/'binaries'/version;p=subprocess.Popen([str(binary),'0','http'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);cfg=json.loads(p.stdout.readline());port=int(cfg['address'].split(':')[-1]);pas=bytes.fromhex(cfg['passes'][0]);pairs=[];start_utc=time.time();stage_rows=[];resources=[];errors=[];wait_errors=[]
 try:
  with Client(port,'http') as c:
   for i in range(96):
    rid=hashlib.sha256(f'AB-{i}'.encode()).digest();st,sid=c.call(1,rid,pas);assert st==0,(i,st);pairs.append((rid,sid))
   for _ in range(100):
    rid,sid=pairs[64];assert c.call(2,sid,b'warmup')[0]==0;st,b=c.call(3,rid);assert st==0;assert c.call(4,rid,b[1:9])[0]==0
  monitor_stop=threading.Event();t0=time.perf_counter();initial=proc(p.pid)
  def monitor():
   while not monitor_stop.wait(1):resources.append({'t_s':time.perf_counter()-t0,**proc(p.pid)})
  monitor_thread=threading.Thread(target=monitor);monitor_thread.start()
  for phase_idx,(name,clients,size,target,waiters) in enumerate(PHASES):
   phase_begin=t0+60*phase_idx;deadline=phase_begin+60;stop_wait=threading.Event();waiting=[];wait_threads=[]
   def waiter(k,c):
    ids=pairs[k:64]+pairs[:k];extra=b''.join(r for r,s in ids[1:])
    try:
     while not stop_wait.is_set():
      st,b=c.call(7,ids[0][0],extra)
      if st!=1:raise RuntimeError(('WAIT',st))
    except Exception as ex:
     if not stop_wait.is_set():wait_errors.append({'phase':name,'kind':type(ex).__name__,'detail':str(ex)[:120]})
   for k in range(waiters):
    c=Client(port,'http');waiting.append(c);th=threading.Thread(target=waiter,args=(k,c));th.start();wait_threads.append(th)
   outputs=[None]*clients
   def worker(k):
    samples=array.array('d');ok=0;attempts=0;failure=0;late=0;rid,sid=pairs[64+k];payload=bytes([k+1])*size;per_client=target/clients;seq=0
    try:
     with Client(port,'http') as c:
      while True:
       due=phase_begin+seq/per_client+k/target
       now=time.perf_counter()
       if due>=deadline or now>=deadline:break
       if due>now:time.sleep(due-now)
       actual=time.perf_counter()
       if actual>=deadline:break
       attempts+=1
       try:
        st,_=c.call(2,sid,payload);assert st==0,('SEND',st)
        st,data=c.call(3,rid);assert st==0 and data[9:]==payload,('FETCH',st)
        assert c.call(4,rid,data[1:9])[0]==0
        end=time.perf_counter();samples.extend((end-t0,(end-actual)*1000,(end-due)*1000));ok+=1;late+=int(end>deadline)
       except Exception as ex:
        failure+=1;errors.append({'phase':name,'worker':k,'kind':type(ex).__name__,'detail':str(ex)[:120]});break
       seq+=1
    except Exception as ex:
     failure+=1;errors.append({'phase':name,'worker':k,'kind':type(ex).__name__,'detail':str(ex)[:120]})
    outputs[k]={'ok':ok,'attempts':attempts,'errors':failure,'boundary_completions':late,'samples':samples}
   threads=[threading.Thread(target=worker,args=(k,)) for k in range(clients)]
   for th in threads:th.start()
   for th in threads:th.join()
   left=deadline-time.perf_counter()
   if left>0:time.sleep(left)
   stop_wait.set()
   for c in waiting:
    try:c.s.shutdown(socket.SHUT_RDWR)
    except OSError:pass
   for th in wait_threads:th.join(timeout=2)
   for c in waiting:c.close()
   dest=RAW/f'{version}-{name}.f64'
   with dest.open('wb') as f:
    for row in outputs:row['samples'].tofile(f)
   summary={'phase':name,'clients':clients,'payload_bytes':size,'target_cycles_s':target,'planned':target*60,'waiters':waiters,'window_s':60,'completed':sum(x['ok'] for x in outputs),'attempted':sum(x['attempts'] for x in outputs),'errors':sum(x['errors'] for x in outputs),'boundary_completions':sum(x['boundary_completions'] for x in outputs),'samples_file':dest.name,'ended_t_s':time.perf_counter()-t0}
   stage_rows.append(summary);print(version,name,summary,flush=True)
  elapsed=time.perf_counter()-t0;final=proc(p.pid);monitor_stop.set();monitor_thread.join()
  with Client(port,'http') as c:assert c.call(6)[0]==0
  stdout,stderr='', ''
  p.terminate();stdout,stderr=p.communicate(timeout=5)
  result={'version':version,'start_utc_s':start_utc,'measured_window_s':300,'elapsed_with_drain_s':elapsed,'source_sha':{'before':'435d40a40eda487ede80da372b785ed8f02cc6d7','after':'a756a3d28d1031d72940fb29693c2c2982e4f53e'}[version],'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'helper_sha256':hashlib.sha256((R/'helpers/relay_lab.rs').read_bytes()).hexdigest(),'phases':stage_rows,'initial':initial,'final':final,'resources':resources,'errors':errors,'wait_errors':wait_errors,'server_output_empty':not stdout and not stderr,'environment':{'cpu_count':os.cpu_count(),'platform':platform.platform(),'cpu_max':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),'swap_total':next(l for l in Path('/proc/meminfo').read_text().splitlines() if l.startswith('SwapTotal'))},'method':'fixed-rate schedule with bounded synchronous workers; schedule lag measured, unserved plan counted; co-located generator; one run/version, sequential before then after; no TLS/WAN; same common quotas, 64 connection cap and work; after defaults 2 fixed reactors/16 Store shards vs baseline thread/connection and global Mutex; standalone wire v1 only, no HA/TLS/WAN'}
  (RAW/f'{version}.json').write_text(json.dumps(result,indent=2));assert not errors and not wait_errors,(errors,wait_errors)
 finally:
  if p.poll() is None:p.kill();p.communicate()
for version in ['before','after']:one(version)
print('Both 300-second measured windows completed',flush=True)
```

## analyze_reactor.py

```python
import json
import hashlib
from pathlib import Path
import numpy as np
import pandas as pd

RAW = Path("/data/raw/relay-reactor-ab")
OUT = Path("/data/analysis/relay-reactor")
OUT.mkdir(parents=True, exist_ok=True)
names = {
    "light_4k": "4 KiB · 5",
    "busy_4k": "4 KiB · 32",
    "large_max": "128 KiB+ · 16",
    "wait48_4k": "4 KiB · 48 WAIT",
    "small_1k": "1 KiB · 16",
}
rows, totals, provenance = [], {}, {}
for version in ["before", "after"]:
    source = RAW / f"{version}.json"
    root = json.loads(source.read_text())
    assert root["measured_window_s"] == 300
    assert len(root["phases"]) == 5
    assert not root["errors"] and not root["wait_errors"]
    assert root["server_output_empty"]
    total_ok = sum(p["completed"] for p in root["phases"])
    total_plan = sum(p["planned"] for p in root["phases"])
    resources = pd.DataFrame(root["resources"])
    cpu = root["final"]["cpu_s"] - root["initial"]["cpu_s"]
    totals[version] = {
        "completed": total_ok, "planned": total_plan,
        "completion_percent": 100 * total_ok / total_plan,
        "unserved": total_plan - total_ok,
        "cpu_s": cpu, "cpu_us_per_cycle": cpu * 1e6 / total_ok,
        "peak_rss_mib": resources.rss_kib.max() / 1024,
        "peak_threads": int(resources.threads.max()),
        "errors": 0, "measured_window_s": 300,
    }
    provenance[version] = {
        k: root[k] for k in ["source_sha", "binary_sha256", "helper_sha256",
                            "start_utc_s", "environment", "method"]
    }
    for index, phase in enumerate(root["phases"]):
        path = RAW / phase["samples_file"]
        assert path.stat().st_size == phase["completed"] * 24
        a = np.fromfile(path, dtype=np.float64).reshape((-1, 3))
        assert np.isfinite(a).all() and (a[:, 1:] >= 0).all()
        assert len(a) == phase["attempted"] == phase["completed"]
        assert phase["errors"] == 0
        r = {
            "version": version, "phase": phase["phase"],
            "completed": len(a), "planned": phase["planned"],
            "completion_percent": 100 * len(a) / phase["planned"],
            "unserved": phase["planned"] - len(a),
            "goodput_cycles_s": len(a) / 60,
            "boundary_completions": phase["boundary_completions"],
        }
        for col, metric in [(1, "service"), (2, "scheduled")]:
            for q in [0.5, 0.95, 0.99]:
                r[f"{metric}_p{int(q * 100)}_ms"] = float(np.quantile(a[:, col], q))
        sample_resources = resources[
            (resources.t_s > index * 60) & (resources.t_s <= (index + 1) * 60)]
        r["rss_peak_mib"] = float(sample_resources.rss_kib.max() / 1024)
        r["threads_peak"] = int(sample_resources.threads.max())
        rows.append(r)
df = pd.DataFrame(rows)
df.to_csv(OUT / "metrics.csv", index=False)
b = df[df.version == "before"].set_index("phase")
a = df[df.version == "after"].set_index("phase")
comparison = a.drop(columns="version").copy()
for c in ["scheduled_p99_ms", "service_p99_ms", "goodput_cycles_s"]:
    comparison[f"{c}_before"] = b[c]
    comparison[f"{c}_change_percent"] = 100 * (a[c] / b[c] - 1)
comparison.to_csv(OUT / "comparison.csv")
delta = {
    k: 100 * (totals["after"][k] / totals["before"][k] - 1)
    for k in ["cpu_s", "cpu_us_per_cycle", "peak_rss_mib", "peak_threads", "completed"]
}
plan = [
    {"grain": "profile/version", "population": "completed SEND→FETCH→ACK cycles",
     "unit": "ms", "finding": "profile-specific scheduled p99 differences",
     "derivation": "numpy linear quantile(end minus scheduled due); unserved plan separate",
     "disposition": "chart", "reason": "preserve all five latency profiles and regressions"},
    {"grain": "version", "population": "300-second measured node process window",
     "unit": "CPU microseconds/completed cycle",
     "finding": f'CPU/cycle change {delta["cpu_us_per_cycle"]:.2f}%',
     "derivation": "proc CPU delta * 1e6 / completed cycles",
     "disposition": "chart", "reason": "normalization avoids confusing unequal work with efficiency"},
    {"grain": "profile/version", "population": "planned and completed cycles",
     "unit": "cycles and percent", "finding": "service p99 and schedule completion",
     "derivation": "completed/planned; quantile(end minus actual start)",
     "disposition": "table", "reason": "completion is required to interpret latency; CSV retains p50/p95"},
    {"grain": "version", "population": "1-second process samples",
     "unit": "MiB and threads", "finding": "sampled maxima, not allocator hard bound",
     "derivation": "max RSS/1024; max thread count",
     "disposition": "prose", "reason": "resource trade-off; no need for a third chart"},
]
evidence = {"totals": totals, "delta_percent": delta,
            "phases": df.to_dict(orient="records"),
            "comparison": comparison.reset_index().to_dict(orient="records"),
            "provenance": provenance, "comparison_plan": plan,
            "limitations": ["one sequential run per version; no confidence interval",
                            "co-located Python generator on two CPUs",
                            "localhost HTTP wire v1; no TLS, WAN, HA or batch throughput",
                            "bounded synchronous workers; not a maximum-capacity proof",
                            "latency quantiles conditional on completed cycles",
                            "RSS sampled at 1s, kernel buffers and subsecond spikes excluded"]}
(OUT / "evidence.json").write_text(json.dumps(evidence, indent=2))
latency = {
    "version": 1, "form": "categorical-bars", "mode": "grouped",
    "title": "Задержка p99 с учётом отставания от графика",
    "aside": "мс на SEND → FETCH → ACK · по 60 с на профиль · только завершённые циклы",
    "labels": list(names.values()), "axis": {"zero": True},
    "colorMap": {"До": "neutral", "После": "blue"},
    "series": [
        {"label": label, "valueKind": "durationMs", "role": role,
         "data": data.loc[list(names), "scheduled_p99_ms"].tolist(),
         "derived": True,
         "derivationReason": "p99(end − scheduled due) among completed cycles"}
        for label, role, data in [("До", "base", b), ("После", "subject", a)]
    ],
}
cpu_spec = {
    "version": 1, "form": "categorical-bars", "mode": "grouped",
    "title": "CPU узла на завершённый цикл",
    "aside": "микросекунды CPU / цикл · весь 300-секундный прогон · меньше лучше",
    "labels": ["Все пять профилей"], "axis": {"zero": True},
    "colorMap": {"До": "neutral", "После": "blue"},
    "series": [
        {"label": label, "valueKind": "number", "role": role,
         "data": [totals[version]["cpu_us_per_cycle"]], "derived": True,
         "derivationReason": "delta process CPU seconds × 1e6 / completed cycles"}
        for label, role, version in [("До", "base", "before"), ("После", "subject", "after")]
    ],
}
# The canonical grouped-bar form needs two categories. For this aggregate
# comparison use a single series with versions as categories, preserving hues.
cpu_spec["orientation"] = "vertical"
cpu_spec["labels"] = ["До", "После"]
cpu_spec["series"] = [{
    "label": "CPU на цикл", "valueKind": "number", "role": "subject",
    "data": [totals[v]["cpu_us_per_cycle"] for v in ["before", "after"]],
    "derived": True, "derivationReason": "delta process CPU seconds × 1e6 / completed cycles",
}]
for name, spec in [("latency", latency), ("cpu", cpu_spec)]:
    (OUT / f"{name}.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2))
table = [
    "| Профиль | Выполнено до / после | p99 ответа, мс до → после | p99 с графиком, мс до → после |",
    "|---|---:|---:|---:|",
]
for key, label in names.items():
    before, after = b.loc[key], a.loc[key]
    table.append(f'| {label} | {int(before.completed):,} / {int(after.completed):,} | '
                 f'{before.service_p99_ms:.2f} → {after.service_p99_ms:.2f} | '
                 f'{before.scheduled_p99_ms:.2f} → {after.scheduled_p99_ms:.2f} |')
(OUT / "table.md").write_text("\n".join(table))
print(json.dumps({"totals": totals, "delta_percent": delta}, indent=2))
print("\n".join(table))
```
