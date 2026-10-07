# Prompt: Diagnose — Lebenszyklus-Protokoll, das jeden Ausfall abdeckt

**Priorität 9, Welle 3.** Befund I aus [`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Sinnvoll nach** `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md`, `PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md`
und `PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md`. Deren Einträge gehören in dieses Protokoll. Sind sie
schon gemergt, leite ihre Einträge zusätzlich hierher. Sind sie es nicht, lass dafür einen klar
benannten Einstiegspunkt, den die anderen nutzen können.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/diagnose-lebenszyklus-log origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `feat(diagnose): Lebenszyklus-Ringdatei für Prozess- und Dienstereignisse`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK. **Keine Schemaänderung**, siehe E1.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Befund

- **Breadcrumbs:** zwei Ringdateien zu je 256 KB (`BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES`). Im
  Bundle vom 07.10. sind von 1.557 Einträgen **1.552 von `AudioService`**, drei je WAV-Aufnahme
  (gestartet, beendet, gespeichert). Sie decken nur 06.10. 08:26–10:09 UTC ab.
- **`events.jsonl`:** im periodischen Bundle auf 1 MB begrenzt (`EVENTS_MAX_PERIODISCH`), etwa
  7.400 Einträge. An einem Messtag fallen 2.100–2.600 pro Stunde an, das Fenster reicht also nur
  etwa 3 h zurück.
- **`logcat.txt`** beginnt mit dem aktuellen Prozess und sagt nichts über den Tod des vorherigen.
- **Folgen am 06.10.:**
  - Der Ausfall um 04:26 lag vor jedem Fenster und lässt sich nicht untersuchen.
  - Ob das Handy neu gestartet wurde, ist nicht feststellbar. Die niedrige PID 4168 beim Start am
    07.10. ist nur ein schwacher Hinweis.
  - Android 10 hat kein `ApplicationExitInfo`. `ProcessExitCollector` liefert dort nichts.

---

## 2 · Auftrag

### Schritt 1 — Eigene Ringdatei für Lebenszyklus-Ereignisse

- Nach dem Muster von `BreadcrumbRingFile`, aber getrennt, z. B. `lebenszyklus_a.jsonl` und
  `lebenszyklus_b.jsonl`, je 64 KB. Seltene Ereignisse reichen damit über Wochen. Bei Bedarf
  durch die Größe begrenzen, nicht durch die Zeit.
- Einträge mit Zeit, Art und kleinen Details. Keine personenbezogenen Daten, Redaktion wie bei
  den Breadcrumbs (`DiagnosticRedactor`).
- **Inhalt:**

| Ereignis | Wo | Details |
|---|---|---|
| Prozessstart | `LaermprotokollApp.onCreate` | PID, `SystemClock.elapsedRealtime()` (Zeit seit Geräteneustart), `versionCode` |
| Gerätestart erkannt | ebenda | wenn `elapsedRealtime` kleiner ist als der Abstand zum letzten Prozessstart → „Gerät wurde seit dem letzten Lauf neu gestartet“ |
| Dienst gestartet / ausdrücklich gestoppt / `onDestroy` | `AudioRecordingService` | Aktion, `audioMonitoringWasActive` |
| `BOOT_COMPLETED` empfangen | `BootCompletedReceiver` | ob gestartet wurde |
| Unerwartetes Ende erkannt | aus `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md` und `PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md` | `letzteDatenAt`, Lücke, Quelle |
| Wechsel der Stromversorgung | aus `PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md` (Präfix `Stromversorgung:`) | Quelle, Status, Prozent |

- **Lebenszeichen:** Höchstens alle 15 min, nur bei laufender Aufzeichnung, ein Eintrag „lebt“
  mit Uhrzeit. Ohne ihn lässt sich der Todeszeitpunkt nur auf das letzte Ereignis eingrenzen.
  Prüfe, ob es dafür schon einen Takt gibt (z. B. `MeasurementRecorder.flush`), statt einen
  neuen Zeitgeber einzuführen.

### Schritt 2 — Ins Bundle

- `log/lebenszyklus.jsonl` **vollständig** in jedes Bundle aufnehmen, auch bei Kürzungsstufe 2.
- Den Eintrag in `SupportBundleExporter` an der Stelle von `breadcrumbs.jsonl` anlegen und die
  Kürzungsreihenfolge (Konzept 4.5) anpassen.
- `manifest.json` bzw. `checksums.sha256` kennen die neue Datei. Prüfe, ob Tests die Liste der
  Einträge festschreiben, und passe sie an.

### Nicht Teil dieses Auftrags

- Keine Änderung an der Ausführlichkeit der WAV-Breadcrumbs. Das ist ein eigener PR:
  `PROMPT_FIX_WAV_BREADCRUMBS_VERDICHTEN.md` (E2).
- Keine Änderung an `EVENTS_MAX_PERIODISCH` (E3).

---

## 3 · Tests (JVM/Robolectric, handgeschriebene Fakes)

1. Die Ringdatei rotiert an der Grenze, und die ältesten Einträge fallen weg. Wie
   `BreadcrumbRingFile`-Tests.
2. Zwei Prozessstarts mit Fake-Uhr und Fake-`elapsedRealtime`: zweiter Start mit kleiner
   Uptime → Eintrag „Gerät wurde neu gestartet“; große Uptime → kein solcher Eintrag.
3. Das Bundle enthält `log/lebenszyklus.jsonl` auch bei Kürzungsstufe 2.
4. Lebenszeichen höchstens alle 15 min und nur bei laufender Aufzeichnung.

---

## 4 · Akzeptanzkriterien

- [ ] Jedes Bundle enthält das vollständige Lebenszyklus-Protokoll der letzten Wochen (Test 3).
- [ ] Ein Neustart des Handys ist im Protokoll erkennbar (Test 2).
- [ ] Der Todeszeitpunkt eines Prozesses lässt sich auf 15 min eingrenzen (Test 4).
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Owner-Entscheidungen (07.10.2026, „Empfehlungen freigeben“)

- **E1 — Speicherort: Ringdatei**, keine Room-Tabelle (sonst Schema 26 mit Migration). Die
  Begründung ist dieselbe wie bei den Breadcrumbs (Konzept 4.3): Die Datei überlebt einen
  Absturz ohne Datenbank.
- **E2 — WAV-Breadcrumbs verdichten: ja, als eigener kleiner PR.** Statt drei Einträgen je
  Aufnahme einer. Das verlängert das Breadcrumb-Fenster etwa um das Dreifache. Auftrag:
  `PROMPT_FIX_WAV_BREADCRUMBS_VERDICHTEN.md`.
- **E3 — `EVENTS_MAX_PERIODISCH` bleibt bei 1 MB**, solange das Lebenszyklus-Protokoll die
  Ausfallfragen beantwortet. Anheben würde jeden täglichen Upload vergrößern.

## 6 · Gerätecheck (macht der Owner nach dem Merge)

1. Handy neu starten, App öffnen, Bundle erzeugen. `log/lebenszyklus.jsonl` enthält den Eintrag
   „Gerät wurde neu gestartet“.
2. Einen Tag aufzeichnen. Das Protokoll reicht im Bundle trotzdem bis zum Vortag zurück.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
