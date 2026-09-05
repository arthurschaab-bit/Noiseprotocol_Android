# Automatisierte Testabdeckung

**Status:** Ist-Dokumentation des automatisierten Testsystems auf `main` nach Merge von PR #117.  
**Referenzstand:** `main` @ `51375a93c87078c4e1440438206930488244f235` (05.09.2026).  
**Instrumentierte Tests:** 67 Tests in 18 Testdateien, ausgeführt auf Android 14 / API 34 ATD.  
**CI:** `.github/workflows/androidci.yml` und `.github/workflows/emulator-tests.yml`.

> Diese Datei beschreibt, **was aktuell tatsächlich automatisiert geprüft wird**.  
> `docs/TESTPLAN_INSTRUMENTIERT.md` ist der ursprüngliche Soll-/Planstand und enthält historische Aussagen und Zählwerte (unter anderem noch „34 instrumentierte Tests“). Bei Abweichungen ist für den aktuellen Implementierungsstand diese Datei maßgeblich.

---

## 1. Teststrategie in Kurzform

Die App verwendet zwei sich ergänzende Testebenen:

1. **JVM- und Robolectric-Tests (`app/src/test`)**
   - schnelle Logik-, State-, Persistenz- und Compose-Tests ohne echten Emulator,
   - große Abdeckung von BLE-/Messlogik, Alarmierung, Datenbankmigrationen, Drive-Sync, Audioanalyse, Retention, Berichten und UI-Zuständen,
   - deterministische Fakes und Android-Shadows für reproduzierbare Fehler- und Randfälle.

2. **Instrumentierte Tests (`app/src/androidTest`)**
   - laufen auf einem echten Android-Systemimage,
   - prüfen reale Compose-Semantics und Touch-Koordinaten, Foreground-Service-Lebenszyklus, `NotificationManager`, `MediaPlayer`, Scoped Storage, `PdfDocument`, Room/SQLite und Android-System-Intents,
   - decken die Stellen ab, an denen Robolectric entweder nur Shadows bietet oder reale Android-Lifecycle-/Systemintegration wichtig ist.

Beide Ebenen werden in GitHub Actions ausgeführt. Ein grüner Emulatorlauf ersetzt dabei **keinen** Hardwaretest mit einem realen PCE-323, Mikrofon, Lautsprecher, Kamera oder einem echten Google-/ntfy-Backend; diese Grenzen sind in Abschnitt 9 dokumentiert.

---

## 2. CI-Gates

### 2.1 Android CI – Build, Lint, JVM und Robolectric

Workflow: `.github/workflows/androidci.yml`

Ausführung bei Pull Requests gegen `main`, Pushes auf `main` und manuell per `workflow_dispatch`.

| Schritt | Kommando / Mechanismus | Bedeutung |
|---|---|---|
| Debug- und Test-Build | `./gradlew assembleDebug assembleDebugAndroidTest compileDebugAndroidTestKotlin --no-daemon --stacktrace` | Kompiliert App und Instrumentationstests. |
| Android Lint | `./gradlew lintDebug --no-daemon --stacktrace` | Android-spezifische statische Analyse. Blockierend. |
| ktlint | `./gradlew ktlintCheck --no-daemon --stacktrace` | Kotlin-Stilprüfung. Aktuell **nicht blockierend** (`continue-on-error: true`). |
| JVM/Robolectric | `./gradlew testDebugUnitTest test --no-daemon --stacktrace --continue` | Explizites Debug-Testgate plus vollständiges `test`-Gate, damit auch weitere Unit-Test-Varianten erhalten bleiben. Blockierend. |
| Testbericht | `.github/scripts/testbericht.py` | Schreibt Testergebnisse direkt in die Actions-Zusammenfassung. |
| Coverage | `koverXmlReportDebug` + `koverHtmlReportDebug` | Debug-JVM-Coverage via Kover. Kein Ersatz für Emulator-/Hardware-Coverage. |
| Artefakte | Lint, ktlint, Testreports, Coverage, Debug-APK | Diagnose und Installationsartefakte werden hochgeladen. |

### 2.2 Android Emulator Tests – API 34

Workflow: `.github/workflows/emulator-tests.yml`

Ausführung bei Pull Requests gegen `main`, Pushes auf `main` und manuell.

Aktuelle Emulator-Konfiguration:

- Android 14 / **API 34**
- AOSP ATD, `x86_64`
- KVM-Hardwarebeschleunigung
- 2 CPU-Kerne
- 2048 MB RAM, 512 MB Heap
- Animationen deaktiviert
- `-no-snapshot -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none`

Ablauf:

```text
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace
```

Bei jedem Lauf werden die Instrumentationstestberichte hochgeladen. Bei Fehlern werden zusätzlich ADB-/Logcat- und `dumpsys activity`-Diagnosen als Artefakte gespeichert.

---

## 3. Instrumentierte Tests: aktueller Bestand

Der Stand nach PR #117 umfasst **67 instrumentierte Tests in 18 Dateien**:

- 61 UI-/Navigationstests,
- 3 Service-Tests,
- 2 Export-Tests,
- 1 Basistest für den App-Kontext.

Die folgende Matrix beschreibt die tatsächliche Funktion der Suiten, nicht nur ihre Dateinamen.

### 3.1 UI, Navigation und App-Startup

| Testdatei | Tests | Was geprüft wird | Fehler-/Randfälle und Emulator-Mehrwert |
|---|---:|---|---|
| `ui/AppNavigationBarInstrumentedTest.kt` | 3 | Auswahl und Bedienbarkeit der Bottom-Navigation; Wechsel Start/Protokoll/Einstellungen; Sichtbarkeit auf Route-Wechseln | Mehrfaches schnelles Antippen; ausgewählter Zustand nach Wechseln. |
| `ui/AppStartupSmokeInstrumentedTest.kt` | 1 | Kaltstart der App und Laden der Kern-Screens; Navigation Start → Protokoll → Einstellungen → Start | Erkennt Integrationsfehler in Application-/Activity-/NavHost-/Theme-Initialisierung. |
| `ui/MainActivityNavigationAndroidTest.kt` | 7 | Drawer-Navigation, Bottom-Navigation, Bluetooth-Badge/Pairingdialog, Overflow-Menü, Filterpanel, Dauermessungs-Card | Prüft mit `TestNavHostController`, dass wiederholtes Tippen auf denselben Tab **keinen doppelten Backstack-Eintrag** erzeugt. Seedet für die Session-Card echte Room-Daten. |
| `ui/HomeScreenInstrumentedTest.kt` | 3 | Home-Filter und zentrale `NoiseRecordItem`-Aktionen | Play, KI-Erkennung, Favorit, Lernen, Löschen, Label-Chips und Long-Click werden als echte Compose-Interaktionen ausgelöst; ausgewählter Zustand wird geprüft. |
| `ui/DiagnoseScreenInstrumentedTest.kt` | 1 | Diagnose-Screen mit Zustand, DB-Logeintrag und Zurück-Navigation | Schreibt einen echten `DiagnosticLogEntity` in Room und scrollt die reale `LazyColumn` bis zum Logeintrag. |

### 3.2 Messgerät / PCE-323 / BLE

| Testdatei | Tests | Was geprüft wird | Fehler-/Randfälle und Determinismus |
|---|---:|---|---|
| `ui/MeterScreenAndroidTest.kt` | 7 | TopAppBar Drawer/Back, Scan-Button, gekoppelt/ungekoppelt, Verbinden-UI, Live-Pegel und Parameterkarte | `FakeMeterTransport` liefert deterministische Frames; bestätigte vs. unbestätigte Frequenzbewertung wird getrennt geprüft. |
| `ui/MeterScreenInstrumentedTest.kt` | 6 | Titel/Scan/Back; Scanfehler; Geräte-Pinning; lange Liste; Live-Pegel | BLE-Scan wird über den `AppContainer` deterministisch eingespeist. Scan-Exception muss sichtbar werden; schnelles Mehrfachtippen darf nicht crashen. Gleicher Name/andere MAC muss Warnung, Abbruch und explizite Bestätigung korrekt behandeln. Bestätigung startet den echten Foreground Service. |

Der Langlisten-Test ist bewusst zweigeteilt:

- 18 Fake-Geräte werden vollständig eingespeist,
- echte `swipeUp()`-Touchgesten müssen die `LazyColumn` sichtbar bewegen,
- weitergescrollt wird, bis die letzte reale Gerätekarte `AA:BB:CC:DD:EE:17` komponiert ist,
- die letzte Karte muss anschließend vollständig sichtbar sein,
- zusätzlich muss `GERAETE_LISTE_ENDE_TAG` am Listenende komponiert sein.

Damit testet die Suite sowohl echte Touch-Scrollbarkeit als auch die tatsächliche Erreichbarkeit des Listenendes, ohne eine flakey Serie von Fling-Gesten an einen künstlichen 1-dp-Marker zu koppeln.

### 3.3 Protokoll und Export

| Testdatei | Tests | Was geprüft wird | Fehler-/Randfälle und Emulator-Mehrwert |
|---|---:|---|---|
| `ui/ProtokollScreenAndroidTest.kt` | 8 | Leerzustand, Drawer/Back, Sessionkarten, aktive/abgeschlossene Badges, Suche, FAB, Filter, Zeitraumdialog | Suchfilter inkl. Clear und „keine Treffer“; FAB ist bei `null`-Callback bewusst nicht vorhanden. Room wird vor/nach Tests bereinigt. |
| `ui/ProtokollScreenInstrumentedTest.kt` | 1 | Minimaler Leerzustands-/Back-Regressionsschutz | Frische/leere DB darf den Screen nicht beschädigen. |
| `ui/ProtokollDetailScreenInstrumentedTest.kt` | 2 | Laden einer echten Session mit Messwerten, Kennwerte, Verlauf, Exportbuttons, Back | Wartet explizit auf asynchrones Room-Laden statt nur `waitForIdle()`; ungültige Session-ID muss einen sichtbaren Fehlerzustand liefern. |
| `report/ProtokollExportAndroidTest.kt` | 2 | Reale CSV- und PDF-Erzeugung auf Scoped Storage und Share-Intent | CSV: UTF-8-BOM, Semikolon, Header und Werte. PDF: nicht leer und `%PDF`-Header. Leere Session muss trotzdem sichere CSV/PDF-Ausgabe erzeugen. Espresso Intents validiert `ACTION_CHOOSER`. |

Der PDF-Test ist wichtig, weil hier das echte Android-`PdfDocument` ausgeführt wird. Das ist eine andere Fehlerklasse als ein reiner JVM-/Robolectric-Test.

### 3.4 AudioPlayer

| Testdatei | Tests | Was geprüft wird | Fehler-/Randfälle |
|---|---:|---|---|
| `ui/AudioPlayerScreenInstrumentedTest.kt` | 5 | Laden und Abspielen einer gültigen WAV-Datei; Lifecycle-Cleanup | Nicht existierende, beschädigte und zwischenzeitlich gelöschte Dateien zeigen einen Fehler statt zu crashen; Play wird deaktiviert. Blank-Pfad für reine Pegelmessung wird separat behandelt. `MediaPlayer.release()` wird beim Entfernen aus der Composition über einen Test-Hook verifiziert. |

### 3.5 Einstellungen, Alarmierung und System-Intents

| Testdatei | Tests | Was geprüft wird | Fehler-/Randfälle und Determinismus |
|---|---:|---|---|
| `ui/SettingsScreenAndroidTest.kt` | 8 | Drawer/Back, Lite-/Pro-Modus, Sprache, Triggerquelle, KI-Modus, ntfy-Topic-Eingabe, Diagnose-Navigation | Persistenz wird über `SettingsManager` geprüft und im Cleanup wiederhergestellt. |
| `ui/SettingsScreenInstrumentedTest.kt` | 8 | Bildschirmende, Sample-Rate, dB-Slider, ntfy-Lifecycle, OEM-System-Intents, Testalarm, fehlendes `POST_NOTIFICATIONS` | Slider wird mit echten Touch-Swipes auf ca. 30 dB und 100 dB gebracht und Persistenz geprüft. Leeres ntfy-Topic wird beim ersten Aktivieren automatisch erzeugt; vorhandenes Topic bleibt erhalten. Batterieoptimierungs- und Exact-Alarm-Intents werden atomar nach Action **und** `package:`-URI geprüft. Testalarm kann gestartet und gestoppt werden. Fehlende Notification-Berechtigung darf den lokalen Alarmweg nicht als Ganzes abbrechen. |

### 3.6 Foreground Service und Quick Settings

| Testdatei | Tests | Was geprüft wird | Emulator-Mehrwert |
|---|---:|---|---|
| `ui/ServiceControlInstrumentedTest.kt` | 1 | `LiveCockpitCard` im echten Idle-Servicezustand; Startbutton sichtbar, erreichbar und enabled | Setup/Cleanup leiten den Zustand aus dem realen `AudioRecordingService` ab, statt statischen Testzustand vorauszusetzen. |
| `service/ForegroundServiceAndroidTest.kt` | 2 | Echter Start/Stop des `AudioRecordingService` über UI und über direkten Intent | Prüft `NotificationManager.activeNotifications`: Notification ID 1 existiert, ist `ongoing`, verwendet `noise_monitoring_channel` und verschwindet beim Stop. Gewährt RECORD_AUDIO und API-33+-Notification-Permission über `UiAutomation`. |
| `service/TileServiceInstrumentedTest.kt` | 1 | Manifest-Registrierung des Quick-Settings-Tile-Service und dessen Icon | Prüft `exported`, `BIND_QUICK_SETTINGS_TILE` und reale Drawable-Auflösung. |

### 3.7 Basistest

| Testdatei | Tests | Was geprüft wird |
|---|---:|---|
| `ExampleInstrumentedTest.kt` | 1 | Ziel-App-Kontext hat Package `com.example.lrmprotokoll`. |

**Summe: 67 instrumentierte Tests.**

---

## 4. JVM- und Robolectric-Abdeckung

Die JVM-Ebene ist deutlich breiter als die 67 Instrumentationstests. Sie liegt unter `app/src/test/java/com/example/lrmprotokoll/` und wird im CI-Gate mit `testDebugUnitTest` und `test` ausgeführt.

Die folgende Übersicht nennt die zentralen Testbereiche und repräsentative Dateien. Sie ist bewusst eine Funktionsübersicht und keine feste Gesamtzahl: Neue JVM-Tests sollen hinzugefügt werden können, ohne dass eine manuell gepflegte Zahl sofort falsch wird.

| Bereich | Repräsentative Tests | Was damit abgesichert wird |
|---|---|---|
| **UI / Compose** | `HomeNavigationComposeTest`, `MeterScreenComposeTest`, `SettingsScreenComposeTest`, `ProtokollDetailScreenComposeTest`, `DiagnoseScreenComposeTest`, `ServiceControlComposeTest`, `AudioPlayerScreenComposeTest`, `LiveCockpitCardTest`, `OemDeviceHelperCardTest` | Sichtbarkeit, Compose-Zustände, Komponentenlogik, Navigation-Hilfslogik, Screen-Randfälle ohne Emulator-Overhead. |
| **PCE-323 / BLE** | `BleScannerTest`, `ConnectionSupervisorTest`, `FakeMeterTransportTest`, `GeraetePinningTest`, `Pce323FrameDecoderTest` plus `meter/ble/` | Decoder, Pinning, Scan-/Transportlogik, Zustandsautomat, Reconnects, Cadence/Stall-Verhalten und deterministische Transportfehler. |
| **Alarmierung** | `AlarmCoordinatorTest`, `AlarmManagerDeadlineSchedulerTest`, `AlertMessagesTest`, `LocalNotificationAlertChannelTest`, `NtfyAlertChannelTest`, `HeartbeatPingerTest`, `HeartbeatWorkerTest` | Karenz/Alarm/Entwarnung, Scheduler, Textbildung, lokale Notification-Logik, ntfy, Heartbeat/Totmannschaltung. |
| **Audio / Klassifikation** | `AudioRecordingServiceStartupTest`, `AufnahmeHaertungTest`, `NoiseClassifierTest`, `SoundClassifierTest`, `BatchKlassifizierungTest`, `BaulaermBefundTest`, `ImpulsanalyseTest`, `TriggerWachhundTest`, `ZeitaggregationTest` | Service-Startup, Aufnahmehärtung, Klassifikation, Label-/Score-Logik, Impuls-/Baulärmanalyse, Watchdog und Aggregation. |
| **Messreihe / Akustik / Retention** | `MeasurementRecorderTest`, `AkustischeKennwerteTest`, `AusfallbaenderTest`, `MeterTriggerSourceTest`, `ReconnectsTest`, `MessreiheCsvTest`, `RetentionCoordinatorTest`, `RetentionWorkerTest`, Speicher-Aufräumtests | Messwertaufnahme, Kennwerte, Ausfälle, Triggerquellen, Reconnects, CSV, Verdichtung/Retention und Speicherbereinigung. |
| **Datenbank / Migrationen** | `AppDatabaseMigrationTest`, `AppDatabaseV4MigrationTest`, `AppDatabaseV7MigrationTest` bis `AppDatabaseV17MigrationTest`, `MeasurementDaoTest`, `SessionDaoTest` | Historische Schema-Migrationen, DAO-Verhalten und Kompatibilität bestehender Installationen. |
| **Settings / Sicherheit** | `SettingsManagerDriveTest`, `SettingsManagerSecureTest`, `SettingsManagerVideoTest`, mehrere UI-Settings-Tests | Persistenz und sicherheits-/featurebezogene Settings. |
| **Google Drive** | `DriveSyncCoordinatorTest`, `DriveSyncWorkerTest`, `DriveSyncNotifierTest`, `GoogleDriveApiClientTest`, `GoogleDriveResumableUploadTest`, `DriveWavUploadAndCsvTest`, `LevelSampleCollectorTest`, `PegelAggregatorTest`, `WavHourlyZipperTest` | Ordner-/Ablagelogik, API-Client, Resumable Upload, Worker/Coordinator, WAV/CSV-Paketierung und Aggregation. |
| **Backup** | `SicherungEinstellungenTest`, `SicherungManagerTest` | Sicherungs-/Restore-nahe Logik und Einstellungen. |
| **Berichte** | `BerichtDateiTest`, `MessreiheExportTest`, `PeriodenBerichtDatenTest`, `TagesberichtDatenTest`, `ReportManagerTest`, PDF-Untertests | Berichtsdaten, Exporte, Einheiten und Hinweislogik. |
| **Foto** | `BildverarbeitungTest` | Bildverarbeitungslogik. |
| **Video** | `KeinKameraTonTest`, `VideoTonMitschnittTest`, `VideoTonSynchronisationTest`, `VideospeicherTest` | Audio-/Video-Kopplung, Synchronisation und Speichermanagement. |

### Beispiel: lokale Notification unter Robolectric

`LocalNotificationAlertChannelTest` testet unter API 34 unter anderem:

- reale Permission-Abfrage aus Sicht des Channels über Robolectric-Permission-Shadows,
- `POST_NOTIFICATIONS` erlaubt/verweigert,
- ohne Permission wird **nur die Notification unterdrückt**; `send()` darf den lokalen Alarmpfad nicht komplett abbrechen,
- korrekter Notification-Kanal, Titel und Text,
- `IMPORTANCE_HIGH`,
- Entwarnungs-Titel,
- wiederholtes Senden ersetzt die bestehende Notification statt zu stapeln,
- Vibrationsmuster und `ACTION_STOP_ALARM`.

Damit wird die Alarm-Logik schnell und deterministisch geprüft; der Emulator ergänzt dazu den realen `NotificationManager`-/Foreground-Service-Pfad.

---

## 5. Test-Fakes und Test-Hooks

Die Testbarkeit wurde so umgesetzt, dass der Produktionspfad standardmäßig unverändert bleibt. Test-Hooks sind nullable/optional oder über einen testweise eingesetzten `AppContainer` gekapselt.

### 5.1 `AppContainer`

Der Container erlaubt gezielte Injektion:

```kotlin
class AppContainer(
    context: Context,
    meterTransportOverride: MeterTransport? = null,
    internal val bleScanProviderOverride: (() -> Flow<BleDevice>)? = null,
)
```

Bedeutung:

- Produktion ohne Override → realer `BleMeterTransport` und realer Scanner,
- Tests → `FakeMeterTransport` oder deterministischer BLE-Scan-Flow,
- der BLE-Scan-Fake ist **containergebunden**, nicht prozessglobal; dadurch kann ein fehlgeschlagener Test keinen globalen Scannerzustand an eine andere Testklasse vererben.

### 5.2 App-Container austauschen

Instrumentierte Tests verwenden `LaermprotokollApp.setCustomContainer(...)` und `resetContainer()`, um reale App-Komponenten mit gezielt gefakten Transporten zu kombinieren. Cleanup ist Teil der Tests, damit Testzustände nicht zwischen Methoden leaken.

### 5.3 OEM-Systemzustände

`OemDeviceHelperCard` besitzt nullable Test-Hooks:

```kotlin
notificationPermissionOverride: Boolean? = null
exactAlarmPermissionOverride: Boolean? = null
batteryOptimizedOverride: Boolean? = null
```

`null` bedeutet in Produktion immer: realen Systemzustand verwenden. Tests können die Sichtbarkeit der relevanten Buttons deterministisch erzwingen und danach die erzeugten Intents prüfen.

### 5.4 Lokale Notification

`LocalNotificationAlertChannel` unterstützt einen `notificationPermissionOverride`. Das vermeidet in Instrumentationstests ein echtes Permission-Revoke mitten im Prozess, das Android selbst beenden oder neu starten kann. Die reale Permission-Auswertung wird separat in Robolectric getestet.

### 5.5 AudioPlayer-Lifecycle

`AudioPlayerScreen` stellt einen Test-Hook für den Release-Lifecycle bereit. Dadurch kann der Instrumentationstest verifizieren, dass `MediaPlayer.release()` beim echten Entfernen des Screens aus der Composition ausgeführt wurde.

### 5.6 Weitere deterministische Mechanismen

- direkte Testdaten in Room/SQLite,
- `FakeMeterTransport` für Live-Pegel und Verbindungszustände,
- Espresso `Intents` für System-/Share-Intents,
- `UiAutomation.grantRuntimePermission(...)` für reproduzierbare Permission-Voraussetzungen,
- `TestNavHostController` für echte Backstack-Assertions,
- explizites Warten auf Service-, Flow- und Room-Zustände statt nur `waitForIdle()`,
- Wiederherstellung geänderter `SettingsManager`-Werte in `@After`/`finally`.

---

## 6. Was ein grüner CI-Lauf tatsächlich beweist

Wenn **Android CI** und **Android Emulator Tests** auf demselben Commit grün sind, ist unter anderem nachgewiesen:

- Debug-App und AndroidTest-APK kompilieren,
- Android Lint ist grün,
- JVM- und Robolectric-Tests sind grün,
- Release-/weitere Unit-Test-Tasks, die unter `test` hängen, werden nicht versehentlich aus dem Gate entfernt,
- App startet auf API 34,
- zentrale Navigation und Backstack-Regressionen funktionieren,
- zentrale Screens rendern und wichtige UI-Aktionen lassen sich bedienen,
- BLE-/Meter-UI kann mit deterministischen Scan- und Transportdaten durchgespielt werden,
- Scanfehler, Pinning-Konflikte und lange Gerätelisten sind regressionsgesichert,
- realer Foreground-Service-Lifecycle und Ongoing-Notification funktionieren auf API 34,
- AudioPlayer behandelt reale Datei-/MediaPlayer-Randfälle ohne Absturz und räumt den Player auf,
- Room-basierte Protokollansichten funktionieren,
- PDF/CSV werden auf Android Scoped Storage tatsächlich erzeugt,
- Settings-Grenzwerte, ntfy-Topic-Lifecycle, Alarm-UI und relevante System-Intents sind automatisiert geprüft,
- Testreports und bei Fehlern Android-Diagnosen stehen als Actions-Artefakte zur Verfügung.

Ein grüner Lauf bedeutet **nicht**, dass jede externe Hardware- oder Cloudintegration real end-to-end getestet wurde. Siehe nächster Abschnitt.

---

## 7. Was bewusst nicht durch den Emulator-CI ersetzt wird

### 7.1 Echtes PCE-323 / reales BLE-GATT

CI besitzt kein physisches PCE-323. Scan-UI, Pinning, Zustandslogik und Transport-/Frame-Verhalten werden deterministisch getestet, aber folgende Punkte bleiben Hardware-/Gerätetests:

- tatsächliches Advertising des konkreten PCE-323,
- reale GATT-Service-/Characteristic-Kompatibilität,
- RF-Störungen, Reichweite und Hersteller-Bluetooth-Stack,
- reale Timing-/Reconnect-Effekte unter ungünstigen Funkbedingungen.

### 7.2 Mikrofon, Lautsprecher und Kamera

Der Emulator wird mit `-noaudio` und `-camera-back none` gestartet. Getestet werden Lifecycle, Dateien, UI und Serviceintegration, nicht die physikalische Qualität von:

- Mikrofonaufnahme,
- Lautstärke/Alarmton,
- Vibrationsmotor,
- Kamera-/Videobild.

### 7.3 Google Drive E2E

Drive-Client, Worker, Coordinator, Upload- und Datenlogik sind umfangreich in JVM-Tests abgesichert. CI führt jedoch keinen produktiven OAuth-Login in ein echtes Google-Konto und keinen vollständigen Cloud-E2E-Test gegen einen realen Drive-Ordner aus.

### 7.4 ntfy E2E

ntfy-Channel- und Einstellungslogik sind automatisiert getestet. Ein tatsächliches Push-Eintreffen auf einem externen Zweitgerät ist kein Pflichtgate des Emulator-CI.

### 7.5 Android-Versionen

Instrumentierung läuft derzeit nur auf **API 34**. Es gibt keine automatische Geräte-/API-Matrix für z. B. API 29, 31, 33, 35 oder 36.

### 7.6 Vollständige Systemdialog-Bedienung

Die Tests prüfen Permission-Zustände und System-Intents deterministisch. Sie klicken nicht jeden OEM-/Android-Systemdialog wie ein Mensch durch. Das reduziert Flakiness und trennt App-Verhalten von variierenden System-UIs.

### 7.7 Release-Instrumentierung

`connectedDebugAndroidTest` prüft die Debug-Variante. Es gibt kein entsprechendes `connectedReleaseAndroidTest`-Gate. JVM-seitig bleibt das vollständige `test`-Gate erhalten.

### 7.8 ktlint

ktlint läuft und erzeugt einen sichtbaren Report, ist derzeit aber **nicht blockierend**. Ein grüner Gesamtjob bedeutet daher nicht automatisch „keine ktlint-Verstöße“.

### 7.9 Coverage-Zahl

Kover misst Debug-JVM-Coverage. Diese Zahl umfasst nicht automatisch die Qualität oder Reichweite der 67 Emulator-/Hardware-Szenarien und darf nicht als alleinige Produkt-Testabdeckung interpretiert werden.

---

## 8. Lokale Ausführung

### JVM / Robolectric

```bash
./gradlew testDebugUnitTest test --no-daemon --stacktrace --continue
```

Nur Debug-JVM/Robolectric:

```bash
./gradlew testDebugUnitTest --no-daemon --stacktrace
```

### Android Lint

```bash
./gradlew lintDebug --no-daemon --stacktrace
```

### ktlint

```bash
./gradlew ktlintCheck --no-daemon --stacktrace
```

### Instrumentation auf verbundenem Gerät/Emulator

```bash
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace
```

Für eine CI-nahe Emulatorausführung sollte API 34 verwendet werden.

### Coverage

```bash
./gradlew koverXmlReportDebug koverHtmlReportDebug --no-daemon --stacktrace
```

---

## 9. Pflege dieser Dokumentation

Diese Datei ist eine **Ist-Dokumentation**. Sie soll bei Änderungen am Testsystem zusammen mit dem Code aktualisiert werden.

Aktualisieren, wenn mindestens einer dieser Punkte eintritt:

- Instrumentationstest-Datei hinzugefügt oder entfernt,
- Anzahl oder Zweck der instrumentierten Tests ändert sich wesentlich,
- neuer produktionsneutraler Test-Hook/Fake wird eingeführt,
- CI-Kommandos oder API-Level ändern sich,
- ein bislang manueller Hardware-/Cloud-Test wird automatisiert,
- ein bislang blockierendes Gate wird non-blocking oder umgekehrt,
- neue wesentliche bekannte Testlücke entsteht.

### Zählregel für Instrumentationstests

Die Zahl der Instrumentationstests wird aus den real vorhandenen `@Test`-Methoden unter `app/src/androidTest` bzw. aus dem ausgeführten `connectedDebugAndroidTest`-Lauf abgeleitet — **nicht** aus dem historischen Testplan.

Referenz nach PR #117:

```text
UI / Navigation: 61
Service:          3
Export:           2
Basistest:        1
-------------------
Gesamt:          67
```

### Verhältnis zum alten Testplan

`docs/TESTPLAN_INSTRUMENTIERT.md` bleibt nützlich als ursprüngliche Anforderungsliste und zur Nachvollziehbarkeit, warum bestimmte Regressionstests entstanden sind. Er ist aber kein verlässliches Inventar des aktuellen Stands, solange historische Zahlen, Zeilenreferenzen oder noch nicht aktualisierte Soll-Formulierungen darin stehen.

Für die Frage **„Was können unsere Tests heute?“** ist `docs/TESTABDECKUNG.md` die maßgebliche Datei.
