# Prompt: M7c — UI-Überarbeitung (Live-Status, Aufzeichnungs-Chart, Navigation)

Für eine eigene Session, idealerweise **Antigravity** (Emulator/visuelles Feedback nötig, siehe
AGENTS.md §8) — reine Layout-/Compose-Arbeit ohne BLE-Protokoll-Anteil. Voraussetzung: M7 und
M7b sind auf `main`. Grundlage ist `docs/BESTANDSAUFNAHME_UI.md` — dort steht die vollständige
Analyse, hier nur der Umsetzungsauftrag.

**Nachträglich benannt:** M8 ist im Plan (Abschnitt 12) bereits für "Härtung" reserviert
(Chaos-Checkliste, 24h-Dauerlauf, Release-Build) — dieser UI-Umbau ist wie M7b ein vom Owner
direkt eingeschobener Meilenstein und heißt deshalb M7c, um die Plan-Nummerierung nicht zu
kollidieren.

---

```text
Du setzt einen vom Owner direkt beauftragten Meilenstein um (kein Plan-Kapitel — die
Bestandsaufnahme dazu steht in docs/BESTANDSAUFNAHME_UI.md, lies sie vollständig zuerst).

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m7c-ui-ueberarbeitung

ZUERST LESEN
1. docs/BESTANDSAUFNAHME_UI.md — vollständig, das ist die Grundlage für alles hier
2. README.md — Statusüberblick
3. docs/PROMPT_M7.md — was M7 bereits gebaut hat, damit nichts doppelt entsteht
4. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert

AUSGANGSLAGE (siehe Bestandsaufnahme für Details)
- 8 Compose-Screens ohne Bottom-Navigation, Einstiege verstreut in der Home-Kopfzeile
- Kein Live-Status auf dem Home-Screen — ServiceControl pollt einmalig statt zu beobachten
- Keinerlei Chart-/Diagramm-Infrastruktur außer WaveformDisplay (AudioPlayerScreen.kt,
  reines Canvas für WAV-Wiedergabe, kein Pegel-über-Zeit-Chart)
- MeterScreen.kt:168 hat dasselbe Strukturrisiko wie das bereits behobene SettingsScreen-
  Scroll-Problem (PR #27) — Column(...fillMaxSize()) ohne verticalScroll, mit LazyColumn
  für die Geräteliste darunter
- ProtokollDetailScreen/DiagnoseScreen sind bereits nach dem richtigen Muster gebaut (ein
  LazyColumn mit item{}/items()) — als Vorbild verwenden, nicht neu erfinden

=== AUFGABE 1: Live-Status-Dashboard auf dem Home-Screen ===

ServiceControl (MainActivity.kt:469-539) ersetzen durch eine Karte, die tatsächlich beobachtet
statt einmalig zu pollen:

- Verbindungszustand aus container.connectionSupervisor.state (StateFlow, wie in MeterScreen/
  DiagnoseScreen bereits verwendet) statt ActivityManager.getRunningServices()
- Laufzeit der aktuellen Session (falls eine läuft): Live-Timer seit SessionEntity.startedAt
- Aktueller Pegel, falls ein Messgerät verbunden ist
- Weiterhin Start/Stop-Kontrolle für den AudioRecordingService

Kein Mikrofon-Only-Fall vergessen: Ohne gepinntes Messgerät läuft die Überwachung weiterhin
über das Mikrofon (bestehendes Verhalten) — die Karte muss auch diesen Fall sinnvoll anzeigen,
nicht nur den Messgerät-Fall.

=== AUFGABE 2: Aufzeichnungs-Chart ===

Neues, reines Canvas-Diagramm (kein neuer Dependency, WaveformDisplay als Vorbild für die
grundsätzliche Machbarkeit) für den Pegelverlauf einer Session:

- X-Achse: Zeit seit Sessionbeginn. Y-Achse: dB.
- Datenquelle: MeasurementEntity (Rohwerte) oder MinuteAggregateEntity (falls der
  Retention-Job bereits komprimiert hat, wie AkustischeKennwerte.ausAggregaten() es bereits
  für die Kennwerte handhabt) — beide Fälle abdecken, nicht nur den Rohwerte-Fall.
- Ausfallbänder farblich markieren — die Daten dafür existieren bereits
  (leiteAusfallbaenderAb() aus M7-A), nicht neu ableiten.
- Einbauort: neuer Abschnitt in ProtokollDetailScreen (historische Session). Zusätzlich ein
  Live-Ausschnitt im neuen Dashboard (Aufgabe 1) ist wünschenswert, aber nicht Pflicht für
  "fertig" — wenn die Zeit knapp wird, zuerst die historische Ansicht fertigstellen.
- Für sehr lange Sessions (viele tausend Rohpunkte): nicht jeden Punkt zeichnen. Auf eine
  vernünftige Anzahl Pixel-Spalten downsamplen (Min/Max/Mittel pro Spalte reicht), sonst
  wird der Canvas-Code auf einem echten Gerät langsam.

=== AUFGABE 3: Navigationsstruktur konsolidieren ===

Statt der verstreuten Text-/Icon-Buttons in der Home-Kopfzeile (MainActivity.kt:188-199):
eine erkennbare Struktur mit klar benannten Zielen (z. B. eine NavigationBar mit 3-4 Einträgen:
Start/Live, Protokoll, Diagnose, Einstellungen). Diagnose ist aktuell nur über ein Info-Icon
ohne Text erreichbar — mindestens das beheben, auch wenn keine volle NavigationBar entsteht.

Freiheit bei der konkreten Umsetzung (NavigationBar vs. NavigationRail vs. verbesserte
Kopfzeile) — das ist eine visuelle Entscheidung, für die diese Session (mit Emulator) besser
geeignet ist als eine reine Bestandsaufnahme.

=== AUFGABE 4: MeterScreen-Scroll-Risiko beheben ===

MeterScreen.kt auf dasselbe Muster wie ProtokollDetailScreen/DiagnoseScreen umstellen: ein
einziges LazyColumn mit item{} für die feste Kopfzeile (Verbindungszustand, Warnungen,
Live-Pegel-Karte) und items() für die Geräte-Scan-Liste, statt der aktuellen Column mit
verschachteltem LazyColumn ohne Scroll-Absicherung.

=== AUFGABE 5: Diagnose-Log/Sync-Historie live statt Snapshot ===

DiagnosticLogDao.alle() und DriveDailyFileDao.alle() (oder die aufrufende Stelle in
DiagnoseScreen) auf Flow umstellen, analog zu NoiseDao.getAll(), damit DiagnoseScreen neue
Einträge automatisch zeigt, ohne den Screen neu zu öffnen.

NICHT TEIL VON M7c
Visuelles Redesign (Farben, Typografie, Material-3-Theming) — reine Geschmacksfrage des
Owners, hier nicht vorwegnehmen. Keine neue Chart-Bibliothek als Dependency ohne Rückfrage
beim Owner — Canvas reicht laut Bestandsaufnahme für den Umfang. Keine Änderung an
Onboarding-/Kopplungs-Screens. Kein Bluetooth-Protokoll-Code.

REGRESSIONSTEST FÜR DAS SCROLL-MUSTER
Mindestens ein Compose-UI-Test unter Robolectric, der das genau in Aufgabe 4 behobene
Strukturproblem als Regression absichert: Screen in einem festen, kleinen Viewport rendern
und prüfen, dass ein Element weit unten im Inhalt (z. B. der letzte Eintrag der Geräteliste
oder ein testTag am Bildschirmende) per performScrollTo().assertIsDisplayed() erreichbar ist.

Die Machbarkeit ist bereits geklärt, nicht mehr offen: PR #29 (test/compose-ui-robolectric-
spike, Branch test/compose-ui-robolectric-spike) hat mit einem generischen Beispiel belegt,
dass Compose-UI-Tests unter der hier verwendeten Robolectric-Version (4.16.1) sauber laufen —
inklusive des wichtigen Befunds, dass assertIsDisplayed() allein NICHT automatisch scrollt und
performScrollTo() davor stehen muss. Dependencies (androidx.compose.ui:ui-test-junit4 als
testImplementation, androidx.compose.ui:ui-test-manifest als debugImplementation) sind dort
bereits eingeführt, nach dem Mergen von PR #29 in dieser Session einfach weiterverwenden statt
neu einzurichten. Offen ist nur noch, ob ein Test gegen eine ECHTE, produktive Screen-Funktion
(mit vollem AppContainer statt des trivialen Spike-Beispiels) genauso sauber läuft — das war
bewusst nicht Teil des Spikes und ist hier zum ersten Mal zu klären.

TESTS
- Aufgabe 1 (Dashboard): Logik (welcher Text/Zustand bei welchem ConnectionState/welcher
  Session) gegen Fakes testbar, ohne echten Service — wie MeterScreen/DiagnoseScreen es
  bereits vormachen.
- Aufgabe 2 (Chart): Die Downsampling-/Datenaufbereitungslogik (Rohwerte bzw. Aggregate zu
  Chart-Punkten) als reine Funktion auslagern und mit JVM-Unit-Tests abdecken — das eigentliche
  Zeichnen (Canvas) ist nur visuell prüfbar, aber die Datenvorbereitung nicht.
- Aufgabe 4: der oben beschriebene Compose-Regressionstest, mindestens für MeterScreen und
  SettingsScreen (dort bereits gefixt, aber ohne Regressionstest — hier nachholen).
- Für jeden neuen Test eine Gegenprobe: Schlägt er fehl, wenn man die zugehörige Logik entfernt?

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün — Ausgabe im PR zeigen, nicht behaupten.
- Beide Room-Migrationstests weiterhin grün.
- Da diese Session vermutlich mit Emulator läuft: die visuellen Änderungen tatsächlich im
  Emulator zeigen (Screenshots im PR), nicht nur kompilieren lassen.
- Draft-PR gegen main mit: was geändert, was verifiziert (Befehl und Ergebnis), was offen.
```

---

## Warum als eigener Meilenstein statt Teil von M7

M7 galt laut `docs/PROMPT_M7.md` als abgeschlossen, bevor der Owner das erste Mal mit echter
Hardware getestet hat. Die hier behandelten Punkte (Live-Status, Chart, Navigation) sind
direktes Feedback aus diesem ersten Gerätetest, nicht Teil des ursprünglichen M7-Umfangs (Plan
Abschnitt 9) — deshalb ein eigener, nachträglich eingeschobener Meilenstein, wie schon M7b.

## Eine Falle, die im Auftrag steht

**Nicht mit dem Chart anfangen, ohne vorher Aufgabe 4 zu erledigen.** Wer versucht, das neue
Chart in `MeterScreen` einzubauen, bevor das bestehende Scroll-Strukturproblem dort behoben
ist, baut auf einer wackligen Grundlage weiter — jedes zusätzliche Element im festen Kopfbereich
verschärft das Risiko aus Bestandsaufnahme-Abschnitt 4.
