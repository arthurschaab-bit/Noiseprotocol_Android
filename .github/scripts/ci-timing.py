#!/usr/bin/env python3
"""Leichte Zeitmessung ohne Dependencies; echte Kommando-Exitcodes bleiben erhalten."""

import json
import os
from pathlib import Path
import subprocess
import sys
import time


def log_path():
    return Path(os.environ.get("CI_TIMING_FILE", "logs/ci-timing.jsonl"))


def record(kind, label="", **extra):
    try:
        path = log_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(dict(kind=kind, label=label, at=time.monotonic(), **extra)) + "\n")
    except OSError as error:
        print(f"::warning::Zeitmessung nicht schreibbar: {error}", file=sys.stderr)


def run(label, command):
    start = time.monotonic()
    status = 127
    try:
        status = subprocess.run(command, check=False).returncode
        if status < 0:
            status = 128 - status
    except OSError as error:
        print(f"Kommando konnte nicht gestartet werden: {error}", file=sys.stderr)
    except KeyboardInterrupt:
        status = 130
    finally:
        record("duration", label, seconds=time.monotonic() - start, status=str(status))
    return status


def summary():
    entries = []
    try:
        for line in log_path().read_text(encoding="utf-8").splitlines():
            try:
                entries.append(json.loads(line))
            except ValueError:
                print("Zeitmessung enthaelt eine unvollstaendige Zeile (Abbruch).\n")
    except FileNotFoundError:
        print("Keine Zeitmessung vorhanden; siehe Job-Protokoll.")
        return
    print("## CI-Laufzeiten\n\n| Phase | Dauer | Status |\n|---|---:|---|")
    starts = {}
    job_start = None
    for entry in entries:
        kind, label = entry["kind"], entry["label"].replace("|", "\\|")
        if kind == "init":
            job_start = entry["at"]
        elif kind == "start":
            starts[label] = entry["at"]
        elif kind == "duration":
            print(f"| {label} | {entry['seconds']:.1f} s | Exit {entry['status']} |")
        elif kind == "end":
            if label in starts:
                duration = entry["at"] - starts.pop(label)
                print(f"| {label} | {duration:.1f} s | {entry['status']} |")
    for label in starts:
        print(f"| {label} | nicht abgeschlossen | siehe Job-Protokoll |")
    if job_start is not None:
        print(f"| **Job bis Summary (ab Checkout)** | **{time.monotonic() - job_start:.1f} s** | gemessen |")
    print("\nDie Gesamtdauer enthaelt Setup und Artefakte bis zu diesem Schritt, keine Warteschlange, "
          "Checkout- oder Post-Action-Zeit. Emulator-Gesamtzeit und ihre Unterphasen "
          "ueberlappen; die Zeilen nicht addieren. Nicht ausgefuehrte Phasen fehlen.")


def main(args):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if args == ["init"]:
        record("init")
    elif args == ["summary"]:
        summary()
    elif len(args) >= 2 and args[0] == "start":
        record("start", args[1])
    elif len(args) >= 2 and args[0] == "end":
        record("end", args[1], status=args[2] if len(args) > 2 else "beendet")
    elif len(args) >= 4 and args[0] == "run" and args[2] == "--":
        return run(args[1], args[3:])
    else:
        print("Usage: ci-timing.py init|summary|start LABEL|end LABEL [STATUS]|run LABEL -- COMMAND", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
