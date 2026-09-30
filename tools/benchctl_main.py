#!/usr/bin/env python3
"""Wrapper that enables Tier 2 execution and statistical compare."""
from __future__ import annotations

import json
from pathlib import Path

import benchctl as core
import benchctl_entry as entry
import benchmark_execute
import compare_results


_original_run = entry.safe_run_plan


def safe_run_plan(plan: dict, experiment_path: Path):
    if plan.get("run_kind") == "benchmark":
        return benchmark_execute.run_benchmark_plan(plan, experiment_path)
    return _original_run(plan, experiment_path)


def cmd_compare(args):
    document = compare_results.compare_results(
        core._load_result_path(Path(args.left)),
        core._load_result_path(Path(args.right)),
    )
    print(json.dumps(document, indent=2))
    return 0 if document["outcome"] != "invalid" else 2


def main() -> int:
    core.build_plan = entry.constrained_build_plan
    core.validate_result = entry.validate_result_document
    core.run_plan = safe_run_plan
    core.cmd_compare = cmd_compare
    return core.main()


if __name__ == "__main__":
    raise SystemExit(main())
