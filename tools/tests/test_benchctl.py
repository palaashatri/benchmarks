import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "benchctl.py"
spec = importlib.util.spec_from_file_location("benchctl_module", MODULE_PATH)
benchctl = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = benchctl
spec.loader.exec_module(benchctl)

class BenchctlTests(unittest.TestCase):
    def test_parse_java_8(self):
        self.assertEqual(8, benchctl.parse_java_feature_version('java version "1.8.0_402"'))
    def test_parse_modern_java(self):
        self.assertEqual(25, benchctl.parse_java_feature_version('openjdk version "25-ea"'))
    def test_validate_benchmark_requires_repetitions(self):
        errors = benchctl.validate_experiment({"schema_version":"1.0.0","run_kind":"benchmark","workloads":["x"],"runtimes":[21],"gcs":["g1"],"repetitions":1})
        self.assertIn("benchmark runs require at least 5 repetitions", errors)
    def test_invalid_result_cannot_be_marked_valid(self):
        errors = benchctl.validate_result({"schema_version":"1.0.0","run_kind":"benchmark","implementation_tier":"tier-2","measurement_valid":True,"invalid_reasons":["bad environment"],"warnings":[]})
        self.assertTrue(any("measurement_valid" in item for item in errors))
    def test_json_is_valid_yaml_subset(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"experiment.yaml";path.write_text(json.dumps({"schema_version":"1.0.0"}));self.assertEqual("1.0.0",benchctl.load_document(path)["schema_version"])
if __name__=="__main__":unittest.main()
