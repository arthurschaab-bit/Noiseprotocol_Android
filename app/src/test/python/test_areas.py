"""Quellenwerte, sichere Fehlerpfade und Vertrag mit der Kotlin-Auswahl."""

from dataclasses import replace
from datetime import date, datetime, timedelta
from pathlib import Path
import re
from zoneinfo import ZoneInfo

import pytest

from laermbericht import DayConfig, Sample, calculate_day_metrics
from laermbericht.areas import AREA_TYPES, apply_area_to_config, area_report_texts, get_area


# AVV 19.08.1970 Nr. 3.1.1 a–e; Zuordnung Jena, Februar 2021, Seite 1.
VERIFIED = [("WA", 55, 40), ("WR", 50, 35), ("MI", 60, 45), ("GE", 65, 50), ("GI", 70, 70)]
UNVERIFIED = ["WS", "WB", "MD", "MDW", "MU", "MK"]


@pytest.fixture
def config():
    return DayConfig(0.9, 0.7, 50.0, 55.0, 15, 19, 1.4, 55.0, 60.0, "Europe/Berlin")


@pytest.mark.parametrize("code,day,night", VERIFIED)
def test_verified_values_and_general_intervention_rule(code, day, night):
    limits = get_area(code).limits
    assert (limits.day_db, limits.night_db) == (day, night)
    assert limits.day_intervention_db == day + 5
    assert limits.night_intervention_db == night + 5
    assert limits.night_peak_db == night + 20
    assert "19081970" in limits.source
    assert "02_2021" in limits.mapping_source


@pytest.mark.parametrize("code", UNVERIFIED)
def test_unverified_types_have_no_usable_values(code, config):
    assert get_area(code).limits is None
    assert "TODO(Owner)" in get_area(code).qualification_todo
    with pytest.raises(ValueError, match="TODO\\(Owner\\)"):
        apply_area_to_config(config, code)
    with pytest.raises(ValueError, match="TODO\\(Owner\\)"):
        area_report_texts(code, config)


@pytest.mark.parametrize("code", ["", " ", None, "WAA", "WA/MI", "Allgemeines Wohngebiet (WA)"])
def test_unknown_and_ambiguous_input_never_defaults_to_wa(code):
    with pytest.raises(ValueError):
        get_area(code)


def test_canonical_code_and_immutable_registry():
    assert get_area(" mi ") is AREA_TYPES["MI"]
    with pytest.raises(TypeError):
        AREA_TYPES["MI"] = AREA_TYPES["WA"]


@pytest.mark.parametrize("code,day,night", VERIFIED)
def test_texts_use_selected_area_and_keep_qualification_open(code, day, night, config):
    texts = area_report_texts(code, replace(config, partial_estimate_db=72.0))
    assert code in texts["heading"]
    assert get_area(code).baunvo_section in texts["heading"]
    assert f"Tag 07–20 Uhr {day} dB(A)" in texts["guidelines"]
    assert f"Nacht 20–07 Uhr {night} dB(A)" in texts["guidelines"]
    assert f"tags {day + 5} dB(A)" in texts["intervention"]
    assert f"nachts {night + 5} dB(A)" in texts["intervention"]
    assert f"über {night + 20} dB(A)" in texts["night_peak"]
    assert "TODO(Owner)" in texts["qualification"]
    assert code in texts["qualification"]
    assert "72 dB(A)" in texts["partial_estimate"]
    assert "unterhalb" not in texts["partial_estimate"]
    assert texts["guideline_duration_label"] == f"Zeit > {day} dB(A)"
    if code != "WA":
        assert "WA" not in " ".join(texts.values())


@pytest.mark.parametrize("code,day,night", VERIFIED)
def test_area_replaces_all_three_dependent_config_values(code, day, night, config):
    result = apply_area_to_config(config, code)
    assert result.day_guideline_db == day
    assert result.day_intervention_db == day + 5
    assert result.window_estimate_db == day
    assert result.partial_estimate_db == config.partial_estimate_db
    assert result.device_uncertainty_db == config.device_uncertainty_db
    assert config.window_estimate_db == 55.0  # Eingabe bleibt unverändert.


def test_non_wa_threshold_is_strict_and_drives_real_core(config):
    start = datetime(2026, 6, 1, 12, tzinfo=ZoneInfo("Europe/Berlin"))
    samples = [Sample(int((start + timedelta(seconds=i)).timestamp() * 1000), level, False, "s")
               for i, level in enumerate([60.0, 65.0, 65.01])]
    result = calculate_day_metrics(samples, date(2026, 6, 1), apply_area_to_config(config, "MI"), is_indoor=False)
    assert result.seconds_above_guideline == 2
    assert result.seconds_above_intervention == 1


def test_kotlin_selection_and_python_registry_cannot_drift():
    source = Path(__file__).parents[2] / "main/java/com/example/lrmprotokoll/report/ReportArea.kt"
    entries = re.findall(
        r'^    (\w+)\("([^"]+)", (true|false)\)',
        source.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    assert {code: (label, ready == "true") for code, label, ready in entries} == {
        code: (area.name, area.limits is not None) for code, area in AREA_TYPES.items()
    }
