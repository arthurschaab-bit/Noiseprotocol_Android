# Prompt: Bugfix — Absturz-Bundles zeigen den Laufzeitzustand zum falschen Zeitpunkt

**Priorität 2.** Gerade bei den Speicherabstürzen (siehe
[`BEFUNDE_P30_2026-09-23.md`](BEFUNDE_P30_2026-09-23.md), Abschnitt 2) fehlt die wichtigste
Angabe: Wie voll war der Heap, lief eine Aufnahme, wie war die BLE-Verbindung **im Moment des
Absturzes**? Diese Lücke ist eine **Planabweichung aus M12**:
[`DIAGNOSE_CRASH_KONZEPT.md`](DIAGNOSE_CRASH_KONZEPT.md) sieht im Diagramm „Absturzmoment“ einen
`LaufzeitzustandCollector` (Heap, Akku, Rechte, Dienste) vor. **Er wurde nie gebaut.**

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c fix/laufzeitzustand-beim-absturz
  origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `feat(diagnose): Laufzeitzustand im Absturzmoment per ACRA-Collector erfassen`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`.
  - ktlint: keine neuen Befunde in den geänderten Dateien.
  - Nur bei Grün committen und pushen. Ist ein Test rot, den deine Änderung nicht berührt: mit
    Ausgabe im PR melden und den Owner fragen.
- **Nur handgeschriebene Fakes**, kein Mockito, kein MockK. Keine Schemaänderung.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Der Befund

Absturz-Bundle vom echten `OutOfMemoryError` um 16:14 (`2026-09-23_161407_absturz.zip`),
`state/runtime.json`:

```json
"heapUsedBytes": 3765880,
"heapMaxBytes": 402653184,
"bleVerbindungszustand": "UNBEKANNT",
"aufnahmeAktiv": false
```

- Die App ist aber mit **vollem** Heap (402 MB) abgestürzt.
- Die 3,7 MB gehören zu dem Prozess, der das Bundle **nach** dem Absturz baut (ACRA-Sender bzw.
  nächster Start).
- `crash/acra_report.json` hat `"CUSTOM_DATA": {}`.
- Dass BLE auf `UNBEKANNT` steht, ist ebenfalls auffällig: `AppContainer.kt` (Zeile 190/191)
  verdrahtet `bleVerbindungszustandProvider` und `aufnahmeAktivProvider` für den normalen
  Exporter. Der Pfad, der Absturz-Bundles baut, nutzt offenbar Standardwerte (`"UNBEKANNT"`,
  `false`, siehe `SupportBundleExporter.kt` Zeile 99/100).

Folge: Die Zeile „Absturz während laufender Aufzeichnung → `aufnahmeAktiv: true`“ der
Gerätecheckliste F14 kann so nie bestehen.

---

## 2 · Auftrag

### Schritt 1 — Collector für den Absturzmoment

- Neuer ACRA-Collector `LaufzeitzustandCollector` in `diagnose/acra/`, registriert per
  `@AutoService(Collector::class)`.
- **Vorbild ist `BreadcrumbRingCollector`** im selben Paket: eigener Report-Schlüssel
  (z. B. `LAUFZEITZUSTAND`), **nicht** `CUSTOM_DATA`, aus dem dort beschriebenen Grund.
- Er läuft im abstürzenden Prozess und erfasst:
  - Heap: `Runtime.totalMemory/freeMemory/maxMemory`;
  - `ActivityManager.MemoryInfo`: `availMem`, `totalMem`, `lowMemory`, `threshold`;
  - `AudioRecordingService.audioAufnahmeAktiv.value`;
  - BLE-Verbindungszustand;
  - laufende Dienste;
  - Zeitstempel der Erfassung.
- **Besonders wichtig bei OOM:**
  - Nur **bereits vorhandene** Zustände lesen, nichts initialisieren. Den `AppContainer` nicht
    anlegen oder erweitern, wenn er noch nicht existiert: Ein `lazy` im Absturzpfad kann einen
    zweiten OOM auslösen.
  - Für den BLE-Zustand einen Weg wählen, der ohne Initialisierung auskommt. Gibt es keinen, das
    Feld weglassen und im PR begründen.
  - Jede Einzelangabe in `runCatching` (auch gegen `Throwable`); ein Fehlschlag darf den Report nicht
    verhindern.
  - **Keine** Berechtigungsliste und keine Einstellungen: zu teuer, und im Absturzmoment nicht
    nötig.
- Den gemeinsamen Teil mit `SupportBundleExporter.buildRuntimeJson()` (Zeile 332) als kleine
  Hilfsfunktion herausziehen, statt ihn zu kopieren. Aber nur die billigen Teile; die
  Berechtigungsliste bleibt im Exporter.

### Schritt 2 — Im Bundle sichtbar machen

- Enthält der ACRA-Report den neuen Schlüssel, schreibt `SupportBundleExporter` ihn als
  **`crash/laufzeitzustand_beim_absturz.json`** ins Bundle.
- Er läuft durch den `DiagnosticRedactor`, wie `crash/acra_report.json`.
- `state/runtime.json` bekommt ein Feld `"erfasst": "bei Bundle-Erstellung"`, damit niemand die
  zwei Zeitpunkte verwechselt.
- Prüfe, warum der Absturzpfad `bleVerbindungszustand` = `UNBEKANNT` liefert: Welcher Exporter wird
  dort gebaut, und ohne welche Provider?
  - Liegt es an einer fehlenden Verdrahtung, die sich **ohne** Initialisierung schwerer Teile
    beheben lässt: beheben.
  - Sonst im PR beschreiben.

### Schritt 3 — Checkliste anpassen

In `docs/CHECKLISTE_GERAETETEST.md`, F14, die Zeile „Absturz **während laufender Aufzeichnung**“
auf `crash/laufzeitzustand_beim_absturz.json` umschreiben. Die Ergebnis-Spalte leer lassen.

### Nicht Teil dieses Auftrags

- Keine Änderung an Breadcrumbs, ANR-Watchdog, Upload oder den anderen Bundle-Dateien, außer dem
  einen Feld in `state/runtime.json`.

---

## 3 · Tests (JVM/Robolectric, handgeschriebene Fakes)

Vorbild sind die vorhandenen Tests zu `BreadcrumbRingCollector` und `SupportBundleExporter`.

1. **Collector:**
   - Mit gesetztem `audioAufnahmeAktiv = true` legt `collect()` den Schlüssel mit
     `aufnahmeAktiv: true` und plausiblen Heap-Werten ab (`maxMemory > 0`).
   - Den Testzustand danach zurücksetzen, damit nachfolgende Tests nicht verfälscht werden.
2. **Collector robust:** Wirft eine Einzelabfrage (per injizierter Fake-Quelle), fehlt nur dieses
   Feld; der Rest ist da und es gibt keine Ausnahme.
3. **Exporter:** Ein ACRA-Report mit dem Schlüssel ergibt `crash/laufzeitzustand_beim_absturz.json`
   im ZIP, einer ohne Schlüssel ergibt keine solche Datei.
4. **`state/runtime.json`** enthält `"erfasst": "bei Bundle-Erstellung"`.

Die Tests 1 und 3 müssen ohne deine Änderung rot sein. Zeig das im PR.

---

## 4 · Akzeptanzkriterien

- [ ] Jedes neue Absturz-Bundle enthält `crash/laufzeitzustand_beim_absturz.json` mit Heap,
      Speicherinfo, Aufnahmezustand und (falls ohne Initialisierung möglich) BLE-Zustand **zum
      Absturzzeitpunkt**.
- [ ] Der Collector initialisiert nichts und kann den Report nicht verhindern.
- [ ] `state/runtime.json` ist als „bei Bundle-Erstellung“ gekennzeichnet.
- [ ] Die Planabweichung ist im PR genannt: Konzept-Diagramm vs. bisheriger Stand.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Gerätecheck (macht der Owner nach dem Merge)

- Aufnahme starten, dann im Diagnose-Screen „RuntimeException auslösen“.
- Im Bundle zeigt `crash/laufzeitzustand_beim_absturz.json` `aufnahmeAktiv: true` und einen Heap-Wert
  des abgestürzten Prozesses, also deutlich mehr als wenige MB.
