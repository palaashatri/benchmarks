#!/usr/bin/env python3
"""Statistics shared by the Tier 2 runner, the comparison tools and the report.

Every function returns None (or an empty result) when the data cannot support the
statistic. Nothing is replaced by zero.
"""
from __future__ import annotations

import random
import statistics
from typing import Any, Sequence

# Iglewicz and Hoaglin's recommended cut-off for the modified z-score.
MODIFIED_Z_THRESHOLD = 3.5
# Consistency constant that makes the MAD comparable to a standard deviation.
MAD_SCALE = 0.6745

# Warm-up detector: throughput is stable when `STABLE_WINDOW` consecutive one-second
# buckets vary by no more than `STABLE_MAX_CV` (coefficient of variation).
STABLE_WINDOW = 5
STABLE_MAX_CV = 0.05


def median_absolute_deviation(values: Sequence[float]) -> float | None:
    if not values:
        return None
    center = statistics.median(values)
    return statistics.median(abs(value - center) for value in values)


def coefficient_of_variation(values: Sequence[float]) -> float | None:
    if len(values) < 2:
        return None
    mean = statistics.fmean(values)
    if mean == 0:
        return None
    return statistics.stdev(values) / mean


def bootstrap_ci(values: Sequence[float], *, seed: int, samples: int = 10_000) -> dict[str, float | None]:
    """Percentile bootstrap confidence interval of the mean (95 %)."""
    if len(values) < 2:
        return {"low": None, "high": None, "confidence": 0.95}
    generator = random.Random(seed)
    count = len(values)
    means = []
    for _ in range(samples):
        draw = [values[generator.randrange(count)] for _ in range(count)]
        means.append(statistics.fmean(draw))
    means.sort()
    low = means[int(.025 * (samples - 1))]
    high = means[int(.975 * (samples - 1))]
    return {"low": low, "high": high, "confidence": 0.95}


def modified_z_outliers(values: Sequence[float], threshold: float = MODIFIED_Z_THRESHOLD) -> list[dict[str, Any]]:
    """Points whose modified z-score exceeds `threshold`.

    Returns an empty list when the MAD is zero: the method cannot separate points in
    that case, and reporting every off-centre value as an outlier would be misleading.
    """
    mad = median_absolute_deviation(values)
    if not values or mad is None or mad == 0:
        return []
    center = statistics.median(values)
    flagged = []
    for index, value in enumerate(values):
        score = MAD_SCALE * (value - center) / mad
        if abs(score) > threshold:
            flagged.append({"index": index, "value": value, "modified_z": score, "method": "modified_z"})
    return flagged


def iqr_outliers(values: Sequence[float], factor: float = 1.5) -> list[dict[str, Any]]:
    """Points outside the Tukey fences. Needs at least four values for stable quartiles."""
    if len(values) < 4:
        return []
    quartiles = statistics.quantiles(values, n=4, method="inclusive")
    lower_quartile, upper_quartile = quartiles[0], quartiles[2]
    spread = upper_quartile - lower_quartile
    low_fence = lower_quartile - factor * spread
    high_fence = upper_quartile + factor * spread
    return [
        {"index": index, "value": value, "method": "iqr"}
        for index, value in enumerate(values)
        if value < low_fence or value > high_fence
    ]


def describe_outliers(name: str, values: Sequence[float], repetitions: Sequence[int] | None = None) -> dict[str, Any]:
    """Explicit outlier report for one metric. Outliers are reported, never removed."""
    labels = list(repetitions) if repetitions is not None else list(range(1, len(values) + 1))
    findings = []
    for method in (modified_z_outliers, iqr_outliers):
        for finding in method(values):
            findings.append({
                "repetition": labels[finding["index"]],
                "value": finding["value"],
                "method": finding["method"],
                **({"modified_z": finding["modified_z"]} if "modified_z" in finding else {}),
            })
    return {"metric": name, "count": len(values), "outliers": findings}


def find_stable_window(
    series: Sequence[float | None],
    *,
    window: int = STABLE_WINDOW,
    max_cv: float = STABLE_MAX_CV,
) -> int | None:
    """Index of the first bucket that starts a stable run, or None.

    A run is stable when `window` consecutive buckets have a coefficient of variation
    no larger than `max_cv`. A bucket with no data (None) or a zero is never stable, so an
    idle or empty start cannot pass as warmed up.
    """
    if window < 2 or len(series) < window:
        return None
    for start in range(len(series) - window + 1):
        chunk = series[start:start + window]
        if any(value is None or value <= 0 for value in chunk):
            continue
        cv = coefficient_of_variation(chunk)
        if cv is not None and cv <= max_cv:
            return start
    return None


def warmup_stability(
    series: Sequence[float | None],
    warmup_seconds: int,
    *,
    window: int = STABLE_WINDOW,
    max_cv: float = STABLE_MAX_CV,
) -> dict[str, Any]:
    """Whether throughput settled before the measurement phase began.

    `series` holds completions per one-second bucket, starting at the first warm-up
    request. The stable bucket must start inside the warm-up phase: a window that only
    begins once measurement has started cannot show that warm-up was sufficient.
    """
    start = find_stable_window(series, window=window, max_cv=max_cv)
    stable_before_measurement = start is not None and start + window <= warmup_seconds
    return {
        "method": "consecutive_window_cv",
        "window_seconds": window,
        "max_cv": max_cv,
        "stable_at_second": start,
        "stable_before_measurement": stable_before_measurement,
        "warmup_seconds": warmup_seconds,
        "bucket_count": len(series),
    }
