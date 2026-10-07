# Prompt: Bugfix — Unerwartet beendete Aufzeichnung beim Öffnen der App erkennen und fortsetzen

**Priorität 2, Welle 1.** Befund G aus [`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Owner-Freigabe vom 07.10.2026:** „Ja, mach die Vorschläge“. Ausdrücklich gewünscht ist:
erkennen, wenn eine Messung aktiv war und vom System (EMUI) beendet wurde, und sie **wieder
starten**. Dieser Auftrag deckt den Fall ab, dass die App geöffnet wird. Den Neustart ohne Zutun
des Nutzers regelt `PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md`, der auf diesem Auftrag aufbaut.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/aufzeichnung-beim-oeffnen-fortsetzen origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
  Beispiel: `fix(audio): Aufzeichnung nach unerwartetem Ende beim Öffnen fortsetzen`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`.
  Maßstab für ktlint: keine neuen Befunde in den geänderten Dateien. Nur bei Grün committen und
  pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK.
- **Keine Schemaänderung.** Eine neue `@Query` in einem bestehenden DAO ist erlaubt, eine neue
  Tabelle oder Spalte nicht.
- UI-Texte auf Deutsch, in **allen** Ressourcenordnern (`values`, `values-de`, `values-en`).
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Befund

- Am 06.10.2026 endete die Aufzeichnung auf dem P30 um 12:41:23 ohne Absturz und ohne
  `onDestroy()`. Der Prozess wurde hart beendet, vermutlich von EMUI.
- Am 07.10. um 06:52 öffnete der Owner die App. Die Aufzeichnung lief trotzdem nicht
  (`aufnahmeAktiv: false`) und begann erst um 06:59:52, als der Owner sie von Hand startete.
- So nimmt die App heute eine Aufzeichnung wieder auf:
  - `BootCompletedReceiver` nach einem Geräteneustart, wenn `monitoringWasActive` gesetzt ist und
    `kannDienstInDenVordergrund()` zustimmt (`audio/Dienstvoraussetzungen.kt`). Der Receiver gibt
    `EXTRA_START_AUDIO_MONITORING = audioMonitoringWasActive` mit.
  - `START_STICKY`, wenn Android den Dienst selbst neu anlegt. Nach einem Zwangsbeenden passiert
    das nicht.
  - `MainActivity.onResume()` ruft nur `meterAutoConnect.verbindeWennGewuenscht()`. **Das Öffnen
    der App startet keine Aufzeichnung.**
- Die Flags sind ein zuverlässiges Soll-Signal. `AudioRecordingService.onStartCommand` setzt
  `monitoringWasActive` und `audioMonitoringWasActive` beim Start. Gelöscht werden sie nur bei
  `ACTION_STOP_SERVICE` bzw. `ACTION_STOP_AUDIO_RECORDING`. Ein Prozesstod lässt sie stehen
  (KDoc von `BootCompletedReceiver`).
- `AudioRecordingService.laeuft` (Companion-`StateFlow`) gilt nur im aktuellen Prozess. In einem
  frischen Prozess ist es `false`, bis der Dienst startet.

---

## 2 · Auftrag

### Schritt 1 — Reine Entscheidungsfunktion

Neue Datei in `audio/`, z. B. `AufzeichnungsSoll.kt`, ohne Android-Abhängigkeit:

```kotlin
enum class AufzeichnungsLage { LAEUFT, AUS, SOLL_LAEUFT_NICHT, SOLL_ABER_NICHT_STARTBAR }

fun bewerteAufzeichnungsLage(
    monitoringWasActive: Boolean,
    dienstLaeuft: Boolean,
    kannInDenVordergrund: Boolean,
): AufzeichnungsLage
```

Der Wächter-Auftrag (`PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md`) nutzt dieselbe Funktion. Halte sie
deshalb frei von UI- und Activity-Details.

### Schritt 2 — Fortsetzen beim Öffnen

- Ort: `MainActivity.onResume()`. Die Activity ist dann im Vordergrund, ein Foreground-Service darf
  auf allen Android-Versionen starten, und die Mikrofon-Berechtigung „während der Nutzung“ ist
  erfüllt.
- Bei `SOLL_LAEUFT_NICHT`: Dienst starten wie `BootCompletedReceiver`, also
  `ContextCompat.startForegroundService` mit
  `EXTRA_START_AUDIO_MONITORING = settings.audioMonitoringWasActive`.
- Bei `SOLL_ABER_NICHT_STARTBAR` (Mikrofonberechtigung entzogen **und** kein Messgerät gekoppelt):
  nicht starten. Hinweis wie F-35 zeigen und in das Diagnoseprotokoll schreiben.
- **Doppelstart vermeiden:** `onResume` kommt oft. `laeuft` wird erst in `onStartCommand` wahr. Ein
  zweiter `onResume` dazwischen darf keinen zweiten Start und keinen zweiten Eintrag erzeugen.
  Verwende dafür einen Merker pro Prozess, z. B. in einem kleinen Koordinator im `AppContainer`
  (manueller DI, kein Hilt).
- `meterAutoConnect.verbindeWennGewuenscht()` bleibt unverändert.

### Schritt 3 — Die Lücke protokollieren

- Neuer Code `RECORDING_ENDED_UNEXPECTEDLY` in `diagnose/DiagnosticCode.kt`, mit KDoc wie bei den
  Nachbarn, z. B. `AUDIO_SERVICE_DESTROYED_WHILE_RECORDING`.
- Bei `SOLL_LAEUFT_NICHT` **vor** dem Start melden: `diagnosticsReporter.report(code = …,
  severity = WARN, …)` mit den Details:
  - `letzteDatenAt`: Zeitstempel des letzten Messwerts. Neue Abfrage in `LevelSampleDao`, z. B.
    `SELECT MAX(at) FROM level_samples`. Gibt es keinen, `null`.
  - `entdecktAt` (jetzt) und `lueckeMinuten`.
  - `audioWarAktiv` und `quelle = "app_geoeffnet"`. Der Wächter-Auftrag ergänzt später
    `"waechter"`.
- Die Abfrage läuft nicht auf dem Main-Thread. `level_samples` hat laut
  `app/schemas/com.example.lrmprotokoll.data.AppDatabase/25.json` den Index `index_level_samples_at`.
  `MAX(at)` ist damit auch bei mehreren Millionen Zeilen billig. Lege keinen weiteren Index an.

### Schritt 4 — Den Nutzer informieren

Die Unterbrechung muss sichtbar sein. In welcher Form, entscheidet der Owner (E1 in Abschnitt 5).
Setz die Empfehlung erst nach seiner Antwort um.

### Nicht Teil dieses Auftrags

- Neustart ohne geöffnete App: `PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md`.
- Lücken in CSV und Verlauf: `PROMPT_FIX_AUFZEICHNUNGSLUECKEN_SICHTBAR.md`.
- Keine Änderung an `BootCompletedReceiver`, `START_STICKY` oder den Flag-Regeln im Dienst.

---

## 3 · Tests (JVM/Robolectric, handgeschriebene Fakes)

1. `bewerteAufzeichnungsLage`: alle acht Kombinationen als Tabelle.
2. Robolectric, Activity oder Koordinator, Flags gesetzt, Dienst läuft nicht:
   - genau **ein** Start-Intent für `AudioRecordingService` mit dem richtigen Extra
     (`ShadowApplication.getNextStartedService()`);
   - genau **ein** `RECORDING_ENDED_UNEXPECTEDLY` im Fake-Reporter;
   - Details enthalten `letzteDatenAt` aus dem Fake-DAO.
3. Zweimal `onResume` hintereinander → trotzdem nur ein Start und ein Eintrag.
4. Flags nicht gesetzt (ausdrücklich gestoppt) → kein Start, kein Eintrag.
5. Dienst läuft (`laeuft = true`) → kein Start, kein Eintrag.
6. Keine Mikrofonberechtigung und kein Messgerät → kein Start, Hinweis und Eintrag.

Die Tests 2, 3 und 6 müssen ohne deine Änderung rot sein. Zeig das im PR.

---

## 4 · Akzeptanzkriterien

- [ ] Wird die App nach einem unerwarteten Ende geöffnet, läuft die Aufzeichnung ohne Zutun wieder
      (Test 2).
- [ ] Eine ausdrücklich gestoppte Aufzeichnung wird nie wieder gestartet (Test 4).
- [ ] Jede erkannte Unterbrechung steht mit Dauer im Diagnoseprotokoll und damit in jedem Bundle
      (Test 2).
- [ ] Der Nutzer sieht die Unterbrechung in der vom Owner gewählten Form.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Offene Entscheidungen (vor Umsetzung beim Owner klären, AGENTS.md §8a)

- **E1 — Wie wird die Unterbrechung angezeigt?**
  - (a) Snackbar: „Aufzeichnung war von 12:41 bis 06:52 unterbrochen und läuft wieder.“
  - (b) Hinweiskarte im Cockpit, die stehen bleibt, bis der Nutzer sie bestätigt. Gespeichert in
    `SettingsManager`.

  **Empfehlung: (b).** Eine Snackbar ist nach wenigen Sekunden weg, die Unterbrechung ist aber
  für das Protokoll relevant.
- **E2 — Ohne Rückfrage starten?** Laut Owner-Freigabe ja. Bestätige das im PR-Text, frag nicht
  erneut.

## 6 · Gerätecheck (macht der Owner nach dem Merge, P30)

1. Aufzeichnung starten.
2. Einstellungen → Apps → Lärmprotokoll → „Beenden erzwingen“.
3. App öffnen. Die Aufzeichnung läuft innerhalb weniger Sekunden wieder. Die Unterbrechung wird
   angezeigt (E1).
4. Diagnose-Screen: Ein Eintrag `RECORDING_ENDED_UNEXPECTEDLY` mit plausibler Dauer.
5. Gegenprobe: Aufzeichnung in der App beenden, App schließen und wieder öffnen. Es startet
   **nichts**.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
