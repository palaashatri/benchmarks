import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import benchctl
import benchctl_entry


class BenchctlTests(unittest.TestCase):
    def test_parse_java_8(self):
        self.assertEqual(8, benchctl.parse_java_feature_version('java version "1.8.0_402"'))

    def test_parse_modern_java(self):
        self.assertEqual(25, benchctl.parse_java_feature_version('openjdk version "25-ea"'))

    def test_validate_benchmark_requires_repetitions(self):
        errors = benchctl.validate_experiment({
            "schema_version": "1.0.0",
            "run_kind": "benchmark",
            "workloads": ["x"],
            "runtimes": [21],
            "gcs": ["g1"],
            "repetitions": 1,
        })
        self.assertIn("benchmark runs require at least 5 repetitions", errors)

    def test_invalid_result_cannot_be_marked_valid(self):
        errors = benchctl.validate_result({
            "schema_version": "1.0.0",
            "run_kind": "benchmark",
            "implementation_tier": "tier-2",
            "measurement_valid": True,
            "invalid_reasons": ["bad environment"],
            "warnings": [],
        })
        self.assertTrue(any("measurement_valid" in item for item in errors))

    def test_json_is_valid_yaml_subset(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "experiment.yaml"
            path.write_text(json.dumps({"schema_version": "1.0.0"}))
            self.assertEqual("1.0.0", benchctl.load_document(path)["schema_version"])

    def test_planner_skips_incompatible_jdk_band(self):
        original_plan = benchctl_entry._original_build_plan
        original_catalog = benchctl_entry.core.catalog
        try:
            benchctl_entry._original_build_plan = lambda data: {
                "items": [
                    {"workload": "modern", "runtime": "jdk8", "gc": "default", "feature_version": 8},
                    {"workload": "modern", "runtime": "jdk21", "gc": "default", "feature_version": 21},
                ],
                "skipped": [],
            }
            benchctl_entry.core.catalog = lambda: [
                {"id": "modern", "min_jdk": 21, "max_jdk": 25}
            ]
            plan = benchctl_entry.constrained_build_plan({})
            self.assertEqual(["jdk21"], [item["runtime"] for item in plan["items"]])
            self.assertEqual(1, len(plan["skipped"]))
            self.assertIn("supports JDK 21-25", plan["skipped"][0]["reason"])
        finally:
            benchctl_entry._original_build_plan = original_plan
            benchctl_entry.core.catalog = original_catalog

    def test_legacy_process_metrics_are_always_discarded(self):
        result = {
            "env": {"kernel": "wrong-process"},
            "kpis": {
                "throughput": 100,
                "gc_pause_p99_ms": 17,
                "alloc_rate_mb_s": 23,
                "rss_mb": 512,
                "native_mem_mb": 300,
                "cpu_util_pct": 87.5,
            },
            "phases": {"warmup_s": 1, "measure_s": 2},
            "warnings": [],
        }
        sanitized = benchctl_entry._sanitize_legacy_result(result)
        self.assertNotIn("env", sanitized)
        self.assertEqual(100, sanitized["kpis"]["throughput"])
        for key in ("gc_pause_p99_ms", "alloc_rate_mb_s", "rss_mb", "native_mem_mb", "cpu_util_pct"):
            self.assertIsNone(sanitized["kpis"][key])
        self.assertIsNone(sanitized["phases"]["warmup_s"])
        self.assertIsNone(sanitized["phases"]["measure_s"])

    def test_aggregate_result_validation(self):
        child = {
            "schema_version": "1.0.0",
            "run_kind": "smoke",
            "implementation_tier": "tier-1",
            "measurement_valid": False,
            "invalid_reasons": ["smoke"],
            "warnings": [],
        }
        aggregate = {
            "schema_version": "1.0.0",
            "run_kind": "smoke",
            "measurement_valid": False,
            "results": [child],
        }
        self.assertEqual([], benchctl_entry.validate_result_document(aggregate))


if __name__ == "__main__":
    unittest.main()
