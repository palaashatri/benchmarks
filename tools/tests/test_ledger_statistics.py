import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import run_ledger_experiment


class AggregateMetricTests(unittest.TestCase):
    def test_single_sample_has_unknown_spread(self):
        metric = run_ledger_experiment.aggregate_metric([5.0], 1)
        self.assertEqual(1, metric["count"])
        self.assertEqual(5.0, metric["median"])
        self.assertIsNone(metric["standard_deviation"])
        self.assertIsNone(metric["coefficient_of_variation"])

    def test_multiple_samples_report_spread(self):
        metric = run_ledger_experiment.aggregate_metric([4.0, 6.0], 1)
        self.assertAlmostEqual(2 ** 0.5, metric["standard_deviation"])
        self.assertAlmostEqual(0.2828, metric["coefficient_of_variation"], places=4)

    def test_empty_values_are_unknown_not_zero(self):
        metric = run_ledger_experiment.aggregate_metric([], 1)
        self.assertEqual(0, metric["count"])
        self.assertIsNone(metric["median"])
        self.assertIsNone(metric["standard_deviation"])


if __name__ == "__main__":
    unittest.main()
