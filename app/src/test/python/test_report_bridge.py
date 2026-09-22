"""Dateihandling, Vertrag und wirkliche PDF-Ausgabe; keine Wiederholung der Kernformeltests."""
import json
from datetime import datetime, timezone
from pathlib import Path

import pytest
from pypdf import PdfReader

from report_bridge import configure_private_directories, generate_report


@pytest.fixture
def report_input(tmp_path):
    day = "2026-09-12"
    start = int(datetime(2026, 9, 12, 7, tzinfo=timezone.utc).timestamp()*1000)
    files_dir = tmp_path / "files"
    cache_dir = tmp_path / "cache"
    reports_dir = files_dir / "reports"
    handoff_dir = cache_dir / "report_handoff" / "test-run"
    reports_dir.mkdir(parents=True)
    handoff_dir.mkdir(parents=True)
    csv = handoff_dir / "samples.csv"
    csv.write_text("timestampMillis,levelDb,flags,sessionId,weighting,timeWeighting\n" +
                   "".join(f"{start+i*500},{60+i%3},0,7,A,FAST\n" for i in range(240)), encoding="utf-8")
    return {
        "_testPrivateDirectories": {"filesDir": str(files_dir), "cacheDir": str(cache_dir)},
        "contractVersion": 2, "timeZone": "UTC",
        "outputPath": str(reports_dir / "report.pdf"), "unconfirmedWeightingOverride": False,
        "reportConfig": {
            "gebietseinstufung": "MI", "tierSchwelleVollmessungProzent": 90,
            "tierSchwelleTeilerfassungProzent": 70, "schaetzpegelTeilerfassungDb": 50,
            "geraeteUnsicherheitDb": 1.4, "konservativFensterStartStunde": 15,
            "konservativFensterEndeStunde": 19, "erzwingeBerichtOhneBestaetigteBewertung": False,
        },
        "days": [{"date": day, "samplesPath": str(csv), "rawSampleCount": 240,
                  "stammdaten": {"id": 3, "innenAussen": "Außen", "messort": "Testort"},
                  "missingStammdatenFields": ["Kalibrierung"],
                  "sessions": [{"id": 7, "sha256": "a"*64}], "photos": []}],
    }


def _generate(parameter):
    private_directories = parameter["_testPrivateDirectories"]
    payload = {key: value for key, value in parameter.items() if key != "_testPrivateDirectories"}
    configure_private_directories(json.dumps(private_directories))
    return generate_report(json.dumps(payload))


def pdf_text(parameter):
    result = _generate(parameter)
    reader = PdfReader(result)
    assert len(reader.pages) >= 10
    return "\n".join(p.extract_text() for p in reader.pages)


def test_pdf_uses_selected_area_and_stored_hash(report_input):
    text = pdf_text(report_input)
    assert "Mischgebiet" in text and "TODO(Owner)" in text
    assert "a"*64 in text
    assert "Kalibrierung: nicht angegeben" in text
    assert "No module" not in text
    assert "WA-Charakter" not in text
    assert "55 dB(A)" not in text
    assert "VORBEHALT:" not in text
    assert not Path(report_input["outputPath"] + ".part").exists()


def test_unknown_setup_is_not_silently_outdoor(report_input):
    report_input["days"][0]["stammdaten"] = None
    report_input["days"][0]["missingStammdatenFields"] = ["Stammdaten insgesamt"]
    text = pdf_text(report_input)
    assert "Innen-/Außenlage unbekannt" in text
    assert "Keine dokumentierten Außenmessungen" in text
    assert "Stammdaten insgesamt" in text


def test_override_is_printed_on_every_page_and_imported_photo_marked(report_input):
    from PIL import Image
    photo = Path(report_input["days"][0]["samplesPath"]).parent / "photo.jpg"
    Image.new("RGB", (200, 100), color="navy").save(photo)
    report_input["unconfirmedWeightingOverride"] = True
    report_input["reportConfig"]["erzwingeBerichtOhneBestaetigteBewertung"] = True
    report_input["days"][0]["photos"] = [{"id": 8, "sessionId": 7, "path": str(photo),
        "category": "MESSAUFBAU", "capturedAt": 1789196400000, "imported": True,
        "sha256": "b"*64, "geometry": "Fenster "*200, "note": "Lange Notiz "*200}]
    _generate(report_input)
    pages = PdfReader(report_input["outputPath"]).pages
    assert all("VORBEHALT:" in p.extract_text() for p in pages)
    text = "\n".join(p.extract_text() for p in pages)
    assert "Nachträglich aus Galerie hinzugefügt" in text
    assert "b"*64 in text
    assert text.split().count("Lange") == 200
    assert text.split().count("Notiz") >= 200


@pytest.mark.parametrize("value", ["{broken", "null", "[]", "{}"])
def test_invalid_json_or_contract(value, report_input):
    configure_private_directories(json.dumps(report_input["_testPrivateDirectories"]))
    with pytest.raises(ValueError, match="JSON|Version"):
        generate_report(value)


@pytest.mark.parametrize("mutation,message", [
    (lambda p: p["reportConfig"].update(gebietseinstufung="NICHTBEKANNT"), "Unbekannte Gebietseinstufung"),
    (lambda p: p["days"][0].update(samplesPath=p["days"][0]["samplesPath"]+"missing"), "fehlt"),
    (lambda p: p["days"][0].update(
        samplesPath=str(Path(p["_testPrivateDirectories"]["cacheDir"]).parent / "escaped-samples.csv")
    ), "privaten App-Speichers"),
    (lambda p: p.update(
        outputPath=str(Path(p["_testPrivateDirectories"]["filesDir"]).parent / "escaped-report.pdf")
    ), "privaten App-Speichers"),
    (lambda p: p["days"][0].update(rawSampleCount=241), "unvollständig"),
    (lambda p: p.pop("timeZone"), "unvollständig"),
])
def test_error_paths_do_not_leave_partial_pdf(report_input, mutation, message):
    mutation(report_input)
    with pytest.raises(ValueError, match=message):
        _generate(report_input)
    assert not Path(report_input["outputPath"] + ".part").exists()


def test_real_unconfirmed_samples_cannot_bypass_override(report_input):
    path = Path(report_input["days"][0]["samplesPath"])
    path.write_text(path.read_text().replace(",A,FAST", ",,"))
    with pytest.raises(ValueError, match="Override fehlt"):
        _generate(report_input)


def test_known_c_weighting_cannot_be_relabelled_as_dba(report_input):
    path = Path(report_input["days"][0]["samplesPath"])
    path.write_text(path.read_text().replace(",A,FAST", ",C,FAST"))
    report_input["unconfirmedWeightingOverride"] = True
    report_input["reportConfig"]["erzwingeBerichtOhneBestaetigteBewertung"] = True
    with pytest.raises(ValueError, match="C-/andere"):
        _generate(report_input)


@pytest.mark.parametrize("value", ["", "Mars/Olympus", 123])
def test_time_zone_must_be_explicit_and_valid(report_input, value):
    report_input["timeZone"] = value
    with pytest.raises(ValueError, match="Zeitzone"):
        _generate(report_input)


def test_dates_must_be_unique(report_input):
    report_input["days"].append(dict(report_input["days"][0]))
    with pytest.raises(ValueError, match="eindeutig"):
        _generate(report_input)


def test_dates_must_be_sorted(report_input):
    later = dict(report_input["days"][0], date="2026-09-13")
    report_input["days"] = [later, report_input["days"][0]]
    with pytest.raises(ValueError, match="chronologisch"):
        _generate(report_input)


def test_date_must_use_canonical_iso_format(report_input):
    report_input["days"][0]["date"] = "20260912"
    with pytest.raises(ValueError, match="kanonisch"):
        _generate(report_input)


def test_parameter_json_cannot_expand_android_allowlist(report_input, tmp_path):
    attacker_root = tmp_path / "attacker-controlled"
    attacker_root.mkdir()
    report_input["privateRoots"] = [str(attacker_root)]
    report_input["outputPath"] = str(attacker_root / "escaped.pdf")
    with pytest.raises(ValueError, match="privaten App-Speichers"):
        _generate(report_input)


@pytest.mark.parametrize("day", ["2026-03-29", "2026-10-25"])
def test_local_calendar_boundaries_survive_dst_handoff(report_input, day):
    # Integrationsregression: 17–20 Uhr müssen auch am Umstellungstag drei Stunden bleiben.
    from zoneinfo import ZoneInfo
    from laermbericht.day_metrics import DayConfig, Sample, calculate_day_metrics
    from datetime import date
    zone = ZoneInfo("Europe/Berlin")
    target = date.fromisoformat(day)
    timestamp = int(datetime.combine(target, datetime.min.time()).replace(hour=17, tzinfo=zone).timestamp()*1000)
    config = DayConfig(.9, .7, 50, 55, 15, 19, 1.4, 55, 60, "Europe/Berlin")
    # Nachtpunkt trifft im Herbst die wiederholte 02-Uhr-Stunde.
    night = int(datetime.combine(target, datetime.min.time()).replace(hour=2, minute=30, tzinfo=zone).timestamp()*1000)
    metrics = calculate_day_metrics([Sample(night, 40, False, 1), Sample(timestamp, 60, False, 2)],
                                    target, config, is_indoor=False)
    assert metrics.conservative_active
    assert metrics.conservative_measurement_end_text == "17:00"
    assert metrics.conservative_coverage_day == pytest.approx((1 + 3*3600)/46800)
