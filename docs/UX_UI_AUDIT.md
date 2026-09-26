# UX/UI- und Workflow-Audit der Android-App (Lärmprotokoll)

**Stand:** 25.09.2026 · **Basis:** `main` @ `d3353e3` · **Art:** reines Audit, keine Produktivcode-Änderung.

Dieses Dokument ist das einzige Ergebnis des Auftrags. Es ist so geschrieben, dass ein
Coding-Agent ein einzelnes Finding umsetzen kann, ohne die UX-Konzeption neu zu machen: Jedes
Finding nennt Datei, Symbol, aktuelles Verhalten, Zielverhalten und Testauswirkung.

> **Nachtrag 25.09.2026:** Sechs der sieben offenen Owner-Fragen sind beantwortet und in die
> betroffenen Findings eingearbeitet – siehe
> [Kapitel 35.2](#352-owner-entscheidungen-vom-25092026). Offen bleibt einzig der Default von
> `onboardingCompleted` ([F-14](#f-14)). Dadurch geändert: [F-16](#f-16) wird über **Weg B**
> gelöst (Room-Migration 25 → 26), [F-30](#f-30) steigt von P3 auf P2, [F-02](#f-02) und
> [F-19](#f-19) sind freigegeben.

---

## 1. Executive Summary

Die App ist funktional sehr weit; die UX-Schulden liegen fast alle an **drei** Stellen: (a) der
Weg von „App offen" bis „Messung läuft mit kalibrierten Werten" ist länger und indirekter als
nötig, (b) mehrere intern vorhandene Zustände erreichen die Oberfläche nie, und (c) zwei
Eingabe-Sheets beim Messbeginn kosten den wiederkehrenden Nutzer bei jeder Messung Zeit, obwohl
sich ihre Inhalte selten ändern.

**Die 8 wichtigsten Probleme**

| # | Finding | Kurz | Prio |
|---|---------|------|------|
| 1 | [F-01](#f-01) | Bildschirmdrehung während des Stammdaten-/Foto-Sheets verwirft alle Eingaben **und** das Sheet kommt für diese Messung nie wieder | P0 |
| 2 | [F-02](#f-02) | Kein Verbindungsaufbau beim App-Start: „Bluetooth verbinden" ist faktisch identisch mit „Messung starten", und der Messgerät-Screen liegt 4 Taps tief in den Einstellungen | P1 |
| 3 | [F-03](#f-03) | Nach `ConnectionState.FAILED` versucht die App **nie wieder** automatisch zu verbinden; der einzige Weg zurück ist ein 10-Sekunden-Scan-Dialog | P1 |
| 4 | [F-06](#f-06) | Cockpit-Timer und „Messung läuft" hängen an der *letzten*, nicht an der *offenen* Session → angezeigte Messdauer kann stundenweit falsch sein | P1 |
| 5 | [F-07](#f-07) / [F-08](#f-08) | Der High-End-Bericht prüft seine Voraussetzungen erst *nach* dem Klick; die Pflicht-Gebietseinstufung wird im Bericht-Sheet nicht einmal angezeigt | P1 |
| 6 | [F-04](#f-04) / [F-05](#f-05) | Die System-Selbstprüfung ist teilweise blind (drei Parameter hartkodiert `true`), und **alle** ihre Aktionsknöpfe öffnen denselben System-Screen | P1 |
| 7 | [F-12](#f-12) | „Abgeschlossen" steht an jeder beendeten Session – unabhängig von Datenlücken. `berechneDatenverfuegbarkeitProzent` existiert, wird aber nur im PDF verwendet, nie in der UI | P1 |
| 8 | [F-09](#f-09) / [F-11](#f-11) | Stiller Trigger-Ausfall und Mikrofon-Initialisierungsfehler erscheinen **nur** in der Notification bzw. im Diagnoselog, nie im Cockpit | P1 |

**Größte Quellen unnötiger Interaktion**

1. **Stammdaten-Sheet bei jedem Messbeginn** (`stammdatenAbfrageAktiv` ist per Default `true`):
   13 Textfelder, vorbefüllt, aber jedes „Speichern" legt eine **neue** Verlaufszeile an – nach
   wenigen Messungen erzwingt das im Berichts-Sheet zusätzlich eine manuelle Auswahl pro Tag
   ([F-15](#f-15)).
2. **Bluetooth-Kopplungsdialog statt Reconnect**: Der Status-Badge auf der Startseite öffnet
   immer den Scan-Dialog, auch wenn das Gerät längst gepinnt ist ([F-02](#f-02), [F-03](#f-03)).
3. **Berichtsvorbereitung als Trial-and-Error**: Zeitraum wählen → erzeugen → Fehlermeldung →
   Einstellungen → zurück → erneut ([F-07](#f-07), [F-08](#f-08)).
4. **Zwei parallele Filtersysteme** mit unterschiedlicher Persistenz und zwei Filter-Buttons
   allein im Protokoll-Screen ([F-18](#f-18)).

**Wichtigste unsichtbare/unklare Systemzustände**

* Datenverfügbarkeit / Messlücken einer Session (vorhanden, nur im PDF) – [F-12](#f-12)
* Stiller Trigger-Ausfall, Mikrofon-Soll/Ist-Abweichung (nur Notification) – [F-09](#f-09)
* Bluetooth-Adapter aus (`BluetoothAdapterStateObserver` existiert, ist aber `private`) – [F-04](#f-04)
* Freier Speicherplatz vor dem Messstart (nur Video prüft) – [F-10](#f-10)
* Drive-Sync-Status im Hauptfluss (nur Einstellungen/Diagnose) – [F-13](#f-13)
* „KI aus" vs. „noch nicht klassifiziert" vs. „nichts erkannt" – [F-17](#f-17)

**Größte Quick Wins** (Details in [Kapitel 30](#30-quick-wins))

`rememberSaveable` in den beiden Sheets · echte Werte an `bewerteSystemZustand` · `HealthActionType`
auswerten · Laufzeit über das bereits getestete `leiteDashboardAnzeigeAb` · Vorprüfung des
Berichts als Live-Zustand statt erst beim Klick · Reconnect-CTA statt Scan-Dialog am Badge.

**Größte strukturelle Probleme** (Details in [Kapitel 31](#31-structural-ux-improvements))

Es gibt keinen Ort, der „Bin ich messbereit?" beantwortet; der Messgerät-Screen ist kein
Navigationsziel erster Klasse; und „Berichtsangaben" sind über drei Orte verteilt
(Stammdaten-Sheet pro Messung, Report-Config in den Einstellungen, Nachtrag im Bericht-Sheet),
ohne dass einer davon den Gesamtzustand zeigt.

---

## 2. Geltungsbereich, Methodik und Grenzen

**Untersucht wurde** der gesamte `app`-Modul-Quelltext (202 Kotlin-Dateien unter
`app/src/main/java`, 35.885 Zeilen), die Ressourcen unter `app/src/main/res`, das Manifest, die
193 JVM-/Robolectric-Tests unter `app/src/test`, die 62 instrumentierten Tests unter
`app/src/androidTest` sowie die drei CI-Workflows unter `.github/workflows/`.

**Methode:** statische Code- und Dokumentenanalyse. Jede Aussage im Dokument ist an eine Datei
und, wo sinnvoll, an eine Zeilennummer geknüpft. Zeilennummern beziehen sich auf den oben
genannten Stand.

**Grenzen – ausdrücklich nicht geleistet:**

* **Keine visuelle Prüfung.** In dieser Umgebung lief kein Emulator und kein Gerät. Alle Aussagen
  zu Layout, Kontrast, Insets und tatsächlicher Darstellung sind aus dem Code abgeleitet und in
  [Kapitel 35](#35-offene-punkte--needs-verification) als *Needs verification* markiert, wo sie
  nicht rein aus dem Code belegbar sind.
* **Keine Messung von Laufzeiten.** Aussagen wie „Scan dauert 10 s" stammen aus Konstanten im
  Code (`SCAN_DURATION_MS = 10_000L`), nicht aus einer Messung.
* **Keine Tap-Schätzungen.** Alle Zahlen in den Interaction Traces sind aus den Callback-Ketten
  im Code abgeleitet und dort jeweils belegt. Wo ein Pfad zustandsabhängig ist, sind die
  Vorbedingungen genannt.
* **Kein Produktivcode geändert**, keine Tests angepasst (Auftrag § „Wichtig").

**Verhältnis zu AGENTS.md:** Der Auftrag ist eine vom Owner freigegebene Einzelausnahme von
AGENTS.md §5 (app-weiter, querschnittlicher Audit). Wo ein Finding einer dokumentierten
Owner-Entscheidung widerspricht, ist das im Finding ausdrücklich als Widerspruch markiert und
**nicht** eigenmächtig aufgelöst (AGENTS.md §2, §8a) – siehe [F-16](#f-16) (Reihenfolge
Messung/Foto) und [F-19](#f-19)/[F-20](#f-20) (bereits im README als offene Owner-Entscheidung
geführt).

---

## 3. Ermittelte Architektur

Alles in diesem Kapitel ist aus dem Code ermittelt, nicht aus der Dokumentation übernommen.

### 3.1 UI-Stack

* **Jetpack Compose + Material 3** durchgehend; kein XML-Layout für App-Screens
  (`app/src/main/res/layout/` enthält nur das Widget-Layout).
* **Kein ViewModel, kein DI-Framework.** Jeder Screen holt sich den Zustand direkt aus dem
  manuellen `AppContainer`: `val container = remember { (context.applicationContext as
  LaermprotokollApp).container }` – so in `MainActivity.kt:377`, `LiveCockpitCard.kt:105`,
  `ProtokollScreen.kt:59`, `SettingsScreen.kt`, `DiagnoseScreen.kt:95` usw.
* **Zustand lebt in `remember`/`mutableStateOf` direkt in den Composables.** Room-Flows werden
  per `collectAsState()` gelesen. Das ist die Ursache mehrerer Findings zur Zustandsrettung
  ([F-01](#f-01)).
* **Theme:** `ui/theme/Theme.kt` mit festem Light/Dark-Schema, **Dynamic Color bewusst aus**
  (dokumentierte Owner-Entscheidung M9 Teil C im KDoc). Semantische Statusfarben liegen in
  `ui/theme/Tokens.kt` (`AppStatusColors`/`statusColors`).
* **Typografie:** `ui/theme/Typography.kt` (`provideAppTypography()`).
* **Sprache:** Umschaltung über `AppCompatDelegate.setApplicationLocales()`; `MainActivity` erbt
  deshalb von `AppCompatActivity` (`MainActivity.kt:98`, mit ausführlicher Begründung im Kommentar).

### 3.2 Navigation

`Navigation-Compose`, ein einziger `NavHost` in `AppNavigation()` (`MainActivity.kt:209-316`),
`startDestination = "main"`.

| Route | Composable | Erreichbar über |
|---|---|---|
| `main` | `NoiseProtocolApp` | Start, BottomBar, `popUpTo("main")` |
| `protokoll` | `ProtokollScreen` | BottomBar |
| `protokoll/{sessionId}` | `ProtokollDetailScreen` | Session-Karte im Protokoll |
| `bericht` | `BerichtScreen` | BottomBar |
| `settings?tab={tab}` | `SettingsScreen` | Drei-Punkt-Menü in Start/Daten/Bericht |
| `meter` | `MeterScreen` | **nur** Einstellungen → Start-Tab → Sektion „Aufnahme & Mikrofon" → „Messgerät öffnen" (`SettingsScreen.kt:764`) |
| `diagnose` | `DiagnoseScreen` | Problem-Banner auf Start; Einstellungen (`SettingsScreen.kt:2245`) |
| `player?path={path}` | `AudioPlayerScreen` | Tap auf eine Aufnahme-Karte |
| `video` | `VideoAufnahmeScreen` | Cockpit-Button, nur bei laufender Messung |
| `drive-uploads` | `DriveUploadScreen` | Einstellungen → Daten-Tab (`SettingsScreen.kt:1296`) |
| `trash` | `TrashScreen` | Einstellungen → Daten-Tab (`SettingsScreen.kt:1953`) |
| `ki-erklaerung` | `KiErklaerungScreen` | Einstellungen → Start-Tab (`SettingsScreen.kt:1015`) |

**BottomBar** hat drei Ziele (Start / Daten / Bericht, `AppNavigationBar`, `MainActivity.kt:331-363`)
und bleibt zusätzlich auf `meter`, `diagnose` und `settings*` sichtbar (`showBottomNav`,
`MainActivity.kt:191-193`).

**Zwei verschiedene Navigationsarten für dieselben Ziele:** `navigiereZuTab()` (mit
`popUpTo("main")`, `MainActivity.kt:176-181`) gegenüber schlichtem `navController.navigate(...)`
in den Settings-Callbacks (`MainActivity.kt:249-256`). Für `meter` und `diagnose` werden beide
Varianten verwendet → unterschiedliches Zurück-Verhalten ([F-24](#f-24)).

**Es gibt keinen `BackHandler` und kein `onBackPressed`-Override** in der gesamten App
(geprüft per Suche über `app/src/main`). Zurück ist überall das Plattformverhalten bzw.
`popBackStack()`.

**Deep Links:** keine. Das Manifest deklariert nur `MAIN`/`LAUNCHER` für `MainActivity`
(`AndroidManifest.xml`). Die einzige externe Ansteuerung ist das Extra
`EXTRA_REQUEST_STOP_CONFIRMATION` aus der Notification, das über `PendingUiAction` in einen
Bestätigungsdialog im Cockpit übersetzt wird (`MainActivity.kt:140-148`, `LiveCockpitCard.kt:132-139`).

### 3.3 State Management und Persistenz

* **`SettingsManager`** (`data/SettingsManager.kt`, 715 Zeilen) über zwei `SharedPreferences`:
  `noise_settings` im Klartext und optional `EncryptedSharedPreferences` (`securePrefs`) für
  sensible Werte, mit dokumentiertem Klartext-Fallback.
* **Room** (`data/AppDatabase.kt`, Schemaversion 25) mit u. a. `sessions`, `measurements`,
  `minute_aggregates`, `connection_events`, `dokumentationsfotos`, `stammdaten_verlauf`,
  `report_config`, `noise_records`, `alerts`, `level_samples`, `diagnostic_log`,
  `drive_daily_files`, `klassifikations_rohdaten`, `beweisvideos`.
* **Bildschirm-Zustand:** fast durchgängig `remember` (nicht `rememberSaveable`). Im gesamten
  Produktivcode gibt es genau **drei** `rememberSaveable`-Stellen: `MainActivity.kt:399`,
  `MainActivity.kt:418` und `OnboardingScreen.kt:40`.

### 3.4 Bluetooth-Architektur

```
BleScanner ──┐
             ├─> BleMeterTransport (GattQueue, Pce323FrameDecoder, Pce323Profile)
BluetoothAdapterStateObserver ──> ConnectionSupervisor ──> ConnectionState (StateFlow)
                                          │
                                          ├─> MeasurementRecorder  (Sessions, Messwerte, ConnectionEvents)
                                          ├─> AlarmCoordinator     (Totmannschaltung, opt-in)
                                          └─> AudioRecordingService (Notification, Trigger)
```

* Der Zustandsautomat hat zehn Zustände (`meter/ConnectionState.kt`), deutsche Kurztexte über
  `ConnectionState.label()`.
* Der Supervisor läuft **nur**, solange `AudioRecordingService` läuft: `supervisor.start(device)`
  wird ausschließlich aus `ensureMeterMonitoringStarted()` gerufen
  (`audio/AudioRecordingService.kt:211-230`), und das wiederum nur aus `onStartCommand`.
* Nach `maxAttempts = 8` Fehlversuchen setzt der Supervisor `FAILED` und **beendet seine
  Coroutine** (`meter/ConnectionSupervisor.kt:213-221`).
* `BluetoothAdapterStateObserver` ist in `AppContainer.kt:59` als **`private val`** angelegt und
  nur an den Supervisor verdrahtet – die UI kann ihn nicht lesen.

### 3.5 Aufnahme- und Datenhaltung

`AudioRecordingService` (1.357 Zeilen) ist der einzige Foreground Service. Er hält drei
`StateFlow`s, die die gesamte UI als Wahrheitsquelle benutzt:

| Flow | Bedeutung | Gesetzt in |
|---|---|---|
| `AudioRecordingService.laeuft` | Foreground-Dienst aktiv | `onStartCommand` (`:343`), Stop (`:299`) |
| `AudioRecordingService.audioAufnahmeAktiv` | `AudioRecord` liest tatsächlich | erst nach `startRecording()` (`:618`) |
| `AudioRecordingService.currentMicDb` | aktueller Mikrofonpegel | Lese-Schleife (`:692`) |

`MeasurementRecorder` eröffnet Sessions: Mikrofon-Session sofort beim Start
(`starteMikrofonMessung`, `messreihe/MeasurementRecorder.kt:206`), Messgerät-Session erst beim
ersten fließenden Frame (`eroeffneMessgeraetSession`, `:296`). Es ist immer höchstens eine
Session offen.

### 3.6 Reporting-Architektur

Drei getrennte Berichtswege:

1. **Tagesbericht** – `report/ReportManager.kt`, Einstieg über das Overflow-Menü der Startseite
   und das Diagramm-Icon je Tagesgruppe (`MainActivity.kt:652-664`, `:1080`).
2. **Zeitraum-/Gesamtbericht** – `report/PeriodenBerichtExport.kt` bzw.
   `report/GesamtberichtExport.kt`, Einstieg `BerichtScreen` → `AlertDialog` mit 7-/30-Tage-/
   Monats-Presets (`BerichtScreen.kt:156-212`).
3. **High-End-Bericht** – `report/HighEndReportExport.kt` über `ChaquopyReportRunner`
   (Python in `app/src/main/python/laermbericht/`), Einstieg `BerichtScreen` →
   `BerichtErstellenSheet`.

Vorprüfungen des High-End-Berichts liegen als reine Funktionen in `report/BerichtErstellung.kt`
(`retentionFehler`, `bewertungsFehler`, `auswahlFehler`, `fehlendeStammdatenFelder`) und
`report/ReportArea.kt` (`areaSelectionError`).

### 3.7 Google-Drive-Synchronisation

`DriveSyncCoordinator` (825 Zeilen) + `DriveSyncWorker` (WorkManager, 30-Minuten-Takt,
`DriveSyncPlanung`), `GoogleDriveApiClient`, `DriveAblage` (Ordnerstruktur
`<Ordner>/JJJJMMTT/{WAV,Schallmessung,Fotos,Videos,Bericht}`). Statusanzeige ausschließlich über
`DriveStatusCard`, eingebunden an genau zwei Stellen: `SettingsScreen.kt:1305` und
`DiagnoseScreen.kt:570`. Zusätzlich `DriveUploadScreen` als Liste je Datei und `DriveSyncNotifier`
für zwei Notification-Fälle (Fehlschläge ab 6 Zyklen, Ordner verschwunden).

### 3.8 KI-Klassifizierung

`audio/NoiseClassifier.kt` (MediaPipe Tasks Audio, YAMNet), Modus über
`SettingsManager.aiMode` ∈ {`BATCH` (Default), `ONLINE`, `OFF`}. Nachträgliche Klassifizierung
über `klassifiziereUndSpeichere` (`audio/BatchKlassifizierung.kt`) und `bewerteAlleNeu`
(`audio/NeuBewerten.kt`). Kandidatenauswahl: `unklassifizierteAufnahmen`
(`messreihe/NoiseRecordGrouping.kt:26`).

### 3.9 Tests und CI

* **193 JVM-/Robolectric-Tests**, darunter 21 Room-Migrationstests (`AppDatabaseV4…V25MigrationTest`)
  und ~35 Compose-Tests unter Robolectric (`ui/*ComposeTest.kt`, `ui/*Test.kt`).
* **62 instrumentierte Tests** (`app/src/androidTest`), u. a. `HomeScreenInstrumentedTest`,
  `GesamtberichtStammdatenSheetInstrumentedTest`, `FotoDokumentationSheetPermissionInstrumentedTest`,
  `MeterScreenInstrumentedTest`, `BerichtErstellenSheetInstrumentedTest`.
* **CI** (`.github/workflows/androidci.yml`): `assembleDebug assembleDebugAndroidTest` → `lintDebug`
  → `ktlintCheck` (`continue-on-error: true`, Altbestand rot) → `testDebugUnitTest test` → Kover.
  `.github/workflows/emulator-tests.yml` fährt die instrumentierten Tests auf API 34 (`aosp_atd`).
* **Hand geschriebene Fakes only**, kein Mockito/MockK (AGENTS.md §3, im Code bestätigt).

---

## 4. Screen-, Dialog- und Komponenten-Inventar

### 4.1 Vollbild-Screens (13)

| Screen | Datei | Eigene TopAppBar | Besonderheit |
|---|---|---|---|
| Start / Cockpit | `MainActivity.kt` (`NoiseProtocolApp`, ab :367) | ja, als `LazyColumn`-Item (:545) | einzige `LazyColumn` für den ganzen Screen |
| Live-Cockpit-Karte | `LiveCockpitCard.kt` | – | Kernsteuerung, in Start eingebettet |
| Daten / Protokoll | `ProtokollScreen.kt` | ja | zwei Filter-Buttons + Suchfeld |
| Protokoll-Detail | `ProtokollDetailScreen.kt` | ja | Chart, Ereignisse, Audit-Block, Ausfallbänder |
| Bericht | `BerichtScreen.kt` | ja | nur zwei Buttons |
| Einstellungen | `SettingsScreen.kt` (2.439 Z.) | ja | 3 Tabs × Lite/Pro × 14 Sektionen |
| Messgerät | `MeterScreen.kt` | ja | Scan, Pinning, Live-Pegel |
| Diagnose | `DiagnoseScreen.kt` | ja | Selbstprüfung, Log, Bundles, Drive |
| Audio-Player | `AudioPlayerScreen.kt` | ja | – |
| Videobeweis | `VideoAufnahmeScreen.kt` | ja | CameraX, prüft freien Speicher (:413) |
| Drive-Uploads | `DriveUploadScreen.kt` | ja | Liste je Datei, **keine** Retry-Aktion |
| Papierkorb | `TrashScreen.kt` | ja | – |
| KI-Erklärung | `KiErklaerungScreen.kt` | ja | reiner Text |
| Onboarding | `OnboardingScreen.kt` | – | 4 Seiten, **wird per Default nie gezeigt** ([F-14](#f-14)) |

### 4.2 Bottom Sheets (4)

| Sheet | Datei | Ausgelöst durch |
|---|---|---|
| Fotodokumentation | `FotoDokumentationSheet.kt` | `LaunchedEffect` auf `offeneSessionFlow()` (`MainActivity.kt:400-408`) |
| Berichtsangaben (Stammdaten) | `GesamtberichtStammdatenSheet.kt` | `LaunchedEffect` auf `offeneSessionFlow()` (`MainActivity.kt:419-425`) |
| Lärmereignis markieren | `MarkNoiseEventBottomSheet.kt` | Cockpit-Button bei laufender Messung |
| High-End-Bericht erzeugen | `BerichtErstellenSheet.kt` | `BerichtScreen.kt:148` |

### 4.3 Dialoge (25 `AlertDialog`-Aufrufe + 1 Vollbild-`Dialog`)

Vollständige Liste mit Trigger und Bewertung: [Kapitel 12](#12-dialog-inventory).

### 4.4 Banner, Badges, Statusanzeigen

| Element | Datei | Wo |
|---|---|---|
| Problem-Banner „Überwachung eingeschränkt" | `MainActivity.kt:681-724` | Start, nur wenn `hasProblemWhileMonitoring` |
| `BluetoothStatusBadge` | `BluetoothStatusBadge.kt` | Start-TopAppBar, `MeterScreen`, `SettingsScreen` |
| `MicrophoneStatusBadge` | `MicrophoneStatusBadge.kt` | nur Start-TopAppBar |
| `StatusPill` | `ui/components/StatusPill.kt` | Aufnahmelisten, Detail, Drive-Karte |
| Session-Karte „Aktiv / Abgeschlossen" | `ProtokollScreen.kt:477-493` | Daten |
| Drive-Statuskarte | `DriveStatusCard.kt` | Einstellungen, Diagnose |
| Berechtigungs-Karten (rot) | `MeterScreen.kt:388-414`, `MeterControlCard.kt:184-208` | Messgerät, Kopplungsdialog |
| Foreground-Notification | `AudioRecordingService.kt:476-506` | System |
| Drive-Notifications (2) | `DriveSyncNotifier.kt` | System |

### 4.5 Snackbars

Es gibt **einen** globalen `SnackbarHostState` im äußeren `Scaffold` (`MainActivity.kt:173`,
`:197`). Er wird an `main`, `settings`, `protokoll/{id}`, `video`, `diagnose` und `trash`
durchgereicht. **Nicht** durchgereicht wird er an `ProtokollScreen`, `BerichtScreen`,
`MeterScreen`, `DriveUploadScreen`, `AudioPlayerScreen` und `KiErklaerungScreen` – diese Screens
können dem Nutzer also keine flüchtige Rückmeldung geben. `MeterScreen` behilft sich mit
Inline-Text (`scanFehler`), `BerichtScreen` mit dem Dialog-Zustand.

Zusätzlich existieren **12 `Toast.makeText`-Aufrufe** (`DiagnoseScreen.kt` ×8,
`SettingsScreen.kt` ×3, `LiveCockpitCard.kt:150`). Neun davon sind Fallbacks für den Fall, dass
`onShowSnackbar == null` ist; drei sind unbedingte Toasts
(`DiagnoseScreen.kt:314`, `:487`, `:714`, `SettingsScreen.kt:2328`, `LiveCockpitCard.kt:150`).
Gemischte Snackbar/Toast-Verwendung ist ein Material-Konsistenzbruch ([F-33](#f-33)).

### 4.6 Nicht verwendeter UI-Code (belegt, nicht vermutet)

| Symbol | Datei | Referenzen im Produktivcode |
|---|---|---|
| `QuickEventTagDialog` / `QuickEventTagContent` | `ui/QuickEventTagDialog.kt` | keine – nur `androidTest/.../QuickEventTagDialogInstrumentedTest.kt` |
| `leiteDashboardAnzeigeAb`, `DashboardAnzeige` | `messreihe/DashboardStatus.kt` | keine – nur `test/.../DashboardStatusTest.kt` |
| `SettingQuickRow` (private) | `LiveCockpitCard.kt:753` | keine Aufrufstelle in derselben Datei |
| `StatCard` (private) | `LiveCockpitCard.kt:815` | keine Aufrufstelle in derselben Datei |
| Parameter `onNavigateToMeter`, `onNavigateToDiagnose` | `LiveCockpitCard.kt:98-99` | werden im Rumpf nie aufgerufen |
| `autoEventDetection`, `audioSnippetEnabled` | `LiveCockpitCard.kt:139-140` | werden gesetzt, aber nie gelesen |

Das ist für den Audit relevant, weil `leiteDashboardAnzeigeAb` **genau die Logik enthält, die
[F-06](#f-06) braucht** – inklusive vorhandenem Unit-Test.

---

## 5. Standard-Use-Case: As-is Interaction Trace

> **Hypothese des Auftrags:** App öffnen → Bluetooth verbinden → Kalibrierfoto → Messaufbau-Foto →
> Berichtsparameter prüfen → korrigieren → Messung starten → beenden → High-End-Bericht.
>
> **Aus dem Code rekonstruierter tatsächlicher Ablauf:** Die Reihenfolge ist eine andere, und
> zwei Schritte der Hypothese existieren so nicht. Die Abweichungen sind selbst ein Befund und
> in [Kapitel 10.3](#103-delta) einzeln aufgeführt.

### 5.1 Vorbedingungen des Traces

PCE-323 bereits gepinnt (`meterDeviceAddress != null`) · `RECORD_AUDIO`, `POST_NOTIFICATIONS`,
`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` erteilt · `fotoDokuAktiv = true` (vom Nutzer eingeschaltet;
Default ist `false`) · `stammdatenAbfrageAktiv = true` (**Default**) · `audioTriggerQuelle = "AUTO"`
(Default) · die vorige Messung wurde über „Messung beenden" beendet, damit ist
`audioMonitoringWasActive = false` (`AudioRecordingService.kt:291`).

### 5.2 Trace

| Schritt | Was passiert | Typ | Beleg |
|---|---|---|---|
| — | App öffnen, Screen `main` | Screen | `MainActivity.kt:211` |
| — | Badge zeigt „PCE-323: Nicht verbunden". **Es läuft kein Verbindungsversuch**, weil `ConnectionSupervisor.start()` nur aus `ensureMeterMonitoringStarted()` gerufen wird und das nur aus `onStartCommand` | Zustand | `AudioRecordingService.kt:211`, `MainActivity.onCreate` startet keinen Service |
| **T1** | „Messung starten" | Tap | `LiveCockpitCard.kt:632` |
| — | Dienst wird Foreground, Mikrofon startet, **Mikrofon-Session wird sofort eröffnet** | Wartezustand | `AudioRecordingService.kt:346`, `:367` |
| **S1** | Sheet „Berichtsangaben für diese Messung" (13 Textfelder, mit den letzten Werten vorbefüllt) | Sheet | `MainActivity.kt:419-425` |
| **E1** | 0–13 Texteingaben | Eingabe | `GesamtberichtStammdatenSheet.kt:88-100` |
| **T2** | „Speichern" (legt **immer** eine neue Verlaufszeile an) | Tap | `GesamtberichtStammdatenSheet.kt:192` |
| **S2** | Sheet „Fotodokumentation" | Sheet | `MainActivity.kt:400-406` |
| **T3** | „Messaufbau (0/3)" | Tap | `FotoDokumentationSheet.kt:196` |
| **M1/M2** | System-Kamera: Auslöser + „Verwenden" | Medienaktionen | `ActivityResultContracts.TakePicture` |
| **T4** | „Kalibrierung (0/3)" | Tap | `FotoDokumentationSheet.kt:196` |
| **M3/M4** | System-Kamera: Auslöser + „Verwenden" | Medienaktionen | dito |
| **T5** | „Fertig" | Tap | `FotoDokumentationSheet.kt:212` |
| — | PCE verbindet; **beim ersten Frame** wird die Mikrofon-Session geschlossen und eine **neue** Messgerät-Session angelegt | Wartezustand | `MeasurementRecorder.kt:296-316` |
| **S3** | **Sheet „Berichtsangaben" erscheint ein zweites Mal** – die neue Session-ID ist ≠ `zuletztGefragteStammdatenSession` | Sheet | `MainActivity.kt:419-425` ([F-16](#f-16)) |
| **T6** | „Überspringen" | Tap | – |
| **S4** | **Sheet „Fotodokumentation" erscheint ein zweites Mal** – `dokumentationsFotoDao().fuerSession(neueId)` ist leer | Sheet | `MainActivity.kt:400-406` ([F-16](#f-16)) |
| **T7** | „Ohne Foto fortfahren" | Tap | `FotoDokumentationSheet.kt:212` |
| **D1** | Rückfrage „Wirklich ohne Foto fortfahren?" | Dialog | `FotoDokumentationSheet.kt:224-252` |
| **T8** | „Ohne Foto fortfahren" | Tap | – |
| — | Messung läuft (Live-Pegel, LAeq/LAmax, Chart, Ausfallbänder) | – | `LiveCockpitCard.kt:404-537` |
| **T9** | „Messung beenden" | Tap | `LiveCockpitCard.kt:601` |
| **D2** | „Messung wirklich beenden?" | Dialog | `LiveCockpitCard.kt:645-670` |
| **T10** | „Messung beenden" | Tap | – |
| **T11** | BottomBar „Bericht" | Tap | `MainActivity.kt:352-358` |
| — | Screenwechsel `main` → `bericht` | Navigation | – |
| **T12** | „High-End-Bericht jetzt erzeugen" | Tap | `BerichtScreen.kt:148` |
| **S5** | Sheet „High-End-Bericht erzeugen" | Sheet | `BerichtErstellenSheet.kt:145` |
| **T13** | „Datumsbereich wählen" | Tap | `BerichtErstellenSheet.kt:155` |
| **D3** | Vollbild-`DateRangePicker` | Dialog | `BerichtErstellenSheet.kt:256-281` |
| **T14/T15** | Start- und Enddatum antippen | Taps | – |
| **T16** | „Übernehmen" | Tap | `BerichtErstellenSheet.kt:282` |
| — | Tage werden geladen (`CircularProgressIndicator`) | Wartezustand | `BerichtErstellenSheet.kt:97-108` |
| **T17** | „Bericht jetzt erzeugen" | Tap | `BerichtErstellenSheet.kt:241` |
| — | **Erst hier** greifen die Vorprüfungen (Retention, Bewertung, Stammdaten-Auswahl, Gebietseinstufung) | Fehlerzustand | `BerichtErstellenSheet.kt:109-124` |
| — | Python-Lauf, Button-Text wechselt auf „Erzeuge …"; **keine Fortschrittsanzeige** | Wartezustand | `BerichtErstellenSheet.kt:241` |
| **T18** | „PDF teilen" | Tap | `BerichtErstellenSheet.kt:235` |
| **D4** | System-Share-Sheet | Dialog | `report/BerichtDatei.kt` |
| **T19** | Zielapp wählen | Tap | – |

### 5.3 Der Fehlerpfad, der praktisch immer beim ersten Mal auftritt

`ReportConfigEntity.gebietseinstufung` ist per Default `""` (`data/ReportConfigEntity.kt:48`).
`areaSelectionError("")` liefert „Bitte in den Berichtsparametern eine Gebietseinstufung
auswählen." (`report/ReportArea.kt:26-33`). Das erfährt der Nutzer **erst nach T17**. Der Weg zur
Behebung:

T20 Sheet schließen → T21 Drei-Punkt-Menü im Bericht-Tab → T22 „Einstellungen" → T23 Tab
„Bericht" → T24 Sektion „Berichtsparameter (§ 287 ZPO)" aufklappen → T25 Gebiet wählen
(`SettingsScreen.kt:1789`) → T26 Zurück → T27 „High-End-Bericht jetzt erzeugen" → **T28–T31
Datumsbereich komplett neu wählen** (`zeitraum` ist reiner `remember`-State,
`BerichtErstellenSheet.kt:84`) → T32 „Bericht jetzt erzeugen".

**+13 Taps**, ein zweiter Durchlauf des kompletten Datumsdialogs.

### 5.4 Kennzahlen (As-is)

| Metrik | Happy Path (mit Fotos, ohne Fehlerpfad) | Ohne Fotodokumentation | Mit Gebiets-Fehlerpfad |
|---|---|---|---|
| Taps | **19** | 15 | 32 |
| Screenwechsel (in der App) | 2 | 2 | 4 |
| App-Wechsel (Kamera/Share) | 3 | 1 | 3 |
| Dialoge | 4 | 3 | 5 |
| Bottom Sheets | 5 | 3 | 6 |
| Texteingaben | 0–13 | 0–13 | 0–13 |
| Bestätigungen | 3 | 2 | 3 |
| Medienaktionen | 4 | 0 | 4 |
| Wartezustände ohne Fortschrittsanzeige | 3 | 3 | 4 |

### 5.5 Variante „erst Bluetooth, dann messen" (die Reihenfolge der Auftragshypothese)

Wer zuerst die Verbindung herstellen will, hat zwei Wege:

**Weg A – Badge auf der Startseite (2 Taps):** Tap Badge (`MainActivity.kt:583-588`) → öffnet
`MeterPairingDialog`, der **sofort einen 10-Sekunden-Scan startet** (`MeterControlCard.kt:142-148`,
`SCAN_DURATION_MS = 10_000L`) → Tap auf die Gerätekarte → `settings.meterDeviceAddress` wird
**erneut geschrieben**, der Dienst gestartet (`MainActivity.kt:1276-1288`).

**Weg B – Messgerät-Screen (5 Taps):** Overflow-Menü → „Einstellungen" → Sektion „Aufnahme &
Mikrofon" aufklappen → „Messgerät öffnen" (`SettingsScreen.kt:764`) → „Verbinden"
(`MeterScreen.kt:371`).

Beide Wege starten denselben Foreground Service. Damit gilt danach `AudioRecordingService.laeuft
== true`, das Cockpit zeigt **„Messung läuft"** samt „Messung beenden"-Button – obwohl weder
Mikrofon noch WAV laufen (`sollAudioMonitoringStarten(false, false) == false`,
`audio/AudioStartPolicy.kt:16`). Den Startknopf gibt es in diesem Zustand nicht mehr; um doch
aufzuzeichnen, muss der Nutzer den Mikrofon-Badge antippen
(`MicrophoneStatusBadge.kt:104-106`). Siehe [F-02](#f-02).

---

## 6. Use-Case A – Nur High-End-Bericht aus vorhandenen Daten

### 6.1 Trace

| Schritt | Aktion | Typ | Beleg |
|---|---|---|---|
| T1 | BottomBar „Bericht" | Tap | `MainActivity.kt:352-358` |
| T2 | „High-End-Bericht jetzt erzeugen" | Tap | `BerichtScreen.kt:148` |
| S1 | Sheet öffnet; **keine** Angabe, ob die Voraussetzungen erfüllt sind | Sheet | `BerichtErstellenSheet.kt:145-249` |
| T3 | „Datumsbereich wählen" | Tap | `:155` |
| D1 | Vollbild-`DateRangePicker` | Dialog | `:256-281` |
| T4/T5 | Start-/Enddatum | Taps | – |
| T6 | „Übernehmen" | Tap | `:282` |
| — | Tage laden | Wartezustand | `:97-108` |
| T7…(2×n) | **Je Tag mit mehr als einem Stammdaten-Kandidaten**: Dropdown öffnen + Eintrag wählen | 2 Taps je Tag | `:177-196`, `report/BerichtErstellung.kt:79-87` |
| Tn+1 | „Bericht jetzt erzeugen" | Tap | `:241` |
| — | Vorprüfung, ggf. Fehlermeldung | Fehlerzustand | `:109-124` |
| Tn+2 | „PDF teilen" | Tap | `:235` |
| Tn+3 | Zielapp im Share-Sheet | Tap | – |

### 6.2 Kennzahlen

| Metrik | 1 Messtag, eindeutige Stammdaten | 30 Tage, je 2 Stammdaten-Zeilen |
|---|---|---|
| Taps | **9** | **69** |
| Dialoge | 2 | 2 |
| Sheets | 1 | 1 |
| Screenwechsel | 1 | 1 |

### 6.3 Befunde in diesem Flow

* Die Pflicht-Auswahl je Tag (`auswahlFehler`, `report/BerichtErstellung.kt:79-87`) greift, sobald
  ein Tag **mehr als eine** `stammdaten_verlauf`-Zeile hat. Da jedes „Speichern" im
  Stammdaten-Sheet eine neue Zeile anlegt (`GesamtberichtStammdatenSheet.kt:210`) und das Sheet
  pro Messvorgang bis zu zweimal erscheint ([F-16](#f-16)), entsteht dieser Zustand im
  Normalbetrieb – nicht als Ausnahme. → [F-15](#f-15)
* `retentionFehler` blockt Tage, deren Rohdaten bereits verdichtet sind
  (`report/BerichtErstellung.kt:57-64`). Das ist fachlich richtig, aber der Nutzer erfährt es
  erst nach dem Klick, obwohl `tag.verdichteteMinuten` bereits **vor** dem Klick im Sheet steht
  (`BerichtErstellenSheet.kt:170-171`). → [F-07](#f-07)
* Der Zeitraum ist reiner `remember`-State (`BerichtErstellenSheet.kt:84`). Schließt sich das
  Sheet (auch unbeabsichtigt durch Wischen), ist die Auswahl weg.

---

## 7. Use-Case B – KI-Klassifizierung nachträglich

Es gibt **fünf** Einstiege mit **drei verschiedenen** Verhaltensweisen bei „nichts erkannt" und
**drei verschiedenen** Ladezuständen.

| # | Einstieg | Kandidaten | Ladezustand | Verhalten bei `null` | Beleg |
|---|---|---|---|---|---|
| 1 | Start → Overflow → „Alle klassifizieren" | alle mit `detectedLabel == null` | **keiner** | Aufnahme bleibt unverändert | `MainActivity.kt:610-624` |
| 2 | Start → Tagesgruppe → Refresh-Icon | `unklassifizierteAufnahmen(tag)` | Spinner im Icon | Aufnahme bleibt unverändert | `MainActivity.kt:1046-1082` |
| 3 | Start → Aufnahme-Karte → Refresh-Icon | genau diese Aufnahme, **auch wenn schon klassifiziert** | **keiner** | schreibt `status_not_recognized` als `detectedLabel` | `MainActivity.kt:1146-1151`, Icon `:1404-1411` |
| 4 | Protokoll-Detail → Ereignis → Refresh-Icon | nur wenn `label == null && detectedLabel == null` | Spinner | **stillschweigend nichts** | `ProtokollDetailScreen.kt:429-462` |
| 5 | Start → Overflow → „Neu bewerten" | alle mit gespeicherten Rohdaten | **keiner** | Snackbar „… neu bewertet" / „nichts zu bewerten" | `MainActivity.kt:626-650`, `audio/NeuBewerten.kt` |

### 7.1 Kennzahlen

| Metrik | Einstieg 1 (alle) | Einstieg 4 (ein Ereignis) |
|---|---|---|
| Taps | 2 | 3 (Daten → Session → Icon) + Scrollen |
| Screenwechsel | 0 | 2 |
| Ladezustand sichtbar | nein | ja |

### 7.2 Befunde

* Einstieg 1 und 5 können mehrere Minuten laufen (YAMNet-Inferenz je Datei) und zeigen bis zur
  Abschluss-Snackbar **gar nichts** an. Der Menüeintrag lässt sich beliebig oft erneut auslösen.
  → [F-26](#f-26)
* Einstieg 3 ist an jeder Aufnahme mit Audio sichtbar, auch an bereits klassifizierten, und
  überschreibt ein vorhandenes `detectedLabel` – ohne Rückfrage.
* „Noch nicht klassifiziert", „nichts erkannt" und „KI ist aus" (`aiMode == "OFF"`) sind in der
  Liste nicht unterscheidbar: `NoiseRecordItem` zeigt die KI-Zeile nur, wenn
  `record.detectedLabel != null` (`MainActivity.kt:1375-1382`). → [F-17](#f-17)

---

## 8. Use-Case C – Google-Drive-Synchronisation

### 8.1 Wo der Status überhaupt steht

`DriveStatusCard` wird an genau zwei Stellen eingebunden: `SettingsScreen.kt:1305` (Tab „Daten",
in der eingeklappten Sektion „Google Drive Synchronisation") und `DiagnoseScreen.kt:570`. Auf
Start, Daten, Bericht und im Protokoll-Detail gibt es **keinerlei** Sync-Anzeige.

| Schritt | Aktion | Typ |
|---|---|---|
| T1 | Start → Drei-Punkt-Menü | Tap |
| T2 | „Einstellungen" | Tap |
| T3 | Segmented Button „Daten" | Tap |
| T4 | Sektion „Google Drive Synchronisation" aufklappen | Tap |
| — | Status lesen: Pill (`Aktiv`/`Pausiert`/`Gestört`/`Nicht verbunden`), Konto, Ordner, „Letzter erfolgreicher Sync", `driveSyncLastMessage`, „Letzte Datenbank-Sicherung" | – |
| T5 | „Jetzt synchronisieren" | Tap |
| T6 | (optional) „Upload-Übersicht öffnen" → Liste je Datei | Tap |

**5 Taps, um zu erfahren, ob die Beweisdaten in der Cloud sind.**

### 8.2 Zustände und ihre Sichtbarkeit

| Zustand | Quelle | Sichtbar in der App? |
|---|---|---|
| Sync aus | `driveSyncEnabled` | ja, Pill „Pausiert"/Text |
| Erfolgreich | `driveSyncLastSuccessAt` | ja, Datum |
| Läuft gerade | `isSyncing` (nur lokaler `remember`-State der Karte) | nur während man auf der Karte steht |
| Ausstehend | `UploadZustand.OFFEN` in `DriveUploadScreen` | nur dort |
| Fehlgeschlagen | `driveSyncFehlschlaegeInFolge`, `driveSyncLastMessage`, `UploadZustand.FEHLGESCHLAGEN` | Pill „Gestört" + Text; Notification erst ab 6 Zyklen (`DriveSyncNotifier.kt:15`) |
| Ordner weg | `driveOrdnerBlockiert` | Pill „Gestört" + eigene Notification |
| Offline | – | **nirgends** als eigener Zustand; erscheint als Fehlschlag |

### 8.3 Befunde

* **Kein Retry je Datei.** `DriveUploadScreen` zeigt „✕ fehlgeschlagen" ohne jede Aktion
  (`DriveUploadScreen.kt:182-194`). Die einzige Wiederholung ist der globale Knopf in der
  Statuskarte. → [F-13](#f-13)
* **Unnötige Sync-Aktionen** konnte ich keine finden: Der 30-Minuten-Takt läuft über WorkManager
  mit Netz-Constraint, Fotos/Videos werden zusätzlich sofort angestoßen
  (`FotoDokumentationSheet.kt:113`). Das ist begründet (Beweismaterial).
* **Keine unnötigen Popups**: Drive meldet sich nur über einen eigenen, ruhigen Kanal und erst
  nach 6 Fehlzyklen – das ist ausdrücklich so geplant (Plan 8.4.6, im KDoc zitiert) und soll so
  bleiben.

---

## 9. Use-Case D – Messdaten auf Vollständigkeit prüfen

### 9.1 Trace

| Schritt | Aktion | Was der Nutzer sieht |
|---|---|---|
| T1 | BottomBar „Daten" | Session-Karten: Datum, Zeitraum, Badge **„Aktiv"/„Abgeschlossen"**, Dauer, LAeq, LAmax, Ereignisse, Gerätename |
| T2 | „Details >" | Kennzahlen, Chart **mit Ausfallbändern**, Ereignisliste, Fotos, Videos |
| T3 | „Audit-Details" aufklappen | Geräte-ID, Messparameter, **„Messpunkte erfasst: N"**, **„Verbindungsausfälle: N Vorfälle"**, L10/L50/L90, App-Version |
| — | ganz nach unten scrollen | Liste der Ausfallbänder mit Zeitraum und Dauer |

3 Taps + Scrollen.

### 9.2 Was vorhanden ist, aber nie in der UI erscheint

| Datum/Kennzahl | Wo es existiert | UI-Verwendung |
|---|---|---|
| Datenverfügbarkeit in % | `messreihe/Datenverfuegbarkeit.kt:14` | **nur** `report/GesamtberichtDaten.kt:125` (PDF) – kein Compose-Aufrufer |
| `MeasurementFlags.GAP` / `GAP_REASON_SENSOR_ERROR` | `data/SessionEntity.kt:113-134`, gesetzt in `MeasurementRecorder` | **kein** UI-Verwender (Suche über `app/src/main/.../ui` leer) |
| `anzahlUnbestaetigtZwischen` (unbestätigte A-/Zeitbewertung) | `data/SessionDao.kt:91` | nur `report/BerichtErstellung.kt:186` |
| `SessionEntity.rohdatenPruefsumme` | `data/SessionEntity.kt:50` | **kein** UI-Verwender |
| `leiteDashboardAnzeigeAb` (Betriebsart/Laufzeit/Pegel) | `messreihe/DashboardStatus.kt:24` | **kein** Aufrufer |

### 9.3 Befund

Die Frage „Ist diese Messung für einen Bericht geeignet?" wird nirgends beantwortet. Das Badge
sagt nur, ob die Session ein `endedAt` hat (`ProtokollScreen.kt:477`). Alle Bausteine für eine
echte Antwort – Ausfalldauer, Datenverfügbarkeit, Lücken-Flags, unbestätigte Bewertung, bereits
verdichtete Tage – sind vorhanden und getestet. → [F-12](#f-12)

---

## 10. Interaction-Cost-Bilanz und Standard-Workflow-Zielbild

### 10.1 As-is (Zusammenfassung aus Kapitel 5)

```
App öffnen
  └─ Badge: "Nicht verbunden" (kein Verbindungsversuch)
T1  "Messung starten"                    ── startet Mikrofon + BLE gleichzeitig
S1  Sheet Berichtsangaben (13 Felder)    ── Mikrofon-Session
T2  "Speichern"                          ── legt neue Verlaufszeile an
S2  Sheet Fotodokumentation
T3  Messaufbau → Kamera (M1,M2)
T4  Kalibrierung → Kamera (M3,M4)
T5  "Fertig"
    ...PCE verbindet...                  ── Mikrofon-Session ENDET, neue Session
S3  Sheet Berichtsangaben ERNEUT
T6  "Überspringen"
S4  Sheet Fotodokumentation ERNEUT
T7  "Ohne Foto fortfahren" → D1 → T8
    ...Messung...
T9  "Messung beenden" → D2 → T10
T11 BottomBar "Bericht"
T12 "High-End-Bericht jetzt erzeugen"
T13 "Datumsbereich wählen" → D3 → T14,T15 → T16 "Übernehmen"
T17 "Bericht jetzt erzeugen"             ── ERST HIER Vorprüfung
T18 "PDF teilen" → D4 → T19
```

### 10.2 Optimized (Zielablauf)

```
App öffnen
  └─ Auto-Reconnect an das gepinnte Gerät, OHNE Aufzeichnung  (0 Taps)
  └─ Messbereitschaftszeile: Gerät ✓ · Mikrofon ✓ · Speicher ✓ ·
     Berichtsangaben ✓ (heute) · Fotos ✓ (heute)              (0 Taps)
T1  "Messung starten"
  └─ Sheets nur, wenn für den heutigen Kontext noch nichts vorliegt,
     und dann genau EINMAL je Messvorgang (nicht je Session-Zeile)
    ...Messung...
T2  "Messung beenden" → D1 Bestätigung → T3
  └─ Abschlusskarte: "Messung 09:12–17:40 · Datenverfügbarkeit 99,4 % ·
     0 Ausfälle · bereit für Bericht"  +  Aktion "Bericht erstellen"
T4  "Bericht erstellen"                  ── Zeitraum bereits vorbelegt
  └─ Sheet zeigt LIVE: Zeitraum ✓ · Rohdaten ✓ · Gebietseinstufung ✗ (Tippen zum Setzen)
T5  "Bericht jetzt erzeugen"             ── Button ist erst hier aktiv
T6  "PDF teilen" → D2 → T7
```

### 10.3 Delta

| Bereich | Heute | Zielbild | Wirkung |
|---|---|---|---|
| Bluetooth | Erst bei „Messung starten" oder über Kopplungsdialog | Auto-Reconnect an gepinntes Gerät beim App-Start, ohne Aufzeichnung | entfällt als Schritt; [F-02](#f-02) |
| Messbereitschaft | nirgends | eine Zeile über dem Startknopf | Fehlerprävention statt Fehlermeldung |
| Berichtsangaben | Sheet bei **jeder** Session-Eröffnung, bis zu 2× je Messvorgang | 1× je Messvorgang, und nur wenn für heute/diesen Messort nichts vorliegt | −1 bis −2 Sheets; [F-15](#f-15), [F-16](#f-16) |
| Fotodokumentation | Sheet je Session; erneut, wenn die Quelle wechselt | 1× je Messvorgang, Regel „einmal je Kalendertag **und** Messort" | −1 Sheet, −1 Dialog; [F-16](#f-16) |
| Bericht-Voraussetzungen | Fehlermeldung nach dem Klick | Live-Checkliste im Sheet, Button gesperrt mit Begründung | −13 Taps im Fehlerfall; [F-07](#f-07), [F-08](#f-08) |
| Berichtszeitraum | immer manuell im Vollbild-Picker | aus der beendeten Messung vorbelegt, Picker nur zum Ändern | −4 Taps, −1 Dialog |
| Messintegrität | erst im Audit-Block der Detailansicht | in der Abschlusskarte und auf der Session-Karte | Transparenz; [F-12](#f-12) |

### 10.4 Kennzahlenvergleich (Standard-Workflow, Returning User)

| Kennzahl | Heute | Zielbild | Δ |
|---|---|---|---|
| Taps | 19 | **7** | −12 |
| Taps im Gebiets-Fehlerfall | 32 | 8 | −24 |
| Bottom Sheets | 5 | 1 | −4 |
| Dialoge | 4 | 2 | −2 |
| Medienaktionen | 4 | 0 (im Regelfall) | −4 |
| Texteingaben | 0–13 | 0 (im Regelfall) | −13 |
| Screenwechsel | 2 | 0 | −2 |

> Die Zahlen des Zielbilds setzen voraus, dass der Nutzer am selben Tag am selben Messort schon
> einmal gemessen hat – genau der Fall, für den die Optimierung gedacht ist. Beim allerersten
> Mal bleiben Sheets und Eingaben erhalten (siehe [Kapitel 11](#11-first-time-user-vs-returning-user)).

---

## 11. First-Time User vs. Returning User

### 11.1 First-Time User

| Erwartung | Realität heute | Beleg |
|---|---|---|
| Einführung beim ersten Start | **Kommt nicht.** `onboardingCompleted` hat den Default `true`, die Bedingung `if (!onboardingDone …)` ist bei einer Neuinstallation also nie erfüllt | `SettingsManager.kt:606`, `MainActivity.kt:160-170` |
| Berechtigungen werden erklärt | Keine Vorab-Erklärung. `RECORD_AUDIO` + `POST_NOTIFICATIONS` werden erst beim Druck auf „Messung starten" angefragt | `LiveCockpitCard.kt:612-626` |
| Gerät koppeln wird angeboten | Nur über den Badge in der TopAppBar (ohne Beschriftung „koppeln") oder 4 Ebenen tief in den Einstellungen | `MainActivity.kt:583-588`, `SettingsScreen.kt:764` |
| Leerzustand hilft weiter | Ja: „Noch keine Aufnahmen" mit Erklärtext auf Start und Daten | `MainActivity.kt:948-969`, `ProtokollScreen.kt:295-311` |
| Erste Messung führt zum Ziel | Teilweise: die beiden Sheets erklären sich selbst, aber die Gebietseinstufung – ohne die **kein** High-End-Bericht möglich ist – wird nirgends erwähnt, bis der Bericht scheitert | `ReportArea.kt:26-33` |

Fazit: Für den First-Time User ist die App **zu still**. Es gibt einen fertigen, getesteten
Onboarding-Screen mit vier Seiten, der faktisch toter Code ist. → [F-14](#f-14)

### 11.2 Returning User

| Erwartung | Realität heute | Beleg |
|---|---|---|
| Sofort messbereit | Nein: Verbindung wird nicht aufgebaut, Status steht auf „Nicht verbunden" | [F-02](#f-02) |
| Keine Wiederholungsfragen | Nein: Stammdaten-Sheet bei jeder Session, Foto-Sheet bei jeder Session, beide bis zu 2× je Messvorgang | [F-16](#f-16) |
| Bekannte Werte sind vorbelegt | Ja, gut gelöst: der letzte Stammdatensatz wird geladen, dazu ein Verlauf der letzten 10 | `GesamtberichtStammdatenSheet.kt:123-127` |
| Wiederkehrende Berichte schnell | Nein: Zeitraum jedes Mal per Vollbild-Picker, keine Presets wie im alten Zeitraumdialog | `BerichtErstellenSheet.kt:155`, vgl. `BerichtScreen.kt:190-208` |
| Filter merkt sich den Stand | Auf Start ja (in `SettingsManager` persistiert), im Protokoll nein (nur `remember`) | `MainActivity.kt:451-478`, `ProtokollScreen.kt:75` |

### 11.3 Hinweise, die beim zehnten Mal stören

| Element | Beim ersten Mal | Beim zehnten Mal | Empfehlung |
|---|---|---|---|
| Sheet „Berichtsangaben" | hilfreich, führt zur vollständigen Dokumentation | 13 unveränderte Felder, dann „Speichern" | nur bei fehlendem/veraltetem Kontext zeigen, sonst als Zeile „Berichtsangaben: Balkon SO, 1,4 m · ändern" |
| Sheet „Fotodokumentation" | hilfreich | erzeugt Foto Nr. 11 vom selben Aufbau | Regel „einmal je Kalendertag und Messort", plus „Erneut fotografieren"-Aktion |
| Dialog „Wirklich ohne Foto fortfahren?" | sinnvoll | reine Zusatzbestätigung, weil man nie fotografieren wollte | entfällt, wenn das Sheet gar nicht mehr erscheint |
| Dialog „Messung wirklich beenden?" | sinnvoll | **weiterhin sinnvoll** – beendet BLE, WAV und Session | beibehalten |
| Snackbar „Gerät gekoppelt" nach jeder Verbindung | ok | überflüssig, weil es keine Kopplung ist, sondern ein Reconnect | Text abhängig davon, ob das Gerät neu ist |

---

## 12. Dialog Inventory

Vollständig: 25 `AlertDialog`-Aufrufe, 1 Vollbild-`Dialog`, 4 `ModalBottomSheet` im
Produktivcode. „Mehrfach je Tag?" meint: kann derselbe Dialog innerhalb eines typischen
Messtags mehr als einmal erscheinen, ohne neue Information zu tragen.

| # | Dialog / Sheet | Datei:Zeile | Trigger | Häufigkeit | Entscheidung nötig? | Mehrfach je Tag? | Empfehlung |
|---|---|---|---|---|---|---|---|
| 1 | Berichtsangaben (Sheet) | `GesamtberichtStammdatenSheet.kt:218` | jede neu eröffnete Session, wenn `stammdatenAbfrageAktiv` | sehr hoch | selten (Werte ändern sich kaum) | **ja, bis 2× je Messvorgang** | nur bei fehlendem/veraltetem Kontext; sonst Inline-Zeile mit „ändern" → [F-15](#f-15), [F-16](#f-16) |
| 2 | Fotodokumentation (Sheet) | `FotoDokumentationSheet.kt:142` | jede neu eröffnete Session, wenn `fotoDokuAktiv` und noch kein Foto für **diese Session** | hoch | ja beim ersten Mal | **ja, bis 2× je Messvorgang** | Regel „einmal je Kalendertag und Messort" → [F-16](#f-16) |
| 3 | „Wirklich ohne Foto fortfahren?" | `FotoDokumentationSheet.kt:224` | Sheet wegwischen statt Button | hoch | nein (Sheet blockiert nichts) | ja | entfällt mit #2; solange #2 bleibt: nur beim ersten Überspringen je Tag |
| 4 | „Messung wirklich beenden?" | `LiveCockpitCard.kt:646` | „Messung beenden" oder Notification-Aktion | 1× je Messung | **ja** – beendet BLE, WAV, Session | nein | **beibehalten** |
| 5 | „WAV-Aufzeichnung deaktivieren?" | `LiveCockpitCard.kt:674` | Trigger-Menü → „WAV-Aufnahme: Aktiv" | niedrig | ja (DSGVO-relevant) | selten | beibehalten |
| 6 | „Bluetooth-Verbindung beenden?" | `LiveCockpitCard.kt:699` | Trigger-Menü → „Nur Mikrofon" bei aktiver Verbindung | niedrig | ja | selten | beibehalten |
| 7 | „WAV-Aufzeichnung beenden?" | `MicrophoneStatusBadge.kt:124` | Tap auf den Mikrofon-Badge bei laufender Aufnahme | mittel | ja | möglich | beibehalten, aber Badge sollte nicht primär Schalter sein → [F-22](#f-22) |
| 8 | PCE-323 koppeln (Scan-Dialog) | `MeterControlCard.kt:159` | Tap auf den Bluetooth-Badge – **immer**, auch bei gepinntem Gerät | hoch | nein, wenn nur ein Reconnect gewollt ist | ja | bei gepinntem Gerät zuerst Reconnect anbieten → [F-02](#f-02), [F-03](#f-03) |
| 9 | „Neues Gerät mit bekanntem Namen?" | `MeterControlCard.kt:291` | Scan findet gleichen Namen, andere MAC | sehr selten | **ja** (Sicherheit) | nein | beibehalten |
| 10 | „Mögliches Ersatzgerät gefunden" | `MeterScreen.kt:195` | wie #9, aber im Messgerät-Screen | sehr selten | ja | nein | mit #9 zusammenführen (zwei Texte für denselben Fall) |
| 11 | „Bluetooth-Verbindung beenden?" (Entkoppeln) | `MeterScreen.kt:223` | „Entkoppeln" | selten | ja (löscht das Pinning) | nein | beibehalten |
| 12 | Geräusch lernen | `MainActivity.kt:1162` | AssistChip „Muster lernen" | niedrig | ja (Name eingeben) | möglich | beibehalten |
| 13 | Referenzgeräusch löschen | `MainActivity.kt:1211` | X am Referenz-Chip | niedrig | ja | nein | Snackbar mit „Rückgängig" statt Dialog (wie beim Aufnahme-Löschen, `MainActivity.kt:1130-1139`) |
| 14 | Tagesbericht (ZIP / nur Text) | `MainActivity.kt:1243` | Overflow „Tagesbericht" oder Diagramm-Icon je Tag | mittel | ja (zwei Ausgabeformate) | ja | beibehalten; Formatwahl merken |
| 15 | Zeitraum-/Gesamtbericht | `BerichtScreen.kt:157` | „Zeitraumbericht erstellen" | mittel | ja | möglich | beibehalten; Umschalter „Gesamtbericht" ist versteckt |
| 16 | Datumsbereich (Vollbild) | `BerichtErstellenSheet.kt:256` | „Datumsbereich wählen" | mittel | ja | ja | Presets ergänzen und Zeitraum vorbelegen |
| 17 | Aufräum-Vorschau | `SettingsScreen.kt:1256` | Auto-Bereinigung einschalten | selten | **ja** (Datenverlust) | nein | beibehalten (vorbildlich: zeigt Anzahl und Bytes vorher) |
| 18 | Wiederherstellung von Drive | `SettingsScreen.kt:1480` | „Von Drive wiederherstellen" | sehr selten | ja | nein | beibehalten |
| 19 | Lokale Wiederherstellung | `SettingsScreen.kt:1562` | Datei über SAF gewählt | sehr selten | ja | nein | beibehalten |
| 20 | „Speicher freigeben?" | `SettingsScreen.kt:2036` | „Endgültig löschen" | selten | ja | nein | beibehalten |
| 21 | Ruhezeit-Presets | `RuhezeitPresetsDialog.kt:124` | Einstellungen → „Grenzwerte nach Wohnraum" | selten | ja | nein | beibehalten |
| 22 | Papierkorb: endgültig löschen | `TrashScreen.kt:156` | Mülleimer-Icon im Papierkorb | selten | ja | nein | beibehalten |
| 23 | Galerie-Kategorie wählen | `ProtokollDetailScreen.kt:715` | „Aus Galerie" im Detail | selten | ja | nein | beibehalten |
| 24 | Drive-Ordnername (Fallback) | `DriveStatusCard.kt:89` | „Ordner wählen", wenn keine Picker-Callbacks übergeben sind | selten | ja | nein | im Produktivcode nicht erreichbar (beide Aufrufer übergeben die Callbacks) – als Fallback ok |
| 25 | Drive-Ordner auswählen | `DriveFolderPickerDialog.kt:201` | „Ordner wählen" | selten | ja | nein | beibehalten |
| 26 | Neuen Ordner erstellen | `DriveFolderPickerDialog.kt:74` | im Ordner-Dialog | selten | ja | nein | beibehalten |
| 27 | Ordner umbenennen | `DriveFolderPickerDialog.kt:139` | im Ordner-Dialog | selten | ja | nein | beibehalten |
| 28 | Lärmereignis markieren (Sheet) | `MarkNoiseEventBottomSheet.kt:73` | Cockpit-Button bei laufender Messung | hoch (gewollt) | ja | ja (gewollt) | beibehalten – das ist die Kernaktion während der Messung |
| 29 | High-End-Bericht erzeugen (Sheet) | `BerichtErstellenSheet.kt:145` | „High-End-Bericht jetzt erzeugen" | mittel | ja | möglich | Vorprüfung live statt nach dem Klick → [F-07](#f-07) |
| 30 | Quick-Event-Tag | `QuickEventTagDialog.kt:44` | **kein Trigger im Produktivcode** | – | – | – | entfernen oder verdrahten → [F-25](#f-25) |

**Ergebnis:** Genau drei Dialoge/Sheets unterbrechen den wiederkehrenden Nutzer ohne neuen
Informationsgehalt: **#1, #2 und #3**. Alle übrigen sind entweder selten, gewollt oder tragen
eine echte Entscheidung.

---

## 13. Fotodokumentation im Detail

### 13.1 Wie es heute funktioniert

| Frage | Antwort aus dem Code | Beleg |
|---|---|---|
| Wann erscheint das Sheet? | Wenn `offeneSessionFlow()` eine Session-ID liefert, die ≠ `zuletztGefragteSession` ist, `fotoDokuAktiv == true` **und** für diese Session-ID noch kein Foto existiert | `MainActivity.kt:400-406` |
| Vor oder nach dem Messstart? | **Immer nach.** Ausdrückliche, begründete Entscheidung im KDoc: „Die Reihenfolge ist nicht verhandelbar: erst messen, dann fotografieren." | `FotoDokumentationSheet.kt:47-53` |
| Ist es Pflicht? | Nein. „PFLICHT" (UI-Label „Empfohlen") hebt die Kategorie hervor und protokolliert eine Auslassung, blockiert aber nie | `SettingsManager.kt:372-379`, `FotoDokumentation.kt:181-187` |
| Welche Kategorien? | `MESSAUFBAU`, `KALIBRIERUNG` (beide Default `OPTIONAL`), `SONSTIGES` nur für Galerie-Import | `foto/FotoDokumentation.kt:48-58` (`abzufragendeKategorien`, `umfangFuer`) |
| Persistenzbezug | **Session** (`DokumentationsFotoEntity.sessionId`, non-null) | `data/DokumentationsFotoEntity.kt:50` |
| Tagesbezug? | **Nein.** Es gibt keine Abfrage „Fotos für diesen Tag / diesen Messort" | `DokumentationsFotoDao` kennt nur `fuerSession`, `neuesteFlow`, `nichtHochgeladene` |
| Wiederverwendbarkeit | Keine. Ein Foto lässt sich nicht einer zweiten Session zuordnen; nachträglich geht nur der Galerie-Import (`nachtraeglichHinzugefuegt = true`) | `FotoDokumentation.kt:129-166` |
| Default | `fotoDokuAktiv = false` – das Sheet erscheint erst, wenn der Nutzer es einschaltet | `SettingsManager.kt:368-370` |

### 13.2 Das eigentliche Problem

Weil der Anker die **Session** ist und nicht der Messvorgang, erscheint das Sheet im
Standard-Workflow zweimal: einmal für die Mikrofon-Session beim Start, einmal für die
Messgerät-Session, sobald der PCE-323 streamt (`MeasurementRecorder.kt:296-316` schließt die
Mikrofon-Session und legt eine neue an). Beim zweiten Mal ist `fuerSession(neueId)` leer, die
Bedingung greift also erneut. → [F-16](#f-16)

Zusätzlich: Bei jedem weiteren Messstart am selben Tag, am selben Ort, mit demselben Aufbau wird
erneut gefragt – das ist genau die Reibung, die der Auftrag adressiert.

### 13.3 Fachliche Prüfung des gewünschten UX-Prinzips

> Gewünscht: „Wenn die relevante Fotodokumentation für diesen Kontext bereits am selben Tag
> erstellt wurde, soll nicht erneut dazu gezwungen werden."

**Spricht etwas dagegen?** Teilweise, und das ist wichtig:

* **Messaufbau-Foto:** Es belegt *wie und wo* gemessen wurde. Solange Ort und Aufbau identisch
  sind, ist ein zweites Foto am selben Tag ohne Beweiswert. → „einmal je Tag **und** Messort" ist
  fachlich vertretbar.
* **Kalibrierfoto:** Es belegt eine *Kalibrierprüfung vor der Messung*. Das ist keine
  Ortseigenschaft, sondern ein zeitgebundener Vorgang. Ein Kalibrierbeleg von 08:00 Uhr deckt
  eine Messung um 20:00 Uhr nach Meinung vieler Gutachter ab, ein Beleg von gestern nicht. → Die
  Tagesregel ist hier **vertretbar, aber eine fachliche Festlegung des Owners**, keine
  technische Ableitung. Der Placeholder-Text im Stammdaten-Feld „Kalibrierung" („…vor Messung
  protokolliert", `GesamtberichtStammdatenSheet.kt:293`) zeigt, dass der zeitliche Bezug gewollt
  ist.

**Empfohlene Regel** (umsetzbar, ohne Beweiskraft zu verlieren):

| Kategorie | Erneut fragen, wenn … | Sonst |
|---|---|---|
| `MESSAUFBAU` | kein Foto mit demselben `messort` (aus der zuletzt gespeicherten Stammdatenzeile) am selben Kalendertag existiert **oder** `messort`/`mikrofonposition`/`mikrofonhoehe` sich seit dem letzten Foto geändert haben | Zeile „Messaufbau dokumentiert (heute, 08:14) · Neues Foto" statt Sheet |
| `KALIBRIERUNG` | kein Kalibrierfoto am selben Kalendertag existiert | Zeile „Kalibrierung belegt (heute, 08:11) · Neues Foto" |
| beide | der Nutzer tippt „Neues Foto" | – |

Dafür fehlt genau **eine** Abfrage: „Fotos zwischen `von` und `bis`, optional gefiltert nach
Kategorie". `DokumentationsFotoDao` hat sie noch nicht; der Zusammenhang Foto → Messort läuft
über `sessionId` → `stammdaten_verlauf` (bzw. künftig über eine eigene Spalte). Details in
[F-16](#f-16).

### 13.4 Weitere Beobachtungen

* Der Notiztext („Notiz zum nächsten Foto") wird nach jeder erfolgreichen Aufnahme zurückgesetzt
  (`FotoDokumentationSheet.kt:109`) – richtig, aber es ist nicht erkennbar, dass die Notiz nur
  für das *nächste* Foto gilt.
* Bricht die Kamera ab, wird die Kategorie als „übersprungen" ins Diagnoseprotokoll geschrieben
  (`FotoDokumentationSheet.kt:96-102`) – der Nutzer bekommt **keine** Rückmeldung, dass nichts
  gespeichert wurde. Der Zähler bleibt bei `(0/3)`, das ist die einzige Information.
* Erreicht eine Kategorie das Maximum, wird der Button `enabled = false`
  (`FotoDokumentationSheet.kt:173`) – ohne Begründung. → [F-23](#f-23)

---

## 14. Berichtsparameter: Quelle, Scope, Persistenz

### 14.1 Stammdaten je Messung (`stammdaten_verlauf`)

Alle 13 Felder werden im selben Sheet erfasst, alle werden aus dem letzten Eintrag vorbefüllt,
alle werden bei „Speichern" gemeinsam als **neue Zeile** geschrieben
(`GesamtberichtStammdatenSheet.kt:192-216`).

| Parameter | Eingabeort heute | Scope heute | Soll persistieren? | Empfohlener Default | Risiko bei automatischer Übernahme |
|---|---|---|---|---|---|
| `geraetHersteller` | Sheet | je Session-Zeile | ja, **global** | letzter Wert | keins – Gerät wechselt praktisch nie |
| `geraetTyp` | Sheet | je Session-Zeile | ja, **global** | letzter Wert | keins |
| `geraetGenauigkeitsklasse` | Sheet | je Session-Zeile | ja, **global** | letzter Wert | keins |
| `geraetSeriennummer` | Sheet | je Session-Zeile | ja, **global** | letzter Wert | gering – nur bei Zweitgerät falsch; sollte an `meterDeviceAddress` hängen |
| `geraetKalibrierung` | Sheet | je Session-Zeile | ja, **pro Tag** | letzter Wert **des heutigen Tages**, sonst leer | **hoch** – eine gestrige Kalibrierprüfung als heutige auszuweisen ist eine falsche Tatsachenbehauptung |
| `messort` | Sheet (+ „Standort ermitteln") | je Session-Zeile | ja, **pro Standort** | letzter Wert, aber sichtbar als „übernommen" markiert | **hoch** – falscher Messort entwertet den Bericht |
| `mikrofonposition` | Sheet | je Session-Zeile | ja, pro Standort | letzter Wert | mittel |
| `mikrofonhoehe` | Sheet | je Session-Zeile | ja, pro Standort | letzter Wert | mittel |
| `entfernungZurQuelle` | Sheet | je Session-Zeile | ja, pro Standort | letzter Wert | mittel |
| `innenAussen` | Sheet | je Session-Zeile | ja, pro Standort | letzter Wert | gering |
| `fensterzustand` | Sheet | je Session-Zeile | ja, pro Standort | letzter Wert | gering |
| `wetter` | Sheet (+ „Wetter abrufen") | je Session-Zeile | **nein** | leer, mit sichtbarem „Jetzt abrufen" | **hoch** – Wetter von heute Morgen ist abends falsch |
| `datenqualitaetHinweis` | Sheet | je Session-Zeile | **nein** | leer | **hoch** – ein Hinweis auf eine Lücke von gestern gehört nicht in den heutigen Bericht |

**Kernpunkt:** Das Sheet behandelt alle 13 Felder gleich. Tatsächlich zerfallen sie in drei
Klassen: *stabil* (Gerätedaten), *ortsgebunden* (Messaufbau) und *zeitgebunden* (Kalibrierung,
Wetter, Datenqualitätshinweis). Nur die stabile Klasse darf kommentarlos übernommen werden.
→ [F-15](#f-15)

### 14.2 Auswertungsparameter (`report_config`, Singleton-Zeile)

Alle in Einstellungen → Tab „Bericht" → Sektion „Berichtsparameter (§ 287 ZPO)",
`SettingsScreen.kt:1753-1892`. Gespeichert wird sofort bei `onValueChangeFinished`
(`speichereReportConfig()`), also ohne „Speichern"-Knopf – das ist gut.

| Parameter | Default | Scope | Bewertung |
|---|---|---|---|
| `gebietseinstufung` | `""` | global | **blockiert den Bericht**, ist aber nirgends im Berichtsflow sichtbar → [F-08](#f-08) |
| `tierSchwelleVollmessungProzent` | 90,0 | global | ok |
| `tierSchwelleTeilerfassungProzent` | 70,0 | global | ok; keine Validierung, dass Teilerfassung < Vollmessung |
| `schaetzpegelTeilerfassungDb` | 50,0 | global | ok |
| `schaetzpegelMessfensterAbbruchDb` | 55,0 | global | laut KDoc **Altwert ohne Wirkung**, wird in der UI dennoch nicht als solcher gekennzeichnet (der Hinweistext nennt es indirekt, `SettingsScreen.kt:1838`) |
| `geraeteUnsicherheitDb` | 1,4 | global | ok |
| `konservativFensterStartStunde` / `EndeStunde` | 15 / 19 | global | zwei Slider ohne Zeitanzeige im Griff; gegenseitige Begrenzung ist implementiert (`SettingsScreen.kt:1862-1878`) |
| `erzwingeBerichtOhneBestaetigteBewertung` | `false` | global | ok, mit sichtbarem Vorbehalt im Sheet (`BerichtErstellenSheet.kt:214-220`) |

### 14.3 Nicht persistierte Berichtsparameter

| Parameter | Heute | Empfehlung |
|---|---|---|
| Berichtszeitraum (`zeitraum`) | reiner `remember`-State, geht beim Schließen des Sheets verloren | aus der zuletzt beendeten Messung vorbelegen; zusätzlich letzten Zeitraum merken |
| Stammdaten-Auswahl je Tag (`ausgewaehlteIds`) | `remember`, wird bei Zeitraumwechsel geleert | ok, solange [F-15](#f-15) die Duplikate beseitigt |
| Ausgabeformat des Tagesberichts (ZIP vs. Text) | jedes Mal neu zu wählen | letzte Wahl merken |
| „Gesamtbericht statt Zeitraumbericht" (`alsGesamtbericht`) | `remember`, Default `false` | letzte Wahl merken |

---

## 15. State-Persistence-Matrix

Mögliche Scopes: `global` · `pro Gerät` · `pro Standort` · `pro Tag` · `pro Messung` ·
`pro Session (UI)` · `gar nicht`.

### 15.1 Berichts- und Messdaten

| Wert / State | Heute gespeichert? | Scope heute | Empfohlener Scope | UX-Grund |
|---|---|---|---|---|
| Stammdaten: Gerätefelder (4) | ja, Room `stammdaten_verlauf` | je Session-Zeile | **global** (an `meterDeviceAddress` gebunden) | ändern sich nie; jede Wiederholung ist reine Tipparbeit |
| Stammdaten: `geraetKalibrierung` | ja | je Session-Zeile | **pro Tag** | zeitgebundener Nachweis; gestriger Wert wäre falsch |
| Stammdaten: Messaufbau (5) | ja | je Session-Zeile | **pro Standort** | ortsgebunden, aber am selben Ort stabil |
| Stammdaten: `wetter` | ja | je Session-Zeile | **gar nicht** (nur im Bericht) | ändert sich stündlich |
| Stammdaten: `datenqualitaetHinweis` | ja | je Session-Zeile | **pro Messung** | bezieht sich auf genau eine Messung |
| Report-Config (9 Werte) | ja, Room `report_config` (Singleton) | global | global | richtig so |
| Berichtszeitraum | **nein** (`remember`) | – | **pro Messung** (vorbelegen) + „letzter Zeitraum" | spart 4 Taps je Bericht |
| Ausgabeformat Tagesbericht (ZIP/Text) | nein | – | global | wiederkehrende Wahl |
| Umschalter „Gesamtbericht" im Zeitraumdialog | nein (`remember`) | – | global | wiederkehrende Wahl |
| Stammdaten-Auswahl je Tag im Bericht-Sheet | nein (`remember`) | – | gar nicht | korrekt, sobald Duplikate wegfallen |

### 15.2 Geräte- und Aufnahmezustand

| Wert / State | Heute gespeichert? | Scope heute | Empfohlener Scope | UX-Grund |
|---|---|---|---|---|
| `meterDeviceAddress` / `meterDeviceName` | ja (`SettingsManager`) | global | global | richtig |
| Verbindung soll beim App-Start aufgebaut werden | **existiert nicht** | – | global (Schalter „Automatisch verbinden", Default an) | Kern von [F-02](#f-02) |
| `monitoringWasActive` / `audioMonitoringWasActive` | ja | global | global | richtig – trägt den Neustart nach Boot/Prozesstod |
| `audioTriggerQuelle` | ja | global | global | richtig |
| `recordWavAudio` | ja | global | global | richtig |
| `dbThreshold` / `quietHoursThreshold` | ja | global | global | richtig |
| Laufender Messvorgang (Zuordnung Mikro-/Messgerät-Session) | in Room als zwei getrennte Sessions | pro Session | zusätzlich **pro Messvorgang** (Klammer über beide Sessions) | Grundlage für [F-16](#f-16) |

### 15.3 UI-Zustand

| Wert / State | Heute gespeichert? | Scope heute | Empfohlener Scope | UX-Grund |
|---|---|---|---|---|
| Start-Filter (7 Werte) | ja (`SettingsManager.filter*`) | global | global | richtig |
| Protokoll-Filter (`SessionFilterState`) | **nein** (`remember`) | – | global, analog Start | Inkonsistenz → [F-18](#f-18) |
| Suchtext im Protokoll | nein | – | gar nicht | ok |
| Eingeklappte Tagesgruppen | nein (`mutableStateListOf`) | – | gar nicht | ok |
| Offene Einstellungs-Sektionen | nein | – | gar nicht | ok |
| Aktiver Settings-Tab | über Nav-Argument `settings?tab=` | pro Aufruf | pro Aufruf | richtig |
| Eingaben im Stammdaten-Sheet | **nein** (`remember`) | – | mindestens `rememberSaveable` | **Datenverlust** → [F-01](#f-01) |
| Eingaben im Foto-Sheet (Notiz, Zähler) | **nein** | – | `rememberSaveable` | → [F-01](#f-01) |
| „Sheet für Session X schon gezeigt" | ja (`rememberSaveable`) | pro Prozess | **Room/Prefs, pro Messvorgang** | heute der Grund, warum das Sheet nach einer Drehung nicht zurückkommt → [F-01](#f-01) |
| Notiz im „Lärmereignis markieren"-Sheet | nein (`remember`) | – | `rememberSaveable` | Drehung verwirft die Notiz |
| `isProMode` | ja | global | global | richtig |
| `appLanguage` | ja | global | global | richtig |
| `onboardingCompleted` | ja | global | global | Default ist falsch herum → [F-14](#f-14) |

---

## 16. Bluetooth-UX

### 16.1 Zustandsabdeckung

| Zustand | Wo sichtbar | Text | Aktion vorhanden? |
|---|---|---|---|
| `IDLE` | Badge (Start/Meter/Settings), Meter-Zeile, Diagnose | „PCE-323: Nicht verbunden" | Badge → Scan-Dialog; Meter-Screen → „Verbinden" |
| `SCANNING`/`CONNECTING`/`DISCOVERING`/`SUBSCRIBING` | dito, mit pulsierendem Punkt | „Suche…" / „Verbinde…" | – |
| `STREAMING` | dito | „PCE-323: Verbunden" | – |
| `DEGRADED` | dito | „PCE-323: Instabil" | **keine** |
| `RECONNECTING` | dito | „Verbinde erneut…" | – |
| `DISCONNECTED` | dito | „Nicht verbunden" | Diagnose-Selbstprüfung bietet „Verbinden" an – der Knopf öffnet aber die System-App-Info ([F-05](#f-05)) |
| `FAILED` | dito | „PCE-323: Fehler" | **keine im Badge**; Supervisor versucht nichts mehr ([F-03](#f-03)) |
| Bluetooth am Gerät aus | `ConnectionSupervisor` pausiert und setzt `DISCONNECTED`; `BluetoothAdapterStateObserver` ist `private` | „Nicht verbunden" – **ohne Ursache** | keine ([F-04](#f-04)) |
| Bluetooth-Berechtigung fehlt | rote Karte in `MeterScreen`/`MeterPairingDialog` | „Bluetooth-Berechtigung erforderlich" + Knopf | ja, gut gelöst |
| GPS aus (Android 10/11) | rote Karte in `MeterScreen` + Scanfehler-Text | erklärt den Zusammenhang | ja, vorbildlich (`MeterScreen.kt:415-441`) |
| Gerät nicht gefunden | Text im Kopplungsdialog | „Kein Bluetooth-Gerät gefunden. Stelle sicher, dass das PCE-323 eingeschaltet und Bluetooth aktiv ist." | erneut scannen |
| Abbruch **während** einer Messung | Chart zeigt Ausfallband; Notification wechselt auf Warnsymbol; optional Alarm | – | Alarm nur, wenn `alarmierungAktiv` (Default `false`) |

### 16.2 Die Leitfrage des Auftrags

> Kann der Nutzer jederzeit sofort erkennen, ob das Messgerät verbunden ist?

**Ja** – der Badge steht in der TopAppBar von Start, Messgerät und Einstellungen und nennt den
Zustand im Klartext, nicht nur farblich (`ConnectionState.label()`). Das ist gut gelöst.

**Nicht erkennbar ist dagegen:**

1. **Warum** nicht verbunden (Adapter aus / kein Dienst / außer Reichweite / endgültig
   gescheitert) – alle vier zeigen „Nicht verbunden" bzw. „Fehler" ohne Ursache.
2. **Was deshalb eingeschränkt ist.** Nirgends steht, dass ohne Verbindung nur unkalibrierte
   Mikrofonwerte entstehen und der High-End-Bericht damit angreifbar wird. Der Hinweis
   `cockpit_meter_fallback_hint` erscheint **nur**, wenn bereits eine Messgerät-Messung läuft
   und die Verbindung dabei abreißt (`LiveCockpitCard.kt:203` / `:393-400`) – nicht im Ruhezustand.
3. **Wie man es behebt.** Auf Daten, Bericht und im Protokoll-Detail gibt es keinen Badge und
   keinen Weg zur Verbindung.

### 16.3 Empfehlung

* Badge bleibt, bekommt aber eine **Ursache- und Aktionszeile** darunter, solange nicht
  gestreamt wird (Inline, kein Modal): „Nicht verbunden – Bluetooth ist aus · Einschalten" /
  „… – Gerät nicht in Reichweite · Erneut versuchen" / „… – Verbindung fehlgeschlagen ·
  Erneut verbinden".
* Tap auf den Badge bei **gepinntem** Gerät startet einen Reconnect, nicht den Scan. Der Scan
  bleibt über „Anderes Gerät koppeln" erreichbar.
* Keine Modals: Ein fehlendes Messgerät blockiert die Mikrofonmessung nicht und rechtfertigt
  keinen Dialog.

---

## 17. Kritische Systemzustände

### 17.1 Bluetooth getrennt

Siehe [Kapitel 16](#16-bluetooth-ux). Sichtbar: ja. Ursache und Ausweg: nein.

### 17.2 Berechtigung fehlt

| Berechtigung | Wann angefragt | Erklärung vorher | Zustand sichtbar | Weg zur Erteilung |
|---|---|---|---|---|
| `RECORD_AUDIO` | erst beim Druck auf „Messung starten" bzw. auf den Mikrofon-Badge (`LiveCockpitCard.kt:612-626`, `MicrophoneStatusBadge.kt:66-82`) | nein | Diagnose-Selbstprüfung: „Fehlt (keine Audioaufnahmen bei Schwellwertüberschreitung möglich)" | Systemdialog; nach Ablehnung nur noch über die System-Einstellungen |
| `POST_NOTIFICATIONS` | zusammen mit `RECORD_AUDIO` | nein | Selbstprüfung: WARNING | dito |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | beim Scan bzw. „Verbinden" | **ja** – rote Karte mit Begründung und Knopf (`MeterScreen.kt:388-414`) | ja | gut gelöst |
| `CAMERA` | beim ersten Fototap bzw. Videobeweis | nein, aber begründet im KDoc | nur indirekt („Kamera-Berechtigung erforderlich" im Video-Screen) | Systemdialog |
| `ACCESS_COARSE_LOCATION` | bei „Standort ermitteln"/„Wetter abrufen" | nein | Hinweistext „Ohne Standort-Berechtigung nicht möglich" (`GesamtberichtStammdatenSheet.kt:140`) | Systemdialog |
| Akku-Optimierung / exakte Alarme | Einstellungen | ja, mit Text | Selbstprüfung | eigene System-Intents in den Einstellungen (`SettingsScreen.kt:860`, `:2231-2249`) |

**Lücke:** Wird `RECORD_AUDIO` dauerhaft abgelehnt, passiert beim Druck auf „Messung starten"
sichtbar **nichts** – der Launcher-Callback startet den Dienst nur bei Zustimmung
(`LiveCockpitCard.kt:143-152` zeigt einen Toast, `MainActivity.kt:490-496` gar nichts). Es gibt
keinen Weg von dort in die System-Einstellungen.

### 17.3 Mikrofon nicht verfügbar

Drei Fehlerpfade in `startMonitoring()` enden jeweils mit `stopSelf()`:
`AudioRecord`-Konstruktor wirft (`AudioRecordingService.kt:556-572`), `state !=
STATE_INITIALIZED` (`:574-590`), `startRecording()` wirft (`:592-607`). In allen drei Fällen:

* Diagnoseeintrag `AUDIO_INIT_FAILED` – nur im Diagnose-Log sichtbar.
* `_audioAufnahmeAktiv = false`, `_laeuft` bleibt auf seinem alten Wert, bis `stopSelf()` greift.
* **Keine** Snackbar, **kein** Dialog, **kein** Banner. Der Nutzer sieht den Badge auf
  „WAV: INAKTIV" springen und den Startknopf zurückkommen.

→ [F-11](#f-11)

### 17.4 Kein ausreichender Speicherplatz

| Fall | Prüfung vorher? | Beleg |
|---|---|---|
| Videoaufnahme | **ja** – `StatFs(...).availableBytes` gegen `Videospeicher.reichtSpeicher`, mit Snackbar | `VideoAufnahmeScreen.kt:412-417` (`StatFs`, `Videospeicher.reichtSpeicher`) |
| Messung/WAV-Aufnahme | **nein** | `AudioRecordingService.startMonitoring()` prüft nichts |
| Datenbanksicherung | **ja** – `freierPlatzErmitteln` | `backup/SicherungManager.kt:123-137` |
| Anzeige des belegten/freien Platzes | nur Einstellungen → Daten → „Speicherplatz" | `SettingsScreen.kt:1905-2082`, `messreihe/Speicheraufraeumer.kt:141-152` |

Ein Schreibfehler wegen vollem Speicher trifft die WAV-Kette erst zur Laufzeit; dafür existiert
inzwischen ein begrenzter automatischer Neustart (`audio/AudioMonitoringRestartPolicy.kt`, im
README beschrieben). Trotzdem beginnt die Messung heute, ohne dass jemand geprüft hat, ob sie
durchhalten kann. → [F-10](#f-10)

### 17.5 Export fehlgeschlagen

| Export | Fehleranzeige | „Daten trotzdem sicher"? | Erneut versuchen? |
|---|---|---|---|
| High-End-Bericht (Python) | Text im Sheet (`bericht_erstellen_fehler`) | wird nicht gesagt | ja, Button bleibt |
| PDF teilen | Text „Der Bericht konnte nicht zum Teilen geöffnet werden." (`BerichtErstellenSheet.kt:228-234`) | nein | ja |
| Zeitraum-/Gesamtbericht | **keine Fehlerbehandlung** – `erstelleUndTeileZeitraumbericht` fängt nichts; eine Exception in `exportierePdf` beendet die Coroutine, `zeitraumWirdErstellt` bleibt `true` und der Dialog hängt im Ladezustand (`BerichtScreen.kt:64-83`) | – | nein |
| Tagesbericht (ZIP/Text) | **keine** – `reportManager.createZipAndShare(...)` wird ohne `try` aufgerufen, der Dialog schließt sich in jedem Fall (`MainActivity.kt:1244-1262`) | – | nein |
| Messreihen-PDF/CSV im Detail | **keine** – `export.exportierePdf(...)`/`exportiereCsv(...)` ohne Fehlerbehandlung (`ProtokollDetailScreen.kt:352-381`) | – | nein |
| Support-Bundle | Snackbar/Toast mit Text | ja (Outbox-Zähler) | ja | 
| Drive-Upload | Statuskarte + Notification ab 6 Zyklen | **ja, explizit**: „Messwerte werden weiterhin lokal gespeichert" (`DriveSyncNotifier.kt:52-55`) | nur global |

→ [F-27](#f-27)

### 17.6 Weitere technische Zustände aus dem Code

| Zustand | Quelle | Heute sichtbar |
|---|---|---|
| Stiller Trigger-Ausfall (über Schwelle, aber keine Ereignisse) | `AudioRecordingService.kt:963-990` (`pruefeStillenAusfall`) | **nur Notification** |
| Audio-Soll/Ist-Abweichung („WAV-/Mikrofon-Aufzeichnung unerwartet inaktiv") | `AudioRecordingService.kt:932-961` (`pruefeAudioSollIstAbweichung`) | **nur Notification** |
| WAV-Ereignis ohne Tonaufnahme gespeichert | `AudioRecordingService.kt:876-892` | Diagnose-Log |
| ANR-Watchdog hat zugeschlagen | `diagnose/AnrWatchdog.kt` | Support-Bundle |
| Prozess wurde vom System beendet | `diagnose/ProcessExitCollector.kt` | Diagnose-Log |
| Heap-/Speicherdruck | `diagnose/acra/LaufzeitzustandCollector.kt` | Absturzbericht |
| Drive-Ordner blockiert/verschwunden | `driveOrdnerBlockiert` | Statuskarte + Notification |
| Datenbanksicherung veraltet (> 26 h) | `DriveStatusCard.kt:36`, `:244-260` | Statuskarte (rot) |

Sieben dieser acht Zustände erreichen die App-Oberfläche nie. → [F-09](#f-09)

---

## 18. UI-State Inventory

Legende: ✅ sichtbar und verständlich · ⚠️ sichtbar, aber unvollständig · ❌ nicht sichtbar.

### 18.1 Start / Cockpit

| State | Heute sichtbar? | Ausreichend verständlich? | Verbesserung |
|---|---|---|---|
| Bluetooth verbunden / getrennt | ✅ Badge mit Klartext | ⚠️ ohne Ursache | Ursache- und Aktionszeile ([F-04](#f-04)) |
| Bluetooth-Adapter aus | ❌ erscheint als „Nicht verbunden" | nein | `BluetoothAdapterStateObserver` exponieren ([F-04](#f-04)) |
| Bluetooth-Berechtigung fehlt | ❌ auf Start (Selbstprüfung sagt hart `true`) | nein | echte Werte an `bewerteSystemZustand` ([F-04](#f-04)) |
| Mikrofon/WAV aktiv | ✅ Badge „WAV: AKTIV/INAKTIV/AUS (DSGVO)" | ✅ | – |
| Mikrofon-Init fehlgeschlagen | ❌ | nein | Fehlerbanner ([F-11](#f-11)) |
| Mikrofon-Berechtigung fehlt | ⚠️ nur als Problem-Banner, und nur bei laufender Überwachung | nein | dauerhafte Messbereitschaftszeile |
| Speicherplatz | ❌ | nein | in Messbereitschaftszeile ([F-10](#f-10)) |
| Messung läuft | ⚠️ – hängt an `AudioRecordingService.laeuft`, das auch bei reiner BLE-Verbindung `true` ist | nein | Betriebsart aus `leiteDashboardAnzeigeAb` ([F-02](#f-02), [F-06](#f-06)) |
| Messdauer | ❌ falsch, wenn keine Session offen ist | nein | ([F-06](#f-06)) |
| Messquelle kalibriert / Fallback | ✅ Einheit + roter Hinweis bei Fallback | ✅ | – |
| Trigger-Quelle (AUTO/PCE/Mikro) | ✅ Chip im Kopf | ⚠️ sieht aus wie ein Label, ist ein Menü | ([F-22](#f-22)) |
| Schwellenwert | ✅ Text, klickbar → Einstellungen | ⚠️ Klickbarkeit nicht erkennbar | – |
| Stiller Trigger-Ausfall | ❌ nur Notification | nein | ([F-09](#f-09)) |
| Drive-Sync | ❌ | nein | ([F-13](#f-13)) |
| KI-Verarbeitung läuft | ⚠️ nur pro Tagesgruppe | nein | ([F-26](#f-26)) |

### 18.2 Daten / Protokoll

| State | Heute sichtbar? | Ausreichend? | Verbesserung |
|---|---|---|---|
| Session aktiv / beendet | ✅ Badge | ⚠️ „Abgeschlossen" sagt nichts über Verwertbarkeit | Integritätsbadge ([F-12](#f-12)) |
| Datenverfügbarkeit | ❌ | nein | ([F-12](#f-12)) |
| Verbindungsausfälle | ⚠️ erst im Detail, im Audit-Block | nein | Anzahl auf der Karte |
| Messlücken (`GAP`-Flag) | ❌ | nein | ([F-12](#f-12)) |
| Unbestätigte A-/Zeitbewertung | ❌ (nur im Berichts-Sheet) | nein | Hinweis am Detail |
| Rohdaten bereits verdichtet | ❌ (nur im Berichts-Sheet) | nein | Hinweis am Detail |
| Klassifizierungsstand | ⚠️ nur indirekt über das Refresh-Icon | nein | ([F-17](#f-17)) |
| Filter aktiv | ✅ Badge am Filter-Icon | ✅ | – |

### 18.3 Bericht

| State | Heute sichtbar? | Ausreichend? | Verbesserung |
|---|---|---|---|
| Bericht möglich / blockiert | ❌ bis zum Klick | nein | Live-Checkliste ([F-07](#f-07)) |
| Gebietseinstufung gesetzt | ❌ im Sheet | nein | ([F-08](#f-08)) |
| Stammdaten-Lücken | ✅ je Tag, rot | ✅ | – |
| Bericht wird erzeugt | ⚠️ nur Button-Text „Erzeuge …" | nein | Fortschritt/Phasen |
| Bericht fertig | ✅ „Bericht wurde erzeugt." + „PDF teilen" | ✅ | – |
| Fehler beim Erzeugen | ✅ Text | ⚠️ ohne Aussage zur Datensicherheit | – |

### 18.4 Einstellungen / Diagnose

| State | Heute sichtbar? | Ausreichend? | Verbesserung |
|---|---|---|---|
| Selbstprüfung gesamt | ✅ nur in Diagnose | ⚠️ 3 Parameter hartkodiert | ([F-04](#f-04)) |
| Aktionsknöpfe der Selbstprüfung | ⚠️ vorhanden, aber alle mit derselben Wirkung | nein | ([F-05](#f-05)) |
| Drive-Status | ✅ ausführlich | ✅ | nur am falschen Ort ([F-13](#f-13)) |
| Upload je Datei | ✅ mit Fortschritt | ⚠️ kein Retry | ([F-13](#f-13)) |
| Speicherbelegung | ✅ nach Kategorie + frei | ✅ | – |
| Sicherung/Wiederherstellung | ✅ mit Warndialogen | ✅ | – |

---

## 19. Visibility of System Status: was intern existiert und nicht ankommt

| Interner Zustand | Symbol / Datei | Erreicht die UI? |
|---|---|---|
| `ConnectionState` (10 Zustände) | `meter/ConnectionState.kt` | ja, vollständig |
| `BluetoothAdapterStateObserver.enabled` | `meter/ble/BluetoothAdapterStateObserver.kt`, `AppContainer.kt:59` (**private**) | **nein** |
| `MeterTransport.frameQuality` (Fehlerrate) | `meter/MeterTransport.kt` | nur Diagnose (`DiagnoseScreen.kt:261`) |
| `zaehleReconnects` | `messreihe/Reconnects.kt` | nur Diagnose (`DiagnoseScreen.kt:257`) |
| `berechneDatenverfuegbarkeitProzent` | `messreihe/Datenverfuegbarkeit.kt` | **nein** (nur PDF) |
| `MeasurementFlags.GAP` | `data/SessionEntity.kt:113-134` | **nein** |
| `SessionEntity.rohdatenPruefsumme` | `data/SessionEntity.kt:50` | **nein** |
| `anzahlUnbestaetigtZwischen` | `data/SessionDao.kt:91` | nur Berichts-Sheet |
| `leiteDashboardAnzeigeAb` | `messreihe/DashboardStatus.kt` | **nein** (kein Aufrufer) |
| `stillerAusfallHinweis` | `AudioRecordingService.kt:963-990` | nur Notification |
| `audioSollIstFehlerGemeldet` | `AudioRecordingService.kt:932-961` | nur Notification |
| `DiagnosticCode.*` (Fehlercodes) | `diagnose/DiagnosticCode.kt` | nur Diagnose-Log |
| `SpeicherplatzUebersicht` / `Speicherbelegung` | `messreihe/SpeicherplatzUebersicht.kt`, `messreihe/Speicheraufraeumer.kt` | nur Einstellungen |
| `DriveUploadUebersicht` | `drive/DriveUploadUebersicht.kt` | nur `DriveUploadScreen` |
| `HealthActionType` | `diagnose/SystemHealthChecker.kt:11-22` | **nein** – wird von der UI ignoriert ([F-05](#f-05)) |

**Befund:** Die Datenlage ist gut, die Übersetzung in die Oberfläche fehlt. Sieben getestete,
produktivfertige Ableitungen erreichen den Nutzer nicht.

---

## 20. Messintegrität

### 20.1 Was erfasst wird

| Information | Quelle | Verlässlichkeit |
|---|---|---|
| Startzeit | `SessionEntity.startedAt` | exakt |
| Endzeit | `SessionEntity.endedAt` (`null` = läuft) | exakt; verwaiste Sessions werden beim nächsten Start geschlossen (`MeasurementRecorder.kt:362`) |
| Verbindungsabbrüche | `connection_events` → `leiteAusfallbaenderAb` | vollständig je Session |
| Datenlücken im Rohwert | `MeasurementFlags.GAP` (+ `GAP_REASON_SENSOR_ERROR`) | vorhanden, in der UI ungenutzt |
| Datenverfügbarkeit % | `berechneDatenverfuegbarkeitProzent` | vorhanden, in der UI ungenutzt |
| App-Pausen / Prozesstod | `ProcessExitCollector`, verwaiste Sessions | nur Diagnose |
| Sensor-/Hardwareprobleme | `DiagnosticCode.AUDIO_INIT_FAILED`, `frameQuality` | nur Diagnose |
| Unvollständige Datensätze | `anzahlUnbestaetigtZwischen`, `verdichteteMinuten` | nur Berichts-Sheet |
| Aufnahmeprobleme | `stillerAusfallHinweis`, „Ereignis ohne Tonaufnahme" | nur Notification / Log |
| Revisionssicherheit | `SessionEntity.rohdatenPruefsumme`, `DokumentationsFotoEntity.pruefsumme` | in der UI ungenutzt |

### 20.2 Vorschlag für die Darstellung

Ein **Integritätsstatus je Session**, abgeleitet aus vorhandenen Daten, an drei Stellen gleich:
Session-Karte (Daten), Kopf der Detailansicht und Abschlusskarte nach dem Messende.

```
Vollständig      Datenverfügbarkeit ≥ tierSchwelleVollmessungProzent (Default 90 %)
                 UND keine Rohwerte mit GAP-Flag
                 UND Rohdaten nicht verdichtet
Eingeschränkt    Datenverfügbarkeit ≥ tierSchwelleTeilerfassungProzent (Default 70 %)
                 ODER einzelne GAP-Zeilen
Lückenhaft       darunter, oder Rohdaten bereits verdichtet
```

Die drei Schwellen sind bereits konfigurierbar (`ReportConfigEntity`) und werden vom Bericht
ohnehin verwendet – die UI würde damit dieselbe Sprache sprechen wie der Bericht, statt eine
zweite zu erfinden.

Beispieldarstellung auf der Session-Karte (Text, nicht nur Farbe):

```
12.09.2026   08:12 – 17:40
Vollständig · 99,4 % Daten · 0 Ausfälle        [Details >]
```

und im Fehlerfall:

```
12.09.2026   08:12 – 17:40
Lückenhaft · 62,1 % Daten · 4 Ausfälle (2 h 14 min)   [Details >]
Für einen High-End-Bericht nicht geeignet: Rohdaten sind bereits verdichtet.
```

→ [F-12](#f-12)

---

## 21. Google-Drive-Sync: UX-Bewertung

Der Ablauf ist in [Kapitel 8](#8-use-case-c--google-drive-synchronisation) beschrieben. Bewertung
gegen die Leitfragen des Auftrags:

| Frage | Antwort | Bewertung |
|---|---|---|
| Weiß der Nutzer, ob synchronisiert wurde? | Nur, wenn er 4 Taps tief in die Einstellungen geht | ❌ |
| Muss er manuell kontrollieren? | Ja, außer bei ≥ 6 Fehlzyklen (dann Notification) | ⚠️ |
| Gibt es unnötige Sync-Aktionen? | Nein – 30-Minuten-Takt mit Netz-Constraint; Fotos/Videos zusätzlich sofort, weil Beweismaterial | ✅ |
| Ist ein manuelles „Jetzt synchronisieren" sinnvoll? | Ja, und es existiert – aber nur in der Statuskarte | ⚠️ |
| Sind Fehler sichtbar? | In der Karte ja (Pill „Gestört" + `driveSyncLastMessage`), je Datei ja („✕ fehlgeschlagen"), aber ohne Aktion | ⚠️ |
| Ist ein erfolgreicher Sync eindeutig erkennbar? | Ja: „Letzter erfolgreicher Sync: …" plus „Letzte Datenbank-Sicherung: …" mit Veraltungswarnung nach 26 h | ✅ |
| Gibt es unnötige Popups? | Nein, ausdrücklich nicht (eigener ruhiger Kanal, Schwelle 6 Zyklen) | ✅ |

**Empfehlung:** Eine kompakte Sync-Zeile dort, wo Beweisdaten entstehen bzw. betrachtet werden –
im Protokoll-Detail („Diese Messung: 128 Dateien · 126 hochgeladen · 2 offen") und als
unaufdringliche Zeile in der Messbereitschaft. Der ausführliche Status bleibt, wo er ist.
Ein Retry je Datei in `DriveUploadScreen` ist naheliegend, weil `DriveSyncCoordinator` ohnehin
alles Nichthochgeladene erneut versucht – der Knopf müsste nur einen Sofort-Lauf anstoßen
(`DriveSyncPlanung.starteSofort`, wie bei Fotos). → [F-13](#f-13)

---

## 22. KI-Klassifizierung: UX-Bewertung

| Frage | Antwort | Bewertung |
|---|---|---|
| Wie erkennt man eine nicht klassifizierte Messung? | Nur am vorhandenen Refresh-Icon in der Tagesgruppe bzw. an der fehlenden „KI:"-Zeile | ❌ |
| Wie löst man eine erneute Klassifizierung aus? | Fünf Einstiege, siehe [Kapitel 7](#7-use-case-b--ki-klassifizierung-nachträglich) | ⚠️ zu viele, inkonsistent |
| Ist der Status sichtbar? | Nein – es gibt keinen Zustand „klassifiziert / nicht klassifiziert / nichts erkannt / KI aus" | ❌ |
| Loading State | Nur bei 2 von 5 Einstiegen | ❌ |
| Fehlermeldungen | Keine. `classifySafely`/`klassifiziereUndSpeichere` schlucken Fehler bewusst (Nebenfunktion darf die Messung nicht gefährden) – für den *nachträglichen*, vom Nutzer ausgelösten Lauf ist Stille aber falsch | ❌ |
| Retry | Ja, der Knopf bleibt | ✅ |
| Ergebnisstatus | Snackbar mit Anzahl bei Batch-Läufen | ⚠️ |
| Korrekturmöglichkeit | Ja: manuelles Label über AssistChips (`MainActivity.kt:1436-1455`), überschreibt das KI-Label in der Anzeige | ✅ |
| Unnötige Navigation | Einstieg 4 verlangt Daten → Session → Scrollen | ⚠️ |

**Empfehlung:** Einen sichtbaren Klassifizierungsstatus je Aufnahme
(`nicht klassifiziert` / `KI: <Label>` / `KI: nichts erkannt` / `KI deaktiviert`) einführen und
die fünf Einstiege auf zwei reduzieren: Batch je Tagesgruppe (mit Fortschritt) und je Aufnahme
(mit Ladezustand). → [F-17](#f-17), [F-26](#f-26)

---

## 23. High-End-Bericht: UX-Bewertung

| Prüfpunkt | Heute | Bewertung |
|---|---|---|
| Datenvoraussetzungen | `tage.none { it.rohwerte > 0 }` → Fehlertext nach dem Klick; Rohwertzahl steht vorher im Sheet | ⚠️ |
| Validierung vor Erstellung | vier Prüfungen, alle erst in `erzeugen()` | ❌ ([F-07](#f-07)) |
| Fehlende Parameter | Gebietseinstufung blockiert, ist im Sheet aber nicht einmal erwähnt | ❌ ([F-08](#f-08)) |
| Fehlende Fotos | wird nicht geprüft und nicht angezeigt | ⚠️ – Fotos gehen in den PDF-Bericht ein (`report/MessreiheExport.kt`), fehlende sind aber kein Blocker |
| Messqualität | `verdichteteMinuten` und `unbestaetigteWerte` werden angezeigt bzw. geprüft; Datenverfügbarkeit nicht | ⚠️ |
| KI-Klassifizierung | fließt nicht in die Vorprüfung ein | – (gewollt) |
| Ladezeit | Chaquopy-Lauf, Dauer unbekannt | – |
| Fortschrittsanzeige | keine, nur Button-Text „Erzeuge …" | ❌ |
| Fehlermeldungen | `ChaquopyReportRunner.Ergebnis.Fehler` als Text im Sheet; OOM wird gezielt gefangen (`report/HighEndReportExport.kt:160-190`) | ✅ |
| Retry | Button bleibt aktiv | ✅ |
| Ergebniszugriff | „Bericht wurde erzeugt." + „PDF teilen" | ⚠️ – der Pfad wird nicht genannt, es gibt keine Liste erzeugter Berichte |
| Export | System-Share über `BerichtDatei.teile` | ✅ |
| Erneute Erstellung | möglich, überschreibt/erzeugt neu | ✅ |

**Kern:** Die Vorprüfungen sind bereits vollständig als reine Funktionen vorhanden
(`retentionFehler`, `bewertungsFehler`, `auswahlFehler`, `areaSelectionError`,
`fehlendeStammdatenFelder`) und unit-getestet. Sie werden nur zum falschen Zeitpunkt aufgerufen.
Sie *vor* dem Klick als Checkliste zu rendern ist eine Umstellung von wenigen Zeilen im Sheet,
ohne Änderung an der Logik. → [F-07](#f-07)

---

## 24. Fehlerprävention

| Risiko | Heute verhindert? | Wie es verhindert werden sollte |
|---|---|---|
| Messung starten ohne Mikrofon-Berechtigung | teilweise – Systemdialog; bei dauerhafter Ablehnung passiert nichts Sichtbares | Messbereitschaftszeile mit CTA „Berechtigung erteilen" bzw. „App-Einstellungen öffnen" |
| Messung starten ohne Speicherplatz | **nein** | `StatFs`-Prüfung wie beim Video, inline-Warnung statt Abbruch ([F-10](#f-10)) |
| Messung starten mit getrenntem Messgerät | nicht verhindert (richtig: Mikrofonmessung bleibt erlaubt), aber auch nicht benannt | Hinweis „läuft unkalibriert" vor dem Start, nicht erst währenddessen |
| Messung starten, während schon eine läuft | verhindert – FAB im Protokoll wird ausgeblendet (`ProtokollScreen.kt:172`), Cockpit zeigt keinen Startknopf | ✅ |
| Zwei parallele Sessions | verhindert – `sessionMutex`, „höchstens eine offene Session" | ✅ |
| Doppelt gestartete Hintergrundjobs | verhindert – `ConnectionSupervisor.start()` ist No-Op bei gleichem Gerät (`:145`), `AlarmCoordinator.start()` prüft `job?.isActive` | ✅ |
| Doppelt ausgelöste Batch-Klassifizierung | **nicht verhindert** – der Menüeintrag hat keinen Busy-Zustand ([F-26](#f-26)) | globaler Busy-State + Fortschritt |
| Bericht mit unvollständiger Messung | teilweise – Retention und unbestätigte Bewertung blockieren; Datenlücken nicht | Integritätsstatus in die Vorprüfung ([F-12](#f-12)) |
| Bericht ohne Gebietseinstufung | blockiert, aber erst nach dem Klick | Live-Checkliste ([F-08](#f-08)) |
| Eingaben durch Navigation/Drehung verlieren | **nicht verhindert** | `rememberSaveable` + Entwurf sichern ([F-01](#f-01)) |
| Versehentliches Löschen einer Aufnahme | verhindert – Soft-Delete + Snackbar „Rückgängig" (`MainActivity.kt:1130-1139`) | ✅ vorbildlich |
| Versehentliches endgültiges Löschen | verhindert – Dialog im Papierkorb | ✅ |
| Versehentliches Aufräumen | verhindert – Vorschau mit Anzahl und Bytes vor dem Einschalten (`SettingsScreen.kt:1255-1283`) | ✅ vorbildlich |
| Versehentliche Wiederherstellung | verhindert – zwei Warndialoge | ✅ |
| Falsches Gerät koppeln (Spoofing) | verhindert – Pinning-Warnung | ✅ |

**Bilanz:** Bei zerstörenden Aktionen ist die Fehlerprävention vorbildlich. Bei *vorbereitenden*
Zuständen (Berechtigung, Speicher, Berichtsvoraussetzungen, Eingabeschutz) fehlt sie fast
vollständig. Genau dort kostet ein Fehler am meisten, weil er erst Stunden später auffällt.

---

## 25. Disabled Controls

| Control | Datei:Zeile | Deaktiviert wenn | Grund erkennbar? | Empfehlung |
|---|---|---|---|---|
| „Bericht jetzt erzeugen" | `BerichtErstellenSheet.kt:240` | `erzeugt \|\| laedt` | nur während des Ladens (Spinner) | zusätzlich sperren, solange Voraussetzungen fehlen – **mit** Begründung darunter ([F-07](#f-07)) |
| Foto-Kategorie-Button | `FotoDokumentationSheet.kt:173` | `anzahl >= fotoDokuMaxProKategorie` | **nein** | Zähler `(3/3)` steht daneben, aber kein Text „Maximum erreicht" |
| „Von Drive wiederherstellen" | `SettingsScreen.kt:1460` | `driveOrdnerId == null` oder Lauf aktiv | **ja** – Hinweistext darunter (`:1466-1472`, Text `:1468`) | ✅ vorbildlich |
| Sicherung erstellen/einspielen | `SettingsScreen.kt:1443`, `:1451` | `sicherungLaeuft` | über `LinearProgressIndicator` | ✅ |
| „Jetzt synchronisieren" | `DriveStatusCard.kt:293` | `isSyncing` | ja (Spinner + „Wird hochgeladen…") | ✅ |
| „Mit Google Drive verbinden" | `DriveStatusCard.kt:326` | `isSyncing` | nein | selten relevant |
| Scan-Button im Messgerät-Screen | `MeterScreen.kt:446` | `isScanning` | ja (Text „Suche…") | ✅ |
| Tagesweise KI-Klassifizierung | `MainActivity.kt:1072` | läuft gerade | ja (Spinner) | ✅ |
| Pro-Ereignis-Klassifizierung im Detail | `ProtokollDetailScreen.kt:451` | läuft gerade | ja (Spinner) | ✅ |
| „Speichern" im Lern-Dialog | `MainActivity.kt:1192` | Name leer | implizit | ✅ |
| Zeitraum-Dialog „Abbrechen" | `BerichtScreen.kt:212` | Erstellung läuft | ja (Spinner im Dialogtext) | ✅ |
| Report-Config-Regler | `SettingsScreen.kt:1792` | bis die Zeile geladen ist | nein, aber sehr kurz | ✅ |

**Fazit:** Nur zwei von zwölf deaktivierten Controls erklären sich nicht selbst – der
Foto-Button am Maximum und (indirekt) der Berichtsknopf, weil er *nicht* deaktiviert ist, obwohl
er es sein müsste.

---

## 26. Navigation-Audit

### 26.1 Befunde

| # | Befund | Beleg |
|---|---|---|
| 1 | `MeterScreen` ist nur über vier Ebenen in den Einstellungen erreichbar, obwohl das Messgerät die zentrale Datenquelle ist | `SettingsScreen.kt:764` |
| 2 | Derselbe Zielscreen wird auf zwei Arten angesteuert: `navigiereZuTab("meter")`/`("diagnose")` mit `popUpTo("main")` aus dem Cockpit gegenüber `navController.navigate("meter")` aus den Einstellungen → unterschiedliches Zurück-Verhalten | `MainActivity.kt:176-181` vs. `:249-256` |
| 3 | Die Callbacks `onNavigateToMeter`/`onNavigateToDiagnose` werden an `LiveCockpitCard` übergeben, dort aber nie aufgerufen – die Verdrahtung existiert, der Einstieg fehlt | `LiveCockpitCard.kt:98-99` |
| 4 | `BerichtScreen` ist ein Screen mit exakt zwei Knöpfen und einem Einleitungstext – ein klassischer Zwischenschritt | `BerichtScreen.kt:117-152` |
| 5 | Kein `BackHandler` irgendwo: Ein offenes Bottom Sheet mit 13 ausgefüllten Feldern verliert bei „Zurück" alles ohne Rückfrage (das Foto-Sheet fragt nach, das Stammdaten-Sheet nicht) | `GesamtberichtStammdatenSheet.kt:218` vs. `FotoDokumentationSheet.kt:142` |
| 6 | Keine Deep Links, auch nicht für „Messung starten/stoppen" – die Notification-Aktion arbeitet über ein Extra + `PendingUiAction`, was funktioniert, aber nicht erweiterbar ist | `AndroidManifest.xml`, `ui/PendingUiAction.kt` |
| 7 | Bottom-Nav bleibt auf Einstellungen/Diagnose/Messgerät sichtbar – bewusst so (Kommentar `MainActivity.kt:185-193`), aber sie markiert dort keinen Eintrag als aktiv | `istBottomNavZielAktiv`, `MainActivity.kt:362` |
| 8 | Sackgasse: `DriveUploadScreen` zeigt Fehlschläge, hat aber keinen Weg zur Behebung (kein Link in die Drive-Einstellungen) | `DriveUploadScreen.kt` |
| 9 | Sackgasse: `KiErklaerungScreen` ist reiner Text ohne Aktion, obwohl er die Schwellen erklärt, die man direkt daneben einstellen könnte | `KiErklaerungScreen.kt` |

### 26.2 Was gut ist

* Der globale `SnackbarHost` liegt im äußeren `Scaffold` und überlebt Screenwechsel.
* `launchSingleTop` verhindert Doppeleinträge im Back Stack.
* Das Protokoll-Detail lädt über `byIdFlow` und aktualisiert sich live, auch während die Messung
  läuft.
* Screens werden während laufender Aufgaben nicht gesperrt – die Messung läuft im
  Foreground Service weiter, unabhängig davon, wo der Nutzer gerade ist.

### 26.3 Empfohlene Struktur

Große strukturelle Änderungen sind laut Auftrag erlaubt. Zwei Vorschläge in
[Kapitel 31](#31-structural-ux-improvements): Messgerät als Ziel erster Klasse, und
`BerichtScreen` als echter Berichts-Arbeitsplatz statt Zwischenseite.

---

## 27. Progressive Disclosure

### 27.1 Was heute schon gestuft ist

| Ort | Mechanismus | Bewertung |
|---|---|---|
| Einstellungen | 3 Tabs × `SettingsSectionCard` (aufklappbar, mit Zusammenfassungszeile) + Lite/Pro-Umschalter | **sehr gut** – die Zusammenfassung nennt den aktuellen Wert, ohne aufzuklappen |
| Protokoll-Detail | „Audit-Details" aufklappbar | gut |
| Start | Filterpanel aufklappbar, Tagesgruppen einklappbar | gut |
| Cockpit | Trigger-Quelle als Chip mit Menü | Inhalt richtig gestuft, Affordanz falsch ([F-22](#f-22)) |

### 27.2 Was fehlt

| Information | Heute | Empfehlung |
|---|---|---|
| „Warum ist mein Messwert unkalibriert?" | nur die Einheit „dB (Mikrofon)" | Ein-Satz-Erklärung hinter „Details", verlinkt auf die Messgerät-Kopplung |
| „Was bedeutet Gebietseinstufung?" | `ReportAreaSelection` nennt Kürzel und Label; ungeprüfte Gebiete werden korrekt gesperrt | zusätzlich die Richtwerte anzeigen, sobald gewählt |
| „Was ist LAeq / LAmax / L10 / L50 / L90?" | nur die Abkürzung | ausklappbare Legende im Detail |
| „Was passiert bei einem Verbindungsabbruch?" | nichts | ein Satz im Messbereitschafts-Bereich |
| Fachbegriffe im Stammdaten-Sheet („Genauigkeitsklasse", „Mikrofonposition") | nur Labels, teils mit Placeholder-Beispiel | Placeholder für **alle** Felder (heute nur 4 von 13 haben einen) |

Für erfahrene Nutzer darf nichts davon dauerhaft sichtbar sein – alle Vorschläge sind
Aufklapp-Inhalte, keine Erklärtexte im Hauptfluss.

---

## 28. Android-/Material-Best-Practices und Accessibility

Nur tatsächliche Befunde aus diesem Code, keine allgemeine Checkliste.

### 28.1 Material 3

| Thema | Befund | Beleg |
|---|---|---|
| Komponentenwahl | durchgängig M3 (`NavigationBar`, `SegmentedButton`, `FilterChip`, `AssistChip`, `ModalBottomSheet`, `BadgedBox`, `RangeSlider`) | – |
| Primary Action | Cockpit-Startknopf ist korrekt die einzige gefüllte Primäraktion des Screens | `LiveCockpitCard.kt:611-641` |
| Destructive Actions | konsistent `colorScheme.error` + Bestätigungsdialog | `TrashScreen.kt:156-180`, `SettingsScreen.kt:1449-1456` |
| Dialog Usage | 25 Dialoge, davon 3 ohne echte Entscheidung ([Kapitel 12](#12-dialog-inventory)) | – |
| Empty States | Start und Daten haben Icon + Titel + Erklärung + (Start) Reset-Knopf; `DriveUploadScreen` und `TrashScreen` haben Text | `MainActivity.kt:948-1007`, `ProtokollScreen.kt:295-311` |
| Loading States | uneinheitlich: `CircularProgressIndicator`, `LinearProgressIndicator`, Button-Textwechsel, und an 3 Stellen gar nichts | [Kapitel 7](#7-use-case-b--ki-klassifizierung-nachträglich) |
| Error States | uneinheitlich: Inline-Text, Snackbar, Toast, Notification, oder nichts | [Kapitel 17](#17-kritische-systemzustände) |
| Snackbar vs. Toast | beides parallel im Einsatz; 5 unbedingte Toasts | `DiagnoseScreen.kt:314`, `:487`, `:714`, `SettingsScreen.kt:2328`, `LiveCockpitCard.kt:150` |
| Progress Indicators | `LinearProgressIndicator(progress = {…})` mit echtem Wert im Upload – gut; „kein erfundener Balken, wo nichts gemessen wird" ist explizit kommentiert | `DriveUploadScreen.kt:158-179` |
| Typography | zentrale Skala über `provideAppTypography()`, kein `sp`-Literal in Screens außer `letterSpacing = 1.5.sp` | `ui/theme/Typography.kt`, `LiveCockpitCard.kt:353` |
| Dynamic Color | bewusst aus, dokumentiert | `ui/theme/Theme.kt:51-57` |
| Dark Mode | vollständiges Dark-Schema vorhanden, **aber** 28 hartkodierte helle Farbliterale in 6 UI-Dateien umgehen es | siehe 28.3 |
| System Insets | `enableEdgeToEdge()`, äußeres `Scaffold` mit `contentWindowInsets = WindowInsets(0,0,0,0)`, `NavHost` bekommt nur `bottom`-Padding; die inneren Screens haben eigene `Scaffold`s mit Default-Insets | `MainActivity.kt:196`, `:209-214` – **Needs verification** |
| Keyboard Handling | **kein** `imePadding()` in der gesamten UI. Das Stammdaten-Sheet hat 13 Textfelder in einem scrollbaren `ModalBottomSheet` | `GesamtberichtStammdatenSheet.kt:218-224` – **Needs verification** |
| Back Handling | kein `BackHandler`; ein offenes Sheet mit Eingaben verliert sie bei „Zurück" | siehe [F-01](#f-01) |
| Lifecycle | `DisposableEffect` + `LifecycleEventObserver` für Berechtigungsprüfung bei `ON_RESUME` an 4 Stellen – korrekt und konsistent | `MainActivity.kt:499-513`, `MeterScreen.kt:109-119`, `MeterControlCard.kt:88-97`, `SettingsScreen.kt:411-421` |
| State Restoration | nur 3 `rememberSaveable` in der gesamten App | siehe [F-01](#f-01) |

### 28.2 Accessibility

| Prüfpunkt | Befund | Beleg |
|---|---|---|
| **Touch Targets** | `NavigationBarItem` und die meisten `IconButton`s sind ≥ 48 dp. Unter 48 dp: Referenz-Chip löschen (**20 dp**), Tagesgruppe klassifizieren (**36 dp**), Tagesbericht je Tag (**36 dp**), Schließen im Markier-Sheet (**36 dp**), Klassifizieren im Detail (**40 dp**) | `MainActivity.kt:923`, `:1073`, `:1084`, `MarkNoiseEventBottomSheet.kt:110`, `ProtokollDetailScreen.kt:452` |
| **Touch Targets (Badges)** | `BluetoothStatusBadge` und `MicrophoneStatusBadge` sind klickbare `Surface`s mit `padding(vertical = 5.dp)` um einen `labelSmall`-Text – deutlich unter 48 dp, und sie sind die primäre Bedienung für Verbindung und WAV | `BluetoothStatusBadge.kt:80-94`, `MicrophoneStatusBadge.kt:100-113` |
| **Semantische Rollen** | Nur 4 Stellen setzen `semantics` (2× `liveRegion`, 1× `contentDescription` am Chart, 1× `mergeDescendants`). Klickbare `Surface`/`Row`/`Box` ohne `Role.Button`: Trigger-Quelle-Chip (`LiveCockpitCard.kt:252`), Schwellenwert-Text (`:336`), Filter-Kopfzeile (`MainActivity.kt:808`), Kategorie-Kacheln (`MarkNoiseEventBottomSheet.kt:148`), Ordnerzeile (`DriveFolderPickerDialog.kt:321`), Preset-Zeilen (`RuhezeitPresetsDialog.kt:157`, `:262`) | – |
| **Content Descriptions** | Interaktive Icons haben durchweg eine – geprüft in `MainActivity`, `ProtokollScreen`, `MeterScreen`, `DiagnoseScreen`. 64 × `contentDescription = null` betreffen dekorative Icons, das ist korrekt | – |
| **Nur über Farbe vermittelt** | Keine der Statusanzeigen verlässt sich allein auf Farbe: `ConnectionState.label()` liefert immer Text, `StatusPill` und die Badges tragen Text, die Selbstprüfung hat Icon + Text. Ausdrücklich so kommentiert in `ConnectionState.kt:22-25` | ✅ **vorbildlich** |
| **Live-Regionen** | Live-Pegel im Cockpit und im Messgerät-Screen sind als `LiveRegionMode.Polite` ausgezeichnet | `LiveCockpitCard.kt:346`, `MeterScreen.kt:319` – laut README am Gerät noch nicht verifiziert |
| **Chart** | `PegelverlaufChart` hat eine generierte `contentDescription` und ist laut README voll getestet | `PegelverlaufChart.kt:118` |
| **Dynamische Schriftgrößen** | Risiko an drei Stellen: Badges mit `widthIn(max = 84.dp)` + `maxLines = 1` + `TextOverflow.Ellipsis` (`MainActivity.kt:580`, `:587`), Cockpit-Kopf mit `maxLines = 1` (`LiveCockpitCard.kt:234`, `:240`), `AssistChip`s mit fester `height(28.dp)` (`MainActivity.kt:1444`, `:1451`) | **Bestätigt am Emulator (25.09.2026)** – und schlimmer als vermutet: der Cockpit-Titel wird nicht gekürzt, sondern bekommt `maxBreite=0px` und verschwindet, schon bei Schriftfaktor 1,0 auf schmalem Gerät. Siehe [F-34](#f-34) |
| **Fokusreihenfolge** | Keine `focusRequester`/`focusProperties` im Code; die Reihenfolge ergibt sich aus der Komposition. Im Stammdaten-Sheet folgt sie der fachlichen Reihenfolge – plausibel | **Needs verification** |
| **Fehlermeldungen** | Inline-Fehlertexte stehen als eigene `Text`-Composables neben dem Feld/Button und werden von TalkBack gelesen. `OutlinedTextField` nutzt aber nirgends `isError`/`supportingText` | `BerichtErstellenSheet.kt:228` |
| **Disabled Controls** | siehe [Kapitel 25](#25-disabled-controls) – deaktivierte Knöpfe bekommen keine Erklärung in den Semantics |
| **Sprache** | `values/`, `values-de/` und `values-en/` haben je 441 Strings. Daneben stehen **156** literale `Text("…")`-Aufrufe in `ui/` mit deutschem Text, die in keiner Sprache übersetzt werden | Zählung per `grep -rn 'Text("' app/src/main/java/com/example/lrmprotokoll/ui` |

### 28.3 Hartkodierte Farben (Dark-Mode-Risiko)

Betroffene Stellen (28 Literale in 8 Dateien), alle mit hellen Container-Farben, die im
Dark-Schema nicht überschrieben werden:

| Datei | Zeilen | Wirkung |
|---|---|---|
| `BluetoothStatusBadge.kt` | 47, 51, 53, 54, 56 | heller Badge-Hintergrund auf dunklem Grund |
| `MicrophoneStatusBadge.kt` | 85, 86, 90, 91, 95, 96 | dito |
| `ProtokollScreen.kt` | 478, 479 | „Aktiv"-Badge |
| `OemDeviceHelperCard.kt` | 122, 135, 140 | „alles in Ordnung"-Zustand |
| `ui/components/StatusPill.kt` | 66, 67, 68 | `CALIBRATED`-Variante |
| `MeterScreen.kt` | 534, 537, 538, 539, 540, 542 | Zustandsicon (Vordergrundfarbe, geringeres Risiko) |
| `PegelverlaufChart.kt` | 326, 331 | Chart-Akzente (bewusst, siehe Datei) |
| `MainActivity.kt` | 1400 | Favoritenstern (Gold, bewusst) |

Das ist **bereits im README als offene Owner-Entscheidung geführt** („30 hartcodierte
Farbliterale … bewusst als Owner-Entscheidung offengelassen"). Dieses Audit bestätigt den Befund
und ordnet ihn als P2 ein, entscheidet ihn aber nicht. → [F-19](#f-19)

---

## 29. Findings im Detail

Jedes Finding hat eine UX- und eine technische Perspektive. „Betroffener Code" nennt nur
Symbole, die im Repository tatsächlich existieren.

<a id="f-01"></a>
### F-01 · Eingaben in den Mess-Sheets gehen verloren – und das Sheet kommt nicht zurück · **P0**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Wird das Gerät gedreht (oder die Activity vom System neu erzeugt),
während das Sheet „Berichtsangaben für diese Messung" oder „Fotodokumentation" offen ist,
verschwindet das Sheet. Alle bis dahin eingetippten Werte sind weg. Es öffnet sich auch **nicht
erneut**: Die Merker `zuletztGefragteSession` und `zuletztGefragteStammdatenSession` sind
`rememberSaveable` und überleben die Neuerzeugung, die Sheet-Zustände dagegen nicht.

*User Problem:* Der Nutzer verliert bis zu 13 Freitextfelder und bekommt keine zweite Chance.
Die Messung läuft weiter, wird aber ohne Messaufbaudokumentation aufgezeichnet – und genau die
ist laut `DokumentationsFotoEntity`-KDoc der Kern des Beweiswerts. Nachtragen geht nur über
„Angaben für diesen Tag nachtragen" im Berichts-Sheet, was der Nutzer in diesem Moment nicht
weiß.

*Betroffener Use Case:* Standard-Workflow, jede Messung. *Häufigkeit:* **hoch** – ein
Bildschirmdreh beim Hinlegen des Telefons genügt.

*Zielverhalten:* Eingaben überleben Rekomposition und Prozesstod; das Sheet erscheint danach
wieder, solange der Messvorgang läuft und die Angaben fehlen.

*UX-Begründung:* Datenverlust ohne Rückweg ist die schwerste Kategorie in diesem Audit. Die
Messung ist ein Beweismittel; eine verlorene Dokumentation ist nicht nachholbar.

| Kennzahl | Heute | Danach |
|---|---|---|
| Verlorene Eingaben bei Drehung | bis 13 Felder | 0 |
| Taps zum Nachholen | 7 (Bericht → Sheet → Zeitraum → Tag → „nachtragen") | 0 |

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Composable | `NoiseProtocolApp` (`ui/MainActivity.kt:367`) |
| State | `fotoSheetFuerSession` (`:398`, `remember`), `stammdatenSheetFuerSession` (`:417`, `remember`) |
| State | `zuletztGefragteSession` (`:399`), `zuletztGefragteStammdatenSession` (`:418`) – beide `rememberSaveable` |
| Composable | `GesamtberichtStammdatenSheet` (`ui/GesamtberichtStammdatenSheet.kt:73`), 13 × `remember { mutableStateOf("") }` (`:88-100`) |
| Composable | `FotoDokumentationSheet` (`ui/FotoDokumentationSheet.kt:69`), `notiz` (`:83`), `gezaehlt` (`:84`) – beide `remember` |
| Persistenz | `stammdaten_verlauf` (nur beim Speichern), `dokumentationsfotos` |

*Aktuelle Implementierung:* Zwei `LaunchedEffect`s auf `offeneSessionFlow()` öffnen die Sheets
genau einmal je Session-ID und merken sich die ID `rememberSaveable`. Die Sheet-Sichtbarkeit und
alle Formularfelder liegen in `remember`.

*Vorgeschlagene Änderung*

1. Alle 13 Formularfelder in `GesamtberichtStammdatenSheet` auf `rememberSaveable` umstellen
   (Strings, kein eigener `Saver` nötig). Gleiches für `notiz` in `FotoDokumentationSheet` und
   `noteText` in `MarkNoiseEventBottomSheet`.
2. `fotoSheetFuerSession` und `stammdatenSheetFuerSession` auf `rememberSaveable` umstellen
   (`Long?` ist saveable).
3. Den „schon gefragt"-Merker von *Compose-State* auf *Fachzustand* heben: statt
   `zuletztGefragteSession` eine abgeleitete Bedingung verwenden, die den tatsächlichen
   Datenbestand prüft — für Fotos existiert sie bereits (`dokumentationsFotoDao().fuerSession(id)
   .isEmpty()`), für Stammdaten wäre es „für den Messvorgang liegt noch keine
   `stammdaten_verlauf`-Zeile vor". Dann ist der Merker entbehrlich und das Sheet kehrt nach
   einer Drehung von selbst zurück.
4. Optional, aber naheliegend: einen Entwurf (`stammdaten_entwurf`) in `SettingsManager`
   ablegen, damit auch ein Prozesstod die Eingaben nicht verliert.

*State-Modell (nach der Änderung)*

```
offeneSession: SessionEntity?          (Room, vorhanden)
hatFotosFuerMessvorgang: Boolean       (abgeleitet, Room)
hatStammdatenFuerMessvorgang: Boolean  (abgeleitet, Room — neue DAO-Abfrage)
sheetSichtbar = offeneSession != null && !hatXFuerMessvorgang
formularwerte: rememberSaveable
```

*Persistenzänderung:* Schritt 1–3 brauchen **keine**. Schritt 4 nur `SharedPreferences`.
Für „hat Stammdaten für diesen Messvorgang" wird eine neue `@Query` in `StammdatenVerlaufDao`
gebraucht (kein Schemawechsel, keine Migration).

*Navigation Impact:* keiner.

*Migration/Rückwärtskompatibilität:* keine – keine Schemaänderung.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `androidTest/.../GesamtberichtStammdatenSheetInstrumentedTest.kt` | läuft weiter; **neuer** Test „Drehung erhält Eingaben" sinnvoll (`ActivityScenario.recreate()`) |
| `androidTest/.../FotoDokumentationSheetPermissionInstrumentedTest.kt` | unverändert |
| `test/.../ui/HomeNavigationComposeTest.kt` | unverändert |
| **Neu (JVM)** | Test der neuen DAO-Abfrage „hat Stammdaten für Messvorgang" analog `test/.../data/SessionDaoTest.kt` |
| **Neu (instrumentiert)** | Regressionstest „Sheet erscheint nach `recreate()` erneut, wenn keine Angaben gespeichert wurden" |

---

<a id="f-02"></a>
### F-02 · Kein Verbindungsaufbau beim App-Start; „Verbinden" ist faktisch „Messung starten" · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Beim Öffnen der App passiert nichts in Richtung Messgerät. Der Badge
zeigt „PCE-323: Nicht verbunden", auch wenn das Gerät eingeschaltet daneben liegt. Der einzige
Weg zu einer Verbindung geht über den Foreground Service – entweder über „Messung starten" oder
über „Verbinden"/Gerätewahl, was denselben Service startet. Danach meldet das Cockpit „Messung
läuft", obwohl je nach Vorzustand weder Mikrofon noch WAV aktiv sind.

*User Problem:* Der wichtigste Schritt des Standard-Workflows („Bluetooth verbinden, falls nicht
automatisch verbunden") ist nicht als eigener Schritt vorgesehen. Wer ihn trotzdem geht, landet
in einem Zwischenzustand, in dem die App etwas anderes behauptet, als sie tut.

*Betroffener Use Case:* Standard-Workflow, jede Messung. *Häufigkeit:* **hoch**.

*Zielverhalten:* Beim App-Start wird ein gepinntes Gerät automatisch verbunden – **ohne**
Aufzeichnung. „Messung starten" beginnt die Aufzeichnung auf einer bereits stehenden Verbindung.

*UX-Begründung:* Verbinden und Messen sind zwei Entscheidungen mit unterschiedlichen Folgen
(Verbindung: keine Daten; Messung: Beweisdaten mit Zeitstempel). Sie in einen Knopf zu legen,
macht beide unklar.

| Kennzahl | Heute | Danach |
|---|---|---|
| Taps bis „verbunden" | 2 (Badge + Gerät) oder 5 (über Einstellungen) | 0 |
| Wartezeit Scan | bis 10 s | entfällt (direkter Reconnect) |
| Falsche Zustandsanzeige | „Messung läuft" ohne Aufzeichnung möglich | ausgeschlossen |

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Service | `AudioRecordingService.onStartCommand` (`audio/AudioRecordingService.kt:255`), `ensureMeterMonitoringStarted` (`:211`) |
| Policy | `sollAudioMonitoringStarten` (`audio/AudioStartPolicy.kt:13`) |
| Supervisor | `ConnectionSupervisor.start/stop` (`meter/ConnectionSupervisor.kt:145`, `:154`) |
| UI | `LiveCockpitCard` (`dienstAktiv` aus `AudioRecordingService.laeuft`, `ui/LiveCockpitCard.kt:109`), `MeterScreen.ensureConnected` (`ui/MeterScreen.kt:131`), `MainActivity` Pairing-Callback (`:1276-1288`) |
| Persistenz | `SettingsManager.meterDeviceAddress/-Name`, `monitoringWasActive`, `audioMonitoringWasActive` |

*Aktuelle Implementierung:* `_laeuft` wird gesetzt, sobald der Service in den Vordergrund geht
(`:343`) – unabhängig davon, ob aufgezeichnet wird. Die Cockpit-UI leitet daraus „Messung läuft"
ab.

*Vorgeschlagene Änderung*

1. Eine neue, explizite Service-Aktion `ACTION_CONNECT_METER_ONLY` (analog zu den vorhandenen
   `ACTION_START_AUDIO_MONITORING` / `ACTION_STOP_AUDIO_RECORDING`), die den Service startet,
   `ensureMeterMonitoringStarted()` aufruft und `shouldStartAudio` erzwungen auf `false` lässt.
2. Einen neuen `StateFlow` `AudioRecordingService.messungLaeuft`, der **nicht** „Service läuft",
   sondern „es gibt eine offene Session" ausdrückt (oder – ohne neuen Flow – das Cockpit direkt
   auf `sessionDao.offeneSessionFlow()` umstellen).
3. Auto-Connect beim App-Start: in `MainActivity.onCreate` bzw. in `AppNavigation` ein
   `LaunchedEffect`, das bei `meterDeviceAddress != null`, vorhandener `BLUETOOTH_CONNECT`-
   Berechtigung und neuem Schalter `autoConnectMeter` die neue Aktion sendet.
   **Owner-Entscheidung 25.09.2026: Schalter einführen, Default an.**
4. Der Bluetooth-Badge löst bei gepinntem Gerät diese Aktion aus statt des Scan-Dialogs; der
   Scan bleibt über „Anderes Gerät koppeln" im Messgerät-Screen erreichbar.

*State-Modell*

```
verbindung   : ConnectionState              (vorhanden)
messungLaeuft: Boolean  = offeneSession != null
wavLaeuft    : Boolean  = AudioRecordingService.audioAufnahmeAktiv   (vorhanden)
```

*Persistenzänderung:* ein neuer Boolean `autoConnectMeter` in `SettingsManager`, Default `true` (Owner-Entscheidung 25.09.2026).

*Navigation Impact:* keiner (wird in [F-29](#f-29) separat behandelt).

*Migration/Rückwärtskompatibilität:* Der neue Schalter braucht einen sinnvollen Default; der
Foreground-Service-Typ wird bereits nachträglich um `connectedDevice` erweitert
(`aktualisiereForegroundServiceTypeFallsNoetig`, `:445`), das funktioniert auch für einen Start
ohne Mikrofon. **Achtung:** Startet der Service ohne `RECORD_AUDIO`, ist
`berechneForegroundServiceType()` ggf. `0` – der Fallback `startForeground(id, notification)`
ohne Typ greift bereits (`:409`, `:423`), sollte aber in einem Gerätetest bestätigt werden.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `test/.../audio/AudioStartPolicyTest.kt` | erweitern um den Nur-Verbindung-Fall |
| `test/.../audio/AudioRecordingServiceStartupTest.kt` | **muss angepasst werden** – neue Aktion |
| `test/.../ui/ServiceControlComposeTest.kt`, `androidTest/.../ServiceControlInstrumentedTest.kt` | prüfen Start/Stop über das Cockpit → anpassen |
| `test/.../ui/MicrophoneCockpitRegressionTest.kt` | prüft genau die Trennung „Dienst läuft" vs. „Audio aktiv" → wahrscheinlich anpassen |
| `androidTest/.../service/ForegroundServiceAndroidTest.kt` | Service-Typ ohne Mikrofon prüfen |
| **Neu** | „App-Start verbindet gepinntes Gerät, eröffnet aber keine Session" |

---

<a id="f-03"></a>
### F-03 · `ConnectionState.FAILED` ist eine Sackgasse · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Nach acht Fehlversuchen (rund zwei Minuten) meldet der Supervisor
`FAILED` und beendet seine Coroutine. Danach wird **nie wieder** automatisch verbunden, auch
wenn das Gerät Sekunden später wieder in Reichweite ist. Der Badge zeigt „PCE-323: Fehler". Ein
Tap darauf öffnet den Kopplungsdialog, der einen 10-Sekunden-Scan startet.

*User Problem:* Bei einer Nachtmessung heißt das: Das Gerät fällt um 02:00 Uhr kurz aus, die App
gibt um 02:02 Uhr auf, und der Rest der Nacht wird unkalibriert oder gar nicht gemessen – ohne
dass jemand etwas davon merkt, solange die Alarmierung nicht aktiv ist (Default `false`).

*Betroffener Use Case:* Dauermessung. *Häufigkeit:* mittel, aber mit hohem Schaden.

*Zielverhalten:* Nach `FAILED` wird in großem Abstand (z. B. alle 5–15 Minuten) weiter versucht,
und die UI bietet an der Stelle des Problems einen „Erneut verbinden"-CTA an.

*UX-Begründung:* Aufgeben ist in einer Dauermessung nie die richtige Voreinstellung. Der
Backoff verhindert Akkuverbrauch; ein *Stopp* verhindert die Messung.

| Kennzahl | Heute | Danach |
|---|---|---|
| Automatische Erholung nach Ausfall | nie | ja |
| Taps zur manuellen Erholung | 2 + 10 s Scan | 1, ohne Scan |

**B – Technische Perspektive**

*Betroffener Code:* `ConnectionSupervisor.supervise` (`meter/ConnectionSupervisor.kt:163`),
insbesondere `return@coroutineScope` nach `setOverride(ConnectionState.FAILED)` (`:213-221`);
`maxAttempts` (`:78`); `backoffDelayMillis` (`:419`); UI: `BluetoothStatusBadge`
(`ui/BluetoothStatusBadge.kt:40`), `MeterScreen` „Verbinden" (`:371`).

*Aktuelle Implementierung:* Die Reconnect-Schleife bricht bei `consecutiveFailures >= maxAttempts`
endgültig ab. `start()` würde neu starten, wird aber nur aus `onStartCommand` gerufen.

*Vorgeschlagene Änderung*

1. Statt `return@coroutineScope` in einen **Ruhemodus** wechseln: `FAILED` setzen, dann mit einem
   langen, konstanten Intervall (neuer Parameter `retryAfterFailure: Duration = 10 min`)
   weiterversuchen, ohne den Zähler zurückzusetzen. Die Coroutine bleibt aktiv, `stop()` beendet
   sie weiterhin sauber.
2. Im Badge einen sekundären CTA „Erneut verbinden" anbieten, wenn `state == FAILED` – er ruft
   `connectionSupervisor.start(device)` mit dem gepinnten Gerät (kein Scan).
3. `DEGRADED` und `FAILED` in der Selbstprüfung mit `HealthActionType.CONNECT_METER` versehen
   (vorhanden, siehe [F-05](#f-05)).

*State-Modell:* `ConnectionState` bleibt unverändert; nur die Übergangslogik ändert sich.

*Persistenzänderung / Navigation Impact / Migration:* keine.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `test/.../meter/ConnectionSupervisorTest.kt` | **muss angepasst werden** – Tests, die „nach 8 Versuchen FAILED und Ende" prüfen, ändern ihre Erwartung auf „FAILED und weiterer Versuch nach `retryAfterFailure`" |
| `test/.../meter/FakeMeterTransportTest.kt` | unverändert |
| **Neu** | „nach FAILED folgt ein weiterer Versuch"; „`stop()` beendet auch den Ruhemodus" |
| `androidTest/.../ui/BluetoothStatusBadgeInstrumentedTest.kt` | erweitern um den CTA im FAILED-Zustand |

---

<a id="f-04"></a>
### F-04 · System-Selbstprüfung ist teilweise blind · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* `bewerteSystemZustand` bekommt an beiden Aufrufstellen fest verdrahtete
Werte: In `MainActivity` sind `hasBluetoothPermission`, `canScheduleExactAlarms` und
`isBluetoothAdapterEnabled` konstant `true`; in `DiagnoseScreen` ist die BT-Berechtigung echt,
Adapterzustand und exakte Alarme sind ebenfalls konstant `true`. Der Problem-Banner auf der
Startseite kann deshalb nie melden, dass Bluetooth am Gerät ausgeschaltet ist oder die
BT-Berechtigung fehlt.

*User Problem:* Genau die zwei häufigsten Gründe für „das PCE verbindet nicht" (Bluetooth aus,
Berechtigung entzogen) sind die zwei, die die Selbstprüfung nicht sehen kann. Der Nutzer sucht
den Fehler beim Gerät.

*Betroffener Use Case:* Bluetooth-Verbindung, Dauermessung. *Häufigkeit:* mittel bis hoch.

*Zielverhalten:* Die Selbstprüfung liest die realen Werte und meldet Adapterzustand und
Berechtigung mit passender Schwere und Aktion.

*UX-Begründung:* Eine Diagnose, die einen Teil der Wahrheit per Konstante ausblendet, ist
schlimmer als keine – sie erzeugt falsches Vertrauen.

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Logik | `bewerteSystemZustand`, `SystemHealthParams` (`diagnose/SystemHealthChecker.kt:33`, `:57-225`) |
| Aufrufer 1 | `NoiseProtocolApp` (`ui/MainActivity.kt:519-537`) – `hasBluetoothPermission = true` (`:524`), `canScheduleExactAlarms = true` (`:526`), `isBluetoothAdapterEnabled = true` (`:527`) |
| Aufrufer 2 | `DiagnoseScreen` (`ui/DiagnoseScreen.kt:132-151`) – `canScheduleExactAlarms = true` (`:139`), `isBluetoothAdapterEnabled = true` (`:140`) |
| Vorhandene Quelle | `BluetoothAdapterStateObserver` (`meter/ble/BluetoothAdapterStateObserver.kt:25`), verdrahtet in `AppContainer.kt:59` als **`private val`** |
| Vorhandene Quelle | `BluetoothPermissions.hasScanPermission/hasConnectPermission` (`meter/ble/BluetoothPermissions.kt`) |
| Vorhandene Quelle | `exakteAlarmeErlaubtOverride` + echte Abfrage in `SettingsScreen.kt` (Parameter `:116`, Auswertung `:401`) |

*Aktuelle Implementierung:* Die Logik ist vollständig und unit-getestet
(`test/.../diagnose/SystemHealthCheckerTest.kt`); nur die Eingaben sind an zwei Stellen
hartkodiert.

*Vorgeschlagene Änderung*

1. `bluetoothAdapterStateObserver` in `AppContainer` von `private` auf `val` heben (der
   `StateFlow<Boolean> enabled` existiert bereits und ist getestet:
   `test/.../meter/ble/BluetoothAdapterStateObserverTest.kt`).
2. In beiden Aufrufstellen echte Werte übergeben:
   `hasBluetoothPermission = BluetoothPermissions.hasScanPermission(context)`,
   `isBluetoothAdapterEnabled = container.bluetoothAdapterStateObserver.enabled.collectAsState().value`,
   `canScheduleExactAlarms = alarmManager.canScheduleExactAlarms()` (API 31+, sonst `true`) –
   analog zu dem bereits vorhandenen `exakteAlarmeErlaubtOverride`-Muster in `SettingsScreen`.
3. Den `remember(...)`-Schlüssel des `healthOverview` um die neuen Werte erweitern, sonst
   aktualisiert sich die Anzeige nicht.

*Persistenzänderung / Migration:* keine. *Navigation Impact:* keiner.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `test/.../diagnose/SystemHealthCheckerTest.kt` | unverändert (Logik bleibt gleich) |
| `test/.../ui/DiagnoseScreenComposeTest.kt`, `androidTest/.../DiagnoseScreenInstrumentedTest.kt` | können brechen, wenn sich die Anzahl der Selbstprüfzeilen ändert (der Adapter-Eintrag erscheint nur bei gepinntem Gerät) → prüfen |
| `test/.../ui/HomeNavigationComposeTest.kt` | prüfen, ob der Problem-Banner jetzt in mehr Fällen erscheint |
| **Neu** | „Adapter aus ⇒ WARNING-Zeile mit Aktion", analog den vorhandenen Fällen |

> Hinweis: `SystemHealthParams` sollte dabei einen Test-Seam für `canScheduleExactAlarms`
> bekommen (wie `exakteAlarmeErlaubtOverride` in `SettingsScreen`), sonst ist der Fall auf dem
> CI-Emulator nicht erzwingbar – dieselbe Begründung, die dort schon im KDoc steht.

---

<a id="f-05"></a>
### F-05 · Alle Aktionsknöpfe der Selbstprüfung öffnen denselben System-Screen · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Die Selbstprüfung liefert je Eintrag ein `actionLabel` („Erteilen",
„Aktivieren", „Deaktivieren", „Erlauben", „Einschalten", „Verbinden") **und** einen passenden
`HealthActionType`. Die UI ignoriert den Typ und startet für **jeden** Knopf
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

*User Problem:* „Verbinden" öffnet die App-Info. „Deaktivieren" (Akku-Optimierung) öffnet die
App-Info. „Einschalten" (Bluetooth) öffnet die App-Info. Der Nutzer landet dreimal am selben,
meist falschen Ort und muss selbst weitersuchen.

*Betroffener Use Case:* Einrichtung, Fehlersuche. *Häufigkeit:* mittel.

*Zielverhalten:* Jeder Typ führt an sein Ziel; wo es kein Ziel gibt, führt er die Aktion
in-app aus.

| Kennzahl | Heute | Danach |
|---|---|---|
| Taps bis zur Behebung (Beispiel Akku-Optimierung) | 1 + manuelle Suche in den System-Einstellungen | 1 |

**B – Technische Perspektive**

*Betroffener Code:* `DiagnoseScreen.kt:229-240` (der `TextButton`, der `checkItem.actionType`
nicht auswertet); `HealthCheckItem.actionType`, `HealthActionType`
(`diagnose/SystemHealthChecker.kt:11-22`, `:24-31`).

*Aktuelle Implementierung:*

```kotlin
checkItem.actionLabel?.let { label ->
    TextButton(onClick = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)…
        context.startActivity(intent)
    }) { Text(label) }
}
```

*Vorgeschlagene Änderung:* `when (checkItem.actionType)` auswerten und je Fall den passenden
Weg gehen:

| `HealthActionType` | Ziel |
|---|---|
| `REQUEST_AUDIO_PERMISSION`, `REQUEST_BLUETOOTH_PERMISSION`, `REQUEST_NOTIFICATION_PERMISSION` | `rememberLauncherForActivityResult(RequestMultiplePermissions())`; bei dauerhafter Ablehnung Fallback auf App-Info |
| `BATTERY_OPTIMIZATION` | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (die Berechtigung ist im Manifest vorhanden; der Weg existiert bereits in `SettingsScreen.kt:2231-2249`) |
| `EXACT_ALARM_PERMISSION` | `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31+) – Muster vorhanden in `SettingsScreen.kt:853-861` |
| `ENABLE_BLUETOOTH` | `Settings.ACTION_BLUETOOTH_SETTINGS` |
| `CONNECT_METER` | `container.connectionSupervisor.start(BoundDevice(...))` bzw. Navigation zum Messgerät-Screen |
| `CONFIGURE_ALERTING`, `CONFIGURE_DRIVE`, `OPEN_SETTINGS` | Navigation in den passenden Einstellungs-Tab (`settings?tab=…`) |

*Navigation Impact:* `DiagnoseScreen` braucht dafür zwei neue Callback-Parameter
(`onNavigateToSettings(tab)`, `onNavigateToMeter`) – `MainActivity` gibt sie bereits an
`SettingsScreen` weiter, das Muster existiert.

*Persistenzänderung / Migration:* keine.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `androidTest/.../ui/DiagnoseScreenInstrumentedTest.kt` | erweitern: je Aktionstyp wird der erwartete Intent ausgelöst (über `Intents.intended`) |
| `test/.../ui/DiagnoseScreenComposeTest.kt` | Callback-Aufrufe prüfen statt Intents |
| `test/.../diagnose/SystemHealthCheckerTest.kt` | unverändert |

---

<a id="f-06"></a>
### F-06 · Messdauer und Betriebsart hängen an der letzten statt der offenen Session · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Das Cockpit berechnet die Laufzeit aus `letzteSession.startedAt`, sobald
`dienstAktiv` wahr ist – ohne zu prüfen, ob diese Session noch läuft. Dieselbe Quelle bestimmt
über `istMikrofonMessung`, ob „Leq (Mikrofon, unkalibriert)" oder „LAeq" angezeigt wird.

*Reproduzierbarer Fall:* Messung starten (Mikrofon-Session ab 09:00) → Mikrofon-Badge antippen →
„WAV beenden". Die Mikrofon-Session wird geschlossen (`AudioRecordingService.kt:309-330`), der
Dienst läuft weiter, `dienstAktiv` bleibt `true`. Der Timer im Cockpit zählt ab jetzt weiterhin
seit 09:00, obwohl keine Messung mehr läuft.

*User Problem:* Die angezeigte Messdauer ist in dieser Situation frei erfunden. Für ein
Beweiswerkzeug ist das die falsche Art von Fehler.

*Betroffener Use Case:* Standard-Workflow, Nur-Bluetooth-Variante, WAV pausieren.
*Häufigkeit:* mittel.

*Zielverhalten:* Laufzeit nur, wenn eine Session offen ist; sonst „Bereit" bzw. „Verbunden,
keine Messung".

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Composable | `LiveCockpitCard` (`ui/LiveCockpitCard.kt:95`) |
| State | `letzteSession` (`:115`, aus `sessionDao.letzteSessionFlow()`), `dienstAktiv` (`:109`) |
| Berechnung | `istMikrofonMessung` (`:195`), `sessionStartTime`/`elapsedSeconds`/`timerString` (`:211-213`) |
| **Vorhandene, getestete Lösung** | `leiteDashboardAnzeigeAb(dienstAktiv, geraetGepinnt, verbindungszustand, sessionStartedAtMillis, jetztMillis, letzterPegel)` in `messreihe/DashboardStatus.kt:24` – der KDoc nennt genau diesen Fall: „Nur eine laufende Session hat eine Laufzeit" |
| Test dazu | `test/.../messreihe/DashboardStatusTest.kt` |

*Vorgeschlagene Änderung:* `letzteSession` im Cockpit durch `sessionDao.offeneSessionFlow()`
ersetzen (oder zusätzlich beobachten) und `sessionStartedAtMillis` nur aus einer offenen Session
ableiten. Die Anzeige über `leiteDashboardAnzeigeAb` erzeugen, statt die Logik erneut inline zu
schreiben. Für Chart und Kennwerte bleibt `letzteSession` richtig – eine beendete Messung soll
dort weiter sichtbar sein (die Chart-/Kennwertlogik hängt bewusst daran, `:165-190`).

*State-Modell*

```
offeneSession  : SessionEntity?   → Laufzeit, Betriebsart, "Messung läuft"
letzteSession  : SessionEntity?   → Chart, LAeq/LAmax, Ausfallbänder  (unverändert)
```

*Persistenzänderung / Navigation Impact / Migration:* keine.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `test/.../ui/LiveCockpitCardTest.kt`, `LiveCockpitFensterTest.kt` | prüfen; `berechneDbFensterAb` bleibt unverändert |
| `test/.../ui/MicrophoneCockpitRegressionTest.kt` | betrifft genau diese Unterscheidung → wahrscheinlich anzupassen |
| `test/.../messreihe/DashboardStatusTest.kt` | wird endlich produktiv genutzt; ggf. um den Fall „Dienst läuft, keine offene Session" erweitern |
| **Neu** | Regressionstest „WAV beenden ⇒ Timer zeigt keine Laufzeit mehr" |

---

<a id="f-07"></a>
### F-07 · Berichts-Vorprüfung läuft erst nach dem Klick · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Alle vier Vorprüfungen (`retentionFehler`, `bewertungsFehler`,
`auswahlFehler`, `areaSelectionError`) laufen innerhalb von `erzeugen()`, also erst beim Druck
auf „Bericht jetzt erzeugen". Der Knopf ist bis dahin aktiv und verspricht einen Bericht.

*User Problem:* Der Nutzer wählt einen Zeitraum, wartet auf das Laden, drückt – und bekommt eine
Absage. Im häufigsten Fall (fehlende Gebietseinstufung) muss er die App-Einstellungen aufsuchen
und danach den gesamten Datumsdialog wiederholen, weil der Zeitraum nicht persistiert ist.

*Betroffener Use Case:* High-End-Bericht. *Häufigkeit:* **hoch** beim ersten Mal, danach mittel.

*Zielverhalten:* Das Sheet zeigt eine Live-Checkliste der Voraussetzungen. Der Knopf ist
gesperrt, solange ein Blocker besteht, mit der Begründung direkt darunter. Jeder Blocker ist
möglichst direkt im Sheet lösbar.

| Kennzahl | Heute | Danach |
|---|---|---|
| Taps im Fehlerfall Gebietseinstufung | 32 | 8 |
| Wiederholte Datumsauswahl | 1× zusätzlich | 0 |

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Composable | `BerichtErstellenSheet` (`ui/BerichtErstellenSheet.kt:74`) |
| Funktion | `erzeugen()` (`:109-140`) |
| Prüfungen | `retentionFehler` (`report/BerichtErstellung.kt:57`), `bewertungsFehler` (`:65`), `auswahlFehler` (`:79`), `areaSelectionError` (`report/ReportArea.kt:26`), `fehlendeStammdatenFelder` (`report/BerichtErstellung.kt:89`) |
| State | `zeitraum` (`:85`), `tage` (`:86`), `config` (`:87`), `ausgewaehlteIds` (`:88`) |
| Button | `:240-243` (`enabled = !erzeugt && !laedt`) |

*Aktuelle Implementierung:* Die Prüfungen sind bereits reine Funktionen über genau die Werte,
die das Sheet schon im Zustand hält. Sie werden nur zu spät aufgerufen.

*Vorgeschlagene Änderung*

```kotlin
data class Voraussetzung(val text: String, val erfuellt: Boolean, val aktion: (() -> Unit)? = null)

val voraussetzungen = remember(zeitraum, tage, config, ausgewaehlteIds) {
    val c = config
    listOf(
        Voraussetzung("Zeitraum gewählt", zeitraum != null),
        Voraussetzung("Rohdaten vorhanden", tage.any { it.rohwerte > 0 }),
        Voraussetzung("Rohdaten nicht verdichtet", retentionFehler(tage) == null),
        Voraussetzung("A-/Zeitbewertung geklärt", c != null && bewertungsFehler(tage, c) == null),
        Voraussetzung("Stammdaten je Tag eindeutig", auswahlFehler(tage, ausgewaehlteIds) == null),
        Voraussetzung("Gebietseinstufung gesetzt", c != null && areaSelectionError(c.gebietseinstufung) == null),
    )
}
val blockiert = voraussetzungen.any { !it.erfuellt }
```

Die Liste wird über dem Button gerendert (✓/✗ + Text, nicht nur Farbe), der Button bekommt
`enabled = !erzeugt && !laedt && !blockiert`, und der erste unerfüllte Punkt steht als
Begründung darunter. `erzeugen()` behält seine Prüfung als Sicherheitsnetz.

*State-Modell:* nur abgeleiteter Zustand, kein neuer persistenter Zustand.

*Persistenzänderung / Navigation Impact / Migration:* keine (siehe aber [F-08](#f-08) für die
Inline-Lösung der Gebietseinstufung).

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `test/.../ui/BerichtErstellenSheetTest.kt` | **muss angepasst werden** – prüft heute die Fehlermeldung nach dem Klick |
| `androidTest/.../ui/BerichtErstellenSheetInstrumentedTest.kt` | dito |
| `test/.../report/BerichtErstellungTest.kt`, `ReportAreaTest.kt`, `UnbestaetigteBewertungHinweisTest.kt` | unverändert – die Logik ändert sich nicht |
| **Neu** | „Knopf ist gesperrt, solange die Gebietseinstufung fehlt, und nennt den Grund" |

---

<a id="f-08"></a>
### F-08 · Gebietseinstufung blockiert den Bericht, ist im Berichtsflow aber unsichtbar · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* `ReportConfigEntity.gebietseinstufung` ist per Default `""`.
`areaSelectionError("")` blockiert jeden High-End-Bericht. Im Berichts-Sheet wird der Wert
weder angezeigt noch gesetzt; er liegt in Einstellungen → Bericht → „Berichtsparameter
(§ 287 ZPO)" hinter einer eingeklappten Sektion.

*User Problem:* Der Nutzer erfährt von der Pflichtangabe erst, wenn der Bericht scheitert, und
muss den Ort der Einstellung raten. Für nicht-technische Nutzer (Mieter) ist „Gebietseinstufung
nach AVV Baulärm" zudem kein Begriff, den man im Einstellungsmenü sucht.

*Betroffener Use Case:* High-End-Bericht. *Häufigkeit:* einmal je Installation zwingend, danach
bei jedem Gebietswechsel.

*Zielverhalten:* Die Gebietseinstufung steht als Zeile im Berichts-Sheet, mit dem aktuellen Wert
und der Möglichkeit, sie direkt dort zu setzen. Gebiete ohne geprüfte Richtwerte
(`hasVerifiedLimits = false`) bleiben sichtbar gesperrt, mit der bereits vorhandenen Begründung.

**B – Technische Perspektive**

*Betroffener Code:* `ReportArea` / `areaSelectionError` (`report/ReportArea.kt:8-23`, `:26-33`),
`ReportAreaSelection` (`ui/ReportAreaSelection.kt:23`), Einbindung in `SettingsScreen.kt:1790-1794`,
`ReportConfigEntity.gebietseinstufung` (`data/ReportConfigEntity.kt:48`),
`ReportConfigDao.speichere`.

*Vorgeschlagene Änderung:* `ReportAreaSelection` ist bereits eine eigenständige, parameterisierte
Composable mit eigenem Test (`test/.../ui/ReportConfigSettingsTest.kt`,
`androidTest/.../ReportConfigSettingsInstrumentedTest.kt`). Sie lässt sich unverändert in
`BerichtErstellenSheet` einsetzen: `value = config?.gebietseinstufung ?: ""`, `onSelect = {
scope.launch { db.reportConfigDao().speichere(config!!.copy(gebietseinstufung = it)); ladezahl++ } }`.

*Persistenzänderung:* keine – dieselbe Singleton-Zeile. *Navigation Impact:* entfällt gerade
(kein Wechsel in die Einstellungen mehr nötig). *Migration:* keine.

*Test-Auswirkung:* `test/.../ui/BerichtErstellenSheetTest.kt` erweitern; `ReportAreaTest.kt` und
`ReportConfigSettingsTest.kt` unverändert. **Neu:** „Auswahl im Sheet wird gespeichert und
entsperrt den Knopf".

---

<a id="f-09"></a>
### F-09 · Stiller Ausfall und Audio-Soll/Ist-Abweichung erscheinen nur in der Notification · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Zwei Wächter erkennen genau die Fälle, in denen die App scheinbar misst,
aber nichts aufzeichnet: `pruefeStillenAusfall` („Seit N Min. über der Schwelle, aber keine
Ereignisse – …") und `pruefeAudioSollIstAbweichung` („WAV-/Mikrofon-Aufzeichnung unerwartet
inaktiv"). Beide setzen `stillerAusfallHinweis`, der ausschließlich in den Text der
Foreground-Notification geschrieben wird.

*User Problem:* Wer die App offen hat, sieht ein normal aussehendes Cockpit mit Live-Pegel.
Dass seit 40 Minuten kein Ereignis mehr gespeichert wird, steht nur in der Benachrichtigungsleiste
– die bei laufender Dauermessung gern ausgeblendet oder überlesen wird.

*Betroffener Use Case:* Dauermessung. *Häufigkeit:* selten, aber der Schaden ist total (keine
Beweisdaten).

*Zielverhalten:* Derselbe Hinweis erscheint als Warnbanner im Cockpit, mit der vorhandenen
Ursachenangabe und einer Aktion („Aufzeichnung neu starten" / „Diagnose öffnen").

**B – Technische Perspektive**

*Betroffener Code:* `AudioRecordingService` – `stillerAusfallHinweis` (`:1062`, `@Volatile`), `pruefeStillenAusfall` (`:963-990`),
`pruefeAudioSollIstAbweichung` (`:932-961`), Verwendung in `buildNotification` (`:489`, `:495`).
Anzeige-Ziel: `LiveCockpitCard` bzw. der vorhandene Problem-Banner in `MainActivity.kt:681-724`.

*Aktuelle Implementierung:* `stillerAusfallHinweis` ist ein privates Feld des Service ohne Flow.

*Vorgeschlagene Änderung:* Das Feld in einen `companion`-`StateFlow<String?>` umwandeln – exakt
das Muster, das der Service für `laeuft`, `audioAufnahmeAktiv` und `currentMicDb` bereits
verwendet (`:69-79`, inklusive der dort vorhandenen `internal fun testSetze…`-Seams). Cockpit und
Problem-Banner lesen ihn per `collectAsState()`.

*State-Modell*

```
AudioRecordingService.aufzeichnungsHinweis: StateFlow<String?>   (neu, null = kein Problem)
```

*Persistenzänderung / Navigation Impact / Migration:* keine.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `test/.../audio/TriggerWachhundTest.kt` | unverändert (die Erkennung selbst ändert sich nicht) |
| `test/.../audio/NotificationTextTest.kt` | prüfen – der Notification-Text bleibt gleich |
| `test/.../ui/LiveCockpitCardTest.kt` | erweitern: gesetzter Hinweis ⇒ Banner sichtbar (der Test-Seam existiert bereits als Muster) |
| **Neu** | „Hinweis wird zurückgesetzt, sobald die Aufzeichnung wieder läuft" (die Rücksetzlogik existiert, `:620`, `:936-940`) |

---

<a id="f-10"></a>
### F-10 · Kein Speicherplatz-Check vor dem Start einer Messung · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Die Videoaufnahme prüft vor dem Start den freien Platz und bricht mit
einer klaren Meldung ab. Die Messung selbst prüft nichts. Bei vollem Speicher schlägt der
WAV-Schreibvorgang zur Laufzeit fehl; dafür existiert inzwischen ein begrenzter automatischer
Neustart, aber keine Nutzerinformation.

*User Problem:* Eine Nachtmessung beginnt scheinbar normal und liefert am Morgen Lücken oder gar
nichts. Der Grund ist zu diesem Zeitpunkt nicht mehr sichtbar.

*Betroffener Use Case:* Standard-Workflow, Dauermessung. *Häufigkeit:* niedrig bis mittel (die
App speichert WAV + Video + DB + Fotos, das summiert sich schnell).

*Zielverhalten:* Vor dem Start wird der freie Platz geprüft und – falls knapp – als
**Warnung, nicht als Blockade** angezeigt, mit direktem Weg zu „Speicher freigeben".

*UX-Begründung:* Eine zusätzliche Interaktion ist hier gerechtfertigt (Auftrag Kap. 31): Sie
verhindert einen Fehler, der erst Stunden später sichtbar wird und nicht reparabel ist.

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Vorbild | `VideoAufnahmeScreen.kt:412-417`, `video/Videospeicher.reichtSpeicher` |
| Vorhandene Logik | `ermittleSpeicherbelegung` (`messreihe/Speicheraufraeumer.kt:141`), `Speicherbelegung.freiBytes` |
| Ziel | `LiveCockpitCard` Startknopf (`:611-641`), `AudioRecordingService.startMonitoring` (`:510`) |
| Aufräumweg | Einstellungen → Daten → „Speicherplatz" (`SettingsScreen.kt:1905`) |

*Vorgeschlagene Änderung:* Eine reine Funktion `reichtSpeicherFuerMessung(freiBytes, sampleRate,
bytesPerSample, mindestStunden): Boolean` neben `Videospeicher` anlegen (JVM-testbar), sie in der
Messbereitschaftszeile auswerten und den Startknopf mit einer Warnzeile versehen. Zusätzlich im
Service beim Start einen Diagnoseeintrag schreiben, wenn der Platz knapp ist.

*Persistenzänderung / Migration:* keine. *Navigation Impact:* Link in den Speicher-Abschnitt.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| **Neu (JVM)** | `reichtSpeicherFuerMessung` analog `test/.../video/VideospeicherTest.kt` |
| `test/.../messreihe/SpeicheraufraeumerTest.kt`, `SpeicherplatzUebersichtTest.kt` | unverändert |
| **Neu (Compose)** | „knapper Speicher ⇒ Warnzeile über dem Startknopf, Knopf bleibt aktiv" |

---

<a id="f-11"></a>
### F-11 · Mikrofon-Initialisierungsfehler bleibt in der App unsichtbar · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Scheitert `AudioRecord` (Konstruktor, `STATE_INITIALIZED`,
`startRecording()`), wird ein Diagnoseereignis geschrieben und der Dienst per `stopSelf()`
beendet. In der App erscheint **keine** Meldung; der Badge springt auf „WAV: INAKTIV" und der
Startknopf kommt zurück.

*User Problem:* Der Nutzer drückt „Messung starten", und es passiert scheinbar nichts. Der
häufigste reale Grund (Mikrofon von einer anderen App belegt, z. B. Telefonat oder
Sprachassistent) ist behebbar – aber nur, wenn man ihn kennt.

*Betroffener Use Case:* Standard-Workflow. *Häufigkeit:* niedrig bis mittel.

*Zielverhalten:* Eine Fehlermeldung mit Ursache und nächstem Schritt („Mikrofon ist von einer
anderen App belegt – beenden und erneut versuchen" / „Aufnahme konnte nicht gestartet werden ·
Diagnose öffnen").

**B – Technische Perspektive**

*Betroffener Code:* `AudioRecordingService.startMonitoring` (`:510`), drei Fehlerpfade
(`:556-572`, `:574-590`, `:592-607`), jeweils mit `DiagnosticCode.AUDIO_INIT_FAILED`.

*Vorgeschlagene Änderung:* Denselben neuen `StateFlow<String?>` benutzen wie in
[F-09](#f-09) (`aufzeichnungsHinweis`) und ihn in diesen drei Pfaden mit einem sprechenden Text
setzen, bevor `stopSelf()` gerufen wird. Der Cockpit-Banner zeigt ihn an. Zusätzlich sinnvoll:
den Text aus dem bereits vorhandenen `DiagnosticCode` ableiten, damit Diagnoseprotokoll und
Anzeige nicht auseinanderlaufen.

*Persistenzänderung / Navigation Impact / Migration:* keine.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| `test/.../audio/AudioRecordingServiceStartupTest.kt` | erweitern: Fehlerpfad setzt den Hinweis |
| `test/.../audio/AudioMonitoringRestartPolicyTest.kt` | unverändert |
| **Neu (Compose)** | „gesetzter Hinweis ⇒ Fehlerbanner mit Aktion" |

---

<a id="f-12"></a>
### F-12 · Messintegrität ist erfasst, aber nirgends dargestellt · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Jede beendete Session trägt im Protokoll das Badge „Abgeschlossen" –
allein abgeleitet aus `endedAt != null`. Ausfälle sind nur im Detail sichtbar (Chart-Bänder,
Audit-Zeile „Verbindungsausfälle: N Vorfälle", Liste am Ende). Datenverfügbarkeit in Prozent,
GAP-Flags, unbestätigte Bewertung und bereits verdichtete Rohdaten erscheinen in der UI gar
nicht.

*User Problem:* Die Kernfrage des Nutzers („Kann ich mit dieser Messung vor Gericht gehen?")
wird nicht beantwortet, obwohl die App alle Daten dafür hat. Schlimmer: „Abgeschlossen" liest
sich wie eine Qualitätsaussage.

*Betroffener Use Case:* Use-Case D, High-End-Bericht. *Häufigkeit:* **hoch** – bei jeder
Sichtung.

*Zielverhalten:* Ein Integritätsstatus („Vollständig / Eingeschränkt / Lückenhaft") mit
Kennzahl, an drei Stellen gleich: Session-Karte, Detail-Kopf, Abschlusskarte nach dem Messende.
Darstellung wie in [Kapitel 20.2](#202-vorschlag-für-die-darstellung).

| Kennzahl | Heute | Danach |
|---|---|---|
| Taps bis zur Aussage „verwertbar?" | 3 + Scrollen, und dann nur indirekt | 0 |

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Vorhandene Logik | `berechneDatenverfuegbarkeitProzent` (`messreihe/Datenverfuegbarkeit.kt:14`), `leiteAusfallbaenderAb` (`messreihe/Ausfallbaender.kt:27`), `zaehleReconnects` (`messreihe/Reconnects.kt:16`) |
| Vorhandene Daten | `connection_events`, `MeasurementFlags.GAP` (`data/SessionEntity.kt:107`, Doku `:113-134`), `MeasurementDao.anzahlUnbestaetigtZwischen` (`data/SessionDao.kt:91`), `MinuteAggregateDao.anzahlZwischen` |
| Schwellen | `ReportConfigEntity.tierSchwelleVollmessungProzent` / `…TeilerfassungProzent` (`data/ReportConfigEntity.kt:45-46`) |
| UI | `ProtokollScreen.ModernSessionCard` (`:399`), `ProtokollDetailScreen` Kopfkarte (`:289-321`), `LiveCockpitCard` |

*Vorgeschlagene Änderung*

1. Neue reine Funktion in `messreihe/`, z. B.

```kotlin
enum class Messintegritaet { VOLLSTAENDIG, EINGESCHRAENKT, LUECKENHAFT }

data class Integritaetsbefund(
    val stufe: Messintegritaet,
    val verfuegbarkeitProzent: Double,
    val ausfaelle: Int,
    val ausfallDauerMs: Long,
    val rohdatenVerdichtet: Boolean,
    val unbestaetigteWerte: Int,
)

fun bewerteMessintegritaet(
    von: Long, bis: Long,
    ausfallbaender: List<Ausfallband>,
    gapAnzahl: Int,
    verdichteteMinuten: Int,
    unbestaetigteWerte: Int,
    config: ReportConfigEntity,
): Integritaetsbefund
```

   Voll JVM-testbar, ohne Android-Abhängigkeit – dasselbe Muster wie `bewerteSystemZustand` und
   `leiteDashboardAnzeigeAb`.
2. Zwei neue DAO-Abfragen: Anzahl der Zeilen mit gesetztem `GAP`-Flag je Session
   (`WHERE sessionId = :id AND (flags & 8) != 0`) und Anzahl verdichteter Minuten je Session
   (die zeitraumbezogene Variante existiert bereits).
3. Anzeige an den drei genannten Stellen, Text **und** Icon (nie nur Farbe).

*Persistenzänderung:* keine Schemaänderung, nur neue `@Query`-Methoden.
*Navigation Impact:* keiner. *Migration:* keine.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| **Neu (JVM)** | `bewerteMessintegritaet` – Grenzfälle an den beiden Tier-Schwellen |
| **Neu (JVM)** | DAO-Tests analog `test/.../data/MeasurementDaoTest.kt` |
| `test/.../messreihe/DatenverfuegbarkeitTest.kt`, `AusfallbaenderTest.kt` | unverändert |
| `test/.../ui/ProtokollScreenTest.kt`, `androidTest/.../ProtokollScreenInstrumentedTest.kt` | **anpassen** – Session-Karte bekommt eine neue Zeile |
| `test/.../ui/ProtokollDetailScreenComposeTest.kt` | anpassen |

---

<a id="f-13"></a>
### F-13 · Drive-Status nicht im Hauptfluss, kein Retry je Datei · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Der Sync-Status steht ausschließlich in `SettingsScreen` (Tab „Daten",
eingeklappte Sektion) und in `DiagnoseScreen`. Fünf Taps trennen den Nutzer von der Antwort auf
„Sind meine Beweisdaten in der Cloud?". `DriveUploadScreen` listet fehlgeschlagene Uploads, bietet
aber keine Wiederholung.

*User Problem:* Nach einem Datenverlust durch Deinstallation (dokumentierter Anlass für die
DB-Sicherung, README) ist die Cloud-Kopie das Sicherheitsnetz – und ihr Zustand ist der am
schlechtesten erreichbare in der App.

*Betroffener Use Case:* Use-Case C. *Häufigkeit:* mittel.

*Zielverhalten:* Eine einzeilige Sync-Zusammenfassung dort, wo Beweisdaten entstehen bzw.
betrachtet werden (Messbereitschaft, Protokoll-Detail), und je Datei bzw. je Fehlerzustand ein
„Erneut versuchen".

| Kennzahl | Heute | Danach |
|---|---|---|
| Taps bis zum Sync-Status | 4 | 0 |
| Taps bis „erneut hochladen" | 5 | 1 |

**B – Technische Perspektive**

*Betroffener Code:* `DriveStatusCard` (`ui/DriveStatusCard.kt:43`), Einbindungen
`SettingsScreen.kt:1305`, `DiagnoseScreen.kt:570`; `DriveUploadScreen`
(`ui/DriveUploadScreen.kt`), `drive/DriveUploadUebersicht.kt` (`UploadEintrag`, `UploadZustand`);
`DriveSyncPlanung.starteSofort` (bereits genutzt in `FotoDokumentationSheet.kt:113`);
`SettingsManager.driveSyncLastSuccessAt`, `driveSyncFehlschlaegeInFolge`, `driveSyncLastMessage`,
`driveOrdnerBlockiert`.

*Vorgeschlagene Änderung*

1. Eine schlanke Composable `DriveSyncZeile` (Text + optionaler CTA), gespeist aus denselben
   `SettingsManager`-Werten, die `DriveStatusCard` schon liest. Einsatz: Messbereitschaftszeile
   und Kopf des Protokoll-Details.
2. In `DriveUploadScreen` bei `UploadZustand.FEHLGESCHLAGEN` einen `TextButton` „Erneut
   versuchen" ergänzen, der `DriveSyncPlanung.starteSofort(context)` auslöst. Der Coordinator
   nimmt ohnehin alle nicht hochgeladenen Dateien erneut mit – es braucht keine
   Einzeldatei-Logik.
3. Optional: In `DriveUploadScreen` einen Link in den Drive-Einstellungsabschnitt, damit
   „Ordner blockiert" dort behebbar ist.

*Persistenzänderung / Migration:* keine. *Navigation Impact:* ein neuer Callback in
`DriveUploadScreen`.

*Test-Auswirkung:* `test/.../ui/DriveStatusCardTest.kt` und
`androidTest/.../DriveStatusCardInstrumentedTest.kt` unverändert;
`androidTest/.../DriveUploadScreenInstrumentedTest.kt` erweitern („Retry-Knopf erscheint bei
FEHLGESCHLAGEN"); **neu**: Compose-Test für `DriveSyncZeile`.

---

<a id="f-14"></a>
### F-14 · Onboarding erscheint bei einer Neuinstallation nicht · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* `SettingsManager.onboardingCompleted` hat den Default `true`. Die
Bedingung `if (!onboardingDone || onboardingErneutAnzeigen)` in `AppNavigation` ist bei einer
frischen Installation also nie erfüllt. Der vollständig implementierte, getestete
`OnboardingScreen` (4 Seiten) ist nur über Einstellungen → „Einführung erneut anzeigen"
erreichbar.

*User Problem:* Neue Nutzer – laut `docs/UX_DESIGN_PLAN.md` primär Mieter ohne technischen
Hintergrund – landen ohne Erklärung auf einem Cockpit mit dB-Werten, Trigger-Quellen und einem
Bluetooth-Badge.

*Betroffener Use Case:* First-Time-Use. *Häufigkeit:* einmal je Installation, aber genau dann am
wichtigsten.

*Zielverhalten:* Onboarding beim ersten Start. Danach nie wieder, außer auf Wunsch.

> **Einordnung:** Ob der Default `true` Absicht war, ist aus dem Code nicht belegbar. Alle
> benachbarten Defaults in `SettingsManager` tragen eine KDoc-Begründung, dieser nicht; die
> Git-Historie hilft nicht, weil der Wert im Initialimport (`d84c837`) entstanden ist. Deshalb
> als **Owner-Frage** markiert, nicht als eindeutiger Bug – AGENTS.md §8a.
>
> **Stand 25.09.2026: weiterhin offen.** Auf die Frage kam eine Rückfrage, keine Entscheidung.
> Zu klären ist nur noch, ob bestehende Installationen die Einführung einmalig nachgeholt
> bekommen sollen; die Erklärung dazu steht in
> [Kapitel 35.2](#352-owner-entscheidungen-vom-25092026).

**B – Technische Perspektive**

*Betroffener Code:* `SettingsManager.onboardingCompleted` (`data/SettingsManager.kt:605-607`),
`AppNavigation` (`ui/MainActivity.kt:160-170`), `OnboardingScreen`
(`ui/OnboardingScreen.kt:33`), Wiederanzeige über `onShowOnboarding` (`SettingsScreen.kt:2256-2268`).

*Vorgeschlagene Änderung:* Default auf `false` setzen. Damit die Umstellung **bestehende**
Installationen nicht erneut durch das Onboarding schickt, den Schlüssel wechseln (z. B.
`onboarding_completed_v2`) und beim ersten Lesen auf `true` setzen, wenn bereits irgendein
anderer Wert im `SharedPreferences`-Satz existiert (z. B. `meterDeviceAddress != null` oder
`monitoringWasActive` gesetzt). Alternativ, einfacher und ohne Heuristik: Default `false` und den
Schlüssel unverändert lassen – Bestandsnutzer sehen das Onboarding dann einmal. Das ist eine
Produktentscheidung, keine technische.

*Persistenzänderung:* ggf. ein neuer Prefs-Schlüssel. *Migration:* nur `SharedPreferences`,
keine Room-Migration.

*Test-Auswirkung:* `test/.../ui/OnboardingScreenComposeTest.kt`,
`androidTest/.../OnboardingScreenInstrumentedTest.kt` unverändert;
`test/.../ui/MainActivityLaunchTest.kt` und `androidTest/.../AppStartupSmokeInstrumentedTest.kt`
**prüfen** – sie starten die App und erwarten heute direkt das Cockpit; mit dem neuen Default
sehen sie zuerst das Onboarding. **Neu**: „frische Installation zeigt das Onboarding, danach
nicht mehr".

---

<a id="f-15"></a>
### F-15 · Stammdaten: jede Speicherung legt eine neue Zeile an, alle 13 Felder werden gleich behandelt · **P2**

**A – UX-Perspektive**

*Aktuelles Verhalten:* „Speichern" schreibt immer eine neue `stammdaten_verlauf`-Zeile, auch
wenn kein Feld geändert wurde. Die Auswahlliste („Andere gespeicherte Angaben wählen (N)") füllt
sich mit identischen Einträgen. Sobald ein Kalendertag mehr als eine Zeile hat, verlangt der
High-End-Bericht eine explizite Auswahl **pro Tag**, bevor er überhaupt startet.

*User Problem:* Ein Verhalten, das als Komfort gedacht war (Verlauf der letzten 10), erzeugt bei
normaler Nutzung Mehrarbeit an einer ganz anderen Stelle – im Berichts-Sheet, Tage später.
Zusätzlich werden zeitgebundene Felder (Kalibrierung, Wetter, Datenqualitätshinweis) unverändert
in die nächste Messung übernommen, was fachlich falsch sein kann (siehe
[Kapitel 14.1](#141-stammdaten-je-messung-stammdaten_verlauf)).

*Betroffener Use Case:* Standard-Workflow, Use-Case A. *Häufigkeit:* **hoch**.

*Zielverhalten:* Unveränderte Angaben erzeugen keine neue Zeile. Wurden die Angaben an einem Tag
einmal bestätigt, wird an diesem Tag nicht erneut aufgefordert; korrigierbar bleiben sie
jederzeit (**Owner-Entscheidung 25.09.2026**). Zeitgebundene Felder werden mit dem Zeitstempel
ihrer letzten Bestätigung angezeigt, statt stillschweigend als aktuell zu gelten.

| Kennzahl | Heute | Danach |
|---|---|---|
| Neue Verlaufszeilen je Messtag | 1–4 | 0–1 |
| Zusatz-Taps im Bericht (30 Tage) | bis 60 | 0 |

**B – Technische Perspektive**

*Betroffener Code:* `GesamtberichtStammdatenSheet.speichern()` (`:192-216`),
`StammdatenVerlaufDao.insert` / `letzte(10)` / `fuerTag` (`data/StammdatenVerlaufEntity.kt:49`, `:54`, `:61`),
`GesamtberichtStammdaten.zuEntity` (`report/GesamtberichtDaten.kt:63`), `auswahlFehler`
(`report/BerichtErstellung.kt:79`).

*Vorgeschlagene Änderung*

1. In `speichern()` zuerst den zuletzt geladenen Eintrag vergleichen; bei Gleichheit aller 13
   Felder **keinen** Insert ausführen und das Sheet trotzdem schließen. Der Vergleich gehört in
   eine reine Funktion (`GesamtberichtStammdaten.entsprichtEintrag(entity): Boolean`), damit er
   JVM-testbar ist.
2. Die drei zeitgebundenen Felder beim Vorbefüllen leer lassen bzw. mit sichtbarem Hinweis
   „zuletzt: … · übernehmen" anbieten (UI-Entscheidung, keine Schemaänderung).
3. `auswahlFehler` bleibt unverändert – es ist die richtige Absicherung, sie wird nur seltener
   greifen.

*Persistenzänderung:* keine Schemaänderung. Bestehende Duplikate bleiben; optional eine
Aufräumaktion in den Einstellungen, aber **nicht** automatisch löschen (Beweiskette).

*Migration:* keine.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| **Neu (JVM)** | `entsprichtEintrag` – Gleichheit/Ungleichheit je Feld |
| `androidTest/.../GesamtberichtStammdatenSheetInstrumentedTest.kt` | erweitern: „zweimal Speichern ohne Änderung ⇒ eine Zeile" |
| `test/.../report/BerichtErstellungTest.kt` | unverändert |
| `test/.../report/GesamtberichtDatenTest.kt` | ggf. erweitern |

---

<a id="f-16"></a>
### F-16 · Beide Mess-Sheets erscheinen zweimal je Messvorgang · **P1**

**A – UX-Perspektive**

*Aktuelles Verhalten:* Beide Sheets hängen an `sessionDao.offeneSessionFlow()` und an einer
„schon gefragt"-Session-ID. Startet eine Messung mit Mikrofon und verbindet sich das PCE-323
danach, schließt `MeasurementRecorder` die Mikrofon-Session und legt eine **neue** Session an.
Die neue ID ist ≠ der gemerkten, für Fotos ist `fuerSession(neueId)` leer – beide Sheets
erscheinen erneut, mitten in der laufenden Messung.

*User Problem:* Der Nutzer beantwortet dieselben Fragen zweimal, ohne dass sich etwas geändert
hat. Beim zweiten Mal weiß er nicht, warum er sie erneut sieht. Zusätzlich erscheint die
Fotodoku-Abfrage genau dann, wenn der Nutzer das Telefon gerade positioniert hat.

*Betroffener Use Case:* Standard-Workflow mit Messgerät. *Häufigkeit:* **hoch** – das ist der
Regelfall, sobald ein PCE-323 im Spiel ist.

*Zielverhalten:* Beide Sheets fragen einmal je **Messvorgang**, nicht je Session-Zeile. Dazu die
Regeln aus [Kapitel 13.3](#133-fachliche-prüfung-des-gewünschten-ux-prinzips) (Tag + Messort).

| Kennzahl | Heute | Danach |
|---|---|---|
| Sheets je Messvorgang | 4 | 0–2 |
| Zusatzdialoge | 1 („ohne Foto?") | 0 |

**B – Technische Perspektive**

*Betroffener Code*

| Rolle | Symbol |
|---|---|
| Auslöser | `LaunchedEffect(offeneSession?.id, …)` in `ui/MainActivity.kt:400-408` und `:419-425` |
| Ursache | `MeasurementRecorder.eroeffneMessgeraetSession` schließt die Mikrofon-Session und legt eine neue an (`messreihe/MeasurementRecorder.kt:296-316`) |
| Daten | `DokumentationsFotoDao.fuerSession` (`data/DokumentationsFotoEntity.kt:84`), `StammdatenVerlaufDao.fuerTag` (`data/StammdatenVerlaufEntity.kt:61`) |

*Aktuelle Implementierung:* Der Anker ist die Session-ID. Ein Messvorgang kann aber zwei
Sessions umfassen (Mikrofon → Messgerät). Das ist fachlich korrekt so modelliert (zwei
Messquellen = zwei Messreihen, ausführlich im `SessionEntity`-KDoc begründet) – nur als
UI-Anker ist es falsch.

> **Owner-Entscheidung 25.09.2026: Weg B.** Weg A bleibt unten als Beschreibung stehen, weil er
> die fachliche Regel definiert, die auch Weg B umsetzt – umgesetzt wird aber die strukturelle
> Variante mit `messvorgangId`.

*Zwei Wege, beide ohne Schemabruch*

**Weg A (minimal, empfohlen als Sofortmaßnahme):** Die Abfrage vom Session- auf den Tagesbezug
umstellen.

* Fotos: neue `@Query("SELECT COUNT(*) FROM dokumentationsfotos WHERE kategorie = :kategorie AND
  aufgenommenAm >= :von AND aufgenommenAm < :bis")` – das Sheet erscheint nur, wenn für den
  heutigen Kalendertag noch kein Foto der jeweiligen Kategorie existiert.
* Stammdaten: `StammdatenVerlaufDao.fuerTag(von, bis)` existiert bereits – das Sheet erscheint
  nur, wenn die Liste leer ist.

**Weg B (sauber, größer):** Eine `messvorgangId` einführen, die beide Sessions klammert (neue
Spalte in `sessions`, Room-Migration 25 → 26 plus Migrationstest). Damit ließe sich auch die
Messintegrität ([F-12](#f-12)) über den gesamten Messvorgang statt je Session berechnen.

*Persistenzänderung:* Weg A: keine Schemaänderung, nur neue `@Query`. Weg B: Migration
25 → 26 + exportiertes Schema + Migrationstest (AGENTS.md §5: `identityHash` und Tabellennamen
dürfen sich nicht versehentlich ändern).

*Navigation Impact:* keiner.

*Test-Auswirkung*

| Test | Wirkung |
|---|---|
| **Neu (JVM)** | DAO-Test „Fotos je Kalendertag" |
| `test/.../foto/FotoDokumentationTest.kt` | erweitern |
| `androidTest/.../FotodokumentationSettingsInstrumentedTest.kt` | prüfen |
| **Neu (instrumentiert)** | „Quellenwechsel Mikrofon → Messgerät löst die Sheets nicht erneut aus" |
| Weg B zusätzlich | neuer `AppDatabaseV26MigrationTest` analog den 21 vorhandenen |

> **Widerspruchshinweis (AGENTS.md §2):** Die Änderung betrifft, *wie oft* gefragt wird, nicht
> *wann*. Die Owner-Festlegung „erst messen, dann fotografieren"
> (`FotoDokumentationSheet.kt:47-53`) bleibt unberührt.
>
> **Owner-Entscheidung 25.09.2026 zum Kalibrierfoto:** nicht erzwingen, aber mehrfach
> ermöglichen. Konkret: Die Abfrage erscheint einmal je Kalendertag; zusätzlich gibt es eine
> jederzeit erreichbare Aktion „Neues Kalibrierfoto", die nicht an die Tagesregel gebunden ist.
> Die Obergrenze `fotoDokuMaxProKategorie` (Default 3) bleibt davon unberührt und ist der
> Ort, an dem „öfter" konfigurierbar ist.

---

### Findings P2/P3 (kompakt)

Die folgenden Findings sind gleich aufgebaut, aber kürzer gefasst: Sie sind weniger
folgenschwer, ihre Umsetzung ist lokal begrenzt.

<a id="f-17"></a>
#### F-17 · Klassifizierungsstatus ist nicht darstellbar, drei verschiedene Null-Verhalten · **P2**

**UX:** Eine Aufnahme ohne `detectedLabel` kann „noch nicht klassifiziert", „nichts erkannt"
oder „KI ist aus" bedeuten – die Liste zeigt in allen drei Fällen dasselbe (nämlich nichts).
Dazu drei unterschiedliche Reaktionen auf ein leeres Ergebnis (siehe
[Kapitel 7](#7-use-case-b--ki-klassifizierung-nachträglich)). Häufigkeit: hoch bei der
Datensichtung. **Ziel:** ein sichtbarer Status je Aufnahme, ein einheitliches Verhalten bei
„nichts erkannt".

**Technik:** `NoiseRecord.detectedLabel` (`data/NoiseRecord.kt`), `NoiseRecordItem`
(`ui/MainActivity.kt:1297`, KI-Zeile `:1375-1382`), `onAiRecognize` (`:1146-1151`),
`ProtokollDetailScreen` (`:429-462`), `klassifiziereUndSpeichere`
(`audio/BatchKlassifizierung.kt:27`), `unklassifizierteAufnahmen`
(`messreihe/NoiseRecordGrouping.kt:26`), `SettingsManager.aiMode` (`:115`).
**Änderung:** Das „nichts erkannt"-Verhalten auf einen Weg festlegen (Vorschlag: eigenes,
reserviertes Label wie heute in `MainActivity`, aber in **allen** Pfaden) und in der Liste die
drei Zustände unterscheiden. `R.string.status_not_recognized` existiert bereits.
**Persistenz:** keine Änderung. **Tests:** `test/.../audio/BatchKlassifizierungTest.kt`,
`NeuBewertenTest.kt` unverändert; `test/.../messreihe/NoiseRecordGroupingTest.kt` erweitern;
Compose-Tests der Aufnahmeliste anpassen.

<a id="f-18"></a>
#### F-18 · Zwei Filtersysteme mit unterschiedlicher Persistenz · **P2**

**UX:** Die Startseite filtert Einzelaufnahmen (`RecordFilterState`, 7 Werte, **persistiert**),
der Protokoll-Screen filtert Sessions (`SessionFilterState`, **nicht persistiert**) und hat dafür
zwei getrennte Buttons in der TopAppBar („nur mit Ereignissen" und das erweiterte Panel).
Häufigkeit: mittel. **Ziel:** ein Filtermodell, ein Einstieg, gleiche Persistenz.

**Technik:** `messreihe/RecordFilter.kt`, `messreihe/SessionFilterUndGruppierung.kt`,
`MainActivity.kt:451-478` (`updateFilter` schreibt in `SettingsManager`), `ProtokollScreen.kt:75`,
`:126-148` (zwei `IconButton`s). **Änderung:** `SessionFilterState` analog persistieren
(`SettingsManager`, neue Schlüssel) und die beiden Buttons zu einem zusammenführen, wobei
„nur mit Ereignissen" ein Chip im Panel wird.
**Tests:** `test/.../messreihe/SessionFilterUndGruppierungTest.kt`, `RecordFilterTest.kt`
unverändert; `androidTest/.../ProtokollScreenInstrumentedTest.kt` **klickt heute genau den
ersten Button** (im Code kommentiert) → muss angepasst werden.

<a id="f-19"></a>
#### F-19 · 28 hartkodierte Farbliterale umgehen das Dark-Schema · **P2**

**UX:** Status-Badges und Pills behalten im Dark Mode helle Hintergründe. Da die App laut
`docs/UX_DESIGN_PLAN.md` gezielt für nächtliche Dauermessungen gebaut ist, trifft das den
Hauptanwendungsfall. Häufigkeit: hoch, Schweregrad optisch.

**Technik:** siehe [Kapitel 28.3](#283-hartkodierte-farben-dark-mode-risiko). Die Zielstruktur
existiert bereits: `AppStatusColors` / `MaterialTheme.colorScheme.statusColors`
(`ui/theme/Tokens.kt:12-45`). **Änderung:** Literale durch Token ersetzen.
**Owner-Entscheidung 25.09.2026: freigegeben** („ja gerne"). Der README-Vorbehalt („bewusst als
Owner-Entscheidung offengelassen") ist damit erledigt. Da jede Nuance heute leicht anders ist,
sollte die Umsetzung je Bedeutung **einen** Token festlegen (verbunden / verbindet / instabil /
fehlgeschlagen / inaktiv) und alle Literale darauf abbilden – nicht Literal für Literal ersetzen.
**Tests:** `test/.../ui/BluetoothStatusBadgeTest.kt`, `MicrophoneStatusBadgeTest.kt`,
`components/NoiseComponentsTest.kt` prüfen Texte, nicht Farben → voraussichtlich unverändert.

<a id="f-20"></a>
#### F-20 · 156 nicht lokalisierte UI-Literale · **P2**

**UX:** Die App bietet eine Sprachumschaltung Deutsch/Englisch/System an
(`SettingsScreen.kt:541-595`), aber große Teile der Oberfläche sind hartkodiert deutsch –
darunter das komplette Stammdaten-Sheet (21 Literale), das Berichts-Sheet (15), der
Drive-Ordner-Dialog (13) und die Cockpit-Bestätigungsdialoge (9). Wer auf Englisch stellt,
bekommt eine gemischte Oberfläche. Häufigkeit: hoch für englischsprachige Nutzer.

**Technik:** Zählung per `grep -rn 'Text("' app/src/main/java/com/example/lrmprotokoll/ui`
(156 Treffer, Verteilung in [Kapitel 28.2](#282-accessibility)). `values/strings.xml`, `values-de/strings.xml` und `values-en/strings.xml`
haben je 441 Einträge. **Änderung:** Literale nach `strings.xml`
verschieben, beginnend bei den sichtbarsten Dialogen. Das README nennt „~59" – die Zahl ist
veraltet. **Tests:** `test/.../ui/ResourceIntegrityTest.kt` erweitern (prüft heute 9
Beispielstrings); Compose-Tests, die auf deutschen Text matchen, müssen auf `stringResource`
umgestellt werden – das betrifft viele Tests und ist der eigentliche Aufwand.

<a id="f-21"></a>
#### F-21 · Touch-Targets unter 48 dp, davon zwei an zentralen Bedienelementen · **P2**

**UX:** Fünf `IconButton`s liegen unter 48 dp (20/36/36/36/40 dp). Kritischer: Die beiden
Status-Badges in der TopAppBar sind die **primäre** Bedienung für Bluetooth-Verbindung und
WAV-Aufzeichnung, bestehen aber nur aus einem `labelSmall`-Text mit 5 dp vertikalem Padding.
Häufigkeit: hoch.

**Technik:** `MainActivity.kt:923`, `:1073`, `:1084`; `MarkNoiseEventBottomSheet.kt:110`;
`ProtokollDetailScreen.kt:452`; `BluetoothStatusBadge.kt:76-82`; `MicrophoneStatusBadge.kt:100-113`.
**Änderung:** `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)` bzw. bei den Badges die
klickbare Fläche über `Modifier.minimumInteractiveComponentSize()` (Material 3) vergrößern, ohne
die visuelle Größe zu ändern. Zusätzlich `Role.Button` in den Semantics setzen.
**Tests:** vorhandene Badge-Tests prüfen Sichtbarkeit und Klick → bleiben grün; **neu**: ein
Instrumented-Test, der die Touch-Bounds gegen 48 dp prüft (`SemanticsNodeInteraction.getBoundsInRoot()`).

<a id="f-22"></a>
#### F-22 · Trigger-Quelle wird über ein Chip-Menü im Kopf geändert · **P2**

**UX:** Ein `Surface` mit Text und kleinem Pfeil im Cockpit-Kopf öffnet ein `DropdownMenu` mit
vier Aktionen, darunter zwei, die Bestätigungsdialoge auslösen (WAV deaktivieren, PCE trennen).
Es sieht aus wie ein Statuslabel, ist aber die mächtigste Einstellung des Screens. Häufigkeit:
mittel.

**Technik:** `LiveCockpitCard.kt:246-330` (`Surface(...).clickable`, `DropdownMenu`).
**Änderung:** Als `AssistChip`/`FilterChip` mit Trailing-Icon darstellen (Material-Affordanz),
`Role.Button` setzen, Mindestgröße 48 dp, und den WAV-Schalter aus diesem Menü herausnehmen – er
gehört zum Mikrofon-Badge, wo er bereits existiert (doppelter Einstieg, `MicrophoneStatusBadge.kt:104`).
**Tests:** `test/.../ui/AudioTriggerSettingsTest.kt` und `LiveCockpitCardTest.kt` prüfen.

<a id="f-23"></a>
#### F-23 · Deaktivierter Foto-Button ohne Begründung · **P3**

**UX:** Bei erreichtem Maximum wird der Aufnahme-Button deaktiviert; die einzige Information ist
der Zähler `(3/3)`. **Ziel:** ein Satz „Maximum erreicht – in den Einstellungen änderbar".

**Technik:** `FotoDokumentationSheet.kt:173` (`enabled = anzahl < maximum`),
`SettingsManager.fotoDokuMaxProKategorie` (`:389`). **Tests:**
`androidTest/.../FotodokumentationSettingsInstrumentedTest.kt` erweitern.

<a id="f-24"></a>
#### F-24 · Uneinheitliche Navigation zu `meter` und `diagnose` · **P3**

**UX:** Aus dem Cockpit werden diese Ziele per `navigiereZuTab` angesteuert (Back → Start), aus
den Einstellungen per `navigate` (Back → Einstellungen). Dasselbe Ziel verhält sich
unterschiedlich. Häufigkeit: niedrig, Verwirrung aber echt.

**Technik:** `MainActivity.kt:176-181` (`navigiereZuTab`) vs. `:249-256`.
**Änderung:** Eine Regel festlegen – Vorschlag: `meter` und `diagnose` sind Unterseiten (Back
kehrt zum Absender zurück), also überall `navigate`. Die Bottom-Nav bleibt sichtbar, markiert
dort aber keinen Eintrag (heute schon so).
**Tests:** `androidTest/.../MainActivityNavigationAndroidTest.kt`,
`androidTest/.../AppNavigationBarInstrumentedTest.kt` prüfen.

<a id="f-25"></a>
#### F-25 · Toter UI-Code · **P3**

**UX:** kein direkter Nutzerbezug, aber `QuickEventTagDialog` ist eine zweite, nie erreichbare
Variante des „Lärmereignis markieren"-Sheets – eine Fehlerquelle für künftige Änderungen, weil
ein Instrumented-Test sie am Leben hält.

**Technik:** vollständige Liste in [Kapitel 4.6](#46-nicht-verwendeter-ui-code-belegt-nicht-vermutet).
**Änderung:** `leiteDashboardAnzeigeAb` produktiv nutzen ([F-06](#f-06)); `QuickEventTagDialog`,
`SettingQuickRow`, `StatCard`, die zwei ungenutzten `LiveCockpitCard`-Parameter und die zwei
ungelesenen States entfernen oder verdrahten. **Tests:**
`androidTest/.../QuickEventTagDialogInstrumentedTest.kt` entfällt beim Entfernen;
`test/.../messreihe/DashboardStatusTest.kt` bleibt und wird endlich relevant.

<a id="f-26"></a>
#### F-26 · Batch-Klassifizierung ohne Fortschritt und ohne Doppelklickschutz · **P2**

**UX:** „Alle klassifizieren" und „Neu bewerten" im Overflow-Menü können über viele Dateien
laufen und zeigen bis zur Abschluss-Snackbar nichts an. Das Menü lässt sich sofort erneut
öffnen und der Lauf erneut starten. Häufigkeit: mittel.

**Technik:** `MainActivity.kt:610-624` und `:626-650` (beide `scope.launch { … }` ohne
Busy-State), `audio/BatchKlassifizierung.kt:27`, `audio/NeuBewerten.kt`.
**Änderung:** Einen `var batchLaeuft by remember { mutableStateOf(false) }` einführen, die
Menüeinträge währenddessen deaktivieren und einen Fortschritt anzeigen (die Schleife kennt die
Kandidatenzahl, ein `n von m` ist ohne Umbau möglich, wenn `klassifiziereUndSpeichere` einen
optionalen `onFortschritt`-Callback bekommt).
**Tests:** `test/.../audio/BatchKlassifizierungTest.kt` erweitern (Callback wird je Kandidat
gerufen); Compose-Test „zweiter Klick ist gesperrt".

<a id="f-27"></a>
#### F-27 · Drei Exportwege ohne jede Fehlerbehandlung · **P2**

**UX:** Zeitraum-/Gesamtbericht, Tagesbericht und die Exporte im Protokoll-Detail rufen ihre
Export-Funktionen ohne `try`/`runCatching` auf. Beim Zeitraumbericht bleibt der Dialog dadurch
dauerhaft im Ladezustand hängen; bei den anderen beiden schließt sich der Dialog, und es passiert
sichtbar nichts. Häufigkeit: niedrig, Wirkung aber „App wirkt kaputt".

**Technik:** `BerichtScreen.kt:64-83` (`erstelleUndTeileZeitraumbericht`),
`MainActivity.kt:1244-1262` (Tagesbericht-Dialog), `ProtokollDetailScreen.kt:352-381`
(PDF/CSV). Zum Vergleich: `BerichtErstellenSheet.kt:228-234` macht es richtig.
**Änderung:** `runCatching` um die Export- und Teilen-Aufrufe, Fehlertext über den vorhandenen
`onShowSnackbar`-Kanal (bei `BerichtScreen` und `ProtokollScreen` muss der Kanal erst
durchgereicht werden, siehe [F-31](#f-31)), plus `diagnosticsReporter.report(...)` wie beim
High-End-Bericht.
**Tests:** `test/.../report/MessreiheExportTest.kt`, `GesamtberichtExportTest.kt`,
`ReportManagerTest.kt` unverändert; **neu**: Compose-Tests „Exportfehler ⇒ Meldung, Dialog
schließt".

<a id="f-28"></a>
#### F-28 · Berichtszeitraum wird nicht vorbelegt und nicht gemerkt · **P2**

**UX:** Der High-End-Bericht verlangt jedes Mal eine vollständige Datumsbereich-Auswahl im
Vollbild-Dialog (4 Taps), obwohl der häufigste Fall „die Messung, die ich gerade beendet habe"
bzw. „derselbe Zeitraum wie letztes Mal" ist. Der alte Zeitraumdialog hat dafür Presets
(7 Tage / 30 Tage / dieser Monat), das neue Sheet nicht. Häufigkeit: mittel.

**Technik:** `BerichtErstellenSheet` hat bereits einen Parameter `initialRange: BerichtZeitraum?`
(`BerichtErstellenSheet.kt:77`), der von `BerichtScreen` als `initialHighEndRange` durchgereicht wird (`BerichtScreen.kt:45`, `:223`) –
aber **niemand übergibt ihn**; `MainActivity.kt:285-288` ruft `BerichtScreen` ohne diesen Parameter auf. Die Verdrahtung existiert also schon.
**Änderung:** `initialHighEndRange` aus der zuletzt beendeten Session ableiten
(`sessionDao.letzte()`), zusätzlich die Presets des alten Dialogs übernehmen und den zuletzt
gewählten Zeitraum in `SettingsManager` merken.
**Persistenz:** zwei neue Long-Schlüssel. **Tests:**
`test/.../ui/BerichtErstellenSheetTest.kt` nutzt `initialRange` bereits – gut testbar;
`androidTest/.../BerichtScreenAndroidTest.kt` prüfen.

<a id="f-29"></a>
#### F-29 · Messgerät-Screen liegt vier Ebenen tief · **P3**

**UX:** `MeterScreen` – der Ort für Kopplung, Live-Pegel, Geräteparameter und Entkopplung – ist
nur über Einstellungen → Start-Tab → Sektion aufklappen → „Messgerät öffnen" erreichbar.
Häufigkeit: mittel.

**Technik:** `SettingsScreen.kt:764`; die Cockpit-Verdrahtung existiert bereits ungenutzt
(`LiveCockpitCard.kt:99`, `MainActivity.kt:218`).
**Änderung:** Zwei Möglichkeiten: (a) Long-Press bzw. „Gerät verwalten" im Bluetooth-Badge-Menü,
(b) ein vierter Bottom-Nav-Eintrag. (a) ist die kleinere Änderung und passt zur bestehenden
Drei-Tab-Struktur. **Tests:** `androidTest/.../AppNavigationBarInstrumentedTest.kt` nur bei (b).

<a id="f-30"></a>
#### F-30 · Cockpit widerspricht der eigenen Designleitlinie „Verlauf vor Einzelwert" · **P2**

**UX:** `docs/UX_DESIGN_PLAN.md` Leitlinie 3 sagt: „Der aktuelle dB-Wert ist nicht
überdimensional groß, sondern die zeitliche Kurve und Tendenz stehen im visuellen Fokus." Im
Cockpit wird der Momentanwert mit `typography.displayLarge` gesetzt, der Chart steht darunter in
einer Karte. Häufigkeit: dauerhaft sichtbar.

**Technik:** `LiveCockpitCard.kt:357-365` (`displayLarge` `:361`), Chart `:529-539`.
**Owner-Entscheidung 25.09.2026: Die Leitlinie gilt weiter** („ja verlauf"). Damit ist das kein
Vorbehalt mehr, sondern eine bestätigte Anforderung – Priorität von P3 auf **P2** angehoben.

**Vorgeschlagene Änderung:** Den Momentanwert von `displayLarge` auf `headlineMedium` oder
`headlineSmall` zurücknehmen und den Chart nach oben ziehen, sodass er das erste große Element
unter der Kopfzeile ist. Der Wert bleibt vollständig lesbar, verliert aber die visuelle
Dominanz. Die Kennwerte (LAeq/LAmax) bleiben, wo sie sind – sie gehören zum Verlauf, nicht zum
Momentanwert. Reine Layoutänderung, keine neue Logik.
**Tests:** `test/.../ui/LiveCockpitCardTest.kt` prüft Texte, nicht Typografie → voraussichtlich
unverändert.

<a id="f-31"></a>
#### F-31 · Sechs Screens haben keinen Snackbar-Kanal · **P3**

**UX:** `ProtokollScreen`, `BerichtScreen`, `MeterScreen`, `DriveUploadScreen`,
`AudioPlayerScreen` und `KiErklaerungScreen` bekommen kein `onShowSnackbar` durchgereicht und
können deshalb keine flüchtige Rückmeldung geben – genau dort, wo [F-27](#f-27) Fehlermeldungen
bräuchte.

**Technik:** `MainActivity.kt:209-316` (nur 6 der 12 `composable`-Blöcke reichen den Kanal
durch). **Änderung:** Kanal überall durchreichen (einheitliche Signatur). **Tests:** bestehende
Screen-Tests übergeben dann einen zusätzlichen Parameter mit Default → meist unverändert.

<a id="f-32"></a>
#### F-32 · Zwei Filter-Buttons nebeneinander im Protokoll · **P3**

**UX:** In der TopAppBar des Protokolls stehen zwei Filter-Icons direkt nebeneinander
(`AppIcons.FilterList` für „nur mit Ereignissen", `Icons.Default.Build` mit Badge für das
erweiterte Panel). Der Code erklärt den Grund offen: der zweite wurde ergänzt, „um den
bestehenden einfachen Umschalter nicht zu verändern (Instrumented-Test klickt genau den)".
Häufigkeit: mittel.

**Technik:** `ProtokollScreen.kt:126-148`; der genannte Test ist
`androidTest/.../ProtokollScreenInstrumentedTest.kt`. **Änderung:** siehe [F-18](#f-18) – beide
zu einem Einstieg zusammenführen und den Test anpassen, statt die UI um den Test herumzubauen.

<a id="f-33"></a>
#### F-33 · Snackbar und Toast gemischt · **P3**

**UX:** 12 `Toast`-Aufrufe stehen neben dem globalen Snackbar-Kanal. Toasts sind auf modernen
Android-Versionen optisch fremd, nicht aktionsfähig und für TalkBack schlechter.
Häufigkeit: mittel.

**Technik:** `DiagnoseScreen.kt:314`, `:375`, `:441`, `:459`, `:487`, `:605`, `:613`, `:714`;
`SettingsScreen.kt:332`, `:435`, `:2328`; `LiveCockpitCard.kt:150`. Neun davon sind bereits als
`onShowSnackbar?.invoke(msg) ?: Toast…` geschrieben, greifen also nur, wenn kein Kanal übergeben
wurde. **Änderung:** Kanal überall übergeben ([F-31](#f-31)), die drei unbedingten Toasts auf
Snackbar umstellen.

<a id="f-34"></a>
#### F-34 · Cockpit-Titel verschwindet, sobald die Status-Badges die Breite füllen · **P1**

*Nachgetragen am 25.09.2026. Dieses Finding entstand nicht beim Lesen des Codes, sondern aus den
Messungen der Emulator-Tests aus PR #204. Im ursprünglichen Audit stand der Sachverhalt nur als
Risiko in [Kapitel 28](#28-android-material-best-practices-und-accessibility) („Dynamische
Schriftgrößen", **Needs verification**) und in [Kapitel 35.1](#351-nicht-geprüft-weil-kein-gerätemulator-verfügbar-war).*

**UX:** Die Überschrift des Cockpits („Überwachung") wird nicht etwa abgeschnitten oder mit
Ellipse gekürzt – sie wird **gar nicht dargestellt**. Betroffen ist jeder Nutzer mit vergrößerter
Schrift und zusätzlich jeder mit schmalem Gerät bei Standardschrift. Häufigkeit: hoch, der
Cockpit-Kopf ist auf dem Startbildschirm immer sichtbar.

**Technik:** `LiveCockpitCard.kt:223` ist eine `Row` mit `Arrangement.SpaceBetween`. Die
Titelspalte darin hat `Modifier.weight(1f, fill = false)`, die beiden Texte `maxLines = 1`
(`:234`, `:240`). Rechts daneben stehen die Status-Badges, die mit der Schriftgröße mitwachsen.
`weight(1f, fill = false)` teilt der Spalte nur zu, was die Badges übriglassen – das kann null
sein, und dann ist es null.

**Gemessen** (Emulator API 34, `SchriftskalierungInstrumentedTest`):

| Lauf | Schriftfaktor | Ergebnis |
|---|---|---|
| [36161839317](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/36161839317) | 1,3 und 2,0 | `assertIsDisplayed()` auf dem Titel schlägt fehl; der Startknopf bleibt sichtbar und klickbar |
| [36164004983](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/36164004983) | **1,0** | `Cockpit-Titel: breite=0px hoehe=28px zeilen=1 ueberlaufBreite=false ueberlaufHoehe=true maxBreite=0px` |

Die zweite Zeile ist der eigentliche Befund: **`maxBreite=0px` bei Schriftfaktor 1,0.** Es ist
also kein reines Schriftgrößenproblem, sondern ein Breitenproblem.

**Einordnung der Messgrundlage, ergänzt am 26.09.2026:** Beide Läufe fanden auf einem Emulator
mit **320×640** statt — einer Bildschirmgröße, die es bei heutigen Telefonen nicht gibt; das
schmalste aktuelle Gerät liegt bei rund 360 dp. Die CI läuft seit PR #204 auf `profile: pixel_5`
(393×851 dp). **Auf dieser realistischen Breite ist der Befund nicht neu vermessen** — der Test
sichert den Titel dort bewusst nicht mehr zu und gibt deshalb keine Zahlen aus.

Der Befund selbst bleibt bestehen: Die Titelspalte hat durch `weight(1f, fill = false)` keine
Untergrenze und kann auf null schrumpfen, sobald die Badges den Platz füllen. Offen ist allein,
**ab welcher Schriftgröße** das auf einem realen Gerät eintritt. Die ursprüngliche Zuspitzung
„trifft den Nutzer schon bei Standardschrift" stützt sich auf die 320-px-Messung und ist in
dieser Form **nicht belegt**.

**Änderung:** Die Titelspalte darf nicht auf null schrumpfen können. Entweder eine Mindestbreite
(`Modifier.widthIn(min = …)`) in Verbindung mit `weight(1f)` ohne `fill = false`, oder – der
robustere Weg – die Kopfzeile bei knapper Breite umbrechen lassen (`FlowRow`) statt Titel und
Badges in einer Zeile zu erzwingen. In beiden Fällen gehören die Badges auf eine Obergrenze
begrenzt, die dem Titel Platz lässt.

**Tests:** `SchriftskalierungInstrumentedTest` liegt vor und misst genau das. Er sichert den Titel
derzeit bewusst **nicht** zu, sondern prüft nur dessen Existenz, weil eine harte Zusicherung die
CI rot färben würde, solange dieses Finding offen ist. Im Test steht an drei Stellen im Klartext,
dass dort nach der Umsetzung `pruefeNichtAbgeschnitten(…)` bzw. `assertIsDisplayed()` hingehört.
Die Messfunktion dafür ist bereits vorhanden.

**Abgrenzung:** Nicht zu verwechseln mit [F-21](#f-21) (Touch-Targets unter 48 dp). Die beiden
betreffen zwar teilweise dieselbe Kopfzeile, sind aber verschiedene Fehler mit verschiedenen
Lösungen.

<a id="f-35"></a>
#### F-35 · Der „typlose" `startForeground()`-Rückfall ist nicht typlos und greift nicht · **P2**

*Nachgetragen am 25.09.2026 aus dem Emulator-Lauf
[36165915514](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/36165915514)
(PR #204). Im ursprünglichen Audit stand der Punkt als ungeprüft in
[Kapitel 35.1](#351-nicht-geprüft-weil-kein-gerätemulator-verfügbar-war): „der typlose Fallback
existiert (`:409`, `:423`), wurde aber in dieser Kombination nicht beobachtet". Er ist jetzt
beobachtet worden.*

**UX:** Ohne Mikrofonberechtigung und ohne gepinntes Messgerät **kommt der Dienst nicht in den
Vordergrund** — er beendet sich selbst. Für den Nutzer heißt das: Ein reines „Verbinden" ohne
Aufzeichnung ist auf dem heutigen Codepfad nicht möglich.

**Tragweite — korrigiert am 26.09.2026:** Die erste Fassung dieses Findings nannte F-35 einen
„technischen Blocker für [F-02](#f-02)". **Das war falsch.**

`berechneForegroundServiceType()` liefert nur dann `0`, wenn **weder** Mikrofonberechtigung
**noch** ein gepinntes Messgerät vorliegt. Der Test, der den Befund erzeugt hat, stellt diesen
Zustand künstlich her (`settings.meterDeviceAddress = null`). Das Zielverhalten von F-02 lautet
dagegen ausdrücklich „Beim App-Start wird ein **gepinntes** Gerät automatisch verbunden" — dort
ist `meterDeviceAddress != null`, der Typ ist `connectedDevice`, und der ist ohne `RECORD_AUDIO`
zulässig. **Der Pfad, den F-02 braucht, ist nicht betroffen.**

Was bleibt, ist ein **stiller Fehlschlag** im Grenzzustand „keine Mikrofonberechtigung und kein
gepinntes Gerät": Der Dienst startet, scheitert und beendet sich selbst, ohne dass der Nutzer
erfährt, warum. Erreichbar etwa bei einer Neuinstallation mit verweigertem Mikrofon und noch
nicht gekoppeltem Messgerät. Das ist derselbe Fehlertyp wie [F-09](#f-09) und [F-11](#f-11) und
gehört deshalb zu **Roadmap-Phase 2**, nicht zu Phase 6.

**Technik — was gemessen wurde:** `ForegroundServiceOhneMikrofonPermissionInstrumentedTest`
läuft als isolierter Berechtigungsfall nach `pm revoke android.permission.RECORD_AUDIO` vor dem
Prozessstart. Der Entzug griff nachweislich (`dumpsys`: `granted=false`), die vier bestehenden
Berechtigungsfälle sind grün. Der Dienst erreichte den Vordergrund innerhalb von 15 s nicht.

**Technik — warum, aus dem Code ablesbar:**

| Stelle | Inhalt |
|---|---|
| `AndroidManifest.xml:102` | `android:foregroundServiceType="microphone\|connectedDevice"` |
| `AudioRecordingService.kt:409` | `startForeground(NOTIFICATION_ID, buildNotification(…))` — try-Zweig bei `serviceType == 0` |
| `AudioRecordingService.kt:423` | `startForeground(NOTIFICATION_ID, buildNotification(…))` — **derselbe Aufruf** im catch |
| `AudioRecordingService.kt:426` | `stopSelf()` |

Liefert `berechneForegroundServiceType()` eine `0`, ruft der try-Zweig die
**Zweiargument-Variante** auf. Die ist nicht typlos: Sie erbt die im Manifest deklarierten Typen,
also auch `microphone` — für das die Berechtigung gerade fehlt. Der catch-Block ruft daraufhin
**genau dieselbe Variante** noch einmal auf und beendet den Dienst.

*Nicht bewiesen:* der genaue Ausnahmetyp. Der stünde im Logcat, das als Artefakt
`emulator-diagnostics-api-34` abgelegt wird und nicht im Job-Log steht. Dass der catch-Block
denselben Aufruf wiederholt, ist dagegen unmittelbar aus dem Code ablesbar.

**Änderung — empfohlen: in diesem Zustand gar keinen Foreground-Service starten.**

Wenn weder Mikrofon noch Messgerät verfügbar sind, hat der Dienst nichts zu tun. Einen
Foreground-Service zu starten, der nichts überwachen kann, ist der eigentliche Fehler — nicht
der fehlende Typ. Richtig ist: vorher prüfen, nicht starten, und dem Nutzer sagen, was fehlt
(derselbe Mechanismus wie bei [F-09](#f-09)/[F-11](#f-11)). Dazu den `catch`-Zweig reparieren,
der heute denselben Aufruf wiederholt.

Zwei naheliegende Alternativen wurden geprüft und **verworfen**, weil sie ein Problem lösen, das
die App nicht hat:

| Weg | Warum nicht |
|---|---|
| `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | Verlangt gegenüber Google eine Begründung bei der Prüfung — Aufwand für einen Zustand, in dem ohnehin nichts passieren kann |
| `dataSync` | Ab Android 15 gilt dafür eine Laufzeitgrenze von rund sechs Stunden je 24 Stunden. Bei einer App, die Lärm über ganze Tage protokolliert, wäre das ein eingebautes Abschaltrisiko |

**Tests:** `ForegroundServiceOhneMikrofonPermissionInstrumentedTest` liegt vor und misst genau
das. Er sichert die Vordergrund-Erwartung derzeit **nicht** zu, sondern protokolliert sie — eine
harte Zusicherung wäre eine dauerhaft rote CI für einen bekannten, unbehobenen Befund. Im Test
steht, dass dort `assertTrue(imVordergrund)` hingehört, sobald F-35 behoben ist. Hart zugesichert
bleibt die Sicherheitsaussage, dass ohne Berechtigung keine Audioaufzeichnung läuft.

---

## 30. Quick Wins

Aufnahmekriterien: erkennbarer UX-Nutzen · geringes Risiko · überschaubarer Aufwand · klar
lokalisierbare Codestelle. Keine Schemaänderung, keine Navigationsumbauten.

### QW-1 · `rememberSaveable` in den Mess-Sheets ([F-01](#f-01))

* **Problem:** Drehung verwirft bis zu 13 ausgefüllte Felder.
* **Verbesserung:** Eingaben überleben die Neuerzeugung.
* **Code-Stelle:** `GesamtberichtStammdatenSheet.kt:88-100`, `FotoDokumentationSheet.kt:83`,
  `MarkNoiseEventBottomSheet.kt:66`, `MainActivity.kt:398`, `:417`.
* **Effekt:** kein Datenverlust mehr durch Drehung (der Rückkehr-Teil von F-01 ist größer, siehe
  Struktur).
* **Testauswirkung:** keine bestehenden Tests betroffen; ein neuer Instrumented-Test mit
  `recreate()`.

### QW-2 · Echte Werte an `bewerteSystemZustand` ([F-04](#f-04))

* **Problem:** Drei Parameter hartkodiert `true`; Bluetooth aus und fehlende BT-Berechtigung
  sind unsichtbar.
* **Verbesserung:** Selbstprüfung und Startseiten-Banner sagen die Wahrheit.
* **Code-Stelle:** `MainActivity.kt:519-537`, `DiagnoseScreen.kt:132-151`, `AppContainer.kt:59`
  (`private` entfernen).
* **Effekt:** die zwei häufigsten Verbindungsursachen werden benannt.
* **Testauswirkung:** `SystemHealthCheckerTest` unverändert; Compose-Tests der Selbstprüfung
  prüfen, ob sich die Zeilenzahl ändert.

### QW-3 · `HealthActionType` auswerten ([F-05](#f-05))

* **Problem:** Sechs verschiedene Aktionsknöpfe, ein Ziel.
* **Verbesserung:** Jeder Knopf führt an sein Ziel.
* **Code-Stelle:** `DiagnoseScreen.kt:229-240`.
* **Effekt:** Einrichtung ohne Suche in den System-Einstellungen.
* **Testauswirkung:** `DiagnoseScreenInstrumentedTest` erweitern (Intent-Prüfung).

### QW-4 · Laufzeit aus der offenen Session ([F-06](#f-06))

* **Problem:** Timer zählt aus einer beendeten Session weiter.
* **Verbesserung:** korrekte Messdauer; `leiteDashboardAnzeigeAb` wird produktiv genutzt.
* **Code-Stelle:** `LiveCockpitCard.kt:115`, `:195`, `:211-213`; Logik in
  `messreihe/DashboardStatus.kt:24`.
* **Effekt:** eine falsche Zahl weniger in einem Beweiswerkzeug.
* **Testauswirkung:** `MicrophoneCockpitRegressionTest` prüfen; `DashboardStatusTest` bleibt.

### QW-5 · Vorprüfung des Berichts als Live-Checkliste ([F-07](#f-07))

* **Problem:** Absage erst nach dem Klick, danach Datumsauswahl erneut.
* **Verbesserung:** Blocker vorher sichtbar, Knopf gesperrt mit Begründung.
* **Code-Stelle:** `BerichtErstellenSheet.kt:109-140`, `:240-243`; Prüffunktionen unverändert.
* **Effekt:** −13 Taps im häufigsten Fehlerfall.
* **Testauswirkung:** `BerichtErstellenSheetTest` und der Instrumented-Test müssen angepasst
  werden (heute wird die Meldung nach dem Klick erwartet).

### QW-6 · Gebietseinstufung im Berichts-Sheet ([F-08](#f-08))

* **Problem:** Pflichtangabe nur in den Einstellungen.
* **Verbesserung:** `ReportAreaSelection` direkt im Sheet – die Composable ist bereits
  eigenständig und getestet.
* **Code-Stelle:** `BerichtErstellenSheet.kt` (neue Zeile), `ui/ReportAreaSelection.kt:23`.
* **Effekt:** Der Bericht wird ohne Kontextwechsel möglich.
* **Testauswirkung:** Sheet-Tests erweitern; `ReportAreaTest` unverändert.

### QW-7 · Berichtszeitraum vorbelegen ([F-28](#f-28))

* **Problem:** 4 Taps Datumsauswahl bei jedem Bericht, obwohl der Parameter schon existiert.
* **Verbesserung:** `initialHighEndRange` aus der letzten Session füllen.
* **Code-Stelle:** `MainActivity.kt:285-288` (Parameter übergeben), `BerichtScreen.kt:45`.
* **Effekt:** −4 Taps je Bericht.
* **Testauswirkung:** `BerichtErstellenSheetTest` nutzt `initialRange` bereits.

### QW-8 · Retry-Knopf für fehlgeschlagene Uploads ([F-13](#f-13))

* **Problem:** „✕ fehlgeschlagen" ohne Aktion.
* **Verbesserung:** „Erneut versuchen" löst `DriveSyncPlanung.starteSofort(context)` aus.
* **Code-Stelle:** `DriveUploadScreen.kt:182-194`.
* **Effekt:** −4 Taps; der Nutzer bleibt dort, wo er das Problem sieht.
* **Testauswirkung:** `DriveUploadScreenInstrumentedTest` erweitern.

### QW-9 · Speicherplatz-Warnung vor dem Messstart ([F-10](#f-10))

* **Problem:** Messung startet ohne Platzprüfung.
* **Verbesserung:** Warnzeile (keine Blockade) über dem Startknopf.
* **Code-Stelle:** neue reine Funktion neben `video/Videospeicher.kt`, Anzeige in
  `LiveCockpitCard.kt:611-641`.
* **Effekt:** verhindert Datenverlust, der erst Stunden später auffällt.
* **Testauswirkung:** neuer JVM-Test analog `VideospeicherTest`.

### QW-10 · Fehlerbehandlung um die drei ungeschützten Exporte ([F-27](#f-27))

* **Problem:** Ein Exportfehler lässt den Zeitraumdialog hängen bzw. passiert unsichtbar.
* **Verbesserung:** `runCatching` + Meldung + Diagnoseeintrag.
* **Code-Stelle:** `BerichtScreen.kt:64-83`, `MainActivity.kt:1244-1262`,
  `ProtokollDetailScreen.kt:352-381`.
* **Effekt:** kein hängender Dialog mehr.
* **Testauswirkung:** neue Compose-Tests; Export-Unit-Tests unverändert.

### QW-11 · Doppelklickschutz und Fortschritt für die Batch-Klassifizierung ([F-26](#f-26))

* **Code-Stelle:** `MainActivity.kt:610-624`, `:626-650`, `audio/BatchKlassifizierung.kt:27`.
* **Testauswirkung:** `BatchKlassifizierungTest` erweitern.

### QW-12 · Begründung am deaktivierten Foto-Button ([F-23](#f-23))

* **Code-Stelle:** `FotoDokumentationSheet.kt:173`.
* **Testauswirkung:** vernachlässigbar.

### QW-13 · Touch-Targets auf 48 dp ([F-21](#f-21))

* **Code-Stelle:** `MainActivity.kt:923`, `:1073`, `:1084`, `MarkNoiseEventBottomSheet.kt:110`,
  `ProtokollDetailScreen.kt:452`, plus `minimumInteractiveComponentSize()` an den beiden Badges.
* **Testauswirkung:** bestehende Tests bleiben grün; optional ein Bounds-Test.

---

## 31. Structural UX Improvements

Nicht mit den Quick Wins zu vermischen: Diese Punkte ändern Datenmodell, Navigation oder den
Ablauf selbst und brauchen eine Owner-Entscheidung vor der Umsetzung (AGENTS.md §8a).

### S-1 · Messbereitschaft als eigenes Konzept

**Problem:** Es gibt keinen Ort, der „Kann ich jetzt sinnvoll messen?" beantwortet. Berechtigung,
Speicher, Gerät, Berichtsangaben und Fotodokumentation werden an fünf verschiedenen Stellen bzw.
gar nicht gezeigt.

**Vorschlag:** Eine Zeile bzw. kompakte Karte über dem Startknopf mit fünf Zuständen, jeder mit
Text und Inline-CTA:

```
Messbereit · Gerät verbunden · Mikrofon frei · 12,4 GB frei · Berichtsangaben von heute · Fotos von heute
```

bzw.

```
Nicht messbereit
  ✗ Bluetooth ist aus                         [Einschalten]
  ✗ Mikrofon-Berechtigung fehlt               [Erteilen]
  ⚠ Nur 0,8 GB frei (~2 h Aufnahme)           [Speicher freigeben]
```

**Betroffen:** `LiveCockpitCard`, `diagnose/SystemHealthChecker.kt` (Logik wiederverwenden),
neue reine Funktion für den Speicher-Check. **Bündelt:** [F-04](#f-04), [F-05](#f-05),
[F-10](#f-10), [F-11](#f-11), [F-13](#f-13). **Risiko:** mittel (viel neue UI im wichtigsten
Screen). **Tests:** neue JVM-Tests für die Ableitung, angepasste Cockpit-Compose-Tests.

### S-2 · „Messvorgang" als Klammer über beide Sessions

**Problem:** Der Wechsel Mikrofon → Messgerät erzeugt eine zweite Session und damit zwei
Sheet-Runden, zwei Protokolleinträge und zwei getrennte Integritätsbetrachtungen – obwohl es
fachlich **eine** Messung war.

**Vorschlag:** Eine `messvorgangId` in `sessions` (Room-Migration 25 → 26). Sheets, Fotos,
Stammdaten, Messintegrität und der Bericht hängen daran statt an der Session.

**Betroffen:** `data/SessionEntity.kt`, `data/AppDatabase.kt`, `MeasurementRecorder`,
`MainActivity` (Sheet-Auslöser), `ProtokollScreen`. **Bündelt:** [F-01](#f-01) (Rückkehr des
Sheets), [F-12](#f-12), [F-16](#f-16). **Risiko:** hoch (Schemaänderung, 21 vorhandene
Migrationstests als Vorbild; `fallbackToDestructiveMigration` ist verboten). **Alternative mit
kleinerem Risiko** wäre die reine Tagesregel aus [F-16 Weg A](#f-16) gewesen – der Owner hat sich
am 25.09.2026 bewusst für Weg B entschieden.

### S-3 · Automatische Geräteverbindung, getrennt von der Aufzeichnung

**Problem:** „Verbinden" und „Messen" sind heute derselbe Knopf.

**Vorschlag:** Auto-Connect beim App-Start (abschaltbar), neue Service-Aktion für
„nur verbinden", Cockpit unterscheidet „verbunden" von „misst".

**Betroffen:** `AudioRecordingService`, `ConnectionSupervisor` (Ruhemodus statt Aufgeben),
`LiveCockpitCard`, `BluetoothStatusBadge`, `SettingsManager`. **Bündelt:** [F-02](#f-02),
[F-03](#f-03), [F-06](#f-06). **Risiko:** hoch – berührt den Foreground-Service-Lebenszyklus,
der laut README bereits Gegenstand mehrerer Gerätetest-Befunde war. **Tests:** mehrere
Service-Tests müssen angepasst werden; ein Gerätetest ist zwingend
(`docs/CHECKLISTE_GERAETETEST.md`).

### S-4 · Bericht-Tab als Arbeitsplatz statt Zwischenseite

**Problem:** `BerichtScreen` ist ein Screen mit zwei Knöpfen; der eigentliche Zustand („welche
Messungen habe ich, welche sind berichtsfähig, was fehlt noch") ist unsichtbar.

**Vorschlag:** Der Tab zeigt eine Liste der letzten Messtage mit Integritätsstatus
([F-12](#f-12)), den Voraussetzungen ([F-07](#f-07)) und den erzeugten Berichten. Die zwei
Knöpfe werden Aktionen an diesen Einträgen.

**Betroffen:** `BerichtScreen`, `BerichtErstellenSheet`, `report/BerichtErstellung.kt`
(Daten liegen bereits vor: `ladeBerichtstage`). **Bündelt:** [F-07](#f-07), [F-08](#f-08),
[F-12](#f-12), [F-28](#f-28). **Risiko:** mittel.

### S-5 · Ein Filtermodell für Aufnahmen und Sessions

**Problem:** Zwei Filtermodelle, zwei Einstiege, unterschiedliche Persistenz.

**Vorschlag:** Ein gemeinsames Modell mit zwei Sichten. **Betroffen:**
`messreihe/RecordFilter.kt`, `messreihe/SessionFilterUndGruppierung.kt`, beide Screens.
**Bündelt:** [F-18](#f-18), [F-32](#f-32). **Risiko:** niedrig bis mittel; ein instrumentierter
Test klickt heute gezielt den alten Button.

### S-6 · Kontextregel für Foto- und Stammdaten-Abfrage

**Problem:** siehe [Kapitel 13](#13-fotodokumentation-im-detail) und
[Kapitel 14](#14-berichtsparameter-quelle-scope-persistenz).

**Vorschlag:** Abfragen nach der Regel „einmal je Kalendertag und Messort", zeitgebundene Felder
nie stillschweigend übernehmen, Inline-Zeile „Berichtsangaben: … · ändern" statt Sheet.
**Bündelt:** [F-15](#f-15), [F-16](#f-16). **Risiko:** mittel – enthält eine fachliche
Owner-Entscheidung zum Kalibrierfoto.

---

## 32. Priorisierungsübersicht und Test Impact Analysis

### 32.1 Alle Findings

| ID | Kurzbeschreibung | Prio | Aufwand | Risiko | Kategorie |
|---|---|---|---|---|---|
| [F-01](#f-01) | Sheet-Eingaben gehen bei Drehung verloren, Sheet kehrt nicht zurück | **P0** | S (Teil 1) / M (Teil 3) | niedrig | Datenverlust |
| [F-02](#f-02) | Kein Auto-Connect; „Verbinden" == „Messung starten" – **freigegeben, Default an** | P1 | L | hoch | Workflow |
| [F-03](#f-03) | `FAILED` ist eine Sackgasse | P1 | S | mittel | Zuverlässigkeit |
| [F-04](#f-04) | Selbstprüfung mit hartkodierten Parametern | P1 | S | niedrig | Transparenz |
| [F-05](#f-05) | `HealthActionType` wird ignoriert | P1 | S | niedrig | Bedienbarkeit |
| [F-06](#f-06) | Laufzeit/Betriebsart aus der falschen Session | P1 | S | niedrig | Korrektheit |
| [F-07](#f-07) | Berichts-Vorprüfung erst nach dem Klick | P1 | M | niedrig | Fehlerprävention |
| [F-08](#f-08) | Gebietseinstufung im Berichtsflow unsichtbar | P1 | S | niedrig | Fehlerprävention |
| [F-09](#f-09) | Stiller Ausfall nur in der Notification | P1 | M | niedrig | Transparenz |
| [F-10](#f-10) | Kein Speicherplatz-Check vor der Messung | P1 | S | niedrig | Fehlerprävention |
| [F-11](#f-11) | Mikrofonfehler ohne sichtbare Meldung | P1 | S | niedrig | Transparenz |
| [F-12](#f-12) | Messintegrität erfasst, nicht dargestellt | P1 | M | niedrig | Transparenz |
| [F-13](#f-13) | Drive-Status nicht im Hauptfluss, kein Retry | P1 | M | niedrig | Transparenz |
| [F-14](#f-14) | Onboarding erscheint nie | P1 | S | niedrig | First-Time-Use |
| [F-15](#f-15) | Stammdaten: Duplikate, alle Felder gleich behandelt | P2 | M | niedrig | Interaktionskosten |
| [F-16](#f-16) | Beide Sheets erscheinen zweimal je Messvorgang | **P1** | **L** (Weg B entschieden) | **hoch** (Room-Migration) | Interaktionskosten |
| [F-17](#f-17) | Klassifizierungsstatus nicht darstellbar | P2 | M | niedrig | Transparenz |
| [F-18](#f-18) | Zwei Filtersysteme | P2 | M | mittel | Konsistenz |
| [F-19](#f-19) | Hartkodierte Farben (Dark Mode) – **freigegeben** | P2 | M | niedrig | Darstellung |
| [F-20](#f-20) | 156 nicht lokalisierte UI-Literale | P2 | L | mittel | i18n |
| [F-21](#f-21) | Touch-Targets unter 48 dp | P2 | S | niedrig | Accessibility |
| [F-22](#f-22) | Trigger-Quelle-Chip: Affordanz und Größe | P2 | S | niedrig | Accessibility |
| [F-23](#f-23) | Deaktivierter Foto-Button ohne Begründung | P3 | S | niedrig | Verständlichkeit |
| [F-24](#f-24) | Uneinheitliche Navigation zu `meter`/`diagnose` | P3 | S | niedrig | Konsistenz |
| [F-25](#f-25) | Toter UI-Code | P3 | S | niedrig | Wartbarkeit |
| [F-26](#f-26) | Batch-Klassifizierung ohne Fortschritt/Schutz | P2 | S | niedrig | Feedback |
| [F-27](#f-27) | Drei Exportwege ohne Fehlerbehandlung | P2 | S | niedrig | Fehlerbehandlung |
| [F-28](#f-28) | Berichtszeitraum nicht vorbelegt | P2 | S | niedrig | Interaktionskosten |
| [F-29](#f-29) | Messgerät-Screen vier Ebenen tief | P3 | S | niedrig | Navigation |
| [F-30](#f-30) | Widerspruch zur Leitlinie „Verlauf vor Einzelwert" | **P2** | S | niedrig | Darstellung |
| [F-31](#f-31) | Sechs Screens ohne Snackbar-Kanal | P3 | S | niedrig | Konsistenz |
| [F-32](#f-32) | Zwei Filter-Buttons im Protokoll | P3 | S | niedrig | Konsistenz |
| [F-33](#f-33) | Snackbar und Toast gemischt | P3 | S | niedrig | Material |

Aufwand: S ≤ ½ Tag · M ≈ 1–2 Tage · L > 2 Tage (grobe Einordnung, keine Schätzung auf Basis
gemessener Daten).

### 32.2 Test Impact je Finding

Die Testbasis ist umfangreich (193 JVM-/Robolectric-, 62 instrumentierte Tests). Die folgende
Tabelle nennt nur Tests, die durch die jeweilige Umsetzung **voraussichtlich brechen oder
angepasst werden müssen** – plus die Tests, die neu entstehen sollten. Bestehende Abdeckung wird
bewusst nicht dupliziert.

| Finding | Bestehende Tests, die anzupassen sind | Regressionsrisiko | Neue Tests |
|---|---|---|---|
| F-01 | keine (Verhalten wird nur robuster) | niedrig | Instrumented: `recreate()` erhält Eingaben; Sheet kehrt zurück; JVM: neue DAO-Abfrage |
| F-02 | `AudioRecordingServiceStartupTest`, `AudioStartPolicyTest`, `ServiceControlComposeTest`, `ServiceControlInstrumentedTest`, `MicrophoneCockpitRegressionTest`, `ForegroundServiceAndroidTest` | **hoch** – Foreground-Service-Lebenszyklus | „App-Start verbindet, ohne Session zu eröffnen"; Service-Typ ohne `RECORD_AUDIO` |
| F-03 | `ConnectionSupervisorTest` (Erwartung „nach 8 Versuchen Ende") | mittel | „nach FAILED folgt ein weiterer Versuch"; „`stop()` beendet den Ruhemodus" |
| F-04 | ggf. `DiagnoseScreenComposeTest`, `DiagnoseScreenInstrumentedTest`, `HomeNavigationComposeTest` (Zeilenzahl/Banner) | niedrig | „Adapter aus ⇒ WARNING mit Aktion" |
| F-05 | `DiagnoseScreenInstrumentedTest` | niedrig | je Aktionstyp ein Intent-/Callback-Test |
| F-06 | `MicrophoneCockpitRegressionTest`, ggf. `LiveCockpitCardTest` | niedrig | „WAV beenden ⇒ keine Laufzeit mehr" |
| F-07 | `BerichtErstellenSheetTest`, `BerichtErstellenSheetInstrumentedTest` | mittel | „Knopf gesperrt mit Begründung" |
| F-08 | `BerichtErstellenSheetTest` | niedrig | „Auswahl im Sheet speichert und entsperrt" |
| F-09 | `NotificationTextTest` prüfen | niedrig | „Hinweis erscheint/verschwindet im Cockpit" |
| F-10 | keine | niedrig | JVM: `reichtSpeicherFuerMessung`; Compose: Warnzeile |
| F-11 | `AudioRecordingServiceStartupTest` erweitern | niedrig | „Init-Fehler setzt Hinweis" |
| F-12 | `ProtokollScreenTest`, `ProtokollScreenInstrumentedTest`, `ProtokollDetailScreenComposeTest` | mittel | JVM: `bewerteMessintegritaet` (Grenzfälle); DAO-Tests |
| F-13 | `DriveUploadScreenInstrumentedTest` | niedrig | Compose-Test der neuen Sync-Zeile |
| F-14 | `MainActivityLaunchTest`, `AppStartupSmokeInstrumentedTest` (starten heute direkt ins Cockpit) | **mittel** | „frische Installation zeigt Onboarding, danach nicht" |
| F-15 | `GesamtberichtStammdatenSheetInstrumentedTest` erweitern | niedrig | JVM: `entsprichtEintrag` |
| F-16 (Weg B, entschieden) | `FotoDokumentationTest`, `FotodokumentationSettingsInstrumentedTest` + alle session-bezogenen Tests | **hoch** | `AppDatabaseV26MigrationTest`; DAO „je Messvorgang"; Instrumented: Quellenwechsel löst nichts aus |
| F-17 | Compose-Tests der Aufnahmeliste | niedrig | `NoiseRecordGroupingTest` erweitern |
| F-18 | `ProtokollScreenInstrumentedTest` (klickt gezielt den alten Button) | mittel | Persistenz des Session-Filters |
| F-19 | voraussichtlich keine (Tests prüfen Texte, nicht Farben) | niedrig | – |
| F-20 | **viele** Compose-Tests, die auf deutschen Text matchen | **hoch** | `ResourceIntegrityTest` erweitern |
| F-21 | keine | niedrig | Bounds-Test gegen 48 dp |
| F-22 | `AudioTriggerSettingsTest`, `LiveCockpitCardTest` | niedrig | – |
| F-23 | keine | niedrig | – |
| F-24 | `MainActivityNavigationAndroidTest`, `AppNavigationBarInstrumentedTest` | niedrig | – |
| F-25 | `QuickEventTagDialogInstrumentedTest` entfällt | niedrig | – |
| F-26 | `BatchKlassifizierungTest` erweitern | niedrig | „zweiter Klick gesperrt" |
| F-27 | keine | niedrig | Compose: „Exportfehler ⇒ Meldung" |
| F-28 | keine (`initialRange` ist testbar vorhanden) | niedrig | „Zeitraum vorbelegt" |
| F-29 | ggf. `AppNavigationBarInstrumentedTest` | niedrig | – |
| F-30 | – | – | – |
| F-31 | Screen-Tests bekommen einen Parameter mit Default | niedrig | – |
| F-32 | `ProtokollScreenInstrumentedTest` | niedrig | – |
| F-33 | keine | niedrig | – |

### 32.3 Querschnittliche Testhinweise

* **Room:** Nur [F-16 Weg B](#f-16) und [S-2](#s-2--messvorgang-als-klammer-über-beide-sessions)
  brauchen eine Migration. Dann gilt AGENTS.md §5: exportiertes Schema, neuer Migrationstest,
  Tabellen-/Spaltennamen und `identityHash` unverändert für alles Bestehende,
  `fallbackToDestructiveMigration` bleibt verboten.
* **Robolectric-Grenzen (aus dem README, weiterhin gültig):** PDF-Export
  (`android.graphics.pdf.PdfDocument`), `EncryptedSharedPreferences`, CameraX/`MediaMuxer` und
  der STREAMING-Zustand mit echtem Frame lassen sich unter Robolectric nicht prüfen. Findings,
  die daran hängen ([F-27](#f-27) teilweise, [F-12](#f-12) nicht), brauchen instrumentierte
  Tests oder einen Gerätetest.
* **Test-Seam für das Messgerät existiert bereits:** `AppContainer` nimmt einen
  `meterTransportOverride` entgegen (`AppContainer.kt:48`, ausgewertet `:55-57`), und viele Tests
  nutzen ihn bereits mit `FakeMeterTransport` (u. a. `test/.../ui/MeterScreenComposeTest.kt:63`,
  `androidTest/.../MeterScreenInstrumentedTest.kt:239`). Der STREAMING-Pfad ist damit testbar –
  die gegenteilige Aussage in den „Bekannten Einschränkungen" des README ist überholt (siehe
  [Kapitel 35](#35-offene-punkte--needs-verification)). Daneben stehen
  `bleScanProviderOverride`, `standortErmittlungOverride` und `wetterProviderOverride`
  (`AppContainer.kt:49-51`).
* **ktlint** ist auf `main` bereits rot (Altbestand). Maßgabe bleibt: keine **neuen** Befunde in
  geänderten Dateien (AGENTS.md §6).

---

## 33. Proposed Implementation Roadmap

Nur Planung. In diesem Auftrag wird nichts davon umgesetzt.

Die Reihenfolge folgt drei Regeln: (1) Datenverlust zuerst, (2) danach die Findings, deren
Logik bereits fertig und getestet im Repository liegt und nur verdrahtet werden muss, (3)
strukturelle Umbauten zuletzt und einzeln, weil sie Gerätetests brauchen.

### Phase 1 – Datenverlust und falsche Aussagen stoppen

| | |
|---|---|
| **Findings** | [F-01](#f-01) (Teil 1+2: `rememberSaveable`), [F-06](#f-06), [F-27](#f-27) |
| **Komponenten** | `GesamtberichtStammdatenSheet`, `FotoDokumentationSheet`, `MarkNoiseEventBottomSheet`, `MainActivity` (Sheet-States), `LiveCockpitCard`, `BerichtScreen`, `ProtokollDetailScreen` |
| **Erwarteter UX-Effekt** | keine verlorenen Eingaben mehr; keine erfundene Messdauer; kein hängender Export-Dialog |
| **Risiko** | niedrig – rein additiv, keine Schemaänderung, keine Service-Änderung |
| **Testauswirkungen** | `MicrophoneCockpitRegressionTest` prüfen; neue Instrumented-Tests (`recreate()`), neue Compose-Tests für Exportfehler |

### Phase 2 – Sichtbarkeit herstellen (vorhandene Logik verdrahten)

| | |
|---|---|
| **Findings** | [F-04](#f-04), [F-05](#f-05), [F-09](#f-09), [F-11](#f-11), [F-12](#f-12), **[F-35](#f-35)** (zugeordnet 26.09.2026), ~~[F-14](#f-14)~~ (**erledigt**, PR #203) |
| **Komponenten** | `SystemHealthChecker` (Aufrufstellen), `AppContainer` (Observer öffentlich), `DiagnoseScreen`, `AudioRecordingService` (neuer `StateFlow`), `LiveCockpitCard` (Banner), `ProtokollScreen`/`ProtokollDetailScreen` (Integritätsstatus), `SettingsManager` (Onboarding-Default) |
| **Erwarteter UX-Effekt** | Die sieben internen Zustände aus [Kapitel 19](#19-visibility-of-system-status-was-intern-existiert-und-nicht-ankommt) erreichen den Nutzer; Erstnutzer bekommen eine Einführung |
| **Risiko** | niedrig bis mittel – `F-14` kann Startup-Tests brechen |
| **Testauswirkungen** | `MainActivityLaunchTest`, `AppStartupSmokeInstrumentedTest`, Diagnose-Tests, Protokoll-Tests; neue JVM-Tests für `bewerteMessintegritaet` |

### Phase 3 – Berichtsflow entschärfen

| | |
|---|---|
| **Findings** | [F-07](#f-07), [F-08](#f-08), [F-28](#f-28), [F-15](#f-15) |
| **Komponenten** | `BerichtErstellenSheet`, `BerichtScreen`, `ReportAreaSelection`, `GesamtberichtStammdatenSheet`, `StammdatenVerlaufDao` |
| **Erwarteter UX-Effekt** | Der häufigste Fehlerpfad (fehlende Gebietseinstufung) verschwindet; −13 Taps im Fehlerfall, −4 Taps im Normalfall; keine Stammdaten-Duplikate mehr |
| **Risiko** | niedrig – nur UI und eine reine Vergleichsfunktion |
| **Testauswirkungen** | `BerichtErstellenSheetTest` + Instrumented-Variante anpassen; neue JVM-Tests |

### Phase 4 – Wiederkehrende Abfragen entschärfen

| | |
|---|---|
| **Findings** | [F-16](#f-16) (**Weg B**, mit `messvorgangId` und Room-Migration 25 → 26 – Owner-Entscheidung 25.09.2026), [F-01](#f-01) Teil 3, [F-13](#f-13), [F-26](#f-26) |
| **Komponenten** | `SessionEntity`, `AppDatabase` (Migration + exportiertes Schema), `MeasurementRecorder`, `DokumentationsFotoDao`, `StammdatenVerlaufDao`, `MainActivity` (Sheet-Auslöser), `DriveUploadScreen`, Batch-Klassifizierung |
| **Erwarteter UX-Effekt** | Sheets erscheinen einmal je Messvorgang statt zweimal; Sync-Fehler sind vor Ort behebbar |
| **Risiko** | **hoch** – Schemaänderung. Die fachlichen Regeln sind entschieden ([Kapitel 35.2](#352-owner-entscheidungen-vom-25092026)), das Risiko liegt jetzt allein in der Migration: `identityHash`, Tabellen- und Spaltennamen für alles Bestehende unverändert, `fallbackToDestructiveMigration` bleibt verboten (AGENTS.md §5) |
| **Testauswirkungen** | **neuer `AppDatabaseV26MigrationTest`** (Pflicht, analog den 21 vorhandenen); neue DAO-Tests; `FotoDokumentationTest` erweitern; neuer Instrumented-Test für den Quellenwechsel; alle session-bezogenen Tests prüfen |

### Phase 5 – Fehlerprävention und Accessibility

| | |
|---|---|
| **Findings** | [F-10](#f-10), [F-21](#f-21), [F-22](#f-22), [F-23](#f-23), [F-31](#f-31), [F-33](#f-33), **[F-34](#f-34)** (nachgetragen 25.09.2026) |
| **Komponenten** | neue Speicher-Prüffunktion, `LiveCockpitCard`, Badges, `MainActivity` (Snackbar-Kanäle) |
| **Erwarteter UX-Effekt** | Messungen scheitern nicht mehr still an Speicher; zentrale Bedienelemente sind bedienbar und für TalkBack korrekt |
| **Risiko** | niedrig |
| **Testauswirkungen** | überwiegend additiv |

### Phase 6 – Strukturelle Umbauten (einzeln, jeweils mit Gerätetest)

| | |
|---|---|
| **Findings** | [S-3](#s-3--automatische-geräteverbindung-getrennt-von-der-aufzeichnung) ([F-02](#f-02), [F-03](#f-03)), danach [S-1](#s-1--messbereitschaft-als-eigenes-konzept), [S-4](#s-4--bericht-tab-als-arbeitsplatz-statt-zwischenseite), [S-5](#s-5--ein-filtermodell-für-aufnahmen-und-sessions) ([F-18](#f-18), [F-32](#f-32)) |
| **Komponenten** | `AudioRecordingService`, `ConnectionSupervisor`, `AppContainer`, `LiveCockpitCard`, `BerichtScreen`, beide Filtermodelle |
| **Erwarteter UX-Effekt** | Der Standard-Workflow fällt von 19 auf ~7 Taps ([Kapitel 10.4](#104-kennzahlenvergleich-standard-workflow-returning-user)) |
| **Risiko** | **hoch** – Foreground-Service-Lebenszyklus, laut README bereits mehrfach Gegenstand von Gerätetest-Befunden |
| **Testauswirkungen** | mehrere Service- und Cockpit-Tests; ein Gerätetest nach `docs/CHECKLISTE_GERAETETEST.md` ist zwingend |

### Phase 7 – Aufräumen (kann jederzeit dazwischen)

| | |
|---|---|
| **Findings** | [F-25](#f-25), [F-24](#f-24), [F-29](#f-29), [F-17](#f-17), [F-19](#f-19), [F-20](#f-20) |
| **Risiko** | [F-19](#f-19) und [F-20](#f-20) brauchen eine Owner-Freigabe (beide sind im README als offene Entscheidung bzw. bewusster Restbestand geführt); [F-20](#f-20) bricht viele Compose-Tests, die auf deutschen Text matchen |

### Stand der Owner-Vorbehalte (25.09.2026)

| Punkt | Stand |
|---|---|
| Reihenfolge „erst messen, dann fotografieren" | **gilt unverändert** – ausdrücklich als „nicht verhandelbar" dokumentiert (`FotoDokumentationSheet.kt:47-53`); keines der Findings rührt daran |
| Tagesregel für das **Kalibrierfoto** | **entschieden**: nicht erzwingen, aber mehrfach ermöglichen ([F-16](#f-16)) |
| Tagesregel für die **Berichtsangaben** | **entschieden**: einmal je Tag bestätigen, jederzeit korrigierbar ([F-15](#f-15)) |
| Vereinheitlichung der Statusfarben ([F-19](#f-19)) | **freigegeben** |
| Leitlinie „Verlauf vor Einzelwert" ([F-30](#f-30)) | **bestätigt** – jetzt Anforderung statt Vorbehalt |
| Schalter „Automatisch verbinden" ([F-02](#f-02)) | **freigegeben**, Default an |
| Weg A/B für [F-16](#f-16) | **Weg B** – `messvorgangId` mit Room-Migration 25 → 26 |
| Default von `onboardingCompleted` ([F-14](#f-14)) | **entschieden und umgesetzt** (PR #203): Einführung nur bei Neuinstallation, erkannt am Zustand der Einstellungsdatei |

---

## 34. Abschlussprüfung des Audits

Selbstprüfung gegen die Vorgaben des Auftrags, bevor dieses Dokument als fertig gilt.

| # | Prüfpunkt | Erfüllt | Wo |
|---|---|---|---|
| 1 | Wurde die gesamte App untersucht? | ja | [Kap. 2](#2-geltungsbereich-methodik-und-grenzen), [Kap. 3](#3-ermittelte-architektur), [Kap. 4](#4-screen--dialog--und-komponenten-inventar) |
| 2 | Alle fünf wichtigen Use-Cases analysiert? | ja | [Kap. 5](#5-standard-use-case-as-is-interaction-trace)–[Kap. 9](#9-use-case-d--messdaten-auf-vollständigkeit-prüfen) |
| 3 | Hauptworkflow vollständig nachvollzogen? | ja, inkl. beider Reihenfolgevarianten | [Kap. 5](#5-standard-use-case-as-is-interaction-trace) |
| 4 | Klicks und Interaktionen quantifiziert? | ja, aus Callback-Ketten abgeleitet, nicht geschätzt | [Kap. 5.4](#54-kennzahlen-as-is), [Kap. 6.2](#62-kennzahlen), [Kap. 10.4](#104-kennzahlenvergleich-standard-workflow-returning-user) |
| 5 | First-Time vs. Returning User berücksichtigt? | ja | [Kap. 11](#11-first-time-user-vs-returning-user) |
| 6 | Wiederkehrende Dialoge systematisch untersucht? | ja, alle 30 | [Kap. 12](#12-dialog-inventory) |
| 7 | Fotodokumentation speziell untersucht? | ja, inkl. fachlicher Prüfung der gewünschten Regel | [Kap. 13](#13-fotodokumentation-im-detail) |
| 8 | Persistenz aller Berichtsparameter geprüft? | ja, alle 13 Stammdaten- und 9 Config-Felder | [Kap. 14](#14-berichtsparameter-quelle-scope-persistenz) |
| 9 | Bluetooth-Status ausreichend analysiert? | ja, alle 10 Zustände plus Adapter/Berechtigung/GPS | [Kap. 16](#16-bluetooth-ux) |
| 10 | Berechtigungen berücksichtigt? | ja, alle sechs | [Kap. 17.2](#172-berechtigung-fehlt) |
| 11 | Mikrofonstatus berücksichtigt? | ja, inkl. der drei Fehlerpfade | [Kap. 17.3](#173-mikrofon-nicht-verfügbar) |
| 12 | Speicherplatz berücksichtigt? | ja | [Kap. 17.4](#174-kein-ausreichender-speicherplatz) |
| 13 | Exportfehler berücksichtigt? | ja, alle sieben Exportwege | [Kap. 17.5](#175-export-fehlgeschlagen) |
| 14 | Messintegrität berücksichtigt? | ja, inkl. Darstellungsvorschlag | [Kap. 20](#20-messintegrität) |
| 15 | Google-Drive-Sync berücksichtigt? | ja | [Kap. 8](#8-use-case-c--google-drive-synchronisation), [Kap. 21](#21-google-drive-sync-ux-bewertung) |
| 16 | KI-Klassifizierung berücksichtigt? | ja, alle fünf Einstiege | [Kap. 7](#7-use-case-b--ki-klassifizierung-nachträglich), [Kap. 22](#22-ki-klassifizierung-ux-bewertung) |
| 17 | High-End-Report vollständig analysiert? | ja | [Kap. 6](#6-use-case-a--nur-high-end-bericht-aus-vorhandenen-daten), [Kap. 23](#23-high-end-bericht-ux-bewertung) |
| 18 | Konkrete Code-Evidenz? | ja, Datei + Zeile bei jedem wesentlichen Punkt | durchgehend |
| 19 | Technische Änderungen je wichtigem Finding beschrieben? | ja | [Kap. 29](#29-findings-im-detail) |
| 20 | Testauswirkungen beschrieben? | ja, je Finding und als Übersicht | [Kap. 29](#29-findings-im-detail), [Kap. 32.2](#322-test-impact-je-finding) |
| 21 | Keine Dateien/Klassen erfunden? | ja – jedes genannte Symbol wurde im Repository verifiziert | [Kap. 35](#35-offene-punkte--needs-verification) nennt, was **nicht** belegbar ist |
| 22 | Quick Wins und strukturelle Änderungen getrennt? | ja | [Kap. 30](#30-quick-wins) / [Kap. 31](#31-structural-ux-improvements) |
| 23 | Jedes relevante Finding mit Priorität? | ja, 33 Findings mit P0–P3 | [Kap. 32.1](#321-alle-findings) |
| 24 | Kein Produktivcode geändert? | ja – dieser Commit enthält ausschließlich `docs/UX_UI_AUDIT.md` | – |

---

## 35. Offene Punkte / Needs verification

Alles, was in diesem Dokument **nicht** allein aus dem Code belegbar ist, steht hier. Keine
dieser Aussagen wurde im Rest des Dokuments als Tatsache behauptet.

### 35.1 Nicht geprüft, weil kein Gerät/Emulator verfügbar war

| Punkt | Warum offen | Wie zu prüfen |
|---|---|---|
| **System-Insets** | ✅ **Geprüft — kein Befund.** Auf einem Bild mit SystemUI (`google_apis`, Lauf 36169220008) ist `SystemLeistenAbstandInstrumentedTest` gelaufen und war grün: Der Titel des Startbildschirms beginnt unterhalb der Statusleiste. Die CI steht wieder auf `aosp_atd` (google_apis kostet 13 fokusbezogene Fehlschläge), der Test skippt sich dort und bleibt als Regressionsbremse erhalten. Ursprünglicher Zweifel: `enableEdgeToEdge()` + `contentWindowInsets = WindowInsets(0,0,0,0)` im äußeren `Scaffold`; der `NavHost` bekommt nur `bottom`-Padding (`MainActivity.kt:196`, `:209-214`). Ob Inhalte unter der Statusleiste landen, hängt davon ab, wie die inneren `Scaffold`s/`TopAppBar`s ihre Insets anwenden – das ist aus dem Code nicht zuverlässig ableitbar | Emulator, Gestennavigation + 3-Button-Navigation, hoher und niedriger Statusleistenbereich |
| **Tastatur/IME** | kein `imePadding()` in der gesamten UI; das Stammdaten-Sheet hat 13 Felder in einem scrollenden `ModalBottomSheet` | Emulator: unterstes Feld antippen, prüfen ob es über der Tastatur bleibt |
| **Dynamische Schriftgrößen** | ✅ **Geprüft und bestätigt** (PR #204, Emulator API 34). Ergebnis schlimmer als vermutet: der Cockpit-Titel wird nicht gekürzt, sondern verschwindet – `maxBreite=0px` bereits bei Schriftfaktor 1,0 auf schmalem Gerät. Als [F-34](#f-34) nachgetragen | erledigt |
| **Kontrastwerte** | Die Farbpalette ist konsistent definiert, aber es liegen keine gemessenen Kontrastverhältnisse vor | Accessibility Scanner auf Start, Daten, Detail, Einstellungen |
| **TalkBack-Fokusreihenfolge** | keine `focusRequester`/`focusProperties` im Code; die Reihenfolge ergibt sich aus der Komposition | TalkBack-Durchlauf je Screen |
| **Live-Region-Ansage des Pegels** | ⚠️ **Teilweise geprüft** (PR #204): Die Auszeichnung als `LiveRegionMode.Polite` ist am Emulator belegt, ebenso dass ohne laufenden Vordergrunddienst kein kalibrierter Wert erscheint (siehe [F-02](#f-02)). **Offen bleibt**, ob TalkBack tatsächlich spricht – das kann kein Test zeigen | TalkBack bei laufender Messung, am Gerät |
| **Dark-Mode-Wirkung der 28 Farbliterale** | der Codebefund ist eindeutig, die optische Wirkung nicht gemessen | Screenshot-Vergleich hell/dunkel |
| **Tatsächliche Dauer des Chaquopy-Berichtslaufs** | keine Messung möglich | Gerätetest mit 30 Messtagen |
| **Verhalten bei vollem Speicher** | Ableitung aus dem Code; der automatische Neustart nach Schreibfehler ist laut README selbst nur rekonstruiert | Gerätetest mit künstlich gefülltem Speicher |
| **Foreground-Service-Typ ohne `RECORD_AUDIO`** | relevant für [F-02](#f-02): `berechneForegroundServiceType()` kann `0` liefern, der typlose Fallback existiert (`:409`, `:423`), wurde aber in dieser Kombination nicht beobachtet. ✅ **Geprüft — und die Antwort ist negativ:** Der Dienst kommt nicht in den Vordergrund, der „typlose" Rückfall ist nicht typlos. Als [F-35](#f-35) nachgetragen (P2, Phase 2 — **kein** Blocker für [F-02](#f-02), siehe dort) | erledigt — Gerätetest: Mikrofon-Berechtigung entziehen, „nur verbinden" auslösen |

### 35.2 Owner-Entscheidungen vom 25.09.2026

Die sieben offenen Fragen sind beantwortet. Die Antworten sind hier wörtlich sinngemäß
festgehalten und in die betroffenen Findings eingearbeitet.

| # | Frage | **Entscheidung** | Wirkung auf das Audit |
|---|---|---|---|
| 1 | Kalibrierfoto nur einmal je Kalendertag? | **Nicht erzwingen, aber mehrfach ermöglichen.** Es soll möglich sein, öfter als einmal pro Tag ein Kalibrierfoto zu machen; verlangt wird es nicht wiederholt. | Bestätigt die Regel aus [Kap. 13.3](#133-fachliche-prüfung-des-gewünschten-ux-prinzips): Abfrage einmal je Kalendertag, dazu eine jederzeit sichtbare Aktion „Neues Foto". → [F-16](#f-16) |
| 2 | Ist `onboardingCompleted = true` als Default Absicht? | **Nein – Einführung nur für Neuinstallationen.** Owner-Antwort: „Ja, nur für neue Installationen." Die zusätzlich erwogene Erkennung über Google Cloud wurde geprüft und verworfen: sie setzt Anmeldung und Netz beim allerersten Start voraus, und die Drive-Synchronisierung ist per Default aus. | [F-14](#f-14) **umgesetzt und gemergt** (PR #203): Der Default hängt jetzt vom Zustand der Einstellungsdatei ab – ohne neuen Schlüssel und ohne Migration. Erklärung in Alltagssprache siehe unten. |
| 3 | Zeitgebundene Stammdatenfelder weiterhin vorbefüllen? | **Tagesregel, mit Korrekturmöglichkeit.** Wurden die Parameter an einem Tag einmal bestätigt, wird an diesem Tag nicht erneut aufgefordert. Der Nutzer muss sie jederzeit korrigieren können. | Löst den Kern von [F-15](#f-15) und [F-16](#f-16). Residualrisiko siehe Hinweis unter der Tabelle. |
| 4 | Gilt „Verlauf vor Einzelwert" weiter? | **Ja, Verlauf.** | [F-30](#f-30) ist damit kein Owner-Vorbehalt mehr, sondern eine bestätigte Anforderung – Priorität von P3 auf **P2** angehoben. |
| 5 | 28 Farbliterale vereinheitlichen? | **Ja.** | [F-19](#f-19) ist freigegeben; der README-Vorbehalt entfällt. |
| 6 | Schalter „Automatisch verbinden" einführen? | **Ja, Default an.** | [F-02](#f-02) ist freigegeben, inklusive Default-Wert. |
| 7 | Weg A oder Weg B für [F-16](#f-16)? | **Weg B** – `messvorgangId` mit Room-Migration 25 → 26. | [F-16](#f-16) wird strukturell gelöst; [F-12](#f-12) und [F-01](#f-01) profitieren mit. Aufwand steigt von M auf L, Risiko auf hoch. |

**Residualrisiko zu Entscheidung 3, ausdrücklich benannt:** Die Tagesregel beseitigt die
Wiederholung, nicht die Aktualität. Das Feld `wetter` kann damit bis zu 24 h alt im Bericht
stehen (morgens bestätigt, abends gemessen). Empfehlung, ohne Rückfrage umsetzbar: den
Zeitstempel der letzten Bestätigung in der Inline-Zeile mitführen
(„Berichtsangaben bestätigt 08:14 · ändern"), damit eine veraltete Angabe sichtbar bleibt,
statt sie zu erzwingen oder zu verschweigen.

**Zu Entscheidung 2, in Alltagssprache:** `onboardingCompleted` ist ein gespeicherter Merker mit
genau einer Bedeutung: „Hat dieser Nutzer die vierseitige Einführung schon gesehen?" Die App
fragt beim Start danach und zeigt die Einführung nur, wenn der Merker `false` ist. Weil der
Standardwert auf `true` steht (`SettingsManager.kt:606`), gilt bei einer frischen Installation
sofort „schon gesehen" – die Einführung erscheint also nie von selbst, sondern nur über
Einstellungen → „Einführung erneut anzeigen". Ein Einzeiler (`true` → `false`) behebt das;
zu klären bleibt nur, ob **bestehende** Installationen die Einführung dann einmalig sehen
sollen oder nicht.

### 35.3 Beobachtungen, die nicht ins Audit-Raster passen

* **Das README ist an einer Stelle überholt.** Unter „Bekannte Einschränkungen" steht:
  „`AppContainer.meterTransport` ist fest auf `BleMeterTransport` verdrahtet, nicht über ein
  Test-Double ersetzbar". Tatsächlich existiert der Parameter `meterTransportOverride`
  (`AppContainer.kt:48`, ausgewertet `:55-57`), und mindestens 15 Tests nutzen ihn bereits
  (u. a. `androidTest/.../MeterScreenInstrumentedTest.kt:239`,
  `androidTest/.../MeterDisconnectConfirmationInstrumentedTest.kt:33`). Der daraus abgeleitete
  Satz, die `liveRegion`-Auszeichnung sei nicht testbar, gilt in dieser Form nicht mehr.
* **Die Zahl „~59 hartcodierte deutsche UI-Literale" im README ist zu niedrig.** Allein
  `Text("…")`-Aufrufe unter `ui/` ergeben 156 Treffer ([F-20](#f-20)).
* **`docs/BESTANDSAUFNAHME_UI.md` und `docs/UX_DESIGN_PLAN.md`** beschreiben eine
  Informationsarchitektur mit „Diagnose" als eigenem Hauptbereich; im Code ist Diagnose kein
  Bottom-Nav-Ziel, sondern über Einstellungen und den Problem-Banner erreichbar. Das ist keine
  Fehlfunktion, aber eine Abweichung zwischen Dokument und Code.
* **Zwei Dialoge behandeln denselben Fall mit unterschiedlichem Text** („Mögliches Ersatzgerät
  gefunden" in `MeterScreen.kt:195` vs. „Neues Gerät mit bekanntem Namen?" in
  `MeterControlCard.kt:291`). Beide gehören zur Spoofing-Erkennung und sollten
  zusammengeführt werden – enthalten in [Kap. 12](#12-dialog-inventory), Zeile 9/10.

---

*Ende des Audits. Die Owner-Fragen sind seit dem 25.09.2026 bis auf eine beantwortet
([Kapitel 35.2](#352-owner-entscheidungen-vom-25092026)); nächster Schritt ist damit Phase 1 der
[Roadmap](#33-proposed-implementation-roadmap), die von keiner offenen Frage abhängt.*
