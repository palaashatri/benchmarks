import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import telemetry


class TelemetryTests(unittest.TestCase):
    def test_parse_kib(self):
        self.assertEqual(123 * 1024, telemetry.parse_kib("123 kB"))
        self.assertIsNone(telemetry.parse_kib(None))

    def test_parse_gc_pauses(self):
        result = telemetry.parse_gc_pauses(
            """
[0.100s][info][gc] GC(0) Pause Young (Normal) 20M->3M(256M) 12.5ms
[1.000s][info][gc] GC(1) Pause Full (System.gc()) 100M->10M(256M) 0.250s
"""
        )
        self.assertEqual(2, result["count"])
        self.assertAlmostEqual(262.5, result["total_ms"])
        self.assertAlmostEqual(250.0, result["max_ms"])
        self.assertAlmostEqual(250.0, result["p99_ms"])

    def test_parse_nmt(self):
        result = telemetry.parse_nmt("Total: reserved=123KB, committed=45KB")
        self.assertEqual(123 * 1024, result["reserved_bytes"])
        self.assertEqual(45 * 1024, result["committed_bytes"])

    def test_parse_jstat_compiler(self):
        result = telemetry.parse_jstat_compiler(
            "Compiled Failed Invalid   Time   FailedType FailedMethod\n"
            "    123      2       1   4.25          1 java.lang.Foo bar\n"
        )
        self.assertEqual(123, result["compiled"])
        self.assertEqual(2, result["failed"])
        self.assertEqual(1, result["invalid"])
        self.assertAlmostEqual(4.25, result["time_seconds"])

    def test_duration_to_ms(self):
        self.assertAlmostEqual(12.5, telemetry.duration_to_ms("PT0.0125S"))
        self.assertAlmostEqual(2.0, telemetry.duration_to_ms(2_000_000))
        self.assertIsNone(telemetry.duration_to_ms("unknown"))

    def test_parse_jfr_json(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "events.json"
            path.write_text(json.dumps({
                "recording": {
                    "events": [
                        {
                            "type": "jdk.ObjectAllocationSample",
                            "values": {"weight": 4096},
                        },
                        {
                            "type": "jdk.Compilation",
                            "values": {"duration": "PT0.002S"},
                        },
                        {
                            "type": "jdk.Compilation",
                            "values": {"duration": 3_000_000},
                        },
                        {
                            "type": "jdk.GarbageCollection",
                            "values": {},
                        },
                    ]
                }
            }))
            result = telemetry.parse_jfr_json(path)
        self.assertEqual(4096, result["allocation_sample_weight_bytes"])
        self.assertEqual(2, result["compilation_events"])
        self.assertAlmostEqual(5.0, result["compilation_duration_ms"])
        self.assertEqual(1, result["gc_events"])

    def test_summarize_samples(self):
        samples = [
            telemetry.Sample(1, None, None, 100, 120, 3, 10, 20),
            telemetry.Sample(2, 100.0, 25.0, 200, 220, 5, 30, 50),
            telemetry.Sample(3, 50.0, 12.5, 300, 320, 4, 40, 80),
        ]
        summary = telemetry.summarize_samples(samples)
        self.assertEqual(3, summary["sample_count"])
        self.assertAlmostEqual(75.0, summary["process_cpu_pct_one_core_mean"])
        self.assertEqual(300.0, summary["rss_bytes_max"])
        self.assertEqual(5.0, summary["threads_max"])
        self.assertEqual(40, summary["read_bytes_end"])
        self.assertEqual(80, summary["write_bytes_end"])


if __name__ == "__main__":
    unittest.main()
