import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import measurement_stats as stats


class OutlierTests(unittest.TestCase):
    def test_modified_z_flags_extreme_point_and_keeps_the_rest(self):
        values = [10.0, 10.2, 9.9, 10.1, 10.0, 25.0]
        flagged = stats.modified_z_outliers(values)
        self.assertEqual([5], [item["index"] for item in flagged])
        self.assertEqual("modified_z", flagged[0]["method"])

    def test_zero_mad_reports_nothing_rather_than_everything(self):
        self.assertEqual([], stats.modified_z_outliers([5.0, 5.0, 5.0, 9.0]))

    def test_iqr_fences_need_four_values(self):
        self.assertEqual([], stats.iqr_outliers([1.0, 100.0, 2.0]))
        self.assertEqual([4], [item["index"] for item in stats.iqr_outliers([1.0, 1.1, 1.2, 1.3, 50.0])])

    def test_describe_outliers_uses_repetition_labels_and_never_drops_values(self):
        report = stats.describe_outliers("throughput", [10.0, 10.1, 9.9, 10.0, 40.0], repetitions=[1, 2, 3, 4, 5])
        self.assertEqual(5, report["count"])
        self.assertIn(5, [item["repetition"] for item in report["outliers"]])


class SpreadTests(unittest.TestCase):
    def test_single_sample_has_no_spread(self):
        self.assertIsNone(stats.coefficient_of_variation([3.0]))
        self.assertIsNone(stats.median_absolute_deviation([]))

    def test_bootstrap_interval_is_null_without_two_samples(self):
        self.assertEqual({"low": None, "high": None, "confidence": 0.95}, stats.bootstrap_ci([1.0], seed=1))

    def test_bootstrap_interval_is_reproducible(self):
        values = [9.0, 10.0, 11.0, 10.5, 9.5]
        self.assertEqual(stats.bootstrap_ci(values, seed=7), stats.bootstrap_ci(values, seed=7))


class StabilityTests(unittest.TestCase):
    def test_ramp_then_plateau_is_detected_at_the_plateau(self):
        series = [5, 20, 50, 80] + [100, 101, 99, 100, 100, 102] * 3
        self.assertEqual(4, stats.find_stable_window(series))

    def test_never_stable_series_returns_none(self):
        self.assertIsNone(stats.find_stable_window([1, 100, 2, 90, 3, 80, 4, 70, 5]))

    def test_zero_buckets_never_count_as_stable(self):
        self.assertIsNone(stats.find_stable_window([0, 0, 0, 0, 0, 0]))

    def test_empty_buckets_are_not_stable_and_do_not_crash(self):
        self.assertEqual(2, stats.find_stable_window([None, None, 5.0, 5.0, 5.0, 5.0, 5.0], window=5))
        self.assertIsNone(stats.find_stable_window([None, 5.0, None, 5.0, None, 5.0], window=5))

    def test_warmup_must_contain_the_stable_window(self):
        series = [5, 20, 50, 80] + [100] * 10
        inside = stats.warmup_stability(series, warmup_seconds=10)
        self.assertTrue(inside["stable_before_measurement"])
        self.assertEqual(4, inside["stable_at_second"])
        late = stats.warmup_stability(series, warmup_seconds=8)
        self.assertFalse(late["stable_before_measurement"])


if __name__ == "__main__":
    unittest.main()
