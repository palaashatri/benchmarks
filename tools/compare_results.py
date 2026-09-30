#!/usr/bin/env python3
"""Statistical comparison helpers for measurement-valid benchmark results."""
from __future__ import annotations

from typing import Any


def metric_bundle(result: dict[str, Any], name: str) -> dict[str, Any]:
    aggregate = (result.get("aggregate") or {}).get(name)
    if isinstance(aggregate, dict):
        return aggregate
    value = (result.get("kpis") or {}).get(name)
    if isinstance(value, (int, float)):
        return {"median": float(value), "mean": float(value), "bootstrap_mean_ci": {}}
    return {}


def compare_results(left: dict[str, Any], right: dict[str, Any]) -> dict[str, Any]:
    if not left.get("measurement_valid") or not right.get("measurement_valid"):
        return {
            "outcome": "invalid",
            "reason": "both results must be measurement_valid",
        }
    if left.get("benchmark") != right.get("benchmark"):
        return {
            "outcome": "invalid",
            "reason": "results describe different workloads",
        }
    if left.get("run_kind") != "benchmark" or right.get("run_kind") != "benchmark":
        return {
            "outcome": "invalid",
            "reason": "only benchmark results may be compared",
        }
    left_metric = metric_bundle(left, "throughput")
    right_metric = metric_bundle(right, "throughput")
    left_throughput = left_metric.get("median")
    right_throughput = right_metric.get("median")
    if not isinstance(left_throughput, (int, float)) or not isinstance(
        right_throughput, (int, float)
    ):
        return {
            "outcome": "invalid",
            "reason": "both results require numeric throughput",
        }
    delta = (
        (right_throughput - left_throughput) / left_throughput * 100
        if left_throughput
        else None
    )
    left_ci = left_metric.get("bootstrap_mean_ci") or {}
    right_ci = right_metric.get("bootstrap_mean_ci") or {}
    left_low, left_high = left_ci.get("low"), left_ci.get("high")
    right_low, right_high = right_ci.get("low"), right_ci.get("high")
    overlapping = (
        isinstance(left_low, (int, float))
        and isinstance(left_high, (int, float))
        and isinstance(right_low, (int, float))
        and isinstance(right_high, (int, float))
        and not (left_high < right_low or right_high < left_low)
    )
    if delta is None or overlapping or abs(delta) < 2:
        outcome = "inconclusive"
    elif delta > 0:
        outcome = "improvement"
    else:
        outcome = "regression"
    return {
        "outcome": outcome,
        "throughput_delta_pct": delta,
        "left_throughput_median": left_throughput,
        "right_throughput_median": right_throughput,
        "confidence_intervals_overlap": overlapping,
    }
