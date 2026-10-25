import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import run_tier2_experiment as runner

REQUIRED_KEYS = {
    "app_dir", "main_class", "token_prefix", "identity_path", "load_mix",
    "correctness", "build", "classpath_file",
}


def declared_tier2_workloads() -> list[str]:
    return sorted(path.parent.name for path in (ROOT / "benchmarks").glob("*/tier2.json"))


class Tier2ContractTests(unittest.TestCase):
    def test_every_declared_contract_has_the_required_fields(self):
        for workload in declared_tier2_workloads():
            spec = json.loads((ROOT / "benchmarks" / workload / "tier2.json").read_text())
            missing = REQUIRED_KEYS - set(spec)
            self.assertFalse(missing, f"{workload} is missing {sorted(missing)}")

    def test_every_contract_names_a_catalogued_tier2_workload(self):
        catalog = json.loads((ROOT / "benchmarks" / "catalog.json").read_text())
        tiers = {item["id"]: item["tier"] for item in catalog["workloads"]}
        for workload in declared_tier2_workloads():
            self.assertIn(workload, tiers)
            self.assertEqual("tier-2", tiers[workload], f"{workload} has a contract but is not Tier 2")

    def test_contract_paths_exist(self):
        for workload in declared_tier2_workloads():
            directory = ROOT / "benchmarks" / workload
            spec = json.loads((directory / "tier2.json").read_text())
            self.assertTrue((directory / spec["app_dir"]).is_dir(), workload)
            self.assertTrue((directory / spec["load_mix"]).is_file(), workload)

    def test_request_mixes_are_well_formed(self):
        for workload in declared_tier2_workloads():
            directory = ROOT / "benchmarks" / workload
            spec = json.loads((directory / "tier2.json").read_text())
            lines = [line for line in (directory / spec["load_mix"]).read_text().splitlines()
                     if line.strip() and not line.startswith("#")]
            self.assertTrue(lines, f"{workload} has no request templates")
            for line in lines:
                columns = line.split("\t")
                self.assertGreaterEqual(len(columns), 3, f"{workload}: {line!r}")
                self.assertTrue(columns[0].isdigit() and int(columns[0]) > 0, f"{workload}: weight in {line!r}")
                self.assertIn(columns[1], {"GET", "POST", "PUT", "DELETE"}, f"{workload}: {line!r}")
                self.assertTrue(columns[2].startswith("/"), f"{workload}: path in {line!r}")

    def test_loading_a_workload_returns_its_jdk_band(self):
        workload = runner.load_workload("12-hft-trading-gateway")
        self.assertEqual((21, 25), (workload.min_jdk, workload.max_jdk))
        self.assertEqual("tier-2", workload.tier)
        self.assertEqual("hft", workload.token_prefix)


if __name__ == "__main__":
    unittest.main()
