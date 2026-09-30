#!/usr/bin/env python3
"""Verify smoke envelopes against a separately launched application JVM."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HARNESS_CLASSES = Path(sys.argv.pop(3)).resolve()
from test_architecture import App, WORKLOAD


class HarnessTests(unittest.TestCase):
    def test_output_identifies_application_and_never_claims_measurements(self):
        with App(java_args=('-XX:+UseSerialGC',)) as app, tempfile.TemporaryDirectory() as temporary:
            health = app.get('/health')
            if 'services' in health:
                app.children.update(service['pid'] for service in health['services'])
            if WORKLOAD.startswith('10'):
                app.children.update(replica['pid'] for replica in app.get('/api/v1/fleet/status')['services'])
            if WORKLOAD.startswith('11'):
                app.children.update(replica['pid'] for replica in app.get('/api/v1/metrics/scaling')['replicas'])
            out = Path(temporary) / 'result.json'
            process = subprocess.run([
                'java', '-cp', str(HARNESS_CLASSES),
                f'com.palaashatri.bench.b{WORKLOAD[:2]}.harness.BenchmarkHarness',
                '--base-url', app.base, '--requests', '5', '--threads', '2', '--runs', '2', '--out', str(out)],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
            result = json.loads(out.read_text())
            self.assertEqual(result['run_kind'], 'smoke')
            self.assertEqual(result['implementation_tier'], 'tier-1')
            self.assertFalse(result['measurement_valid'])
            self.assertTrue(result['invalid_reasons'])
            self.assertIsNone(result['kpis'])
            self.assertEqual(result['requests'], 10)
            self.assertEqual(result['ok'], 10)
            self.assertEqual(result['application_runtime'], app.get('/runtime'))
            self.assertEqual(result['application_runtime']['pid'], app.process.pid)
            self.assertIn('-XX:+UseSerialGC', result['jvm_flags'])
            self.assertFalse(any('G1' in name for name in result['gc']))
            self.assertTrue(result['gc'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
