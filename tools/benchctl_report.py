#!/usr/bin/env python3
"""Readable HTML reports for benchctl result envelopes.

A report never turns an invalid or smoke result into a performance claim. Their
numbers are shown as diagnostics under an explicit banner, and comparisons are
drawn only between measurement-valid benchmark results. Unknown values render as
"unknown", never as zero.
"""
from __future__ import annotations

import html
import json
from pathlib import Path
from typing import Any, Callable, Sequence

from compare_results import compare_results

UNKNOWN = "unknown"

# Aggregate keys written by run_tier2_experiment.aggregate(), in display order.
AGGREGATE_METRICS = (
    ("throughput", "Throughput", "req/s"),
    ("p50_ms", "p50 latency", "ms"),
    ("p99_ms", "p99 latency", "ms"),
    ("p999_ms", "p99.9 latency", "ms"),
    ("gc_pause_p99_ms", "GC pause p99", "ms"),
    ("rss_mb_max", "Peak RSS", "MiB"),
    ("process_cpu_pct_one_core_mean", "Process CPU (one-core mean)", "%"),
    ("native_memory_committed_mb", "Committed native memory", "MiB"),
    ("code_cache_used_mb", "Code cache used", "MiB"),
    ("compiled_methods", "Compiled methods", "count"),
    ("compiler_time_seconds", "Compiler time", "s"),
    ("jfr_allocation_sample_rate_mb_s", "Allocation rate (JFR)", "MiB/s"),
)

CSS = """
body { font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 1100px; padding: 16px; color: #1d1d1f; }
h1 { font-size: 1.6em; } h2 { font-size: 1.25em; margin-top: 2em; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
table { border-collapse: collapse; width: 100%; margin: 8px 0 16px; font-size: 14px; }
th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid #e3e3e3; vertical-align: top; }
th { font-weight: 600; background: #f5f5f7; }
td.num { text-align: right; font-variant-numeric: tabular-nums; }
td.unknown, .unknown { color: #6e6e73; font-style: italic; }
.banner { padding: 10px 12px; border-radius: 6px; margin: 12px 0; }
.banner.valid { background: #e8f5e9; border-left: 4px solid #2e7d32; }
.banner.diagnostic { background: #fff4e5; border-left: 4px solid #b26a00; }
.banner ul { margin: 6px 0 0 18px; padding: 0; }
figure { margin: 12px 0; } figure svg { max-width: 100%; height: auto; }
code { font-size: 13px; word-break: break-all; }
"""


def dig(document: Any, *keys: str) -> Any:
    """Return the nested value at `keys`, or None when any level is missing."""
    value = document
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def format_number(value: Any) -> str:
    if value is None or isinstance(value, bool):
        return UNKNOWN if value is None else str(value).lower()
    if isinstance(value, int):
        return f"{value:,}"
    if isinstance(value, float):
        if abs(value) >= 1000:
            return f"{value:,.0f}"
        if abs(value) >= 10:
            return f"{value:.1f}"
        # Keep small values in plain notation; `.3g` would print 999.5 as 1e+03.
        return f"{value:.3g}"
    return html.escape(str(value))


def cell(value: Any, *, numeric: bool = False) -> str:
    text = format_number(value)
    css = []
    if numeric:
        css.append("num")
    if value is None:
        css.append("unknown")
    class_attr = f' class="{" ".join(css)}"' if css else ""
    return f"<td{class_attr}>{text}</td>"


def text_cell(value: Any) -> str:
    if value is None:
        return f'<td class="unknown">{UNKNOWN}</td>'
    return f"<td>{html.escape(str(value))}</td>"


def interval_text(metric: dict[str, Any]) -> str:
    interval = metric.get("bootstrap_mean_ci") or {}
    low, high = interval.get("low"), interval.get("high")
    if low is None or high is None:
        return UNKNOWN
    return f"{format_number(low)} to {format_number(high)}"


def aggregate_table(aggregate: dict[str, Any]) -> str:
    header = (
        "<tr><th>Metric</th><th>Unit</th><th>n</th><th>Median</th><th>Mean</th>"
        "<th>Std. dev.</th><th>MAD</th><th>CV</th><th>95% CI of mean</th></tr>"
    )
    rows = []
    for key, label, unit in AGGREGATE_METRICS:
        metric = aggregate.get(key)
        if not isinstance(metric, dict):
            continue
        rows.append(
            "<tr>"
            f"<td>{html.escape(label)}</td><td>{html.escape(unit)}</td>"
            f"{cell(metric.get('count'), numeric=True)}"
            f"{cell(metric.get('median'), numeric=True)}"
            f"{cell(metric.get('mean'), numeric=True)}"
            f"{cell(metric.get('standard_deviation'), numeric=True)}"
            f"{cell(metric.get('median_absolute_deviation'), numeric=True)}"
            f"{cell(metric.get('coefficient_of_variation'), numeric=True)}"
            f"<td>{interval_text(metric)}</td>"
            "</tr>"
        )
    if not rows:
        return "<p class='unknown'>No aggregate metrics were recorded.</p>"
    return f"<table>{header}{''.join(rows)}</table>"


def kpi_table(kpis: dict[str, Any]) -> str:
    rows = [
        f"<tr><td><code>{html.escape(str(key))}</code></td>{cell(value, numeric=True)}</tr>"
        for key, value in kpis.items()
        if not isinstance(value, (dict, list))
    ]
    if not rows:
        return "<p class='unknown'>No single-value KPIs were recorded.</p>"
    return f"<table><tr><th>KPI</th><th>Value</th></tr>{''.join(rows)}</table>"


def repetition_table(repetitions: Sequence[dict[str, Any]]) -> str:
    rows = []
    for index, repetition in enumerate(repetitions, start=1):
        rows.append(
            "<tr>"
            f"<td class='num'>{index}</td>"
            f"{text_cell(repetition.get('status'))}"
            f"{cell(dig(repetition, 'load', 'kpis', 'throughput'), numeric=True)}"
            f"{cell(dig(repetition, 'load', 'kpis', 'p99_ms'), numeric=True)}"
            f"{cell(dig(repetition, 'telemetry', 'gc', 'p99_ms'), numeric=True)}"
            "</tr>"
        )
    return (
        "<table><tr><th>Repetition</th><th>Status</th><th>Throughput (req/s)</th>"
        "<th>p99 (ms)</th><th>GC pause p99 (ms)</th></tr>"
        f"{''.join(rows)}</table>"
    )


def environment_table(document: dict[str, Any]) -> str:
    """Show the first repetition's environment fingerprint, if one was recorded."""
    repetitions = document.get("repetitions") or []
    environment = dig(repetitions[0], "environment") if repetitions else None
    if not isinstance(environment, dict):
        return "<p class='unknown'>No environment fingerprint was recorded.</p>"
    rows = []
    for key in sorted(environment):
        value = environment[key]
        shown = json.dumps(value, sort_keys=True) if isinstance(value, (dict, list)) else value
        rows.append(f"<tr><td><code>{html.escape(key)}</code></td>{text_cell(shown)}</tr>")
    return f"<table><tr><th>Field</th><th>Value</th></tr>{''.join(rows)}</table>"


def banner(document: dict[str, Any]) -> str:
    valid = document.get("measurement_valid") is True and document.get("run_kind") == "benchmark"
    reasons = document.get("invalid_reasons") or []
    warnings = document.get("warnings") or []
    if valid:
        headline = "Measurement-valid benchmark result."
        css = "valid"
    else:
        headline = (
            "Not a measurement-valid result. The numbers below are diagnostics, "
            "not performance claims."
        )
        css = "diagnostic"
    items = [f"<li>{html.escape(str(reason))}</li>" for reason in reasons]
    items += [f"<li>warning: {html.escape(str(warning))}</li>" for warning in warnings]
    listing = f"<ul>{''.join(items)}</ul>" if items else ""
    return (
        f"<div class='banner {css}'><strong>{html.escape(headline)}</strong><br>"
        f"run_kind: <code>{html.escape(str(document.get('run_kind')))}</code> · "
        f"tier: <code>{html.escape(str(document.get('implementation_tier')))}</code> · "
        f"measurement_valid: <code>{str(document.get('measurement_valid')).lower()}</code>"
        f"{listing}</div>"
    )


def identity_table(document: dict[str, Any]) -> str:
    fields = (
        ("Benchmark", document.get("benchmark") or document.get("workload")),
        ("Runtime", document.get("runtime")),
        ("Requested collector", document.get("requested_collector") or document.get("gc")),
        ("Java identity (sha256)", document.get("java_identity_sha256")),
        ("Repetitions", len(document["repetitions"]) if isinstance(document.get("repetitions"), list) else None),
    )
    rows = "".join(f"<tr><th>{label}</th>{text_cell(value)}</tr>" for label, value in fields)
    return f"<table>{rows}</table>"


CHART_WIDTH = 640
CHART_HEIGHT = 240
MARGIN_LEFT = 64
MARGIN_RIGHT = 16
MARGIN_TOP = 28
MARGIN_BOTTOM = 40
SERIES_COLOURS = ("#2f6fbf", "#c4572b", "#2e7d32", "#7b1fa2", "#00838f", "#8d6e00")


def _plot_area() -> tuple[int, int, int, int]:
    return MARGIN_LEFT, MARGIN_TOP, CHART_WIDTH - MARGIN_LEFT - MARGIN_RIGHT, CHART_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM


def _scale_top(values: list[float]) -> float:
    top = max(values) if values else 1.0
    return top * 1.1 if top > 0 else 1.0


def svg_bar_chart(
    title: str,
    labels: list[str],
    values: list[float | None],
    unit: str,
    lows: list[float | None] | None = None,
    highs: list[float | None] | None = None,
) -> str:
    """Vertical bars with optional confidence whiskers. A None value is drawn as the word
    'unknown' at the baseline, never as a bar, so a missing measurement cannot look like zero."""
    left, top, width, height = _plot_area()
    measured = [value for value in values if value is not None]
    extents = measured + [bound for bound in [*(lows or []), *(highs or [])] if bound is not None]
    if not measured:
        return f"<p class='unknown'>{html.escape(title)}: no measured values.</p>"
    ceiling = _scale_top(extents)
    slot = width / max(len(values), 1)
    bar_width = slot * .6
    parts = [
        f'<svg viewBox="0 0 {CHART_WIDTH} {CHART_HEIGHT}" width="100%" role="img" aria-label="{html.escape(title)}">',
        f'<text x="{left}" y="16" font-size="13" font-weight="600">{html.escape(title)} ({html.escape(unit)})</text>',
        f'<line x1="{left}" y1="{top + height}" x2="{left + width}" y2="{top + height}" stroke="#888"/>',
        f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top + height}" stroke="#888"/>',
        f'<text x="{left - 6}" y="{top + 4}" font-size="11" text-anchor="end">{format_number(ceiling)}</text>',
    ]
    for index, (label, value) in enumerate(zip(labels, values)):
        centre = left + slot * index + slot / 2
        x = centre - bar_width / 2
        if value is None:
            parts.append(f'<text x="{centre}" y="{top + height - 6}" font-size="11" text-anchor="middle" fill="#6e6e73">unknown</text>')
        else:
            bar_height = height * value / ceiling
            parts.append(f'<rect x="{x:.1f}" y="{top + height - bar_height:.1f}" width="{bar_width:.1f}" height="{bar_height:.1f}" fill="{SERIES_COLOURS[0]}"/>')
            parts.append(f'<text x="{centre:.1f}" y="{top + height - bar_height - 4:.1f}" font-size="11" text-anchor="middle">{format_number(value)}</text>')
            low = lows[index] if lows else None
            high = highs[index] if highs else None
            if low is not None and high is not None:
                def y_of(item: float) -> float:
                    return top + height - height * item / ceiling
                parts.append(f'<line x1="{centre:.1f}" y1="{y_of(low):.1f}" x2="{centre:.1f}" y2="{y_of(high):.1f}" stroke="#222" stroke-width="1.5"/>')
        parts.append(f'<text x="{centre:.1f}" y="{top + height + 16}" font-size="11" text-anchor="middle">{html.escape(label)}</text>')
    parts.append("</svg>")
    return "".join(parts)


def svg_line_chart(
    title: str,
    series: list[tuple[str, list[float | None]]],
    unit: str,
    x_label: str = "second",
    marker_x: int | None = None,
) -> str:
    """One polyline per series. A None point breaks the line rather than dropping to zero.
    `marker_x` draws a dashed line at the end of warm-up."""
    left, top, width, height = _plot_area()
    points = [value for _, values in series for value in values if value is not None]
    if not points:
        return f"<p class='unknown'>{html.escape(title)}: no measured values.</p>"
    ceiling = _scale_top(points)
    length = max((len(values) for _, values in series), default=1)
    x_step = width / max(length - 1, 1)

    def x_of(index: int) -> float:
        return left + index * x_step

    def y_of(value: float) -> float:
        return top + height - height * value / ceiling

    parts = [
        f'<svg viewBox="0 0 {CHART_WIDTH} {CHART_HEIGHT}" width="100%" role="img" aria-label="{html.escape(title)}">',
        f'<text x="{left}" y="16" font-size="13" font-weight="600">{html.escape(title)} ({html.escape(unit)})</text>',
        f'<line x1="{left}" y1="{top + height}" x2="{left + width}" y2="{top + height}" stroke="#888"/>',
        f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top + height}" stroke="#888"/>',
        f'<text x="{left - 6}" y="{top + 4}" font-size="11" text-anchor="end">{format_number(ceiling)}</text>',
        f'<text x="{left + width}" y="{top + height + 30}" font-size="11" text-anchor="end">{html.escape(x_label)}</text>',
    ]
    if marker_x is not None and 0 <= marker_x < length:
        parts.append(f'<line x1="{x_of(marker_x):.1f}" y1="{top}" x2="{x_of(marker_x):.1f}" y2="{top + height}" stroke="#888" stroke-dasharray="4 3"/>')
        parts.append(f'<text x="{x_of(marker_x) + 4:.1f}" y="{top + 12}" font-size="11" fill="#555">warm-up ends</text>')
    for index, (name, values) in enumerate(series):
        colour = SERIES_COLOURS[index % len(SERIES_COLOURS)]
        run: list[str] = []
        for position, value in enumerate(values):
            if value is None:
                _flush_run(parts, run, colour)
                run = []
            else:
                run.append(f"{x_of(position):.1f},{y_of(value):.1f}")
        _flush_run(parts, run, colour)
        parts.append(f'<text x="{left + 8 + index * 110}" y="{CHART_HEIGHT - 6}" font-size="11" fill="{colour}">{html.escape(name)}</text>')
    parts.append("</svg>")
    return "".join(parts)


def _flush_run(parts: list[str], run: list[str], colour: str) -> None:
    if len(run) == 1:
        x, y = run[0].split(",")
        parts.append(f'<circle cx="{x}" cy="{y}" r="2.5" fill="{colour}"/>')
    elif run:
        parts.append(f'<polyline points="{" ".join(run)}" fill="none" stroke="{colour}" stroke-width="1.8"/>')


def repetition_charts(document: dict[str, Any]) -> list[str]:
    """Charts drawn from one result's repetitions: delivered throughput and p50 latency over
    time, and per-repetition bars for throughput, GC pause and peak RSS."""
    repetitions = [item for item in document.get("repetitions") or [] if isinstance(item, dict)]
    if not repetitions:
        return []
    labels = [f"rep {item.get('repetition', index + 1)}" for index, item in enumerate(repetitions)]
    charts: list[str] = []
    first_load = dig(repetitions[0], "load") or {}
    warmup = dig(first_load, "phases", "warmup_s")
    completed = [(label, dig(item, "load", "series", "completed")) for label, item in zip(labels, repetitions)]
    if all(values is not None for _, values in completed):
        charts.append(svg_line_chart(
            "Delivered throughput over time", completed, "req/s",
            marker_x=warmup if isinstance(warmup, int) else None,
        ))
    p50 = [(label, dig(item, "load", "series", "p50_ms")) for label, item in zip(labels, repetitions)]
    if all(values is not None for _, values in p50):
        charts.append(svg_line_chart(
            "p50 latency over time (by intended send second)", p50, "ms",
            marker_x=warmup if isinstance(warmup, int) else None,
        ))
    charts.append(svg_bar_chart(
        "Throughput per repetition", labels,
        [dig(item, "load", "kpis", "throughput") for item in repetitions], "req/s",
    ))
    charts.append(svg_bar_chart(
        "GC pause p99 per repetition", labels,
        [dig(item, "telemetry", "gc", "p99_ms") for item in repetitions], "ms",
    ))
    charts.append(svg_bar_chart(
        "Peak RSS per repetition", labels,
        [_mebibytes(dig(item, "telemetry", "process", "rss_bytes_max")) for item in repetitions], "MiB",
    ))
    return charts


def _mebibytes(value: Any) -> float | None:
    return value / (1024 * 1024) if isinstance(value, (int, float)) else None


def comparison_chart(entries: Sequence[tuple[str, dict[str, Any]]]) -> str:
    """Throughput median with its 95 % interval, for measurement-valid results only."""
    labels, values, lows, highs = [], [], [], []
    for label, document in entries:
        metric = dig(document, "aggregate", "throughput") or {}
        valid = document.get("measurement_valid") is True
        labels.append(label if valid else f"{label} (not comparable)")
        values.append(metric.get("median") if valid else None)
        interval = metric.get("bootstrap_mean_ci") or {}
        lows.append(interval.get("low") if valid else None)
        highs.append(interval.get("high") if valid else None)
    return svg_bar_chart("Throughput median across runs", labels, values, "req/s", lows, highs)

def document_section(label: str, document: dict[str, Any]) -> str:
    parts = [
        f"<h2>{html.escape(label)}</h2>",
        banner(document),
        identity_table(document),
    ]
    aggregate = document.get("aggregate")
    if isinstance(aggregate, dict):
        parts += ["<h3>Aggregate over repetitions</h3>", aggregate_table(aggregate)]
    elif isinstance(document.get("kpis"), dict):
        parts += ["<h3>KPIs</h3>", kpi_table(document["kpis"])]
    if isinstance(document.get("repetitions"), list) and document["repetitions"]:
        parts += ["<h3>Repetitions</h3>", repetition_table(document["repetitions"])]
        parts += [f"<figure>{chart}</figure>" for chart in repetition_charts(document)]
    parts += ["<h3>Environment</h3>", environment_table(document)]
    return "\n".join(parts)


def throughput_summary(document: dict[str, Any]) -> tuple[Any, Any]:
    metric = dig(document, "aggregate", "throughput")
    if isinstance(metric, dict):
        return metric.get("median"), interval_text(metric)
    return dig(document, "kpis", "throughput"), UNKNOWN


def comparison_table(entries: Sequence[tuple[str, dict[str, Any]]]) -> str:
    """Compare every measurement-valid benchmark against the first valid one."""
    baseline = next(((label, doc) for label, doc in entries if doc.get("measurement_valid") is True), None)
    header = (
        "<tr><th>Run</th><th>Runtime</th><th>Collector</th><th>Valid</th>"
        "<th>Throughput median (req/s)</th><th>Throughput 95% CI</th>"
        "<th>Outcome vs. baseline</th></tr>"
    )
    rows = []
    for label, document in entries:
        median, interval = throughput_summary(document)
        valid = document.get("measurement_valid") is True
        if not valid:
            outcome = "not comparable: " + "; ".join(document.get("invalid_reasons") or [UNKNOWN])
        elif baseline is None:
            outcome = UNKNOWN
        elif baseline[1] is document:
            outcome = "baseline"
        else:
            outcome = compare_results(baseline[1], document).get("outcome", UNKNOWN)
        rows.append(
            "<tr>"
            f"<td>{html.escape(label)}</td>"
            f"{text_cell(document.get('runtime'))}"
            f"{text_cell(document.get('requested_collector') or document.get('gc'))}"
            f"<td>{'yes' if valid else 'no'}</td>"
            f"{cell(median if valid else None, numeric=True)}"
            f"<td>{interval if valid else UNKNOWN}</td>"
            f"<td>{html.escape(outcome)}</td>"
            "</tr>"
        )
    return f"<h2>Comparison</h2><table>{header}{''.join(rows)}</table>"


def render_report(entries: Sequence[tuple[str, dict[str, Any]]]) -> str:
    """Render one HTML document covering the given (label, result) pairs."""
    title = "Benchmark report" if len(entries) != 1 else f"Benchmark report: {entries[0][0]}"
    sections = [document_section(label, document) for label, document in entries]
    if len(entries) > 1:
        sections.insert(0, comparison_table(entries))
        sections.insert(1, f"<figure>{comparison_chart(entries)}</figure>")
    return (
        "<!doctype html><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width, initial-scale=1'>"
        f"<title>{html.escape(title)}</title><style>{CSS}</style>"
        f"<h1>{html.escape(title)}</h1>"
        "<p>Smoke output verifies behaviour only. Only measurement-valid benchmark "
        "results may be compared or quoted as performance.</p>"
        + "\n".join(sections)
        + "\n"
    )


def entry_label(path: Path) -> str:
    """Name a result by its run directory, or by its file name without `.json`."""
    if path.is_dir() or path.name == "result.json":
        return path.parent.name if path.name == "result.json" else path.name
    return path.stem


def write_report(
    paths: Sequence[Path],
    output: Path,
    load: Callable[[Path], dict[str, Any]],
) -> Path:
    entries = [(entry_label(path), load(path)) for path in paths]
    output.write_text(render_report(entries))
    return output
