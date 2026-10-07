# Prompt: Diagnose — Eine statt drei Spuren je WAV-Aufnahme

**Priorität 10, Welle 3.** Folgeauftrag zu Befund I aus
[`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md). Er geht auf die
Owner-Entscheidung E2 im Auftrag
[`PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md`](PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md) zurück
(07.10.2026: „ja, aber als eigener kleiner PR“).

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c fix/wav-breadcrumbs-verdichten
  origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `fix(diagnose): WAV-Aufnahme mit einer statt drei Spuren protokollieren`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK. **Keine Schemaänderung.**
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Befund

- Je WAV-Aufnahme schreibt `AudioRecordingService` drei Breadcrumbs:
  - `WAV-Aufnahme gestartet` (`AudioRecordingService.kt:1228`);
  - `WAV-Aufnahme beendet` (`:1264`);
  - `NoiseRecord gespeichert (KI=…, Rohdaten=…)` (`:1342`).
- Im Bundle vom 07.10. stammen 1.552 von 1.557 Breadcrumbs von `AudioService`. Die Ringdatei
  (2 × 256 KB) deckte deshalb nur 1 h 43 min ab. Dieselben Einträge füllen auch `events.jsonl`
  mit 2.100–2.600 Einträgen pro Stunde.

---

## 2 · Auftrag

- Statt drei Breadcrumbs **einer** am Ende jeder Aufnahme, z. B. „WAV-Aufnahme gespeichert“.
  Die `data` vereinigt alle bisherigen Felder der drei Einträge:
  - Dateiname, Abtastrate, Ziel- und Ist-Dauer, Bytes, `unterbrochen`, `meterConnected`;
  - Pegel beim Start, `recordId`, KI-Ergebnis, `rohdatenGespeichert`, Quelle, Kanäle, AGC.
- Wird nach der WAV kein `NoiseRecord` gespeichert (Fehlerpfad), entsteht trotzdem genau ein
  Eintrag, der das sagt. Prüfe alle Pfade zwischen Start und Speichern.
- **Fehler und Unterbrechungen melden weiter** über ihre `DiagnosticCode`s
  (`AUDIO_WAV_INTERRUPTED` usw.). Diese Meldungen bleiben unverändert.
- Bekannter Verlust: Wird der Prozess hart beendet, während eine Aufnahme läuft, gibt es für diese
  Aufnahme keinen Eintrag mehr. Bisher stand dann wenigstens „gestartet“ da. Den Todeszeitpunkt
  liefert künftig das Lebenszyklus-Protokoll. Halte das im PR fest.
- Prüfe, ob etwas die alten Texte liest, z. B. der Diagnose-Screen, Tests oder
  `NoiseClassifier.kt:422` (Kommentar). Passe es an.

---

## 3 · Tests und Akzeptanzkriterien

1. Eine vollständige Aufnahme erzeugt genau **einen** `AudioService`-Breadcrumb mit allen Feldern.
   Vorher waren es drei, **vorher rot**.
2. Fehlerpfad ohne gespeicherten `NoiseRecord` → genau ein Eintrag mit Fehlerkennzeichen.
3. Die bestehenden Tests zu `AUDIO_WAV_INTERRUPTED` bleiben grün.

- [ ] Ein Breadcrumb je Aufnahme (Tests 1 und 2).
- [ ] Keine Fehlermeldung geht verloren (Test 3).
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 4 · Gerätecheck (macht der Owner nach dem Merge)

- Nach einem Messtag reicht `breadcrumbs.jsonl` im Bundle etwa dreimal so weit zurück wie bisher
  (Vergleich: 1 h 43 min am 06.10.).

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
