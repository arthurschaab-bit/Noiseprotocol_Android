# Prompt: M5 — Alarmierung bei Verbindungsabbruch

Für eine eigene Session. Voraussetzung: M-1, M0, M1, M2, M3 und B-11 sind auf `main`.

**Vollständig ohne Hardware baubar und testbar.** Der `ConnectionSupervisor` liefert seit M3 genau
die Signale, auf die die Alarmlogik hört, und der `FakeMeterTransport` kann sie alle auslösen.
Hardware braucht erst die Endabnahme.

M5 ist der Meilenstein, für den das ganze Vorhaben gestartet wurde („eine SMS verschicken wenn die
Verbindung abgebrochen ist"). Er steht auf dem kritischen Pfad des Plans: `M-1 → M0 → M2 → M3 → M5`.
M4 (Persistenz der Messreihe) ist bewusst **nicht** Voraussetzung.

---

## Entscheidungen des Owners — bereits getroffen, nicht mehr zur Diskussion

Diese vier Punkte standen in Plan Abschnitt 13 als offen und sind jetzt entschieden:

| Plan §13 | Entscheidung |
|---|---|
| 1 — Entwarnungsmeldung bei Wiederkehr | **Bei Push an, bei SMS aus.** Beides in den Einstellungen umstellbar, aber so vorbelegt. |
| 3 — Cooldown und Eskalation | **Cooldown 30 min, Eskalation nach 60 min, maximal 3 Wiederholungen.** |
| 4 — Push-Kanal | **ntfy.** Für den ersten Wurf der öffentliche Server `ntfy.sh` mit langem Zufalls-Topic. |
| — | SMS bleibt dabei, parallel zu ntfy. Interne Verteilung ist beschlossen (Plan 0.1), `SEND_SMS` daher unproblematisch. |

**Zur ntfy-Entscheidung, weil daran eine Auflage hängt:** Der Owner hat sich für „ntfy ausprobieren"
entschieden, nicht gegen self-hosting. Die Server-Basis-URL gehört deshalb von Anfang an in die
Einstellungen (Default `https://ntfy.sh`), **nicht** als Konstante in den Code. Ein späterer Wechsel
auf eine eigene Instanz muss eine Einstellungsänderung sein, kein Umbau.

---

```text
Du setzt Meilenstein M5 eines beschlossenen Plans um. Der Plan steht — deine Aufgabe ist die
Umsetzung, nicht die Neuplanung.

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m5-alarmierung

ZUERST LESEN
1. README.md — Statusüberblick
2. docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md, **Abschnitt 7 vollständig** — das ist der
   Inhalt dieses Meilensteins. Lies 7.0 und 7.5 besonders genau; sie sind der Grund, warum
   dieser Meilenstein mehr ist als "SMS verschicken".
3. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert
4. Die Entscheidungstabelle am Anfang dieser Datei — vier Punkte aus Plan §13 sind entschieden

AUSGANGSLAGE
Auf main vorhanden:
- meter/ConnectionSupervisor.kt — state: StateFlow<ConnectionState>, start(BoundDevice), stop().
  Erkennt bereits alle vier Ausfallsignale aus Plan 7.1 und setzt DEGRADED / RECONNECTING /
  FAILED. Zeitquelle ist über InstantSource injizierbar — halte es dabei.
- meter/ConnectionState.kt — die Zustände plus label() für die deutsche Anzeige
- meter/MeterTransport.kt — Schnittstelle, MeterFrame, Kennzahlen für die Fehlerrate
- meter/FakeMeterTransport.kt — simulateConnectionLoss(), simulateStall(), simulateCorruptFrames()
- audio/AudioRecordingService.kt — Foreground Service, betreibt Supervisor und Transport
- AppContainer.kt — manuelle DI, hier werden die neuen Bausteine verdrahtet
- data/SettingsManager.kt — SharedPreferences-Wrapper, hier kommen die neuen Einstellungen rein

NOCH NICHT vorhanden und von dir zu ergänzen: WorkManager und ein HTTP-Client (OkHttp) als
Abhängigkeit, sowie die Berechtigungen SEND_SMS, INTERNET, READ_PHONE_STATE und
SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM im Manifest. INTERNET fehlt heute tatsächlich — ohne sie
scheitert jeder ntfy-Post stumm.

=== AUFGABE 1: AlertChannel-Abstraktion und Alarm-Datenmodell ===

Die Abstraktion aus Plan 7.6, wörtlich so:

    interface AlertChannel {
        val id: ChannelId
        val isAvailable: Boolean          // Berechtigung / Konfiguration vorhanden?
        suspend fun send(alert: Alert): Result<Unit>
    }

Sie ist keine Stilfrage: Falls später doch eine Play-Veröffentlichung ansteht, muss sich der
SmsAlertChannel aus dem Release entfernen lassen, ohne die Alarmlogik anzufassen (Plan 7.6).

Dazu die Room-Entity AlertEntity aus Plan 8.1 — id, sessionId, raisedAt, resolvedAt, reason,
recipients, deliveryState (PENDING/SENT/FAILED), attempts. sessionId gibt es vor M4 noch nicht;
lass die Spalte nullable statt eine Session zu erfinden.

⚠ Room-Migration: Neue Tabelle heißt neue Schema-Version und ein Migrationstest. Beide
bestehenden Migrationstests müssen grün bleiben. fallbackToDestructiveMigration ist verboten.

=== AUFGABE 2: AlarmCoordinator — Watchdog, Karenzzeit, Entprellung ===

Die Ablaufkette aus Plan 7.2. Der Coordinator beobachtet ConnectionSupervisor.state und kennt
sonst nichts von BLE — genau wie der Supervisor selbst nur MeterTransport kennt.

- Ausfallsignal (DEGRADED, DISCONNECTED, RECONNECTING oder FAILED) startet die Karenzzeit
  t_grace, Default 60 s, einstellbar 10 s – 15 min.
- Rückkehr nach STREAMING während der Karenzzeit ⇒ Karenzzeit abbrechen, kein Alarm. Das ist
  der häufigste Fall im Alltag und muss geräuschlos bleiben.
- Nach Ablauf: AlertEntity in Room anlegen, dann über ALLE aktivierten Kanäle versenden.
- Cooldown 30 min: kein zweiter Alarm für denselben Ausfall.
- Eskalation: besteht der Ausfall nach 60 min noch, Wiederholung; maximal 3 Wiederholungen.
- Verbindung kehrt zurück ⇒ AlertEntity schließen (resolvedAt), Entwarnung nur über die Kanäle,
  bei denen sie eingeschaltet ist (Default: Push ja, SMS nein).

⚠ Die Karenzzeit MUSS über AlarmManager.setExactAndAllowWhileIdle() laufen, NICHT über einen
Coroutine-delay(). Ein delay() feuert im Doze-Modus unter Umständen erst Stunden später — genau
dann, wenn der Alarm am wichtigsten wäre. Prüfe canScheduleExactAlarms() und zeige den Zustand
an, statt stillschweigend auf einen ungenauen Alarm zurückzufallen.

⚠ Der Alarmzustand liegt in Room, nicht nur im Speicher. Stirbt der Prozess während der
Karenzzeit, muss der Alarm beim nächsten Service-Start nachgeholt und nicht verschluckt werden.

⚠ Kanäle PARALLEL, nicht als Fallback-Kette (Plan 7.2). "Erst Push, bei Fehlschlag SMS" ist hier
falsch: Ein Push gilt als erfolgreich, sobald der Server ihn angenommen hat — ob er ankommt,
weiß die App nie. Eine Fallback-Kette bliebe genau dann stumm, wenn sie gebraucht wird.

=== AUFGABE 3: SmsAlertChannel mit Zustellnachweis ===

Plan 7.3, inklusive des Codegerüsts dort:

- divideMessage() + sendMultipartTextMessage(), damit >160 Zeichen nicht abgeschnitten werden
- PendingIntent je Teil, BroadcastReceiver wertet resultCode aus (RESULT_ERROR_NO_SERVICE,
  RESULT_ERROR_RADIO_OFF, RESULT_ERROR_GENERIC_FAILURE) und setzt SENT oder FAILED
- Bei Fehlschlag ein WorkManager-Job mit NetworkType.NOT_REQUIRED und Backoff, der wiederholt,
  sobald wieder Netz da ist. Ein Alarm darf nicht verloren gehen, nur weil im Moment des
  Ausfalls kein Empfang war.
- Dual-SIM über SmsManager.createForSubscriptionId() mit vom Nutzer gewählter SIM
- Nachrichtentext kompakt, Zeitstempel in Ortszeit, Beispiel aus dem Plan:
  "Lärmprotokoll: Verbindung zu PCE-323 unterbrochen seit 16.08.2026 14:32 (Grund: keine
  Daten). Aufzeichnung pausiert."

=== AUFGABE 4: NtfyAlertChannel ===

Plan 7.4 Option A. Ein HTTP-POST, kein SDK.

- Basis-URL aus den Einstellungen, Default https://ntfy.sh — NICHT als Konstante im Code
  festnageln (siehe Entscheidungstabelle oben).
- Header Priority: 5 (durchbricht Do-Not-Disturb), Title, Tags.
- Das Topic wird beim ersten Einschalten mit SecureRandom erzeugt, mindestens 32 Zeichen, und
  in den Einstellungen gespeichert. Beim öffentlichen Server IST der Topic-Name die einzige
  Zugangskontrolle.
- Der Topic-Name darf weder ins Repository noch in irgendeine Logzeile geraten — auch nicht
  gekürzt, auch nicht im Fehlerfall. Zum Teilen mit dem Zweitgerät gehört er ins UI (QR-Code
  oder Kopierschaltfläche), nicht ins Log.
- Der Alarmtext bleibt minimal: keine Messwerte, keine Orte, keine Gerätekennungen.

⚠ Verschlüsselte Ablage des Topics ist M6 (Keystore, verschlüsselter DataStore), nicht M5. Leg
es vorerst in den bestehenden SettingsManager und vermerke im PR, dass die Verschlüsselung in
M6 nachgezogen wird. Baue KEINE eigene halbe Krypto-Lösung.

=== AUFGABE 5: Totmannschaltung (Heartbeat) ===

Plan 7.5, und laut Plan 7.0 Teil von M5, nicht Ausbaustufe. Sie deckt den wahrscheinlichsten
Ausfall im Dauerbetrieb ab: dass das Überwachungsgerät SELBST stirbt — Akku leer, ROM killt den
Service, App gecrasht. Dann kommt von keinem Alarmkanal etwas, weil jeder Alarm eine ausgehende
Nachricht dieses Geräts ist.

- Variante 1 aus dem Plan: HeartbeatWorker sendet regelmäßig ein GET auf eine Ping-URL
  (healthchecks.io oder self-hosted). URL aus den Einstellungen; ist sie leer, ist der
  Heartbeat aus — kein Zwang zu einem Fremddienst.
- WorkManager-Mindestintervall ist 15 min. Nimm 15 min, statt einen AlarmManager-Tick
  danebenzustellen; 5 min bringen hier keinen erkennbaren Gewinn und kosten Akku. Wenn du
  anders entscheidest, begründe es im PR.

⚠ Der Heartbeat darf NICHT vom BLE-Zustand abhängen. Er bestätigt "App und Gerät leben", nicht
"Messgerät verbunden". Hängt er am BLE-Zustand, löst ein normaler Verbindungsabbruch zusätzlich
einen Heartbeat-Alarm aus und die beiden Signale sind nicht mehr unterscheidbar — dann ist die
Totmannschaltung wertlos, weil man ihr nicht mehr glaubt.

=== AUFGABE 6: Einstellungen und Testfunktionen ===

In SettingsScreen: Empfängerrufnummer, SIM-Auswahl, ntfy-Server und -Topic (mit
Kopierschaltfläche/QR), Karenzzeit, Entwarnung je Kanal an/aus, Heartbeat-URL.

Dazu je Kanal eine Testfunktion — "Test-SMS senden", "Test-Push senden" (Plan 7.3). Sie prüfen
Berechtigung, Konfiguration und Zustellung, BEVOR es ernst wird. Ein Alarmkanal, der erst im
Ernstfall zum ersten Mal benutzt wird, ist kein Alarmkanal, sondern eine Hoffnung.

Im Onboarding bzw. den Einstellungen SCHEDULE_EXACT_ALARM aktiv abfragen (canScheduleExactAlarms)
und den Zustand sichtbar machen.

NICHT TEIL VON M5
Persistenz der Messreihe (M4), FCM/Google Sign-In (M9 — der ntfy-Kanal deckt dieselbe Funktion
zu einem Bruchteil des Aufwands), Verschlüsselung und SQLCipher (M6), Diagnose-Screen und
Export (M7), Drive-Sync (M7b), die vier Altbefunde aus docs/PROMPT_REVIEW.md Schritt 5.

Ebenfalls nicht: den Aufnahme-Trigger auf das Messgerät umstellen. Das ist M4.

TESTS — hier liegt der Wert dieses Meilensteins
Alles gegen den FakeMeterTransport und gegen Fake-AlertChannels, mit injizierter Zeit. Die
Zeitquelle MUSS injizierbar sein wie im ConnectionSupervisor, sonst dauert ein einziger
Eskalationstest real 60 Minuten. Mindestens:

- Abbruch, Rückkehr innerhalb der Karenzzeit ⇒ KEIN Alarm. Der wichtigste Test überhaupt: ein
  Alarmsystem, das bei jedem kurzen Aussetzer feuert, wird binnen einer Woche stummgeschaltet.
- Abbruch, keine Rückkehr ⇒ nach t_grace genau EIN Alarm, über alle aktivierten Kanäle
- Ein Kanal wirft eine Exception ⇒ die anderen versenden trotzdem (paralleler Versand, kein
  gemeinsames Scheitern)
- Cooldown: zweites Ausfallsignal innerhalb von 30 min ⇒ kein zweiter Alarm
- Eskalation: Ausfall besteht nach 60 min ⇒ Wiederholung; nach der dritten ⇒ Schluss
- Entwarnung: bei Wiederkehr geht sie an den Push-Kanal, NICHT an SMS (Default)
- Flapping: 10 kurze Ausfälle mit Rückkehr ⇒ kein Alarm, keine Alarmflut
- Prozess-Tod während der Karenzzeit: Alarmzustand aus Room wiederhergestellt ⇒ Alarm wird
  nachgeholt statt verschluckt
- Heartbeat: läuft weiter, während die BLE-Verbindung getrennt ist (die Gegenprobe zur Falle
  aus Aufgabe 5)
- SMS-Fehlschlag ⇒ Retry-Job eingeplant, deliveryState FAILED

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün — Ausgabe im PR zeigen, nicht behaupten.
  Fehlt ein Android SDK: sdkmanager "platforms;android-36" "build-tools;36.0.0", dann
  local.properties (sdk.dir=...).
- Für jeden neuen Test eine Gegenprobe: Schlägt er fehl, wenn man die zugehörige Logik
  entfernt? Tests, die in keiner Fassung fehlschlagen können, gehören nicht in den PR.
- Alle Room-Migrationstests grün, inklusive des neuen für AlertEntity
- Draft-PR gegen main mit: was geändert, was verifiziert (Befehl und Ergebnis), was offen

ENDABNAHME AM GERÄT — im PR als offen markieren, nicht behaupten
Diese Punkte kann nur ein Test mit echtem Telefon und echter SIM zeigen:
- Test-SMS kommt an, auf beiden SIMs
- Test-Push kommt auf dem Zweitgerät an und durchbricht Do-Not-Disturb
- Messgerät ausschalten ⇒ nach 60 s kommen SMS und Push
- Messgerät wieder an ⇒ Entwarnung per Push, keine SMS
- Flugmodus beim Ausfall ⇒ SMS geht raus, sobald wieder Netz da ist
- Telefon ausschalten ⇒ healthchecks.io meldet den ausbleibenden Ping
- Nachtlauf: kein Fehlalarm über acht Stunden
```

---

## Warum die Totmannschaltung nicht wegverhandelt werden darf

Sie ist der einzige Teil von M5, der den Fall abdeckt, in dem das Überwachungsgerät selbst
ausfällt — und das ist im Dauerbetrieb der wahrscheinlichste Ausfall, nicht der unwahrscheinlichste.
Jeder Alarmkanal ist eine *ausgehende* Nachricht dieses Geräts; ist es tot, kommt schlicht nichts,
und der Ausfall bleibt tagelang unbemerkt. Plan 7.0 sagt deshalb ausdrücklich: Teil von M5, nicht
Ausbaustufe. Sie kostet einen Worker und eine URL.

## Drei Fallen, die im Auftrag stehen

**Die Karenzzeit als `delay()`.** Der naheliegende Weg, und im Doze-Modus der falsche: Der Alarm
feuert dann unter Umständen Stunden zu spät. `AlarmManager.setExactAndAllowWhileIdle()`, und der
Zustand gehört in Room, damit ein Prozess-Tod während der Karenzzeit den Alarm nicht frisst.

**Die Fallback-Kette.** „Erst Push, bei Fehlschlag SMS" klingt sparsamer und ist hier falsch, weil
ein Push-Versand schon als erfolgreich gilt, wenn der Server ihn annimmt. Die Kette bliebe genau
dann stumm, wenn sie gebraucht wird. Zwei parallele Alarme sind das kleinere Übel als ein
verpasster.

**Der Heartbeat am BLE-Zustand.** Verlockend, weil man ihn dann „nur senden muss, wenn alles läuft" —
und damit unbrauchbar, weil ein normaler Verbindungsabbruch zusätzlich einen Heartbeat-Alarm
auslöst. Er bestätigt „App und Gerät leben", sonst nichts.

## Was M5 bewusst offen lässt

Die verschlüsselte Ablage des ntfy-Topics ist M6, nicht M5 — im PR zu vermerken, nicht selbst zu
bauen. Und die Alarmierung meldet vorerst nur den Verbindungszustand; Messwerte im Alarmtext gibt
es bewusst nicht, weder aus Datenschutz- noch aus Reifegründen (die Frequenzbewertung ist bis zum
Gerätetest unbestätigt).
