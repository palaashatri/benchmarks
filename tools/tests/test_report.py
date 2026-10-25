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
