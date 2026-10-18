"""Tests der Recovery-Werkzeuge (tools/recovery) mit synthetischen Daten - keine echten Messwerte."""

import csv
import hashlib
import math
import sqlite3
import sys
import zipfile
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

sys.path.insert(0, str(Path(__file__).parents[4] / "tools" / "recovery"))

from pcerecovery import altcsv, db, inventar, kopieren, merge, pruefen  # noqa: E402
from pcerecovery.common import DAY_WINDOW_SECONDS, day_window_ms, energetic_mean, sha256_file  # noqa: E402

TZ = ZoneInfo("Europe/Berlin")
DAY = date(2026, 9, 14)


def _start_ms() -> int:
    return day_window_ms(DAY)[0]


def _make_db(path: Path, samples: list[tuple[int, float, str]]) -> None:
    """samples = (at_ms, level, source); Schema wie LevelSampleEntity (Room)."""
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE level_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL, "
                 "levelDb REAL NOT NULL, source TEXT NOT NULL)")
    conn.execute("CREATE INDEX index_level_samples_at ON level_samples (at)")
    conn.executemany("INSERT INTO level_samples (at, levelDb, source) VALUES (?, ?, ?)", samples)
    conn.commit()
    conn.close()


def _alt_csv(path: Path, rows: list[tuple[int, float, int, str]]) -> None:
    """Format von DriveCsv.kt: BOM, Semikolon, Dezimalkomma, CRLF."""
    with open(path, "w", encoding="utf-8-sig", newline="") as handle:
        handle.write("Zeit;Pegel_dB;LAeq_dB;LAFmax_dB;LAFmin_dB;Bewertung;Zeitbewertung;Messbereich;Samples;Quelle;"
                     "Ereignis;Klassifikation;Notizen\r\n")
        for start_ms, laeq, samples, source in rows:
            text = datetime.fromtimestamp(start_ms / 1000, TZ).isoformat()
            value = f"{laeq:.1f}".replace(".", ",")
            handle.write(f"{text};{value};{value};{value};{value};A;FAST;AUTO;{samples};{source};;;\r\n")


@pytest.fixture
def mixed_day(tmp_path):
    """Sekunde 0: PCE 70 dB x2 + Mikrofon 90 dB x3 (alt GEMISCHT); Sekunde 1: nur PCE; Sekunde 2: nur Mikrofon."""
    t = _start_ms()
    samples = [(t, 70.0, "PCE_323"), (t + 400, 70.0, "PCE_323"), (t + 100, 90.0, "MIKROFON"),
               (t + 300, 90.0, "MIKROFON"), (t + 600, 90.0, "MIKROFON"),
               (t + 1000, 65.0, "PCE_323"), (t + 2000, 80.0, "MIKROFON")]
    mixed = energetic_mean([70.0, 70.0, 90.0, 90.0, 90.0])
    rows = [(t, mixed, 5, "GEMISCHT"), (t + 1000, 65.0, 1, "PCE_323"), (t + 2000, 80.0, 1, "MIKROFON")]
    database, csv_path = tmp_path / "noise_database", tmp_path / "laermprotokoll_2026-09-14.csv"
    _make_db(database, sorted(samples))
    _alt_csv(csv_path, rows)
    return database, csv_path, rows, t


def test_pce_seconds_ignore_microphone_and_do_not_interpolate(mixed_day):
    database, _, _, t = mixed_day
    conn = db.open_readonly(database)
    result = list(db.pce_seconds(conn, DAY))
    assert [r.sekunde for r in result] == [t // 1000, t // 1000 + 1]  # Sekunde 2 (nur Mikrofon) fehlt bewusst
    assert result[0].n == 2 and result[0].laeq == pytest.approx(70.0)
    assert result[1].laeq == pytest.approx(65.0)


def test_database_is_not_modified_by_reading(mixed_day):
    database, _, _, _ = mixed_day
    before = sha256_file(database)
    conn = db.open_readonly(database)
    list(db.pce_seconds(conn, DAY))
    db.schema_dump(conn)
    db.level_sample_overview(conn)
    conn.close()
    assert sha256_file(database) == before
    with pytest.raises(sqlite3.OperationalError):
        db.open_readonly(database).execute("INSERT INTO level_samples (at, levelDb, source) VALUES (1, 1, 'X')")


def test_echtheitstest_confirms_old_mix_weighted_by_sample_count(mixed_day):
    database, csv_path, rows, _ = mixed_day
    alt = altcsv.parse_alt_csv(csv_path)
    assert altcsv.window_seconds(alt) == 1
    samples = db.load_day_samples(db.open_readonly(database), DAY)
    mixed = next(r for r in alt if r.quelle == "GEMISCHT")
    result = pruefen.check_mixed_window(mixed, 1, samples)
    assert result["status"] == "BESTAETIGT" and result["samples_gleich"] == "JA"
    # Mittel der zwei Quellenmittel (80 dB) wäre falsch - Gewichtung nach Probenzahl ergibt ~86 dB
    assert result["db_gemischt_laeq"] > 85


def test_echtheitstest_reports_conflict_and_missing(mixed_day):
    database, csv_path, rows, t = mixed_day
    samples = db.load_day_samples(db.open_readonly(database), DAY)
    wrong = altcsv.AltRow(t, 81.0, 81.0, 5, "GEMISCHT")
    assert pruefen.check_mixed_window(wrong, 1, samples)["status"] == "KONFLIKT"
    empty = altcsv.AltRow(t + 50_000, 70.0, 70.0, 5, "GEMISCHT")
    assert pruefen.check_mixed_window(empty, 1, samples)["status"] == "NICHT_PRUEFBAR"


def _reconstruct(pce, mic):
    row = altcsv.AltRow(0, round(energetic_mean(pce + mic), 1), None, len(pce) + len(mic), "GEMISCHT")
    return pruefen.reconstruct_pce(row, mic)


def test_reconstruction_with_quiet_microphone_stays_inside_rounding_interval():
    pce = [70.0, 72.0]
    result = _reconstruct(pce, [70.0, 71.0, 69.0])
    assert result["ergebnis"] == "BERECHNET" and result["pce_n_berechnet"] == 2
    assert result["pce_laeq_min"] <= energetic_mean(pce) <= result["pce_laeq_max"]
    assert result["pce_laeq_max"] - result["pce_laeq_min"] < 0.5


def test_reconstruction_with_loud_microphone_is_unbounded_below():
    # Mikrofon (90 dB) dominiert: die Rundung der Alt-CSV auf 0,1 dB macht die Untergrenze undefiniert.
    # Genau deshalb ist 4c nur informativ und nie ein Messwert.
    pce = [70.0, 72.0]
    result = _reconstruct(pce, [90.0, 91.0, 89.0])
    assert result["ergebnis"] == "BERECHNET" and result["pce_laeq_min"] == ""
    assert energetic_mean(pce) <= result["pce_laeq_max"]


def test_reconstruction_not_computable_without_microphone_or_with_impossible_energy():
    assert pruefen.reconstruct_pce(altcsv.AltRow(0, 70.0, None, 3, "GEMISCHT"), [])["ergebnis"] == "NICHT_BERECHENBAR"
    impossible = pruefen.reconstruct_pce(altcsv.AltRow(0, 50.0, None, 3, "GEMISCHT"), [90.0])
    assert impossible["ergebnis"] == "UNMOEGLICH_NEGATIVE_ENERGIE"


def test_day_targets_status_per_second(mixed_day):
    _, csv_path, _, _ = mixed_day
    rows = altcsv.parse_alt_csv(csv_path)
    targets = altcsv.day_targets(rows, 1, DAY)
    assert len(targets) == DAY_WINDOW_SECONDS
    assert [t[0] for t in targets[:4]] == ["GEMISCHT", "PCE", "MIKROFON", "FEHLT"]
    assert altcsv.has_activity(rows)


def test_alt_csv_with_other_header_is_rejected(tmp_path):
    path = tmp_path / "Session_x.csv"
    path.write_text("Zeit;Wert\n2026-09-07T10:00:00+02:00;50\n", encoding="utf-8")
    with pytest.raises(ValueError):
        altcsv.parse_alt_csv(path)


def test_copy_refuses_overlap_and_verifies_hashes(tmp_path):
    source = tmp_path / "quelle"
    (source / "unter").mkdir(parents=True)
    (source / "unter" / "a.bin").write_bytes(b"abc")
    with pytest.raises(ValueError):
        kopieren.copy_tree(source, source / "kopie")
    destination = tmp_path / "originals"
    manifest = tmp_path / "manifest.csv"
    manifest.write_text(f"a.bin;{hashlib.sha256(b'abc').hexdigest()}\n", encoding="utf-8")
    results = kopieren.copy_tree(source, destination, kopieren.load_manifest_hashes([manifest]))
    assert [(r.status, r.im_manifest) for r in results] == [("OK", "JA")]
    assert (destination / "unter" / "a.bin").read_bytes() == b"abc"
    assert (source / "unter" / "a.bin").read_bytes() == b"abc"  # Quelle unverändert


def test_inventory_unpacks_nested_zip_flags_defects_and_duplicates(tmp_path):
    originals, work = tmp_path / "originals", tmp_path / "work"
    originals.mkdir()
    database = tmp_path / "x.db"
    _make_db(database, [(1, 60.0, "PCE_323")])
    inner = tmp_path / "inner.zip"
    with zipfile.ZipFile(inner, "w") as bundle:
        bundle.write(database, "noise_database")
    with zipfile.ZipFile(originals / "outer.zip", "w") as bundle:
        bundle.write(inner, "inner.zip")
        bundle.writestr("../evil.txt", "x")  # Zip-Slip darf nicht entpackt werden
    (originals / "leer.zip").write_bytes(b"")
    (originals / "kaputt.zip").write_bytes(b"PK\x03\x04" + b"\x00" * 30)
    (originals / "a.csv").write_text("a;b;c\n1;2;3\n", encoding="utf-8")
    (originals / "b.csv").write_text("a;b;c\n1;2;3\n", encoding="utf-8")
    rows = {r.pfad: r for r in inventar.build_inventory(originals, work, 1)}
    assert rows["outer.zip!/inner.zip!/noise_database"].typ == "SQLITE"
    assert rows["leer.zip"].status == "defekt/unlesbar" and "0 Byte" in rows["leer.zip"].grund
    assert rows["kaputt.zip"].status == "defekt/unlesbar"
    assert rows["b.csv"].status == "Duplikat" and rows["b.csv"].duplikat_von == "a.csv"
    assert not (tmp_path / "evil.txt").exists() and not (work / "evil.txt").exists()
    assert not any("evil" in p for p in rows)


def test_wal_frames_are_reported_separately(tmp_path):
    database = tmp_path / "noise_database"
    conn = sqlite3.connect(database)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA wal_autocheckpoint=0")
    conn.execute("CREATE TABLE level_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL, "
                 "levelDb REAL NOT NULL, source TEXT NOT NULL)")
    conn.execute("INSERT INTO level_samples (at, levelDb, source) VALUES (?, 60, 'PCE_323')", (_start_ms(),))
    conn.commit()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.execute("INSERT INTO level_samples (at, levelDb, source) VALUES (?, 61, 'PCE_323')", (_start_ms() + 1000,))
    conn.commit()
    snapshot = tmp_path / "kopie"
    snapshot.mkdir()
    for suffix in ("", "-wal"):
        (snapshot / f"noise_database{suffix}").write_bytes((tmp_path / f"noise_database{suffix}").read_bytes())
    conn.close()
    intact = db.open_readonly(snapshot / "noise_database")
    with_wal = db.open_readonly(snapshot / "noise_database", immutable=False)
    assert len(list(db.pce_seconds(intact, DAY))) == 1  # immutable=1 ignoriert -wal
    new_rows = list(db.pce_seconds(with_wal, DAY, only_id_above=db.max_id(intact)))
    assert [r.laeq for r in new_rows] == [pytest.approx(61.0)]


def test_merge_priority_conflicts_and_validation(tmp_path, mixed_day):
    pytest.importorskip("pyarrow")
    import pyarrow.parquet as pq

    database, csv_path, _, t = mixed_day
    conn = db.open_readonly(database)
    meta = {"datei": "noise_database", "sha256": "ab" * 32, "methode": "DB_INTAKT"}
    first = tmp_path / "e1.csv"
    db.write_extract(db.pce_seconds(conn, DAY), first, meta)
    # zweite Quelle: Sekunde 0 stimmt (70,1), Sekunde 1 widerspricht (75 statt 65) -> Konflikt
    second = tmp_path / "e2.csv"
    with open(second, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["sekunde", "n", "laeq", "lmin", "lmax", "id_von", "id_bis", "datei", "sha256", "tabelle", "methode"])
        writer.writerow([t // 1000, 1, 70.1, 70.1, 70.1, 1, 1, "wal", "cd" * 32, "level_samples", "WAL"])
        writer.writerow([t // 1000 + 1, 1, 75.0, 75.0, 75.0, 2, 2, "wal", "cd" * 32, "level_samples", "WAL"])
    check = tmp_path / "p.csv"
    alt = altcsv.parse_alt_csv(csv_path)
    results = [pruefen.check_mixed_window(r, 1, db.load_day_samples(conn, DAY)) for r in alt if r.quelle == "GEMISCHT"]
    pruefen.write_dicts(results, pruefen.CHECK_COLUMNS, check)
    out = tmp_path / "pce_gerettet.parquet"
    stats = merge.merge_extracts([first, second], [check], 1, out, tmp_path / "konflikte.csv")
    rows = pq.read_table(out).to_pylist()
    assert stats == {"sekunden": 1, "konflikte": 1}
    first_second = [r for r in rows if r["sekunde_utc"] == t // 1000]
    assert len(first_second) == 1 and first_second[0]["methode"] == "DB_INTAKT"
    assert first_second[0]["validierung"] == "BESTAETIGT_4A" and first_second[0]["weitere_quellen"] == 1
    conflicted = [r for r in rows if r["sekunde_utc"] == t // 1000 + 1]
    assert {r["methode"] for r in conflicted} == {"DB_INTAKT", "WAL"}
    assert all(r["validierung"] == "KONFLIKT_QUELLEN" for r in conflicted)  # nicht aufgelöst
    # Bilanz + Stichprobe
    target = tmp_path / "ziel.parquet"
    merge.write_target_parquet([(DAY, alt, 1, csv_path.name, "00" * 32)], target, tmp_path / "ziel.csv")
    balance = merge.daily_balance(target, out, tmp_path / "bilanz.csv")[0]
    assert balance["bereits_pce"] == 1 and balance["gerettet"] == 1 and balance["davon_bestaetigt_4a"] == 1
    assert merge.sample_per_day(out, tmp_path / "stichprobe.csv") == len(rows)
