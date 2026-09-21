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
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from laermbericht.areas import apply_area_to_config, area_report_texts, get_area
from laermbericht.day_metrics import DayConfig, Sample, calculate_day_metrics

_RENDER_LOCK = Lock()
_PRIVATE_DIRECTORIES = None


def _private_path(value, root, label):
    if not isinstance(value, str) or not Path(value).is_absolute():
        raise ValueError(f"{label}: Ein absoluter privater Dateipfad fehlt.")
    path = Path(value).resolve()
    if not path.is_relative_to(root):
        raise ValueError(f"{label}: Die Datei liegt außerhalb des privaten App-Speichers.")
    return path


def _load_samples(day, handoff_root, override):
    path = _private_path(day.get("samplesPath"), handoff_root, "Rohdaten")
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


def _parse_private_directories(private_directories_json):
    try:
        directories = json.loads(private_directories_json)
    except (ValueError, TypeError):
        raise ValueError("Die privaten Android-Verzeichnisse sind kein gültiges JSON.") from None
    if not isinstance(directories, dict) or set(directories) != {"filesDir", "cacheDir"}:
        raise ValueError("Die privaten Android-Verzeichnisse sind unvollständig.")

    canonical = {}
    for key, label in (("filesDir", "Dateiverzeichnis"), ("cacheDir", "Cacheverzeichnis")):
        value = directories[key]
        if not isinstance(value, str) or not Path(value).is_absolute():
            raise ValueError(f"{label}: Ein absoluter privater Verzeichnispfad fehlt.")
        canonical[key] = Path(value).resolve()
    if canonical["filesDir"] == canonical["cacheDir"]:
        raise ValueError("Datei- und Cacheverzeichnis dürfen nicht identisch sein.")
    return (
        (canonical["filesDir"] / "reports").resolve(),
        (canonical["cacheDir"] / "report_handoff").resolve(),
        canonical["cacheDir"],
    )


def configure_private_directories(private_directories_json: str) -> None:
    """Setzt die Android-Allowlist getrennt vom untrusted Berichtsparameter-JSON."""
    global _PRIVATE_DIRECTORIES
    _PRIVATE_DIRECTORIES = _parse_private_directories(private_directories_json)


def _parse_iso_date(value, index):
    if not isinstance(value, str):
        raise ValueError(f"Berichtstag {index + 1}: Das Datum muss im ISO-Format YYYY-MM-DD vorliegen.")
    try:
        parsed = date.fromisoformat(value)
    except ValueError:
        raise ValueError(f"Berichtstag {index + 1}: Das Datum ist kein gültiges ISO-Datum.") from None
    if parsed.isoformat() != value:
        raise ValueError(f"Berichtstag {index + 1}: Das Datum muss kanonisch als YYYY-MM-DD vorliegen.")
    return parsed


def _parse(parameter_json):
    try:
        parameter = json.loads(parameter_json)
    except (ValueError, TypeError):
        raise ValueError("Die Berichtsparameter sind kein gültiges JSON.") from None
    if not isinstance(parameter, dict) or parameter.get("contractVersion") != 2:
        raise ValueError("Die Version der Berichtsparameter ist ungültig; bitte erneut exportieren.")
    if _PRIVATE_DIRECTORIES is None:
        raise ValueError("Die privaten Android-Verzeichnisse wurden nicht konfiguriert.")
    reports_root, handoff_root, cache_root = _PRIVATE_DIRECTORIES
    try:
        output = _private_path(parameter["outputPath"], reports_root, "PDF-Ausgabe")
        if output.suffix.lower() != ".pdf" or not output.parent.is_dir():
            raise ValueError("Der private PDF-Ausgabeordner fehlt oder der Dateiname ist ungültig.")
        parameter["outputPath"] = str(output)
        time_zone = parameter["timeZone"]
        if not isinstance(time_zone, str) or not time_zone:
            raise ValueError("Die Zeitzone ist ungültig.")
        try:
            ZoneInfo(time_zone)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError(f"Die Zeitzone '{time_zone}' ist unbekannt.") from None
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
            time_zone=time_zone,
        ), code)
        days = parameter["days"]
        if not isinstance(days, list) or not days:
            raise ValueError("Es wurden keine Berichtstage übergeben.")
        dates = []
        seen_dates = set()
        for index, d in enumerate(days):
            if not isinstance(d, dict):
                raise ValueError(f"Berichtstag {index + 1}: Die Tagesdaten sind ungültig.")
            parsed_date = _parse_iso_date(d.get("date"), index)
            if parsed_date in seen_dates:
                raise ValueError("Die Berichtstage müssen eindeutig sein.")
            if dates and parsed_date < dates[-1]:
                raise ValueError("Die Berichtstage müssen chronologisch sortiert sein.")
            seen_dates.add(parsed_date)
            dates.append(parsed_date)
            if not isinstance(d["rawSampleCount"], int) or d["rawSampleCount"] < 0:
                raise ValueError("Die Rohdatenanzahl ist ungültig.")
            if d.get("stammdaten") is not None and not isinstance(d["stammdaten"], dict):
                raise ValueError("Die Stammdaten sind ungültig.")
            d["samplesPath"] = str(_private_path(d.get("samplesPath"), handoff_root, "Rohdaten"))
            for photo in d.get("photos", []):
                photo["path"] = str(_private_path(photo["path"], handoff_root, "Dokumentationsfoto"))
        override = parameter["unconfirmedWeightingOverride"]
        if type(override) is not bool or type(c["erzwingeBerichtOhneBestaetigteBewertung"]) is not bool:
            raise ValueError("Die Override-Angabe ist ungültig.")
        if override and not c["erzwingeBerichtOhneBestaetigteBewertung"]:
            raise ValueError("Der Override wurde in den Berichtsparametern nicht freigegeben.")
    except (KeyError, TypeError, OverflowError) as error:
        raise ValueError("Die Berichtsparameter sind unvollständig oder falsch aufgebaut.") from error
    return parameter, handoff_root, cache_root, output, config, code, override


def generate_report(parameter_json: str) -> str:
    """Erzeugt synchron eine PDF; verständliche ValueErrors passieren die JNI-Grenze."""
    parameter, handoff_root, cache_root, output, config, code, override = _parse(parameter_json)
    days = []
    for source in parameter["days"]:
        samples, unconfirmed, modes = _load_samples(source, handoff_root, override)
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
            matplotlib_root = (cache_root / "matplotlib").resolve()
            matplotlib_root.mkdir(parents=True, exist_ok=True)
            os.environ["MPLCONFIGDIR"] = str(matplotlib_root)
            from laermbericht.pdf_pages import render_report
            render_report(partial, days, config, get_area(code), area_report_texts(code, config), override)
        partial.replace(output)
    except Exception as error:
        partial.unlink(missing_ok=True)
        if isinstance(error, ValueError):
            raise
        raise RuntimeError("Die PDF konnte nicht erstellt werden. Bitte Speicherplatz und Dokumentationsfotos prüfen.") from error
    return str(output)
