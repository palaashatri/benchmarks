#!/usr/bin/env python3
"""Runtime-safe entry point layered over the dependency-free controller."""
from __future__ import annotations
import json, os, shutil, subprocess, time, urllib.error, urllib.request
from pathlib import Path
import benchctl as core


def _identity(url: str, process: subprocess.Popen[str], token: str) -> None:
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            value = json.load(response)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return
        raise
    if value.get("run_token") != token or int(value.get("pid", -1)) != process.pid:
        raise core.BenchError("runtime identity mismatch; unrelated process detected")


def safe_run_plan(plan: dict, experiment_path: Path) -> Path:
    if plan["run_kind"] == "benchmark":
        raise core.BenchError("benchmark execution is blocked until a workload reaches Tier 2")
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + os.urandom(4).hex()
    run_dir = core.ROOT / "results" / run_id
    run_dir.mkdir(parents=True)
    shutil.copy2(experiment_path, run_dir / "experiment.yaml")
    (run_dir / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    (run_dir / "environment.json").write_text(json.dumps(core.environment_fingerprint(), indent=2) + "\n")
    outputs = []
    for index, item in enumerate(plan["items"], 1):
        item_dir = run_dir / f"{index:03d}-{item['workload']}-{item['runtime']}-{item['gc']}"
        item_dir.mkdir()
        app_dir = core.ROOT / item["workload_path"] / "app"
        harness_dir = core.ROOT / item["workload_path"] / "harness"
        env = os.environ.copy()
        env["JAVA_HOME"] = item["java_home"]
        env["PATH"] = str(Path(item["java_home"]) / "bin") + os.pathsep + env.get("PATH", "")
        token = os.urandom(24).hex()
        env["BENCH_RUN_TOKEN"] = token
        selected_flags = " ".join(item["gc_flags"])
        env["JAVA_TOOL_OPTIONS"] = " ".join(v for v in (env.get("JAVA_TOOL_OPTIONS", "").strip(), selected_flags) if v)
        port = core.free_port()
        app_log = (item_dir / "app.log").open("w")
        kwargs = {"cwd": app_dir, "env": env, "stdout": app_log, "stderr": subprocess.STDOUT, "text": True}
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
                "--runs", "1", "--out", str(raw_path),
            ], cwd=harness_dir, env=env, timeout=240)
            (item_dir / "harness.log").write_text(result.combined + "\n")
            if result.returncode != 0 or not raw_path.exists():
                raise core.BenchError(f"harness failed ({result.returncode}): {result.combined[-400:]}")
            normalized = core.enrich_smoke_result(json.loads(raw_path.read_text()), item, run_id, process.pid)
            normalized["run_kind"] = plan["run_kind"]
            normalized["measurement_valid"] = False
        except Exception as exc:
            normalized = {
                "schema_version": core.RESULT_SCHEMA_VERSION, "run_id": run_id,
                "run_kind": plan["run_kind"], "implementation_tier": item["tier"],
                "measurement_valid": False, "invalid_reasons": [str(exc)], "warnings": [],
                "workload": item["workload"], "runtime": item["runtime"], "gc": item["gc"],
                "status": "failed",
            }
        finally:
            core.terminate_process(process)
            app_log.close()
        (item_dir / "result.json").write_text(json.dumps(normalized, indent=2) + "\n")
        outputs.append(normalized)
    (run_dir / "result.json").write_text(json.dumps({
        "schema_version": core.RESULT_SCHEMA_VERSION, "run_id": run_id,
        "run_kind": plan["run_kind"], "measurement_valid": False, "results": outputs,
    }, indent=2) + "\n")
    return run_dir


core.run_plan = safe_run_plan
raise SystemExit(core.main())
