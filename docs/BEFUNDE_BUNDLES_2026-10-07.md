# Befunde aus den Support-Bundles vom 05.–07.10.2026 und dem Aufzeichnungsausfall am 06.10.

Auswertung von fünf Support-Bundles und fünf Tages-CSVs. Anlass waren zwei Fragen des Owners:
„Ist da etwas zu fixen?“ (Bundles vom 05.–07.10.) und „Ab ca. 13 Uhr wurde am 06.10. nichts mehr
aufgezeichnet – nur ein Anzeigefehler?“. Es war **kein** Anzeigefehler.

Zu jedem Befund mit Handlungsbedarf gibt es einen eigenen Auftrag (`docs/PROMPT_…`).
Die empfohlene Reihenfolge steht in Abschnitt 11.

| Bundle | Gerät | App-Version | erstellt (UTC) |
|---|---|---|---|
| `e8cf878f-2026-10-05_035959_periodisch.zip` | Pixel 9 Pro, SDK 37 | `ci764+bbf1a09` | 05.10. 02:00 |
| `049bf932-2026-10-05_063115_periodisch.zip` | **Huawei P30 (ELE-L29), SDK 29** | `ci794+1e1c13f` | 05.10. 04:31 |
| `7e6334e3-2026-10-06_040100_periodisch.zip` | Pixel 9 Pro | `ci764` | 06.10. 02:01 |
| `109b0743-2026-10-07_040556_periodisch.zip` | Pixel 9 Pro | `ci764` | 07.10. 02:05 |
| `2026-10-07_065216_periodisch.zip` (aus Drive) | **Huawei P30** | `ci804+52945c7` | 07.10. 04:52 |

Tages-CSVs aus Drive: `laermprotokoll_2026-10-01`, `-02`, `-05`, `-06`, `-07`. Das P30 ist das
Messgerät im Einsatz, der Pixel ein Entwicklungsgerät ohne laufende Aufzeichnung.

Die Bundles liegen **nicht** im Repository (persönliche Daten). Zeiten sind Ortszeit (MESZ), wenn
nicht „UTC“ dabeisteht. Die Zitate unten stammen wörtlich aus den Bundles.

---

## 1 · Überblick

| # | Befund | Schwere | Auftrag |
|---|---|---|---|
| G | Aufzeichnung am 06.10. zweimal still beendet (04:26–06:53 und 12:41–07.10. 06:59), kein automatischer Neustart | **hoch** | `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md`, `PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md`, `PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md`, `PROMPT_FIX_AUFZEICHNUNGSLUECKEN_SICHTBAR.md` |
| J | **Totmannschaltung seit 21.08. nicht einrichtbar**: das Eingabefeld für die Ping-URL ist bei einem UI-Umbau verschwunden | **hoch** | `PROMPT_FIX_TOTMANNSCHALTUNG_EINGABE.md` |
| D | Der Kadenzwächter löst fast alle Verbindungstrennungen aus, vermutlich Fehlalarme | mittel | `PROMPT_FIX_KADENZWAECHTER.md` |
| H | Am 06.10. liefen schon vor dem Ausfall keine Hintergrundjobs (kein einziger Drive-Sync in 4 h Aufzeichnung) | mittel | `PROMPT_UNTERSUCHUNG_HINTERGRUNDJOBS_DRIVE.md` |
| B | Datenbanksicherung: 25 Fehlschläge am 01./02.10., davon 16 × „Job was cancelled“ | mittel | wie H |
| C | Gescheiterte Verbindungsaufbauten erscheinen in keiner Kennzahl, und die Logmeldung ist irreführend | niedrig | `PROMPT_FIX_VERBINDUNGSFEHLVERSUCHE_MELDEN.md` |
| I | Das Diagnosefenster eines Bundles reicht an einem Messtag nur rund 3 h zurück | niedrig | `PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md`, `PROMPT_FIX_WAV_BREADCRUMBS_VERDICHTEN.md` |
| A, E, F | Messgerät aus, Pixel-Speicherkills, alte OOMs | — | keiner, siehe Abschnitt 9 |

---

## 2 · Befund G — Die Aufzeichnung ist am 06.10. zweimal still beendet worden

### Zeitachse

| Zeit | Was passiert | Beleg |
|---|---|---|
| 06.10. 00:00–04:26:43 | Aufzeichnung läuft (`GEMISCHT`) | CSV 06.10. |
| **04:26:44–06:53:43** | **keine Daten**, 2 h 27 min, `KEINE_VERBINDUNG` mit 0 Samples | CSV 06.10. |
| 06:53:44 | Neustart: 13 s `MIKROFON`, ab 06:53:57 `GEMISCHT`. Dasselbe Muster wie beim Start von Hand am 07.10. | CSV 06.10. |
| 12:09:13 | letztes Ereignis (`20261006_12_09_13_3011.wav`, recordId 34047), letzter Diagnose-Eintrag 12:09:17 | `events.jsonl`, `breadcrumbs.jsonl` |
| **12:41:23** | **letzter Messwert.** Danach endet die CSV. | CSV 06.10., am 07.10. 06:55 neu erzeugt |
| bis 07.10. 06:52 | **18 h ohne einen einzigen Log-Eintrag** | `events.jsonl` |
| 07.10. 06:52:09 | Prozessstart durch Öffnen der App (`MainActivity`, PID 4168) | `logcat.txt` |
| 06:52:16 | Bundle: `aufnahmeAktiv: false`, `bleVerbindungszustand: STREAMING` | `runtime.json` |
| 06:59:52 | Aufzeichnung läuft wieder (5 s `MIKROFON`, dann `GEMISCHT`). Starten geht nur über App, Kachel oder Widget. | CSV 07.10. |

### Was die Daten sagen

- **Kein Absturz.** Im Crash-Puffer steht kein neuer Eintrag. Es fehlt auch der Breadcrumb
  `AudioRecordingService wird beendet`, den `AudioRecordingService.onDestroy()` schreibt. Die
  Ringdatei reicht bis 12:09:17 und hätte einen späteren Eintrag enthalten. Der Prozess wurde
  also **hart beendet**, ohne `onDestroy()`.
- **Kein automatischer Neustart.** Die App nimmt die Aufzeichnung nur auf zwei Wegen selbst wieder
  auf: über `BootCompletedReceiver` nach einem Geräteneustart und über `START_STICKY`, wenn Android
  den Dienst neu anlegt. Nach einem Zwangsbeenden greift keiner von beiden. Das **Öffnen der App
  startet nichts**: `MainActivity.onResume()` ruft nur `meterAutoConnect.verbindeWennGewuenscht()`.
- `monitoring_was_active` und `audio_monitoring_was_active` stehen auf `true`. Die Flags werden
  nur beim ausdrücklichen Stopp gelöscht, ein Prozesstod lässt sie stehen. Damit ist „sollte
  laufen, läuft aber nicht“ zuverlässig erkennbar.
- Die Versionen wechselten zwischen den Bundles von `ci794` auf `ci804`. Laut Owner wurde zu
  den Ausfallzeiten **kein** Update installiert.

### Ursache: nicht beweisbar, aber eingegrenzt

Android 10 meldet keinen Exit-Grund (`ApplicationExitInfo` gibt es erst ab API 30). Was der Owner
am 07.10. ergänzt hat:

- Kein Update zu diesen Zeitpunkten.
- Der Owner war nicht vor Ort, hat die App also nicht weggewischt. Übrig bleibt: **Das System hat
  die App beendet.** Auf einem Huawei ist das typischerweise die EMUI-Energieverwaltung.
- Das Handy hängt an einer **USB-Powerbank** und hatte morgens 92–93 %. Möglicher Auslöser:
  Die Powerbank schaltet bei vollem Akku ab. Das Handy läuft dann auf Akku, und EMUI beendet
  „energieintensive“ Hintergrund-Apps. Eine App, die rund um die Uhr Mikrofon und Bluetooth
  offen hält, ist ein typisches Ziel.
- **Belegen lässt sich der Ladezustand nicht**, weil die App ihn nicht protokolliert. Der Auftrag
  `PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md` ändert das, damit der nächste Ausfall beweisbar ist.

Der Ausfall um 04:26 lässt sich nicht untersuchen, weil er vor dem Fenster jedes Bundles liegt
(Befund I).

### Was Code leisten kann und was nicht

Gegen ein echtes Zwangsbeenden kann sich keine App wehren. Bis zum nächsten Start von Hand
laufen dann weder Dienste noch WorkManager-Jobs, Alarme oder Broadcasts. Erreichbar ist trotzdem
Folgendes:

1. **Beim Öffnen der App automatisch fortsetzen** und die Lücke als „unerwartet beendet“
   protokollieren und anzeigen. Das hilft immer, auch nach einem Zwangsbeenden.
2. **Ein Wächter-Job, der neu startet.** Er hilft nur, wenn der Prozess getötet, aber nicht
   zwangsbeendet wurde, und vollständig nur auf dem P30 (Android 10). Ab Android 11 darf ein aus
   dem Hintergrund gestarteter Dienst das Mikrofon nicht nutzen.
3. **Die Totmannschaltung** meldet den Totalausfall von außen. Das ist der einzige Schutz, der
   auch nach einem Zwangsbeenden wirkt. Sie ist aber nicht einrichtbar (Befund J).
4. **Lücken sichtbar machen.** Die Tagesdatei vom 06.10. hört um 12:41:23 einfach auf, statt den
   Rest des Tages als Lücke auszuweisen. Darum sah der Ausfall nach „Anzeigefehler“ aus.

### Die Vortage zum Vergleich

| Tag | aufgezeichnet |
|---|---|
| 01.10. | 06:28 bis 23:59:59 |
| 02.10. | 00:00 bis 20:12, ab 13:50 nur Mikrofon (Messgerät aus) |
| 03./04.10. | keine Datei. Der Owner hatte über das Wochenende ausgeschaltet. |
| 05.10. | 06:50 bis 23:59:59 |
| 06.10. | 00:00–04:26, Lücke bis 06:53, dann bis 12:41 |
| 07.10. | ab 06:59:52 |

---

## 3 · Befund J — Die Totmannschaltung lässt sich seit 21.08. nicht einrichten

`HeartbeatWorker` sendet alle 15 Minuten einen Ping an `SettingsManager.heartbeatUrl`. Bleibt der
Ping aus, alarmiert die Gegenseite (Plan 7.5, Empfehlung healthchecks.io). Die Totmannschaltung
ist also genau für den Fall gebaut, der am 06.10. eingetreten ist.

- **Das Eingabefeld fehlt.** In M5 (`b5b8e91c`, 18.08.2026) kamen ein Eingabefeld und ein
  Speichern-Knopf dazu. Der UI-Umbau `179295e0` vom 21.08.2026 („Navigation Drawer,
  Onboarding, …“) hat den Alarmbereich neu gebaut und dabei die ntfy-Felder übernommen, das
  Heartbeat-Feld aber nicht. Übrig ist nur die ungenutzte Zustandsvariable `SettingsScreen.kt:247`
  (`var heartbeatUrl by remember …`). `git log -G heartbeatUrl` zeigt danach keinen Commit,
  der das Feld zurückbringt.
- **Die Bundles bestätigen es.** Ein erfolgreicher Ping schreibt den Breadcrumb `Heartbeat
  erfolgreich gesendet`, ein gescheiterter `HEARTBEAT_SEND_FAILED`. Bei leerer URL wird er
  **still übersprungen** (`HeartbeatPinger.kt:56`). In beiden P30-Bundles gibt es **keinen**
  Heartbeat-Eintrag, obwohl die App etwa am 02.10. von 12:43 bis 19:03 UTC aufzeichnete. Das
  wären rund 25 Pings gewesen.
- Das README (Zeile 87) beschreibt die Funktion weiterhin als vorhanden.

---

## 4 · Befund H — Am 06.10. liefen schon vor dem Ausfall keine Hintergrundjobs

| Fenster | `Drive-Sync-Zyklus gestartet` |
|---|---|
| 01.10. 14:14 – 05.10. 04:31 UTC (Bundle 05.10.) | **207 ×** |
| 06.10. 05:58 – 10:09 UTC, App lebt und zeichnet auf | **0 ×** |
| 07.10. 04:52:15 UTC, direkt nach dem Öffnen der App | 3 × in derselben Millisekunde, dazu 1 × „wartet auf laufenden Zyklus“ |

`AudioRecordingService` stößt nach jeder WAV-Aufnahme einen Sofort-Sync an. Am 06.10. zwischen
05:58 und 10:09 UTC entstanden Dutzende Aufnahmen, aber kein einziger Sync startete. Zuletzt
erfolgreich synchronisiert wurde am 05.10. um 12:19:15 UTC (`drive_sync_last_success_at`). Die
letzte Datenbanksicherung startete am 05.10. um 11:55:49 UTC, ein Erfolg ist danach nicht
verzeichnet.

Ungeklärt, drei Kandidaten:
1. Die Netzwerkbedingung war nicht erfüllt („nur WLAN“ ohne WLAN).
2. EMUI hat die Jobs der App schon gedrosselt. Das würde zum Zwangsbeenden um 12:41 passen.
3. Ein laufender Job wurde nie fertig und blockiert die eindeutigen Arbeiten (`KEEP`, periodisch).

Der Befund ist auch für Befund G wichtig: Dürfen Jobs nicht laufen, läuft auch kein Wächter-Job.

## 5 · Befund B — Datenbanksicherung: Abbrüche am 01./02.10. (korrigiert)

Im Bundle vom 05.10.:
- 9 × `BACKUP_CREATE_FAILED … Drive nicht erreichbar`, 01.10. 14:16–21:19 UTC
- 16 × `BACKUP_CREATE_FAILED … Job was cancelled`, 01.10. 18:40 – 02.10. 16:42 UTC

**Richtigstellung:** In der ersten Auswertung stand „noch nie durchgelaufen“. Das war falsch: Am
05.10. um 10:59:10 UTC gelang eine Sicherung (`datenbank_sicherung_last_success_at`). Die
Datenbank war am 05.10. 1,10 GB groß, am 07.10. nur noch 0,53 GB, nachdem die 30-Tage-Bereinigung
von `level_samples` gegriffen hatte (18,9 Mio. → 7,6 Mio. Zeilen).

„Job was cancelled“ ist vermutlich das 10-Minuten-Limit von JobScheduler bei einem großen Upload.
Belegt ist das nicht, weil der Abbruchgrund nicht protokolliert wird. Untersucht wird es im
selben Auftrag wie H.

## 6 · Befund D — Der Kadenzwächter verursacht fast alle Verbindungstrennungen

| Fenster | Kadenz-Trennungen | Trennungen durch Datenstillstand | (Wieder-)Verbindungen |
|---|---:|---:|---:|
| P30, 01.10. 14:14 – 05.10. 04:31 UTC | **5** | 1 | 6 |
| Pixel, 23.09. – 07.10. | **9** | 3 | 19 |

Gemessene Abstände gegen das Fenster `[257, 772] ms` (515 ms ± 50 %, `AppContainer.kt:266`):
**0, 1, 2, 3, 6, 27, 135, 223, 850 ms**. Abstände von 0–6 ms sind bei einem Gerät, das zweimal pro
Sekunde sendet, physikalisch unmöglich.

Wahrscheinlicher Mechanismus, im Code nachvollzogen, aber nicht gemessen: Der Wächter
(`ConnectionSupervisor.kt`, `cadenceWatcher`) sammelt den **StateFlow** `transport.lastFrameAt`
und nimmt als Ankunftszeit `now.now()` **beim Sammeln**. Ein StateFlow fasst Werte zusammen.
`BleMeterTransport.onNotify()` setzt `lastFrameAt` für jedes Frame einer Notification einzeln. Der
Wächter misst also, wann sein Sammler drankommt, nicht, wann die Frames ankamen. Verzögert sich
der Sammler, folgen auf einen langen Abstand (850 ms) mehrere fast leere (0–6 ms).

Jede Kadenz-Trennung kostet einen Reconnect und erzeugt ein `DEGRADED`-Ereignis, also ein rotes
Ausfallband. Das drückt die Messintegrität im Bericht.

## 7 · Befund C — Gescheiterte Verbindungsaufbauten erscheinen in keiner Kennzahl

- Vom 02.10. 11:51 bis 04.10. 06:39 UTC: 244 × `Kein Frame innerhalb von 5000ms nach
  Verbindungsaufbau - Versuch verworfen`. `health_metrics.json` meldet trotzdem
  `"reconnectCount":0` und `"fehlerJeCode":{}`.
- `ConnectionSupervisor.attemptOnce()` schreibt im `NEVER_STREAMED`-Pfad nur
  `diagnosticLogger?.protokolliere(…)`: kein `DiagnosticCode`, keine Kennzahl.
- **Die Meldung ist irreführend.** Sie erscheint auch, wenn der Transport sofort `FAILED` oder
  `DISCONNECTED` meldet, wenn also gar keine Verbindung zustande kam. Das Messgerät war in diesem
  Zeitraum aus (Owner, 07.10.), der Text behauptet einen Verbindungsaufbau.
- `DiagnosticCode.BLE_CONNECT_FAILED` gibt es, er wird aber nirgends gemeldet.
- Dass `reconnectCount` diese Fälle nicht zählt, ist dagegen gewollt: `MeasurementRecorder.onState`
  schreibt vor dem ersten `STREAMING` bewusst kein `ConnectionEvent` („Ausfälle einer Verbindung,
  die nie zustande kam, gehören zu keinem Messvorgang“).

## 8 · Befund I — Das Diagnosefenster ist an einem Messtag zu kurz

- `breadcrumbs.jsonl` im Bundle vom 07.10.: 1.557 Einträge, davon **1.552 von `AudioService`**,
  3 je WAV-Aufnahme. Sie decken nur 06.10. 08:26–10:09 UTC ab, also 1 h 43 min.
- `events.jsonl` ist im periodischen Bundle auf 1 MB begrenzt (`EVENTS_MAX_PERIODISCH`). Das
  sind etwa 7.400 Einträge. An einem Messtag fallen etwa 2.100–2.600 pro Stunde an, das Fenster
  reicht also nur **rund 3 h** zurück. Am 06.10. reichte es nur deshalb über 23 h, weil die App
  18 h lang tot war.
- `logcat.txt` beginnt mit dem Prozessstart und sagt deshalb nichts über den Tod des vorherigen
  Prozesses.
- Folge: Der Ausfall um 04:26 lässt sich nicht untersuchen. Ob das Handy neu gestartet wurde, ist
  ebenfalls nicht feststellbar. Die niedrige PID 4168 beim Start am 07.10. ist nur ein schwacher
  Hinweis darauf.

---

## 9 · Befunde ohne eigenen Auftrag

- **A — Messgerät seit 02.10. weg.** Der Owner hatte es über das Wochenende ausgeschaltet. Auch
  die 22 h ohne Eintrag (04.10. 06:39 – 05.10. 04:31 UTC) bei Zustand `CONNECTING` sind kein
  Hänger. Ohne laufende Aufzeichnung ist der Prozess ein gewöhnlicher Hintergrundprozess und darf
  beendet werden. Der Logcat des Bundles beginnt um 06:31:13 mit einem frischen GATT-Connect, den
  der periodische Bundle-Job ausgelöst hat. Die irreführende Logmeldung behandelt Befund C.
- **E — Pixel 19 × `LOW_MEMORY`.** **Richtigstellung:** In der ersten Auswertung stand
  „kein Zufall“. Der Pixel zeichnet nicht auf (`aufnahmeAktiv: false`, nur `SystemJobService`).
  Er ist ein zwischengespeicherter Hintergrundprozess, den Android bei Speicherbedarf beendet.
  Das ist normales Verhalten. Die längeren Anlaufabstände im Leerlauf
  (`failedRetryIntervalLeerlauf`) behandeln die Folgen bereits.
- **F — Die OOM-Abstürze.** Erledigt durch den Streaming-Umbau (`BEFUNDE_P30_2026-09-23.md`).
  Der letzte Absturz war am 25.09. um 07:36. Der Crash-Puffer ist nur noch historisch.
- **`CursorWindow: Window is full`.** 16 Warnungen in den ersten Sekunden nach dem Start am
  07.10.: Abfragen mit mehr als 2 MB Ergebnis. Keine Fehlfunktion, nur beobachten.

## 10 · Sofortmaßnahmen beim Owner (kein Code)

1. **EMUI:** Einstellungen → Akku → App-Start → Lärmprotokoll → „Manuell verwalten“. Dort alle
   drei Schalter an: Automatisch starten, Sekundärstart, Im Hintergrund ausführen.
2. **App in der Übersicht sperren:** In der Ansicht der letzten Apps die Karte herunterziehen und
   das Schloss setzen.
3. **Stromversorgung:** möglichst ein Steckernetzteil. Bei einer Powerbank eine mit
   Dauerstrom- oder Kleinstrommodus („Low Current“, „IoT“), die bei vollem Akku nicht abschaltet.
4. **Totmannschaltung:** Bei healthchecks.io einen Check anlegen (Periode 15 min, Kulanz 30 min).
   Eintragen lässt sich die URL erst nach `PROMPT_FIX_TOTMANNSCHALTUNG_EINGABE.md`.

---

## 11 · Reihenfolge der Aufträge

Die Reihenfolge richtet sich danach, wie schnell ein Auftrag Datenverlust verhindert oder sichtbar
macht, wie klein er ist und welche Dateien er berührt. Aufträge einer Welle können parallel
laufen, weil sie verschiedene Dateien berühren. Jeder Auftrag ist ein eigener Draft-PR.

| Welle | Nr. | Auftrag | Größe | hängt ab von | berührt vor allem |
|---|---|---|---|---|---|
| 1 | 1 | `PROMPT_FIX_TOTMANNSCHALTUNG_EINGABE.md` | klein | — | `SettingsScreen.kt`, Strings |
| 1 | 2 | `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md` | klein–mittel | — | `MainActivity.kt`, neue Datei in `audio/`, `DiagnosticCode.kt` |
| 1 | 3 | `PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md` | klein | — | `AudioRecordingService.kt` (nur Empfänger), `SupportBundleExporter.kt` |
| 1 | 4 | `PROMPT_FIX_VERBINDUNGSFEHLVERSUCHE_MELDEN.md` | klein | — | `ConnectionSupervisor.kt` (`attemptOnce`, Schleife) |
| 2 | 5 | `PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md` | mittel | 2 | neuer Worker, `AudioRecordingService.kt` (Planung) |
| 2 | 6 | `PROMPT_FIX_KADENZWAECHTER.md` | mittel | 4 (gleiche Datei) | `ConnectionSupervisor.kt` (`cadenceWatcher`), `BleMeterTransport.kt` |
| 2 | 7 | `PROMPT_UNTERSUCHUNG_HINTERGRUNDJOBS_DRIVE.md` | mittel, erst Untersuchung | — | `drive/`, `SupportBundleExporter.kt` |
| 3 | 8 | `PROMPT_FIX_AUFZEICHNUNGSLUECKEN_SICHTBAR.md` | groß | — (E1–E4 entschieden) | `PegelAggregator.kt`, `DriveSyncCoordinator.kt`, `PegelverlaufChart.kt` |
| 3 | 9 | `PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md` | mittel | sinnvoll nach 2, 3, 5 | `diagnose/`, `SupportBundleExporter.kt` |
| 3 | 10 | `PROMPT_FIX_WAV_BREADCRUMBS_VERDICHTEN.md` | klein | nach 3 und 5 (gleiche Datei) | `AudioRecordingService.kt` (WAV-Breadcrumbs) |

**Warum diese Reihenfolge**

- **1 zuerst:** kleinster Aufwand, größte Wirkung. Ein Ausfall wie am 06.10. hätte nach etwa
  45 Minuten einen Alarm ausgelöst statt 18 Stunden unbemerkt zu bleiben.
- **2:** Danach wird der Morgen-Neustart automatisch, und jede Unterbrechung wird mit Dauer
  protokolliert. Das hilft auch nach einem Zwangsbeenden.
- **3:** Macht den nächsten Ausfall beweisbar (Powerbank-Hypothese).
- **4:** Klein und unabhängig. Muss vor 6 fertig sein, weil beide `ConnectionSupervisor.kt`
  ändern.
- **5:** Nutzt die Erkennung aus 2. Wie viel er bringt, hängt von den EMUI-Einstellungen und von 7
  ab.
- **6:** Verbessert die Datenqualität deutlich, weil die meisten Ausfallbänder wegfallen.
  Sicherheitsrelevante BLE-Logik: Prüfung gegen `CHECKLISTE_M6_SICHERHEITSREVIEW.md`.
- **7:** Erst instrumentieren, dann entscheiden. Klärt auch, ob 5 unter EMUI überhaupt laufen kann.
- **8:** Der Wunsch des Owners, aber der größte Eingriff. Er ändert das Dateiformat aus Plan
  8.4.2. Die vier Entscheidungen dazu hat der Owner am 07.10. getroffen (Abschnitt 12).
- **9:** Verbessert die nächste Ausfallanalyse. Nimmt die Einträge aus 2, 3 und 5 auf, wenn diese
  schon gemergt sind.
- **10:** Klein. Verlängert das Breadcrumb-Fenster etwa um das Dreifache. Er ändert
  `AudioRecordingService.kt` an anderer Stelle als 3 und 5; um Konflikte zu vermeiden, kommt er
  danach.

**Für jeden Auftrag gilt:** Eine **andere** Sitzung, möglichst ein anderes Modell, prüft ihn mit
`docs/PROMPT_REVIEW.md`. Ein Reviewer prüft nie die eigene Umsetzung (AGENTS.md §9). Die
Gerätechecks trägt jeder Auftrag in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein.

---

## 12 · Owner-Entscheidungen vom 07.10.2026

Der Owner hat am 07.10.2026 alle Empfehlungen der Aufträge freigegeben („Empfehlungen
freigeben“). Die Einzelheiten stehen jeweils in Abschnitt 5 des Auftrags.

| Auftrag | Entscheidung |
|---|---|
| 1 Totmannschaltung | E1: kein Cockpit-Hinweis bei fehlender URL in diesem PR |
| 2 Fortsetzen | E1: Hinweiskarte im Cockpit, bis „Verstanden“; E2: automatisch fortsetzen, ohne Rückfrage |
| 4 Fehlversuche | E1: neuer Code `BLE_NO_FIRST_FRAME`; E2: eine Meldung je Grund und gescheiterter Runde |
| 5 Wächter | E1: ntfy-Meldung, wenn eingerichtet; E2: höchstens 3 Neustarts pro Stunde |
| 8 Lücken | E1: ganzer Tag 00:00:00–23:59:59; E2: `KEINE_AUFZEICHNUNG` aus den Sessions, kein Schema; E3: Tage ohne Daten ohne Datei; E4: graues, schraffiertes Band, nicht in PDF-Berichten |
| 9 Lebenszyklus | E1: Ringdatei; E2: WAV-Breadcrumbs verdichten, als eigener Auftrag 10; E3: `EVENTS_MAX_PERIODISCH` bleibt 1 MB |

**Noch offen:** E1 in Auftrag 7, die Datenbanksicherung. Dafür gab es noch keine Empfehlung, sie
folgt erst nach der Messung.
