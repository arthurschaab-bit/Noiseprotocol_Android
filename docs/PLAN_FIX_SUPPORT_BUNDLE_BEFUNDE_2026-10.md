# Umsetzungsplan: Behebung der Support-Bundle-Befunde (Oktober 2026)

Dieser Plan adressiert die in [`BEFUNDE_SUPPORT_BUNDLES_2026-10-02.md`](file:///c:/Users/arthu/AndroidStudioProjects/Lrmprotokoll/Noiseprotocol_Android/docs/BEFUNDE_SUPPORT_BUNDLES_2026-10-02.md) identifizierten Schwachstellen und Stabilitätsprobleme.

Die Umsetzung erfolgt in vier klar voneinander isolierten Abschnitten, jeweils über einen eigenen Pull Request gegen `main`.

---

## Übersicht der Abschnitte & PR-Reihenfolge

| Abschnitt | Thema | Hauptkomponenten | Ziel |
|---|---|---|---|
| **Abschnitt 1** | Neueste Diagnosedaten in Support-Bundles exportieren | `SupportBundleExporter.kt`, `DiagnosticLogDao.kt` | Diagnosefähigkeit wiederherstellen: Immer die neuesten Ereignisse im Bundle |
| **Abschnitt 2** | Datenbanksicherung bei großen Datenbanken gangbar machen | `SicherungManager.kt`, `DriveSyncCoordinator.kt` | Platzprüfung korrigieren, ZIP-Kompression berücksichtigen, Sicherungsausfall beheben |
| **Abschnitt 3** | DB-Retention & CursorWindow-Schutz für `level_samples` | `DriveSyncCoordinator.kt`, `LevelSampleDao.kt` | 1-GB-Datenbank entlasten: Synchronisierte Rohwerte zügig bereinigen, Chunk-Größe halbieren |
| **Abschnitt 4** | MeterAutoConnect-Backoff im Leerlauf schonen | `MeterAutoConnect.kt`, `ConnectionSupervisor.kt` | Unnötige BLE-Verbindungsschleifen bei inaktivem Monitoring drosseln |

---

## Abschnitt 1: SupportBundleExporter — Neueste Diagnose-Events exportieren

### Problem
`SupportBundleExporter.schreibeEventsSeitenweise` liest die Tabelle `diagnostic_log_entries` aufsteigend ab `id = 0`. Bei über 48.000 Zeilen ist das 1-MB-Budget (`eventsMax`) bereits nach 7.422 Zeilen (Stand September) erschöpft. Aktuelle Fehler aus Oktober landen nicht im Bundle.

### Änderungen
1. **`DiagnosticLogDao.kt`**:
   * Neue Query: `seiteRueckwaerts(vorId: Long, limit: Int)` oder `letzteEintraege(limit: Int)` mit `ORDER BY id DESC`.
2. **`SupportBundleExporter.kt`**:
   * `schreibeEventsSeitenweise` so anpassen, dass vom höchsten vorhandenen ID-Wert rückwärts gelesen wird (neueste Einträge zuerst), bis das Byte-Budget erreicht ist.
   * Formatierung in `events.jsonl`: Die Zeilen können chronologisch oder absteigend geschrieben werden (mit `isoTime`).
3. **Tests**:
   * Unit-Test in `SupportBundleExporterTest.kt`: Bei einer Datenbank mit mehr Einträgen als dem Byte-Budget landen nachweislich die *neuesten* Logeinträge im Bundle.

---

## Abschnitt 2: SicherungManager — Datenbanksicherung bei großen Datenbanken gangbar machen

### Problem
`SicherungManager.baueSicherungsDatei` prüft:
`benoetigterPlatz = dbDatei.length() * 1.1`
Bei 1,05 GB DB-Größe werden ~1,16 GB freier Speicher im Cache verlangt. SQLite-Datenbanken komprimieren im ZIP-Format typischerweise um 60–80 % (reale ZIP-Größe liegt meist unter 350 MB). Zudem verhindert ein harter Abbruch vor dem Schreiben jede Möglichkeit, eine Sicherung zu erstellen, wenn der Cache-Speicher knapp unter 1,16 GB liegt.

### Änderungen
1. **`SicherungManager.kt`**:
   * Realistischere Speicherplatzschätzung: Bei sehr großen Datenbanken (> 200 MB) kann der Kompressionsfaktor als Puffer berücksichtigt werden (z. B. konservative 0,6x bis 0,8x der Rohgröße plus fester Sicherheitszuschlag von 50 MB, statt 1,1x unkomprimiert).
   * Detaillierte Fehlerursache in `UnzureichenderSpeicherplatzException` beibehalten und in `DriveSyncCoordinator.ladeDatenbankSicherungHoch` im Diagnosebericht mit konkreten Zahlen melden.
2. **Tests**:
   * Unit-Tests in `SicherungManagerTest.kt` und `DriveSyncCoordinatorTest.kt` anpassen und validieren.

---

## Abschnitt 3: DB-Retention & CursorWindow-Schutz für `level_samples`

### Problem
1. **Explosive DB-Größe:** `DriveSyncCoordinator` hält Rohwerte pauschal 30 Tage vor (`levelSampleDao.loescheVor(jetzt.minus(Duration.ofDays(30)))`). Nach erfolgreichem Drive-Upload der aggregierten Tages-CSV (`laermprotokoll_YYYY-MM-DD.csv`) werden die 10-Hz-Rohwerte lokal jedoch nicht mehr benötigt.
2. **CursorWindow-Überläufe:** 1-Stunden-Chunks (`abschnittDauer = 1 Stunde`) enthalten ~36.000 Samples, die das 2-MB-SQLite-CursorWindow sprengen.

### Änderungen
1. **Gezielte Rohdaten-Bereinigung in `DriveSyncCoordinator.kt`**:
   * Behalte die 30-Tage-Frist **nur** für Tage, die *noch nicht* erfolgreich nach Drive synchronisiert wurden (`drive_daily_files.state != SYNCED`).
   * Für Tage, deren CSV-Datei bereits erfolgreich in Drive hochgeladen und verifiziert wurde, können die Rohwerte in `level_samples` nach 3 Tagen (statt 30 Tagen) bereinigt werden.
   * Das reduziert die Tabelle von 19 Millionen Zeilen auf unter 2 Millionen Zeilen (~90 % Platzersparnis).
2. **CursorWindow-Schutz in `aggregiereInAbschnitten`**:
   * Reduziere die Abschnittsdauer von 60 Minuten auf 30 Minuten (oder nutze `abschnittDauer = minOf(abschnittDauer, 30.minutes)`).
   * Dadurch enthält jeder SQLite-Cursor maximal ~18.000 Zeilen, was sicher innerhalb der 2-MB-Grenze bleibt und die `CursorWindow: Window is full`-Warnungen und GC-Spitzen eliminiert.
3. **Tests**:
   * `DriveSyncCoordinatorTest.kt`: Tests für Retention synchronisierter vs. nicht-synchronisierter Tage und Cursor-Abschnitte.

---

## Abschnitt 4: MeterAutoConnect im Leerlauf drosseln

### Problem
Wenn der Nutzer die Messung beendet (`STOP_SERVICE`), bleibt `meter_auto_connect = true` aktiv. `MeterAutoConnect` versucht alle 15 Minuten endlos, das PCE-323 per Bluetooth zu finden, selbst wenn das Messgerät ausgeschaltet oder mit einem anderen Handy gekoppelt ist. Dies erzeugt Fehlerkaskaden (`BLE_STREAM_STALLED`, Reconnect-Logs) und führt zu Android `LOW_MEMORY`-Prozessabschüssen.

### Änderungen
1. **`MeterAutoConnect.kt` / `ConnectionSupervisor.kt`**:
   * Wenn `AudioRecordingService` **nicht** aktiv ist (Dienst im Ruhezustand), wird der Wiederholungs-Backoff nach den initialen 8 Fehlversuchen progressiv verlängert (z. B. von 15 min auf 1 Stunde, oder pausiert bis zum nächsten App-Vordergrund/Messungsstart).
2. **Tests**:
   * `MeterAutoConnectTest.kt`: Verhalten bei inaktivem Service prüfen.

---

## CI & Qualitätskriterien für jeden PR
* `./gradlew assembleDebug lintDebug test` muss lokal fehlerfrei durchlaufen.
* `./gradlew ktlintCheck` darf keine neuen Befunde in geänderten Dateien erzeugen.
* PR erstellen, CI-Prüfungen auf GitHub beobachten und nachbessern, bis alle Workflows grün sind.
