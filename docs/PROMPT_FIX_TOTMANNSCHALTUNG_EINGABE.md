# Prompt: Bugfix — Eingabefeld für die Totmannschaltung wiederherstellen

**Priorität 1, Welle 1.** Befund J aus [`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

Die Totmannschaltung (Plan 7.5) ist der einzige Schutz, der auch dann alarmiert, wenn das
Messhandy selbst ausfällt oder die App zwangsbeendet wird. Am 06.10.2026 lief die App 18 Stunden
lang nicht, und niemand wurde benachrichtigt. Der Grund: **Die Ping-URL lässt sich seit dem
21.08.2026 nirgends eingeben.**

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/totmannschaltung-eingabefeld origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
  Beispiel: `fix(alarm): Eingabefeld für die Heartbeat-URL wiederherstellen`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`.
  ktlint ist auf `main` schon rot; Maßstab ist: **keine neuen Befunde in den geänderten Dateien.**
  Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK.
- **Keine Schemaänderung.** UI-Texte auf Deutsch, Bezeichner auf Englisch, neue Strings in
  **allen** Ressourcenordnern (`values`, `values-de`, `values-en`).
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Befund

- In M5 (Commit `b5b8e91c`, 18.08.2026) gab es in den Einstellungen ein Eingabefeld
  (`value = heartbeatUrl`, `onValueChange = { heartbeatUrl = it }`) und einen Knopf, der
  `settings.heartbeatUrl = heartbeatUrl` speicherte.
- Der UI-Umbau `179295e0` (21.08.2026, „Navigation Drawer, Onboarding, Papierkorb …“) hat den
  Alarmbereich neu gebaut. Die ntfy-Felder hat er übernommen, das Heartbeat-Feld nicht. Prüfe das mit
  `git show b5b8e91c -- app/src/main/java/com/example/lrmprotokoll/ui/` und
  `git show 179295e0 -- app/src/main/java/com/example/lrmprotokoll/ui/ | grep -i heartbeat`.
  Bei einem flachen Klon vorher `git fetch --unshallow origin`.
- Übrig ist nur die ungenutzte Zustandsvariable `SettingsScreen.kt:247`
  (`var heartbeatUrl by remember { mutableStateOf(settings.heartbeatUrl) }`).
- Die Logik dahinter ist vollständig und wird weiter geplant:
  - `HeartbeatWorker` und `HeartbeatPlanung` (alle 15 min, geplant in
    `AudioRecordingService.kt:309`, gestoppt beim ausdrücklichen Stopp);
  - `HeartbeatPinger.ping()` mit stillem `UEBERSPRUNGEN` bei leerer URL
    (`HeartbeatPinger.kt:56`).
- Belege aus den Bundles: In keinem P30-Bundle gibt es einen Heartbeat-Eintrag, weder den
  Breadcrumb `Heartbeat erfolgreich gesendet` noch `HEARTBEAT_SEND_FAILED`.
- Das README (Zeile 87) beschreibt die Funktion weiter als vorhanden.

---

## 2 · Auftrag

### Schritt 1 — Eingabefeld im Alarmbereich

- Ort: die Karte mit `title = stringResource(R.string.settings_alerting_title)` in
  `SettingsScreen.kt`, direkt nach dem ntfy-Block (`input_ntfy_topic`).
- Ein Abschnitt „Totmannschaltung“ mit kurzem Erklärtext. Inhalt sinngemäß: „Das Handy meldet sich
  alle 15 Minuten bei einem Überwachungsdienst, z. B. healthchecks.io. Bleibt die Meldung aus,
  alarmiert der Dienst – auch wenn das Handy oder die App ausgefallen ist.“
- `OutlinedTextField` für die URL, `testTag("input_heartbeat_url")`. Speichern im selben Muster wie
  die ntfy-Felder (`onValueChange` schreibt `settings.heartbeatUrl`). `SettingsManager` speichert
  verschlüsselt und trimmt selbst (`SettingsManager.kt:255`).
- **Prüfung der Eingabe:** Leer bedeutet „aus“ und ist erlaubt. Sonst muss die Eingabe eine
  `https://`-URL sein, die sich parsen lässt (`HttpUrl.parse`/`toHttpUrlOrNull`). Ungültige
  Eingaben werden nicht gespeichert; unter dem Feld steht ein Hinweis.
  `DiagnosticCode.HEARTBEAT_CONFIGURATION_INVALID` existiert, wird aber nirgends gemeldet. Prüfe,
  wofür er gedacht war (KDoc), und nutze ihn nur, wenn es passt.
- Die URL ist eine Capability-URL. Sie darf in keiner Meldung, keinem Log und keinem Breadcrumb
  erscheinen (siehe KDoc an `OkHttpClient.hole` in `HeartbeatPinger.kt`). Zeige sie im Feld wie
  die anderen Geheimnisse dieser Karte. Falls das ntfy-Topic maskiert wird, maskiere die URL
  genauso.

### Schritt 2 — Probe-Ping

- Knopf „Probe-Ping senden“, `testTag("btn_heartbeat_probe")`, neben dem Feld.
- `HeartbeatPinger.ping()` überspringt, solange `monitoringWasActive` falsch ist. Ein Probe-Ping
  muss aber auch ohne laufende Überwachung senden. Ergänze dafür eine eigene Methode, z. B.
  `HeartbeatPinger.probe(): Ergebnis`. Sie prüft nur die URL, nicht `monitoringWasActive`, und
  verwendet dieselbe HTTP-Funktion. Ändere `ping()` nicht.
- Zeige das Ergebnis wie beim lokalen Testalarm (`testErgebnis` in derselben Karte):
  „Probe-Ping angekommen“ oder „Fehlgeschlagen: Dienst nicht erreichbar / HTTP 404“, ohne URL.

### Schritt 3 — Dokumentation richtigstellen

- In README Zeile 87 und im Abschnitt zu M5 knapp festhalten, dass das Feld von 21.08. bis zu
  diesem PR fehlte.
- `docs/EXTERNE_DIENSTE_EINRICHTUNG.md` Abschnitt 2 sagt bisher nicht, **wo** die URL in der App
  eingetragen wird. Ergänze den Schritt: Einstellungen → „Alarmierung bei Verbindungsabbruch“ →
  Totmannschaltung, danach „Probe-Ping senden“.
- Gleiche dort die Karenzzeit (heute „10–15 Minuten“) an die KDoc von `HeartbeatWorker` an. Dort
  steht ein Überwachungsfenster von 45 min auf der Gegenseite, also 15 min Periode plus 30 min
  Kulanz. Doze und JobScheduler verzögern den 15-Minuten-Takt auf dem P30, eine kürzere Kulanz
  erzeugt Fehlalarme.

### Nicht Teil dieses Auftrags

- Kein neues Intervall (bleibt 15 min) und keine Änderung an `HeartbeatWorker` und
  `HeartbeatPlanung`.
- Keine Kopplung an den BLE-Zustand (Plan 7.5, „Wichtiges Detail“).
- Kein Hinweis im Cockpit, wenn die Totmannschaltung fehlt. Das ist eine offene Entscheidung,
  siehe Abschnitt 5.

---

## 3 · Tests (Robolectric/Compose, handgeschriebene Fakes)

Vorhandene Tests zu `SettingsScreen` und zum Heartbeat suchen
(`grep -rl "SettingsScreen\|HeartbeatPinger\|HeartbeatWorker" app/src/test`) und das Muster
übernehmen. Für den Pinger gibt es bereits einen Test gegen `MockWebServer`.

1. **Regressionstest für genau diesen Fehler:** Die Alarmkarte rendert ein Feld mit
   `input_heartbeat_url`. Eine gültige URL eintragen → `settings.heartbeatUrl` enthält sie.
   **Der Test muss ohne deine Änderung rot sein.** Zeig das im PR.
2. Eine ungültige Eingabe (`http://…`, `kein-url`) wird nicht gespeichert, der Hinweis erscheint.
3. Leeren des Feldes speichert `""`.
4. `HeartbeatPinger.probe()` gegen `MockWebServer`: 200 → `GESENDET`, 404 → `FEHLGESCHLAGEN`,
   leere URL → `UEBERSPRUNGEN`. **Auch bei `monitoringWasActive = false` wird gesendet.**
5. `ping()` verhält sich unverändert: Die bestehenden Tests bleiben grün.

Zu AGENTS.md §8b: Ein UI-Umbau hat eine Funktion stillschweigend unerreichbar gemacht. Test 1 ist
die Antwort darauf. Schlag im PR vor, ob eine allgemeinere Absicherung sinnvoll ist, etwa ein
Test „jede Einstellung in `SettingsManager` mit Nutzerbezug hat ein UI-Element“. Bau sie nicht
ohne Zustimmung des Owners.

---

## 4 · Akzeptanzkriterien

- [ ] Die Heartbeat-URL lässt sich im Alarmbereich eingeben und speichern (Test 1).
- [ ] Ungültige Eingaben werden abgewiesen (Test 2).
- [ ] Der Probe-Ping funktioniert auch ohne laufende Überwachung und meldet das Ergebnis (Test 4).
- [ ] Die URL erscheint in keiner Meldung und keinem Log. Belege das im PR mit einem `grep` über
      die geänderten Dateien.
- [ ] README und `EXTERNE_DIENSTE_EINRICHTUNG.md` sind korrigiert.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Offene Entscheidung (vor Umsetzung beim Owner klären, AGENTS.md §8a)

- **E1 — Hinweis bei fehlender Totmannschaltung?** Läuft eine Aufzeichnung und ist keine URL
  eingetragen, könnte das Cockpit dezent darauf hinweisen. Empfehlung: **nein in diesem PR.**
  Erst einrichten und am Gerät prüfen, dann über einen Hinweis entscheiden.

## 6 · Gerätecheck (macht der Owner nach dem Merge)

1. Bei healthchecks.io einen Check anlegen: Periode 15 min, Kulanz 30 min.
2. Die URL in der App eintragen, „Probe-Ping senden“ drücken. Bei healthchecks.io erscheint ein
   Ping.
3. Aufzeichnung starten und 30 min warten: Die Pings kommen im 15-Minuten-Takt.
4. Die App über Einstellungen → Apps → Lärmprotokoll → „Beenden erzwingen“ stoppen. Nach spätestens
   etwa 45 min alarmiert healthchecks.io.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
