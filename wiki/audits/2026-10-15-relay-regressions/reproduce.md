# Воспроизведение аудита регрессий

Дата: 2026-10-15. Версии/ограничения — `report.md`. Используются те же два release lab-бинарника из `wiki/audits/2026-10-15-relay-reactor/reproduce.md`; их SHA256 должны совпасть. Любую новую сборку класть в отдельный target dir, не перезаписывать benchmark baseline. Требуются Linux, Python/NumPy/Pandas, perf/strace; production ключи/сеть/данные не использовать.

## Команды

```sh
# Скопировать helper client.py в /data/relay-reactor-ab/helpers
# before/after binaries — /data/relay-reactor-ab/binaries
python3 /data/analysis/relay-regression-audit/diagnose.py trace
python3 /data/analysis/relay-regression-audit/diagnose.py perf
python3 /data/analysis/relay-regression-audit/diagnose.py matrix
# Не выполнять тяжёлую локальную работу параллельно matrix/perf.
for f in /data/raw/relay-regression-audit/*.perf.data; do
  perf report -i "$f" --stdio --no-children --sort symbol --percent-limit 1 > "${f%.perf.data}.perf-report.txt"
done
python3 /data/analysis/relay-regression-audit/analyze.py
```

`strace` выводит статистику по всему процессу/потокам с startup; не использовать `% time` как CPU breakdown. Perf user-only sampling не покрывает kernel CPU и может иметь недостаточно samples для малых функций.


## diagnose.py

```python
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

sys.path.insert(0, "/data/relay-reactor-ab/helpers")
from client import Client

RAW = Path("/data/raw/relay-regression-audit")
RAW.mkdir(parents=True, exist_ok=True)
BIN = Path("/data/relay-reactor-ab/binaries")

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
                versions = ["before","after","after-1worker"]
                random.Random(1729+repeat).shuffle(versions)
                for v in versions:
                    extra = {"SM_RELAY_IO_WORKERS":"1"} if v.endswith("1worker") else {}
                    run(v,profile,repeat,seconds=10,envextra=extra)
```

## client.py

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

## analyze.py

```python
import collections
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import struct

import numpy as np
import pandas as pd

RAW = Path("/data/raw/relay-regression-audit")
OUT = Path("/data/analysis/relay-regression-audit")
rows, traces, profiles = [], [], []
for path in sorted(RAW.glob("*.json")):
    d = json.loads(path.read_text())
    assert not d["errors"]
    sample_path = RAW / d["sample_file"]
    assert sample_path.stat().st_size == d["completed"] * 16
    data = np.fromfile(sample_path, dtype=np.float64).reshape((-1, 2))
    assert np.isfinite(data).all() and (data >= 0).all()
    cpu = d["final"]["cpu_s"] - d["initial"]["cpu_s"]
    r = {k: d[k] for k in ["tag", "version", "profile", "repeat", "mode",
                           "completed", "clients", "payload_bytes", "elapsed_s",
                           "window_s", "offered_cycles_s", "binary_sha256"]}
    r["cpu_us_cycle"] = cpu * 1e6 / d["completed"]
    r["cpu_s"] = cpu
    r["user_us_cycle"] = (d["final"]["user_s"] - d["initial"]["user_s"]) * 1e6 / d["completed"]
    r["system_us_cycle"] = (d["final"]["system_s"] - d["initial"]["system_s"]) * 1e6 / d["completed"]
    for col, name in [(0, "service"), (1, "scheduled")]:
        for q in [.5, .95, .99]:
            r[f"{name}_p{int(q*100)}_ms"] = float(np.quantile(data[:, col],q))
    # Independent headline check, not a second numpy/pandas aggregation.
    direct = sorted(x[1] for x in struct.iter_unpack("dd",sample_path.read_bytes()))
    pos = (len(direct)-1)*.99
    i = int(pos)
    q99 = direct[i] + (direct[min(i+1,len(direct)-1)]-direct[i])*(pos-i)
    assert math.isclose(q99,r["scheduled_p99_ms"],abs_tol=1e-10)
    rows.append(r)
for path in sorted(RAW.glob("*.strace")):
    for line in path.read_text().splitlines():
        match = re.match(r"^\s*([\d.]+)\s+([\d.]+)\s+(\d+)\s+(\d+)\s+(?:(\d+)\s+)?([a-zA-Z_0-9]+)\s*$",line)
        if match and match[6] != "total":
            traces.append({"tag":path.stem,"syscall":match[6],
                           "calls":int(match[4]),"errors":int(match[5] or 0),
                           "traced_wall_seconds":float(match[2])})
for path in sorted(RAW.glob("*.perf-report.txt")):
    text = path.read_text()
    n = int(re.search(r"# Samples: ([\d,]+)",text)[1].replace(",",""))
    for line in text.splitlines():
        match = re.match(r"^\s*([\d.]+)%\s+\[\.\]\s+(.*?)\s+-\s+-\s*$",line)
        if match:
            profiles.append({"tag":path.name.replace(".perf-report.txt",""),
                             "samples":n,"user_sample_percent":float(match[1]),
                             "symbol":match[2]})
df = pd.DataFrame(rows)
assert len(df[df["mode"]=="plain"]) == 27
plain = df[df["mode"]=="plain"]
assert (plain.completed == plain.offered_cycles_s * plain.window_s).all()
df.to_csv(OUT/"runs.csv",index=False)
pd.DataFrame(traces).to_csv(OUT/"syscalls.csv",index=False)
pd.DataFrame(profiles).to_csv(OUT/"user-profile.csv",index=False)
grouped = plain.groupby(["profile","version"]).agg(
    repeats=("tag","count"),
    cpu_median=("cpu_us_cycle","median"),
    cpu_min=("cpu_us_cycle","min"), cpu_max=("cpu_us_cycle","max"),
    user_median=("user_us_cycle","median"),
    system_median=("system_us_cycle","median"),
    scheduled_p99_median=("scheduled_p99_ms","median"),
    scheduled_p99_min=("scheduled_p99_ms","min"),
    scheduled_p99_max=("scheduled_p99_ms","max"),
    service_p99_median=("service_p99_ms","median"))
grouped.to_csv(OUT/"summary.csv")
# Independent median/cpu check using raw JSON only.
for (profile, version), r in grouped.iterrows():
    inputs=[json.loads(p.read_text()) for p in RAW.glob("*.json")]
    checks=[(x["final"]["cpu_s"]-x["initial"]["cpu_s"])*1e6/x["completed"]
            for x in inputs if x["mode"]=="plain" and x["version"]==version and x["profile"]==profile]
    assert math.isclose(statistics.median(checks),r.cpu_median)
changes={}
for p in ["light","busy","max"]:
    b=grouped.loc[(p,"before")]
    a=grouped.loc[(p,"after")]
    changes[p]={"cpu_percent":100*(a.cpu_median/b.cpu_median-1),
                "p99_scheduled_percent":100*(a.scheduled_p99_median/b.scheduled_p99_median-1)}
outliers=[]
for row in rows:
    if row["mode"] != "plain" or row["profile"] != "busy":
        continue
    clients=row["clients"]
    assert row["completed"] % clients == 0
    data=np.fromfile(RAW/(row["tag"]+".f64"),dtype=np.float64).reshape((clients,-1,2))
    n=data.shape[1]
    # All clients completed their complete fixed schedule, so worker-concatenated
    # samples can be mapped back to planned time without inventing wall timestamps.
    due=(np.arange(n)[None,:]*clients+np.arange(clients)[:,None])/row["offered_cycles_s"]
    mask=data[:,:,1] > 5
    outliers.append({"tag":row["tag"],"scheduled_over_5ms":int(mask.sum()),
                     "first_second_count":int((mask&(due<1)).sum()),
                     "first_planned_s":float(due[mask].min()) if mask.any() else None,
                     "last_planned_s":float(due[mask].max()) if mask.any() else None,
                     "max_start_lag_ms":float((data[:,:,1]-data[:,:,0]).max())})
cleanup=[]
text=(RAW/"cleanup-probe.log").read_text()
for shards,blobs,budget,rounds in re.findall(r"AUDIT_CLEANUP shards=(\d+) blobs=(\d+) budget=(\d+) ttl_rounds=(\d+)",text):
    cleanup.append(dict(zip(["shards","blobs","step_budget","rounds"],map(int,[shards,blobs,budget,rounds]))))
assert len(cleanup)==3
plan=[
    {"grain":"profile/version","population":"three uninstrumented 10s runs per combination",
     "unit":"CPU microseconds/completed SEND→FETCH→ACK cycle",
     "finding":"light/busy CPU regression persists; one worker is not a general remedy",
     "derivation":"median of per-run CPU delta / completed count; ranges in table",
     "disposition":"chart","reason":"comparison across all three profiles and worker variant"},
    {"grain":"profile/version","population":"completed cycles within each 10s trial",
     "unit":"ms","finding":"p99 changes vary, notably busy tails; no confidence claim",
     "derivation":"median of per-run p99, not pooled quantile",
     "disposition":"table","reason":"preserve CPU/latency trade-off and non-replicated regressions"},
    {"grain":"function/profile","population":"software cpu-clock:u samples",
     "unit":"percent of sampled user CPU","finding":"SHA256 and parser/memmove are hot",
     "derivation":"perf self samples; no kernel and no causal sum",
     "disposition":"table","reason":"different clocks/populations; not 100% total CPU attribution"},
    {"grain":"configuration","population":"one queue with 1000 expired 1-byte blobs, no foreground traffic",
     "unit":"256-step Engine maintenance calls","finding":"cleanup budget is diluted among idle shards",
     "derivation":"deterministic isolated unit probe; not measured wall recovery time",
     "disposition":"prose","reason":"three exact counts explain algorithmic issue without timing chart"},
    {"grain":"syscall/trace","population":"synthetic ptrace-instrumented serial cycles plus setup/warmup",
     "unit":"calls","finding":"new epoll overhead replaces old setsockopt; many small reads persist",
     "derivation":"strace -f -c counts","disposition":"table",
     "reason":"ptrace changes scheduling; durations not usable as benchmark or CPU percentages"},
]
evidence={"audited_sha":"cd74127524e5b8279d35d5add2d2dfea5f27efe1",
          "production_changes":False,"runs":rows,
          "summary":grouped.reset_index().to_dict(orient="records"),
          "changes":changes,"cleanup_probe":cleanup,"syscalls":traces,
          "busy_outlier_timing":outliers,
          "user_profiles":profiles,"comparison_plan":plan,
          "limitations":["2 CPUs, co-located generator, same host as previous comparison",
                         "only three short repetitions; no statistical significance or saturation proof",
                         "changing order with fixed seed; environmental drift not eliminated",
                         "p99 summary is median of three per-run quantiles",
                         "tracing and perf run separately; no performance comparison from traced wall times",
                         "perf cpu-clock:u, no hardware instruction counters or kernel self attribution",
                         "task status context-switch fields are main-thread only and excluded from findings",
                         "no production VPS, TLS/WAN, Android, HA or full RSS test"]}
(OUT/"evidence.json").write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
labels={"light":"4 KiB · 5 клиентов","busy":"4 KiB · 32 клиента","max":"MAX_BLOB · 16"}
colors={"До":"neutral","После · 2 worker":"blue","После · 1 worker":"purple"}
spec={"version":1,"form":"categorical-bars","mode":"grouped","orientation":"vertical",
      "title":"Повторные замеры CPU узла",
      "aside":"мкс CPU / завершённый цикл · медианы 3×10 с · меньше лучше · локальный стенд",
      "labels":list(labels.values()),"axis":{"zero":True},"colorMap":colors,
      "series":[{"label":label,"role":role,"valueKind":"number",
                 "derived":True,"derivationReason":"median of process CPU delta × 1e6 / completed cycles",
                 "data":[float(grouped.loc[(p,v)].cpu_median) for p in labels]}
                for label,role,v in [("До","base","before"),("После · 2 worker","subject","after"),
                                     ("После · 1 worker","aux","after-1worker")]]}
(OUT/"cpu-spec.json").write_text(json.dumps(spec,ensure_ascii=False,indent=2))
table=["| Профиль | CPU до, мкс/цикл | CPU после, мкс/цикл | Δ CPU | p99 с графиком до → после, мс |",
       "|---|---:|---:|---:|---:|"]
range_table=["| Профиль | Версия | CPU медиана [min; max], мкс/цикл | p99 медиана [min; max], мс |",
             "|---|---|---:|---:|"]
for p,label in labels.items():
    b=grouped.loc[(p,"before")];a=grouped.loc[(p,"after")]
    table.append(f"| {label} | {b.cpu_median:.2f} | {a.cpu_median:.2f} | {changes[p]['cpu_percent']:+.1f}% | {b.scheduled_p99_median:.3f} → {a.scheduled_p99_median:.3f} |")
    for v in ["before","after","after-1worker"]:
        r=grouped.loc[(p,v)]
        range_table.append(f"| {label} | {v} | {r.cpu_median:.2f} [{r.cpu_min:.2f}; {r.cpu_max:.2f}] | {r.scheduled_p99_median:.3f} [{r.scheduled_p99_min:.3f}; {r.scheduled_p99_max:.3f}] |")
(OUT/"table.md").write_text("\n".join(table))
(OUT/"ranges.md").write_text("\n".join(range_table))
print("\n".join(table))
print("CPU ranges/p99:",grouped.round(3).to_string())
print("Cleanup:",cleanup)
```

## Лабораторная cfg(test) проба для engine.rs

```rust

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
```

## Проверка очистки и штатных регрессий

Добавить только последний cfg(test) модуль в `engine.rs` отдельного disposable worktree; не менять production methods. Инъецируется номер часа Store, часы ОС не переводятся.

```sh
export PATH="$HOME/.cargo/bin:$PATH"
export PROTOC="$(bash scripts/fetch-protoc.sh)"
CARGO_TARGET_DIR=/data/relay-audit-target cargo test --release --locked -p sm-relay --lib audit_backlog_rounds -- --nocapture
CARGO_TARGET_DIR=/data/relay-audit-target cargo test --workspace --locked
```

Проба считает вызовы обслуживания, не имитирует sleep 10 мс и не измеряет время восстановления. Код cfg(test) отсутствует в измеренных бинарниках before/after. Материалы архива синтетические; perf dwarf stack нельзя снимать/публиковать так на реальных сообщениях/ключах.

