import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci-timing.py"


class TimingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "timing.jsonl"
        self.env = dict(os.environ, CI_TIMING_FILE=str(self.path))

    def invoke(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], env=self.env,
                              capture_output=True, text=True, encoding="utf-8", timeout=10)

    def test_failed_command_remains_failed_and_gets_measured(self):
        result = self.invoke("run", "Test", "--", sys.executable, "-c", "raise SystemExit(42)")
        self.assertEqual(result.returncode, 42)
        entry = json.loads(self.path.read_text())
        self.assertEqual(entry["status"], "42")
        self.assertGreaterEqual(entry["seconds"], 0)

    def test_missing_command_cannot_pass(self):
        self.assertEqual(self.invoke("run", "Test", "--", "missing-ci-command-728").returncode, 127)

    def test_reporting_failure_does_not_replace_test_failure(self):
        self.path.mkdir()
        result = self.invoke("run", "Test", "--", sys.executable, "-c", "raise SystemExit(23)")
        self.assertEqual(result.returncode, 23)
        self.assertIn("warning", result.stderr)

    def test_summary_includes_completed_failed_and_interrupted_phases(self):
        self.invoke("init")
        self.invoke("start", "Emulator")
        self.invoke("run", "UTP", "--", sys.executable, "-c", "pass")
        self.invoke("end", "Emulator", "failure")
        self.invoke("start", "Abbruch")
        result = self.invoke("summary")
        self.assertEqual(result.returncode, 0, result.stderr)
        for text in ("UTP", "Exit 0", "failure", "nicht abgeschlossen", "ab Checkout"):
            self.assertIn(text, result.stdout)

    def test_no_timing_file_is_reported_honestly(self):
        self.assertIn("Keine Zeitmessung", self.invoke("summary").stdout)


if __name__ == "__main__":
    unittest.main()
