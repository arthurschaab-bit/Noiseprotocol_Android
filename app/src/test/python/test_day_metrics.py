from __future__ import annotations

from dataclasses import replace
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd
import pytest
from laermbericht import (
    DayConfig,
    Sample,
    calculate_day_metrics,
    calculate_rating_level,
    get_tier,
    resample_to_one_hz,
)

TEST_DAY = date(2026, 6, 1)
TEST_ZONE = ZoneInfo("Europe/Berlin")


@pytest.fixture
def config() -> DayConfig:
    return DayConfig(
        coverage_valid=0.90,
        coverage_partial=0.70,
        partial_estimate_db=50.0,
        window_estimate_db=55.0,
        conservative_window_start_hour=15,
        conservative_window_end_hour=19,
        device_uncertainty_db=1.4,
        day_guideline_db=55.0,
        day_intervention_db=60.0,
        time_zone="Europe/Berlin",
    )


def test_get_tier_checks_both_boundaries() -> None:
    assert get_tier(0.900001, 0.90, 0.70) == "valid"
    assert get_tier(0.90, 0.90, 0.70) == "valid"
    assert get_tier(0.899999, 0.90, 0.70) == "partial"
    assert get_tier(0.700001, 0.90, 0.70) == "partial"
    assert get_tier(0.70, 0.90, 0.70) == "partial"
    assert get_tier(0.699999, 0.90, 0.70) == "window"


def test_resampling_uses_energy_mean_and_separate_peak_channel() -> None:
    start = datetime(2026, 6, 1, 12, 0, tzinfo=TEST_ZONE)
    samples = [
        _sample(start, 50.0),
        _sample(start + timedelta(milliseconds=500), 70.0),
        _sample(start + timedelta(milliseconds=750), 99.0, is_gap=True),
        _sample(start + timedelta(seconds=1), 60.0),
        _sample(start + timedelta(seconds=2), 80.0, is_gap=True),
    ]

    grid = resample_to_one_hz(samples, TEST_DAY, "Europe/Berlin")

    expected_energy_mean = 10.0 * np.log10((10.0**5 + 10.0**7) / 2.0)
    assert grid.energy_level_db.iloc[0] == pytest.approx(expected_energy_mean)
    assert grid.peak_level_db.iloc[0] == pytest.approx(70.0)
    assert grid.energy_level_db.iloc[1] == pytest.approx(60.0)
    assert np.isnan(grid.energy_level_db.iloc[2])
    assert np.isnan(grid.peak_level_db.iloc[2])


def test_full_constant_day_has_exact_laeq_and_valid_tier(config: DayConfig) -> None:
    start = datetime(2026, 6, 1, 7, 0, tzinfo=TEST_ZONE)
    samples = _constant_samples(start, 13 * 3600, 55.0)

    result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=False)

    assert result.day_seconds == 46_800
    assert result.coverage_day == pytest.approx(1.0)
    assert result.tier == "valid"
    assert result.laeq_day_db == pytest.approx(55.0)
    assert result.l1_day_db == pytest.approx(55.0)
    assert result.highest_db == pytest.approx(55.0)
    assert result.rating_level_day_db == pytest.approx(55.0)
    assert result.impulse_adjustment_day_db == pytest.approx(0.0)
    assert result.seconds_above_guideline == 0
    assert result.seconds_above_intervention == 0
    assert result.laeq_1min_db.iloc[-1] == pytest.approx(55.0)
    assert result.laeq_day_label.startswith("► LAeq Tag")
    assert result.coverage_supplement_text.startswith(
        "Vollständig erfasster Tages-LAeq"
    )


def test_gap_rows_reduce_coverage_without_changing_laeq(config: DayConfig) -> None:
    start = datetime(2026, 6, 1, 7, 0, tzinfo=TEST_ZONE)
    samples = [
        _sample(
            start + timedelta(seconds=offset),
            90.0 if offset % 10 == 0 else 60.0,
            is_gap=offset % 10 == 0,
        )
        for offset in range(13 * 3600)
    ]

    result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=False)

    assert result.day_seconds == 42_120
    assert result.coverage_day == pytest.approx(0.90)
    assert result.tier == "valid"
    assert result.laeq_day_db == pytest.approx(60.0)


def test_window_tier_uses_conservative_rest_of_day_estimate(config: DayConfig) -> None:
    start = datetime(2026, 6, 1, 12, 0, tzinfo=TEST_ZONE)
    samples = _constant_samples(start, 5 * 3600 + 1, 65.0)

    result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=False)

    expected_gap_seconds = 3 * 3600
    expected = 10.0 * np.log10(
        (result.day_seconds * 10.0**6.5 + expected_gap_seconds * 10.0**5.5)
        / (result.day_seconds + expected_gap_seconds),
    )
    assert result.tier == "window"
    assert result.conservative_active is True
    assert result.conservative_measurement_end_text == "17:00"
    assert result.conservative_laeq_day_db == pytest.approx(expected)
    assert result.conservative_coverage_day == pytest.approx(
        (result.day_seconds + expected_gap_seconds) / 46_800,
    )
    assert result.high_coverage_estimate_active is False
    assert result.laeq_day_label.startswith("(M)")

    indoor_result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=True)
    assert indoor_result.conservative_active is False


def test_partial_tier_uses_full_gap_estimate_not_window_rule(config: DayConfig) -> None:
    start = datetime(2026, 6, 1, 7, 0, tzinfo=TEST_ZONE)
    measured_seconds = 46_800 * 7 // 10
    samples = _constant_samples(start, measured_seconds, 60.0)

    result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=False)

    expected = 10.0 * np.log10(
        (measured_seconds * 10.0**6 + (46_800 - measured_seconds) * 10.0**5) / 46_800,
    )
    assert result.tier == "partial"
    assert result.high_coverage_estimate_active is True
    assert result.high_coverage_laeq_day_db == pytest.approx(expected)
    assert result.conservative_active is False
    assert result.laeq_day_label.startswith("(~)")
    assert "Nicht erfasst" in result.coverage_supplement_text


def test_before_day_and_after_day_windows_are_calculated_separately(
    config: DayConfig,
) -> None:
    samples = [
        _sample(datetime(2026, 6, 1, 6, 59, 59, tzinfo=TEST_ZONE), 50.0),
        _sample(datetime(2026, 6, 1, 7, 0, tzinfo=TEST_ZONE), 60.0),
        _sample(datetime(2026, 6, 1, 20, 0, tzinfo=TEST_ZONE), 70.0),
    ]

    result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=False)

    assert result.before_7_seconds == 1
    assert result.day_seconds == 1
    assert result.after_20_seconds == 1
    assert result.laeq_before_7_db == pytest.approx(50.0)
    assert result.laeq_day_db == pytest.approx(60.0)
    assert result.laeq_after_20_db == pytest.approx(70.0)
    assert result.lmax_before_7_db == pytest.approx(50.0)
    assert result.lmax_after_20_db == pytest.approx(70.0)


def test_rating_level_uses_five_second_peak_blocks() -> None:
    levels = pd.Series([50.0, 50.0, 50.0, 50.0, 70.0, 60.0, 60.0, 60.0, 60.0, 60.0])
    laeq = 55.0

    rating_level, impulse_adjustment = calculate_rating_level(levels, laeq)

    expected = 10.0 * np.log10((10.0**7 + 10.0**6) / 2.0)
    assert rating_level == pytest.approx(expected)
    assert impulse_adjustment == pytest.approx(expected - laeq)


def test_two_hz_peak_is_preserved_for_peak_sensitive_metrics(config: DayConfig) -> None:
    start = datetime(2026, 6, 1, 12, 0, tzinfo=TEST_ZONE)
    samples = []
    for second in range(10):
        timestamp = start + timedelta(seconds=second)
        samples.append(_sample(timestamp, 50.0))
        samples.append(
            _sample(
                timestamp + timedelta(milliseconds=500),
                80.0 if second == 4 else 50.0,
            ),
        )

    result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=False)

    assert result.highest_db == pytest.approx(80.0)
    assert result.peak_level_1hz_db.iloc[4] == pytest.approx(80.0)
    assert result.energy_level_1hz_db.iloc[4] < 80.0
    first_takt = 80.0
    second_takt = 50.0
    expected_rating = 10.0 * np.log10(
        (10.0 ** (first_takt / 10) + 10.0 ** (second_takt / 10)) / 2
    )
    assert result.rating_level_day_db == pytest.approx(expected_rating)


def test_matches_original_reference_for_one_hz_synthetic_signal(
    config: DayConfig,
) -> None:
    """Golden Values aus Referenzcommit 646c7c7, direkt mit compute_day erzeugt."""

    start = datetime(2026, 6, 1, 8, 0, tzinfo=TEST_ZONE)
    samples = [
        _sample(
            start + timedelta(seconds=offset),
            75.0 if offset % 300 == 0 else 55.0 + (offset % 10) * 0.1,
        )
        for offset in range(4 * 3600)
    ]

    result = calculate_day_metrics(samples, TEST_DAY, config, is_indoor=False)

    assert result.day_seconds == 14_400
    assert result.coverage_day == pytest.approx(0.3076923076923077)
    assert result.tier == "window"
    assert result.laeq_day_db == pytest.approx(56.588454327245834)
    assert result.l1_day_db == pytest.approx(55.9)
    assert result.highest_db == pytest.approx(75.0)
    assert result.percent_above_guideline == pytest.approx(90.33333333333333)
    assert result.seconds_above_guideline == 13_008
    assert result.seconds_above_intervention == 48
    assert result.measured_minutes == 240
    assert result.rating_level_day_db == pytest.approx(59.48979099981874)
    assert result.impulse_adjustment_day_db == pytest.approx(2.901336672572903)
    assert result.loudest_hour_db == pytest.approx(56.75026353117504)
    assert result.loudest_hour_text == "07:30–08:30"
    assert result.loudest_hour_coverage_seconds == 1_801


def test_invalid_config_is_rejected_before_calculation(config: DayConfig) -> None:
    invalid = replace(config, coverage_partial=0.95)
    samples = [_sample(datetime(2026, 6, 1, 12, 0, tzinfo=TEST_ZONE), 55.0)]

    with pytest.raises(ValueError, match="Abdeckungsschwellen"):
        calculate_day_metrics(samples, TEST_DAY, invalid, is_indoor=False)


def _constant_samples(start: datetime, seconds: int, level_db: float) -> list[Sample]:
    return [
        _sample(start + timedelta(seconds=offset), level_db)
        for offset in range(seconds)
    ]


def _sample(
    timestamp: datetime,
    level_db: float,
    *,
    is_gap: bool = False,
    session_id: str = "session-1",
) -> Sample:
    return Sample(
        timestamp_epoch_ms=int(timestamp.timestamp() * 1000),
        level_db=level_db,
        is_gap=is_gap,
        session_id=session_id,
    )
