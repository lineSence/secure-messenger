# Воспроизведение hot-path сравнения

Дата лаборатории: 2026-10-15 UTC. Только изолированный synthetic localhost, не рабочий узел. До `4edb5ca407ffa55af5c7f04dd7abc681e80c8eec`, после `92ad766c7cf5f4a29e899c7e2fd72a06a1cf7a78`. Полные условия/ограничения — [report.md](report.md).

1. Два отдельных чистых worktree/target для версий; один Rust/Cargo.lock/release профиль. Установить PROTOC через `scripts/fetch-protoc.sh`. Одинаковый helper ниже временно положить в `crates/sm-relay/examples/relay_lab.rs`, `cargo build --release --locked -p sm-relay --example relay_lab`, копировать бинарники в `binaries/before`/`after`, затем удалить helper. Проверить разные ожидаемые binary SHA и одинаковый helper SHA; тестовые ключи фиктивные, никогда не использовать для настоящего узла. Существующий before binary был перепроверен по SHA, production-код совпадает с указанным main.
2. Не запускать сборки/другие локальные нагрузки одновременно с замерами. `python3 run_ab.py`: 2×300 с; последовательно до/после. Все пути абсолютные из данного стенда; при переносе заменить корни единообразно. Проверить окружение, не задавать отличающиеся SM_RELAY_*.
3. Только после A/B: `python3 diagnose.py matrix` (18×10 с), затем `python3 diagnose.py trace` (отдельно strace). Это не perf и не профиль user/kernel symbols; новые perf-проценты не публикуются.
4. Cleanup probe временно добавить в engine.rs как cfg(test), `cargo test --release --locked -p sm-relay --lib audit_backlog_rounds -- --nocapture`; восстановить файл и сверить его с опубликованной версией. Probe не входит в рабочий узел/PR.
5. `python3 analyze.py`: сравнивает byte length и число completed каждого f64, конечность/положительность, scheduled≥service, raw JSON ошибки и coverage. p99 независимо перепроверяется Python struct+линейной порядковой статистикой. 300s grain — один cycle = triplet float64 `(end_t_s, service_ms, scheduled_ms)`, по worker concatenation, не хронологическая сортировка; diagnostic f64 — пара service/scheduled. Не смешивать форматы.
6. Nested raw JSON не читается общим табличным profiler (`arrays of different lengths`); нормализованы и профилированы phases/resources/short-runs/syscalls CSV. Ключи: version+phase, version+t_s, tag, tag+syscall. Константные errors=0/window_s=60/threads=3 ожидаемы, не пропуски; дубликатов/NULL нет. f64 проверены непосредственно в анализе, а не интерпретированы как текст.
7. График строится из `cpu-spec.json` canonical inline-charts builder; он показывает только медианы CPU трёх повторов, диапазоны/latency counterexamples в report. Процентили между trials не складываются.

## Исходники

### run_ab.py

````python
import sys,time,json,socket,subprocess,os,threading,hashlib,struct,platform,array,traceback
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent/'helpers'))
from client import Client
R=Path('/data/relay-hotpath-ab'); RAW=Path('/data/raw/relay-hotpath-ab')
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
  result={'version':version,'start_utc_s':start_utc,'measured_window_s':300,'elapsed_with_drain_s':elapsed,'source_sha':{'before':'4edb5ca407ffa55af5c7f04dd7abc681e80c8eec','after':'92ad766c7cf5f4a29e899c7e2fd72a06a1cf7a78'}[version],'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'helper_sha256':hashlib.sha256((R/'helpers/relay_lab.rs').read_bytes()).hexdigest(),'phases':stage_rows,'initial':initial,'final':final,'resources':resources,'errors':errors,'wait_errors':wait_errors,'server_output_empty':not stdout and not stderr,'environment':{'cpu_count':os.cpu_count(),'platform':platform.platform(),'cpu_max':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),'swap_total':next(l for l in Path('/proc/meminfo').read_text().splitlines() if l.startswith('SwapTotal'))},'method':'fixed-rate schedule with bounded synchronous workers; schedule lag measured, unserved plan counted; co-located generator; one run/version, sequential before then after; no TLS/WAN; same common quotas, 64 connection cap and work; both versions 2 fixed reactors/16 Store shards; before is current main, after approved five hot-path changes; standalone wire v1 only, no HA/TLS/WAN'}
  (RAW/f'{version}.json').write_text(json.dumps(result,indent=2));assert not errors and not wait_errors,(errors,wait_errors)
 finally:
  if p.poll() is None:p.kill();p.communicate()
for version in ['before','after']:one(version)
print('Both 300-second measured windows completed',flush=True)


````

### helpers/relay_lab.rs

````rust
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


````

### helpers/client.py

````python
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


````

### diagnose.py

````python
"""Synthetic standalone lab; no instrumentation is added to the production relay."""
import argparse
import array
import hashlib
import json
import os
from pathlib import Path
import random
import signal
import socket
import subprocess
import sys
import threading
import time

sys.path.insert(0, "/data/relay-hotpath-ab/helpers")
from client import Client

RAW = Path("/data/raw/relay-hotpath-diagnostics")
RAW.mkdir(parents=True, exist_ok=True)
BIN = Path("/data/relay-hotpath-ab/binaries")

def proc(pid):
    st = Path(f"/proc/{pid}/stat").read_text().split()
    v = dict(line.split(":", 1) for line in Path(f"/proc/{pid}/status").read_text().splitlines() if ":" in line)
    return {"cpu_s": (int(st[13]) + int(st[14])) / os.sysconf("SC_CLK_TCK"),
            "user_s": int(st[13]) / os.sysconf("SC_CLK_TCK"),
            "system_s": int(st[14]) / os.sysconf("SC_CLK_TCK"),
            "rss_kib": int(v["VmRSS"].split()[0]), "threads": int(v["Threads"]),
            "voluntary_cs": int(v["voluntary_ctxt_switches"]),
            "nonvoluntary_cs": int(v["nonvoluntary_ctxt_switches"])}

def child(pid):
    children = Path(f"/proc/{pid}/task/{pid}/children").read_text().split()
    assert len(children) == 1, children
    return int(children[0])

def run(version, profile, repeat, mode="plain", seconds=10, cycles=None, envextra=None):
    # Profile defines active clients, bytes, offered cycles/s; actual counts are retained.
    profiles = {"light": (5,4096,2000), "busy": (32,4096,6000),
                "max": (16,131136,1000), "one": (1,4096,0),
                "one_max": (1,131136,0)}
    clients, size, rate = profiles[profile]
    tag = f"{version}-{profile}-{repeat}-{mode}"
    env = os.environ.copy()
    # Refuse invisible differences in the lab configuration.
    for key in list(env):
        if key.startswith("SM_RELAY_"):
            del env[key]
    env.update(envextra or {})
    binary = BIN / ("before" if version == "before" else "after")
    cmd = [str(binary), "0", "http"]
    if mode == "strace":
        cmd = ["strace", "-f", "-c", "-o", str(RAW/f"{tag}.strace"), *cmd]
    if mode == "perf":
        cmd = ["perf", "record", "-e", "cpu-clock:u", "-F", "499",
               "--call-graph", "dwarf,8192", "-o", str(RAW/f"{tag}.perf.data"), "--", *cmd]
    stderr = open(RAW/f"{tag}.stderr", "w")
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=stderr, text=True, env=env)
    pid = None
    try:
        line = p.stdout.readline()
        assert line, (tag, "no helper startup", p.poll())
        cfg = json.loads(line)
        pid = p.pid if mode == "plain" else child(p.pid)
        port = int(cfg["address"].split(":")[-1])
        pas = bytes.fromhex(cfg["passes"][0])
        pairs = []
        with Client(port) as c:
            for i in range(clients):
                rid = hashlib.sha256(f"audit-{i}".encode()).digest()
                st, sid = c.call(1, rid, pas)
                assert st == 0
                pairs.append((rid, sid))
        barrier = threading.Barrier(clients+1)
        outputs = [None] * clients
        timing = {}
        def worker(k):
            samples = array.array("d")
            failures = []
            rid, sid = pairs[k]
            payload = bytes([k+1]) * size
            with Client(port) as c:
                for _ in range(10):
                    assert c.call(2, sid, b"warm")[0] == 0
                    st, data = c.call(3, rid)
                    assert st == 0 and data[9:] == b"warm"
                    assert c.call(4, rid, data[1:9])[0] == 0
                barrier.wait()
                seq = 0
                while True:
                    due = timing["t0"] + (seq*clients+k)/rate if rate else time.perf_counter()
                    if cycles is not None and seq >= cycles:
                        break
                    if time.perf_counter() >= timing["deadline"] or due >= timing["deadline"]:
                        break
                    if due > time.perf_counter():
                        time.sleep(due-time.perf_counter())
                    start = time.perf_counter()
                    try:
                        assert c.call(2, sid, payload)[0] == 0
                        st, data = c.call(3, rid)
                        assert st == 0 and data[9:] == payload
                        assert c.call(4, rid, data[1:9])[0] == 0
                        end = time.perf_counter()
                        samples.extend(((end-start)*1000, (end-due)*1000))
                    except Exception as ex:
                        failures.append(repr(ex))
                        break
                    seq += 1
            outputs[k] = (samples, failures)
        workers = [threading.Thread(target=worker,args=(k,)) for k in range(clients)]
        for w in workers:
            w.start()
        # Wait until warmup reached the barrier before reading CPU.
        while barrier.n_waiting < clients:
            time.sleep(.001)
        initial = proc(pid)
        timing["t0"] = time.perf_counter()
        timing["deadline"] = timing["t0"] + seconds
        barrier.wait()
        for w in workers:
            w.join()
        elapsed = time.perf_counter() - timing["t0"]
        final = proc(pid)
        data = array.array("d")
        errors = []
        for samples, failures in outputs:
            data.extend(samples)
            errors.extend(failures)
        sample_path = RAW/f"{tag}.f64"
        with sample_path.open("wb") as f:
            data.tofile(f)
        count = len(data)//2
        row = {"tag": tag, "version":version, "profile":profile, "repeat":repeat,
               "mode":mode, "clients":clients, "payload_bytes":size,
               "offered_cycles_s":rate, "window_s":seconds,
               "elapsed_s":elapsed, "completed":count,
               "initial":initial, "final":final, "errors":errors,
               "sample_file":sample_path.name, "env_overrides":envextra or {},
               "binary_sha256":hashlib.sha256(binary.read_bytes()).hexdigest()}
        (RAW/f"{tag}.json").write_text(json.dumps(row,indent=2))
        print(tag, "completed",count,"CPU",round(final["cpu_s"]-initial["cpu_s"],3),
              "elapsed",round(elapsed,3),"errors",errors,flush=True)
        assert not errors
    finally:
        if pid:
            try: os.kill(pid, signal.SIGTERM)
            except ProcessLookupError: pass
        try: p.communicate(timeout=10)
        except subprocess.TimeoutExpired: p.kill();p.communicate()
        stderr.close()

if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "trace":
        for v in ["before", "after"]:
            for profile, count in [("one",2000),("one_max",400)]:
                run(v, profile, 0, "strace", seconds=120, cycles=count)
    elif mode == "perf":
        for v, profile in [("before","light"),("after","light"),("after","busy"),("after","max")]:
            run(v, profile, 0, "perf", seconds=20)
    elif mode == "matrix":
        for repeat in range(3):
            for profile in ["light","busy","max"]:
                versions = ["before","after"]
                random.Random(1729+repeat).shuffle(versions)
                for v in versions:
                    extra = {"SM_RELAY_IO_WORKERS":"1"} if v.endswith("1worker") else {}
                    run(v,profile,repeat,seconds=10,envextra=extra)
````

### cleanup-probe.rs

````rust

#[cfg(test)]
mod audit_cleanup_probe {
    use super::*;
    #[test]
    fn audit_backlog_rounds() {
        for shards in [1, 16, 256] {
            let l = Limits {memory_bytes: 1 << 30, ..Limits::default()};
            let e = Engine::new(l, Budget::new(l.memory_bytes), shards);
            let rid = [42;32];
            let sid = e.open(rid, &[1;16], 100, 0).unwrap();
            for _ in 0..1000 { e.send(&sid, vec![7], 0).unwrap(); }
            let mut rounds = 0usize;
            while e.total_bytes() > 0 {
                e.maintain(168, 256);
                rounds += 1;
                assert!(rounds < 10000);
            }
            println!("AUDIT_CLEANUP shards={shards} blobs=1000 budget=256 ttl_rounds={rounds}");
        }
    }
}

````

### analyze.py

````python
import json, math, struct, hashlib
from pathlib import Path
import numpy as np
import pandas as pd
RAW=Path('/data/raw/relay-hotpath-ab'); OUT=Path('/data/analysis/relay-hotpath')
labels={'light_4k':'4 KiB · 5','busy_4k':'4 KiB · 32','large_max':'128 KiB+ · 16','wait48_4k':'4 KiB · 48 WAIT','small_1k':'1 KiB · 16'}
runs={}; rows=[]; resource_rows=[]
for version in ['before','after']:
    d=json.loads((RAW/f'{version}.json').read_text()); assert not d['errors'] and not d['wait_errors'] and d['server_output_empty']
    all_samples=[]
    for p in d['phases']:
        source=RAW/p['samples_file'];a=np.fromfile(source,dtype=np.float64).reshape(-1,3)
        assert len(a)==p['completed']==p['attempted'] and p['errors']==0
        assert np.isfinite(a).all() and (a>=0).all() and (a[:,2]>=a[:,1]-1e-7).all()
        row={'version':version,**{k:p[k] for k in ['phase','clients','payload_bytes','planned','completed','errors','window_s','boundary_completions']}}
        for col,name in [(1,'service'),(2,'scheduled')]:
            for q in [.5,.95,.99]:row[f'{name}_p{int(q*100)}_ms']=float(np.quantile(a[:,col],q))
        # Independent percentile check from raw binary, using Python linear order statistics.
        vals=sorted(x[2] for x in struct.iter_unpack('ddd',source.read_bytes()));pos=(len(vals)-1)*.99;lo=int(pos)
        direct=vals[lo]+(vals[min(lo+1,len(vals)-1)]-vals[lo])*(pos-lo)
        assert math.isclose(direct,row['scheduled_p99_ms'],abs_tol=1e-10)
        row['start_lag_p99_ms']=float(np.quantile(a[:,2]-a[:,1],.99))
        row['scheduled_max_ms']=float(a[:,2].max());row['label']=labels[p['phase']]
        rows.append(row);all_samples.append(a)
    total=sum(p['completed'] for p in d['phases']);cpu=d['final']['cpu_s']-d['initial']['cpu_s']
    resources=pd.DataFrame(d['resources']);resources['version']=version;resource_rows.extend(resources.to_dict('records'))
    runs[version]={'source_sha':d['source_sha'],'binary_sha256':d['binary_sha256'],'completed':total,'planned':sum(p['planned'] for p in d['phases']),
       'cpu_s':cpu,'cpu_us_cycle':cpu*1e6/total,'rss_peak_mib':float(resources.rss_kib.max()/1024),'rss_last_mib':d['final']['rss_kib']/1024,
       'threads_min':int(resources.threads.min()),'threads_max':int(resources.threads.max()),'measured_window_s':300,'elapsed_with_drain_s':d['elapsed_with_drain_s']}
    assert sum(x['errors'] for x in d['phases'])==0
    # Compatible numerator/denominator; CPU is process-only, not load-generator CPU.
    independent=math.fsum([d['final']['cpu_s'],-d['initial']['cpu_s']])*1e6/sum(len(x) for x in all_samples)
    assert math.isclose(independent,runs[version]['cpu_us_cycle'])
df=pd.DataFrame(rows);df.to_csv(OUT/'phases.csv',index=False);pd.DataFrame(resource_rows).to_csv(OUT/'resources.csv',index=False)
changes={}
for phase in labels:
    b=df[(df.version=='before')&(df.phase==phase)].iloc[0];a=df[(df.version=='after')&(df.phase==phase)].iloc[0]
    changes[phase]={'scheduled_p99_percent':100*(a.scheduled_p99_ms/b.scheduled_p99_ms-1),'service_p99_percent':100*(a.service_p99_ms/b.service_p99_ms-1)}
cpu_change=100*(runs['after']['cpu_us_cycle']/runs['before']['cpu_us_cycle']-1)
plan=[{'grain':'version, whole 300s','population':'completed SEND→FETCH→ACK cycles, identical five-profile offered schedule','unit':'CPU microseconds/completed cycle','derivation':'process CPU delta ×1e6 / completed cycles','finding':f'CPU per cycle changes {cpu_change:+.1f}%','disposition':'chart','reason':'directly checks prior CPU regression'},
 {'grain':'profile/version, 60s','population':'completed cycles in each profile; unserved offered plan counted separately','unit':'milliseconds','derivation':'raw service and schedule-inclusive p99, linear quantile','finding':'retain all five profiles and any latency counterexamples','disposition':'table','reason':'small complete quantile comparison accompanies CPU finding'},
 {'grain':'version','population':'external process samples once/s during test','unit':'MiB / threads','derivation':'sampled max; no hard RSS guarantee','finding':'resource context, not a scalability bound','disposition':'prose','reason':'secondary to CPU and latency'},
 {'grain':'phase/version','population':'offered schedule and completed cycles','unit':'cycles','derivation':'sum compatible disjoint profiles','finding':'completion coverage gates comparison','disposition':'prose','reason':'must distinguish fewer completions from lower CPU'}]
evidence={'runs':runs,'phases':rows,'cpu_change_percent':cpu_change,'phase_changes':changes,'comparison_plan':plan,'limitations':['One 300s run per version, sequential order; no confidence or causal attribution per individual optimization','2 CPU cgroup, co-located Python generator; scheduling/host noise affects p99','Standalone HTTP/wire-v1 only; no TLS/WAN, Android or HA','CPU process clock resolution is 10ms; external RSS samples are not a full memory proof','Benchmark before binary is unchanged relay code of 4edb5ca; exact synthetic helper matches both binaries']}
(OUT/'evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
spec={'version':1,'form':'categorical-bars','mode':'grouped','orientation':'vertical','title':'CPU узла на завершённый цикл','aside':'мкс CPU / SEND → FETCH → ACK · 300 с на версию · меньше лучше',
      'labels':['Пять профилей'],'axis':{'zero':True},'colorMap':{'До':'neutral','После':'blue'},'series':[{'label':label,'role':role,'valueKind':'number','derived':True,'derivationReason':'process CPU seconds × 1e6 / completed cycles','data':[runs[v]['cpu_us_cycle']]} for label,role,v in [('До','base','before'),('После','subject','after')]]}
(OUT/'cpu-spec.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2))
table=['| Профиль | p99 с отставанием, мс: до → после | p99 обслуживания, мс: до → после |','|---|---:|---:|']
for p in labels:
    b=df[(df.version=='before')&(df.phase==p)].iloc[0];a=df[(df.version=='after')&(df.phase==p)].iloc[0]
    table.append(f'| {labels[p]} | {b.scheduled_p99_ms:.3f} → {a.scheduled_p99_ms:.3f} | {b.service_p99_ms:.3f} → {a.service_p99_ms:.3f} |')
(OUT/'table.md').write_text('\n'.join(table))
manifest=[{'path':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(RAW.iterdir()) if p.is_file()]
(OUT/'raw-manifest.json').write_text(json.dumps(manifest,indent=2))
print(json.dumps({'runs':runs,'cpu_change_percent':cpu_change,'phase_changes':changes},ensure_ascii=False,indent=2));print('\n'.join(table))
# Independent short repetitions and ptrace diagnostics are never pooled with 300s windows.
import re, statistics
DIAG=Path('/data/raw/relay-hotpath-diagnostics'); short=[]; traces=[]
for path in sorted(DIAG.glob('*-plain.json')):
    d=json.loads(path.read_text());assert not d['errors'];a=np.fromfile(DIAG/d['sample_file'],dtype=np.float64).reshape(-1,2)
    assert len(a)==d['completed']==d['offered_cycles_s']*d['window_s']
    assert np.isfinite(a).all() and (a>=0).all()
    cpu=(d['final']['cpu_s']-d['initial']['cpu_s'])*1e6/d['completed']
    short.append({'version':d['version'],'profile':d['profile'],'repeat':d['repeat'],'tag':d['tag'],'completed':d['completed'],'cpu_us_cycle':cpu,'scheduled_p99_ms':float(np.quantile(a[:,1],.99))})
shortdf=pd.DataFrame(short);assert len(shortdf)==18
shortdf.to_csv(OUT/'short-runs.csv',index=False)
g=shortdf.groupby(['profile','version']).agg(cpu_median=('cpu_us_cycle','median'),cpu_min=('cpu_us_cycle','min'),cpu_max=('cpu_us_cycle','max'),p99_median=('scheduled_p99_ms','median'),p99_min=('scheduled_p99_ms','min'),p99_max=('scheduled_p99_ms','max')).reset_index()
for row in g.to_dict('records'):
    vals=[r['cpu_us_cycle'] for r in short if r['profile']==row['profile'] and r['version']==row['version']]
    assert math.isclose(statistics.median(vals),row['cpu_median'])
g.to_csv(OUT/'short-summary.csv',index=False)
for path in sorted(DIAG.glob('*.strace')):
    for line in path.read_text().splitlines():
        m=re.match(r'^\s*([\d.]+)\s+([\d.]+)\s+(\d+)\s+(\d+)\s+(?:(\d+)\s+)?([a-zA-Z_0-9]+)\s*$',line)
        if m and m[6]!='total':traces.append({'tag':path.stem,'syscall':m[6],'calls':int(m[4]),'errors':int(m[5] or 0)})
pd.DataFrame(traces).to_csv(OUT/'syscalls.csv',index=False)
cleanup=[]
for s,b,budget,n in re.findall(r'AUDIT_CLEANUP shards=(\d+) blobs=(\d+) budget=(\d+) ttl_rounds=(\d+)',(DIAG/'cleanup-probe.log').read_text()):cleanup.append({'shards':int(s),'blobs':int(b),'budget':int(budget),'rounds':int(n)})
assert len(cleanup)==3
outliers=[]
for p in ['busy_4k','small_1k']:
    phase_index=list(labels).index(p)
    for version in ['before','after']:
        a=np.fromfile(RAW/f'{version}-{p}.f64',dtype=np.float64).reshape(-1,3);mask=a[:,2]>5;due=a[:,0]-a[:,2]/1000-phase_index*60
        outliers.append({'version':version,'phase':p,'scheduled_over_5ms':int(mask.sum()),'in_first_second':int((mask&(due<1)).sum()),'first_due_s':float(due[mask].min()) if mask.any() else None,'last_due_s':float(due[mask].max()) if mask.any() else None})
evidence.update(short_runs=short,short_summary=g.to_dict('records'),syscalls=traces,cleanup=cleanup,outliers=outliers)
evidence['comparison_plan'].extend([{'grain':'profile/version, median of 3 separate 10s trials','population':'all completed cycles in each independent repeat','unit':'microseconds CPU per completed cycle','derivation':'median of three CPU-per-cycle ratios; min/max retained','finding':'check whether long-window benefit repeats across profiles','disposition':'chart','reason':'repeated profiles expose a possible no-benefit/negative exception to pooled result'}, {'grain':'synthetic serial syscall trace and isolated cleanup probe','population':'instrumented trials with setup/warmup, not latency population','unit':'calls','derivation':'strace -f -c, and number of 256-step maintenance invocations','finding':'algorithmic work checks, not percent CPU attribution','disposition':'table','reason':'counts validate mechanisms without treating ptrace duration as benchmark'}])
(OUT/'evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
# Prefer repeated per-profile CPU over a redundant standalone mixed-window bar.
repeated_labels={'light':'4 KiB · 5','busy':'4 KiB · 32','max':'128 KiB+ · 16'}
spec['title']='CPU узла: повторная проверка трёх профилей';spec['aside']='мкс CPU / цикл · медианы 3×10 с · меньше лучше · localhost';spec['labels']=list(repeated_labels.values())
for series,v in zip(spec['series'],['before','after']):
    series['data']=[float(g[(g.profile==p)&(g.version==v)].iloc[0].cpu_median) for p in repeated_labels];series['derivationReason']='median of 3 separate process CPU deltas × 1e6 / completed cycles'
(OUT/'cpu-spec.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2))
# Pooled CPU level remains in prose, preserving its exact original population.
evidence['comparison_plan'][0]['disposition']='prose';evidence['comparison_plan'][0]['reason']='repeated per-profile chart is more diagnostic; retain mixed-window value in prose'
(OUT/'evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
print('SHORT SUMMARY');print(g.round(4).to_string(index=False));print('CLEANUP',cleanup);print('OUTLIERS',outliers)
print('SYSCALLS', [r for r in traces if r['syscall'] in ['epoll_ctl','recvfrom','writev','epoll_wait']])

````
