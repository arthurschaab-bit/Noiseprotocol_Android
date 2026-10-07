# Prompt: Bugfix — Wächter-Job startet eine unerwartet beendete Aufzeichnung neu

**Priorität 5, Welle 2.** Befund G aus [`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Setzt `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md` voraus.** Dieser Auftrag nutzt dessen
`bewerteAufzeichnungsLage()` und dessen Code `RECORDING_ENDED_UNEXPECTEDLY`. Ist der PR dazu noch
nicht gemergt, warte darauf oder frag den Owner. Baue die Funktion nicht ein zweites Mal.

**Owner-Wunsch vom 07.10.2026:** „Gibt es eine Möglichkeit zu erkennen, wenn eigentlich eine
Messung aktiv war und von EMUI beendet wurde, und sie wieder zu starten?“

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c fix/aufzeichnungs-waechter origin/main`.
  Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `feat(audio): Wächter-Job startet beendete Aufzeichnung neu`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK. **Keine Schemaänderung.**
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Was ein Wächter kann und was nicht

Schreib diese Grenzen in die KDoc des Workers und in den PR-Text. Der Owner soll nicht mehr
erwarten, als die Plattform hergibt.

| Fall | Hilft der Wächter? |
|---|---|
| Prozess getötet, nicht zwangsbeendet (Speicher, EMUI-„Bereinigung“) | **ja.** JobScheduler startet den Job in einem neuen Prozess. |
| **Zwangsbeendet** (Einstellungen → „Beenden erzwingen“, oder EMUI per `forceStopPackage`) | **nein.** Android entfernt alle Jobs und Alarme, bis die App von Hand geöffnet wird. Dagegen helfen nur die Totmannschaltung und `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md`. |
| EMUI lässt die Jobs der App nicht laufen | **nein.** Befund H: Am 06.10. lief 4 h lang kein einziger Drive-Sync-Job, obwohl die App aufzeichnete. Siehe `PROMPT_UNTERSUCHUNG_HINTERGRUNDJOBS_DRIVE.md`. |

**Plattformgrenze Mikrofon:**
- Bis Android 10 (P30, SDK 29) darf ein aus dem Hintergrund gestarteter Foreground-Service das
  Mikrofon benutzen.
- **Ab Android 11** erhält ein solcher Dienst kein Mikrofon, er nimmt Stille auf.
- Ab Android 12 ist der Start aus dem Hintergrund grundsätzlich verboten
  (`ForegroundServiceStartNotAllowedException`).
- Ab Android 14 verlangt der Typ `microphone` einen erlaubten Zustand.

Ein Neustart aus dem Hintergrund ist deshalb **nur bis SDK 29** ehrlich. Ab SDK 30 darf der
Wächter nur benachrichtigen. Prüfe diese Grenzen gegen die aktuelle Android-Dokumentation und
korrigiere sie im PR, wenn sie nicht stimmen.

---

## 2 · Auftrag

### Schritt 1 — Worker

Neuer `CoroutineWorker`, z. B. `audio/AufzeichnungsWaechterWorker.kt`, mit einem
`…Planung`-Objekt nach dem Muster von `HeartbeatPlanung`:

- `PeriodicWorkRequest`, 15 min (Minimum von WorkManager), `enqueueUniquePeriodicWork` mit `UPDATE`.
- **Planen:** an derselben Stelle wie `HeartbeatPlanung.plane()` in
  `AudioRecordingService.onStartCommand`.
- **Stoppen:** im Zweig `ACTION_STOP_SERVICE`, neben `HeartbeatPlanung.stoppe(…)`. Ein
  ausdrücklich gestoppter Dienst darf nie wieder hochkommen.
- Testseam nach dem Muster von `HeartbeatWorker.pingerOverride`: Dienststarter, SDK-Stufe und
  Uhr sind injizierbar.

### Schritt 2 — Entscheidung im Worker

`bewerteAufzeichnungsLage(monitoringWasActive, AudioRecordingService.laeuft.value,
kannDienstInDenVordergrund(…))`. Läuft der Worker in einem frischen Prozess, ist `laeuft` korrekt
`false`.

- `LAEUFT` oder `AUS` → nichts tun, **nichts protokollieren** (kein Fluten alle 15 min).
- `SOLL_LAEUFT_NICHT`:
  1. `RECORDING_ENDED_UNEXPECTEDLY` melden, mit `quelle = "waechter"` und denselben Details wie
     im Fortsetzen-Auftrag.
  2. **SDK ≤ 29:** Dienst starten wie `BootCompletedReceiver`
     (`EXTRA_START_AUDIO_MONITORING = audioMonitoringWasActive`). Den Startversuch und sein
     Ergebnis protokollieren und Ausnahmen fangen.
  3. **SDK ≥ 30:** nicht starten. Stattdessen eine Benachrichtigung: „Aufzeichnung wurde vom
     System beendet – tippen zum Fortsetzen“. Ein Tipp öffnet `MainActivity`, die dann nach
     dem Fortsetzen-Auftrag selbst neu startet. Ab SDK 33 ohne `POST_NOTIFICATIONS`: nur
     protokollieren.
- `SOLL_ABER_NICHT_STARTBAR` → einmal protokollieren, nicht starten.
- **Schleifenschutz:** höchstens 3 Neustarts pro Stunde. Danach nur noch benachrichtigen und
  protokollieren. Zähler und Zeitfenster liegen in `SettingsManager`, nicht im Speicher, weil
  jeder Lauf ein neuer Prozess sein kann.

### Schritt 3 — Fernmeldung per ntfy (Owner-Entscheidung E1)

- Nur wenn ntfy eingerichtet ist (`settings.ntfyAktiv`, Topic gesetzt). Sonst nichts senden,
  auch keinen Fehler melden.
- Text je nach Fall:
  - SDK ≤ 29, Neustart erfolgt: „Aufzeichnung war vom System beendet (letzte Daten 12:41) und
    wurde neu gestartet.“
  - SDK ≥ 30: „Aufzeichnung wurde vom System beendet (letzte Daten 12:41). Zum Fortsetzen die
    App öffnen.“
  - Schleifenschutz greift: „Neustart der Aufzeichnung wiederholt gescheitert – bitte am Gerät
    prüfen.“
- Niedrige Priorität, kein Alarmton. Ein Verbindungsalarm des Messgeräts bleibt davon
  unterscheidbar.
- **Weg:** `AlertChannel.send(Alert)` kennt nur `AlertReason`-Werte zum Verbindungszustand. Eine
  neue `AlertReason` würde `AlarmCoordinator`, `AlertMessages` und eventuell die Tabelle `alerts`
  berühren. Prüfe das. Ist es mehr als eine Enum-Zeile mit Text, nutze stattdessen eine schmale
  eigene Methode für eine Textnachricht im Paket `alert/ntfy`. Sie verwendet denselben Server,
  dasselbe Topic und dieselbe Header-Behandlung (`alsHeaderWert`) wie `NtfyAlertChannel`, aber
  nicht dessen Alarmlogik. **Keine Schemaänderung.**
- Ein Sendefehler wird protokolliert und blockiert nie den Neustart.

### Nicht Teil dieses Auftrags

- Kein AlarmManager-Takt und kein zweiter Zeitgeber. Zwei konkurrierende Zeitgeber sind mehr
  Risiko als Gewinn, dieselbe Begründung steht in der KDoc von `HeartbeatWorker`.
- Keine Änderung an `START_STICKY`, `BootCompletedReceiver` oder der Totmannschaltung.

---

## 3 · Tests (JVM/Robolectric, `work-testing`, handgeschriebene Fakes)

`TestListenableWorkerBuilder` mit eigener `WorkerFactory`, wie bei den bestehenden Worker-Tests
(`grep -rl "TestListenableWorkerBuilder" app/src/test`).

1. Soll läuft nicht, SDK 29 → ein Startaufruf mit richtigem Extra und ein
   `RECORDING_ENDED_UNEXPECTEDLY` mit `quelle = "waechter"`.
2. Soll läuft nicht, SDK 34 → **kein** Start, eine Benachrichtigung.
3. Dienst läuft → kein Start, kein Eintrag.
4. Ausdrücklich gestoppt (Flags `false`) → kein Start, kein Eintrag.
5. Vierter Neustart innerhalb einer Stunde → kein Start, nur Benachrichtigung und Eintrag.
6. Der Starter wirft eine Ausnahme → `Result.success()`, Eintrag mit Fehler, kein Absturz.
7. `ACTION_STOP_SERVICE` storniert die Arbeit. Prüfen über `WorkManager.getWorkInfosForUniqueWork`
   mit `WorkManagerTestInitHelper`.
8. ntfy eingerichtet (Fake bzw. `MockWebServer`):
   - Fall SDK 29 → genau eine Nachricht mit „neu gestartet“;
   - Fall SDK 34 → „Zum Fortsetzen die App öffnen“;
   - ntfy nicht eingerichtet → keine Anfrage;
   - Sendefehler → Neustart trotzdem ausgeführt.

Die Tests 1, 2, 7 und 8 müssen ohne deine Änderung rot sein. Zeig das im PR.

---

## 4 · Akzeptanzkriterien

- [ ] Auf dem P30 läuft eine getötete Aufzeichnung nach spätestens etwa 15 min wieder, plus
      JobScheduler-Verzögerung (Test 1 und Gerätecheck).
- [ ] Ab SDK 30 kein stiller Fehlstart, stattdessen eine Benachrichtigung (Test 2).
- [ ] Nie ein Neustart nach ausdrücklichem Stopp (Tests 4 und 7).
- [ ] Kein Protokolleintrag im Normalfall, Schleifenschutz greift (Tests 3 und 5).
- [ ] Bei eingerichtetem ntfy erfährt der Owner von jedem Eingriff, ohne Alarmton (Test 8).
- [ ] Grenzen aus Abschnitt 1 in der KDoc und im PR.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Owner-Entscheidungen (07.10.2026, „Empfehlungen freigeben“)

- **E1 — Zusätzlich per ntfy melden: ja, wenn ntfy eingerichtet ist** (Schritt 3). Ein Neustart
  nach einem EMUI-Eingriff ist ein Ereignis, das der Owner kennen sollte.
- **E2 — Schleifenschutz: höchstens 3 Neustarts pro Stunde.** So startet die App nach einer
  EMUI-Bereinigung neu, kämpft aber nicht endlos gegen EMUI an.

## 6 · Gerätecheck (macht der Owner nach dem Merge, P30)

1. Aufzeichnung starten.
2. Prozess töten, **ohne** Zwangsbeenden:
   `adb shell run-as com.example.lrmprotokoll kill -9 $(adb shell pidof com.example.lrmprotokoll)`.
   Mit Debug-Builds funktioniert das ohne Root.
3. Spätestens nach etwa 20 min läuft die Aufzeichnung wieder, und das Protokoll enthält
   `RECORDING_ENDED_UNEXPECTEDLY` mit `quelle = waechter`. Läuft nach 30 min noch nichts:
   Befund H, die Jobs werden unterdrückt. Dann die EMUI-Einstellungen prüfen.
4. Gegenprobe mit „Beenden erzwingen“: Es startet **nichts** von selbst. Das ist zu erwarten, siehe
   Abschnitt 1.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
