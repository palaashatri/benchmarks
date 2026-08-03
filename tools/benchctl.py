#!/usr/bin/env python3
"""OpenJDK benchmark suite controller.

The controller intentionally uses only the Python standard library so runtime
inventory, planning, validation, and smoke orchestration work on a clean host.
JSON documents are used for manifests; JSON is valid YAML, so files retain the
`.yaml` extension without requiring PyYAML.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import html
import json
import os
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT / ".benchctl"
RUNTIMES_FILE = STATE_DIR / "runtimes.json"
CATALOG_FILE = ROOT / "benchmarks" / "catalog.json"
RESULT_SCHEMA_VERSION = "1.0.0"

COLLECTOR_FLAGS = {
    "serial": ["-XX:+UseSerialGC"],
    "parallel": ["-XX:+UseParallelGC"],
    "cms": ["-XX:+UseConcMarkSweepGC"],
    "g1": ["-XX:+UseG1GC"],
    "zgc": ["-XX:+UseZGC"],
    "shenandoah": ["-XX:+UseShenandoahGC"],
    "epsilon": ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"],
}

class BenchError(RuntimeError):
    pass

@dataclasses.dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str
    @property
    def combined(self):
        return (self.stdout + "\n" + self.stderr).strip()

def command(args, *, timeout=20, env=None, cwd=None):
    try:
        completed = subprocess.run(args, cwd=str(cwd) if cwd else None, env=env, capture_output=True, text=True, timeout=timeout, check=False)
        return CommandResult(completed.returncode, completed.stdout, completed.stderr)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return CommandResult(127, "", str(exc))

def parse_java_feature_version(text):
    match = re.search(r'version\s+"([^"]+)"', text) or re.search(r'openjdk\s+([0-9][^\s]*)', text, re.IGNORECASE)
    if not match:
        raise BenchError(f"cannot parse Java version from: {text[:160]!r}")
    version = match.group(1)
    return int(version.split(".")[1]) if version.startswith("1.") else int(re.match(r"\d+", version).group(0))

def parse_properties(text):
    properties = {}
    for line in text.splitlines():
        match = re.match(r"\s*([\w.]+)\s*=\s*(.*)\s*$", line)
        if match:
            properties[match.group(1)] = match.group(2)
    return properties

def file_identity(path):
    stat = path.stat()
    return hashlib.sha256(f"{path.resolve()}:{stat.st_size}:{stat.st_mtime_ns}".encode()).hexdigest()

def probe_flag(java, flags):
    result = command([str(java), *flags, "-version"], timeout=15)
    if result.returncode == 0:
        return True, "supported"
    reason = result.combined.splitlines()[-1] if result.combined else f"exit {result.returncode}"
    return False, reason[:240]

def probe_runtime(java):
    version_result = command([str(java), "-version"])
    if version_result.returncode != 0:
        raise BenchError(f"{java} is not runnable: {version_result.combined}")
    version_text = version_result.combined
    feature = parse_java_feature_version(version_text)
    props = parse_properties(command([str(java), "-XshowSettings:properties", "-version"]).combined)
    java_home = Path(props.get("java.home", str(java.resolve().parent.parent))).resolve()
    identity = file_identity(java)
    collectors = {"default": {"supported": True, "flags": []}}
    for name, flags in COLLECTOR_FLAGS.items():
        supported, reason = probe_flag(java, flags)
        collectors[name] = {"supported": supported, "flags": flags, "reason": reason}
    tools = {}
    for tool in ("javac", "jcmd", "jstat", "jmap", "jstack", "jfr"):
        candidate = java_home / "bin" / (tool + (".exe" if os.name == "nt" else ""))
        tools[tool] = str(candidate) if candidate.exists() else None
    capabilities = {
        "print_flags_final": probe_flag(java, ["-XX:+PrintFlagsFinal"])[0],
        "unified_logging": feature >= 9,
        "legacy_gc_logging": feature <= 8,
        "jfr": tools["jfr"] is not None or feature >= 11,
        "nmt": probe_flag(java, ["-XX:NativeMemoryTracking=summary"])[0],
        "cds": probe_flag(java, ["-Xshare:auto"])[0],
        "appcds": feature >= 8,
        "dynamic_cds": feature >= 13,
        "container_awareness": feature >= 10,
        "virtual_threads": feature >= 21,
        "vector_api": feature >= 16,
        "ffm_api": feature >= 19,
        "generational_zgc": feature >= 21 and collectors["zgc"]["supported"],
        "preview_features": feature >= 12,
    }
    return {
        "schema_version":"1.0.0", "id":f"openjdk-{feature}-{identity[:8]}", "java_home":str(java_home),
        "java":str(java.resolve()), "feature_version":feature, "vendor":props.get("java.vendor","unknown"),
        "runtime_name":props.get("java.runtime.name","unknown"), "vm_name":props.get("java.vm.name","unknown"),
        "vm_version":props.get("java.vm.version","unknown"), "architecture":props.get("os.arch",platform.machine()),
        "version_output":version_text, "identity":identity, "tools":tools, "collectors":collectors, "capabilities":capabilities,
    }

def candidate_java_executables():
    candidates=[]; exe="java.exe" if os.name=="nt" else "java"
    def add_home(raw):
        if not raw: return
        path=Path(raw).expanduser(); java=path if path.name.lower()==exe else path/"bin"/exe
        if java.exists(): candidates.append(java.resolve())
    for raw in os.environ.get("BENCH_JAVA_HOMES","").split(os.pathsep): add_home(raw)
    add_home(os.environ.get("JAVA_HOME"))
    if shutil.which("java"): candidates.append(Path(shutil.which("java")).resolve())
    import glob
    for pattern in ("/usr/lib/jvm/*","/opt/jdks/*",str(Path.home()/".sdkman/candidates/java/*"),str(Path.home()/".jabba/jdk/*")):
        for home in glob.glob(pattern): add_home(home)
    unique=[]; seen=set()
    for java in candidates:
        if str(java) not in seen: seen.add(str(java)); unique.append(java)
    return unique

def discover_runtimes():
    runtimes=[]; errors=[]
    for java in candidate_java_executables():
        try:
            runtime=probe_runtime(java)
            if "hotspot" not in runtime["vm_name"].lower() and "openjdk" not in runtime["runtime_name"].lower():
                errors.append(f"skipped non-OpenJDK/HotSpot runtime: {java} ({runtime['vm_name']})"); continue
            runtimes.append(runtime)
        except Exception as exc: errors.append(f"{java}: {exc}")
    runtimes.sort(key=lambda item:(item["feature_version"],item["id"]))
    STATE_DIR.mkdir(parents=True,exist_ok=True); RUNTIMES_FILE.write_text(json.dumps({"runtimes":runtimes,"errors":errors},indent=2)+"\n")
    return runtimes

def load_runtimes(required=True):
    if not RUNTIMES_FILE.exists(): return discover_runtimes() if required else []
    return json.loads(RUNTIMES_FILE.read_text()).get("runtimes",[])

def load_document(path):
    try: data=json.loads(path.read_text())
    except json.JSONDecodeError as exc: raise BenchError(f"{path} must use JSON syntax (JSON is valid YAML): {exc}") from exc
    if not isinstance(data,dict): raise BenchError(f"{path} must contain an object")
    return data

def catalog():
    if CATALOG_FILE.exists(): return load_document(CATALOG_FILE).get("workloads",[])
    return [{"id":p.name,"tier":"tier-0","path":str(p.relative_to(ROOT))} for p in sorted((ROOT/"benchmarks").glob("[0-9][0-9]-*"))]

def validate_experiment(data):
    errors=[]
    if data.get("schema_version")!="1.0.0": errors.append("schema_version must be 1.0.0")
    if data.get("run_kind") not in {"smoke","calibration","benchmark"}: errors.append("run_kind must be smoke, calibration, or benchmark")
    for key in ("workloads","runtimes","gcs"):
        if not isinstance(data.get(key),list) or not data[key]: errors.append(f"{key} must be a non-empty array")
    repetitions=data.get("repetitions",1)
    if not isinstance(repetitions,int) or repetitions<1: errors.append("repetitions must be a positive integer")
    if data.get("run_kind")=="benchmark" and repetitions<5: errors.append("benchmark runs require at least 5 repetitions")
    return errors

def select_runtimes(selectors,runtimes):
    selected={}
    for selector in selectors:
        if selector=="all": selected.update({r["id"]:r for r in runtimes})
        elif isinstance(selector,int) or (isinstance(selector,str) and selector.isdigit()):
            selected.update({r["id"]:r for r in runtimes if r["feature_version"]==int(selector)})
        else: selected.update({r["id"]:r for r in runtimes if r["id"]==selector})
    return sorted(selected.values(),key=lambda r:(r["feature_version"],r["id"]))

def select_workloads(selectors):
    available={w["id"]:w for w in catalog()}
    if "all" in selectors: return list(available.values())
    missing=[item for item in selectors if item not in available]
    if missing: raise BenchError(f"unknown workloads: {', '.join(missing)}")
    return [available[item] for item in selectors]

def build_plan(data):
    errors=validate_experiment(data)
    if errors: raise BenchError("invalid experiment:\n- "+"\n- ".join(errors))
    runtimes=select_runtimes(data["runtimes"],load_runtimes()); workloads=select_workloads(data["workloads"])
    items=[]; skipped=[]
    for workload in workloads:
        for runtime in runtimes:
            for gc in data["gcs"]:
                collector=runtime["collectors"].get(gc)
                if collector is None or not collector.get("supported",False):
                    skipped.append({"workload":workload["id"],"runtime":runtime["id"],"gc":gc,"reason":(collector or {}).get("reason","collector not discovered")}); continue
                if data["run_kind"]=="benchmark" and workload.get("tier") not in {"tier-2","tier-3"}:
                    skipped.append({"workload":workload["id"],"runtime":runtime["id"],"gc":gc,"reason":f"{workload.get('tier','unknown')} is not measurement-ready"}); continue
                items.append({"workload":workload["id"],"workload_path":workload["path"],"tier":workload.get("tier","tier-0"),"runtime":runtime["id"],"java_home":runtime["java_home"],"java":runtime["java"],"feature_version":runtime["feature_version"],"gc":gc,"gc_flags":collector.get("flags",[]),"repetitions":data.get("repetitions",1),"requests":data.get("requests",25),"threads":data.get("threads",4)})
    return {"schema_version":"1.0.0","run_kind":data["run_kind"],"generated_at_epoch_ms":int(time.time()*1000),"items":items,"skipped":skipped}

def environment_fingerprint():
    return {"os":platform.system(),"os_release":platform.release(),"architecture":platform.machine(),"python":platform.python_version(),"logical_cpus":os.cpu_count(),"hostname_hash":hashlib.sha256(platform.node().encode()).hexdigest()[:12]}

def free_port():
    with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as sock: sock.bind(("127.0.0.1",0)); return int(sock.getsockname()[1])
def wait_for_health(url,process,timeout=20):
    deadline=time.monotonic()+timeout; last_error="not attempted"
    while time.monotonic()<deadline:
        if process.poll() is not None: raise BenchError(f"application exited before readiness with code {process.returncode}")
        try:
            with urllib.request.urlopen(url,timeout=.5) as response:
                if response.status==200:return
        except (OSError,urllib.error.URLError) as exc:last_error=str(exc)
        time.sleep(.1)
    raise BenchError(f"readiness timeout for {url}: {last_error}")
def terminate_process(process):
    if process.poll() is not None:return
    try:
        os.killpg(process.pid,signal.SIGTERM) if os.name=="posix" else process.terminate(); process.wait(timeout=5)
    except Exception:
        try: os.killpg(process.pid,signal.SIGKILL) if os.name=="posix" else process.kill()
        except Exception: pass

def enrich_smoke_result(raw,item,run_id,app_pid):
    raw.update({"schema_version":RESULT_SCHEMA_VERSION,"run_id":run_id,"run_kind":"smoke","implementation_tier":item["tier"],"measurement_valid":False,"invalid_reasons":["Tier-0/Tier-1 smoke execution is not a controlled performance benchmark","legacy workload harness telemetry has not been normalized from the application process"],"warnings":["Latency and throughput values are diagnostic only"],"runtime":item["runtime"],"gc":item["gc"],"jvm_flags":item["gc_flags"],"application_pid":app_pid,"environment":environment_fingerprint()})
    if isinstance(raw.get("kpis"),dict):
        for name in ("gc_pause_p99_ms","alloc_rate_mb_s","native_mem_mb","cpu_util_pct"):
            if raw["kpis"].get(name) in (0,0.0): raw["kpis"][name]=None
    if isinstance(raw.get("phases"),dict):
        for name in ("warmup_s","measure_s"):
            if raw["phases"].get(name)==0:raw["phases"][name]=None
    return raw

def run_plan(plan,experiment_path):
    if plan["run_kind"]=="benchmark":
        if not plan["items"]: raise BenchError("no Tier-2/Tier-3 workload combinations are eligible")
        raise BenchError("benchmark orchestration is blocked until Tier-2 telemetry gates pass")
    run_id=time.strftime("%Y%m%d-%H%M%S")+"-"+hashlib.sha256(os.urandom(16)).hexdigest()[:8]; run_dir=ROOT/"results"/run_id; run_dir.mkdir(parents=True)
    shutil.copy2(experiment_path,run_dir/"experiment.yaml"); (run_dir/"plan.json").write_text(json.dumps(plan,indent=2)+"\n"); (run_dir/"environment.json").write_text(json.dumps(environment_fingerprint(),indent=2)+"\n")
    results=[]
    for index,item in enumerate(plan["items"],1):
        item_dir=run_dir/f"{index:03d}-{item['workload']}-{item['runtime']}-{item['gc']}"; item_dir.mkdir(); app_dir=ROOT/item["workload_path"]/"app"; harness_dir=ROOT/item["workload_path"]/"harness"; app_script=app_dir/"run.sh"; harness_script=harness_dir/"run.sh"
        if not app_script.exists() or not harness_script.exists(): results.append({"workload":item["workload"],"status":"failed","error":"missing run.sh"}); continue
        env=os.environ.copy(); env["JAVA_HOME"]=item["java_home"]; env["PATH"]=str(Path(item["java_home"])/"bin")+os.pathsep+env.get("PATH",""); env["BENCH_RUN_KIND"]=plan["run_kind"]; env["BENCH_GC_FLAGS"]=" ".join(item["gc_flags"]); port=free_port(); app_log=(item_dir/"app.log").open("w")
        kwargs={"cwd":app_dir,"env":env,"stdout":app_log,"stderr":subprocess.STDOUT,"text":True};
        if os.name=="posix":kwargs["start_new_session"]=True
        process=subprocess.Popen([str(app_script),"run",str(port)],**kwargs)
        try:
            wait_for_health(f"http://127.0.0.1:{port}/health",process); raw_path=item_dir/"legacy-results.json"; harness_cmd=[str(harness_script),"run","--base-url",f"http://127.0.0.1:{port}","--requests",str(item["requests"]),"--threads",str(item["threads"]),"--runs","1","--out",str(raw_path)]; harness=command(harness_cmd,cwd=harness_dir,env=env,timeout=180); (item_dir/"harness.log").write_text(harness.combined+"\n")
            if harness.returncode!=0 or not raw_path.exists(): raise BenchError(f"harness failed with exit {harness.returncode}: {harness.combined[-500:]}")
            normalized=enrich_smoke_result(json.loads(raw_path.read_text()),item,run_id,process.pid); (item_dir/"result.json").write_text(json.dumps(normalized,indent=2)+"\n"); results.append(normalized)
        except Exception as exc:
            failure={"schema_version":RESULT_SCHEMA_VERSION,"run_id":run_id,"run_kind":plan["run_kind"],"implementation_tier":item["tier"],"measurement_valid":False,"invalid_reasons":[str(exc)],"warnings":[],"workload":item["workload"],"runtime":item["runtime"],"gc":item["gc"],"status":"failed"}; (item_dir/"result.json").write_text(json.dumps(failure,indent=2)+"\n"); results.append(failure)
        finally: terminate_process(process); app_log.close()
    (run_dir/"result.json").write_text(json.dumps({"schema_version":RESULT_SCHEMA_VERSION,"run_id":run_id,"run_kind":plan["run_kind"],"measurement_valid":False,"results":results},indent=2)+"\n"); return run_dir

def validate_result(data):
    errors=[]
    for key in ("schema_version","run_kind","implementation_tier","measurement_valid","invalid_reasons","warnings"):
        if key not in data:errors.append(f"missing required field: {key}")
    if data.get("run_kind") not in {"smoke","calibration","benchmark"}:errors.append("invalid run_kind")
    if data.get("implementation_tier") not in {"tier-0","tier-1","tier-2","tier-3"}:errors.append("invalid implementation_tier")
    if data.get("measurement_valid") is True and data.get("invalid_reasons"):errors.append("measurement_valid cannot be true when invalid_reasons is non-empty")
    return errors

def cmd_doctor(_):
    checks=[("python",sys.version_info>=(3,10),platform.python_version()),("repository",(ROOT/"benchmarks").is_dir(),str(ROOT)),("java",shutil.which("java") is not None,shutil.which("java") or "not found"),("curl",shutil.which("curl") is not None,shutil.which("curl") or "optional/missing"),("docker",shutil.which("docker") is not None,shutil.which("docker") or "optional/missing")]; failed=False
    for name,ok,detail in checks:
        required=name in {"python","repository","java"}; status="OK" if ok else ("FAIL" if required else "WARN"); print(f"{status:4} {name:12} {detail}"); failed|=required and not ok
    return 1 if failed else 0
def cmd_discover(_):
    runtimes=discover_runtimes()
    for runtime in runtimes: print(f"{runtime['id']} JDK {runtime['feature_version']} {runtime['vm_name']} collectors={','.join(n for n,v in runtime['collectors'].items() if v.get('supported'))}")
    return 0 if runtimes else 1
def cmd_list_runtimes(_):
    for r in load_runtimes():print(f"{r['id']}\tJDK {r['feature_version']}\t{r['java_home']}")
    return 0
def cmd_inspect_runtime(args):
    runtime=next((r for r in load_runtimes() if r["id"]==args.runtime_id),None)
    if not runtime:raise BenchError(f"unknown runtime: {args.runtime_id}")
    print(json.dumps(runtime,indent=2));return 0
def cmd_list_workloads(_):
    for w in catalog():print(f"{w['id']}\t{w.get('tier','tier-0')}\t{w.get('status','prototype')}")
    return 0
def cmd_list_capabilities(_):
    for r in load_runtimes():print(f"{r['id']}: {', '.join(k for k,v in r['capabilities'].items() if v)}")
    return 0
def cmd_validate(args):
    errors=validate_experiment(load_document(Path(args.experiment)))
    if errors:
        for error in errors:print(f"ERROR {error}")
        return 1
    print(f"valid: {args.experiment}");return 0
def cmd_plan(args):
    plan=build_plan(load_document(Path(args.experiment)));print(json.dumps(plan,indent=2));print(f"planned={len(plan['items'])} skipped={len(plan['skipped'])}",file=sys.stderr);return 0 if plan["items"] else 2
def cmd_run(args):
    path=Path(args.experiment).resolve();plan=build_plan(load_document(path))
    if not plan["items"]:raise BenchError("experiment produced no runnable combinations")
    print(run_plan(plan,path));return 0
def cmd_resume(args):
    experiment=ROOT/"results"/args.run_id/"experiment.yaml"
    if not experiment.exists():raise BenchError(f"cannot resume: {experiment} not found")
    print(run_plan(build_plan(load_document(experiment)),experiment));return 0
def load_result_path(path):return load_document(path/"result.json" if path.is_dir() else path)
def cmd_verify(args):
    errors=validate_result(load_result_path(Path(args.path)))
    if errors:
        for error in errors:print(f"ERROR {error}")
        return 1
    print("valid result envelope");return 0
def cmd_compare(args):
    left=load_result_path(Path(args.left));right=load_result_path(Path(args.right))
    if not left.get("measurement_valid") or not right.get("measurement_valid"):print(json.dumps({"outcome":"invalid comparison","reason":"both results must be measurement_valid"},indent=2));return 2
    left_tp=left.get("kpis",{}).get("throughput");right_tp=right.get("kpis",{}).get("throughput")
    if not isinstance(left_tp,(int,float)) or not isinstance(right_tp,(int,float)):raise BenchError("both results require numeric throughput")
    delta=(right_tp-left_tp)/left_tp*100 if left_tp else None;outcome="inconclusive" if delta is None or abs(delta)<2 else ("improvement" if delta>0 else "regression");print(json.dumps({"outcome":outcome,"throughput_delta_pct":delta},indent=2));return 0
def cmd_report(args):
    path=Path(args.path);data=load_result_path(path);output=path/"report.html" if path.is_dir() else path.with_suffix(".html");output.write_text(f"<!doctype html><meta charset='utf-8'><title>Benchmark report</title><h1>Benchmark report</h1><pre>{html.escape(json.dumps(data,indent=2))}</pre>\n");print(output);return 0
def build_parser():
    parser=argparse.ArgumentParser(prog="benchctl");sub=parser.add_subparsers(dest="command",required=True);sub.add_parser("doctor").set_defaults(func=cmd_doctor);sub.add_parser("discover-runtimes").set_defaults(func=cmd_discover);sub.add_parser("list-runtimes").set_defaults(func=cmd_list_runtimes);i=sub.add_parser("inspect-runtime");i.add_argument("runtime_id");i.set_defaults(func=cmd_inspect_runtime);sub.add_parser("list-workloads").set_defaults(func=cmd_list_workloads);sub.add_parser("list-capabilities").set_defaults(func=cmd_list_capabilities)
    for name,func in (("validate",cmd_validate),("plan",cmd_plan),("run",cmd_run)):item=sub.add_parser(name);item.add_argument("experiment");item.set_defaults(func=func)
    r=sub.add_parser("resume");r.add_argument("run_id");r.set_defaults(func=cmd_resume);v=sub.add_parser("verify-results");v.add_argument("path");v.set_defaults(func=cmd_verify);c=sub.add_parser("compare");c.add_argument("left");c.add_argument("right");c.set_defaults(func=cmd_compare);p=sub.add_parser("report");p.add_argument("path");p.set_defaults(func=cmd_report);return parser
def main(argv=None):
    try:return int((args:=build_parser().parse_args(argv)).func(args))
    except BenchError as exc:print(f"ERROR: {exc}",file=sys.stderr);return 2
    except KeyboardInterrupt:print("Interrupted",file=sys.stderr);return 130
if __name__=="__main__":raise SystemExit(main())
