#!/usr/bin/env python3
"""OpenJDK HotSpot benchmark-suite controller.

The controller uses only the Python standard library. Experiment files use JSON
syntax with a `.yaml` extension because JSON is a strict subset of YAML.
"""
from __future__ import annotations

import argparse
import dataclasses
import glob
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
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT / ".benchctl"
RUNTIMES_FILE = STATE_DIR / "runtimes.json"
CATALOG_FILE = ROOT / "benchmarks" / "catalog.json"
RESULT_SCHEMA_VERSION = "1.0.0"

COLLECTOR_FLAGS: dict[str, list[str]] = {
    "serial": ["-XX:+UseSerialGC"],
    "parallel": ["-XX:+UseParallelGC"],
    "cms": ["-XX:+UseConcMarkSweepGC"],
    "g1": ["-XX:+UseG1GC"],
    "zgc": ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC"],
    "shenandoah": ["-XX:+UseShenandoahGC"],
    "epsilon": ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"],
}

UNTRUSTED_LEGACY_KPIS = (
    "gc_pause_p99_ms",
    "gc_total_ms",
    "alloc_rate_mb_s",
    "rss_mb",
    "native_mem_mb",
    "cpu_util_pct",
    "process_cpu_pct",
)


class BenchError(RuntimeError):
    """User-facing controller error."""


@dataclasses.dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str

    @property
    def combined(self) -> str:
        return (self.stdout + "\n" + self.stderr).strip()


def command(
    args: Iterable[str | os.PathLike[str]],
    *,
    timeout: int = 20,
    env: dict[str, str] | None = None,
    cwd: Path | None = None,
) -> CommandResult:
    try:
        completed = subprocess.run(
            [str(value) for value in args],
            cwd=str(cwd) if cwd else None,
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        return CommandResult(completed.returncode, completed.stdout, completed.stderr)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return CommandResult(127, "", str(exc))


def parse_java_feature_version(text: str) -> int:
    match = re.search(r'version\s+"([^"]+)"', text)
    if not match:
        match = re.search(r"openjdk\s+([0-9][^\s]*)", text, re.IGNORECASE)
    if not match:
        raise BenchError(f"cannot parse Java version from: {text[:160]!r}")
    version = match.group(1)
    if version.startswith("1."):
        return int(version.split(".")[1])
    feature = re.match(r"\d+", version)
    if not feature:
        raise BenchError(f"cannot parse Java feature version from: {version!r}")
    return int(feature.group(0))


def parse_properties(text: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in text.splitlines():
        match = re.match(r"\s*([\w.]+)\s*=\s*(.*)\s*$", line)
        if match:
            properties[match.group(1)] = match.group(2)
    return properties


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def file_identity(path: Path) -> str:
    return sha256_file(path)


def probe_flag(java: Path, flags: list[str]) -> tuple[bool, str]:
    result = command([java, *flags, "-version"], timeout=20)
    if result.returncode == 0:
        return True, "supported"
    lines = [line.strip() for line in result.combined.splitlines() if line.strip()]
    reason = lines[-1] if lines else f"exit {result.returncode}"
    return False, reason[:300]


def probe_module(java: Path, module: str, feature: int) -> tuple[bool, str]:
    if feature < 9:
        return False, "module system unavailable"
    return probe_flag(java, ["--add-modules", module])


def _tool_path(java_home: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    return java_home / "bin" / f"{name}{suffix}"


def probe_runtime(java: Path) -> dict[str, Any]:
    version_result = command([java, "-version"])
    if version_result.returncode != 0:
        raise BenchError(f"{java} is not runnable: {version_result.combined}")

    version_text = version_result.combined
    feature = parse_java_feature_version(version_text)
    properties_result = command([java, "-XshowSettings:properties", "-version"])
    properties = parse_properties(properties_result.combined)
    java_home = Path(
        properties.get("java.home", str(java.resolve().parent.parent))
    ).expanduser().resolve()
    identity = file_identity(java.resolve())

    collectors: dict[str, dict[str, Any]] = {
        "default": {"supported": True, "flags": [], "reason": "runtime default"}
    }
    for name, flags in COLLECTOR_FLAGS.items():
        supported, reason = probe_flag(java, flags)
        collectors[name] = {
            "supported": supported,
            "flags": flags,
            "reason": reason,
        }

    tools: dict[str, str | None] = {}
    for tool in ("javac", "jar", "jcmd", "jstat", "jmap", "jstack", "jfr"):
        candidate = _tool_path(java_home, tool)
        tools[tool] = str(candidate) if candidate.exists() else None

    unified_logging, _ = probe_flag(java, ["-Xlog:help"])
    legacy_gc_logging, _ = probe_flag(java, ["-XX:+PrintGCDetails"])
    jfr, _ = probe_flag(java, ["-XX:+FlightRecorder"])
    nmt, _ = probe_flag(java, ["-XX:NativeMemoryTracking=summary"])
    cds, _ = probe_flag(java, ["-Xshare:auto"])
    container_support, _ = probe_flag(java, ["-XX:+UseContainerSupport"])
    vector_api, _ = probe_module(java, "jdk.incubator.vector", feature)
    generational_zgc, _ = probe_flag(
        java, ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC", "-XX:+ZGenerational"]
    )
    compact_headers, _ = probe_flag(
        java,
        ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"],
    )

    capabilities: dict[str, Any] = {
        "print_flags_final": probe_flag(java, ["-XX:+PrintFlagsFinal"])[0],
        "unified_logging": unified_logging,
        "legacy_gc_logging": legacy_gc_logging,
        "jfr": jfr,
        "nmt": nmt,
        "cds": cds,
        "appcds": feature >= 10,
        "dynamic_cds": feature >= 13,
        "container_awareness": container_support,
        "modules": feature >= 9,
        "records": feature >= 16,
        "sealed_classes": feature >= 17,
        "virtual_threads": feature >= 21,
        "structured_concurrency_api": feature >= 21,
        "vector_api": vector_api,
        "ffm_api": feature >= 19,
        "ffm_api_stage": (
            "final" if feature >= 22 else "preview" if feature >= 19 else "unavailable"
        ),
        "generational_zgc": generational_zgc,
        "compact_object_headers": compact_headers,
        "preview_features": feature >= 12,
        "code_cache_diagnostics": tools["jcmd"] is not None,
    }

    return {
        "schema_version": "1.0.0",
        "id": f"openjdk-{feature}-{identity[:12]}",
        "java_home": str(java_home),
        "java": str(java.resolve()),
        "feature_version": feature,
        "vendor": properties.get("java.vendor", "unknown"),
        "runtime_name": properties.get("java.runtime.name", "unknown"),
        "runtime_version": properties.get("java.runtime.version", "unknown"),
        "vm_name": properties.get("java.vm.name", "unknown"),
        "vm_version": properties.get("java.vm.version", "unknown"),
        "vm_info": properties.get("java.vm.info", "unknown"),
        "architecture": properties.get("os.arch", platform.machine()),
        "version_output": version_text,
        "identity": identity,
        "tools": tools,
        "collectors": collectors,
        "capabilities": capabilities,
    }


def candidate_java_executables() -> list[Path]:
    executable = "java.exe" if os.name == "nt" else "java"
    candidates: list[Path] = []

    def add_home(raw: str | None) -> None:
        if not raw:
            return
        path = Path(raw).expanduser()
        candidate = path if path.name.lower() == executable else path / "bin" / executable
        if candidate.exists():
            candidates.append(candidate.resolve())

    for raw in os.environ.get("BENCH_JAVA_HOMES", "").split(os.pathsep):
        add_home(raw)
    add_home(os.environ.get("JAVA_HOME"))
    discovered = shutil.which("java")
    if discovered:
        candidates.append(Path(discovered).resolve())

    patterns = (
        "/usr/lib/jvm/*",
        "/opt/jdks/*",
        "/Library/Java/JavaVirtualMachines/*/Contents/Home",
        str(Path.home() / ".sdkman/candidates/java/*"),
        str(Path.home() / ".jabba/jdk/*"),
    )
    for pattern in patterns:
        for home in glob.glob(pattern):
            add_home(home)

    unique: list[Path] = []
    seen: set[str] = set()
    for candidate in candidates:
        key = str(candidate)
        if key not in seen:
            seen.add(key)
            unique.append(candidate)
    return unique


def _is_openjdk_hotspot(runtime: dict[str, Any]) -> bool:
    label = f"{runtime['runtime_name']} {runtime['vm_name']}".lower()
    excluded = (("open" + "j9"), ("graal" + "vm"))
    return "openjdk" in label and not any(value in label for value in excluded)


def discover_runtimes() -> list[dict[str, Any]]:
    runtimes: list[dict[str, Any]] = []
    errors: list[str] = []
    for java in candidate_java_executables():
        try:
            runtime = probe_runtime(java)
            if not _is_openjdk_hotspot(runtime):
                errors.append(
                    f"skipped non-OpenJDK-HotSpot runtime: {java} ({runtime['vm_name']})"
                )
                continue
            if runtime["feature_version"] < 8 or runtime["feature_version"] > 25:
                errors.append(
                    f"skipped JDK {runtime['feature_version']} outside configured range: {java}"
                )
                continue
            runtimes.append(runtime)
        except Exception as exc:  # discovery must continue across broken installs
            errors.append(f"{java}: {exc}")

    runtimes.sort(key=lambda item: (item["feature_version"], item["id"]))
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    RUNTIMES_FILE.write_text(
        json.dumps({"schema_version": "1.0.0", "runtimes": runtimes, "errors": errors}, indent=2)
        + "\n"
    )
    return runtimes


def load_runtimes(required: bool = True) -> list[dict[str, Any]]:
    if not RUNTIMES_FILE.exists():
        return discover_runtimes() if required else []
    return json.loads(RUNTIMES_FILE.read_text()).get("runtimes", [])


def load_document(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise BenchError(
            f"{path} must use JSON syntax (JSON is valid YAML): {exc}"
        ) from exc
    if not isinstance(data, dict):
        raise BenchError(f"{path} must contain an object")
    return data


def catalog() -> list[dict[str, Any]]:
    if CATALOG_FILE.exists():
        return load_document(CATALOG_FILE).get("workloads", [])
    return [
        {
            "id": path.name,
            "tier": "tier-0",
            "path": str(path.relative_to(ROOT)),
            "min_jdk": 8,
            "max_jdk": 25,
        }
        for path in sorted((ROOT / "benchmarks").glob("[0-9][0-9]-*"))
    ]


def validate_experiment(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if data.get("schema_version") != "1.0.0":
        errors.append("schema_version must be 1.0.0")
    if data.get("run_kind") not in {"smoke", "calibration", "benchmark"}:
        errors.append("run_kind must be smoke, calibration, or benchmark")
    for key in ("workloads", "runtimes", "gcs"):
        value = data.get(key)
        if not isinstance(value, list) or not value:
            errors.append(f"{key} must be a non-empty array")
    repetitions = data.get("repetitions", 1)
    if not isinstance(repetitions, int) or repetitions < 1:
        errors.append("repetitions must be a positive integer")
    if data.get("run_kind") == "benchmark" and repetitions < 5:
        errors.append("benchmark runs require at least 5 repetitions")
    for key in ("requests", "threads"):
        value = data.get(key, 1)
        if not isinstance(value, int) or value < 1:
            errors.append(f"{key} must be a positive integer")
    return errors


def select_runtimes(
    selectors: list[str | int], runtimes: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    selected: dict[str, dict[str, Any]] = {}
    for selector in selectors:
        if selector == "all":
            selected.update({runtime["id"]: runtime for runtime in runtimes})
        elif isinstance(selector, int) or (
            isinstance(selector, str) and selector.isdigit()
        ):
            feature = int(selector)
            selected.update(
                {
                    runtime["id"]: runtime
                    for runtime in runtimes
                    if runtime["feature_version"] == feature
                }
            )
        else:
            selected.update(
                {
                    runtime["id"]: runtime
                    for runtime in runtimes
                    if runtime["id"] == selector
                }
            )
    return sorted(selected.values(), key=lambda item: (item["feature_version"], item["id"]))


def select_workloads(selectors: list[str]) -> list[dict[str, Any]]:
    available = {workload["id"]: workload for workload in catalog()}
    if "all" in selectors:
        return list(available.values())
    missing = [selector for selector in selectors if selector not in available]
    if missing:
        raise BenchError(f"unknown workloads: {', '.join(missing)}")
    return [available[selector] for selector in selectors]


def build_plan(data: dict[str, Any]) -> dict[str, Any]:
    errors = validate_experiment(data)
    if errors:
        raise BenchError("invalid experiment:\n- " + "\n- ".join(errors))

    runtimes = select_runtimes(data["runtimes"], load_runtimes())
    workloads = select_workloads(data["workloads"])
    items: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []

    for workload in workloads:
        minimum = int(workload.get("min_jdk", 8))
        maximum = int(workload.get("max_jdk", 25))
        for runtime in runtimes:
            feature = int(runtime["feature_version"])
            for gc in data["gcs"]:
                if feature < minimum or feature > maximum:
                    skipped.append({
                        "workload": workload["id"],
                        "runtime": runtime["id"],
                        "gc": gc,
                        "reason": (
                            f"workload supports JDK {minimum}-{maximum}; "
                            f"runtime is JDK {feature}"
                        ),
                    })
                    continue
                collector = runtime["collectors"].get(gc)
                if collector is None or not collector.get("supported", False):
                    skipped.append({
                        "workload": workload["id"],
                        "runtime": runtime["id"],
                        "gc": gc,
                        "reason": (collector or {}).get(
                            "reason", "collector not discovered"
                        ),
                    })
                    continue
                if data["run_kind"] == "benchmark" and workload.get("tier") not in {
                    "tier-2",
                    "tier-3",
                }:
                    skipped.append({
                        "workload": workload["id"],
                        "runtime": runtime["id"],
                        "gc": gc,
                        "reason": (
                            f"{workload.get('tier', 'unknown')} is not measurement-ready"
                        ),
                    })
                    continue
                items.append({
                    "workload": workload["id"],
                    "workload_path": workload["path"],
                    "tier": workload.get("tier", "tier-0"),
                    "runtime": runtime["id"],
                    "java_home": runtime["java_home"],
                    "java": runtime["java"],
                    "feature_version": feature,
                    "gc": gc,
                    "gc_flags": collector.get("flags", []),
                    "repetitions": data.get("repetitions", 1),
                    "requests": data.get("requests", 25),
                    "threads": data.get("threads", 4),
                })

    return {
        "schema_version": "1.0.0",
        "run_kind": data["run_kind"],
        "generated_at_epoch_ms": int(time.time() * 1000),
        "items": items,
        "skipped": skipped,
    }


def _read_text(path: str) -> str | None:
    try:
        return Path(path).read_text().strip()
    except (OSError, UnicodeError):
        return None


def _linux_cpu_details() -> tuple[str | None, int | None]:
    cpuinfo = _read_text("/proc/cpuinfo")
    if not cpuinfo:
        return None, None
    model = None
    physical: set[tuple[str, str]] = set()
    physical_id = core_id = None
    for line in cpuinfo.splitlines():
        if not line.strip():
            if physical_id is not None and core_id is not None:
                physical.add((physical_id, core_id))
            physical_id = core_id = None
            continue
        key, _, value = line.partition(":")
        key, value = key.strip(), value.strip()
        if key in {"model name", "Hardware"} and model is None:
            model = value
        elif key == "physical id":
            physical_id = value
        elif key == "core id":
            core_id = value
    if physical_id is not None and core_id is not None:
        physical.add((physical_id, core_id))
    return model, len(physical) or None


def _linux_memory_bytes() -> int | None:
    meminfo = _read_text("/proc/meminfo")
    if not meminfo:
        return None
    match = re.search(r"^MemTotal:\s+(\d+)\s+kB$", meminfo, re.MULTILINE)
    return int(match.group(1)) * 1024 if match else None


def _git_state() -> tuple[str | None, bool | None]:
    commit = command(["git", "rev-parse", "HEAD"], cwd=ROOT)
    status = command(["git", "status", "--porcelain"], cwd=ROOT)
    return (
        commit.stdout.strip() if commit.returncode == 0 else None,
        bool(status.stdout.strip()) if status.returncode == 0 else None,
    )


def environment_fingerprint() -> dict[str, Any]:
    cpu_model, physical_cores = _linux_cpu_details()
    git_commit, git_dirty = _git_state()
    cgroup_v2 = Path("/sys/fs/cgroup/cgroup.controllers").exists()
    return {
        "captured_at_epoch_ms": int(time.time() * 1000),
        "os": platform.system(),
        "os_release": platform.release(),
        "os_version": platform.version(),
        "architecture": platform.machine(),
        "python": platform.python_version(),
        "cpu_model": cpu_model or platform.processor() or None,
        "physical_cores": physical_cores,
        "logical_cpus": os.cpu_count(),
        "memory_bytes": _linux_memory_bytes(),
        "cgroup_version": 2 if cgroup_v2 else 1 if Path("/sys/fs/cgroup").exists() else None,
        "cgroup_cpu_max": _read_text("/sys/fs/cgroup/cpu.max"),
        "cgroup_memory_max": _read_text("/sys/fs/cgroup/memory.max"),
        "cpu_governor": _read_text(
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        ),
        "transparent_hugepages": _read_text(
            "/sys/kernel/mm/transparent_hugepage/enabled"
        ),
        "git_commit": git_commit,
        "git_dirty": git_dirty,
        "hostname_hash": hashlib.sha256(platform.node().encode()).hexdigest()[:12],
    }


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def wait_for_health(url: str, process: subprocess.Popen[Any], timeout: int = 20) -> None:
    deadline = time.monotonic() + timeout
    last_error = "not attempted"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise BenchError(
                f"application exited before readiness with code {process.returncode}"
            )
        try:
            with urllib.request.urlopen(url, timeout=0.5) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError) as exc:
            last_error = str(exc)
        time.sleep(0.1)
    raise BenchError(f"readiness timeout for {url}: {last_error}")


def terminate_process(process: subprocess.Popen[Any]) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
        process.wait(timeout=5)
    except Exception:
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
        except Exception:
            pass


def enrich_smoke_result(
    raw: dict[str, Any], item: dict[str, Any], run_id: str, app_pid: int
) -> dict[str, Any]:
    raw.pop("env", None)
    raw.update({
        "schema_version": RESULT_SCHEMA_VERSION,
        "run_id": run_id,
        "run_kind": "smoke",
        "implementation_tier": item["tier"],
        "measurement_valid": False,
        "invalid_reasons": [
            "Tier-0/Tier-1 smoke execution is not a controlled performance benchmark",
            "legacy workload harness telemetry is not normalized from the application process",
        ],
        "warnings": [
            "Latency and throughput values are diagnostic only",
            "Legacy load-generator JVM/OS metrics were discarded",
        ],
        "runtime": item["runtime"],
        "gc": item["gc"],
        "jvm_flags": item["gc_flags"],
        "application_pid": app_pid,
        "environment": environment_fingerprint(),
    })
    kpis = raw.get("kpis")
    if isinstance(kpis, dict):
        for name in UNTRUSTED_LEGACY_KPIS:
            kpis[name] = None
    phases = raw.get("phases")
    if isinstance(phases, dict):
        phases["warmup_s"] = None
        phases["measure_s"] = None
    return raw


def run_plan(plan: dict[str, Any], experiment_path: Path) -> Path:
    del plan, experiment_path
    raise BenchError(
        "the safe runtime orchestrator was not installed; invoke the repository ./benchctl entrypoint"
    )


def validate_result(data: dict[str, Any]) -> list[str]:
    if "results" in data:
        errors: list[str] = []
        for key in ("schema_version", "run_kind", "measurement_valid", "results"):
            if key not in data:
                errors.append(f"missing required aggregate field: {key}")
        if not isinstance(data.get("results"), list):
            errors.append("aggregate results must be an array")
            return errors
        for index, child in enumerate(data["results"]):
            for error in validate_result(child):
                errors.append(f"results[{index}]: {error}")
        if data.get("measurement_valid") is True and any(
            not child.get("measurement_valid", False) for child in data["results"]
        ):
            errors.append("aggregate cannot be valid when a child result is invalid")
        return errors

    errors = []
    for key in (
        "schema_version",
        "run_kind",
        "implementation_tier",
        "measurement_valid",
        "invalid_reasons",
        "warnings",
    ):
        if key not in data:
            errors.append(f"missing required field: {key}")
    if data.get("run_kind") not in {"smoke", "calibration", "benchmark"}:
        errors.append("invalid run_kind")
    if data.get("implementation_tier") not in {
        "tier-0",
        "tier-1",
        "tier-2",
        "tier-3",
    }:
        errors.append("invalid implementation_tier")
    if data.get("measurement_valid") is True and data.get("invalid_reasons"):
        errors.append(
            "measurement_valid cannot be true when invalid_reasons is non-empty"
        )
    return errors


def _load_result_path(path: Path) -> dict[str, Any]:
    return load_document(path / "result.json" if path.is_dir() else path)


def cmd_doctor(_: argparse.Namespace) -> int:
    checks = [
        ("python", sys.version_info >= (3, 10), platform.python_version(), True),
        ("repository", (ROOT / "benchmarks").is_dir(), str(ROOT), True),
        ("java", shutil.which("java") is not None, shutil.which("java") or "not found", True),
        ("git", shutil.which("git") is not None, shutil.which("git") or "not found", True),
        ("curl", shutil.which("curl") is not None, shutil.which("curl") or "optional/missing", False),
        ("docker", shutil.which("docker") is not None, shutil.which("docker") or "optional/missing", False),
    ]
    failed = False
    for name, ok, detail, required in checks:
        status = "OK" if ok else "FAIL" if required else "WARN"
        print(f"{status:4} {name:12} {detail}")
        failed = failed or (required and not ok)
    return 1 if failed else 0


def cmd_discover(_: argparse.Namespace) -> int:
    runtimes = discover_runtimes()
    for runtime in runtimes:
        collectors = ",".join(
            name
            for name, value in runtime["collectors"].items()
            if value.get("supported")
        )
        print(
            f"{runtime['id']} JDK {runtime['feature_version']} "
            f"{runtime['vm_name']} collectors={collectors}"
        )
    return 0 if runtimes else 1


def cmd_list_runtimes(_: argparse.Namespace) -> int:
    for runtime in load_runtimes():
        print(
            f"{runtime['id']}\tJDK {runtime['feature_version']}\t"
            f"{runtime['java_home']}\tsha256={runtime['identity'][:16]}"
        )
    return 0


def cmd_inspect_runtime(args: argparse.Namespace) -> int:
    runtime = next(
        (runtime for runtime in load_runtimes() if runtime["id"] == args.runtime_id),
        None,
    )
    if runtime is None:
        raise BenchError(f"unknown runtime: {args.runtime_id}")
    print(json.dumps(runtime, indent=2))
    return 0


def cmd_list_workloads(_: argparse.Namespace) -> int:
    for workload in catalog():
        print(
            f"{workload['id']}\t{workload.get('tier', 'tier-0')}\t"
            f"JDK {workload.get('min_jdk', 8)}-{workload.get('max_jdk', 25)}\t"
            f"{workload.get('status', 'prototype')}"
        )
    return 0


def cmd_list_capabilities(_: argparse.Namespace) -> int:
    for runtime in load_runtimes():
        enabled = [
            name
            for name, value in runtime["capabilities"].items()
            if value is True or (isinstance(value, str) and value != "unavailable")
        ]
        print(f"{runtime['id']}: {', '.join(enabled)}")
    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    errors = validate_experiment(load_document(Path(args.experiment)))
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        return 1
    print(f"valid: {args.experiment}")
    return 0


def cmd_plan(args: argparse.Namespace) -> int:
    plan = build_plan(load_document(Path(args.experiment)))
    print(json.dumps(plan, indent=2))
    print(
        f"planned={len(plan['items'])} skipped={len(plan['skipped'])}",
        file=sys.stderr,
    )
    return 0 if plan["items"] else 2


def cmd_run(args: argparse.Namespace) -> int:
    experiment = Path(args.experiment).resolve()
    plan = build_plan(load_document(experiment))
    if not plan["items"]:
        raise BenchError("experiment produced no runnable combinations")
    print(run_plan(plan, experiment))
    return 0


def cmd_resume(args: argparse.Namespace) -> int:
    experiment = ROOT / "results" / args.run_id / "experiment.yaml"
    if not experiment.exists():
        raise BenchError(f"cannot resume: {experiment} not found")
    print(run_plan(build_plan(load_document(experiment)), experiment))
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    errors = validate_result(_load_result_path(Path(args.path)))
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        return 1
    print("valid result envelope")
    return 0


def cmd_compare(args: argparse.Namespace) -> int:
    left = _load_result_path(Path(args.left))
    right = _load_result_path(Path(args.right))
    if not left.get("measurement_valid") or not right.get("measurement_valid"):
        print(json.dumps({
            "outcome": "invalid comparison",
            "reason": "both results must be measurement_valid",
        }, indent=2))
        return 2
    if left.get("benchmark") != right.get("benchmark"):
        print(json.dumps({
            "outcome": "invalid comparison",
            "reason": "results describe different workloads",
        }, indent=2))
        return 2
    left_throughput = left.get("kpis", {}).get("throughput")
    right_throughput = right.get("kpis", {}).get("throughput")
    if not isinstance(left_throughput, (int, float)) or not isinstance(
        right_throughput, (int, float)
    ):
        raise BenchError("both results require numeric throughput")
    delta = (
        (right_throughput - left_throughput) / left_throughput * 100
        if left_throughput
        else None
    )
    outcome = (
        "inconclusive"
        if delta is None or abs(delta) < 2
        else "improvement"
        if delta > 0
        else "regression"
    )
    print(json.dumps({"outcome": outcome, "throughput_delta_pct": delta}, indent=2))
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    path = Path(args.path)
    data = _load_result_path(path)
    output = path / "report.html" if path.is_dir() else path.with_suffix(".html")
    output.write_text(
        "<!doctype html><meta charset='utf-8'><title>Benchmark report</title>"
        "<h1>Benchmark report</h1><pre>"
        + html.escape(json.dumps(data, indent=2))
        + "</pre>\n"
    )
    print(output)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="benchctl")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("doctor").set_defaults(func=cmd_doctor)
    subparsers.add_parser("discover-runtimes").set_defaults(func=cmd_discover)
    subparsers.add_parser("list-runtimes").set_defaults(func=cmd_list_runtimes)

    inspect = subparsers.add_parser("inspect-runtime")
    inspect.add_argument("runtime_id")
    inspect.set_defaults(func=cmd_inspect_runtime)

    subparsers.add_parser("list-workloads").set_defaults(func=cmd_list_workloads)
    subparsers.add_parser("list-capabilities").set_defaults(func=cmd_list_capabilities)

    for name, function in (
        ("validate", cmd_validate),
        ("plan", cmd_plan),
        ("run", cmd_run),
    ):
        item = subparsers.add_parser(name)
        item.add_argument("experiment")
        item.set_defaults(func=function)

    resume = subparsers.add_parser("resume")
    resume.add_argument("run_id")
    resume.set_defaults(func=cmd_resume)

    verify = subparsers.add_parser("verify-results")
    verify.add_argument("path")
    verify.set_defaults(func=cmd_verify)

    compare = subparsers.add_parser("compare")
    compare.add_argument("left")
    compare.add_argument("right")
    compare.set_defaults(func=cmd_compare)

    report = subparsers.add_parser("report")
    report.add_argument("path")
    report.set_defaults(func=cmd_report)
    return parser


def main(argv: list[str] | None = None) -> int:
    try:
        args = build_parser().parse_args(argv)
        return int(args.func(args))
    except BenchError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
