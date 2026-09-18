"""Portable Tageskennwerte für den High-End-Lärmbericht.

Der Port basiert auf ``compute_day`` in
``arthurschaab-bit/Baul-rm/pipeline/Verknuepfung/scripts/gesamtbericht_lib_v3.py``
(Referenzcommit 646c7c7e7edfa87332d1e6783767f626f67741dd, Zeilen 607-822).
Kontext und Abgrenzung stehen in ``docs/DATENMAPPING_BERICHT_SCHRITT4.md`` und
``docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md``.

Abtastraten-Entscheidung für die etwa 2 Hz des PCE-323: Vor der portierten
Kernlogik entsteht ein striktes 1-Hz-Raster. Energiegetriebene Kennwerte nutzen
je Sekunde den energetischen Mittelwert aller gültigen Frames. Parallel bleibt
je Sekunde der Maximalwert erhalten; Höchstwert, L1 und das 5-Sekunden-
Taktmaximum werden daraus berechnet. Dadurch senkt die Verdichtung kurze Spitzen
nicht systematisch ab. GAP-Frames werden aus beiden Reihen ausgeschlossen.
"""

from __future__ import annotations

from collections.abc import Hashable, Sequence
from dataclasses import dataclass
from datetime import date
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import numpy as np
import pandas as pd

TAG_REFERENCE_SECONDS = 13 * 60 * 60
GAP_MIN_SECONDS = 120
DEFAULT_LOUDEST_HOUR_MIN_SECONDS = 30 * 60


@dataclass(frozen=True)
class Sample:
    """Ein Rohwert aus ``MeasurementEntity`` vor der 1-Hz-Verdichtung."""

    timestamp_epoch_ms: int
    level_db: float
    is_gap: bool
    session_id: Hashable


@dataclass(frozen=True)
class DayConfig:
    """Alle konfigurierbaren Parameter der portierten Tagesberechnung."""

    coverage_valid: float
    coverage_partial: float
    partial_estimate_db: float
    window_estimate_db: float
    conservative_window_start_hour: int
    conservative_window_end_hour: int
    device_uncertainty_db: float
    day_guideline_db: float
    day_intervention_db: float
    time_zone: str
    loudest_hour_min_seconds: int = DEFAULT_LOUDEST_HOUR_MIN_SECONDS


SessionPeriod = tuple[pd.Timestamp, pd.Timestamp]


@dataclass(frozen=True)
class SecondGrid:
    """Striktes 1-Hz-Raster mit getrennten Energie- und Peak-Reihen."""

    energy_level_db: pd.Series
    peak_level_db: pd.Series
    sessions: tuple[SessionPeriod, ...]


@dataclass(frozen=True)
class DayMetrics:
    """Datenzugriffsfreies Ergebnis des portierten ``compute_day``-Kerns."""

    day: date
    is_indoor: bool
    tier: str
    coverage_day: float
    day_seconds: int
    before_7_seconds: int
    after_20_seconds: int
    laeq_day_db: float
    laeq_before_7_db: float
    laeq_after_20_db: float
    l1_day_db: float
    highest_db: float
    lmax_before_7_db: float
    lmax_after_20_db: float
    percent_above_guideline: float
    seconds_above_guideline: int
    seconds_above_intervention: int
    measured_minutes: int
    rating_level_day_db: float
    impulse_adjustment_day_db: float
    loudest_hour_db: float
    loudest_hour_text: str
    loudest_hour_coverage_seconds: int
    loudest_hour_end: pd.Timestamp | None
    conservative_active: bool
    conservative_laeq_day_db: float
    conservative_coverage_day: float
    conservative_measurement_end_text: str
    high_coverage_estimate_active: bool
    high_coverage_laeq_day_db: float
    laeq_day_label: str
    coverage_supplement_text: str
    measurement_period_text: str
    energy_level_1hz_db: pd.Series
    peak_level_1hz_db: pd.Series
    laeq_1min_db: pd.Series
    sessions: tuple[SessionPeriod, ...]


def get_tier(coverage: float, valid_threshold: float, partial_threshold: float) -> str:
    """Ordnet die Abdeckung wie das Original ein (Referenzzeilen 558-563)."""

    if coverage >= valid_threshold:
        return "valid"
    if coverage >= partial_threshold:
        return "partial"
    return "window"


def resample_to_one_hz(
    samples: Sequence[Sample],
    day: date,
    time_zone: str,
) -> SecondGrid:
    """Verdichtet PCE-323-Frames vor dem Port auf ein striktes 1-Hz-Raster.

    Das Raster entspricht der ``reindex(freq="1s")``-Vorbedingung des Originals
    (Referenzzeilen 641-646). Der Energiekanal bildet pro Sekunde den
    energetischen Mittelwert, der Peak-Kanal das Maximum. Eine GAP-Zeile trägt
    zu keinem Kanal bei. Existiert in derselben Sekunde mindestens ein gültiger
    Frame, gilt diese Sekunde als gemessen.
    """

    if not samples:
        raise ValueError("Für den Messtag wurden keine Rohwerte übergeben.")

    zone = _load_time_zone(time_zone)
    rows: list[dict[str, object]] = []
    for sample in samples:
        if isinstance(sample.timestamp_epoch_ms, bool) or not isinstance(
            sample.timestamp_epoch_ms,
            (int, np.integer),
        ):
            raise TypeError("Jeder Zeitstempel muss als Epoch-Millisekunde vorliegen.")
        if not sample.is_gap and not np.isfinite(sample.level_db):
            raise ValueError(
                "Ein gültiger Rohwert muss einen endlichen Pegel enthalten."
            )
        try:
            hash(sample.session_id)
        except TypeError as error:
            raise ValueError(
                "Die Session-ID eines Rohwerts muss hashbar sein."
            ) from error
        timestamp = pd.Timestamp(
            sample.timestamp_epoch_ms, unit="ms", tz="UTC"
        ).tz_convert(zone)
        if timestamp.date() != day:
            continue
        rows.append(
            {
                "timestamp": timestamp,
                "level_db": float(sample.level_db),
                "is_gap": bool(sample.is_gap),
                "session_id": sample.session_id,
            },
        )

    if not rows:
        raise ValueError(
            f"Für den ausgewählten Messtag {day.isoformat()} liegen keine Rohwerte vor.",
        )

    frame = pd.DataFrame(rows).sort_values("timestamp", kind="stable")
    frame["second"] = frame["timestamp"].map(lambda value: value.floor("s"))
    valid = frame.loc[~frame["is_gap"]].copy()

    start = frame["second"].min()
    end = frame["second"].max()
    full_index = pd.date_range(start=start, end=end, freq="1s")
    energy_level = pd.Series(
        np.nan, index=full_index, dtype=float, name="energy_level_db"
    )
    peak_level = pd.Series(np.nan, index=full_index, dtype=float, name="peak_level_db")

    if not valid.empty:
        valid["energy"] = np.power(10.0, valid["level_db"] / 10.0)
        energy_per_second = valid.groupby("second", sort=True)["energy"].mean()
        peak_per_second = valid.groupby("second", sort=True)["level_db"].max()
        energy_level.loc[energy_per_second.index] = 10.0 * np.log10(energy_per_second)
        peak_level.loc[peak_per_second.index] = peak_per_second

    sessions = tuple(
        sorted(
            (
                group["timestamp"].min().floor("s"),
                group["timestamp"].max().floor("s"),
            )
            for _, group in frame.groupby("session_id", sort=False)
        ),
    )
    return SecondGrid(
        energy_level_db=energy_level, peak_level_db=peak_level, sessions=sessions
    )


def calculate_rating_level(
    peak_levels_db: pd.Series | Sequence[float],
    laeq_db: float,
) -> tuple[float, float]:
    """Berechnet Taktmaximalpegel und Impulszuschlag (Referenzzeilen 674-679).

    Die Eingabe ist bereits die strikt sekundenweise Peak-Reihe. Damit bestehen
    die unverändert portierten Fünfergruppen aus fünf Sekunden statt aus
    fünf Rohframes des etwa 2-Hz-Geräts.
    """

    values = np.asarray(peak_levels_db, dtype=float)
    values = values[~np.isnan(values)]
    if len(values) < 5 or np.isnan(laeq_db):
        return float("nan"), float("nan")
    usable_count = (len(values) // 5) * 5
    taktmaxima = values[:usable_count].reshape(-1, 5).max(axis=1)
    rating_level = 10.0 * np.log10(np.mean(np.power(10.0, taktmaxima / 10.0)))
    return float(rating_level), float(rating_level - laeq_db)


def coverage_label_short(
    laeq_db: float,
    coverage: float,
    sessions: Sequence[SessionPeriod],
    day_start: pd.Timestamp,
    valid_threshold: float,
    partial_threshold: float,
) -> str:
    """Portiert die kurze Abdeckungskennzeichnung (Referenzzeilen 563-586)."""

    if np.isnan(laeq_db):
        return "LAeq: —"
    percentage = round(coverage * 100)
    gaps, within = _get_gaps(sessions, day_start)
    tier = get_tier(coverage, valid_threshold, partial_threshold)
    if tier == "valid":
        return f"► LAeq Tag (07–20h): {laeq_db:.1f} dB(A)  [Abdeckung {percentage}%]"
    if tier == "partial":
        gap_text = ""
        if gaps:
            gap_text = " — Lücke(n): " + "; ".join(
                f"{start:%H:%M}–{end:%H:%M} "
                f"({_format_duration(int((end - start).total_seconds()))})"
                for start, end in gaps
            )
        return (
            f"(~) LAeq Teilerfassung 07–20h: {laeq_db:.1f} dB(A) — "
            f"{percentage}% erfasst{gap_text}"
        )
    if len(within) >= 2:
        segments = " + ".join(f"{start:%H:%M}–{end:%H:%M}" for start, end in within)
        return (
            f"(M) LAeq {len(within)} Segmente ({segments}): "
            f"{laeq_db:.1f} dB(A) — {percentage}%"
        )
    if within:
        segment = within[0]
    elif sessions:
        segment = sessions[0][0], sessions[-1][1]
    else:
        return f"(M) LAeq Messfenster: {laeq_db:.1f} dB(A) — {percentage}%"
    return (
        f"(M) LAeq Messfenster {segment[0]:%H:%M}–{segment[1]:%H:%M}: "
        f"{laeq_db:.1f} dB(A) — {percentage}%"
    )


def coverage_supplement(
    coverage: float,
    sessions: Sequence[SessionPeriod],
    day_start: pd.Timestamp,
    day_seconds: int,
    valid_threshold: float,
    partial_threshold: float,
) -> str:
    """Portiert den Abdeckungstext mit Lückenliste (Referenzzeilen 588-606)."""

    gaps, _ = _get_gaps(sessions, day_start)
    percentage = round(coverage * 100)
    tier = get_tier(coverage, valid_threshold, partial_threshold)
    if tier == "valid":
        return (
            "Vollständig erfasster Tages-LAeq — "
            f"{percentage}% des Bezugszeitraums 07–20h erfasst."
        )
    if tier == "partial":
        if gaps:
            gap_text = "; ".join(
                f"{start:%H:%M}–{end:%H:%M} "
                f"({_format_duration(int((end - start).total_seconds()))})"
                for start, end in gaps
            )
        else:
            gap_text = "keine Lücken erkennbar"
        return (
            f"Weitgehend repräsentativ für Tagesmittel ({percentage}% Abdeckung). "
            f"Nicht erfasst: {gap_text}."
        )
    measured_hours = round(day_seconds / 3600.0, 1)
    gap_strings = [
        f"{start:%H:%M}–{end:%H:%M} ({_format_duration(int((end - start).total_seconds()))})"
        for start, end in gaps
    ]
    uncovered = ", ".join(gap_strings) if gap_strings else "—"
    return (
        f"LAeq für das gemessene Fenster ({measured_hours:.1f}h = {percentage}%). "
        f"Nicht Tagesdurchschnitt. Nicht erfasst: {uncovered}."
    )


def calculate_day_metrics(
    samples: Sequence[Sample],
    day: date,
    config: DayConfig,
    *,
    is_indoor: bool,
) -> DayMetrics:
    """Portiert den datenfreien Teil von ``compute_day`` (Referenzzeilen 646-822)."""

    _validate_config(config)
    grid = resample_to_one_hz(samples, day, config.time_zone)
    day_start = pd.Timestamp(day).tz_localize(_load_time_zone(config.time_zone))
    day_window_end = day_start + pd.Timedelta(hours=20)

    levels = grid.energy_level_db
    peaks = grid.peak_level_db
    energy = np.power(10.0, levels / 10.0)
    laeq_1min = 10.0 * np.log10(energy.rolling(60, min_periods=20).mean())
    timestamps = levels.index
    hour = timestamps.hour + timestamps.minute / 60.0
    day_mask = (hour >= 7) & (hour < 20)
    before_7_mask = timestamps.hour < 7
    after_20_mask = timestamps.hour >= 20

    day_seconds = int(levels.loc[day_mask].notna().sum())
    before_7_seconds = int(levels.loc[before_7_mask].notna().sum())
    after_20_seconds = int(levels.loc[after_20_mask].notna().sum())
    coverage_day = day_seconds / TAG_REFERENCE_SECONDS

    laeq_day = _leq_of(energy.loc[day_mask])
    laeq_before_7 = _leq_of(energy.loc[before_7_mask])
    laeq_after_20 = _leq_of(energy.loc[after_20_mask])
    day_levels = levels.loc[day_mask].dropna()
    day_peaks = peaks.loc[day_mask].dropna()

    highest = float(peaks.max()) if peaks.notna().any() else float("nan")
    l1_day = (
        float(np.nanpercentile(day_peaks.to_numpy(), 99))
        if len(day_peaks)
        else float("nan")
    )
    lmax_before_7 = (
        float(peaks.loc[before_7_mask].max()) if before_7_seconds > 0 else float("nan")
    )
    lmax_after_20 = (
        float(peaks.loc[after_20_mask].max()) if after_20_seconds > 0 else float("nan")
    )

    if len(day_levels):
        percent_above_guideline = 100.0 * float(
            (day_levels > config.day_guideline_db).mean()
        )
        seconds_above_guideline = int((day_levels > config.day_guideline_db).sum())
        seconds_above_intervention = int(
            (day_levels > config.day_intervention_db).sum()
        )
    else:
        percent_above_guideline = 0.0
        seconds_above_guideline = 0
        seconds_above_intervention = 0
    measured_minutes = day_seconds // 60

    rating_level, impulse_adjustment = calculate_rating_level(day_peaks, laeq_day)
    loudest_hour_series = 10.0 * np.log10(
        energy.rolling(3600, min_periods=config.loudest_hour_min_seconds).mean(),
    )
    if loudest_hour_series.notna().any():
        loudest_hour_end = loudest_hour_series.idxmax()
        loudest_hour_db = float(loudest_hour_series.max())
        loudest_hour_start = loudest_hour_end - pd.Timedelta(seconds=3599)
        loudest_hour_text = f"{loudest_hour_start.strftime('%H:%M')}–{loudest_hour_end.strftime('%H:%M')}"
        loudest_hour_coverage = int(
            levels.loc[
                (timestamps >= loudest_hour_start) & (timestamps <= loudest_hour_end)
            ]
            .notna()
            .sum(),
        )
    else:
        loudest_hour_end = None
        loudest_hour_db = float("nan")
        loudest_hour_text = "—"
        loudest_hour_coverage = 0

    tier = get_tier(coverage_day, config.coverage_valid, config.coverage_partial)
    last_measurement = day_levels.index.max() if len(day_levels) else None
    last_measurement_hour = (
        last_measurement.hour
        + last_measurement.minute / 60.0
        + last_measurement.second / 3600.0
        if last_measurement is not None
        else None
    )
    conservative_active = bool(
        not is_indoor
        and tier == "window"
        and last_measurement is not None
        and config.conservative_window_start_hour
        <= last_measurement_hour
        <= config.conservative_window_end_hour
        and day_seconds > 0
        and not np.isnan(laeq_day)
    )
    if conservative_active:
        conservative_gap_seconds = int(
            (day_window_end - last_measurement).total_seconds()
        )
        measured_energy = np.power(10.0, laeq_day / 10.0)
        assumed_energy = np.power(10.0, config.window_estimate_db / 10.0)
        conservative_laeq = 10.0 * np.log10(
            (day_seconds * measured_energy + conservative_gap_seconds * assumed_energy)
            / (day_seconds + conservative_gap_seconds),
        )
        conservative_coverage = (
            day_seconds + conservative_gap_seconds
        ) / TAG_REFERENCE_SECONDS
        conservative_measurement_end_text = last_measurement.strftime("%H:%M")
    else:
        conservative_laeq = float("nan")
        conservative_coverage = float("nan")
        conservative_measurement_end_text = ""

    high_coverage_active = bool(
        not is_indoor
        and tier == "partial"
        and day_seconds > 0
        and not np.isnan(laeq_day)
    )
    if high_coverage_active:
        high_coverage_gap_seconds = TAG_REFERENCE_SECONDS - day_seconds
        measured_energy = np.power(10.0, laeq_day / 10.0)
        assumed_energy = np.power(10.0, config.partial_estimate_db / 10.0)
        high_coverage_laeq = 10.0 * np.log10(
            (day_seconds * measured_energy + high_coverage_gap_seconds * assumed_energy)
            / TAG_REFERENCE_SECONDS,
        )
    else:
        high_coverage_laeq = float("nan")

    day_label = coverage_label_short(
        laeq_day,
        coverage_day,
        grid.sessions,
        day_start,
        config.coverage_valid,
        config.coverage_partial,
    )
    supplement = coverage_supplement(
        coverage_day,
        grid.sessions,
        day_start,
        day_seconds,
        config.coverage_valid,
        config.coverage_partial,
    )
    measurement_period_text = " / ".join(
        f"{start:%H:%M}–{end:%H:%M}" for start, end in grid.sessions
    )

    return DayMetrics(
        day=day,
        is_indoor=is_indoor,
        tier=tier,
        coverage_day=coverage_day,
        day_seconds=day_seconds,
        before_7_seconds=before_7_seconds,
        after_20_seconds=after_20_seconds,
        laeq_day_db=laeq_day,
        laeq_before_7_db=laeq_before_7,
        laeq_after_20_db=laeq_after_20,
        l1_day_db=l1_day,
        highest_db=highest,
        lmax_before_7_db=lmax_before_7,
        lmax_after_20_db=lmax_after_20,
        percent_above_guideline=percent_above_guideline,
        seconds_above_guideline=seconds_above_guideline,
        seconds_above_intervention=seconds_above_intervention,
        measured_minutes=measured_minutes,
        rating_level_day_db=rating_level,
        impulse_adjustment_day_db=impulse_adjustment,
        loudest_hour_db=loudest_hour_db,
        loudest_hour_text=loudest_hour_text,
        loudest_hour_coverage_seconds=loudest_hour_coverage,
        loudest_hour_end=loudest_hour_end,
        conservative_active=conservative_active,
        conservative_laeq_day_db=float(conservative_laeq),
        conservative_coverage_day=float(conservative_coverage),
        conservative_measurement_end_text=conservative_measurement_end_text,
        high_coverage_estimate_active=high_coverage_active,
        high_coverage_laeq_day_db=float(high_coverage_laeq),
        laeq_day_label=day_label,
        coverage_supplement_text=supplement,
        measurement_period_text=measurement_period_text,
        energy_level_1hz_db=levels,
        peak_level_1hz_db=peaks,
        laeq_1min_db=laeq_1min,
        sessions=grid.sessions,
    )


def _validate_config(config: DayConfig) -> None:
    if not 0.0 <= config.coverage_partial <= config.coverage_valid <= 1.0:
        raise ValueError(
            "Abdeckungsschwellen müssen 0 <= Teilerfassung <= Vollmessung <= 1 erfüllen.",
        )
    if not 0 <= config.conservative_window_start_hour <= 23:
        raise ValueError(
            "Der Start des konservativen Fensters muss zwischen 0 und 23 liegen."
        )
    if not 0 <= config.conservative_window_end_hour <= 23:
        raise ValueError(
            "Das Ende des konservativen Fensters muss zwischen 0 und 23 liegen."
        )
    if config.conservative_window_start_hour > config.conservative_window_end_hour:
        raise ValueError("Das konservative Zeitfenster ist rückwärts definiert.")
    if not 1 <= config.loudest_hour_min_seconds <= 3600:
        raise ValueError(
            "Die Mindestabdeckung der lautesten Stunde muss 1 bis 3600 s betragen."
        )
    numeric_values = (
        config.partial_estimate_db,
        config.window_estimate_db,
        config.device_uncertainty_db,
        config.day_guideline_db,
        config.day_intervention_db,
    )
    if not all(np.isfinite(value) for value in numeric_values):
        raise ValueError("Alle Pegel- und Unsicherheitsparameter müssen endlich sein.")
    _load_time_zone(config.time_zone)


def _load_time_zone(time_zone: str) -> ZoneInfo:
    try:
        return ZoneInfo(time_zone)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValueError(f"Unbekannte Zeitzone: {time_zone}") from error


def _leq_of(energy: pd.Series) -> float:
    """Energetischer Mittelwert wie in den Referenzzeilen 654-657."""

    valid_energy = energy.dropna()
    if not len(valid_energy):
        return float("nan")
    return float(10.0 * np.log10(valid_energy.mean()))


def _merge_sessions(
    sessions: Sequence[SessionPeriod],
    gap_seconds: int = 0,
) -> list[SessionPeriod]:
    if not sessions:
        return []
    sorted_sessions = sorted(sessions)
    merged: list[list[pd.Timestamp]] = [list(sorted_sessions[0])]
    for start, end in sorted_sessions[1:]:
        if (start - merged[-1][1]).total_seconds() <= gap_seconds:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return [(start, end) for start, end in merged]


def _sessions_in_day(
    sessions: Sequence[SessionPeriod],
    day_start: pd.Timestamp,
) -> list[SessionPeriod]:
    window_start = day_start + pd.Timedelta(hours=7)
    window_end = day_start + pd.Timedelta(hours=20)
    within = []
    for start, end in sessions:
        clipped_start = max(start, window_start)
        clipped_end = min(end, window_end)
        if clipped_start < clipped_end:
            within.append((clipped_start, clipped_end))
    return _merge_sessions(within)


def _get_gaps(
    sessions: Sequence[SessionPeriod],
    day_start: pd.Timestamp,
) -> tuple[list[SessionPeriod], list[SessionPeriod]]:
    """Portiert die Lückenermittlung aus den Referenzzeilen 532-556."""

    window_start = day_start + pd.Timedelta(hours=7)
    window_end = day_start + pd.Timedelta(hours=20)
    merged = _merge_sessions(sessions, gap_seconds=GAP_MIN_SECONDS)
    within = _sessions_in_day(merged, day_start)
    gaps: list[SessionPeriod] = []
    cursor = window_start
    for start, end in within:
        if (start - cursor).total_seconds() > GAP_MIN_SECONDS:
            gaps.append((cursor, start))
        cursor = max(cursor, end)
    if (window_end - cursor).total_seconds() > GAP_MIN_SECONDS:
        gaps.append((cursor, window_end))
    return gaps, within


def _format_duration(seconds: int) -> str:
    hours = seconds // 3600
    minutes = (seconds % 3600) // 60
    return f"{hours}h {minutes:02d}min" if hours else f"{minutes}min"
