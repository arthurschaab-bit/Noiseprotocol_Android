# Recovery-Werkzeuge: reine PCE-323-Sekunden zurückgewinnen

Werkzeuge für den Auftrag „Rückgewinnung reiner PCE-323-Messwerte für GEMISCHT- und Lückenzeiträume“
Nur Python-Standardbibliothek, für Parquet zusätzlich `pyarrow`
(`pip install -r tools/recovery/requirements.txt`, Python ≥ 3.11).

**Stand:** getestet nur mit synthetischen Daten (`app/src/test/python/test_recovery.py`). An echten
Datenbanken ist nichts davon bisher gelaufen.

## Regeln, die der Code erzwingt

| Regel | Umsetzung |
|---|---|
| 1 Nie in `F:\` schreiben | `kopieren` liest die Quelle nur mit `open(..., 'rb')` und bricht ab, wenn Quelle und Ziel sich überlappen |
| 2 SHA-256 vor/nach, Manifeste | Hash der Quelle und der Kopie stehen im Kopierprotokoll; jeder 64-stellige Hex-Wert in den Phase-A-Manifesten zählt als bekannter Hash (`im_manifest`); Cloud-Platzhalter werden als `NUR_ONLINE` gemeldet |
| 3 SQLite nur lesend | `file:<pfad>?mode=ro&immutable=1`; mit `immutable=1` wird `-wal` ignoriert, deshalb gibt es `--wal-kopie` (WAL-Frames getrennt, Methode `WAL`) |
| 5/7 nie erzeugen, Mikrofon nie als Pegel | `extrahieren` liest nur `source='PCE_323'`; Mikrofonzeilen nutzen nur `pruefen` (4a/4c) |
| 6 Herkunft je Sekunde | `datei`, `sha256`, `tabelle`, `id_von`/`id_bis`, `methode`, `runde` |

## Befehle (Beispiel Runde 1, PowerShell, im Repo-Root)

```powershell
$R = "data/recovery"
# 1) Kopieren (liest F:\ nur) - Quelle nicht verändern
python tools/recovery/recovery.py kopieren --quelle "F:\Meine Ablage\Lärmprotokoll\Sicherungen" `
  --ziel "$R/originals/Sicherungen" --manifest "<Phase_A>\Protokolle\SHA256_Pruefmanifest_Phase_A.csv" `
  --manifest "<Phase_A>\Protokolle\Manifest_20261011T133214Z_db98d39d.csv" --out "$R/kopierprotokoll_runde1.csv"
# 2) Inventar, ZIPs rekursiv nach work/ entpacken
python tools/recovery/recovery.py inventar --originals "$R/originals" --work "$R/work" --runde 1 --out "$R/inventar_runde1.csv"
# 3) Schema der DB (Pfad aus work/entpackt/<hash>/...) - mit integrity_check
python tools/recovery/recovery.py schema --db "$R/work/entpackt/<hash>/<db>" --out "$R/schema_<hash>.md" --integrity
# 4) Zielmenge aus den Alt-CSVs (Tagesordner), 5) PCE-Sekunden, 6) Echtheitstest + 4c
python tools/recovery/recovery.py ziel --csv-ordner "$R/originals/<Tagesordner>" --von 2026-05-20 --bis 2026-10-10 --out-dir $R
python tools/recovery/recovery.py extrahieren --db <db> --von 2026-09-13 --bis 2026-10-10 --runde 1 --out-dir "$R/work/extrakte"
python tools/recovery/recovery.py pruefen --db <db> --csv-ordner "$R/originals/<Tagesordner>" --von 2026-09-13 --bis 2026-10-10 --runde 1 --out-dir "$R/work/pruefungen"
# 7) Zusammenführen, Tagesbilanz, Stichprobe 4e
python tools/recovery/recovery.py zusammenfuehren --extrakte $R/work/extrakte/extrakt_*.csv --pruefungen $R/work/pruefungen/pruefung_4a_*.csv --runde 1 --out-dir $R
python tools/recovery/recovery.py bilanz --ziel $R/ziel_sekunden.parquet --gerettet $R/pce_gerettet.parquet --out $R/tagesbilanz_runde1.csv
python tools/recovery/recovery.py stichprobe --gerettet $R/pce_gerettet.parquet --out $R/stichprobe_4e.csv
```

## Methoden (so wie sie im Code stehen)

- **Zeitbasis:** Europe/Berlin (`zoneinfo`); Auswertefenster 07:00–20:00 = 46.800 s je Tag.
- **1-s-Raster:** Alle echten `PCE_323`-Samples derselben Epoch-Sekunde werden energetisch gemittelt
  (`laeq`), dazu Min, Max und Anzahl `n`. Keine Interpolation; Sekunden ohne PCE-Sample fehlen.
- **Alt-GEMISCHT (4a):** Vor Commit `d64e84a` (10.10.2026) mittelte `PegelAggregator.bildeZeile` **alle** Roh-Samples
  des Fensters beider Quellen energetisch, also nach Probenzahl gewichtet. `pruefen` rechnet genauso und vergleicht mit
  `LAeq_dB` der Alt-CSV (≤ 0,15 dB = `BESTAETIGT`, sonst `KONFLIKT`, ohne DB-Samples `NICHT_PRUEFBAR`).
  Fenster = `drive_aggregation_seconds` (Standard 1 s, 1–60 einstellbar); das Werkzeug bestimmt es empirisch aus dem
  Zeilenabstand (`ziel_tageszusammenfassung.csv`, Spalte `fenster_s`). Bei Fenstern > 1 s erbt jede Sekunde den Status.
- **4c:** `E_pce = N·10^(L/10) − Σ10^(Lmic/10)`, `n_pce = N − n_mic`, mit Intervall aus der Rundung der Alt-CSV (±0,05 dB).
  Ausgabe nur in `rekonstruiert_nicht_verwendbar.csv`, nie in `pce_gerettet.parquet`. `--auch-mit-pce` rechnet auch
  dort, wo echte PCE-Samples existieren (Methodentest).
- **Konflikte (4b):** Kandidaten derselben Sekunde innerhalb 0,15 dB bestätigen sich (`weitere_quellen`), sonst stehen
  alle Kandidaten mit `KONFLIKT_QUELLEN` im Ergebnis und in `konflikte.csv`; nichts wird aufgelöst.
- **Zielmenge:** Status je Sekunde aus der Alt-CSV (`PCE_323`→PCE, `GEMISCHT`, `MIKROFON`; alles andere → FEHLT).
  Ziel = nicht PCE, aber nur an Tagen mit Zeile mit Quelle und Samples > 0. **Gibt es mehrere CSV-Versionen für einen
  Tag, wird die zuletzt geänderte gewählt und eine Warnung ausgegeben** - bei Beweisfragen bitte prüfen.

## Bewusst noch nicht enthalten (offen für spätere Runden)

- `.recover`, Freelist-Auswertung und Page-Carving defekter DBs (S4/S5).
- PCE-Geräteformat und Kreuzkorrelation des Zeitversatzes (S6): braucht ein Musterexemplar.
- `Session_*.csv`: andere Spalten, werden vom Parser abgelehnt und nur im Inventar geführt.
- WAL: erkannt werden nur angehängte Zeilen mit `id` größer als das Maximum der Hauptdatei, keine geänderten Zeilen.
- Abgleich mit der Berichtspipeline (Commit `6250b27502de`, nicht in diesem Repo) und `BERICHT_RECOVERY.md`.
- Die Skripte übernehmen nichts in bestehende Auswertungsdateien (Freigabe durch den Owner nötig).
