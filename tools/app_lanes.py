#!/usr/bin/env python3
"""Measurement lanes for workloads whose unit of work is not an HTTP request.

startup -- 07-coldstart-suite: one sample is one child JVM, started and checked by the application.
jobs    -- 08-etl-batch: one sample is one batch job, timed and digest-checked by the application.

Each lane starts the workload's application, takes the samples the application reports, computes the
statistics the controller already uses, and writes a result envelope. Failed samples are counted, never
dropped. Unknown values are null, never zero. Envelopes are labelled calibration with measurement_valid
false: the host is not controlled, so the numbers are real but are not evidence for comparison.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Sequence

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import measurement_stats as stats  # noqa: E402

MODES = ("default", "no-cds", "appcds")
REMOVED_ENVIRONMENT = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
UNCONTROLLED_HOST = "host is not controlled: no preflight, no pinned frequency, shared with the driver"
STARTUP_MAIN = "com.palaashatri.bench.b07.app.BenchmarkApp"
JOBS_MAIN = "com.palaashatri.bench.b08.app.BenchmarkApp"


class LaneError(RuntimeError):
    """The lane could not run; the run is abandoned rather than reported with partial data."""


class Application:
    """One application process: started, identity-checked, used, and terminated by this driver."""

    def __init__(self, java: str, classpath: str, main_class: str,
                 jvm_options: Sequence[str] = ("-Xms256m", "-Xmx256m")):
        self.java = java
        self.classpath = classpath
        self.main_class = main_class
        self.jvm_options = list(jvm_options)
        self.token = f"lane-{os.getpid()}-{time.monotonic_ns()}"
        self.process: subprocess.Popen | None = None
        self.base = ""
        self.identity: dict[str, Any] = {}
        self._temp = tempfile.TemporaryDirectory(prefix="app-lane-")

    def __enter__(self) -> "Application":
        port_file = Path(self._temp.name) / "ready.port"
        environment = {key: value for key, value in os.environ.items() if key not in REMOVED_ENVIRONMENT}
        environment.update({"BENCH_RUN_TOKEN": self.token, "BENCH_PORT_FILE": str(port_file)})
        log = open(Path(self._temp.name) / "app.log", "w")
        self.process = subprocess.Popen(
            [self.java, *self.jvm_options, "-cp", self.classpath, self.main_class, "0"],
            env=environment, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 180
        while True:
            if self.process.poll() is not None:
                raise LaneError(f"application exited with {self.process.returncode} before readiness")
            if port_file.exists() and port_file.read_text().strip():
                self.base = f"http://127.0.0.1:{int(port_file.read_text().strip())}"
                break
            if time.monotonic() > deadline:
                raise LaneError("application did not publish a port")
            time.sleep(0.05)
        self.identity = self.get("/runtime")
        if self.identity.get("run_token") != self.token or self.identity.get("pid") != self.process.pid:
            raise LaneError("application identity does not match the process this lane started")
        return self

    def __exit__(self, *exc: object) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self._temp.cleanup()

    def get(self, path: str, timeout: float = 60) -> Any:
        with urllib.request.urlopen(self.base + path, timeout=timeout) as response:
            return json.load(response)

    def post(self, path: str, body: Any, timeout: float = 180) -> tuple[int, Any]:
        request = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), method="POST",
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def resident_kib(self) -> int | None:
        try:
            for line in Path(f"/proc/{self.process.pid}/status").read_text().splitlines():
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])
        except OSError:
            return None
        return None


def summarize(values: Sequence[float | None], *, seed: int) -> dict[str, Any]:
    """Statistics over the known values. Missing values are excluded from the statistics and never zeroed."""
    known = [float(value) for value in values if value is not None]
    if not known:
        return {"count": 0, "median": None, "median_absolute_deviation": None,
                "coefficient_of_variation": None, "bootstrap_mean_ci": {"low": None, "high": None, "confidence": 0.95},
                "minimum": None, "maximum": None, "outliers": []}
    return {
        "count": len(known),
        "median": statistics.median(known),
        "median_absolute_deviation": stats.median_absolute_deviation(known),
        "coefficient_of_variation": stats.coefficient_of_variation(known),
        "bootstrap_mean_ci": stats.bootstrap_ci(known, seed=seed),
        "minimum": min(known),
        "maximum": max(known),
        "outliers": stats.describe_outliers("sample", known)["outliers"],
    }


def envelope(benchmark: str, lane: str, identity: dict[str, Any], reasons: list[str], warnings: list[str],
             parameters: dict[str, Any], results: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "1.0.0",
        "benchmark": benchmark,
        "lane": lane,
        "run_kind": "calibration",
        "implementation_tier": "tier-1",
        "measurement_valid": False,
        "invalid_reasons": [UNCONTROLLED_HOST, *reasons],
        "warnings": warnings,
        "application_runtime": identity,
        "parameters": parameters,
        "results": results,
    }


def run_startup(args: argparse.Namespace) -> dict[str, Any]:
    with Application(args.java, args.app_classpath, STARTUP_MAIN) as app:
        probe = app.get("/api/v1/coldstart/modes")
        per_mode: dict[str, Any] = {}
        recorded_ms: dict[str, list[float]] = {}
        for mode in MODES:
            times: list[float | None] = []
            failures: list[str] = []
            jvm_options: list[str] = []
            for _ in range(args.samples):
                status, body = app.post("/api/v1/coldstart/measure", {"mode": mode})
                if status == 200:
                    times.append(float(body["time_to_health_ms"]))
                    if not jvm_options:
                        jvm_options = list(body["child_runtime"]["jvm_args"])
                else:
                    times.append(None)
                    failures.append(str(body.get("message", body)))
            recorded_ms[mode] = [value for value in times if value is not None]
            per_mode[mode] = {
                "time_to_health_ms": summarize(times, seed=len(per_mode) + 1),
                "requested": args.samples,
                "failures": failures,
                "child_jvm_options": jvm_options,
            }
        app_side = app.get("/api/v1/coldstart/samples")["samples"]
        # The application reports to the microsecond; the response carries three decimals.
        matches = all(
            len(app_side[mode]["time_to_health_ms"]) == len(recorded_ms[mode])
            and all(abs(a - b) <= 0.0006 for a, b in zip(app_side[mode]["time_to_health_ms"], recorded_ms[mode]))
            for mode in MODES)
        identity = app.identity
    reasons = ["startup lane has no per-sample repetition gates and no page-cache control"]
    warnings = ["the page cache is warm; a cold page cache needs root, which this lane does not use",
                "times are the application's own spawn-to-ready measurements, read per response"]
    return envelope("07-coldstart-suite", "startup", identity, reasons, warnings,
                    {"samples_per_mode": args.samples},
                    {"modes": per_mode, "mode_probe": probe, "app_recorded_samples_match_driver": matches})


def run_jobs(args: argparse.Namespace) -> dict[str, Any]:
    with Application(args.java, args.app_classpath, JOBS_MAIN) as app:
        peak = {"kib": None}
        done = threading.Event()

        def sample_resident() -> None:
            while not done.is_set():
                kib = app.resident_kib()
                if kib is not None and (peak["kib"] is None or kib > peak["kib"]):
                    peak["kib"] = kib
                done.wait(0.25)

        def run_one(_: int) -> dict[str, Any]:
            status, body = app.post("/api/v1/etl/run", {})
            if status != 200:
                return {"status": "rejected", "failure": str(body)}
            job = body["job_id"]
            deadline = time.monotonic() + args.job_timeout
            while True:
                snapshot = app.get(f"/api/v1/etl/{job}/status")
                if snapshot["status"] in ("completed", "failed"):
                    return snapshot
                if time.monotonic() > deadline:
                    return {"job_id": job, "status": "timeout", "failure": "job did not finish in time"}
                time.sleep(0.1)

        sampler = threading.Thread(target=sample_resident, daemon=True)
        sampler.start()
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            statuses = list(pool.map(run_one, range(args.jobs)))
        done.set()
        sampler.join()
        identity = app.identity

    completed = [s for s in statuses if s.get("status") == "completed"]
    failed = [s for s in statuses if s.get("status") != "completed"]
    metrics = {
        name: summarize([s.get(field) for s in completed], seed=index + 1)
        for index, (name, field) in enumerate([
            ("records_per_second", "records_per_second"),
            ("io_throughput_mb_s", "io_throughput_mb_s"),
            ("elapsed_ms", "elapsed_ms"),
        ])
    }
    results = {
        "jobs_requested": args.jobs,
        "jobs_completed": len(completed),
        "jobs_failed": [{"job_id": s.get("job_id"), "status": s.get("status"), "failure": s.get("failure")}
                        for s in failed],
        "digest_matches": sum(1 for s in completed if s.get("digest_match") is True),
        "metrics": metrics,
        "resident_mib_max": None if peak["kib"] is None else peak["kib"] / 1024,
        "raw_job_statuses": statuses,
    }
    reasons = ["jobs are submitted by the lane, not by a load schedule; there is no open-loop arrival model"]
    warnings = ["elapsed time includes record generation, so io_throughput_mb_s is end-to-end, not a pure I/O rate"]
    return envelope("08-etl-batch", "jobs", identity, reasons, warnings,
                    {"jobs": args.jobs, "concurrency": args.concurrency, "job_timeout_seconds": args.job_timeout},
                    results)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    lanes = parser.add_subparsers(dest="lane", required=True)
    for name in ("startup", "jobs"):
        lane = lanes.add_parser(name)
        lane.add_argument("--app-classpath", required=True,
                          help="application classes or JAR (build the app first with ./run.sh build)")
        lane.add_argument("--java", default="java", help="java executable for the application")
        lane.add_argument("--out", type=Path, required=True, help="result envelope to write")
    lanes.choices["startup"].add_argument("--samples", type=int, default=10, help="samples per mode")
    lanes.choices["jobs"].add_argument("--jobs", type=int, default=10, help="jobs to submit")
    lanes.choices["jobs"].add_argument("--concurrency", type=int, default=1, help="jobs in flight at once")
    lanes.choices["jobs"].add_argument("--job-timeout", type=float, default=300, help="seconds per job")
    args = parser.parse_args(argv)
    try:
        result = run_startup(args) if args.lane == "startup" else run_jobs(args)
    except LaneError as failure:
        print(f"lane abandoned: {failure}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
