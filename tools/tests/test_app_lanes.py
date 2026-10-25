import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import app_lanes as lanes


class SummarizeTests(unittest.TestCase):
    def test_missing_values_are_excluded_and_never_counted_as_zero(self):
        summary = lanes.summarize([10.0, None, 12.0, None, 14.0], seed=1)
        self.assertEqual(3, summary["count"])
        self.assertEqual(12.0, summary["median"])
        self.assertEqual(10.0, summary["minimum"])

    def test_no_known_values_gives_null_statistics(self):
        summary = lanes.summarize([None, None], seed=1)
        self.assertEqual(0, summary["count"])
        self.assertIsNone(summary["median"])
        self.assertIsNone(summary["bootstrap_mean_ci"]["low"])

    def test_median_absolute_deviation_comes_from_the_shared_statistics(self):
        summary = lanes.summarize([1.0, 2.0, 3.0, 4.0, 100.0], seed=1)
        self.assertEqual(1.0, summary["median_absolute_deviation"])
        self.assertTrue(summary["outliers"], "the extreme value must be reported, not removed")
        self.assertEqual(5, summary["count"])


class EnvelopeTests(unittest.TestCase):
    def test_every_lane_envelope_is_labelled_uncontrolled_calibration(self):
        document = lanes.envelope("07-coldstart-suite", "startup", {"pid": 1}, [], [], {}, {})
        self.assertEqual("calibration", document["run_kind"])
        self.assertFalse(document["measurement_valid"])
        self.assertIn(lanes.UNCONTROLLED_HOST, document["invalid_reasons"])

    def test_envelope_serialises_without_non_finite_numbers(self):
        document = lanes.envelope("08-etl-batch", "jobs", {}, [], [], {},
                                  {"metrics": lanes.summarize([1.0, 2.0], seed=1)})
        import json
        json.dumps(document, allow_nan=False)


if __name__ == "__main__":
    unittest.main()
