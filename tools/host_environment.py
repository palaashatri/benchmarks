#!/usr/bin/env python3
"""Host observations for Tier 2 measurement, and the quiet-machine preflight.

Each observation returns None when the host does not expose it. Unknown is never
reported as a passing value: a preflight check on an unknown setting fails.
"""
from __future__ import annotations

import glob
import hashlib
import os
from pathlib import Path
from typing import Any

# Environment variables the JVM reads at start-up. They add options the controller did
# not write, so a measured JVM can differ from its recorded flags.
JVM_OPTION_VARIABLES = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")

# A 1-minute load above this fraction of the logical CPUs means the host is not quiet.
MAX_LOAD_FRACTION = 0.5


def read_text(path: str) -> str | None:
    try:
        return Path(path).read_text().strip()
    except (OSError, UnicodeError):
        return None


def cpu_governors() -> list[str] | None:
    """Distinct scaling governors across every CPU, or None when cpufreq is absent."""
    governors = set()
    for path in glob.glob("/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor"):
        value = read_text(path)
        if value:
            governors.add(value)
    return sorted(governors) or None


def count_id_ranges(text: str | None) -> int | None:
    """Number of ids in a kernel range list such as `0-3,8-11` (eight ids)."""
    if not text:
        return None
    count = 0
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            low, high = part.split("-", 1)
            count += int(high) - int(low) + 1
        else:
            count += 1
    return count


def swap_device_count() -> int | None:
    text = read_text("/proc/swaps")
    if text is None:
        return None
    return max(len(text.splitlines()) - 1, 0)


def loadavg_1m() -> float | None:
    text = read_text("/proc/loadavg")
    if not text:
        return None
    try:
        return float(text.split()[0])
    except (ValueError, IndexError):
        return None


def transparent_hugepage_mode() -> str | None:
    """The active mode from `always [madvise] never`, or None."""
    text = read_text("/sys/kernel/mm/transparent_hugepage/enabled")
    if not text:
        return None
    for token in text.split():
        if token.startswith("[") and token.endswith("]"):
            return token[1:-1]
    return None


def injected_jvm_options() -> dict[str, str]:
    """Names of JVM option variables that are set, mapped to a digest of each value.

    Values are digested, not stored: they can contain proxy credentials.
    """
    injected = {}
    for name in JVM_OPTION_VARIABLES:
        value = os.environ.get(name)
        if value:
            injected[name] = hashlib.sha256(value.encode()).hexdigest()[:16]
    return injected


def observations() -> dict[str, Any]:
    """The host fields added to the environment fingerprint."""
    return {
        "cpu_governors": cpu_governors(),
        "transparent_hugepage_mode": transparent_hugepage_mode(),
        "smt_control": read_text("/sys/devices/system/cpu/smt/control"),
        "numa_nodes": count_id_ranges(read_text("/sys/devices/system/node/online")),
        "online_cpus": count_id_ranges(read_text("/sys/devices/system/cpu/online")),
        "swap_devices": swap_device_count(),
        "loadavg_1m": loadavg_1m(),
        "injected_jvm_options": injected_jvm_options(),
    }


def preflight(environment: dict[str, Any]) -> dict[str, Any]:
    """Quiet-machine checks for benchmark runs. `passed` is False if any check fails."""
    checks: list[dict[str, str]] = []

    def record(name: str, status: str, detail: str) -> None:
        checks.append({"check": name, "status": status, "detail": detail})

    governors = environment.get("cpu_governors")
    if governors is None:
        record("cpu_governor", "fail", "CPU frequency governor is not observable")
    elif governors == ["performance"]:
        record("cpu_governor", "pass", "performance on every CPU")
    else:
        record("cpu_governor", "fail", f"governors are {', '.join(governors)}; expected performance")

    injected = environment.get("injected_jvm_options") or {}
    if injected:
        record("jvm_options", "fail",
               "environment injects JVM options outside the recorded flags: " + ", ".join(sorted(injected)))
    else:
        record("jvm_options", "pass", "no JVM option variables are set")

    load = environment.get("loadavg_1m")
    cpus = environment.get("logical_cpus")
    if load is None or not cpus:
        record("load", "fail", "1-minute load average or CPU count is not observable")
    elif load > MAX_LOAD_FRACTION * cpus:
        record("load", "fail", f"1-minute load {load:.2f} exceeds {MAX_LOAD_FRACTION:g} x {cpus} CPUs")
    else:
        record("load", "pass", f"1-minute load {load:.2f} on {cpus} CPUs")

    thp = environment.get("transparent_hugepage_mode")
    if thp is None:
        record("transparent_hugepages", "warn", "THP mode is not observable")
    elif thp in ("never", "madvise"):
        record("transparent_hugepages", "pass", f"THP is {thp}")
    else:
        record("transparent_hugepages", "warn", f"THP is {thp}; allocation latency may vary")

    smt = environment.get("smt_control")
    if smt is None:
        record("smt", "warn", "SMT state is not observable and is recorded as unknown")
    elif smt in ("off", "forceoff", "notsupported"):
        record("smt", "pass", f"SMT is {smt}")
    else:
        record("smt", "warn", f"SMT is {smt}; record it with the results")

    cgroup_cpu = environment.get("cgroup_cpu_max")
    if cgroup_cpu and cgroup_cpu.split()[0] != "max":
        record("cgroup_cpu", "warn", f"cgroup CPU quota is {cgroup_cpu}; throttling can distort latency")
    else:
        record("cgroup_cpu", "pass", "no cgroup CPU quota")

    swap = environment.get("swap_devices")
    if swap is None:
        record("swap", "warn", "swap configuration is not observable")
    elif swap:
        record("swap", "warn", f"{swap} swap device(s) configured")
    else:
        record("swap", "pass", "no swap devices")

    failures = [check for check in checks if check["status"] == "fail"]
    return {
        "passed": not failures,
        "checks": checks,
        "failures": [f"{check['check']}: {check['detail']}" for check in failures],
    }
