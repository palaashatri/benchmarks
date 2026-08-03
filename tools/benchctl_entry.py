#!/usr/bin/env python3
"""Runtime-safe entry point layered over the dependency-free controller."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

import benchctl as core


_original_build_plan = core.build_plan
_original_validate_result = core.validate_result


def constrained_build_plan(data: dict) -> dict:
    """Remove workload/runtime combinations outside each implementation band."""
    plan = _original_build_plan(data)
    workload_rules = {item["id"]: item for item in core.catalog()}
    accepted = []
    for item in plan["items"]:
        rule = workload_rules[item["workload"]]
        minimum = int(rule.get("min_jdk", 8))
        maximum = int(rule.get("max_jdk", 25))
        feature = int(item["feature_version"])
        if minimum <= feature <= maximum:
            accepted.append(item)
            continue
        plan["skipped"].append({
            "workload": item["workload"],
            "runtime": item["runtime"],
            "gc": item["gc"],
            "reason": f"workload supports JDK {minimum}-{maximum}; runtime is JDK {feature}",
        })
    plan["items"] = accepted
    return plan


def validate_result_document(data: dict) -> list[str]:
    """Validate either one normalized result or a run-level aggregate."""
    if "results" not in data:
        return _original_validate_result(data)
    errors = []
    for key in ("schema_version", "run_kind", "measurement_valid", "results"):
        if key not in data:
            errors.append(f"missing required aggregate field: {key}")
    if not isinstance(data.get("results"), list):
        errors.append("aggregate results must be an array")
        return errors
    for index, result in enumerate(data["results"]):
        for error in _original_validate_result(result):
            errors.append(f"results[{index}]: {error}")
    if data.get("measurement_valid") is True and any(
            not item.get("measurement_valid", False) for item in data["results"]):
        errors.append("aggregate cannot be valid when a child result is invalid")
    return errors


def _identity(url: str, process: subprocess.Popen[str], token: str) -> None:
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            value = json.load(response)
    except urllib.error.HTTPError as exc:
        # Older Tier-0 prototypes do not all expose /runtime yet. A random port
        # plus process-liveness still prevents the stale fixed-port false pass.
        if exc.code == 404:
            return
        raise
    if value.get("run_token") != token or int(value.get("pid", -1)) != process.pid:
        raise core.BenchError("runtime identity mismatch; unrelated process detected")


def _sanitize_legacy_result(result: dict) -> dict:
    """Discard metrics known to come from the legacy load-generator process."""
    result.pop("env", None)
    kpis = result.get("kpis")
    if isinstance(kpis, dict):
        for name in (
            "gc_pause_p99_ms",
            "gc_total_ms",
            "alloc_rate_mb_s",
            "rss_mb",
            "native_mem_mb",
            "cpu_util_pct",
            "process_cpu_pct",
        ):
            kpis[name] = None
    phases = result.get("phases")
    if isinstance(phases, dict):
        phases["warmup_s"] = None
        phases["measure_s"] = None
    warnings = result.setdefault("warnings", [])
    warning = "Legacy harness JVM/OS metrics were discarded because they describe the load generator, not the application"
    if warning not in warnings:
        warnings.append(warning)
    return result


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _build_shared_artifacts(plan: dict, run_dir: Path) -> dict[str, dict]:
    """Build compatibility artifacts once and reuse them across runtime runs."""
    compatibility_items = [
        item for item in plan["items"] if item["workload"] == "00-runtime-compatibility"
    ]
    if not compatibility_items:
        return {}

    runtime_by_id = {runtime["id"]: runtime for runtime in core.load_runtimes()}
    build_candidates = [
        item for item in compatibility_items
        if runtime_by_id.get(item["runtime"], {}).get("tools", {}).get("javac")
    ]
    if not build_candidates:
        raise core.BenchError("the compatibility lane requires one discovered JDK with javac")
    builder = max(build_candidates, key=lambda item: item["feature_version"])
    app_dir = core.ROOT / builder["workload_path"] / "app"
    artifact_dir = run_dir / "artifacts"
    artifact_dir.mkdir()
    artifact_path = (artifact_dir / "compatibility-app-java8.jar").resolve()

    env = os.environ.copy()
    env["JAVA_HOME"] = builder["java_home"]
    env["PATH"] = str(Path(builder["java_home"]) / "bin") + os.pathsep + env.get("PATH", "")
    built = core.command(
        [str(app_dir / "run.sh"), "artifact", str(artifact_path)],
        cwd=app_dir,
        env=env,
        timeout=120,
    )
    (artifact_dir / "build.log").write_text(built.combined + "\n")
    if built.returncode != 0 or not artifact_path.exists():
        raise core.BenchError(
            f"compatibility artifact build failed ({built.returncode}): {built.combined[-400:]}")

    metadata = {
        "path": str(artifact_path),
        "relative_path": str(artifact_path.relative_to(run_dir)),
        "sha256": _sha256(artifact_path),
        "bytecode_target": 8,
        "builder_runtime": builder["runtime"],
    }
    (artifact_dir / "artifacts.json").write_text(json.dumps({
        "00-runtime-compatibility": metadata,
    }, indent=2) + "\n")
    return {"00-runtime-compatibility": metadata}


def safe_run_plan(plan: dict, experiment_path: Path) -> Path:
    if plan["run_kind"] == "benchmark":
        raise core.BenchError("benchmark execution is blocked until a workload reaches Tier 2")
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + os.urandom(4).hex()
    run_dir = core.ROOT / "results" / run_id
    run_dir.mkdir(parents=True)
    shutil.copy2(experiment_path, run_dir / "experiment.yaml")
    (run_dir / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    (run_dir / "environment.json").write_text(
        json.dumps(core.environment_fingerprint(), indent=2) + "\n")
    shared_artifacts = _build_shared_artifacts(plan, run_dir)

    outputs = []
    for index, item in enumerate(plan["items"], 1):
        item_dir = run_dir / f"{index:03d}-{item['workload']}-{item['runtime']}-{item['gc']}"
        item_dir.mkdir()
        app_dir = core.ROOT / item["workload_path"] / "app"
        harness_dir = core.ROOT / item["workload_path"] / "harness"

        base_env = os.environ.copy()
        base_env["JAVA_HOME"] = item["java_home"]
        base_env["PATH"] = str(Path(item["java_home"]) / "bin") + os.pathsep + base_env.get("PATH", "")
        token = os.urandom(24).hex()
        base_env["BENCH_RUN_TOKEN"] = token

        # Collector flags belong to the measured application JVM only. The load
        # generator must not silently inherit the target collector selection.
        app_env = base_env.copy()
        selected_flags = " ".join(item["gc_flags"])
        app_env["JAVA_TOOL_OPTIONS"] = " ".join(
            value for value in (base_env.get("JAVA_TOOL_OPTIONS", "").strip(), selected_flags) if value)
        harness_env = base_env.copy()
        artifact = shared_artifacts.get(item["workload"])
        if artifact:
            app_env["BENCH_APP_ARTIFACT"] = artifact["path"]

        port = core.free_port()
        app_log = (item_dir / "app.log").open("w")
        kwargs = {
            "cwd": app_dir,
            "env": app_env,
            "stdout": app_log,
            "stderr": subprocess.STDOUT,
            "text": True,
        }
        if os.name == "posix":
            kwargs["start_new_session"] = True
        process = subprocess.Popen([str(app_dir / "run.sh"), "run", str(port)], **kwargs)
        try:
            core.wait_for_health(f"http://127.0.0.1:{port}/health", process, timeout=30)
            _identity(f"http://127.0.0.1:{port}/runtime", process, token)
            raw_path = item_dir / "legacy-results.json"
            result = core.command([
                str(harness_dir / "run.sh"), "run",
                "--base-url", f"http://127.0.0.1:{port}",
                "--requests", str(item["requests"]),
                "--threads", str(item["threads"]),
                "--runs", "1",
                "--out", str(raw_path),
            ], cwd=harness_dir, env=harness_env, timeout=240)
            (item_dir / "harness.log").write_text(result.combined + "\n")
            if result.returncode != 0 or not raw_path.exists():
                raise core.BenchError(
                    f"harness failed ({result.returncode}): {result.combined[-400:]}")
            normalized = core.enrich_smoke_result(
                json.loads(raw_path.read_text()), item, run_id, process.pid)
            normalized["run_kind"] = plan["run_kind"]
            normalized["measurement_valid"] = False
            normalized = _sanitize_legacy_result(normalized)
            if artifact:
                normalized["workload_artifact"] = {
                    "path": artifact["relative_path"],
                    "sha256": artifact["sha256"],
                    "bytecode_target": artifact["bytecode_target"],
                    "builder_runtime": artifact["builder_runtime"],
                }
        except Exception as exc:
            normalized = {
                "schema_version": core.RESULT_SCHEMA_VERSION,
                "run_id": run_id,
                "run_kind": plan["run_kind"],
                "implementation_tier": item["tier"],
                "measurement_valid": False,
                "invalid_reasons": [str(exc)],
                "warnings": [],
                "workload": item["workload"],
                "runtime": item["runtime"],
                "gc": item["gc"],
                "status": "failed",
            }
        finally:
            core.terminate_process(process)
            app_log.close()
        (item_dir / "result.json").write_text(json.dumps(normalized, indent=2) + "\n")
        outputs.append(normalized)
    (run_dir / "result.json").write_text(json.dumps({
        "schema_version": core.RESULT_SCHEMA_VERSION,
        "run_id": run_id,
        "run_kind": plan["run_kind"],
        "measurement_valid": False,
        "results": outputs,
    }, indent=2) + "\n")
    return run_dir


def main() -> int:
    core.build_plan = constrained_build_plan
    core.validate_result = validate_result_document
    core.run_plan = safe_run_plan
    return core.main()


if __name__ == "__main__":
    raise SystemExit(main())
