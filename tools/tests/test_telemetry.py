import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import telemetry

JSTAT_COMPILER = """Compiled Failed Invalid   Time   FailedType FailedMethod
    3309      3       0     2.83          1 jdk/internal/org/objectweb/asm/MethodWriter visitLabel
"""
JAVA_TOOL_OPTIONS_BANNER = "Picked up JAVA_TOOL_OPTIONS: -Dhttps.proxyHost=127.0.0.1"


class JstatCompilerTests(unittest.TestCase):
    def test_parses_plain_output(self):
        parsed = telemetry.parse_jstat_compiler(JSTAT_COMPILER)
        self.assertEqual(3309, parsed["compiled"])
        self.assertEqual(3, parsed["failed"])
        self.assertEqual(0, parsed["invalid"])
        self.assertEqual(2.83, parsed["time_seconds"])

    def test_banner_line_does_not_shift_the_columns(self):
        # Before the fix, a banner after the values line was read as the header pair,
        # so every compiler statistic came back as None.
        parsed = telemetry.parse_jstat_compiler(JAVA_TOOL_OPTIONS_BANNER + "\n" + JSTAT_COMPILER + "\n" + JAVA_TOOL_OPTIONS_BANNER + "\n")
        self.assertEqual(3309, parsed["compiled"])

    def test_missing_output_is_unknown_not_zero(self):
        parsed = telemetry.parse_jstat_compiler(JAVA_TOOL_OPTIONS_BANNER)
        self.assertEqual({"compiled": None, "failed": None, "invalid": None, "time_seconds": None}, parsed)


if __name__ == "__main__":
    unittest.main()
