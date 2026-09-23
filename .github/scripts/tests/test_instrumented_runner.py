"""Fuehrt den echten Bash-Runner mit simuliertem Gradle und ADB aus (kein Emulator)."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parents[1]


def find_bash():
    if os.environ.get("BASH_EXE"):
        return os.environ["BASH_EXE"]
    if os.name == "nt":
        # Windows' System32/bash.exe ist WSL; die Tests brauchen Git Bash.
        for base in (os.environ.get("ProgramFiles", "C:/Program Files"),
                     os.environ.get("LOCALAPPDATA", "") + "/Programs"):
            candidate = Path(base) / "Git/bin/bash.exe"
            if candidate.is_file():
                return str(candidate)
        return None
    return shutil.which("bash")


BASH = find_bash()

ADB = r'''#!/usr/bin/env bash
set -eu
printf 'adb %s\n' "$*" >> calls.log
case "$*" in
  wait-for-device)
    if [ "$SCENARIO" = setup_failure ]; then exit 7; fi
    ;;
  'shell pm list instrumentation')
    if [ "$SCENARIO" = missing_runner ]; then exit 0; fi
    echo 'instrumentation:com.example.lrmprotokoll.test/androidx.test.runner.AndroidJUnitRunner (target=com.example.lrmprotokoll)'
    ;;
  'shell dumpsys package '*)
    for permission in CAMERA ACCESS_COARSE_LOCATION BLUETOOTH_SCAN BLUETOOTH_CONNECT; do
      granted=false
      if [ "$SCENARIO" = revoke_failure ]; then granted=true; fi
      printf 'android.permission.%s: granted=%s\n  flags=[]\n' "$permission" "$granted"
    done
    ;;
  'shell am instrument '*)
    case "$*" in
      *FotoDokumentationSheetPermissionInstrumentedTest*)
        if [ "$SCENARIO" = permission_failure ]; then
          printf 'FAILURES!!!\nExpected <camera> & dialog\nTests run: 1, Failures: 1\n'
          exit 0
        fi
        if [ "$SCENARIO" = permission_exit_failure ] || [ "$SCENARIO" = permission_and_grant_failure ]; then
          printf 'OK (1 test)\n'
          exit 23
        fi
        ;;
    esac
    printf 'OK (1 test)\r\n'
    ;;
  'shell pm grant '*)
    if [ "$SCENARIO" = permission_and_grant_failure ]; then exit 31; fi
    ;;
  'logcat -b all -d'|'shell dumpsys activity')
    if [ "$SCENARIO" = diagnostics_failure ]; then exit 33; fi
    printf 'LIVE DIAGNOSTIC: %s\n' "$*"
    ;;
  'logcat -c'|'shell input keyevent 82'|'shell am force-stop '*|'shell pm revoke '*)
    ;;
  *) printf 'EMULATOR INFORMATION: %s\n' "$*" ;;
esac
'''

GRADLE = r'''#!/usr/bin/env bash
set -eu
printf 'gradlew %s\n' "$*" >> calls.log
case "$1" in
  connectedDebugAndroidTest)
    case "$SCENARIO" in
      test_failure|diagnostics_failure) exit 42 ;;
      signal)
        kill -TERM "$RUNNER_PID"
        exit 0
        ;;
    esac
    ;;
  installDebug)
    if [ "$SCENARIO" = install_failure ]; then exit 19; fi
    ;;
esac
'''


@unittest.skipUnless(BASH, "Bash bzw. Git Bash wird fuer die Runner-Integrationstests benoetigt")
class InstrumentedRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="instrumented-runner-")
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        (self.workspace / "bin").mkdir()
        (self.workspace / ".github/scripts").mkdir(parents=True)
        self.write(".github/scripts/run-instrumented-tests.sh",
                   (SCRIPTS / "run-instrumented-tests.sh").read_text(encoding="utf-8"))
        self.write("gradlew", GRADLE)
        self.write("bin/adb", ADB)
        # Nur Timing wird ersetzt: das eigentliche Testkommando und sein Status bleiben echt.
        self.write("bin/python3", '''#!/usr/bin/env bash
if [ "$1" = .github/scripts/ci-timing.py ]; then
  printf 'timing %s\\n' "$3" >> calls.log
  shift 4
  exec "$@"
fi
exec "$REAL_PYTHON" "$@"
''')
        self.write("bin/timeout", '''#!/usr/bin/env bash
printf 'timeout %s\\n' "$*" >> calls.log
shift
exec "$@"
''')
        self.write("invoke.sh", '''#!/usr/bin/env bash
export PATH="$PWD/bin:$PATH"
# Im selben Prozess starten, damit TERM dieselbe Trap wie im echten Action-Skript trifft.
export RUNNER_PID=$$
exec bash .github/scripts/run-instrumented-tests.sh "$API_LEVEL"
''')

    def write(self, name, content):
        path = self.workspace / name
        path.write_text(content, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run_scenario(self, scenario="success", api_level=34):
        env = os.environ.copy()
        env.update(SCENARIO=scenario, API_LEVEL=str(api_level),
                   REAL_PYTHON=Path(sys.executable).as_posix(),
                   PYTHONIOENCODING="utf-8", MSYS_NO_PATHCONV="1")
        completed = subprocess.run([BASH, "invoke.sh"], cwd=self.workspace,
                                   env=env, capture_output=True, text=True,
                                   encoding="utf-8", errors="replace", timeout=30)
        self.output = completed.stdout + completed.stderr
        self.calls = (self.workspace / "calls.log").read_text(encoding="utf-8").splitlines()
        # Die Action beendet den Emulator erst nach Rueckkehr des script-Aufrufs.
        self.calls.append("EMULATOR_TEARDOWN")
        return completed.returncode

    def assert_live_diagnostics(self, status):
        self.assertIn(f"Teststatus: {status}",
                      (self.workspace / "logs/emulator-status.txt").read_text())
        for filename in ("adb-logcat.txt", "dumpsys-activity.txt", "emulator-info.txt"):
            self.assertGreater((self.workspace / "logs" / filename).stat().st_size, 0)
        self.assertEqual(self.calls.count("adb logcat -b all -d"), 1)
        self.assertLess(self.calls.index("adb logcat -b all -d"),
                        self.calls.index("EMULATOR_TEARDOWN"))
        # Saemtliche Diagnose-ADB-Aufrufe sind begrenzt, auch UI-Dump und Pull.
        first = self.calls.index("timeout 5s adb devices -l")
        for index, call in enumerate(self.calls[first:], start=first):
            if call.startswith("adb "):
                self.assertEqual(self.calls[index - 1], f"timeout 5s {call}")

    def reports(self):
        return [ET.parse(path).getroot() for path in sorted(
            (self.workspace / "app/build/outputs/androidTest-results/permission").glob("TEST-*.xml"))]

    def test_success_runs_utp_and_all_four_isolated_methods(self):
        self.assertEqual(self.run_scenario(), 0, self.output)
        commands = [line for line in self.calls if line.startswith("adb shell am instrument ")]
        self.assertEqual(len(commands), 4)
        gradle = [line for line in self.calls if line.startswith("gradlew ")]
        self.assertEqual(len(gradle), 2)
        excluded = gradle[0].split(".notClass=", 1)[1].split(",")
        selected = [line.split("-e class ", 1)[1].split(" ", 1)[0] for line in commands]
        self.assertEqual(selected, excluded)
        self.assertNotIn("--no-daemon", "\n".join(gradle))
        self.assertEqual(len(self.reports()), 4)
        self.assertTrue(all(report.get("failures") == "0" for report in self.reports()))
        self.assertEqual(len(list((self.workspace / "logs/permission-tests").glob("*.txt"))), 4)
        self.assertNotIn("adb logcat -b all -d", self.calls)

    def test_api_30_keeps_existing_bluetooth_permission_exception(self):
        self.assertEqual(self.run_scenario(api_level=30), 0, self.output)
        self.assertEqual(len(self.reports()), 3)
        self.assertFalse(any("am instrument" in line and "MeterScreen" in line for line in self.calls))

    def test_main_test_failure_preserves_gradle_status_and_live_logs(self):
        self.assertEqual(self.run_scenario("test_failure"), 42, self.output)
        self.assert_live_diagnostics(42)
        self.assertFalse(any(line.startswith("gradlew installDebug") for line in self.calls))

    def test_install_failure_preserves_original_status(self):
        self.assertEqual(self.run_scenario("install_failure"), 19, self.output)
        self.assert_live_diagnostics(19)

    def test_setup_failure_is_inside_trap_scope(self):
        self.assertEqual(self.run_scenario("setup_failure"), 7, self.output)
        self.assert_live_diagnostics(7)
        self.assertFalse(any(line.startswith("gradlew ") for line in self.calls))

    def test_missing_runner_fails_with_diagnostics(self):
        self.assertEqual(self.run_scenario("missing_runner"), 1, self.output)
        self.assertIn("Keine Instrumentation", self.output)
        self.assert_live_diagnostics(1)

    def test_permission_junit_failure_fails_even_when_adb_returns_zero(self):
        self.assertEqual(self.run_scenario("permission_failure"), 1, self.output)
        self.assert_live_diagnostics(1)
        self.assertEqual(len(self.reports()), 4)
        failed = [report for report in self.reports() if report.get("failures") == "1"]
        self.assertEqual(len(failed), 1)
        self.assertIn("Expected <camera> & dialog", failed[0].find("testcase/failure").text)

    def test_nonzero_adb_status_cannot_be_hidden_by_success_text(self):
        self.assertEqual(self.run_scenario("permission_exit_failure"), 23, self.output)
        self.assert_live_diagnostics(23)
        self.assertEqual(len(self.reports()), 4)

    def test_permission_failure_remains_primary_when_cleanup_also_fails(self):
        self.assertEqual(self.run_scenario("permission_and_grant_failure"), 23, self.output)
        self.assert_live_diagnostics(23)

    def test_failed_revoke_cannot_produce_false_green_permission_test(self):
        self.assertEqual(self.run_scenario("revoke_failure"), 1, self.output)
        self.assert_live_diagnostics(1)
        self.assertIn("nach zwei Versuchen", self.output)
        self.assertFalse(any(line.startswith("adb shell am instrument") for line in self.calls))

    def test_diagnostic_failure_does_not_replace_original_test_status(self):
        self.assertEqual(self.run_scenario("diagnostics_failure"), 42, self.output)
        self.assert_live_diagnostics(42)
        self.assertIn("Status 33", (self.workspace / "logs/adb-logcat.txt").read_text())

    def test_sigterm_collects_diagnostics_and_preserves_signal_status(self):
        self.assertEqual(self.run_scenario("signal"), 143, self.output)
        self.assert_live_diagnostics(143)


if __name__ == "__main__":
    unittest.main()
