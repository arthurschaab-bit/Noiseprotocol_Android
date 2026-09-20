"""Datei-/JSON-Vertrag Version 2 für Chaquopy, ohne Android-Import.

Kotlin exportiert einen konsistenten Room-Snapshot und besitzt dessen Lebenszyklus.
Referenz: Baul-rm/gesamtbericht_lib_v3.py, compute_day Z. 607–822 und
PdfPages-Seiten Z. 917–3022. Seiteninventar: docs/BERICHT_PDF_SEITEN_V1.md.
"""
from __future__ import annotations

import csv
import json
import math
import os
from datetime import date
from pathlib import Path
from threading import Lock

from laermbericht.areas import apply_area_to_config, area_report_texts, get_area
from laermbericht.day_metrics import DayConfig, Sample, calculate_day_metrics

_RENDER_LOCK = Lock()


def _private_path(value, roots, label):
    if not isinstance(value, str) or not Path(value).is_absolute():
        raise ValueError(f"{label}: Ein absoluter privater Dateipfad fehlt.")
    path = Path(value).resolve()
    if not any(path.is_relative_to(root) for root in roots):
        raise ValueError(f"{label}: Die Datei liegt außerhalb des privaten App-Speichers.")
    return path


def _load_samples(day, roots, override):
    path = _private_path(day.get("samplesPath"), roots, "Rohdaten")
    if not path.is_file():
        raise ValueError(f"Die Rohdaten-Datei für {day['date']} fehlt. Bitte den Bericht erneut erzeugen.")
    samples = []
    unconfirmed = False
    modes = set()
    try:
        with path.open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            expected = {"timestampMillis", "levelDb", "flags", "sessionId", "weighting", "timeWeighting"}
            if set(reader.fieldnames or []) != expected:
                raise ValueError("CSV-Spalten passen nicht zum Rohdatenformat.")
            for row in reader:
                timestamp, flags = int(row["timestampMillis"]), int(row["flags"])
                level = float(row["levelDb"])
                gap = bool(flags & 8)
                if not gap:
                    if not math.isfinite(level):
                        raise ValueError("Ein Messpegel ist nicht endlich.")
                    weighting, mode = row["weighting"], row["timeWeighting"]
                    if weighting and weighting != "A":
                        raise ValueError("C-/andere Frequenzbewertung kann nicht als dB(A) ausgewertet werden.")
                    if not weighting or not mode:
                        unconfirmed = True
                        if not override:
                            raise ValueError("A-/Zeitbewertung ist nicht bestätigt; ein bewusster Override fehlt.")
                    modes.add(mode or "unbestätigt")
                samples.append(Sample(timestamp, level, gap, int(row["sessionId"])))
    except (OSError, UnicodeError, csv.Error, TypeError, KeyError, ValueError) as error:
        raise ValueError(f"Rohdaten für {day['date']} sind nicht auswertbar: {error}") from None
    if len(samples) != day["rawSampleCount"]:
        raise ValueError(f"Rohdaten für {day['date']} sind unvollständig. Bitte erneut exportieren.")
    return samples, unconfirmed, sorted(modes)


def _parse(parameter_json):
    try:
        parameter = json.loads(parameter_json)
    except (ValueError, TypeError):
        raise ValueError("Die Berichtsparameter sind kein gültiges JSON.") from None
    if not isinstance(parameter, dict) or parameter.get("contractVersion") != 2:
        raise ValueError("Die Version der Berichtsparameter ist ungültig; bitte erneut exportieren.")
    try:
        root_values = parameter["privateRoots"]
        if not isinstance(root_values, list) or not root_values:
            raise ValueError("Private Speicherorte fehlen.")
        roots = []
        for value in root_values:
            if not isinstance(value, str) or not Path(value).is_absolute():
                raise ValueError("Private Speicherorte müssen absolute Pfade sein.")
            roots.append(Path(value).resolve())
        output = _private_path(parameter["outputPath"], roots, "PDF-Ausgabe")
        if output.suffix.lower() != ".pdf" or not output.parent.is_dir():
            raise ValueError("Der private PDF-Ausgabeordner fehlt oder der Dateiname ist ungültig.")
        c = parameter["reportConfig"]
        code = c["gebietseinstufung"]
        config = apply_area_to_config(DayConfig(
            coverage_valid=float(c["tierSchwelleVollmessungProzent"]) / 100,
            coverage_partial=float(c["tierSchwelleTeilerfassungProzent"]) / 100,
            partial_estimate_db=float(c["schaetzpegelTeilerfassungDb"]),
            window_estimate_db=0, day_guideline_db=0, day_intervention_db=0,
            conservative_window_start_hour=int(c["konservativFensterStartStunde"]),
            conservative_window_end_hour=int(c["konservativFensterEndeStunde"]),
            device_uncertainty_db=float(c["geraeteUnsicherheitDb"]),
            time_zone=parameter["timeZone"],
        ), code)
        days = parameter["days"]
        if not isinstance(days, list) or not days:
            raise ValueError("Es wurden keine Berichtstage übergeben.")
        dates = [date.fromisoformat(d["date"]) for d in days]
        if dates != sorted(set(dates)):
            raise ValueError("Die Berichtstage müssen eindeutig und chronologisch sortiert sein.")
        for d in days:
            if not isinstance(d["rawSampleCount"], int) or d["rawSampleCount"] < 0:
                raise ValueError("Die Rohdatenanzahl ist ungültig.")
            if d.get("stammdaten") is not None and not isinstance(d["stammdaten"], dict):
                raise ValueError("Die Stammdaten sind ungültig.")
            for photo in d.get("photos", []):
                photo["path"] = str(_private_path(photo["path"], roots, "Dokumentationsfoto"))
        override = parameter["unconfirmedWeightingOverride"]
        if type(override) is not bool or type(c["erzwingeBerichtOhneBestaetigteBewertung"]) is not bool:
            raise ValueError("Die Override-Angabe ist ungültig.")
        if override and not c["erzwingeBerichtOhneBestaetigteBewertung"]:
            raise ValueError("Der Override wurde in den Berichtsparametern nicht freigegeben.")
    except (KeyError, TypeError, OverflowError) as error:
        raise ValueError("Die Berichtsparameter sind unvollständig oder falsch aufgebaut.") from error
    return parameter, roots, output, config, code, override


def generate_report(parameter_json: str) -> str:
    """Erzeugt synchron eine PDF; verständliche ValueErrors passieren die JNI-Grenze."""
    parameter, roots, output, config, code, override = _parse(parameter_json)
    days = []
    for source in parameter["days"]:
        samples, unconfirmed, modes = _load_samples(source, roots, override)
        metadata = source.get("stammdaten") or {}
        setting = str(metadata.get("innenAussen", "")).strip().lower()
        environment = "inside" if setting.startswith("innen") else (
            "outside" if setting.startswith(("außen", "aussen")) else "unknown"
        )
        # Fehlende Aufstellung ist keine stillschweigende Außenmessung. Der reine Kern hat
        # nur einen Innen-Schalter: True sperrt in diesem Fall beide Außenhochrechnungen.
        metrics = calculate_day_metrics(samples, date.fromisoformat(source["date"]), config,
                                        is_indoor=environment != "outside") if samples else None
        days.append(dict(source=source, metrics=metrics, environment=environment, modes=modes))
        del samples
    if not any(d["metrics"] is not None for d in days):
        raise ValueError("Im gewählten Zeitraum liegen keine Rohdaten für einen Bericht vor.")
    # Keine Teil-PDF als Erfolg und kein Überschreiben eines bestehenden Berichts bei Fehlern.
    partial = output.with_name(output.name + ".part")
    try:
        with _RENDER_LOCK:
            os.environ.setdefault("MPLCONFIGDIR", str(roots[0] / "matplotlib"))
            from laermbericht.pdf_pages import render_report
            render_report(partial, days, config, get_area(code), area_report_texts(code, config), override)
        partial.replace(output)
    except Exception as error:
        partial.unlink(missing_ok=True)
        if isinstance(error, ValueError):
            raise
        raise RuntimeError("Die PDF konnte nicht erstellt werden. Bitte Speicherplatz und Dokumentationsfotos prüfen.") from error
    return str(output)
