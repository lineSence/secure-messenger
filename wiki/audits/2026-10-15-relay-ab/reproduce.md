# Воспроизведение

Собрать одинаковый лабораторный example на указанных в report.md SHA. Очистить кеш sm-core/sm-relay между worktree либо использовать независимые target dir; проверить разные binary SHA. Пути BIN/ROOT в runner/analyzer заменить на свои. Оба бинарника запускать последовательно, без других нагрузок/сборок; генератор общий с узлом — это ограничение методики. Значения полей f64 — в report.md. Весь синтетический корневой материал ниже намеренно публичный, не использовать в production.

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
R=Path('/data/relay-opt-ab'); RAW=R/'raw'
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
  result={'version':version,'start_utc_s':start_utc,'measured_window_s':300,'elapsed_with_drain_s':elapsed,'source_sha':{'before':'889f5f434949f35e1a58e239a3fd47788b1796db','after':'790ec6470d9a3b8c807f84c0743c2397221e5250'}[version],'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'helper_sha256':hashlib.sha256((R/'helpers/relay_lab.rs').read_bytes()).hexdigest(),'phases':stage_rows,'initial':initial,'final':final,'resources':resources,'errors':errors,'wait_errors':wait_errors,'server_output_empty':not stdout and not stderr,'environment':{'cpu_count':os.cpu_count(),'platform':platform.platform(),'cpu_max':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),'swap_total':next(l for l in Path('/proc/meminfo').read_text().splitlines() if l.startswith('SwapTotal'))},'method':'fixed-rate schedule with bounded synchronous workers; schedule lag measured, unserved plan counted; co-located generator; one run/version, sequential before then after; no TLS/WAN; same default shared quotas and work, new after message-count cap 100000 never reached'}
  (RAW/f'{version}.json').write_text(json.dumps(result,indent=2));assert not errors and not wait_errors,(errors,wait_errors)
 finally:
  if p.poll() is None:p.kill();p.communicate()
for version in ['before','after']:one(version)
print('Both 300-second measured windows completed',flush=True)

```

## analyze_ab.py

```python
import json,math,array,re,statistics
from pathlib import Path
import numpy as np,pandas as pd
R=Path('/data/relay-opt-ab');raw=R/'raw';out=R/'analysis';out.mkdir(exist_ok=True)
rows=[];time_rows=[];sources={}
for version in ['before','after']:
 source=json.loads((raw/f'{version}.json').read_text());sources[version]=source
 assert len(source['phases'])==5 and source['measured_window_s']==300 and not source['errors'] and not source['wait_errors'] and source['server_output_empty']
 ts=np.array([0]+[x['t_s'] for x in source['resources']]+[source['elapsed_with_drain_s']]);cpu=np.array([source['initial']['cpu_s']]+[x['cpu_s'] for x in source['resources']]+[source['final']['cpu_s']])
 for idx,p in enumerate(source['phases']):
  a=np.fromfile(raw/p['samples_file'],dtype=np.float64).reshape(-1,3);assert len(a)==p['completed'] and np.isfinite(a).all() and (a[:,1:]>=0).all();assert (a[:,0]>=idx*60).all() and (a[:,0]<idx*60+61).all()
  start=idx*60;end=start+60;service=a[:,1];scheduled=a[:,2];resources=[x for x in source['resources'] if start<=x['t_s']<end]
  cpu_seconds=float(np.interp(end,ts,cpu)-np.interp(start,ts,cpu))
  row={'version':version,'phase':p['phase'],'clients':p['clients'],'payload_bytes':p['payload_bytes'],'waiters':p['waiters'],'window_s':60,'planned':p['planned'],'completed':p['completed'],'attempted':p['attempted'],'errors':p['errors'],'unserved_plan':p['planned']-p['attempted'],'boundary_completions':p['boundary_completions'],'cycles_s':p['completed']/60,'goodput_mib_s':p['completed']*p['payload_bytes']/60/2**20,'service_p50_ms':float(np.quantile(service,.5)),'service_p95_ms':float(np.quantile(service,.95)),'service_p99_ms':float(np.quantile(service,.99)),'scheduled_p95_ms':float(np.quantile(scheduled,.95)),'scheduled_p99_ms':float(np.quantile(scheduled,.99)),'cpu_seconds':cpu_seconds,'cpu_percent_one_core':cpu_seconds/60*100,'rss_peak_mib':max(x['rss_kib'] for x in resources)/1024,'threads_peak':max(x['threads'] for x in resources)}
  rows.append(row)
  for second in range(start,end):
   mask=(a[:,0]>=second)&(a[:,0]<second+1)
   if mask.any():time_rows.append({'version':version,'phase':p['phase'],'second':second,'completed':int(mask.sum()),'p99_ms':float(np.quantile(a[mask,1],.99))})
df=pd.DataFrame(rows);df.to_csv(out/'comparison.csv',index=False);pd.DataFrame(time_rows).to_csv(out/'per-second.csv',index=False)
comparisons=[]
for phase in df.phase.unique():
 a=df[(df.version=='before')&(df.phase==phase)].iloc[0];b=df[(df.version=='after')&(df.phase==phase)].iloc[0]
 comparisons.append({'phase':phase,'before_service_p99_ms':a.service_p99_ms,'after_service_p99_ms':b.service_p99_ms,'p99_change_percent':100*(b.service_p99_ms/a.service_p99_ms-1),'before_cpu_percent':a.cpu_percent_one_core,'after_cpu_percent':b.cpu_percent_one_core,'cpu_change_percent':100*(b.cpu_seconds/a.cpu_seconds-1),'before_rss_peak_mib':a.rss_peak_mib,'after_rss_peak_mib':b.rss_peak_mib,'before_scheduled_p99_ms':a.scheduled_p99_ms,'after_scheduled_p99_ms':b.scheduled_p99_ms})
# Independently reconcile samples/CPU totals without the pandas summaries.
for version in sources:
 assert int(df[df.version==version].completed.sum())==sum(p['completed'] for p in sources[version]['phases'])
 assert abs(float(df[df.version==version].cpu_seconds.sum())-(np.interp(300,np.array([0]+[x['t_s'] for x in sources[version]['resources']]+[sources[version]['elapsed_with_drain_s']]),np.array([sources[version]['initial']['cpu_s']]+[x['cpu_s'] for x in sources[version]['resources']]+[sources[version]['final']['cpu_s']]))-sources[version]['initial']['cpu_s']))<1e-8
for x in comparisons:
 f=next(p['samples_file'] for p in sources['after']['phases'] if p['phase']==x['phase']);values=array.array('d');values.frombytes((raw/f).read_bytes());v=sorted(values[1::3]);pos=(len(v)-1)*.99;lo=math.floor(pos);hi=math.ceil(pos);assert abs(v[lo]+(v[hi]-v[lo])*(pos-lo)-x['after_service_p99_ms'])<1e-9
headline={v:{'cycles':int(df[df.version==v].completed.sum()),'errors':int(df[df.version==v].errors.sum()),'unserved_plan':int(df[df.version==v].unserved_plan.sum()),'cpu_seconds':float(df[df.version==v].cpu_seconds.sum()),'rss_peak_mib':float(df[df.version==v].rss_peak_mib.max()),'window_s':300} for v in sources}
plan=[{'comparison':'service p99 by phase','grain':'individual successful SEND/FETCH/ACK cycle; fixed phase population','unit':'ms','disposition':'chart','reason':'preserve both gains and regressions across workloads'}, {'comparison':'node CPU by phase','grain':'external cumulative /proc CPU interpolated at fixed 60-second boundaries','unit':'percent of one core','disposition':'chart','reason':'same achieved scheduled throughput makes processing cost decision-relevant'}, {'comparison':'scheduled p99, RSS and throughput','grain':'same phase/workload','disposition':'table','reason':'schedule lag and memory remain visible; no additional redundant plots'}]
evidence={'before':headline['before'],'after':headline['after'],'comparisons':comparisons,'rows':rows,'comparison_plan':plan,'method':'one 300s measured window per version, five fixed 60s phases; sequential co-located generator; scheduled synchronous bounded workers, not independent open-loop saturation; tail comparisons descriptive, not confidence estimates','code_sha':{v:sources[v]['source_sha'] for v in sources},'binary_sha256':{v:sources[v]['binary_sha256'] for v in sources}}
(out/'evidence.json').write_text(json.dumps(evidence,indent=2))
labels=['5 клиентов · 4 КиБ','32 клиента · 4 КиБ','16 клиентов · максимум','48 WAIT + 5 клиентов','16 клиентов · 1 КиБ']
for metric,title,aside,kind,name in [('service_p99_ms','Задержка p99 зависит от сценария, а не только от версии','SEND/FETCH/ACK; один 60-секундный профиль на версию','durationMs','latency'),('cpu_percent_one_core','Стоимость одинаковой нагрузки в CPU','CPU узла; % одного ядра, внешние измерения','percent','cpu')]:
 spec={'version':1,'form':'categorical-bars','title':title,'aside':aside,'mode':'grouped','labels':labels,'axis':{'zero':True},'colorMap':{'До':'blue','После':'orange'},'series':[{'label':label,'valueKind':kind,'role':role,'data':df[df.version==v][metric].tolist()} for v,label,role in [('before','До','base'),('after','После','subject')]]}
 (out/(name+'-chart.json')).write_text(json.dumps(spec,ensure_ascii=False))
print(json.dumps({'headline':headline,'comparisons':comparisons},indent=2))

# Supplemental TTL check: preserve the regression instead of hiding it behind request latency.
expiry=pd.read_json(raw/'expiry-comparison.jsonl',lines=True)
assert len(expiry)==20 and not expiry.duplicated().any()
exp=expiry.groupby(['version','queues'])[['hour_sweep_ms','ttl_ms','idle_ms']].median().reset_index()
exp.to_csv(out/'expiry-comparison.csv',index=False)
evidence['expiry_comparison']=exp.to_dict('records')
evidence['overall_cpu_change_percent']=100*(evidence['after']['cpu_seconds']/evidence['before']['cpu_seconds']-1)
evidence['comparison_plan'].append({'comparison':'expiry under two queue populations','grain':'median of 5 independent process runs, one 16-byte blob per queue','unit':'ms','disposition':'table','reason':'secondary structural test; explicitly retain the mass-TTL regression and differing idle-deletion result'})
(out/'evidence.json').write_text(json.dumps(evidence,indent=2))

```

