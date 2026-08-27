# Prompt: M10 — neue Funktionen (Vorschlagskatalog + Umsetzungsauftrag Stufe 1)

Gegenstück zu [`docs/PROMPT_M9_UX.md`](PROMPT_M9_UX.md): **M9 repariert und vereinheitlicht,
was da ist — M10 baut Neues.** Beides in einer Session zu vermischen macht den Review unmöglich,
deshalb zwei Meilensteine.

**Zur Nummerierung:** M8 (Härtung, Plan Abschnitt 12) behält seine Nummer und seinen Platz. M9
und M10 sind vom Owner direkt eingeschobene Meilensteine wie schon M7b und M7c. Sie stehen nicht
im Implementierungsplan und widersprechen ihm nicht.

**Dieses Dokument enthält zwei Dinge:**

- **Teil A — Vorschlagskatalog.** 15 Funktionen mit Begründung, Aufwandsklasse und Abhängigkeit.
  Das ist ein Angebot, keine Beschlusslage. **Was davon gebaut wird, entscheidet der Owner.**
- **Teil B — Umsetzungsauftrag für Stufe 1.** Die fünf Funktionen, die ohne Room-Schemaänderung
  auskommen und deshalb sofort startbar sind. Fertig formuliert, sobald der Owner sie freigibt.

Teil C nennt die Entscheidungen, die vor Stufe 2 fallen müssen.

Alle Vorschläge sind aus dem gelesenen Code abgeleitet (Stand `main` @ `0bbb33e`, nach PR #46) —
jeder nennt, worauf er aufbaut, damit erkennbar ist, was schon existiert und was wirklich neu
wäre.

> **Ein Vorschlag ist bereits umgesetzt und deshalb gestrichen.** Ein Live-Pegelverlauf auf dem
> Start-Screen stand in der ersten Fassung dieses Katalogs als eigener Vorschlag. PR #46 hat ihn
> gebaut (`ui/PegelverlaufChart.kt`, eingebunden in `ServiceControl` und
> `ProtokollDetailScreen`) — mit Achsenbeschriftung, Lückendarstellung und Live-Kennzeichnung.
> Er steht hier nicht mehr als Vorschlag. Was an seiner Umsetzung noch zu tun ist, ist kein
> Funktionswunsch, sondern ein Befund: siehe `PROMPT_M9_UX.md` Befund A1 (der Start-Screen lädt
> und verrechnet dafür alle 5 Sekunden die gesamte Messreihe der Session neu).

---

## Teil A — Vorschlagskatalog

**Aufwandsklassen:** S = eine Sitzung · M = mehrere Sitzungen · L = eigener Meilenstein.
**Schema:** ob eine Room-Migration nötig wird (dann Migrationstest, AGENTS.md §5).

### Stufe 1 — kein neues Datenmodell nötig

#### F1 · Schwellenwert-Assistent · S · kein Schema

Der Aufnahme-Schwellenwert ist der wichtigste Wert der App und heute ein nackter Slider mit
einer Zahl (`SettingsScreen.kt:162` für das Mikrofon; die Messgerät-Schwelle ist mit PR #43
entfallen, weil bei bestehender Messgerät-Verbindung durchgehend aufgezeichnet wird). Niemand
weiß, ob „60" für seine Wohnung viel oder wenig ist — der Mikrofonwert ist obendrein
unkalibriert und geräteabhängig, wie README und Plan selbst festhalten.

Vorschlag: **Der aktuelle Pegel steht live neben dem Slider**, als Marker auf der Skala und als
Zahl. Dazu zwei Knöpfe: „Schwelle auf aktuellen Pegel setzen" und „+5 dB über aktuell". Wer 30
Sekunden lang die eigene Ruhe misst und dann „+5 dB" drückt, hat eine sinnvolle Schwelle, ohne
irgendetwas über dB zu wissen.

Baut auf: dem Mikrofonpegel und `container.meterTransport.frames`, beide bereits als Flow
vorhanden und in `ServiceControl`/`MeterScreen` schon so konsumiert.

Von allen Vorschlägen der mit dem besten Verhältnis von Aufwand zu Wirkung: Eine falsch
eingestellte Schwelle macht die gesamte Aufzeichnung wertlos, und heute hat niemand eine
Möglichkeit, sie richtig einzustellen.

#### F2 · Suche und Filter-Vorlagen · S · kein Schema

Heute filtert der Start-Screen über zwei `RangeSlider` (dB, Uhrzeit) und je Tag über ein
Label-Dropdown (`MainActivity.kt:332–383` und `:470–481`). Es gibt keine Textsuche, keinen
Filter auf „nur Messgerät-ausgelöst", „nur mit Notiz", „nur Favoriten", und ein
zusammengestellter Filter ist beim nächsten Öffnen weg (alles `remember`, nichts persistiert).

Vorschlag: Suchfeld über Label und KI-Label; zusätzliche Filter für Quelle (`meterConnected`)
und Kalibrierung (`calibratedDbA != null`); Filterzustand in `SettingsManager` merken. Wer
regelmäßig „Nächte über 70 dB" nachschlägt, stellt das einmal ein.

Hängt inhaltlich an M9 Aufgabe 9: Nach Quelle filtern zu können, ohne die Quelle je Zeile zu
sehen, wäre halb.

#### F3 · Selbstprüfung („Ist alles bereit?") · S · kein Schema

Die App hat viele stille Ausfallgründe: Berechtigung entzogen, Akku-Optimierung wieder aktiv,
Bluetooth aus, kein Gerät gepinnt, ntfy nicht konfiguriert, Drive-Ordner nicht eingerichtet,
`SCHEDULE_EXACT_ALARM` nicht gewährt. Diese Information liegt heute verstreut über
`SettingsScreen`, `MeterScreen` und `DiagnoseScreen` — und teils gar nicht. Das neue
`BluetoothStatusBadge` aus PR #46 deckt genau einen dieser Punkte ab, und nur auf „Start".

Vorschlag: Ganz oben auf `DiagnoseScreen` eine Prüfliste, jede Zeile grün/gelb/rot mit einem
Knopf, der genau das behebt (Systemeinstellung öffnen, Scan starten, Probealarm senden).
Zusätzlich ein Warnbanner auf dem Start-Screen, wenn eine **rote** Zeile existiert, während die
Überwachung läuft — genau der Fall, in dem der Nutzer glaubt, er protokolliere, und es passiert
nichts.

Baut auf: alle Einzelprüfungen existieren schon verstreut im Code; hier werden sie an einer
Stelle zusammengeführt.

#### F4 · Notification-Aktionen · S · kein Schema

Die Dauer-Notification (`AudioRecordingService.kt:280–310`) zeigt Zustand und Text und hat einen
Stop-Intent. Für eine App, die stunden- bis nächtelang läuft, ist die Notification die
eigentliche Bedienoberfläche — dort steht der zuverlässigste Statuswert des ganzen Systems (so
sagt es `BESTANDSAUFNAHME_UI.md` Abschnitt 2 selbst).

Vorschlag: Aktionen „Beenden" und „Ereignis markieren" (Letzteres setzt F6 voraus), aktueller
Pegel im Text, und bei Verbindungsverlust ein sichtbarer Zustandswechsel statt nur einer
geänderten Textzeile.

#### F5 · Speicherplatz und Aufräumen · S · kein Schema

Die App schreibt WAV-Dateien ohne Obergrenze — und seit PR #43 wird bei bestehender
Messgerät-Verbindung **durchgehend** aufgezeichnet statt nur oberhalb einer Schwelle, was das
Datenaufkommen deutlich erhöht. Es gibt keine Anzeige, wie viel Platz das belegt, und keine
Aufräumfunktion außer manuellem Löschen einzelner Aufnahmen. Der Retention-Job aus M4 verdichtet
nur die Messwerte in der Datenbank, nicht die Audiodateien.

Vorschlag: In den Einstellungen die belegte Größe (Audio / Datenbank getrennt), dazu eine
konfigurierbare automatische Löschung von Audioaufnahmen älter als N Tage — Favoriten und
Aufnahmen mit Label ausgenommen. Vor dem Löschen zeigen, was betroffen wäre.

Durch die Änderung aus PR #43 ist das von „nice to have" zu „läuft sonst irgendwann voll"
geworden.

### Stufe 2 — braucht eine Room-Migration

Für jede dieser Funktionen gilt AGENTS.md §5 unverändert: Tabellen- und Spaltennamen ändern sich
nicht versehentlich, `fallbackToDestructiveMigration()` ist verboten, ein Migrationstest ist der
Beweis. Die vorhandenen Tests für v4 bis v11 sind das Muster.

#### F6 · Manuelle Ereignis-Markierung mit Notiz · S–M · Schema

Der Auslöser ist heute die Schwelle bzw. — bei verbundenem Messgerät — die Verbindung selbst. Es
gibt keine Möglichkeit zu sagen „**jetzt** ist es laut", und genau das ist der häufigste Wunsch
bei einer Dokumentation, die später jemand lesen soll: der Moment, in dem der Mensch etwas
bemerkt, nicht nur der, in dem eine Zahl eine Grenze überschreitet.

Vorschlag: Ein Knopf im Dashboard und in der Notification, der eine Aufnahme erzwingt (der
Pre-Roll-Puffer macht das rückwirkend möglich, er läuft ohnehin) und ein Notizfeld öffnet.
Markierte Ereignisse sind in der Liste erkennbar und getrennt filterbar.

Schema: ein Feld für die Auslöseart und eines für die Notiz an `NoiseRecord`.

#### F7 · Notizen und freie Labels · S · Schema

Heute vier fest verdrahtete Labels (`listOf("Bagger", "Bohren", "Hämmern", "Verkehr")`,
`MainActivity.kt:854`), kein eigenes, kein Entfernen eines gesetzten Labels. Der `label`-String
ist frei — nur die Oberfläche lässt nichts anderes zu.

Vorschlag: Eigene Labels anlegen und verwalten, zuletzt benutzte zuerst, Label wieder entfernen.
Freitext-Notiz je Aufnahme (fällt mit F6 zusammen, wenn beide kommen).

#### F8 · Ruhezeiten · M · Schema

Für die Frage, ob Lärm dokumentationswürdig ist, macht die Uhrzeit den Unterschied. Die App
kennt Ruhezeiten nicht — dieselbe Schwelle gilt um 14 Uhr wie um 2 Uhr.

Vorschlag: Frei konfigurierbare Zeitfenster (Vorbelegung 22:00–06:00, vom Nutzer änderbar) mit
eigener, niedrigerer Schwelle; Ereignisse innerhalb eines Fensters werden als solche
gekennzeichnet, in Liste, Filter und Bericht. Bewusst **frei konfigurierbar und ohne rechtliche
Bewertung** — die App stellt fest, was gemessen wurde, sie beurteilt nicht.

Schema: ein Kennzeichen an `NoiseRecord`; die Fenster selbst können in `SettingsManager` liegen.

#### F9 · Papierkorb · S · Schema

> **Korrektur: bereits umgesetzt.** Entgegen dem Vorschlag unten ("Stufe 2, erst nach dem
> Gerätetest") existiert der Papierkorb bereits vollständig: `ui/TrashScreen.kt` (Liste,
> Wiederherstellen, endgültig löschen inkl. Audiodatei), `NoiseRecord.deletedAt: Long?` als
> weiches Löschen (dient gleichzeitig als Flag und Zeitstempel), `MIGRATION_11_12` in
> `data/AppDatabase.kt` (Schema-Version 11→12), automatische endgültige Löschung nach 30 Tagen
> über den bestehenden `RetentionWorker`. Wurde im Rahmen paralleler Arbeit vor dem Gerätetest
> gebaut, nicht auf dessen Abschluss gewartet — funktioniert und ist auf `main`, kein
> Nacharbeitsbedarf. Diese Zeile bleibt stehen, damit sichtbar ist, dass die Reihenfolge unten
> nicht eingehalten wurde, nicht weil die Funktion fehlt.

Löschen ist heute überall endgültig, inklusive Datei (`MainActivity.kt:576`). Das
Rückgängig-Machen per Snackbar aus M9 fängt den Fehlgriff der nächsten fünf Sekunden ab — nicht
den von gestern.

Vorschlag: Markiertes Löschen statt sofortigem, endgültige Entfernung nach 30 Tagen durch den
bestehenden Retention-Worker (`messreihe/RetentionWorker.kt` — der Mechanismus existiert und
läuft täglich), plus eine Papierkorb-Ansicht mit „Wiederherstellen".

#### F10 · Kennwerte in der Protokoll-Liste · S · Schema

`ProtokollScreen` zeigt je Session nur Datum, Gerät und Dauer. Das KDoc begründet das
ausdrücklich: eine Vorschau würde für jede Session sämtliche Messwerte laden. Das ist als
Begründung richtig — und wird hinfällig, sobald die Kennwerte beim Sessionende einmal berechnet
und gespeichert werden.

Vorschlag: LAeq, Max und die Zahl der Ausfälle beim Beenden einer Session in `SessionEntity`
ablegen (`AkustischeKennwerte.berechne` existiert bereits) und in der Liste zeigen. Löst
nebenbei einen Teil von M9-Befund A1 an der Wurzel: Ein gespeicherter Kennwert muss nicht alle
5 Sekunden neu über den Gesamtbestand gerechnet werden.

### Stufe 3 — größere Brocken, jeweils eigene Session

#### F11 · Mikrofon gegen das Messgerät kalibrieren · M · Schema

Die dickste bekannte Einschränkung der App: `20·log10(rms/32767) + 100` ist dBFS plus
willkürlicher Offset. Sobald ein PCE-323 verbunden ist, laufen aber **beide** Quellen parallel —
die App hat für dieselben Zeitpunkte einen kalibrierten und einen unkalibrierten Wert.

Vorschlag: Ein Kalibrierlauf über einige Minuten bestimmt den mittleren Versatz zwischen beiden
und legt ihn ab; danach zeigt die App auch ohne angeschlossenes Messgerät einen korrigierten
Mikrofonwert.

**Zwei Dinge müssen dabei ehrlich bleiben, sonst richtet die Funktion Schaden an:** Ein einzelner
Offset ist keine A-Bewertung — die ist frequenzabhängig, ein konstanter Summand kann sie nicht
ersetzen. Der korrigierte Wert ist eine **Näherung** und muss überall als solche beschriftet
sein, nie als dBA. Und solange `MeterFrame.modeAssumptionConfirmed` `false` ist, ist auch die
Referenz selbst unbestätigt (README-Warnung, Checkliste Teil B2) — die Funktion dürfte dann
höchstens „kalibriert gegen PCE-323 (Bewertung unbestätigt)" behaupten. **Deshalb erst nach dem
Gerätetest umsetzen.**

#### F12 · Wochen- und Monatsbericht mit Diagramm · M · kein Schema

Berichte gibt es heute in zwei Formen: ein Tagesbericht als reiner Text
(`ReportManager.generateDailyReport`) und ein Session-PDF ohne jede Grafik (`MessreiheExport`,
bewusst so entschieden, siehe README). Ein Nachbarschaftskonflikt oder ein Bauvorhaben zieht
sich aber über Wochen.

Vorschlag: Zeitraum wählen, Bericht über alle Sessions und Ereignisse darin, mit dem
Pegelverlauf als gezeichnete Grafik im PDF. Der Zeichencode existiert seit PR #46 fertig und
inklusive Achsenbeschriftung als `PegelverlaufChart` — für ein PDF muss dasselbe auf
`PdfDocument`s `Canvas` gezeichnet werden. Der Aufwand ist dadurch deutlich kleiner geworden.
**Achtung:** `PdfDocument` ist unter Robolectric nicht testbar (README, verifiziertes Limit) —
nur am Gerät prüfbar, das gehört so in den PR geschrieben.

#### F13 · Sicherung und Wiederherstellung aus der App · S–M · kein Schema

Die README weist den Nutzer für das Backup an `adb exec-out run-as … cat databases/noise_database`
— unbenutzbar für jeden, der kein Terminal hat. Gleichzeitig sagt dieselbe README, das Backup sei
vor einem Update „die einzige Rückfalloption". Und `allowBackup` ist im Manifest ausgeschaltet.

Vorschlag: „Sicherung erstellen" schreibt Datenbank und Einstellungen in eine Datei über den
Storage Access Framework-Dialog; „Sicherung einspielen" liest sie zurück, mit deutlicher Warnung
und Versionsprüfung. Der FileProvider-Weg dafür existiert bereits; der Support-Bundle-Exporter
aus PR #39 (`diagnose/export/SupportBundleExporter.kt`) zeigt das ZIP-Muster bereits vor.

#### F14 · Widget und Schnelleinstellungs-Kachel · M · kein Schema

Start und Stopp der Überwachung erfordern heute das Öffnen der App. Eine Kachel in den
Schnelleinstellungen (`TileService`) und ein kleines Homescreen-Widget mit Zustand und aktuellem
Pegel machen aus einer App, die man bedient, eine, die man einschaltet.

#### F15 · Alarm-Historie im Protokoll · S · kein Schema

`AlertEntity`/`AlertDao` speichern Alarme bereits (M5), aber keine Oberfläche zeigt sie —
`DiagnoseScreen` zeigt Diagnose-Log, Sync-Historie und seit PR #39 den Diagnosekern, nicht die
ausgelösten Alarme. Wann alarmiert wurde, ob eine Entwarnung raus ist und über welchen Kanal,
ist damit nur in der Datenbank sichtbar. Kleine Funktion, direkte Wirkung: bei einer
Alarmierung, der man vertrauen soll, muss nachlesbar sein, dass sie funktioniert hat.

### Bewusst nicht vorgeschlagen

- **Cloud-Konto, Mehrgeräte-Sync, eigenes Backend.** Die App synchronisiert per Drive in einen
  vom Nutzer gewählten Ordner (M7b) und alarmiert per ntfy (M5) — beides ohne eigenen Dienst.
  Das ist eine Stärke, kein Mangel.
- **Automatische rechtliche Bewertung** („Grenzwert überschritten"). Die App misst und
  dokumentiert. Sie kann bis heute nicht einmal belegen, dass ihr Wert dBA ist (README-Warnung).
  Eine Ampel „zulässig/unzulässig" wäre eine Behauptung, die die Datenlage nicht trägt.
- **Eigene Geräuscherkennung trainieren.** Die vorhandene Referenz-Funktion („Geräusch lernen")
  vergleicht YAMNet-Ausgaben; echtes Training gehört nicht in eine Telefon-App.
- **Weitere Messgeräte-Modelle.** `MeterTransport` ist die richtige Abstraktion dafür, aber das
  erste Gerät ist noch nicht vollständig am realen Gerät verifiziert.

---

## Teil B — Umsetzungsauftrag Stufe 1

Erst starten, wenn der Owner die Auswahl bestätigt hat (Teil C, Entscheidung 1).

```text
Du setzt M10 Stufe 1 um, einen vom Owner direkt beauftragten Funktions-Meilenstein (kein
Plan-Kapitel).

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m10-funktionen-stufe1

ZUERST LESEN
1. docs/PROMPT_M10_FUNKTIONEN.md Teil A, Abschnitt "Stufe 1" — die Begründung je Funktion
2. docs/PROMPT_M9_UX.md — M9 muss auf main sein, bevor das hier beginnt (siehe VORAUSSETZUNG)
3. docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md Abschnitte 4 und 9 — Architektur und UI
4. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert
5. AGENTS.md — vollständig
Alle Zeilenangaben in Teil A beziehen sich auf main @ 0bbb33e. Ist main weitergelaufen, die
Stellen über den zitierten Code wiederfinden, nicht blind der Zeilennummer folgen.

VORAUSSETZUNG
M9 ist auf main. Grund: Die Funktionen hier bringen neue Texte, neue Farben und neue Zustände
mit. Vor M9 gäbe es weder String-Ressourcen noch Farbtokens noch einen SnackbarHost — jede
Zeile hier müsste danach ein zweites Mal angefasst werden. Ist M9 noch nicht gemergt: melden
und anhalten, nicht vorgreifen.

KEINE ROOM-MIGRATION IN DIESEM AUFTRAG
Alle fünf Funktionen kommen ohne Schemaänderung aus. Beide Room-Migrationstests müssen
unverändert grün bleiben und app/schemas/ darf sich nicht ändern. Wenn eine Aufgabe hier
scheinbar ein neues Feld braucht: Das ist ein Denkfehler oder gehört zu Stufe 2 — melden,
nicht heimlich migrieren.

=== AUFGABE 1: Schwellenwert-Assistent (F1) — erledigt ===

> **Nachtrag/Korrektur:** Ein Mikrofonpegel-Flow war entgegen der Annahme unten NICHT bereits
> vorhanden - `AudioRecordingService` berechnete `currentDb` pro Audio-Buffer nur lokal für den
> Schwellenvergleich, ohne ihn nach außen zu geben. Ergänzt: `AudioRecordingService.currentMicDb`
> (statischer `StateFlow<Double?>`, `null` solange keine Überwachung läuft, analog zu
> `laeuft`/`audioAufnahmeAktiv`). Die Vorschlagsknöpfe wirken bewusst NUR auf den Mikrofonpegel,
> nicht auf `container.meterTransport.frames` (den es wie beschrieben bereits gibt) - die
> Schwelle wird ausschließlich gegen den Mikrofonpfad geprüft, ein Vorschlag auf Basis des
> kalibrierten Messgerätewerts läge auf der falschen Skala. Der Messgerätewert wird hier deshalb
> nicht zusätzlich anzeigt; das wäre eine separate, kleinere Ergänzung.

Am Schwellen-Slider in SettingsScreen:
- Aktueller Pegel live neben dem Slider, als Zahl und als Marker auf der Skala. Quelle: der
  Mikrofonpegel; wenn ein Messgerät verbunden ist, zusätzlich dessen Wert aus
  container.meterTransport.frames. Beide sind bereits als Flow vorhanden.
- Zwei Knöpfe: "Auf aktuellen Pegel" und "+5 dB über aktuell".
- Läuft die Überwachung gerade nicht, gibt es keinen Live-Wert. Diesen Fall ausschreiben
  ("Überwachung starten, um den aktuellen Pegel zu sehen") und die Knöpfe sperren — nicht
  einfach 0 dB anzeigen.
- Die Zuordnung Pegel -> Schwellenvorschlag als reine Funktion, JVM-testbar.
- Nicht vergessen: seit PR #43 gibt es KEINEN eigenen Messgerät-Schwellwert mehr (bei
  bestehender Verbindung wird durchgehend aufgezeichnet). Keinen wieder einführen.

=== AUFGABE 2: Suche und Filter-Vorlagen (F2) ===

Auf dem Start-Screen:
- Suchfeld über label und detectedLabel.
- Zusätzliche Filter: nur messgerät-ausgelöst (meterConnected), nur mit kalibriertem Wert
  (calibratedDbA != null), nur Favoriten.
- Filterzustand (dB-Bereich, Uhrzeit, Suchtext, Schalter) in SettingsManager persistieren,
  damit er das Schließen der App überlebt.
- Filtert der aktive Filter alles weg, gehört das gesagt — mit einem Knopf "Filter
  zurücksetzen". (Der Leerzustand selbst kommt aus M9 Aufgabe 6; hier nur der Filter-Fall.)
- Die gesamte Filterung als reine Funktion (Liste rein, Liste raus), JVM-getestet. Nicht
  wieder inline im Composable wie heute in MainActivity.

=== AUFGABE 3: Selbstprüfung (F3) ===

Neue Prüfliste ganz oben auf DiagnoseScreen, je Zeile Zustand (ok / Hinweis / Problem), Text
und, wo möglich, ein Knopf, der es behebt:
- RECORD_AUDIO, POST_NOTIFICATIONS, BLUETOOTH_SCAN/CONNECT erteilt?
- Akku-Optimierung ausgenommen? (Prüfung existiert in SettingsScreen)
- SCHEDULE_EXACT_ALARM gewährt? (Prüfung existiert in SettingsScreen)
- Bluetooth-Adapter an? (BluetoothAdapterStateObserver existiert)
- Messgerät gepinnt, und wenn ja: aktueller Verbindungszustand? (BluetoothStatusBadge aus
  PR #46 hat die Anzeigelogik dafür schon — wiederverwenden, nicht neu bauen)
- Alarmierung aktiv und ntfy konfiguriert?
- Drive-Sync aktiv und Ordner eingerichtet?
- Diagnose-Log an oder aus?
Dazu ein Banner auf dem Start-Screen, wenn mindestens eine Zeile "Problem" ist WÄHREND die
Überwachung läuft — mit Sprung auf die Diagnose. Nicht bei "Hinweis", sonst gewöhnt man sich
das Wegklicken an.
Die Auswertung (Einzelzustände rein, Zeilen und Gesamtbewertung raus) als reine Funktion,
JVM-getestet, inklusive der Fälle "alles ok" und "mehrere Probleme gleichzeitig".

=== AUFGABE 4: Notification-Aktionen (F4) ===

In AudioRecordingService.buildNotification():
- Aktion "Beenden" als echte Notification-Action (der Stop-Intent existiert bereits).
- Aktuellen Pegel in den Text aufnehmen, wenn einer vorliegt. Auf die Aktualisierungsrate
  achten: die Notification NICHT mit jedem Frame neu setzen (rund alle 515 ms) — höchstens
  alle paar Sekunden, sonst kostet das spürbar Akku und das System drosselt ohnehin.
- Bei DEGRADED/FAILED sichtbar unterscheidbar werden, nicht nur eine geänderte Textzeile.
- Die Aktion "Ereignis markieren" gehört zu F6 und ist NICHT Teil dieses Auftrags.

=== AUFGABE 5: Speicherplatz und Aufräumen (F5) ===

In den Einstellungen, eigener Abschnitt:
- Belegter Platz, getrennt nach Audiodateien und Datenbank.
- Automatische Löschung von Audioaufnahmen älter als N Tage (Default: aus). Ausgenommen:
  Aufnahmen mit gesetztem Label und solche, deren Muster als Referenz gelernt wurde.
- Ausführung über den bestehenden RetentionWorker-Mechanismus — kein zweiter Zeitplaner.
- Vor der ersten Aktivierung zeigen, wie viele Aufnahmen und wie viel Platz das jetzt beträfe.
  Eine Aufräumfunktion, die ungefragt loslegt, ist ein Datenverlust mit Einstellungsschalter.
- Die Auswahllogik (welche Dateien fallen weg) als reine Funktion, JVM-getestet, ausdrücklich
  mit einem Test für "geschützte Aufnahmen bleiben".

NICHT TEIL VON M10 STUFE 1
- Alles aus Stufe 2 und Stufe 3 des Katalogs (jede Room-Migration).
- Reine UX-Reparaturen — die sind M9. Fällt dabei etwas auf, das M9 übersehen hat: im PR
  melden, nicht nebenbei miterledigen.
- BLE-Protokollcode, Decoder, ConnectionSupervisor.
- Neue Chart- oder UI-Bibliothek. PegelverlaufChart aus PR #46 ist da und reicht.

TESTS
- Für jede der fünf Aufgaben mindestens ein JVM-Test der ausgelagerten Logik (jeweils oben
  benannt). Das ist der Stil, den MeterScreen (scanFehlermeldung), ServiceControl
  (leiteDashboardAnzeigeAb), ChartDaten (downsample*), BluetoothStatusBadge
  (BluetoothStatusBadgeTest) und PegelverlaufChart (PegelverlaufChartTest) bereits vorgeben.
- Für jeden neuen Test eine Gegenprobe: schlägt er fehl, wenn man die Logik entfernt?
- Compose-Tests unter Robolectric für die neuen Oberflächenteile (app/src/test/.../ui/).
  Die instrumentierte androidTest-Suite gibt es nicht mehr — nicht versuchen, sie
  wiederzubeleben.
- Beide Room-Migrationstests unverändert grün. Ändert sich app/schemas/, ist etwas falsch.

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün — Ausgabe im PR zeigen, nicht behaupten.
- Beide Room-Migrationstests grün, app/schemas/ unverändert (git diff im PR zeigen).
- Screenshots der neuen Oberflächenteile, hell und dunkel.
- Was ohne Hardware nicht prüfbar war (Live-Pegel vom PCE-323, Notification-Verhalten über
  Stunden), genau so benennen — nicht als geprüft ausgeben (AGENTS.md §6).
- Draft-PR gegen main: was geändert, was verifiziert (Befehl und Ergebnis), was offen.
```

---

## Teil C — Owner-Entscheidungen

1. **Welche Funktionen überhaupt?** Teil B unterstellt alle fünf aus Stufe 1. Wenn davon etwas
   raus soll, jetzt streichen — nicht mitten in der Session. **Vorschlag als kleinstes
   sinnvolles Paket, falls es kürzer sein soll: F1 (Schwellenwert-Assistent) und F3
   (Selbstprüfung).** Diese beiden beantworten zusammen die Frage, die nach dem Gerätetest im
   Raum stand — „läuft gerade was, und stimmt die Einstellung?". Den Live-Verlauf, der dazu
   gehört hätte, hat PR #46 bereits geliefert.

2. **Reihenfolge M9 vor M10?** Teil B setzt es voraus und begründet es. Soll M10 vorgezogen
   werden, wird ein Teil der Arbeit doppelt gemacht (Texte ohne Ressourcen, Farben ohne Tokens,
   Rückmeldungen ohne Snackbar). Bewusst entscheidbar, aber nicht kostenlos. **Zusatzargument
   seit PR #46:** M9 Aufgabe 1 behebt ein Laufzeitproblem, das mit jeder weiteren Funktion auf
   dem Start-Screen schwerer wiegt.

3. **Stufe 2 nach dem Gerätetest?** Alles in Stufe 2 fasst die Datenbank an. Solange der
   Gerätetest offen ist, erhöht jede zusätzliche Migration die Zahl der Dinge, die beim ersten
   echten Dauereinsatz gleichzeitig neu sind. **Vorschlag: Stufe 2 erst nach dem Gerätetest** —
   mit Ausnahme von F10 (Kennwerte in der Session speichern), das M9-Befund A1 an der Wurzel
   löst und deshalb vorgezogen werden darf, wenn der Owner das will.

4. **F11 (Mikrofon-Kalibrierung) überhaupt?** Der Nutzen ist groß — sie behebt die
   Haupteinschränkung der App. Das Risiko ist Fehlinterpretation: ein konstanter Offset ist
   keine A-Bewertung, und die Referenz selbst ist unbestätigt, solange
   `modeAssumptionConfirmed` `false` ist. **Vorschlag: nach dem Gerätetest und nur dann, wenn
   Teil B2 der Checkliste die A/C-Zuordnung bestätigt hat.** Vorher wäre es eine Näherung auf
   einer Annahme, und das ist eine Genauigkeit, die die App nicht behaupten sollte.

---

## Woran die Auswahl gemessen wurde

Nicht daran, was sich gut anhört, sondern an drei Fragen: Beantwortet es eine Frage, die ein
Nutzer heute nicht beantworten kann? Nutzt es Daten oder Bausteine, die schon existieren? Und
hält es die Ehrlichkeit durch, die dieses Projekt sich bisher auferlegt hat — kein „dBA", wo
nur „dB" belegt ist, keine Sicherheit vortäuschen, wo Bonding nicht funktioniert?

Deshalb steht der Schwellenwert-Assistent oben und nicht das Widget: Eine falsch eingestellte
Schwelle macht die gesamte Aufzeichnung wertlos, und heute hat niemand eine Möglichkeit, sie
richtig einzustellen. Und deshalb steht die Kalibrierung trotz ihres Nutzens ganz hinten: Sie
ist die einzige Funktion im Katalog, die eine Genauigkeit behaupten würde, die noch niemand
nachgemessen hat.
