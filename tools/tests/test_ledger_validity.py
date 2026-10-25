import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import run_tier2_experiment as ledger

PASSING_PREFLIGHT = {"passed": True, "checks": [], "failures": []}
FAILING_PREFLIGHT = {"passed": False, "checks": [], "failures": ["cpu_governor: governors are powersave"]}


def repetition(status="passed", throughput=100.0, p99=2.0, collector="g1"):
    return {
        "status": status,
        "warmup_stability": {"stable_before_measurement": True},
        "requested_collector": collector,
        "actual_collector": collector,
        "environment": {field: "fixed" for field in ledger.CRITICAL_ENVIRONMENT_FIELDS},
        "load": {
            "errors": {"measurement": 0},
            "completed": {"measurement": 80},
            "scheduled": {"measurement": 80},
            "kpis": {"throughput": throughput, "p99_ms": p99},
        },
        "telemetry": {
            "process": {"sample_count": 20},
            "nmt": {"committed_bytes": 10 * 1024 * 1024},
            "compiler": {"compiled": 100},
            "jfr_conversion": {"returncode": 0},
            "jfr": {"allocation_sample_weight_bytes": 1024},
        },
    }


class LedgerValidityTests(unittest.TestCase):
    def test_five_stable_repetitions_are_valid(self):
        valid, reasons, _warnings = ledger.validity(
            [repetition(throughput=100 + index) for index in range(5)],
            "benchmark",
            PASSING_PREFLIGHT,
        )
        self.assertTrue(valid, reasons)
        self.assertEqual([], reasons)

    def test_failed_preflight_invalidates_an_otherwise_valid_run(self):
        valid, reasons, _warnings = ledger.validity(
            [repetition(throughput=100 + index) for index in range(5)],
            "benchmark",
            FAILING_PREFLIGHT,
        )
        self.assertFalse(valid)
        self.assertTrue(any("cpu_governor" in reason for reason in reasons))

    def test_missing_preflight_is_recorded_as_a_reason(self):
        valid, reasons, _warnings = ledger.validity(
            [repetition(throughput=100 + index) for index in range(5)], "benchmark"
        )
        self.assertFalse(valid)
        self.assertIn("host preflight was not recorded", reasons)

    def test_too_few_repetitions_are_invalid(self):
        valid, reasons, _warnings = ledger.validity([repetition()], "benchmark")
        self.assertFalse(valid)
        self.assertTrue(any("five repetitions" in reason for reason in reasons))

    def test_throughput_cv_gate(self):
        values = [repetition(throughput=value) for value in (50, 200, 80, 300, 90)]
        valid, reasons, _warnings = ledger.validity(values, "benchmark")
        self.assertFalse(valid)
        self.assertTrue(any("coefficient of variation" in reason for reason in reasons))


if __name__ == "__main__":
    unittest.main()
