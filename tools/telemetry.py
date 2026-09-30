#!/usr/bin/env python3
"""Linux/OpenJDK telemetry helpers used by controlled workload runners."""
from __future__ import annotations

import dataclasses
import json
import math
import os
import re
import statistics
import subprocess
import threading
import time
from pathlib import Path
from typing import Any


@dataclasses.dataclass
class Sample:
    epoch_ns: int
    process_cpu_pct_one_core: float | None
    process_cpu_pct_host: float | None
    rss_bytes: int | None
    rss_high_water_bytes: int | None
    threads: int | None
    read_bytes: int | None
    write_bytes: int | None

    def to_json(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


class ProcessSampler:
    def __init__(self, pid: int, output: Path, interval_seconds: float = 0.1):
        self.pid = pid
        self.output = output
        self.interval_seconds = interval_seconds
        self.samples: list[Sample] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._last_ticks: int | None = None
        self._last_time_ns: int | None = None
        self._clock_ticks = os.sysconf(os.sysconf_names["SC_CLK_TCK"])
        self._logical_cpus = max(1, os.cpu_count() or 1)

    def start(self) -> None:
        self.output.parent.mkdir(parents=True, exist_ok=True)
        self._thread = threading.Thread(target=self._run, name="process-sampler", daemon=True)
        self._thread.start()

    def stop(self) -> dict[str, Any]:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=max(5, self.interval_seconds * 10))
        self.output.write_text(
            "".join(json.dumps(sample.to_json(), sort_keys=True) + "\n" for sample in self.samples)
        )
        return summarize_samples(self.samples)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self.samples.append(self._sample())
            except (FileNotFoundError, ProcessLookupError):
                break
            except Exception:
                # Sampling must not terminate the measured process or the runner.
                pass
            self._stop.wait(self.interval_seconds)

    def _sample(self) -> Sample:
        now = time.time_ns()
        stat = read_proc_stat(self.pid)
        status = read_key_value_file(Path(f"/proc/{self.pid}/status"))
        io = read_key_value_file(Path(f"/proc/{self.pid}/io"))
        ticks = stat["utime_ticks"] + stat["stime_ticks"]
        cpu_one: float | None = None
        cpu_host: float | None = None
        if self._last_ticks is not None and self._last_time_ns is not None:
            elapsed_seconds = (now - self._last_time_ns) / 1_000_000_000.0
            cpu_seconds = (ticks - self._last_ticks) / float(self._clock_ticks)
            if elapsed_seconds > 0:
                cpu_one = max(0.0, cpu_seconds / elapsed_seconds * 100.0)
                cpu_host = cpu_one / self._logical_cpus
        self._last_ticks = ticks
        self._last_time_ns = now
        return Sample(
            epoch_ns=now,
            process_cpu_pct_one_core=cpu_one,
            process_cpu_pct_host=cpu_host,
            rss_bytes=parse_kib(status.get("VmRSS")),
            rss_high_water_bytes=parse_kib(status.get("VmHWM")),
            threads=parse_int(status.get("Threads")) or stat.get("threads"),
            read_bytes=parse_int(io.get("read_bytes")),
            write_bytes=parse_int(io.get("write_bytes")),
        )


def read_proc_stat(pid: int) -> dict[str, int]:
    text = Path(f"/proc/{pid}/stat").read_text()
    close = text.rfind(")")
    if close < 0:
        raise ValueError("malformed /proc stat")
    fields = text[close + 2 :].split()
    # fields[0] is original field 3 (state).
    return {
        "utime_ticks": int(fields[11]),
        "stime_ticks": int(fields[12]),
        "threads": int(fields[17]),
        "rss_pages": int(fields[21]),
    }


def read_key_value_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition(":")
        if separator:
            values[key.strip()] = value.strip()
    return values


def parse_kib(value: str | None) -> int | None:
    if value is None:
        return None
    match = re.match(r"(\d+)\s+kB", value)
    return int(match.group(1)) * 1024 if match else None


def parse_int(value: str | None) -> int | None:
    if value is None:
        return None
    match = re.match(r"-?\d+", value)
    return int(match.group(0)) if match else None


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(
        len(ordered) - 1,
        max(0, math.ceil(percentile_value / 100.0 * len(ordered)) - 1),
    )
    return ordered[index]


def summarize_samples(samples: list[Sample]) -> dict[str, Any]:
    cpu_one = [value for sample in samples if (value := sample.process_cpu_pct_one_core) is not None]
    cpu_host = [value for sample in samples if (value := sample.process_cpu_pct_host) is not None]
    rss = [float(value) for sample in samples if (value := sample.rss_bytes) is not None]
    threads = [float(value) for sample in samples if (value := sample.threads) is not None]
    return {
        "sample_count": len(samples),
        "process_cpu_pct_one_core_mean": statistics.fmean(cpu_one) if cpu_one else None,
        "process_cpu_pct_one_core_p95": percentile(cpu_one, 95),
        "process_cpu_pct_host_mean": statistics.fmean(cpu_host) if cpu_host else None,
        "rss_bytes_mean": statistics.fmean(rss) if rss else None,
        "rss_bytes_p95": percentile(rss, 95),
        "rss_bytes_max": max(rss) if rss else None,
        "threads_max": max(threads) if threads else None,
        "read_bytes_end": next((sample.read_bytes for sample in reversed(samples) if sample.read_bytes is not None), None),
        "write_bytes_end": next((sample.write_bytes for sample in reversed(samples) if sample.write_bytes is not None), None),
    }


def run_tool(arguments: list[str], output: Path, timeout: int = 30) -> dict[str, Any]:
    try:
        completed = subprocess.run(
            arguments,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        text = (completed.stdout + "\n" + completed.stderr).strip()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text + "\n")
        return {"returncode": completed.returncode, "output": text}
    except Exception as exception:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(f"{type(exception).__name__}: {exception}\n")
        return {"returncode": 127, "output": str(exception)}


def collect_jcmd(jcmd: Path, pid: int, output_dir: Path) -> dict[str, Any]:
    commands = {
        "nmt": ["VM.native_memory", "summary", "scale=KB"],
        "code_cache": ["Compiler.codecache"],
        "compiler_queue": ["Compiler.queue"],
        "heap": ["GC.heap_info"],
        "flags": ["VM.flags", "-all"],
    }
    results: dict[str, Any] = {}
    for name, command in commands.items():
        results[name] = run_tool(
            [str(jcmd), str(pid), *command], output_dir / f"jcmd-{name}.txt"
        )
    return results


def collect_jstat(jstat: Path, pid: int, output_dir: Path) -> dict[str, Any]:
    return {
        "compiler": run_tool(
            [str(jstat), "-compiler", str(pid)], output_dir / "jstat-compiler.txt"
        ),
        "gc": run_tool([str(jstat), "-gc", str(pid)], output_dir / "jstat-gc.txt"),
    }


def parse_gc_pauses(text: str) -> dict[str, Any]:
    values_ms: list[float] = []
    pattern = re.compile(r"\bPause\b.*?([0-9]+(?:\.[0-9]+)?)(ms|s)\b")
    for line in text.splitlines():
        match = pattern.search(line)
        if not match:
            continue
        value = float(match.group(1))
        if match.group(2) == "s":
            value *= 1_000.0
        values_ms.append(value)
    return {
        "count": len(values_ms),
        "total_ms": sum(values_ms),
        "p50_ms": percentile(values_ms, 50),
        "p95_ms": percentile(values_ms, 95),
        "p99_ms": percentile(values_ms, 99),
        "max_ms": max(values_ms) if values_ms else None,
    }


def parse_nmt(text: str) -> dict[str, Any]:
    result: dict[str, Any] = {"reserved_bytes": None, "committed_bytes": None}
    match = re.search(r"Total:\s+reserved=(\d+)KB,\s+committed=(\d+)KB", text)
    if match:
        result["reserved_bytes"] = int(match.group(1)) * 1024
        result["committed_bytes"] = int(match.group(2)) * 1024
    return result


def parse_jstat_compiler(text: str) -> dict[str, Any]:
    lines = [line.split() for line in text.splitlines() if line.strip()]
    if len(lines) < 2:
        return {"compiled": None, "failed": None, "invalid": None, "time_seconds": None}
    header, values = lines[-2], lines[-1]
    mapping = dict(zip(header, values))
    return {
        "compiled": parse_int(mapping.get("Compiled")),
        "failed": parse_int(mapping.get("Failed")),
        "invalid": parse_int(mapping.get("Invalid")),
        "time_seconds": float(mapping["Time"]) if "Time" in mapping else None,
    }


def parse_jfr_json(path: Path) -> dict[str, Any]:
    if not path.exists() or path.stat().st_size == 0:
        return {
            "allocation_sample_weight_bytes": None,
            "compilation_events": None,
            "compilation_duration_ms": None,
            "gc_events": None,
        }
    document = json.loads(path.read_text())
    events = document.get("recording", {}).get("events", [])
    allocation_weight = 0
    allocation_seen = False
    compilation_events = 0
    compilation_duration_ms = 0.0
    gc_events = 0
    for event in events:
        event_type = event.get("type")
        values = event.get("values", {})
        if event_type == "jdk.ObjectAllocationSample":
            weight = values.get("weight")
            if isinstance(weight, (int, float)):
                allocation_weight += int(weight)
                allocation_seen = True
        elif event_type == "jdk.Compilation":
            compilation_events += 1
            compilation_duration_ms += duration_to_ms(values.get("duration")) or 0.0
        elif event_type == "jdk.GarbageCollection":
            gc_events += 1
    return {
        "allocation_sample_weight_bytes": allocation_weight if allocation_seen else None,
        "compilation_events": compilation_events,
        "compilation_duration_ms": compilation_duration_ms,
        "gc_events": gc_events,
    }


def duration_to_ms(value: Any) -> float | None:
    if isinstance(value, (int, float)):
        # JFR JSON numeric durations are nanoseconds.
        return float(value) / 1_000_000.0
    if not isinstance(value, str):
        return None
    match = re.fullmatch(r"PT([0-9]+(?:\.[0-9]+)?)S", value)
    return float(match.group(1)) * 1_000.0 if match else None
