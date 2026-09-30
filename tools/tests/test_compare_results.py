import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import compare_results


class CompareResultsTests(unittest.TestCase):
    def test_compare_requires_valid_benchmark_results(self):
        invalid = compare_results.compare_results(
            {
                "benchmark": "01-fintech-ledger",
                "run_kind": "benchmark",
                "measurement_valid": False,
                "kpis": {"throughput": 100},
            },
            {
                "benchmark": "01-fintech-ledger",
                "run_kind": "benchmark",
                "measurement_valid": True,
                "kpis": {"throughput": 110},
            },
        )
        self.assertEqual("invalid", invalid["outcome"])

    def test_compare_uses_confidence_interval_overlap(self):
        left = {
            "benchmark": "01-fintech-ledger",
            "run_kind": "benchmark",
            "measurement_valid": True,
            "aggregate": {
                "throughput": {
                    "median": 100.0,
                    "bootstrap_mean_ci": {"low": 95.0, "high": 105.0},
                }
            },
        }
        overlapping = {
            "benchmark": "01-fintech-ledger",
            "run_kind": "benchmark",
            "measurement_valid": True,
            "aggregate": {
                "throughput": {
                    "median": 103.0,
                    "bootstrap_mean_ci": {"low": 98.0, "high": 108.0},
                }
            },
        }
        separated = {
            "benchmark": "01-fintech-ledger",
            "run_kind": "benchmark",
            "measurement_valid": True,
            "aggregate": {
                "throughput": {
                    "median": 130.0,
                    "bootstrap_mean_ci": {"low": 120.0, "high": 140.0},
                }
            },
        }
        self.assertEqual("inconclusive", compare_results.compare_results(left, overlapping)["outcome"])
        self.assertEqual("improvement", compare_results.compare_results(left, separated)["outcome"])


if __name__ == "__main__":
    unittest.main()
