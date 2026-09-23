import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci-changes.py"
SPEC = importlib.util.spec_from_file_location("ci_changes", SCRIPT)
changes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(changes)


class PathPolicyTest(unittest.TestCase):
    def test_only_documentation_markdown_is_exempt(self):
        for path in ("README.md", "AGENTS.md", "docs/test.md", "docs/deep/file.md", "docs/mit Leerzeichen.md"):
            with self.subTest(path=path):
                self.assertTrue(changes.docs_only(path))
        for path in (
            "app/src/main/assets/help.md", "app/src/test/readme.md", ".github/README.md",
            ".github/workflows/androidci.yml", ".github/scripts/ci-changes.py",
            "docs/discovery/decoder.py", "docs/data.csv", "docs/image.png",
            "gradle.properties", "settings.gradle.kts", "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties", "requirements-test-python.txt",
            "app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml",
            "app/src/main/python/report.py", "app/src/test/java/Test.kt", "unknown.txt",
        ):
            with self.subTest(path=path):
                self.assertFalse(changes.docs_only(path))


class GitChangesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "--quiet", "--initial-branch=main")
        self.git("config", "user.email", "ci-test@example.invalid")
        self.git("config", "user.name", "CI Test")
        self.write("README.md", "initial documentation")
        self.write("app/src/main/App.kt", "initial source")
        self.base = self.save()

    def git(self, *args):
        return changes.git(self.repo, *args).decode("utf-8").strip()

    def write(self, path, content="changed"):
        file = self.repo / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")

    def save(self):
        self.git("add", "--all")
        self.git("commit", "--quiet", "-m", "fixture")
        return self.git("rev-parse", "HEAD")

    def push(self, base=None, head=None):
        return changes.classify("push", {"before": base or self.base, "after": head or self.git("rev-parse", "HEAD")}, self.repo)

    def test_docs_push_skips_android(self):
        self.write("docs/nested/Änderungen mit Leerzeichen.md")
        self.save()
        self.assertEqual((False, False), self.push()[::2])

    def test_code_push_runs_android(self):
        self.write("app/src/main/App.kt")
        self.save()
        self.assertEqual((True, False), self.push()[::2])

    def test_mixed_push_runs_android(self):
        self.write("README.md")
        self.write("gradle.properties")
        self.save()
        self.assertTrue(self.push()[0])

    def test_deleted_source_runs_android(self):
        (self.repo / "app/src/main/App.kt").unlink()
        self.save()
        self.assertTrue(self.push()[0])

    def test_deleted_root_markdown_skips_android(self):
        (self.repo / "README.md").unlink()
        self.save()
        self.assertFalse(self.push()[0])

    def test_renaming_source_to_documentation_does_not_hide_source_deletion(self):
        (self.repo / "docs").mkdir()
        self.git("mv", "app/src/main/App.kt", "docs/example.md")
        self.save()
        self.assertTrue(self.push()[0])

    def test_more_than_300_paths_includes_code_outside_documentation(self):
        for number in range(305):
            self.write(f"docs/file-{number:03}.md")
        self.write("zzz-code.kt")
        self.save()
        android, reason, warning = self.push()
        self.assertTrue(android)
        self.assertIn("306", reason)
        self.assertFalse(warning)

    def test_pr_uses_merge_base_and_all_pr_commits(self):
        self.git("checkout", "--quiet", "-b", "feature")
        self.write("app/src/main/App.kt", "code in earlier PR commit")
        self.save()
        self.write("docs/latest.md", "latest PR commit is documentation only")
        head = self.save()
        self.git("checkout", "--quiet", "main")
        self.write("main-only.kt")
        new_base = self.save()
        result = changes.classify("pull_request", {"pull_request": {"base": {"sha": new_base}, "head": {"sha": head}}}, self.repo)
        self.assertTrue(result[0])
        self.assertIn("2 geänderte Pfade", result[1])
        self.assertFalse(result[2])

    def test_docs_pr_excludes_unrelated_base_branch_changes(self):
        self.git("checkout", "--quiet", "-b", "feature")
        self.write("docs/latest.md")
        head = self.save()
        self.git("checkout", "--quiet", "main")
        self.write("app/src/main/App.kt", "main advanced")
        new_base = self.save()
        result = changes.classify("pull_request", {"pull_request": {"base": {"sha": new_base}, "head": {"sha": head}}}, self.repo)
        self.assertEqual((False, False), result[::2])

    def test_empty_diff_fails_closed(self):
        self.assertEqual((True, True), self.push()[::2])

    def test_missing_commits_fail_closed(self):
        for missing in ("0" * 40, "a" * 40, "--output=unexpected"):
            with self.subTest(missing=missing):
                self.assertEqual((True, True), self.push(base=missing)[::2])

    def test_missing_history_fails_closed(self):
        self.git("checkout", "--quiet", "--orphan", "unrelated")
        self.write("docs/unrelated.md")
        head = self.save()
        result = changes.classify("pull_request", {"pull_request": {"base": {"sha": self.base}, "head": {"sha": head}}}, self.repo)
        self.assertEqual((True, True), result[::2])

    def test_manual_dispatch_always_runs(self):
        self.assertEqual((True, False), changes.classify("workflow_dispatch", None, self.repo)[::2])

    def test_unsupported_or_malformed_events_fail_closed(self):
        for name, event in (("schedule", {}), ("pull_request", {}), ("push", None), ("pull_request", {"pull_request": []})):
            with self.subTest(name=name, event=event):
                self.assertEqual((True, True), changes.classify(name, event, self.repo)[::2])

    def test_cli_writes_output_and_summary(self):
        self.write("docs/new.md")
        head = self.save()
        event_file = self.repo / "event.json"
        output_file = self.repo / "output.txt"
        summary_file = self.repo / "summary.md"
        event_file.write_text(json.dumps({"before": self.base, "after": head}), encoding="utf-8")
        env = dict(os.environ, GITHUB_EVENT_NAME="push", GITHUB_EVENT_PATH=str(event_file), GITHUB_OUTPUT=str(output_file), GITHUB_STEP_SUMMARY=str(summary_file), PYTHONIOENCODING="utf-8")
        subprocess.run([sys.executable, str(SCRIPT)], cwd=self.repo, env=env, check=True, capture_output=True)
        self.assertEqual("android=false\n", output_file.read_text(encoding="utf-8"))
        self.assertIn("ausschließlich Markdown", summary_file.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
