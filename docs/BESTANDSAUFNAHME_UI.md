# Bestandsaufnahme: App-UI (Stand 2026-08-20)

Auslöser: Rückmeldung des Owners nach dem ersten echten Gerätetest — die App müsse "viel
intuitiver" werden, jederzeit zeigen "was gerade läuft", und eine grobe
Aufzeichnungsdarstellung (Grafik: seit wann läuft die Aufzeichnung, welche Werte wurden
gemessen, gab es Verbindungsprobleme) wäre hilfreich.

Dieses Dokument ist eine reine Bestandsaufnahme + Verbesserungsvorschläge — **keine
Umsetzung**. Der Implementierungsauftrag steht separat in `docs/PROMPT_M7C.md`.

---

## 1. Screen-Inventar

| Screen | Datei | Route | Zweck |
|---|---|---|---|
| Home | `MainActivity.kt` (`NoiseProtocolApp`) | `"main"` (Startziel) | Liste aller mikrofon-ausgelösten Aufnahmen (`NoiseRecord`), nach Tag gruppiert, mit dB-/Zeitfiltern, Label/KI-Klassifizierung/Favorit/Löschen je Aufnahme, Tages-ZIP/Bericht-Teilen. Enthält `ServiceControl`. |
| Service-Status (eingebettet) | `MainActivity.kt` (`ServiceControl`) | — | Karte "Monitoring Status: AKTIV/Inaktiv" mit Start/Stop. Einziger Auftritt eines "läuft gerade"-Indikators auf dem Home-Screen. |
| Player | `AudioPlayerScreen.kt` | `"player?path={path}"` | Wiedergabe eines aufgenommenen WAV-Clips mit Wellenform. |
| Einstellungen | `SettingsScreen.kt` | `"settings"` | Alle Einstellungen: Aufnahme, KI, Alarmierung, Drive-Sync, Diagnose-Log, Akku/Alarm-Ausnahmen. 575 Zeilen, ein einziger langer Screen ohne Untergliederung in Tabs/Abschnittsnavigation. |
| Messgerät | `MeterScreen.kt` | `"meter"` | BLE-Kopplung + Live-Anzeige: Verbindungszustand, Pegel, Bewertung/Zeitbewertung/Bereich, Scan-Liste. |
| Protokoll | `ProtokollScreen.kt` | `"protokoll"` | Liste aller Messgerät-Sessions (Datum, Gerät, Dauer/"läuft noch"). |
| Protokoll-Detail | `ProtokollDetailScreen.kt` | `"protokoll/{sessionId}"` | Kennwerte (LAeq/Max/Min/L10/L50/L90) einer Session, CSV/PDF-Export, Liste der Ausfallbänder. |
| Diagnose | `DiagnoseScreen.kt` | `"diagnose"` | Verbindungszustand live, Decode-Fehlerrate live, Reconnect-Zähler, Diagnose-Log, Sync-Historie. |

**8 Screens, kein Bottom-Navigation, keine Navigationsleiste, kein Drawer.** Einstiege liegen
verstreut in der Home-Screen-Kopfzeile: Textbuttons "Messgerät"/"Protokoll", ein Info-Icon
(→ Diagnose) und ein Zahnrad-Icon (→ Einstellungen) (`MainActivity.kt:188–199`). Diagnose ist
über ein reines Info-Icon ohne Text erreichbar — für einen Screen mit Verbindungszustand und
Fehlerdiagnostik nicht selbsterklärend.

## 2. Was der Nutzer heute vom laufenden Betrieb sieht

Drei verschiedene, uneinheitliche Mechanismen:

1. **`ServiceControl` auf dem Home-Screen** (`MainActivity.kt:469–539`): roter Punkt +
   "AKTIV"/"Inaktiv"-Text. Wird **einmalig** über `ActivityManager.getRunningServices()` beim
   Öffnen ermittelt — **kein** live beobachteter State. Stoppt der Service im Hintergrund
   selbst (z. B. Herstellerdrossel), zeigt die Karte weiter "AKTIV", bis der Screen neu
   geöffnet wird. Zeigt außerdem nur "läuft der Service", nicht "ist das Messgerät verbunden"
   oder "wie lange läuft die aktuelle Session schon".
2. **Persistente Notification** (`AudioRecordingService.buildNotification()`,
   `audio/AudioRecordingService.kt:244–260`): zeigt den echten, live per
   `connectionSupervisor.state` aktualisierten Verbindungszustand — der zuverlässigste
   Indikator im System, aber nur sichtbar, wenn man die Notification aufklappt.
3. **`MeterScreen`/`DiagnoseScreen`**: beide beobachten `connectionSupervisor.state` live per
   `collectAsState()` — korrekt, aber nur sichtbar, wenn man aktiv dorthin navigiert.

**Befund:** Der einzige Ort, an dem ein Nutzer beim Öffnen der App sofort sieht "läuft die
Überwachung gerade, ist das Messgerät verbunden, seit wann" ist aktuell keiner — man muss
entweder die Notification aufklappen oder zu `MeterScreen`/`DiagnoseScreen` navigieren. Genau
das deckt sich mit der Owner-Rückmeldung.

## 3. Diagramm-/Chart-Infrastruktur

Es gibt **genau eine** Visualisierung im gesamten Code: `WaveformDisplay` in
`AudioPlayerScreen.kt:106–141`, ein `Canvas`-basiertes Amplituden-Diagramm für die Wiedergabe
eines einzelnen WAV-Clips (100 Balken + rote Fortschrittslinie). Das ist reine
Mikrofon-Clip-Visualisierung, kein Pegel-über-Zeit-Chart für Messgerät-Sessions.

Es gibt **kein** dB-über-Zeit-Diagramm, **kein** Histogramm, **keine** L10/L50/L90-Verteilung,
**keine** Ausfallzeitleiste als Grafik (`ProtokollDetailScreen`s `KennwerteBlock` ist reiner
Text, der PDF-Export ist reiner `PdfDocument`-Text ohne Zeichnung). Es ist keine
Chart-Bibliothek als Dependency vorhanden. Ein "Graf, der zeigt seit wann läuft die
Aufzeichnung, welche Werte gemessen wurden, gab es Verbindungsprobleme" (Owner-Wunsch) muss
komplett neu gebaut werden — es gibt nichts Wiederverwendbares außer dem
Wellenform-`Canvas`-Beispiel als Beleg, dass ein reiner `Canvas`-Ansatz ohne externe
Bibliothek grundsätzlich machbar ist.

## 4. Konkrete Befunde (Bugs/Risiken)

- **`SettingsScreen` nicht scrollbar — bereits behoben** (PR #27): äußere `Column` hatte kein
  `verticalScroll`, der untere Teil (u. a. der ganze Drive-Sync-Abschnitt) war unerreichbar.
- **`MeterScreen` — dasselbe Strukturrisiko, noch nicht behoben:** `Column(... .fillMaxSize())`
  bei `MeterScreen.kt:168` trägt Verbindungszustand, Bonding-Warnung, ggf.
  Geräte-Pinning-Spoofing-Warnung, Live-Pegel-Karte, Annahme-Hinweis-Karte UND darunter ein
  `LazyColumn` mit den gescannten Geräten (`MeterScreen.kt:318`) — alles ohne
  `verticalScroll`. Anders als bei `SettingsScreen` crasht das nicht (kein
  `LazyColumn`-in-`verticalScroll`-Konflikt), aber bei vielen gleichzeitig sichtbaren Warnungen
  (Bonding + Spoofing) und vielen gefundenen Geräten auf kleinen Screens kann der feste Teil
  bereits den gesamten sichtbaren Bereich verbrauchen und die Geräteliste bzw. den
  "Verbinden"-Button aus dem Sichtbereich drängen, ohne dass man scrollen kann. Kandidat für
  denselben Fix wie `ProtokollDetailScreen`/`DiagnoseScreen`: ein einziges `LazyColumn` mit
  `item {}` für die feste Kopfzeile und `items()` für die Geräteliste.
- **`ServiceControl` pollt statt zu beobachten** (s. o.) — zeigt im schlimmsten Fall einen
  veralteten Status an.
- **Diagnose-Log und Sync-Historie sind Snapshot, nicht live** (`DiagnoseScreen.kt:66–74`, ein
  `LaunchedEffect(Unit)` statt eines beobachteten `Flow`) — wer den Screen offen lässt, sieht
  neue Diagnose-Einträge nicht, ohne den Screen zu verlassen und neu zu öffnen.

## 5. Verbesserungsvorschläge

Absichtlich nicht priorisiert oder in Aufwand geschätzt — das ist Sache von
`docs/PROMPT_M7C.md` bzw. der Owner-Entscheidung, was zuerst kommt.

1. **Live-Status-Dashboard auf dem Home-Screen.** `ServiceControl` durch eine Karte ersetzen,
   die tatsächlich aus `connectionSupervisor.state` (falls Messgerät gepinnt) bzw. dem
   Service-Laufzustand als `StateFlow` gespeist wird, nicht per einmaligem Poll. Zeigt auf
   einen Blick: läuft die Überwachung, ist das Messgerät verbunden, seit wann läuft die
   aktuelle Session (Live-Timer), aktueller Pegel. Macht `MeterScreen` nicht überflüssig
   (dort bleiben Kopplung/Scan/Detailwerte), aber der Home-Screen muss die Kernfrage "läuft
   gerade was?" ohne Navigation beantworten.
2. **Aufzeichnungs-Chart.** Ein Pegel-über-Zeit-Diagramm für eine (laufende oder
   abgeschlossene) Session: X-Achse Zeit seit Sessionbeginn, Y-Achse dB, mit farblich
   markierten Ausfallbändern (Daten dafür existieren bereits: `leiteAusfallbaenderAb()` aus
   M7-A) und optional den bereits vorhandenen `MinuteAggregateEntity`-Werten für lange
   Sessions statt aller Rohpunkte. Sinnvoller Ort: neuer Abschnitt in
   `ProtokollDetailScreen` (historisch) und/oder ein Live-Ausschnitt auf `MeterScreen`/im
   Dashboard (laufend). Reines `Canvas` reicht für den Umfang — keine neue Abhängigkeit
   nötig, siehe `WaveformDisplay` als Vorbild.
3. **Navigationsstruktur konsolidieren.** Statt verstreuter Text-/Icon-Buttons in der
   Home-Kopfzeile: eine erkennbare Struktur (z. B. `NavigationBar` mit 3–4 klar benannten
   Zielen: Start/Live, Protokoll, Diagnose, Einstellungen). Verringert die Fläche, auf der ein
   Nutzer suchen muss, um überhaupt zu verstehen, welche Screens es gibt.
4. **Konsistente Scroll-Absicherung.** `MeterScreen` auf dasselbe `LazyColumn`-Muster wie
   `ProtokollDetailScreen`/`DiagnoseScreen` umstellen (Befund 4.2), und als Konvention für
   künftige Screens festhalten: jeder potenziell lange Screen bekommt von Anfang an entweder
   `verticalScroll` (reine `Column`) oder wird direkt als `LazyColumn` gebaut, nie eine reine
   `Column` ohne Scroll-Absicherung.
5. **Diagnose-Log/Sync-Historie live statt Snapshot.** DAOs auf `Flow`-Rückgabe umstellen
   (analog zu `NoiseDao.getAll()`), damit `DiagnoseScreen` neue Einträge automatisch zeigt,
   ohne den Screen neu zu öffnen.
6. **Terminologie/Struktur vereinheitlichen.** Einheitliche Sprache für "läuft" vs.
   "historisch/abgeschlossen" über alle Screens (aktuell: "läuft noch" in `ProtokollScreen`,
   "AKTIV/Inaktiv" in `ServiceControl`, reine Zustandsnamen in `MeterScreen`/`DiagnoseScreen`)
   — kein funktionaler Bug, aber Teil von "intuitiver".

## 6. Was bewusst nicht Teil dieser Bestandsaufnahme ist

- Keine Bewertung einer konkreten Chart-Bibliothek (Vico, MPAndroidChart, eigenes Canvas) —
  das ist eine Umsetzungsentscheidung für `PROMPT_M7C.md`.
- Kein visuelles Redesign (Farben, Typografie, Material-3-Theming) — das ist Geschmackssache
  des Owners, keine Bestandsaufnahme-Aufgabe.
- Keine Bewertung der Onboarding-/Kopplungs-Screens im Detail (existieren bereits aus früheren
  Meilensteinen, laut `docs/PROMPT_M7.md` nicht Teil des UI-Ausbaus) — nur am Rande über die
  Navigationsstruktur berührt.
