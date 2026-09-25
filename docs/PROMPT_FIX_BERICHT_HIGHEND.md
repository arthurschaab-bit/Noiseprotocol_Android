# Prompt: Bugfix — Gescheiterter High-End-Bericht hinterlässt keine Spur

**Priorität 2.** Der Owner hat am 23.09.2026 auf dem Huawei P30 einen High-End-Bericht erzeugen
wollen. Das ist gescheitert, und im direkt danach erstellten Support-Bundle
(`2026-09-23_164324_manuell.zip`) gibt es **keine einzige Zeile** dazu: nichts in den Breadcrumbs,
in `events.jsonl` oder im Logcat. Belege: [`BEFUNDE_P30_2026-09-23.md`](BEFUNDE_P30_2026-09-23.md),
Abschnitt 3.

**Die eigentliche Ursache des Fehlschlags ist unbekannt.** Dieser Auftrag rät sie nicht. Er macht
Fehlschläge sichtbar und beseitigt ein belegtes Speicherrisiko im Export. Den Grund liefert dann
der nächste Versuch auf dem Gerät.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c fix/bericht-highend-diagnose
  origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `fix(bericht): Fehlschlag des High-End-Berichts an die Diagnose melden`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`.
  - ktlint: keine neuen Befunde in den geänderten Dateien.
  - Nur bei Grün committen und pushen. Ist ein Test rot, den deine Änderung nicht berührt: mit
    Ausgabe im PR melden und den Owner fragen.
- **Nur handgeschriebene Fakes**, kein Mockito, kein MockK.
- **Keine Schemaänderung.** Der Vertrag zur Python-Seite (`contractVersion`, CSV-Spalten,
  JSON-Felder) bleibt **unverändert**.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Der Befund

### 1.1 Fehlschläge gehen nur an die Oberfläche

- `ui/BerichtErstellenSheet.kt`:
  - **Vorprüfung** (etwa Zeile 110–122: `retentionFehler`, `bewertungsFehler`, `auswahlFehler`,
    `areaSelectionError`): Eine Meldung landet nur in `meldung` im Sheet.
  - **Erzeugung** (etwa Zeile 125–138): `ChaquopyReportRunner.Ergebnis.Fehler` landet ebenfalls nur
    in `meldung`.
- `report/HighEndReportExport.kt` und `report/ChaquopyReportRunner.kt` erzeugen `Ergebnis.Fehler`,
  melden aber nichts an den `DiagnosticsReporter`.
- `diagnose/DiagnosticCode.kt` hat `REPORT_CREATE_FAILED`, der Code wird aber **nirgends
  verwendet** (`grep -rn REPORT_CREATE_FAILED app/src/main` zeigt nur die Definition).

### 1.2 Der Export lädt pro Tag alle Messwerte als Liste

`report/HighEndReportExport.kt`, `generate()`, Zeile 49:

```kotlin
val rows = db.measurementDao().zwischen(day.von, day.bis)   // ganzer Tag als List<MeasurementEntity>
```

- `measurements` hat auf dem Owner-Gerät 575.403 Zeilen. Der Heap steht auf dem Gerät ohnehin
  unter Druck (siehe `PROMPT_FIX_OOM_DRIVE_SYNC.md`).
- Ein `OutOfMemoryError` ist ein `Error`, keine `Exception`. Er fällt durch das
  `catch (error: Exception)` in Zeile 98 **und** durch das in `BerichtErstellenSheet` und bringt
  die App zum Absturz, statt eine Meldung zu zeigen.
- Der ganze Export läuft in `db.withTransaction { … }`. **Nicht umbauen**, aber die Dauer messen
  (siehe Schritt 2).

---

## 2 · Auftrag

### Schritt 1 — Jeder Fehlschlag landet im Diagnose-Log

- **Vorprüfung scheitert** (`BerichtErstellenSheet`): Breadcrumb Kategorie `"Bericht"`, Level INFO,
  Text `"High-End-Bericht nicht gestartet: <meldung>"`. Das ist eine Nutzerangabe, kein Fehler,
  deshalb **kein** Report-Event.
- **Erzeugung startet:** Breadcrumb `"High-End-Bericht gestartet: <n> Tage"`.
- **Erzeugung scheitert:**
  - `diagnosticsReporter.report(code = DiagnosticCode.REPORT_CREATE_FAILED, component =
    "HighEndReportExport", operation = "generate", severity = WARN, message = <Fehler.nachricht>,
    cause = <Fehler.ursache>, details = …)`.
  - `details`: Anzahl Tage, Summe `rawSampleCount`, Dauer in ms, Phase (`vorpruefung`, `export`,
    `python`, `pdfpruefung`).
  - Die Phase ergibt sich aus der Stelle, an der der Fehler entsteht. Führe dafür eine kleine
    Phasenvariable in `generate()` mit.
- **Erzeugung gelingt:** Breadcrumb `"High-End-Bericht erzeugt: <Tage> Tage, <Rohwerte> Rohwerte,
  <Dauer> ms, PDF <KB> KB"`.
- Den `DiagnosticsReporter` in `HighEndReportExport` per Konstruktor hineingeben. Der einzige
  Aufrufer ist `BerichtErstellenSheet.kt` (Zeile 127); dort aus dem Container holen.
- Den Text nicht selbst redigieren: Der Reporter bzw. der Bundle-Export laufen bereits durch den
  `DiagnosticRedactor`. **Prüfe**, dass Dateipfade in der Meldung im Bundle geschwärzt erscheinen.

### Schritt 2 — Export streamend statt tageweise als Liste

- In `generate()` die Messwerte eines Tages **abschnittsweise** lesen und direkt in die CSV
  schreiben, z. B. je Stunde über das vorhandene `measurementDao().zwischen(von, bis)`.
  Alternativ über eine neue, nach `timestamp` sortierte Seitenabfrage mit `LIMIT`; dann dieselbe
  Sortierung wie bisher sicherstellen.
- Die Session-IDs (`rows.map { it.sessionId }`) während des Schreibens in einem `Set` sammeln.
- **Die CSV muss byteweise identisch sein** mit der bisherigen: gleiche Kopfzeile, gleiche
  Reihenfolge, gleiche Formatierung.
- `rawSampleCount` wird mitgezählt statt `rows.size`.
- Die Dauer der Transaktion messen und im Erfolgs-Breadcrumb mitgeben (siehe Schritt 1). Blockiert
  der Export laut Messung länger als ein paar Sekunden, gilt:
  - **nicht umbauen**;
  - im PR mit Zahl melden: Die Aufnahme schreibt währenddessen in dieselbe Datenbank.

### Schritt 3 — Speichermangel wird zur Meldung statt zum Absturz

**Owner-Vorgabe (vorbelegt), im PR ausdrücklich nennen:**
- In `HighEndReportExport.generate()` zusätzlich `OutOfMemoryError` fangen, und nur dort.
- Dann `REPORT_CREATE_FAILED` mit Phase und dem Hinweis „zu wenig Arbeitsspeicher“ melden und
  `Ergebnis.Fehler("Bericht konnte nicht erzeugt werden: zu wenig Arbeitsspeicher. Bitte einen
  kürzeren Zeitraum wählen.")` zurückgeben.
- Begründung: Die großen Listen des Exports sind beim Fangen nicht mehr erreichbar. Ein vom Nutzer
  ausgelöster Bericht soll die laufende Messung nicht mit in den Absturz reißen.
- **Kein** allgemeines `catch (Throwable)`, kein Fangen an anderer Stelle.

### Schritt 4 — Grund beim Owner einsammeln

- Im PR einen kurzen Abschnitt „Nach dem Merge“ ergänzen:
  - Der Owner erzeugt denselben High-End-Bericht auf dem P30 noch einmal (gleicher Zeitraum wie am
    23.09.) und erstellt danach ein Bundle.
  - Zeigt das Bundle dann eine **Vorprüfungsmeldung** oder einen **fachlichen Python-Fehler**
    (`ValueError`): Das ist ein eigener, neuer Befund. **Nicht in diesem PR beheben**, sondern an
    den Owner melden.

### Nicht Teil dieses Auftrags

- Keine Änderung an Python-Code, Berichtsinhalt, Vorprüfungsregeln oder Transaktionsumfang.

---

## 3 · Tests (JVM/Robolectric, handgeschriebene Fakes)

Vorhandene Tests zu `HighEndReportExport`, `ChaquopyReportRunner` und `BerichtErstellenSheet` suchen
(`grep -rl "HighEndReportExport\|BerichtErstellenSheet" app/src/test`). Deren Fakes wiederverwenden,
z. B. den `runner`-Parameter von `generate()`.

1. **Fehler wird gemeldet:** Der Runner gibt `Ergebnis.Fehler` zurück. Danach gibt es genau ein
   `REPORT_CREATE_FAILED` mit Phase `python`, und der Rückgabewert ist unverändert der Fehler.
2. **Exportfehler wird gemeldet:** Eine Ausnahme im Export (z. B. schreibgeschütztes
   Temp-Verzeichnis) ergibt `REPORT_CREATE_FAILED` mit Phase `export`.
3. **Vorprüfung:** Eine ungültige Auswahl ergibt einen Breadcrumb „High-End-Bericht nicht
   gestartet: …“ und **kein** Report-Event.
4. **CSV unverändert:** Ein Tag mit mehr Messwerten als ein Abschnitt fasst (≥ 3 Abschnitte, mit
   Session-Wechsel) ergibt eine CSV, die byteweise gleich der bisherigen ist. Den alten Algorithmus
   im Test als Referenz nachbilden.
5. **Speichergrenze als Proxy:** Kein Messwert-Abfrageaufruf umfasst mehr als einen Abschnitt.
6. **OOM:** Ein Fake-Runner wirft `OutOfMemoryError`. Ergebnis: `Ergebnis.Fehler` mit dem Text aus
   Schritt 3, ein `REPORT_CREATE_FAILED`, und die Temp-Dateien sind gelöscht.
7. **Erfolg:** Breadcrumb „High-End-Bericht erzeugt: …“ mit Tagen, Rohwerten und Dauer.

Die Tests 1–3, 5 und 6 müssen ohne deine Änderung rot sein. Zeig das im PR.

---

## 4 · Akzeptanzkriterien

- [ ] Jeder gescheiterte High-End-Versuch hinterlässt im nächsten Bundle ein
      `REPORT_CREATE_FAILED` mit Phase und Grund. Jede abgelehnte Vorprüfung hinterlässt einen
      Breadcrumb.
- [ ] Der CSV-Handoff an Python ist byteweise unverändert (Test 4).
- [ ] Keine Messwert-Abfrage über mehr als einen Abschnitt (Test 5).
- [ ] `OutOfMemoryError` im Export führt zu einer Meldung statt zu einem Absturz (Test 6).
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Gerätecheck (macht der Owner nach dem Merge)

- Den High-End-Bericht vom 23.09. wiederholen, danach „Bundle jetzt erstellen und hochladen“.
- Im Bundle unter `log/events.jsonl` bzw. `log/breadcrumbs.jsonl` steht entweder „High-End-Bericht
  erzeugt: …“ oder ein `REPORT_CREATE_FAILED` bzw. „nicht gestartet: …“ mit Grund. Den Grund an
  Claude/Owner zurückmelden.

Trag diesen Check als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein (Ergebnis-Spalte
leer).
