import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import host_environment as host


def quiet_host(**overrides):
    environment = {
        "cpu_governors": ["performance"],
        "injected_jvm_options": {},
        "loadavg_1m": 0.2,
        "logical_cpus": 8,
        "transparent_hugepage_mode": "madvise",
        "smt_control": "off",
        "cgroup_cpu_max": "max 100000",
        "swap_devices": 0,
    }
    environment.update(overrides)
    return environment


def failing(report):
    return {check["check"] for check in report["checks"] if check["status"] == "fail"}


class PreflightTests(unittest.TestCase):
    def test_quiet_host_passes(self):
        report = host.preflight(quiet_host())
        self.assertTrue(report["passed"], report["failures"])

    def test_non_performance_governor_fails(self):
        report = host.preflight(quiet_host(cpu_governors=["performance", "powersave"]))
        self.assertFalse(report["passed"])
        self.assertIn("cpu_governor", failing(report))

    def test_unobservable_governor_fails_rather_than_passing(self):
        report = host.preflight(quiet_host(cpu_governors=None))
        self.assertIn("cpu_governor", failing(report))

    def test_injected_jvm_options_fail_the_host(self):
        report = host.preflight(quiet_host(injected_jvm_options={"JAVA_TOOL_OPTIONS": "abc"}))
        self.assertIn("jvm_options", failing(report))

    def test_busy_host_fails_on_load(self):
        report = host.preflight(quiet_host(loadavg_1m=6.0))
        self.assertIn("load", failing(report))

    def test_unknown_load_fails_rather_than_passing(self):
        report = host.preflight(quiet_host(loadavg_1m=None))
        self.assertIn("load", failing(report))

    def test_warnings_do_not_fail_the_host(self):
        report = host.preflight(quiet_host(transparent_hugepage_mode="always", swap_devices=1))
        self.assertTrue(report["passed"])
        statuses = {check["check"]: check["status"] for check in report["checks"]}
        self.assertEqual("warn", statuses["transparent_hugepages"])
        self.assertEqual("warn", statuses["swap"])

    def test_cpu_range_lists_are_counted(self):
        self.assertEqual(8, host.count_id_ranges("0-3,8-11"))
        self.assertEqual(1, host.count_id_ranges("0"))
        self.assertIsNone(host.count_id_ranges(None))

    def test_injected_values_are_digested_not_stored(self):
        import os
        os.environ["JAVA_TOOL_OPTIONS"] = "-Dproxy.password=secret"
        try:
            injected = host.injected_jvm_options()
        finally:
            del os.environ["JAVA_TOOL_OPTIONS"]
        self.assertIn("JAVA_TOOL_OPTIONS", injected)
        self.assertNotIn("secret", str(injected))


if __name__ == "__main__":
    unittest.main()
