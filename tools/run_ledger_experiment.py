#!/usr/bin/env python3
"""Controlled OpenJDK ledger experiment runner.

This is the first workload-specific Tier-2 candidate. It launches the measured
JVM directly, drives it with the shared open-loop HdrHistogram generator,
collects application-process telemetry, retains raw artifacts, and applies
explicit validity gates. A run is never marked valid merely because it exits 0.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import re
import shutil
import signal
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import Any, Iterable

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import benchctl
import telemetry

APP_DIR = ROOT / "benchmarks" / "01-fintech-ledger" / "app"
LOADGEN = ROOT / "tools" / "loadgen" / "run.sh"
MAIN_CLASS = "com.palaashatri.bench.b01.app.BenchmarkApp"
CRITICAL_ENVIRONMENT_FIELDS = (
    "os",
    "os_release",
    "architecture",
    "cpu_model",
    "physical_cores",
    "logical_cpus",
    "memory_bytes",
    "cgroup_version",
    "cgroup_cpu_max",
    "cgroup_memory_max",
    "cpu_governor",
    "transparent_hugepages",
)


class ExperimentError(RuntimeError):
    pass


def run(
    arguments: Iterable[str | os.PathLike[str]],
    *,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    timeout: int = 120,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(value) for value in arguments],
        cwd=str(cwd) if cwd else None,
        env=env,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )


def executable(java_home: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    return java_home / "bin" / f"{name}{suffix}"


def java_environment(java_home: Path) -> dict[str, str]:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(java_home)
    environment["PATH"] = str(java_home / "bin") + os.pathsep + environment.get("PATH", "")
    return environment


def validate_runtime(java_home: Path, collector: str) -> dict[str, Any]:
    java = executable(java_home, "java")
    if not java.exists():
        raise ExperimentError(f"Java executable not found: {java}")
    runtime = benchctl.probe_runtime(java)
    if runtime["feature_version"] < 21 or runtime["feature_version"] > 25:
        raise ExperimentError(
            f"ledger Tier-2 candidate supports JDK 21-25, got JDK {runtime['feature_version']}"
        )
    if not benchctl._is_openjdk_hotspot(runtime):
        raise ExperimentError(f"not an OpenJDK HotSpot runtime: {runtime['vm_name']}")
    selected = runtime["collectors"].get(collector)
    if selected is None or not selected.get("supported"):
        raise ExperimentError(
            f"collector {collector!r} is unavailable: {(selected or {}).get('reason', 'not probed')}"
        )
    for tool in ("javac", "jar", "jcmd", "jstat", "jfr"):
        if not runtime["tools"].get(tool):
            raise ExperimentError(f"selected JDK lacks required tool: {tool}")
    if not runtime["capabilities"].get("jfr"):
        raise ExperimentError("selected runtime does not support JFR")
    if not runtime["capabilities"].get("nmt"):
        raise ExperimentError("selected runtime does not support Native Memory Tracking")
    if not runtime["capabilities"].get("unified_logging"):
        raise ExperimentError("selected runtime does not support unified JVM logging")
    return runtime


def correctness_gate(environment: dict[str, str], output: Path) -> None:
    completed = run([APP_DIR / "run.sh", "test"], cwd=APP_DIR, env=environment, timeout=240)
    output.write_text(completed.stdout + "\n" + completed.stderr)
    if completed.returncode != 0:
        raise ExperimentError(
            f"ledger correctness gate failed ({completed.returncode}): "
            f"{(completed.stdout + completed.stderr)[-500:]}"
        )


def build_dependencies(environment: dict[str, str], output: Path) -> str:
    completed = run([APP_DIR / "run.sh", "build"], cwd=APP_DIR, env=environment, timeout=240)
    output.write_text(completed.stdout + "\n" + completed.stderr)
    if completed.returncode != 0:
        raise ExperimentError(
            f"ledger build failed ({completed.returncode}): "
            f"{(completed.stdout + completed.stderr)[-500:]}"
        )
    classes = APP_DIR / "build" / "run-sh" / "classes"
    dependencies = sorted((APP_DIR / "build" / "run-sh" / "deps").glob("*.jar"))
    if not classes.exists() or not dependencies:
        raise ExperimentError("ledger build did not produce classes and dependency JARs")
    return os.pathsep.join([str(classes), *(str(path) for path in dependencies)])


def build_loadgen(environment: dict[str, str], output: Path) -> None:
    completed = run([LOADGEN, "build"], cwd=ROOT, env=environment, timeout=180)
    output.write_text(completed.stdout + "\n" + completed.stderr)
    if completed.returncode != 0:
        raise ExperimentError(
            f"load generator build failed ({completed.returncode}): "
            f"{(completed.stdout + completed.stderr)[-500:]}"
        )


def free_port() -> int:
    return benchctl.free_port()


def wait_identity(port: int, process: subprocess.Popen[str], token: str, timeout: int = 30) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ExperimentError(f"application exited before readiness: {process.returncode}")
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/runtime", timeout=.5
            ) as response:
                identity = json.load(response)
            if identity.get("run_token") == token and int(identity.get("pid", -1)) == process.pid:
                return identity
            raise ExperimentError(
                f"application identity mismatch: expected pid={process.pid}, token={token!r}; got {identity}"
            )
        except Exception as exception:
            last_error = exception
            time.sleep(.1)
    raise ExperimentError(f"application identity timeout: {last_error}")


def terminate(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
        process.wait(timeout=10)
    except Exception:
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
        except Exception:
            pass


def jfr_to_json(jfr_tool: Path, recording: Path, output: Path) -> dict[str, Any]:
    if not recording.exists() or recording.stat().st_size == 0:
        output.write_text("")
        return {"returncode": 1, "error": "recording missing"}
    completed = run(
        [
            jfr_tool,
            "print",
            "--json",
            "--events",
            "jdk.ObjectAllocationSample,jdk.Compilation,jdk.GarbageCollection",
            recording,
        ],
        timeout=180,
    )
    if completed.returncode == 0:
        output.write_text(completed.stdout)
    else:
        output.write_text(completed.stdout + "\n" + completed.stderr)
    return {
        "returncode": completed.returncode,
        "error": completed.stderr.strip() or None,
    }


def parse_code_cache(text: str) -> dict[str, Any]:
    segments: list[dict[str, Any]] = []
    for line in text.splitlines():
        match = re.search(
            r"CodeHeap '([^']+)': size=(\d+)Kb used=(\d+)Kb max_used=(\d+)Kb free=(\d+)Kb",
            line,
        )
        if match:
            segments.append({
                "name": match.group(1),
                "size_bytes": int(match.group(2)) * 1024,
                "used_bytes": int(match.group(3)) * 1024,
                "max_used_bytes": int(match.group(4)) * 1024,
                "free_bytes": int(match.group(5)) * 1024,
            })
    if not segments:
        match = re.search(
            r"CodeCache: size=(\d+)Kb used=(\d+)Kb max_used=(\d+)Kb free=(\d+)Kb",
            text,
        )
        if match:
            segments.append({
                "name": "CodeCache",
                "size_bytes": int(match.group(1)) * 1024,
                "used_bytes": int(match.group(2)) * 1024,
                "max_used_bytes": int(match.group(3)) * 1024,
                "free_bytes": int(match.group(4)) * 1024,
            })
    return {
        "segments": segments,
        "size_bytes": sum(segment["size_bytes"] for segment in segments) or None,
        "used_bytes": sum(segment["used_bytes"] for segment in segments) or None,
        "max_used_bytes": sum(segment["max_used_bytes"] for segment in segments) or None,
    }


def actual_collector(gc_log: str) -> str | None:
    for pattern in (
        r"Using (Serial)",
        r"Using (Parallel)",
        r"Using (G1)",
        r"Using (Z)",
        r"Using (Shenandoah)",
        r"Using (Epsilon)",
        r"Concurrent Mark Sweep",
    ):
        match = re.search(pattern, gc_log, re.IGNORECASE)
        if match:
            value = match.group(1) if match.lastindex else "CMS"
            return {
                "serial": "serial",
                "parallel": "parallel",
                "g1": "g1",
                "z": "zgc",
                "shenandoah": "shenandoah",
                "epsilon": "epsilon",
                "cms": "cms",
            }.get(value.lower(), value.lower())
    return None


def checksum_tree(root: Path) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name == "checksums.json":
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        values.append({
            "path": str(path.relative_to(root)),
            "sha256": digest,
            "bytes": path.stat().st_size,
        })
    return values


def median_absolute_deviation(values: list[float]) -> float | None:
    if not values:
        return None
    median = statistics.median(values)
    return statistics.median(abs(value - median) for value in values)


def bootstrap_ci(values: list[float], *, seed: int, samples: int = 10_000) -> dict[str, float | None]:
    if not values:
        return {"low": None, "high": None, "confidence": .95}
    random_source = random.Random(seed)
    means = []
    for _ in range(samples):
        means.append(statistics.fmean(random_source.choice(values) for _ in values))
    means.sort()
    low = means[max(0, int(samples * .025))]
    high = means[min(samples - 1, int(samples * .975))]
    return {"low": low, "high": high, "confidence": .95}


def aggregate_metric(values: list[float], seed: int) -> dict[str, Any]:
    if not values:
        return {
            "count": 0,
            "mean": None,
            "median": None,
            "standard_deviation": None,
            "median_absolute_deviation": None,
            "coefficient_of_variation": None,
            "bootstrap_mean_ci": {"low": None, "high": None, "confidence": .95},
            "values": [],
        }
    mean = statistics.fmean(values)
    standard_deviation = statistics.stdev(values) if len(values) > 1 else 0.0
    return {
        "count": len(values),
        "mean": mean,
        "median": statistics.median(values),
        "standard_deviation": standard_deviation,
        "median_absolute_deviation": median_absolute_deviation(values),
        "coefficient_of_variation": standard_deviation / mean if mean else None,
        "bootstrap_mean_ci": bootstrap_ci(values, seed=seed),
        "values": values,
    }


def critical_environment(environment: dict[str, Any]) -> dict[str, Any]:
    return {field: environment.get(field) for field in CRITICAL_ENVIRONMENT_FIELDS}


def run_repetition(
    *,
    repetition: int,
    repetition_dir: Path,
    runtime: dict[str, Any],
    collector: str,
    classpath: str,
    target_rate: float,
    warmup_seconds: int,
    measure_seconds: int,
    threads: int,
    seed: int,
    heap_mb: int,
    extra_jvm_arguments: list[str],
) -> dict[str, Any]:
    repetition_dir.mkdir(parents=True)
    telemetry_dir = repetition_dir / "telemetry"
    telemetry_dir.mkdir()
    java_home = Path(runtime["java_home"])
    java = executable(java_home, "java")
    jcmd = executable(java_home, "jcmd")
    jstat = executable(java_home, "jstat")
    jfr_tool = executable(java_home, "jfr")
    environment = java_environment(java_home)
    token = hashlib.sha256(
        f"ledger-{repetition}-{seed}-{time.time_ns()}".encode()
    ).hexdigest()
    environment["BENCH_RUN_TOKEN"] = token
    port = free_port()
    gc_log = repetition_dir / "gc.log"
    recording = repetition_dir / "recording.jfr"
    app_log_path = repetition_dir / "app.log"

    jvm_arguments = [
        f"-Xms{heap_mb}m",
        f"-Xmx{heap_mb}m",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+DebugNonSafepoints",
        "-XX:NativeMemoryTracking=summary",
        *runtime["collectors"][collector]["flags"],
        (
            "-XX:StartFlightRecording="
            f"filename={recording},settings=profile,dumponexit=true,disk=true,maxsize=512m"
        ),
        (
            "-Xlog:gc*,safepoint,jit+compilation=info:"
            f"file={gc_log}:time,uptime,level,tags"
        ),
        *extra_jvm_arguments,
    ]

    app_log = app_log_path.open("w")
    popen_arguments: dict[str, Any] = {
        "cwd": APP_DIR,
        "env": environment,
        "stdout": app_log,
        "stderr": subprocess.STDOUT,
        "text": True,
    }
    if os.name == "posix":
        popen_arguments["start_new_session"] = True
    process = subprocess.Popen(
        [java, *jvm_arguments, "-cp", classpath, MAIN_CLASS, str(port)],
        **popen_arguments,
    )

    sampler = telemetry.ProcessSampler(
        process.pid,
        repetition_dir / "process-samples.jsonl",
        interval_seconds=.1,
    )
    status = "failed"
    failure: str | None = None
    load_result: dict[str, Any] | None = None
    application_identity: dict[str, Any] | None = None
    process_summary: dict[str, Any] = {}
    jcmd_results: dict[str, Any] = {}
    jstat_results: dict[str, Any] = {}
    environment_fingerprint = benchctl.environment_fingerprint()
    try:
        application_identity = wait_identity(port, process, token)
        sampler.start()
        load_output = repetition_dir / "loadgen.json"
        histogram_output = repetition_dir / "latency.hgrm"
        load = run(
            [
                LOADGEN,
                "run",
                "--base-url",
                f"http://127.0.0.1:{port}",
                "--target-rate",
                str(target_rate),
                "--warmup-seconds",
                str(warmup_seconds),
                "--measure-seconds",
                str(measure_seconds),
                "--threads",
                str(threads),
                "--seed",
                str(seed + repetition),
                "--run-kind",
                "benchmark",
                "--out",
                str(load_output),
                "--histogram-out",
                str(histogram_output),
            ],
            cwd=ROOT,
            env=environment,
            timeout=warmup_seconds + measure_seconds + 120,
        )
        (repetition_dir / "loadgen.log").write_text(load.stdout + "\n" + load.stderr)
        if load.returncode != 0 or not load_output.exists():
            raise ExperimentError(
                f"load generator failed ({load.returncode}): "
                f"{(load.stdout + load.stderr)[-500:]}"
            )
        load_result = json.loads(load_output.read_text())
        if load_result["errors"]["measurement"] != 0:
            raise ExperimentError("measurement phase contains request errors")
        if load_result["completed"]["measurement"] != load_result["scheduled"]["measurement"]:
            raise ExperimentError("measurement phase did not complete every scheduled request")

        jcmd_results = telemetry.collect_jcmd(jcmd, process.pid, telemetry_dir)
        jstat_results = telemetry.collect_jstat(jstat, process.pid, telemetry_dir)
        status = "passed"
    except Exception as exception:
        failure = f"{type(exception).__name__}: {exception}"
    finally:
        process_summary = sampler.stop()
        terminate(process)
        app_log.close()

    jfr_json = repetition_dir / "jfr-events.json"
    jfr_conversion = jfr_to_json(jfr_tool, recording, jfr_json)
    gc_text = gc_log.read_text(errors="replace") if gc_log.exists() else ""
    nmt_text = (telemetry_dir / "jcmd-nmt.txt").read_text(errors="replace") \
        if (telemetry_dir / "jcmd-nmt.txt").exists() else ""
    code_cache_text = (telemetry_dir / "jcmd-code_cache.txt").read_text(errors="replace") \
        if (telemetry_dir / "jcmd-code_cache.txt").exists() else ""
    compiler_text = (telemetry_dir / "jstat-compiler.txt").read_text(errors="replace") \
        if (telemetry_dir / "jstat-compiler.txt").exists() else ""

    normalized_telemetry = {
        "process": process_summary,
        "gc": telemetry.parse_gc_pauses(gc_text),
        "requested_collector": collector,
        "actual_collector": actual_collector(gc_text),
        "nmt": telemetry.parse_nmt(nmt_text),
        "code_cache": parse_code_cache(code_cache_text),
        "compiler": telemetry.parse_jstat_compiler(compiler_text),
        "jfr_conversion": jfr_conversion,
        "jfr": telemetry.parse_jfr_json(jfr_json),
        "raw_jcmd": jcmd_results,
        "raw_jstat": jstat_results,
    }
    (repetition_dir / "telemetry.json").write_text(
        json.dumps(normalized_telemetry, indent=2) + "\n"
    )

    result = {
        "schema_version": "1.0.0",
        "repetition": repetition,
        "status": status,
        "failure": failure,
        "runtime": runtime["id"],
        "java_identity_sha256": runtime["identity"],
        "requested_collector": collector,
        "actual_collector": normalized_telemetry["actual_collector"],
        "jvm_arguments": jvm_arguments,
        "application_identity": application_identity,
        "environment": environment_fingerprint,
        "load": load_result,
        "telemetry": normalized_telemetry,
    }
    (repetition_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    (repetition_dir / "checksums.json").write_text(
        json.dumps(checksum_tree(repetition_dir), indent=2) + "\n"
    )
    return result


def validity(repetitions: list[dict[str, Any]], run_kind: str) -> tuple[bool, list[str], list[str]]:
    reasons: list[str] = []
    warnings: list[str] = []
    if run_kind != "benchmark":
        reasons.append("run_kind is not benchmark")
    if len(repetitions) < 5:
        reasons.append("at least five repetitions are required")
    if any(repetition["status"] != "passed" for repetition in repetitions):
        reasons.append("one or more repetitions failed")
    if any(not repetition.get("load") for repetition in repetitions):
        reasons.append("one or more repetitions lack load-generator output")
    for repetition in repetitions:
        load = repetition.get("load") or {}
        if load.get("errors", {}).get("measurement") != 0:
            reasons.append("measurement request errors were observed")
            break
        if load.get("completed", {}).get("measurement") != load.get("scheduled", {}).get("measurement"):
            reasons.append("scheduled measurement requests were not all completed")
            break
    if any(repetition["actual_collector"] is None for repetition in repetitions):
        reasons.append("actual collector could not be established from JVM logs")
    if any(
        repetition["requested_collector"] != "default"
        and repetition["actual_collector"] != repetition["requested_collector"]
        for repetition in repetitions
    ):
        reasons.append("actual collector differs from requested collector")
    if any(repetition["telemetry"]["process"].get("sample_count", 0) < 10 for repetition in repetitions):
        reasons.append("insufficient application-process samples")
    if any(repetition["telemetry"]["nmt"].get("committed_bytes") is None for repetition in repetitions):
        reasons.append("Native Memory Tracking output is missing")
    if any(repetition["telemetry"]["compiler"].get("compiled") is None for repetition in repetitions):
        reasons.append("compiler statistics are missing")
    if any(repetition["telemetry"]["jfr_conversion"].get("returncode") != 0 for repetition in repetitions):
        reasons.append("JFR conversion failed")
    environments = [critical_environment(repetition["environment"]) for repetition in repetitions]
    if environments and any(environment != environments[0] for environment in environments[1:]):
        reasons.append("critical environment fields changed between repetitions")

    throughputs = [
        float(repetition["load"]["kpis"]["throughput"])
        for repetition in repetitions
        if repetition.get("load")
    ]
    p99_values = [
        float(repetition["load"]["kpis"]["p99_ms"])
        for repetition in repetitions
        if repetition.get("load")
    ]
    if len(throughputs) >= 2:
        cv = statistics.stdev(throughputs) / statistics.fmean(throughputs)
        if cv > .10:
            reasons.append(f"throughput coefficient of variation exceeds 10%: {cv:.4f}")
    if len(p99_values) >= 2 and statistics.fmean(p99_values) > 0:
        cv = statistics.stdev(p99_values) / statistics.fmean(p99_values)
        if cv > .20:
            reasons.append(f"p99 coefficient of variation exceeds 20%: {cv:.4f}")

    if any(repetition["telemetry"]["jfr"].get("allocation_sample_weight_bytes") is None for repetition in repetitions):
        warnings.append("JFR allocation samples were unavailable; allocation-rate KPI remains null")
    if not repetitions:
        reasons.append("no repetitions were produced")
    return not reasons, list(dict.fromkeys(reasons)), list(dict.fromkeys(warnings))


def aggregate(repetitions: list[dict[str, Any]], seed: int) -> dict[str, Any]:
    def values(path: tuple[str, ...]) -> list[float]:
        output: list[float] = []
        for repetition in repetitions:
            value: Any = repetition
            for key in path:
                if not isinstance(value, dict):
                    value = None
                    break
                value = value.get(key)
            if isinstance(value, (int, float)):
                output.append(float(value))
        return output

    allocation_rates: list[float] = []
    for repetition in repetitions:
        weight = repetition["telemetry"]["jfr"].get("allocation_sample_weight_bytes")
        duration = (repetition.get("load") or {}).get("phases", {}).get("measure_s")
        if isinstance(weight, (int, float)) and isinstance(duration, (int, float)) and duration > 0:
            allocation_rates.append(weight / duration / 1024 / 1024)

    return {
        "throughput": aggregate_metric(values(("load", "kpis", "throughput")), seed),
        "p50_ms": aggregate_metric(values(("load", "kpis", "p50_ms")), seed + 1),
        "p99_ms": aggregate_metric(values(("load", "kpis", "p99_ms")), seed + 2),
        "p999_ms": aggregate_metric(values(("load", "kpis", "p999_ms")), seed + 3),
        "gc_pause_p99_ms": aggregate_metric(values(("telemetry", "gc", "p99_ms")), seed + 4),
        "rss_mb_max": aggregate_metric(
            [value / 1024 / 1024 for value in values(("telemetry", "process", "rss_bytes_max"))],
            seed + 5,
        ),
        "process_cpu_pct_one_core_mean": aggregate_metric(
            values(("telemetry", "process", "process_cpu_pct_one_core_mean")), seed + 6
        ),
        "native_memory_committed_mb": aggregate_metric(
            [value / 1024 / 1024 for value in values(("telemetry", "nmt", "committed_bytes"))],
            seed + 7,
        ),
        "code_cache_used_mb": aggregate_metric(
            [value / 1024 / 1024 for value in values(("telemetry", "code_cache", "used_bytes"))],
            seed + 8,
        ),
        "compiled_methods": aggregate_metric(
            values(("telemetry", "compiler", "compiled")), seed + 9
        ),
        "compiler_time_seconds": aggregate_metric(
            values(("telemetry", "compiler", "time_seconds")), seed + 10
        ),
        "jfr_allocation_sample_rate_mb_s": aggregate_metric(allocation_rates, seed + 11),
    }


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--java-home", required=True, type=Path)
    value.add_argument(
        "--gc",
        default="g1",
        choices=["default", "serial", "parallel", "g1", "zgc", "shenandoah", "epsilon"],
    )
    value.add_argument("--target-rate", type=float, default=100.0)
    value.add_argument("--warmup-seconds", type=int, default=30)
    value.add_argument("--measure-seconds", type=int, default=60)
    value.add_argument("--threads", type=int, default=32)
    value.add_argument("--repetitions", type=int, default=5)
    value.add_argument("--seed", type=int, default=424242)
    value.add_argument("--heap-mb", type=int, default=512)
    value.add_argument("--run-kind", choices=["calibration", "benchmark"], default="benchmark")
    value.add_argument("--out", type=Path, required=True)
    value.add_argument("--skip-correctness", action="store_true")
    value.add_argument("--jvm-arg", action="append", default=[])
    return value


def main(arguments: list[str] | None = None) -> int:
    args = parser().parse_args(arguments)
    if os.name != "posix" or not Path("/proc").exists():
        raise ExperimentError("controlled ledger telemetry currently requires Linux /proc")
    if args.repetitions < 1:
        raise ExperimentError("repetitions must be positive")
    if args.run_kind == "benchmark" and args.repetitions < 5:
        raise ExperimentError("benchmark runs require at least five repetitions")
    if args.target_rate <= 0 or args.warmup_seconds < 0 or args.measure_seconds < 1:
        raise ExperimentError("invalid load phase configuration")

    output = args.out.resolve()
    if output.exists() and any(output.iterdir()):
        raise ExperimentError(f"output directory is not empty: {output}")
    output.mkdir(parents=True, exist_ok=True)

    runtime = validate_runtime(args.java_home.resolve(), args.gc)
    environment = java_environment(Path(runtime["java_home"]))
    (output / "runtime.json").write_text(json.dumps(runtime, indent=2) + "\n")
    (output / "experiment.json").write_text(json.dumps({
        "schema_version": "1.0.0",
        "workload": "01-fintech-ledger",
        "run_kind": args.run_kind,
        "runtime": runtime["id"],
        "gc": args.gc,
        "target_rate": args.target_rate,
        "warmup_seconds": args.warmup_seconds,
        "measure_seconds": args.measure_seconds,
        "threads": args.threads,
        "repetitions": args.repetitions,
        "seed": args.seed,
        "heap_mb": args.heap_mb,
        "extra_jvm_arguments": args.jvm_arg,
    }, indent=2) + "\n")

    if not args.skip_correctness:
        correctness_gate(environment, output / "correctness.log")
    classpath = build_dependencies(environment, output / "build-ledger.log")
    build_loadgen(environment, output / "build-loadgen.log")

    repetitions = []
    for repetition in range(1, args.repetitions + 1):
        result = run_repetition(
            repetition=repetition,
            repetition_dir=output / "repetitions" / f"{repetition:02d}",
            runtime=runtime,
            collector=args.gc,
            classpath=classpath,
            target_rate=args.target_rate,
            warmup_seconds=args.warmup_seconds,
            measure_seconds=args.measure_seconds,
            threads=args.threads,
            seed=args.seed,
            heap_mb=args.heap_mb,
            extra_jvm_arguments=args.jvm_arg,
        )
        repetitions.append(result)
        print(
            f"repetition {repetition}/{args.repetitions}: {result['status']}",
            flush=True,
        )

    measurement_valid, invalid_reasons, warnings = validity(repetitions, args.run_kind)
    aggregate_result = aggregate(repetitions, args.seed)
    final = {
        "schema_version": "1.0.0",
        "benchmark": "01-fintech-ledger",
        "run_kind": args.run_kind,
        "implementation_tier": "tier-2" if measurement_valid else "tier-1",
        "measurement_valid": measurement_valid,
        "invalid_reasons": invalid_reasons,
        "warnings": warnings,
        "runtime": runtime["id"],
        "java_identity_sha256": runtime["identity"],
        "requested_collector": args.gc,
        "repetitions": repetitions,
        "aggregate": aggregate_result,
    }
    (output / "result.json").write_text(json.dumps(final, indent=2) + "\n")
    (output / "checksums.json").write_text(
        json.dumps(checksum_tree(output), indent=2) + "\n"
    )
    print(json.dumps({
        "output": str(output),
        "measurement_valid": measurement_valid,
        "invalid_reasons": invalid_reasons,
    }, indent=2))
    return 0 if all(repetition["status"] == "passed" for repetition in repetitions) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ExperimentError as exception:
        print(f"ERROR: {exception}", file=sys.stderr)
        raise SystemExit(2)
