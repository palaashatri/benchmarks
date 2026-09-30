#!/usr/bin/env python3
"""Execute Tier 2 benchmark plans through the ledger experiment runner."""
from __future__ import annotations

import json
import os
import shutil
import sys
import time
from pathlib import Path

import benchctl as core


def run_benchmark_plan(plan: dict, experiment_path: Path) -> Path:
    experiment = core.load_document(experiment_path)
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + os.urandom(4).hex()
    run_dir = core.ROOT / "results" / run_id
    run_dir.mkdir(parents=True)
    shutil.copy2(experiment_path, run_dir / "experiment.yaml")
    (run_dir / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    (run_dir / "environment.json").write_text(
        json.dumps(core.environment_fingerprint(), indent=2) + "\n"
    )

    outputs = []
    for index, item in enumerate(plan["items"], 1):
        if item["workload"] != "01-fintech-ledger" or item.get("tier") != "tier-2":
            raise core.BenchError(
                "benchmark execution is only enabled for the Tier 2 01-fintech-ledger workload"
            )
        item_dir = run_dir / f"{index:03d}-{item['workload']}-{item['runtime']}-{item['gc']}"
        completed = core.command([
            sys.executable,
            str(core.ROOT / "tools" / "run_ledger_experiment.py"),
            "--java-home", item["java_home"],
            "--gc", item["gc"],
            "--target-rate", str(experiment.get("target_rate", 80)),
            "--warmup-seconds", str(experiment.get("warmup_seconds", 8)),
            "--measure-seconds", str(experiment.get("measure_seconds", 12)),
            "--threads", str(item["threads"]),
            "--repetitions", str(item["repetitions"]),
            "--heap-mb", str(experiment.get("heap_mb", 512)),
            "--run-kind", "benchmark",
            "--out", str(item_dir),
        ], cwd=core.ROOT, timeout=max(
            600,
            item["repetitions"] * (
                int(experiment.get("warmup_seconds", 8))
                + int(experiment.get("measure_seconds", 12))
                + 180
            ),
        ))
        (run_dir / f"{item_dir.name}-controller.log").write_text(completed.combined + "\n")
        result_path = item_dir / "result.json"
        if not result_path.exists():
            raise core.BenchError(
                f"ledger experiment produced no result.json ({completed.returncode}): "
                f"{completed.combined[-500:]}"
            )
        outputs.append(json.loads(result_path.read_text()))

    (run_dir / "result.json").write_text(json.dumps({
        "schema_version": core.RESULT_SCHEMA_VERSION,
        "run_kind": plan["run_kind"],
        "measurement_valid": all(item.get("measurement_valid") for item in outputs),
        "results": outputs,
    }, indent=2) + "\n")
    return run_dir
