#!/usr/bin/env python3
"""Entscheidet konservativ, ob ein Lauf Android-Build und Emulator braucht.

Nur Markdown im Repository-Wurzelverzeichnis und unter docs/ darf die Gates
überspringen. Unbekannte Dateien, unvollständige Git-Historie und Fehler führen
immer zum vollständigen Lauf. git diff liefert alle Pfade ohne API-Paginierung.
"""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


def docs_only(path):
    return path.endswith(".md") and ("/" not in path or path.startswith("docs/"))


def git(repo, *args):
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
    ).stdout


def commit(value):
    # Event-Daten sind keine Shell-Befehle und auch keine frei wählbaren Git-Optionen.
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", value):
        raise ValueError("Commit-SHA fehlt oder ist ungültig")
    if set(value) == {"0"}:
        raise ValueError("Kein vollständiger vorheriger Commit vorhanden")
    return value


def classify(event_name, event, repo="."):
    """Liefert (android, Begründung, Warnung), bei Unsicherheit immer android=True."""
    if event_name == "workflow_dispatch":
        return True, "Manueller Lauf: alle Quality Gates werden ausgeführt.", False
    try:
        if not isinstance(event, dict):
            raise ValueError("Event ist kein JSON-Objekt")
        if event_name == "pull_request":
            pr = event["pull_request"]
            base = commit(pr["base"]["sha"])
            head = commit(pr["head"]["sha"])
            # Alle Änderungen dieses PRs, auch wenn main seit dem Abzweigen weiterlief.
            base = git(repo, "merge-base", base, head).decode("ascii").strip()
            base = commit(base)
        elif event_name == "push":
            base = commit(event["before"])
            head = commit(event["after"])
        else:
            raise ValueError("Event wird nicht als sicherer Docs-only-Lauf behandelt")

        # --no-renames lässt beim Verschieben von Code nach docs/ auch den gelöschten
        # Quellpfad stehen. -z erhält Leerzeichen, Zeilenumbrüche und Sonderzeichen.
        raw = git(repo, "diff", "--name-only", "--no-renames", "-z", base, head, "--")
        paths = [os.fsdecode(path) for path in raw.split(b"\0") if path]
        if not paths:
            raise ValueError("Leere Änderungsliste ist kein belegter Docs-only-Lauf")
        relevant = sum(not docs_only(path) for path in paths)
        if relevant:
            return True, f"{len(paths)} geänderte Pfade, davon {relevant} außerhalb der Markdown-Ausnahme: alle Quality Gates.", False
        return False, f"{len(paths)} geänderte Pfade: ausschließlich Markdown im Wurzelverzeichnis oder unter docs/. Android-Gates entfallen.", False
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
        # Keine Git-/Event-Ausgabe in Workflow-Kommandos interpolieren. Eine fehlende
        # oder unlesbare Historie darf niemals den Android-Testlauf ausschalten.
        return True, "Änderungen nicht sicher bestimmbar (Event, Commit-Historie oder Git-Diff): vorsorglich alle Quality Gates.", True


def main():
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    try:
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    except (KeyError, OSError, ValueError):
        event = None
    android, reason, warning = classify(event_name, event)
    result = f"android={str(android).lower()}"
    print(result)
    print(reason)
    if warning:
        print(f"::warning::{reason}")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(result + "\n")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write("## Änderungserkennung\n\n" + reason + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
