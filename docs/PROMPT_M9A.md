# Prompt: M9a — Owner-Entscheidungen aus dem UX-Review

Der Owner hat die vier Befunde aus [`docs/PROMPT_M9_UX.md`](PROMPT_M9_UX.md) beantwortet, die
ihm nach dem Review vorgelegt wurden. Dieses Dokument hält die Antworten fest, gleicht sie
gegen den heutigen Code ab und formuliert daraus den verbliebenen Auftrag.

**Stand des Abgleichs:** `main` @ `762b465` (nach PR #76), geprüft am 2026-08-20. Das ist
wichtig, weil `main` zwischen dem Review (`0bbb33e`, nach PR #46) und diesen Antworten um rund
30 PRs weitergelaufen ist. **Drei der vier Punkte sind dadurch bereits erledigt** — nicht durch
diesen Meilenstein, sondern durch Arbeit, die parallel gelaufen ist. Übrig bleibt ein
technischer Punkt und ein neuer, dabei aufgefallener Befund.

---

## Teil A — Die vier Antworten und was der Code heute dazu sagt

### A1 · „Minimiere die Datenpunkte für die reine Anzeige über die Zeit. Bringt das was?"

**→ Offen. Die Idee ist richtig, greift aber an der falschen Stelle an. Ausführliche Antwort in
Teil C, Auftrag in Teil B Aufgabe 1.**

Kurzfassung: Die Anzeige ist bereits minimiert — auf zwei Ebenen sogar. Das Chart fasst auf
höchstens 200 Spalten zusammen (`messreihe/ChartDaten.kt:35`), und seit kurzem begrenzt das
Cockpit die dargestellte Zeitspanne zusätzlich auf die letzten vier Stunden
(`ui/LiveCockpitCard.kt:402–411`). Beides ändert nichts an den Kosten, weil beide Reduktionen
**nach** dem Laden stattfinden. Geladen wird weiterhin die vollständige Messreihe der Session.

### A2 · „lass das mal offen ich hab noch nicht das Problem verstanden"

Bezog sich auf den Befund, dass `calibratedDbA` gespeichert, aber nirgends angezeigt wurde.

**→ Der Befund hat sich erledigt, das Problem existiert nicht mehr.** Der kalibrierte Wert wird
inzwischen an allen relevanten Stellen ausgewiesen: in der Aufnahmeliste
(`ui/MainActivity.kt:1095–1100`), im Pegelverlauf (`ui/PegelverlaufChart.kt:306`), im Papierkorb
(`ui/TrashScreen.kt:116`), in der Protokoll-Detailansicht (`ui/ProtokollDetailScreen.kt:328`)
und im Tagesbericht (`report/ReportManager.kt:30, 41–45`), dort sogar mit dem Mikrofonwert
daneben statt an seiner Stelle — genau so, wie es der Review vorgeschlagen hatte.

**Aber:** Beim Nachprüfen ist an genau dieser Stelle ein neuer, ernsterer Befund aufgefallen —
siehe A5 unten. Der ist der eigentliche Rest dieses Themas.

### A3 · „Ja umstellen." (Dunkelmodus / Farbschema)

**→ Bereits umgesetzt, kein Auftrag mehr nötig.** `ui/theme/Theme.kt` existiert mit
`darkColorScheme()`, `lightColorScheme()` und `darkTheme: Boolean = isSystemInDarkTheme()`
(`ui/theme/Theme.kt:59–60`); `MainActivity.kt:92` benutzt `LaermprotokollTheme` statt des
nackten `MaterialTheme`. Damit ist der Befund „die App ist immer hell" gegenstandslos.

Was **nicht** geprüft werden konnte: ob die Farben im Dunkelmodus tatsächlich ausreichend
kontrastieren. Das ist eine visuelle Frage und braucht einen Emulator; sie bleibt als Restpunkt
in `PROMPT_M9_UX.md` Aufgabe 2 stehen (Kontrastprüfung, hartcodierte Farbliterale).

### A4 · „Bringe die UI Test Suite wieder zurück"

**→ Bereits vorhanden — und die Aussage, sie sei gelöscht worden, war falsch.**

Der Review hatte behauptet, die instrumentierte Suite sei „mit PR #43–#46 gelöscht worden"
(`PROMPT_M9_UX.md` Befund A12). Das stimmt nicht. Nachgeprüft:

```
$ git merge-base --is-ancestor 4c60f3e origin/main
4c60f3e ist NICHT Vorfahr von main
```

Der Merge von PR #38, der die Suite gebracht hatte, liegt nicht in der Historie von `main` —
gelöscht wurde nichts, die Arbeit war nie dort angekommen. Inzwischen ist sie es: `main` trägt
heute **10 instrumentierte Testklassen** unter
`app/src/androidTest/java/com/example/lrmprotokoll/ui/`, eine mehr als damals
(`AppStartupSmokeInstrumentedTest.kt` ist neu), und `.github/workflows/emulator-tests.yml`
existiert wieder.

**Kein Auftrag.** Der Befund A12 in `PROMPT_M9_UX.md` ist sachlich falsch und wird mit diesem
Commit korrigiert (siehe Teil B Aufgabe 3).

### A5 · Neu aufgefallen: der Tagesbericht behauptet „dBA", ohne das je Datensatz zu prüfen

> **Korrektur, nachträglich, nach Umsetzung:** Dieser Abschnitt unterstellte ursprünglich, die
> A/C-Zuordnung des PCE-323 sei insgesamt noch unbestätigt („Checkliste Teil B2 steht aus"). Das
> war zum Zeitpunkt dieses Dokuments bereits falsch — `Pce323Profile.MODE_ASSUMPTION_CONFIRMED`
> stand schon in Commit `762b465` (der Basis dieses Dokuments) auf `true`, mit KDoc-Beleg: „Vom
> Owner am 2026-08-20 im Gerätetest bestätigt (dB(A)/dB(C) und Fast/Slow stimmten live exakt mit
> der Geräteanzeige überein, siehe `docs/PROTOKOLL_PCE-323.md` Abschnitt 10)". Hätte ich das vor
> dem Schreiben geprüft statt es aus einem älteren Stand zu übernehmen, wäre dieser Abschnitt
> nie in dieser Schärfe entstanden. Der **Kern des Befunds bleibt trotzdem gültig** und ist unten
> unverändert stehen gelassen: `ReportManager.kt:42,100` leitet die Einheit nicht aus dem
> Datensatz ab, sondern hängt sie hart an — das ist heute (Flag global `true`) folgenlos, wäre
> aber falsch für jeden Datensatz, dessen `meterWeighting` `null` ist (z. B. Altbestand von vor
> der Bestätigung). Deshalb bleibt Aufgabe 2 bestehen, jetzt als Robustheits-/Korrektheitsfix,
> nicht mehr als Ehrlichkeitsdringlichkeit.

Dieser Befund stammt nicht vom Owner, sondern aus dem Abgleich zu A2.

**[C]** `report/ReportManager.kt:42` schreibt in den Tagesbericht:

```kotlin
content.append("Kalibrierter Pegel: ${String.format(...)} dBA (PCE-323)\n")
```

— und zwar **unbedingt**, sobald `calibratedDbA != null` ist. `MeterFrame.modeAssumptionConfirmed`
wird dabei nicht abgefragt.

Überall sonst tut der Code genau das Gegenteil und prüft das Flag sorgfältig, bevor er eine
Frequenzbewertung behauptet: `MeterScreen.kt:311` (`frame.weighting.takeIf { frame.modeAssumptionConfirmed }`),
`MeterControlCard.kt:158`, `MeterTriggerSource.kt:47,69`, `MeasurementRecorder.kt:137–138`. M4
speichert die Bewertung konsequent als `null`, solange sie unbestätigt ist — die README hält
das als ausdrückliche Warnung fest.

Der Tagesbericht ist damit die **einzige** Stelle der App, die eine Einheit unabhängig vom
Datensatz anhängt statt sie aus `NoiseRecord.meterWeighting` abzuleiten — genau dem Feld, das
M4 pro Datensatz `null` lässt, solange die Bewertung zum Zeitpunkt DIESES Messwerts unbestätigt
war. Die A/C-Zuordnung selbst ist inzwischen global bestätigt (siehe Korrekturhinweis oben), aber
`meterWeighting` bleibt die pro Datensatz korrekte Quelle — für Altbestand von vor der
Bestätigung ebenso wie für den (heute rein hypothetischen) Fall, dass die Zuordnung je wieder
zurückgesetzt würde. Ein hart angehängtes „dBA" merkt davon nichts.

---

## Teil B — Der Auftrag

```text
Du setzt M9a um: die vom Owner entschiedenen Restpunkte aus dem UX-Review.

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m9a-datenpfad-und-ehrlichkeit

ZUERST LESEN
1. docs/PROMPT_M9A.md — dieses Dokument, vollständig, inklusive Teil C
2. docs/PROMPT_M9_UX.md — der Review dahinter. ACHTUNG: dessen Zeilenangaben beziehen sich auf
   main @ 0bbb33e und sind grossteils veraltet; Befund A12 darin ist sachlich falsch (siehe
   Aufgabe 3). Die Befunde A2 (Theme) und A10 (calibratedDbA) sind inzwischen erledigt.
3. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert
4. AGENTS.md — vollständig

Zeilenangaben hier beziehen sich auf main @ 762b465. Ist main weitergelaufen, die Stellen über
den zitierten Code wiederfinden, nicht blind der Zeilennummer folgen.

=== AUFGABE 1: Den Datenpfad des Live-Cockpits begrenzen ===

Das Problem steht in Teil C ausführlich. Kurz: LiveCockpitCard.kt:103 lädt über
measurementDao().fuerSessionFlow() die KOMPLETTE Messreihe der Session (SessionDao.kt:53-54,
"SELECT * FROM measurements WHERE sessionId = :sessionId ORDER BY timestamp", ohne LIMIT), und
Room stösst diesen Flow bei jedem Messwert-Batch neu an - MeasurementRecorder flusht alle 5 s
oder alle 50 Werte (MeasurementRecorder.kt:49-50). Die Begrenzung auf vier Stunden
(LiveCockpitCard.kt:402-411) und das Downsampling auf 200 Spalten (ChartDaten.kt:35) greifen
beide ERST DANACH.

Zu tun:
- Neue, zusätzliche DAO-Methode, die das Zeitfenster in die Query zieht, z. B.
  fuerSessionAbFlow(sessionId, ab) mit
  "SELECT * FROM measurements WHERE sessionId = :sessionId AND timestamp >= :ab ORDER BY timestamp".
  Das ist rein additiv: bestehende Methoden bleiben, kein Schema, keine Migration.
- LiveCockpitCard benutzt sie mit derselben Vier-Stunden-Grenze, die heute clientseitig
  gefiltert wird. Der Client-Filter in Zeile 410-411 entfällt damit oder wird zur reinen
  Absicherung.
- Achtung, der eigentliche Fallstrick: Die 4-Stunden-Grenze verschiebt sich mit der Zeit
  weiter. Wenn `ab` als Query-Parameter bei jedem Tick neu berechnet wird, wird der Flow bei
  jeder Sekunde neu abonniert. Den Parameter deshalb grob rastern (z. B. auf volle Minuten),
  damit das Abonnement stabil bleibt.
- AkustischeKennwerte.berechne(geladeneMesswerte) in Zeile 106 läuft heute über die
  UNGEFILTERTE Vollmenge und macht dabei zwei vollständige Sortierungen
  (AkustischeKennwerte.kt:50,54). Prüfen, welche Kennwerte das Cockpit tatsächlich anzeigt -
  wenn es nur LAeq und Max sind, braucht es dafür keine Sortierung, sondern einen laufenden
  Akkumulator (energetische Summe + Anzahl für LAeq, max() für Max). Die Perzentile
  L10/L50/L90 braucht die Detailansicht, nicht das Cockpit.
- Die Berechnung gehört auf Dispatchers.Default, nicht in den Kompositionskontext. Sie läuft
  heute im collectLatest-Rumpf eines LaunchedEffect, also auf dem Main-Thread, und
  collectLatest kann sie nicht abbrechen, weil berechne nie suspendiert.
- Beweis statt Behauptung: ein JVM-Test, der zählt, wie viele Messwerte bei N eingehenden
  Batches insgesamt verarbeitet werden. Vor der Änderung muss er fehlschlagen. Ein zweiter
  Test für die Rasterung des `ab`-Parameters (gleiche Minute -> gleicher Wert).
- Die Anzeige darf sich dabei NICHT verschlechtern. Das Chart bleibt live, das
  Vier-Stunden-Fenster bleibt, die Kennwerte bleiben.

Was NICHT zu tun ist: das Anzeigefenster weiter verkleinern oder maxSpalten senken. Das ist
genau die Stellschraube, die nichts bringt (Teil C).

=== AUFGABE 2: "dBA" im Tagesbericht ehrlich machen (Befund A5) ===

report/ReportManager.kt:42 schreibt "dBA (PCE-323)", ohne MeterFrame.modeAssumptionConfirmed
zu prüfen. Jede andere Stelle im Code prüft dieses Flag (MeterScreen.kt:311,
MeterControlCard.kt:158, MeterTriggerSource.kt:47,69, MeasurementRecorder.kt:137-138).

- Solange die Bewertung unbestätigt ist, schreibt der Bericht "dB", nicht "dBA".
- Einmal im Kopf des Berichts der Hinweis, dass die Frequenzbewertung des Geräts nicht
  bestätigt ist und der Wert deshalb als dB ausgewiesen wird - kurz, sachlich, kein Kleingedrucktes.
- Ist die Bewertung bestätigt, darf der Bericht "dBA" bzw. "dBC" schreiben. Die Zuordnung dafür
  liegt bereits in NoiseRecord.meterWeighting, das genau dann gesetzt ist.
- Denselben Blick auf die anderen Ausgabewege werfen: MessreiheExport (CSV/PDF), DriveCsv. Wo
  dort "dBA" behauptet wird, ohne dass das Flag geprüft ist, gilt dasselbe. Die bestehende
  Konvention "_dB statt _dBA" in DriveCsv ist der richtige Massstab.
- Test: ein Bericht mit unbestätigter Bewertung enthält nirgends die Zeichenfolge "dBA"; mit
  bestätigter Bewertung enthält er sie. Beide Richtungen prüfen.

=== AUFGABE 3: Die falsche Aussage in PROMPT_M9_UX.md korrigieren ===

Befund A12 dort behauptet, die instrumentierte Test-Suite und emulator-tests.yml seien mit
PR #43-#46 gelöscht worden. Das ist falsch - der Merge von PR #38 ist schlicht nie in main
gelandet (git merge-base --is-ancestor 4c60f3e origin/main schlägt fehl). Heute liegen 10
Testklassen unter app/src/androidTest/.../ui/ und der Workflow existiert.

- A12 entsprechend korrigieren oder streichen.
- Die daraus abgeleitete Anweisung in Teil B ("nicht versuchen, die gelöschte Suite
  wiederzubeleben") ebenfalls - sie ist gegenstandslos und würde einen Umsetzer in die Irre
  führen.
- Bei der Gelegenheit die erledigten Befunde A2 (Theme) und A10 (calibratedDbA) als erledigt
  kennzeichnen, statt sie als offen stehen zu lassen. Nicht den ganzen Review neu schreiben -
  nur die Stellen, die inzwischen unwahr sind.

NICHT TEIL VON M9a
- Der Rest von PROMPT_M9_UX.md (String-Ressourcen, Navigation, Barrierefreiheit,
  Berechtigungen, Einstellungen). Der bleibt M9.
- Alles aus PROMPT_M10_FUNKTIONEN.md.
- Room-Entities, Migrationen, Schemata. Aufgabe 1 fügt nur eine Query hinzu.
- BLE-Protokollcode.
- Das Anzeigeverhalten des Charts verändern.

TESTS
- Die in den Aufgaben genannten JVM-Tests, jeder mit Gegenprobe (schlägt er fehl, wenn man die
  Logik entfernt?).
- Die vorhandenen instrumentierten Tests laufen weiter - Aufgabe 1 fasst LiveCockpitCard an,
  Aufgabe 2 den Berichtstext. Was dort auf Textinhalte prüft, mitziehen.
- Beide Room-Migrationstests unverändert grün, app/schemas/ unverändert.

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün - Ausgabe im PR zeigen, nicht behaupten.
- app/schemas/ unverändert (git diff im PR zeigen).
- Für Aufgabe 1 eine Messung, nicht nur der Zähltest: eine über mehrere Minuten laufende
  Session am Emulator, vorher/nachher. War das nicht möglich, genau das schreiben.
- Ein Beispielbericht aus Aufgabe 2 im PR, mit und ohne bestätigte Bewertung.
- Draft-PR gegen main: was geändert, was verifiziert (Befehl und Ergebnis), was offen.
```

---

## Teil C — Antwort auf die Frage: „Bringt das was?"

**Kurz: An der Anzeige nicht mehr — die ist schon minimiert. An der Stelle davor: sehr viel.**

Die Idee ist richtig, sie ist nur eine Stufe zu spät angesetzt. Der Code beweist das inzwischen
selbst, weil beide naheliegenden Formen der Minimierung bereits eingebaut sind:

| Ebene | Zustand heute | Wirkung auf die Kosten |
|---|---|---|
| Gezeichnete Punkte | auf 200 Spalten zusammengefasst (`ChartDaten.kt:35`) | keine |
| Dargestellte Zeitspanne | auf 4 Stunden begrenzt (`LiveCockpitCard.kt:402–411`) | keine |
| **Geladene Messwerte** | **unbegrenzt** (`SessionDao.kt:53–54`, kein `LIMIT`) | **hier sitzt alles** |

Der Grund ist die Reihenfolge. Der Ablauf ist heute:

1. Room liefert **alle** Messwerte der Session (`fuerSessionFlow`, ohne `LIMIT`).
2. `AkustischeKennwerte.berechne` läuft über diese **ungefilterte** Vollmenge und sortiert sie
   dabei zweimal (`AkustischeKennwerte.kt:50,54`).
3. *Erst jetzt* filtert `messwerte.filter { it.timestamp in chartStart..… }` auf vier Stunden.
4. *Erst jetzt* fasst `downsample` auf 200 Spalten zusammen.

Die Schritte 3 und 4 sind die „Minimierung der Datenpunkte für die Anzeige". Sie werfen weg,
was Schritt 1 und 2 bereits vollständig geladen, allokiert und sortiert haben. Und das
wiederholt sich alle 5 Sekunden, weil Room den Flow bei jedem Schreibvorgang neu anstößt und
`MeasurementRecorder` alle 5 s oder 50 Werte schreibt (`MeasurementRecorder.kt:49–50`).

**In Zahlen.** Eine Session über 24 Stunden bei rund 2 Werten/s sind etwa 172.000 Zeilen. Alle
5 Sekunden werden die vollständig aus SQLite gelesen, in Objekte verwandelt und zweimal
sortiert — um daraus eine Grafik mit 200 Spalten über die letzten vier Stunden zu zeichnen. Von
den geladenen Zeilen landen etwa 17 % überhaupt im Zeitfenster, und die werden dann auf 200
Spalten verdichtet. Der Aufwand wächst mit jeder Stunde Messdauer weiter, der Abstand zwischen
den Durchläufen bleibt bei 5 Sekunden.

**Was stattdessen wirkt** — dieselbe Idee, nur eine Stufe früher: Das Vier-Stunden-Fenster
gehört nicht in den Filter nach dem Laden, sondern in die `WHERE`-Klausel. Dann liefert SQLite
statt 172.000 Zeilen konstant rund 28.000, unabhängig davon, wie lange die Session schon läuft
— und die Kosten hören auf zu wachsen. Das ist genau „die Datenpunkte über die Zeit
minimieren", nur eben dort, wo die Datenpunkte entstehen.

Dazu zwei kleinere Hebel, die nichts kosten: die Kennwerte für das Cockpit brauchen keine
Sortierung (LAeq und Max lassen sich laufend mitführen), und die Rechnung gehört von der
Kompositions-Coroutine auf `Dispatchers.Default`.

**Was ausdrücklich nichts bringt:** `maxSpalten` von 200 auf 100 senken, oder das Fenster von
vier auf zwei Stunden verkleinern. Beides verändert nur die Größe dessen, was am Ende übrig
bleibt — nicht die Menge dessen, was vorher durch den Speicher läuft. Deshalb steht in Teil B
Aufgabe 1 ausdrücklich, das *nicht* zu tun.

---

## Teil D — Was offen bleibt

- **Wie stark sich das in der Praxis auswirkt, ist nicht gemessen.** Der Code belegt, *dass*
  alle 5 Sekunden die Vollmenge verarbeitet wird; *wie sehr* das ruckelt und was es an Akku
  kostet, sagt nur eine Messung an einer über Stunden gelaufenen Session am echten Gerät. Die
  Definition of Done in Aufgabe 1 verlangt genau diese Messung.
- **Der Kontrast des Dunkelmodus ist ungeprüft.** Das Farbschema existiert (A3), ob die
  Kombinationen die 4,5:1 erreichen, ist eine visuelle Prüfung und steht weiter in
  `PROMPT_M9_UX.md` Aufgabe 2.
- **Die A/C-Zuordnung des PCE-323 ist bestätigt** (`Pce323Profile.MODE_ASSUMPTION_CONFIRMED =
  true`, siehe Korrekturhinweis bei A5) — anders als eine frühere Fassung dieses Abschnitts
  behauptete. Aufgabe 2 ist damit kein Warten mehr auf eine offene Messfrage, sondern macht den
  Bericht robust gegenüber Altbestand und einem hypothetischen künftigen Zurücksetzen des Flags.
- **Der Rest von M9 und ganz M10 sind unberührt.** Dieses Dokument deckt nur ab, was der Owner
  entschieden hat.
