# Prompt: Bugfix — stiller Trigger-Ausfall und unsichtbare Trigger-Quelle

Zwei vom Owner am Gerät gefundene Fehler. **Befund 1 hat 12 Stunden Messung wertlos gemacht** —
er hat Vorrang vor allem anderen in diesem Dokument.

Beide Ursachen sind bereits analysiert und im Code lokalisiert. Deine Aufgabe ist die Umsetzung,
nicht die Fehlersuche. **Verifiziere die genannten Stellen trotzdem am Code**, bevor du etwas
änderst — wenn eine Angabe nicht mehr stimmt, halte dich an den Code und melde die Abweichung.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Insbesondere:

- Neuer Branch von `main`, `fix/trigger-ohne-mikrofon`. **Nie auf `main` pushen.**
- Commit-Nachrichten auf Deutsch, klein geschnitten.
- Code-Bezeichner englisch, UI-Texte deutsch.
- `./gradlew assembleDebug` und `./gradlew test` müssen grün sein; **die Ausgabe kommt in den
  PR**, nicht deren Zusammenfassung.
- `fallbackToDestructiveMigration()` ist verboten. Dieser Auftrag braucht **keine**
  Schemaänderung — wenn du glaubst, doch eine zu brauchen, halte an und frag.
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

---

## 1 · Befund 1 — Der Aufnahme-Trigger ist tot, solange das Mikrofon aus ist

### Was der Owner erlebt hat

> „Habe heute 12h Aufnahmezeit mit 66dB LAeq (PCE-323) und Schwelle war bei 55dBA und Auslöser
> war PCE-323. Kein Einziger Event wurde aufgezeichnet. Audioaufnahme (WAV) war auch auf JA."

Zwölf Stunden, durchgehend gut 10 dB über der Schwelle, Messgerät verbunden, WAV-Aufnahme
eingeschaltet — **null Ereignisse.**

### Die Ursache

`audio/AudioRecordingService.kt:646`, die erste Zeile der Trigger-Prüfung:

```kotlin
private fun pruefeSchwellenwertUndTrigger(...) {
    if (!isRunning) return
```

`isRunning` ist **ausschließlich das Flag der Mikrofon-Überwachung.** Es wird nur an einer
einzigen Stelle gesetzt (`AudioRecordingService.kt:306–307`):

```kotlin
val shouldStartAudio = intent?.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false) == true ||
    intent?.action == ACTION_START_AUDIO_MONITORING || ...
if (!isRunning && shouldStartAudio) {
    isRunning = true
    _audioAufnahmeAktiv.value = true
    startMonitoring()
```

Der PCE-323-Pfad ruft den Trigger korrekt auf — `AudioRecordingService.kt:159`, im
Frame-Collector:

```kotlin
meterTransport.frames.collect { frame ->
    letzterMeterFrame = frame
    if (settingsManager.driveSyncEnabled) { levelSampleCollector.pegel(...) }
    pruefeSchwellenwertUndTrigger(meterFrame = frame, mikrofonDb = null)
}
```

**Aber jeder dieser Aufrufe fällt in Zeile 646 sofort wieder heraus**, solange die
Mikrofon-Überwachung nicht separat gestartet wurde. Zwölf Stunden lang, bei jedem einzelnen
Frame.

### Warum das niemandem aufgefallen ist

Alles andere lief weiter und sah gesund aus: Die Verbindung stand, die Session wurde angelegt,
`MeasurementEntity`-Zeilen wurden geschrieben, der Drive-Sync bekam seine Pegelwerte, der
Live-Pegel war im Cockpit sichtbar, die Notification zeigte Betrieb. Nur der eine Zweig, der
Ereignisse erzeugt, war tot — und Stille sieht von außen genauso aus wie „es war nichts los".

Die Bedienoberfläche legt die Fehlbedienung sogar nahe: `MicrophoneControlCard` („1.
Smartphone-Mikrofon") und `MeterControlCard` sind getrennte Karten mit getrennten Startknöpfen.
Wer mit einem kalibrierten Messgerät misst, hat keinen Anlass, zusätzlich das Mikrofon
einzuschalten — die Trigger-Quelle steht ja auf „Nur PCE-323".

### Zwei Folgebefunde, die du beim Beheben kennen musst

**(a) Ein WAV braucht das Mikrofon zwingend.** Die Audiodaten stammen ausschließlich aus der
Mikrofonschleife: `activeWavRecorder.writeChunk(...)` wird nur dort aufgerufen
(`AudioRecordingService.kt:541`), und `getPreRollData()` liest den Rolling Buffer, der ebenfalls
nur dort gefüllt wird. Der PCE-323 liefert Pegelwerte über BLE, kein Audio.

Die Kombination **„Trigger = Nur PCE-323" + „WAV = an" + Mikrofon-Überwachung aus** ist damit
technisch unmöglich — und genau die hatte der Owner eingestellt. Die App muss das sagen, statt
stillzuhalten.

**(b) Ein reines Pegel-Ereignis braucht das Mikrofon gar nicht.**
`speicherePegelEreignisOhneAudio()` (`AudioRecordingService.kt:687`) schreibt einen
`NoiseRecord` mit `filePath = ""` und kommt ohne jeden Audiozugriff aus. Trotzdem ist auch
dieser Pfad durch `if (!isRunning) return` blockiert. Das ist der Teil des Fehlers, der sich
ohne jede Abwägung beheben lässt.

### Was zu tun ist

**1.1 · Das Trigger-Gate von der Mikrofonschleife entkoppeln.**

`if (!isRunning) return` prüft heute die falsche Sache. Gemeint ist „läuft die Überwachung
überhaupt", nicht „läuft die Mikrofonschleife". Ersetze die Prüfung so, dass sie erfüllt ist,
wenn **entweder** die Mikrofon-Überwachung **oder** ein Messgerät-Stream aktiv ist.

`_laeuft` (der Foreground-Service-Zustand) und `connectionSupervisor.state == STREAMING` stehen
dafür bereits zur Verfügung. Der Frame-Collector selbst ist der Beleg, dass ein Frame
angekommen ist.

**Wichtig — keine Übersteuerung:** Wenn `settingsManager.recordWavAudio == true` und die
Mikrofonschleife *nicht* läuft, darfst du **keine** WAV-Aufnahme starten. Sie würde eine Datei
mit Header und leerem Datenteil erzeugen — schlimmer als kein Ereignis, weil sie wie ein Beleg
aussieht und keiner ist. In dieser Konstellation gilt Punkt 1.2.

**1.2 · Die unmögliche Kombination sichtbar machen und sinnvoll auflösen.**

Wenn getriggert wird, während ein WAV gewünscht, aber kein Mikrofon verfügbar ist, dann:

- **speichere das Ereignis trotzdem** — über `speicherePegelEreignisOhneAudio()`. Ein Ereignis
  mit Pegel, Zeitstempel und kalibriertem Wert, aber ohne Audio, ist unendlich viel mehr wert
  als gar keins. Das ist die Kernkorrektur: **Der Owner hätte nach diesem Fix 12 Stunden
  Ereignisse gehabt, nur ohne Tonbelege.**
- **melde es genau einmal je Überwachungsperiode**, nicht bei jedem Frame: ein Breadcrumb über
  `diagnosticsReporter` (Kategorie `"AudioService"`) plus ein sichtbarer Hinweis in der
  Notification, sinngemäß „Ereignisse werden ohne Tonaufnahme gespeichert — Mikrofon-Überwachung
  ist aus".

**1.3 · Ein Wachhund gegen den stillen Ausfall.**

Das ist die ausdrückliche Bitte des Owners:

> „Könnte man das irgendwie zusätzlich abfangen beim Betrieb das es erkannt wird? Das ist sehr
> unglücklich im Nachhinein."

Baue eine Überwachung des Überwachers. Die Regel:

> Wenn über ein zusammenhängendes Fenster von **10 Minuten** Pegelwerte oberhalb der aktiven
> Schwelle eingegangen sind und in derselben Zeit **kein einziger** `NoiseRecord` entstanden
> ist, ist etwas kaputt — melde es.

Bau das als **eigene, reine Klasse** (Vorschlag: `audio/TriggerWachhund.kt`), die nur mit
Zeitstempeln und Zählern arbeitet und weder Context noch Service kennt — dieselbe Trennung wie
bei `MeterTriggerSource` oder `Seitenlauf`. Vorschlag für die Schnittstelle:

```kotlin
class TriggerWachhund(
    private val fensterMs: Long = 10 * 60 * 1000L,
) {
    /** Meldet einen ausgewerteten Pegel. */
    fun pegelGesehen(zeitpunkt: Long, ueberSchwelle: Boolean)
    /** Meldet ein tatsaechlich gespeichertes Ereignis. */
    fun ereignisGespeichert(zeitpunkt: Long)
    /** true, sobald die Bedingung oben erfuellt ist. Danach erst wieder nach einem Ereignis. */
    fun stillerAusfall(jetzt: Long): Boolean
}
```

Bei Auslösung:

- `diagnosticsReporter.report(...)` mit einem passenden `DiagnosticCode` — prüfe
  `diagnose/DiagnosticCode.kt` und lege bei Bedarf einen neuen an, der zur vorhandenen
  Namenskonvention passt (z. B. `TRIGGER_STILLER_AUSFALL`).
- Eine **sichtbare** Warnung: Notification-Text und ein Hinweis im Cockpit. Ein Eintrag, den man
  nur im Diagnose-Screen findet, hätte diesen Fall nicht verhindert — der Owner hat 12 Stunden
  lang nicht in den Diagnose-Screen geschaut, und dafür gibt es keinen Grund.
- **Höchstens einmal je Überwachungsperiode**, sonst wird die Warnung zum Rauschen und der
  nächste echte Ausfall geht darin unter.

**Die Warnung muss den Grund nennen, nicht nur den Zustand.** „Seit 10 Minuten über der
Schwelle, aber keine Ereignisse — Mikrofon-Überwachung ist aus" ist brauchbar; „Trigger-Problem
erkannt" ist es nicht.

### Tests für Befund 1

Handgeschriebene Fakes, kein Mockito/MockK (gibt es in diesem Repo nicht):

1. **`TriggerWachhundTest`** (plain JUnit, keine Robolectric nötig):
   - Pegel über der Schwelle über das ganze Fenster, kein Ereignis → schlägt an.
   - Dasselbe, aber mit einem Ereignis in der Mitte → schlägt **nicht** an.
   - Pegel durchgehend unter der Schwelle → schlägt nicht an (der wichtigste Gegentest: eine
     ruhige Nacht ist kein Defekt).
   - Kurz über der Schwelle, dann lange darunter → schlägt nicht an.
   - Nach einer Auslösung nicht erneut, solange kein Ereignis dazwischenlag.
2. **`MeterTriggerSourceTest`** existiert bereits — ergänze den Fall, der zum Fehler geführt
   hat: Quelle `PCE_323`, Frame über der Schwelle → `ausgeloest == true`. Der Test wird schon
   vorher grün sein; er hält fest, dass die Auswertung nie das Problem war, sondern das Gate
   davor.
3. Ein Test, der belegt, dass ein Pegel-Ereignis **ohne** laufende Mikrofonschleife gespeichert
   wird. Wenn du dafür Logik aus `AudioRecordingService` herausziehen musst, damit sie testbar
   wird: tu das — das ist genau das Muster, das `classifySafely()` und `MeterTriggerSource`
   in dieser Codebasis schon verfolgen. Ein Service, dessen Kernentscheidung nur am Gerät
   prüfbar ist, hat diesen Fehler überhaupt erst ermöglicht.

---

## 2 · Befund 2 — „Nur Mikrofon" ist da, aber unsichtbar

### Was der Owner sieht

> „In Einstellungen stehen Triggerquelle nur auf Automatisch (Standard) oder nur PCE-323.
> Mikrofon fehlt."

### Die Ursache — und eine Korrektur der Annahme

**Die Option fehlt nicht.** Sie ist vorhanden, in `ui/SettingsScreen.kt` als dritter
`FilterChip`:

```kotlin
Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    FilterChip(... label = { Text(stringResource(R.string.settings_trigger_source_auto)) })   // "Automatisch (Standard)"
    FilterChip(... label = { Text(stringResource(R.string.settings_trigger_source_meter)) })  // "Nur PCE-323"
    FilterChip(... label = { Text(stringResource(R.string.settings_trigger_source_mic)) })    // "Nur Mikrofon"
}
```

**Ein `Row` bricht nicht um — er schneidet ab.** Die drei Beschriftungen „Automatisch
(Standard)", „Nur PCE-323" und „Nur Mikrofon" ergeben mitsamt Chip-Innenabständen deutlich mehr
als die Breite eines üblichen Telefons (~360 dp). Der dritte Chip wird rechts aus dem sichtbaren
Bereich geschoben.

> **Das ist eine begründete Hypothese, keine Messung.** Sie erklärt den Bericht vollständig und
> folgt aus dem Layout, aber ich konnte sie ohne Gerät nicht nachstellen. **Prüfe sie zuerst** —
> Compose-Preview in verschiedenen Breiten oder Emulator. Falls die Ursache eine andere ist,
> behebe die tatsächliche und schreib in den PR, was wirklich los war.

### Was zu tun ist

**2.1** `Row` → `FlowRow` (`androidx.compose.foundation.layout.FlowRow`), damit die Chips
umbrechen statt zu verschwinden. Prüfe im selben Zug, ob es im Projekt weitere Chip-Reihen mit
demselben Muster gibt — aber **ändere nur die, die tatsächlich überlaufen**, kein
Flächenbombardement.

**2.2 · Die Trigger-Quelle hängt an der falschen Bedingung.** Der gesamte Block steckt in:

```kotlin
if (recordWavAudio) {
    ... Trigger-Quelle ...
}
```

Mit ausgeschalteter WAV-Aufnahme ist die Trigger-Quelle also **gar nicht einstellbar** — obwohl
auch ein reines Pegel-Ereignis (`speicherePegelEreignisOhneAudio`) eine Quelle braucht und
`MeterTriggerSource` weiterhin danach entscheidet. Zieh die Auswahl aus dieser Bedingung heraus.

**2.3 · Zum „Automatisch"-Verhalten: das ist bereits korrekt.** Der Owner schreibt:

> „Automatisch sollte PCE-323 priorisieren und umschalten sobald PCE-323 verfügbar sonst
> Mikrofon nehmen."

Genau das tut `messreihe/MeterTriggerSource.kt` heute schon:

```kotlin
// "AUTO"
if (letzterMeterFrame != null) { /* Messgerät */ }
return Auswertung(ausgeloest = mikrofonDb > activeSchwelle, ...)  // sonst Mikrofon
```

Und der Rückfall greift auch wirklich, weil `letzterMeterFrame` genullt wird, sobald die
Verbindung nicht mehr `STREAMING` ist (`AudioRecordingService.kt:170`) — ein alter Wert wird
also nicht beliebig lange weiterbenutzt.

**Ändere an dieser Logik nichts.** Was fehlt, ist die Sichtbarkeit: Der Nutzer kann nicht
erkennen, welche Quelle gerade tatsächlich auslöst. Zeig im Cockpit bei „Automatisch" an, worauf
es im Moment hinausläuft — „Automatisch → Messgerät" bzw. „Automatisch → Mikrofon". Das ist
eine reine Anzeige, keine Verhaltensänderung.

### Tests für Befund 2

`SettingsScreen`-UI-Tests existieren im Projekt (`app/src/androidTest`). Ein Test, der die
Sichtbarkeit **aller drei** Chips prüft, wäre die passende Absicherung — er läuft aber nur mit
Gerät/Emulator. Wenn du keinen hast: schreib ihn trotzdem und vermerk im PR ausdrücklich, dass
er hier nicht ausgeführt werden konnte.

---

## 3 · Reihenfolge und Zuschnitt

Ein PR, aber getrennte Commits — Befund 1 und Befund 2 haben nichts miteinander zu tun und
müssen im Review einzeln nachvollziehbar sein:

1. `fix: Aufnahme-Trigger nicht mehr an die Mikrofonschleife koppeln`
2. `fix: Pegel-Ereignis ohne Audio speichern, wenn kein Mikrofon verfügbar ist`
3. `feat: Wachhund gegen stillen Trigger-Ausfall`
4. `fix: Trigger-Quelle-Chips brechen um statt abgeschnitten zu werden`
5. `fix: Trigger-Quelle auch ohne WAV-Aufnahme einstellbar`

**Befund 1 zuerst und vollständig.** Wenn dir unterwegs die Zeit oder der Zuschnitt ausgeht,
ist ein PR mit Befund 1 allein wertvoll; einer mit Befund 2 allein ist es kaum.

---

## 4 · Was ausdrücklich **nicht** Teil des Auftrags ist

- Umbau der Trennung von Mikrofon- und Messgerät-Karte in der Oberfläche. Sie hat zur
  Fehlbedienung beigetragen, aber ein UI-Redesign ist ein eigener Auftrag.
- Automatisches Mitstarten der Mikrofon-Überwachung, sobald ein Messgerät verbunden wird. Das
  wäre eine stille Erweiterung des Mikrofonzugriffs, die niemand beauftragt hat — und bei einer
  App, die Ton aufzeichnet, nichts, was man nebenbei einführt. Wenn du es für richtig hältst:
  vorschlagen, nicht bauen.
- Änderungen an `MeterTriggerSource.auswerten()`. Die Auswertung war nie das Problem.
- Nacherfassung der verlorenen 12 Stunden. Die Ereignisse existieren nicht; die Messreihe
  (`MeasurementEntity`) ist dagegen vollständig — sie lief über einen anderen Pfad.

---

## 5 · Definition of Done

1. `./gradlew assembleDebug` und `./gradlew test` grün — **Ausgabe im PR.**
2. Alle bestehenden Room-Migrationstests weiterhin grün (`AGENTS.md` §7.2).
3. Jeder Punkt aus Abschnitt 1 und 2 einzeln adressiert — auch die, die du nicht erfüllen
   konntest, mit Begründung.
4. Draft-PR gegen `main` mit: was geändert wurde · was verifiziert wurde (Kommando + Ergebnis) ·
   was offen blieb · ob sich die Hypothese aus 2 bestätigt hat oder die Ursache eine andere war.
5. **Der PR muss die eine Frage beantworten, auf die es hier ankommt:** Hätte der Owner mit
   diesem Stand nach spätestens 10 Minuten gemerkt, dass keine Ereignisse entstehen? Wenn nein,
   ist der Auftrag nicht erledigt.
