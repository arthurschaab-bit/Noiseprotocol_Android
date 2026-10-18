"""SQLite-Zugriff (Regel 3: nur Kopien, nur lesend) - Schema-Dump und PCE-Extraktion je Sekunde."""

import bisect
import csv
import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from .common import SOURCE_MIC, SOURCE_PCE, day_window_ms, energetic_mean, local_day

REQUIRED_COLUMNS = {"id", "at", "levelDb", "source"}  # LevelSampleEntity.kt


def _uri(path: Path, immutable: bool) -> str:
    flags = "mode=ro&immutable=1" if immutable else "mode=ro"
    return f"{path.resolve().as_uri()}?{flags}"


def open_readonly(path: Path, immutable: bool = True) -> sqlite3.Connection:
    """Öffnet file:<pfad>?mode=ro&immutable=1. Mit immutable=1 ignoriert SQLite -wal/-journal;
    WAL-Frames werden deshalb gesondert über open_readonly(..., immutable=False) auf einer Arbeitskopie
    ausgewertet (siehe wal_new_ids)."""
    return sqlite3.connect(_uri(path, immutable), uri=True)


def schema_dump(conn: sqlite3.Connection, with_counts: bool = True) -> dict:
    cur = conn.cursor()
    info = {pragma: cur.execute(f"PRAGMA {pragma}").fetchone()[0]
            for pragma in ("page_size", "page_count", "freelist_count", "user_version", "journal_mode")}
    tables = []
    for name, sql in cur.execute("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall():
        columns = [{"name": c[1], "typ": c[2], "pk": bool(c[5])}
                   for c in cur.execute(f'PRAGMA table_info("{name}")').fetchall()]
        entry = {"name": name, "spalten": columns, "sql": sql}
        if with_counts:
            entry["zeilen"] = cur.execute(f'SELECT COUNT(*) FROM "{name}"').fetchone()[0]
        tables.append(entry)
    indexes = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='index' ORDER BY name")]
    return {"info": info, "tabellen": tables, "indizes": indexes}


def has_level_samples(conn: sqlite3.Connection) -> bool:
    columns = {c[1] for c in conn.execute("PRAGMA table_info(level_samples)")}
    return REQUIRED_COLUMNS <= columns


def level_sample_overview(conn: sqlite3.Connection) -> list[dict]:
    """Je Berliner Tag und Quelle: Anzahl Samples, erster/letzter Zeitpunkt (Epoch-ms). P1/P5."""
    per_day: dict[tuple[date, str], list] = defaultdict(lambda: [0, None, None])
    for source, at in conn.execute("SELECT source, at FROM level_samples"):
        slot = per_day[(local_day(at // 1000), source)]
        slot[0] += 1
        slot[1] = at if slot[1] is None else min(slot[1], at)
        slot[2] = at if slot[2] is None else max(slot[2], at)
    return [{"tag": day.isoformat(), "quelle": source, "samples": n, "erst_ms": first, "letzt_ms": last}
            for (day, source), (n, first, last) in sorted(per_day.items())]


def time_checks(conn: sqlite3.Connection, max_gap_seconds: int = 120) -> dict[str, dict]:
    """Plausibilität 4d: Zeit gegen id-Reihenfolge nicht monoton (Rücksprünge) und große Lücken je Tag."""
    result: dict[str, dict] = defaultdict(lambda: {"ruecksprung": 0, "luecken_ueber_grenze": 0})
    previous_at = None
    for _id, at in conn.execute("SELECT id, at FROM level_samples ORDER BY id"):
        if previous_at is not None:
            day = local_day(at // 1000).isoformat()
            if at < previous_at:
                result[day]["ruecksprung"] += 1
            elif at - previous_at > max_gap_seconds * 1000:
                result[day]["luecken_ueber_grenze"] += 1
        previous_at = at
    return dict(result)


@dataclass
class SecondRow:
    sekunde: int  # Epoch-Sekunde (UTC)
    n: int
    laeq: float
    lmin: float
    lmax: float
    letzter: float
    id_von: int
    id_bis: int


def pce_seconds(conn: sqlite3.Connection, day: date, only_id_above: int | None = None):
    """PCE-Samples des Tagesfensters 07-20 Uhr auf das 1-s-Raster.

    Methode: alle echten PCE_323-Samples derselben Epoch-Sekunde werden energetisch gemittelt
    (zusätzlich Min/Max/letzter Wert). Keine Interpolation, keine Auffüllung; Sekunden ohne
    PCE-Sample erscheinen nicht. Mikrofonzeilen werden nie gelesen."""
    start_ms, end_ms = day_window_ms(day)
    sql = "SELECT id, at, levelDb FROM level_samples WHERE source=? AND at>=? AND at<?"
    params: list = [SOURCE_PCE, start_ms, end_ms]
    if only_id_above is not None:
        sql += " AND id>?"
        params.append(only_id_above)
    sql += " ORDER BY at, id"
    current, bucket = None, []
    for sample_id, at, level in conn.execute(sql, params):
        second = at // 1000
        if second != current and bucket:
            yield _flush(current, bucket)
            bucket = []
        current = second
        bucket.append((sample_id, at, level))
    if bucket:
        yield _flush(current, bucket)


def _flush(second: int, bucket: list) -> SecondRow:
    levels = [b[2] for b in bucket]
    ids = [b[0] for b in bucket]
    return SecondRow(second, len(bucket), energetic_mean(levels), min(levels), max(levels), bucket[-1][2],
                     min(ids), max(ids))


@dataclass
class DaySamples:
    at: list[int]
    level: list[float]
    source: list[str]

    def window(self, start_ms: int, end_ms: int) -> tuple[list[float], list[float]]:
        """(PCE-Pegel, Mikrofon-Pegel) im Fenster [start, end)."""
        lo, hi = bisect.bisect_left(self.at, start_ms), bisect.bisect_left(self.at, end_ms)
        pce = [self.level[i] for i in range(lo, hi) if self.source[i] == SOURCE_PCE]
        mic = [self.level[i] for i in range(lo, hi) if self.source[i] == SOURCE_MIC]
        return pce, mic


def load_day_samples(conn: sqlite3.Connection, day: date) -> DaySamples:
    """Nur für die Echtheitsprüfung 4a/4c: Mikrofonzeilen dienen dort ausschließlich dem Vergleich
    mit dem alten GEMISCHT-Wert (Regel 7), nie als Pegel."""
    start_ms, end_ms = day_window_ms(day)
    rows = conn.execute("SELECT at, levelDb, source FROM level_samples WHERE at>=? AND at<? ORDER BY at, id",
                        (start_ms, end_ms)).fetchall()
    return DaySamples([r[0] for r in rows], [r[1] for r in rows], [r[2] for r in rows])


def max_id(conn: sqlite3.Connection) -> int:
    return conn.execute("SELECT COALESCE(MAX(id), 0) FROM level_samples").fetchone()[0]


def write_schema_markdown(dump: dict, overview: list[dict], out: Path, title: str) -> None:
    lines = [f"# Schema {title}", "", "## Datenbank", ""]
    lines += [f"- {k}: {v}" for k, v in dump["info"].items()]
    for table in dump["tabellen"]:
        lines += ["", f"## Tabelle `{table['name']}`" + (f" ({table['zeilen']} Zeilen)" if "zeilen" in table else ""), ""]
        lines += [f"- `{c['name']}` {c['typ']}{' (PK)' if c['pk'] else ''}" for c in table["spalten"]]
    lines += ["", "## Indizes", ""] + [f"- {i}" for i in dump["indizes"]]
    if overview:
        lines += ["", "## level_samples je Tag und Quelle", "", "| Tag | Quelle | Samples | erster at (ms) | letzter at (ms) |", "|---|---|---|---|---|"]
        lines += [f"| {o['tag']} | {o['quelle']} | {o['samples']} | {o['erst_ms']} | {o['letzt_ms']} |" for o in overview]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_extract(rows, out: Path, meta: dict) -> int:
    """Zwischenergebnis je Datenbank: eine Zeile je PCE-Sekunde mit Herkunft (Regel 6)."""
    count = 0
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["sekunde", "n", "laeq", "lmin", "lmax", "id_von", "id_bis", "datei", "sha256", "tabelle", "methode"])
        for row in rows:
            writer.writerow([row.sekunde, row.n, repr(row.laeq), repr(row.lmin), repr(row.lmax), row.id_von, row.id_bis,
                             meta["datei"], meta["sha256"], "level_samples", meta["methode"]])
            count += 1
    return count
