import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import benchctl
import benchctl_report


def metric(median, spread=0.5):
    low, high = median - spread, median + spread
    return {
        "count": 5,
        "mean": median,
        "median": median,
        "standard_deviation": spread,
        "median_absolute_deviation": spread,
        "coefficient_of_variation": spread / median,
        "bootstrap_mean_ci": {"low": low, "high": high, "confidence": 0.95},
        "values": [],
    }


def benchmark_result(throughput, runtime="jdk-21-g1", valid=True, reasons=None):
    return {
        "schema_version": "1.0.0",
        "benchmark": "01-fintech-ledger",
        "run_kind": "benchmark" if valid else "smoke",
        "implementation_tier": "tier-2" if valid else "tier-1",
        "measurement_valid": valid,
        "invalid_reasons": reasons or ([] if valid else ["repetitions are fewer than five"]),
        "warnings": [],
        "runtime": runtime,
        "requested_collector": "g1",
        "java_identity_sha256": "ab" * 32,
        "repetitions": [
            {"status": "passed", "environment": {"cpu_model": "fixture-cpu"}, "load": {"kpis": {"throughput": throughput, "p99_ms": 3.0}}}
        ],
        "aggregate": {
            "throughput": metric(throughput),
            "p99_ms": metric(3.0, spread=0.1),
            "gc_pause_p99_ms": {"count": 0, "median": None, "mean": None, "standard_deviation": None,
                                "median_absolute_deviation": None, "coefficient_of_variation": None,
                                "bootstrap_mean_ci": {"low": None, "high": None, "confidence": 0.95}},
        },
    }


class ReportRenderingTests(unittest.TestCase):
    def test_valid_benchmark_gets_a_positive_banner_and_aggregate_table(self):
        html = benchctl_report.render_report([("run-a", benchmark_result(1000.0))])
        self.assertIn("Measurement-valid benchmark result.", html)
        self.assertIn("Aggregate over repetitions", html)
        self.assertIn("1,000", html)
        self.assertNotIn("e+0", html)

    def test_invalid_result_is_labelled_as_diagnostic_and_lists_reasons(self):
        html = benchctl_report.render_report([("smoke", benchmark_result(900.0, valid=False))])
        self.assertIn("Not a measurement-valid result", html)
        self.assertIn("repetitions are fewer than five", html)
        self.assertNotIn("Measurement-valid benchmark result.", html)

    def test_unknown_metrics_render_as_unknown_never_zero(self):
        html = benchctl_report.render_report([("run-a", benchmark_result(1000.0))])
        gc_row = html.split("GC pause p99")[1].split("</tr>")[0]
        # The sample count (0) is a real count; the measured statistics must be unknown.
        self.assertIn('<td class="num unknown">unknown</td>', gc_row)
        self.assertNotIn('<td class="num">0</td><td class="num">0</td>', gc_row)

    def test_free_text_is_escaped(self):
        hostile = benchmark_result(1.0, valid=False, reasons=["<script>alert(1)</script>"])
        html = benchctl_report.render_report([("x", hostile)])
        self.assertNotIn("<script>alert(1)</script>", html)
        self.assertIn("&lt;script&gt;", html)

    def test_entry_label_uses_run_directory_for_result_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = Path(tmp) / "run-042"
            run.mkdir()
            self.assertEqual("run-042", benchctl_report.entry_label(run / "result.json"))
            self.assertEqual("single", benchctl_report.entry_label(Path(tmp) / "single.json"))


class ComparisonTests(unittest.TestCase):
    def test_invalid_entries_are_not_comparable_and_valid_ones_are_compared(self):
        entries = [
            ("baseline", benchmark_result(1000.0)),
            ("candidate", benchmark_result(1200.0)),
            ("smoke", benchmark_result(9999.0, valid=False)),
        ]
        html = benchctl_report.render_report(entries)
        comparison = html.split("<h2>Comparison</h2>")[1].split("</table>")[0]
        self.assertIn("baseline", comparison)
        self.assertIn("improvement", comparison)
        self.assertIn("not comparable", comparison)
        self.assertNotIn("9999", comparison)

    def test_single_result_has_no_comparison_section(self):
        html = benchctl_report.render_report([("only", benchmark_result(1000.0))])
        self.assertNotIn("<h2>Comparison</h2>", html)


class ReportCommandTests(unittest.TestCase):
    def test_report_command_writes_comparison_to_requested_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            left = Path(tmp) / "left.json"
            right = Path(tmp) / "right.json"
            left.write_text(json.dumps(benchmark_result(1000.0)))
            right.write_text(json.dumps(benchmark_result(1200.0)))
            output = Path(tmp) / "out" / "report.html"
            output.parent.mkdir()
            self.assertEqual(0, benchctl.main(["report", str(left), str(right), "-o", str(output)]))
            text = output.read_text()
            self.assertIn("<h2>Comparison</h2>", text)
            self.assertIn("<title>Benchmark report</title>", text)


if __name__ == "__main__":
    unittest.main()


class RunEnvelopeTests(unittest.TestCase):
    def test_run_directory_is_reported_as_one_section_per_workload(self):
        run = {
            "schema_version": "1.0.0",
            "run_kind": "benchmark",
            "measurement_valid": False,
            "results": [benchmark_result(1000.0), benchmark_result(1100.0, valid=False)],
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run"
            path.mkdir()
            (path / "result.json").write_text(json.dumps(run))
            output = Path(tmp) / "report.html"
            benchctl_report.write_report([path], output, benchctl._load_result_path)
            text = output.read_text()
        self.assertIn("run / 01-fintech-ledger", text)
        self.assertIn("Measurement-valid benchmark result.", text)
        self.assertIn("Not a measurement-valid result", text)


class ChartTests(unittest.TestCase):
    def test_missing_bar_values_are_labelled_unknown_and_never_drawn(self):
        svg = benchctl_report.svg_bar_chart("Peak RSS", ["rep 1", "rep 2", "rep 3"], [100.0, None, 120.0], "MiB")
        self.assertEqual(2, svg.count("<rect"))
        self.assertIn(">unknown</text>", svg)

    def test_no_measured_values_produces_text_not_an_empty_chart(self):
        svg = benchctl_report.svg_bar_chart("GC pause", ["rep 1"], [None], "ms")
        self.assertNotIn("<svg", svg)
        self.assertIn("no measured values", svg)

    def test_line_breaks_at_missing_points_instead_of_dropping_to_zero(self):
        svg = benchctl_report.svg_line_chart("p50", [("rep 1", [5.0, 6.0, None, 7.0, 8.0])], "ms")
        # Two runs of two points each: the gap splits the line into two polylines and no
        # point is plotted at zero.
        self.assertEqual(2, svg.count("<polyline"))
        self.assertEqual(0, svg.count("<circle"))

    def test_warm_up_marker_is_drawn_at_the_warm_up_boundary(self):
        svg = benchctl_report.svg_line_chart("throughput", [("rep 1", [20.0] * 6)], "req/s", marker_x=2)
        self.assertIn("warm-up ends", svg)

    def test_repetition_charts_come_from_the_recorded_series(self):
        document = benchmark_result(1000.0)
        document["repetitions"][0]["load"]["series"] = {
            "completed": [20, 20, 20, 20], "p50_ms": [4.0, 4.1, 3.9, 4.0]
        }
        document["repetitions"][0]["load"]["phases"] = {"warmup_s": 1, "measure_s": 2}
        charts = benchctl_report.repetition_charts(document)
        self.assertTrue(any("Delivered throughput over time" in chart for chart in charts))
        self.assertTrue(any("p50 latency over time" in chart for chart in charts))

    def test_comparison_chart_refuses_to_plot_invalid_results(self):
        svg = benchctl_report.comparison_chart([
            ("baseline", benchmark_result(1000.0)),
            ("smoke", benchmark_result(9999.0, valid=False)),
        ])
        self.assertIn("smoke (not comparable)", svg)
        self.assertNotIn("9,999", svg)

    def test_chart_titles_are_escaped(self):
        svg = benchctl_report.svg_bar_chart("<b>x</b>", ["a"], [1.0], "ms")
        self.assertNotIn("<b>x</b>", svg)
