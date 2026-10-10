# Befunde aus den Support-Bundles vom 07.–10.10.2026

## Grundlage

| Bundle | Gerät | Build | erstellt (UTC) |
|---|---|---|---|
| `b7692280-2026-10-07_065216_periodisch.zip` | Huawei P30 (ELE-L29), SDK 29 | `ci804+52945c7` | 07.10. 04:52 |
| `728583ed-2026-10-09_061552_periodisch.zip` | Pixel 9 Pro, SDK 37 | `ci764+bbf1a09` | 09.10. 04:15 |
| `42081acf-2026-10-09_065235_periodisch.zip` | Huawei P30 | `ci804` | 09.10. 04:52 |
| `9e592563-2026-10-10_061700_periodisch.zip` | Pixel 9 Pro | `ci764` | 10.10. 04:17 |
| `8ae2a776-2026-10-10_065252_periodisch.zip` | Huawei P30 | `ci804` | 10.10. 04:53 |

`events.jsonl` der drei P30-Bundles zusammengeführt (nach `id` entdupliziert): 12.676 Einträge,
06.10. 05:58 – 10.10. 04:52 UTC.

**Wichtig:** Das P30 läuft noch mit Build 804. `main` ist rund 40 Commits weiter; unter anderem
fehlen dem Gerät der Kadenzwächter-Fix (`b66ce8c`) und die Abbruch-Weitergabe im Drive-Sync
(`99b2df1`). Laut Owner kommt der aktuelle Stand ab Montag, 12.10., aufs Gerät.

Die Pixel-Bundles (Leerlauf, `aufnahmeAktiv: false`) zeigen nichts Neues.

## 1 · Befund 1 — Der WAV-ZIP-Upload treibt die App in den Speichermangel (hoch)

- 07.10. 05:02–05:55 UTC: 16 × `DRIVE_UPLOAD_FAILED @ DriveSyncCoordinator.ladeWavZipsHoch:
  Failed to allocate a 122181728 byte allocation … -> OutOfMemoryError`.
- 07.10. 07:09 UTC (09:09 Ortszeit): `FATAL EXCEPTION: Binder:4168_6 … OutOfMemoryError`
  (Crash-Puffer im Logcat), danach ein Bundle `anr, Watchdog, nach Neustart`. Ein Stacktrace
  fehlt („OutOfMemoryError thrown while trying to throw an exception“). Dass derselbe Pfad den
  Absturz auslöste, ist naheliegend, aber nicht bewiesen.
- Ursache im Code (auch auf `main`): `WavHourlyZipper.erstelleZipArchiv()` baute jedes
  Stunden-ZIP in einem `ByteArrayOutputStream`. Am 06.10. hatten drei Stunden 703–855 WAVs à
  ~161 KB, also bis ~138 MB pro ZIP. Beim Wachsen des Puffers und bei `toByteArray()` braucht das
  kurzzeitig das Doppelte, bei 384 MB Heap-Grenze.
- Die laufende Stunde wird bei jedem Sync neu gebaut und aktualisiert, in einer lauten Stunde
  also mehrfach.
- Verloren ging nichts: Alle betroffenen ZIPs kamen nach dem Neustart mit frischem Heap durch
  (07:16–07:57 UTC).
- **Richtigstellung zu `BEFUNDE_BUNDLES_2026-10-07.md`, Abschnitt 9, F:** „OOM-Abstürze
  erledigt“ gilt nur für die damalige Ursache (Rohwerte und Datenbanksicherung im Speicher).
  Dies ist eine zweite.
- **Fix:** Branch `fix/wav-zip-streaming`. Das ZIP wird streamend in eine temporäre Datei
  geschrieben und per resumable Upload hochgeladen, wie es Datenbanksicherung und Videos schon
  tun.

## 2 · Befund 2 — Die Datenbanksicherung ist groß und wird oft komplett neu hochgeladen

### Zahlen

| Tag (UTC) | erfolgreich | Volumen | fehlgeschlagen |
|---|---:|---:|---:|
| 07.10. | 21 | 4,6 GB | 11 |
| 08.10. | 31 | 8,1 GB | 6 |
| 09.10. | 11 | 3,1 GB | 12 |
| 10.10. (bis 04:52) | 7 | 2,1 GB | 1 |

- Größe einer Sicherung: 200 MB (07.10.) → 302 MB (10.10.).
- Datenbankdatei: 526 MB → 709 MB → 793 MB (`db_stats.json`).
- Abstand: `DATENBANK_SICHERUNG_MIN_INTERVALL = 30 min`, eine Abwägung des Implementierers,
  keine Owner-Vorgabe.

### Woraus die Sicherung besteht (Stand 10.10.)

| Tabelle | Zeilen | geschätzter Anteil an der Datei |
|---|---:|---:|
| `level_samples` | 11.868.395 | ca. 70–75 % |
| `measurements` | 1.955.138 | ca. 15 % |
| `diagnostic_log_entries` | 27.349 | klein |
| `noise_records` | 35.457 | klein |
| alles andere | < 1.000 | vernachlässigbar |

Die Anteile sind aus Zeilenzahl und Zeilenaufbau geschätzt (`level_samples`: id, at, levelDb,
source plus Index auf `at`, etwa 50 Byte je Zeile), nicht gemessen.

### Wo die Redundanz liegt

1. **`level_samples` ist ein Puffer für den Drive-Sync, keine Primärquelle.** Er wird mit
   ~20 Hz vom Mikrofon und 2 Hz vom PCE-323 gefüllt (~1,4 Mio. Zeilen/Tag). Sein Inhalt steht
   verdichtet bereits in den Tages-CSVs auf Drive, für den laufenden Tag bis zum letzten Sync.
   Nach drei Tagen löscht die App ihn ohnehin (`DriveSyncCoordinator`, `loescheBereich`).
2. **Die PCE-323-Werte stehen doppelt in der Datenbank**: in `measurements` und in
   `level_samples` (Quelle `PCE_323`).
3. **Jede Sicherung ist eine Vollsicherung.** Zwischen zwei Sicherungen ändern sich nur die
   Werte der letzten 30 Minuten, also deutlich unter 1 % der Datei. Hochgeladen wird trotzdem
   die ganze Datei.
4. **WAVs, Fotos, Videos und Tages-CSVs liegen bereits einzeln auf Drive.** Die Sicherung
   enthält davon nur die Metadaten, keine Dopplung.

### Ausblick ohne Änderung

- `level_samples` pendelt sich durch die 3-Tage-Löschung ein.
- `measurements` wird erst nach 90 Tagen verdichtet (`RetentionCoordinator`). Mit ~170.000
  Zeilen/Tag wächst die Tabelle also noch rund zwölf Wochen weiter.

### Entscheidung offen (E1 aus `PROMPT_UNTERSUCHUNG_HINTERGRUNDJOBS_DRIVE.md`)

Ziel des Owners (10.10.2026): Nach einem Fehler soll der **letzte gültige Stand**
wiederherstellbar sein, nicht der von vor drei Tagen. Daraus folgt: Die Häufigkeit ist richtig,
der Inhalt ist zu groß. Die Varianten und die Entscheidung stehen noch aus, siehe Abschnitt 5.

## 3 · Befund 3 — Cockpit lädt bei gesperrtem Bildschirm alle 5 s vier Stunden Messwerte (mittel)

- Logcat 10.10. 06:50–06:52: `CursorWindow: Window is full` alle ~5 s, dazu jeweils 12–28 MB
  Garbage Collection.
- Der Owner lässt das Cockpit offen und sperrt den Bildschirm.
- Mechanismus, im Code nachvollzogen, nicht gemessen:
  - `MeasurementRecorder` schreibt alle 5 s.
  - Room benachrichtigt `LiveCockpitCard` → `measurementDao().fuerSessionAbFlow()`, und der lädt
    das ganze 4-h-Fenster (~28.800 Zeilen) neu und rechnet die Kennwerte neu.
  - Der Sammler läuft in einem `LaunchedEffect` und ist nicht an den Lebenszyklus gebunden. Er
    sammelt deshalb auch bei gestopptem Activity, also bei gesperrtem Bildschirm, weiter.
- Folge: dauerhafte CPU-, Speicher- und Akkulast ohne Nutzen. Die Werte sieht niemand.
- Mögliche Abhilfe, noch nicht freigegeben: das Sammeln an den Lebenszyklus binden (nur im
  Zustand `STARTED`).

## 4 · Ohne neuen Code

- **Kadenzwächter:** 21 Kadenz-Trennungen mit Abständen von 0–219 ms. Das ist das bekannte
  Muster aus Befund D vom 07.10. Build 804 hat den Fix noch nicht; beurteilen lässt er sich erst
  nach dem Update am 12.10.
- **Datenstillstand jeden Morgen um ~06:45 Ortszeit:** Er fällt mit Mess-Stopp und den Fotos
  „kalibrierung“ und „messaufbau“ zusammen. Das ist die Kalibrier-Routine des Owners, kein
  Fehler.
- **„Kein Zugriffstoken verfügbar“:** nur am 07.10. 04:53–07:04 UTC, danach von selbst
  verschwunden.

## 5 · Offene Punkte

| Punkt | Wer | Wann wieder ansehen |
|---|---|---|
| E1: Inhalt und Form der Datenbanksicherung (Befund 2) | Owner | jetzt, vor einem Umsetzungsauftrag |
| Lebenszyklus-Bindung im Cockpit (Befund 3) | Owner-Freigabe | jetzt |
| Kadenzwächter auf aktuellem Build | Auswertung | erstes Bundle nach dem Update am 12.10. |
| Befund 1 auf dem Gerät bestätigen | Owner | nach dem Merge, an einem Tag mit vielen Ereignissen |
