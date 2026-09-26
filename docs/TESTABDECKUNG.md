# Automatisierte Testabdeckung

**Status:** Ist-Dokumentation des automatisierten Testsystems auf `main` nach Abschluss der
Button/Screen-Coverage-Initiative (Plan `sorted-orbiting-crown.md`, Phasen 0–9, 17.09.2026).
**Zahlen:** siehe `docs/KENNZAHLEN.md` — automatisch generiert, **nicht** von Hand pflegen und
nicht hier duplizieren. Diese Datei beschreibt Strategie, CI-Gates, Testarten und bekannte
Grenzen; für die aktuelle Anzahl an Tests/Dateien ist `KENNZAHLEN.md` maßgeblich.

> `docs/TESTPLAN_INSTRUMENTIERT.md` ist der ursprüngliche, historische Soll-Plan (Einzelelement-
> Tabellen mit Zeilennummern-Referenzen aus einem frühen Entwicklungsstand). Er wird nicht mehr
> gepflegt und seine Zeilenangaben sind größtenteils veraltet. Für den aktuellen Stand ist diese
> Datei maßgeblich.

---

## 1. Teststrategie in Kurzform

Die App verwendet zwei sich ergänzende Testebenen:

1. **JVM- und Robolectric-Tests (`app/src/test`)**
   - schnelle Logik-, State-, Persistenz- und Compose-Tests ohne echten Emulator,
   - große Abdeckung von BLE-/Messlogik, Alarmierung, Datenbankmigrationen, Drive-Sync, Audioanalyse, Retention, Berichten und UI-Zuständen,
   - deterministische Fakes und Android-Shadows für reproduzierbare Fehler- und Randfälle.

2. **Instrumentierte Tests (`app/src/androidTest`)**
   - laufen auf einem echten Android-Systemimage (Emulator, API 34 ATD in CI),
   - prüfen reale Compose-Semantics und Touch-Koordinaten, Foreground-Service-Lebenszyklus, `NotificationManager`, `MediaPlayer`, Scoped Storage, `PdfDocument`, Room/SQLite, Android-System-Intents, Espresso-`Intents` für SAF-Dateiauswahl und (seit Phase 9b) eine echte YAMNet/TFLite-Modellinferenz,
   - decken die Stellen ab, an denen Robolectric entweder nur Shadows bietet oder reale Android-Lifecycle-/Systemintegration wichtig ist.

Beide Ebenen laufen parallel im gemeinsamen GitHub-Actions-Lauf **Android CI** bei jedem
relevanten Pull Request und Push auf `main`. Nur ausschließlich Markdown im Wurzelverzeichnis
oder unter `docs/` überspringt die Android-Jobs; ein leichtes, stabiles Gesamtgate bleibt sichtbar.
Unbekannte Pfade, gemischte Änderungen und unvollständige Git-Diffs führen zum vollständigen Lauf.
Ein manueller Lauf führt immer beide Ebenen aus. Ein grüner
Emulatorlauf ersetzt **keinen** Hardwaretest mit einem realen PCE-323, Mikrofon, Lautsprecher,
Kamera oder einem echten Google-/ntfy-Backend — siehe Abschnitt 7.

---

## 2. CI-Gates

### 2.1 Android CI — `build-and-test` (`.github/workflows/androidci.yml`)

| Schritt | Kommando | Blockierend? |
|---|---|---|
| Python | `pytest app/src/test/python/` und CI-Skriptregressionen | Ja |
| Compile | Bestandteil von `test` und `lintDebug`; APKs im parallelen Heavy Gate | Ja |
| Android Lint | `lintDebug` | Ja |
| ktlint | `ktlintCheck` | **Nein** (`continue-on-error: true` — Report wird trotzdem als Artefakt hochgeladen) |
| JVM/Robolectric | `test` (inkl. `testDebugUnitTest` und `checkUnusedDaoMethods`, siehe unten) | Ja |
| Coverage | `koverXmlReportDebug koverHtmlReportDebug` | Nein (nur Reporting) |

`checkUnusedDaoMethods` (eigene Gradle-Task in `app/build.gradle.kts`) hängt sich automatisch an
`test`: eine neu deklarierte, aber nirgends aufgerufene DAO-Methode macht CI genauso rot wie ein
fehlgeschlagener Test, ohne eine separate Zeile im Workflow zu brauchen.

### 2.2 Heavy Gate — `emulator / instrumented-tests` (`.github/workflows/emulator-tests.yml`)

Wiederverwendbarer Workflow, ausschließlich vom Parent `androidci.yml` aufgerufen.
APK-Erstellung und AndroidTest-Kompilierung erfolgen nur hier. Die Debug-APK ist unmittelbar
nach dem Build als `app-debug-apk-<Laufnummer>` im gemeinsamen Lauf verfügbar, auch wenn
später Tests fehlschlagen. Versionskennung und Debug-Keystore gelten für alle Gradle-Aufrufe
dieses Jobs. Der Keystore wird erst nach den Installations-/Testschritten gelöscht.

Android 14 / **API 34** AOSP ATD, `x86_64`, KVM-Beschleunigung, 2 Kerne, 2048 MB RAM, Animationen
deaktiviert, `-noaudio -camera-back none`. Ablauf:

```text
./gradlew assembleDebug assembleDebugAndroidTest
bash .github/scripts/run-instrumented-tests.sh 34   # siehe unten
```

`run-instrumented-tests.sh` führt zuerst `connectedDebugAndroidTest` mit allen Tests **außer**
den vier ausdrücklich benannten `ohneBerechtigung...`-Methoden aus, die eine Laufzeitberechtigung entziehen
(ein Entzug mitten im laufenden Testprozess würde diesen Prozess töten und den ganzen restlichen
Lauf mitreißen). Diese vier Fälle laufen danach einzeln über `adb shell am instrument`, jeweils
nach einem gezielten `pm revoke` **vor** dem jeweiligen Prozessstart.

Bei Fehlern sichert ein EXIT-Trap ADB-/Logcat-, `dumpsys`-, Geräteinformationen und Screenshots
**vor dem Emulator-Teardown**. Diagnosefehler überschreiben den ursprünglichen Teststatus nicht.
Die vier isolierten Methoden liefern zusätzlich eigene JUnit-XML- und Textberichte. Ein nicht
bestätigtes Permission-Revoke ist ein Fehler statt einer möglicherweise falsch grünen Prüfung.

Beide Jobs schreiben Phasenzeiten und ihre Dauer ab Checkout in `GITHUB_STEP_SUMMARY`.
Details zur Messmethode, Baseline und Architekturentscheidung: [CI-Audit](CI_AUDIT_2026-09.md).
Gradle-Daemons werden innerhalb eines Jobs wiederverwendet; Configuration Cache bleibt aus.

---

## 3. Instrumentierte Tests: Kategorien

Aktuelle Zahlen: siehe `docs/KENNZAHLEN.md` (`Instrumentierte Tests (Emulator): N in M Dateien`).
Statt einer erschöpfenden Pro-Datei-Tabelle (die bei diesem Umfang sofort wieder veraltet) hier
die grobe Verteilung nach Funktionsbereich:

| Bereich | Repräsentative Dateien |
|---|---|
| Home-Screen / Navigation / App-Start | `HomeScreenInstrumentedTest`, `HomeTagHeaderCollapseInstrumentedTest`, `HomeLoeschbestaetigungInstrumentedTest`, `HomeBerichtZipDialogInstrumentedTest`, `HomeReferenztonDialogInstrumentedTest`, `AppNavigationBarInstrumentedTest`, `AppStartupSmokeInstrumentedTest`, `MainActivityNavigationAndroidTest` |
| Messgerät / PCE-323 / BLE | `MeterScreenInstrumentedTest`, `MeterScreenComposeTest`-Pendants, `MeterScreenPermissionInstrumentedTest`, `MeterPairingDialogInstrumentedTest` |
| Protokoll / Export / Bericht | `ProtokollScreenAndroidTest`, `ProtokollDetailScreenInstrumentedTest`, `ProtokollExportAndroidTest`, `ReportConfigSettingsInstrumentedTest`, `GesamtberichtStammdatenSheetInstrumentedTest` |
| Einstellungen (`SettingsScreen`) | `SettingsScreenInstrumentedTest`, `SettingsSicherungInstrumentedTest`, `FotodokumentationSettingsInstrumentedTest`, `VideobeweisSettingsInstrumentedTest`, `SettingsHilfeSectionInstrumentedTest`, `SchwellenwertAssistentInstrumentedTest` — siehe Abschnitt 4 für den Detail-Stand. |
| Foreground Service / System | `ForegroundServiceAndroidTest`, `ServiceControlInstrumentedTest`, `TileServiceInstrumentedTest`, `DiagnoseScreenInstrumentedTest` |
| AudioPlayer / Video / Foto | `AudioPlayerScreenInstrumentedTest`, `VideoAufnahmeScreenInstrumentedTest` + `*PermissionInstrumentedTest`, `FotoDokumentationSheetInstrumentedTest` + `*PermissionInstrumentedTest` |
| Sonstige Dialoge/Screens | `TrashScreenInstrumentedTest`, `RuhezeitPresetsDialogInstrumentedTest`, `OnboardingScreenInstrumentedTest`, `DriveFolderPickerDialogInstrumentedTest`, `MarkNoiseEventBottomSheetInstrumentedTest` |

Die vier permissionsabhängigen `*PermissionInstrumentedTest`-Klassen (Kamera ×2, Standort,
Bluetooth-Scan) laufen isoliert außerhalb des Hauptlaufs — siehe Abschnitt 2.2.

---

## 4. SettingsScreen — Abdeckungs-Stand nach Funktionsbereich

`SettingsScreen.kt` ist mit ~2.200 Zeilen und zehn thematischen Abschnitten die größte
Einzelfläche der App. Ein Audit der `testTag`-Konstanten gegen beide Testbäume (JVM + Emulator)
ergibt folgenden Stand (Phase 6.1/6.2/6.3/9a/9c dieser Initiative haben die zuvor offenen Punkte
geschlossen):

| Bereich | Interaktions-getestet | Test(s) |
|---|---|---|
| Sprache, Lite-/Pro-Modus, Tab-Umschalter | Ja | `SettingsScreenAndroidTest` |
| Aufnahme & Mikrofon-/Messgerät-Schwellenwerte | Ja | `SettingsScreenInstrumentedTest`, `MeterSchwellenwertUiTest`, `SchwellenwertAssistentInstrumentedTest`, `AudioTriggerSettingsTest` |
| Alarmierung (Karenzzeit, ntfy, Alarmton) | Ja | `SettingsScreenInstrumentedTest` |
| Exakte Alarme erlauben | Ja (Phase 9a) | `SettingsScreenInstrumentedTest` mit `exakteAlarmeErlaubtOverride`-Testseam (echter Systemzustand auf dem CI-Emulator nicht erzwingbar) |
| KI-Erkennung | Ja | `SettingsScreenAndroidTest` |
| Ruhezeiten (F8) | Ja | `SettingsScreenInstrumentedTest` |
| Speicherplatz & Auto-Bereinigung (F5) | Ja | `SettingsScreenInstrumentedTest`, `SpeicherplatzUebersichtInstrumentedTest` |
| Google Drive Synchronisation (Umschalter/WLAN-only/Aggregation) | Ja | `SettingsScreenInstrumentedTest` |
| **Sicherung & Wiederherstellung (F13)** | **Ja (Phase 9c)** | `SettingsSicherungInstrumentedTest`: echte ZIP-Erstellung, voller Wiederherstellungspfad (SAF-Dateiauswahl via Espresso-Intents, echter Datei-/DB-Roundtrip) mit `neustartAusloeser`-Testseam anstelle des echten, unconditional `Runtime.getRuntime().exit(0)` in `SicherungManager.starteNeustart` |
| Fotodokumentation | Ja | `FotodokumentationSettingsInstrumentedTest` |
| Videobeweis | Ja | `VideobeweisSettingsInstrumentedTest` |
| Bericht-Parameter (§287 ZPO, Stammdaten-Abfrage-Schalter) | Ja | `ReportConfigSettingsInstrumentedTest`/`-Test` |
| Diagnose & Systemgesundheit (Akku-Opt., OEM-Hilfe) | Ja | `SettingsScreenInstrumentedTest`, `OemDeviceHelperCardTest` |
| Hilfe-Bereich | Ja | `SettingsHilfeSectionInstrumentedTest` |

Bewusst nicht Teil der Suite: der Drive-basierte Wiederherstellungspfad
(`ausstehendeDriveWiederherstellung`-Dialog) — nutzt denselben `neustartAusloeser`-Seam, ist aber
ein zusätzlicher Codepfad (Drive-Download + Fake-Backend nötig), der nicht Teil der
ursprünglichen Lücke war.

---

## 5. Home-Screen (`NoiseProtocolApp`) — Abdeckungs-Stand

War die größte einzelne Lücke der gesamten Initiative (Phase 7): der einzige zuvor existierende
Filtertest nutzte eine handgebaute Ersatz-Composable statt der echten Produktivkomponente.
Fünf unabhängige PRs haben das geschlossen:

- **Globaler Filter** (`HomeScreenInstrumentedTest`): RangeSlider, Such-, Favoriten-, Ruhezeit-,
  Messgerät- und Kalibriert-Filterchips, Voreinstellung aus den Einstellungen, Zurücksetzen —
  gegen die echte Composable statt eines Ersatzbaus.
- **Tages-Header ein-/ausklappen** (`HomeTagHeaderCollapseInstrumentedTest`): klappt nur die
  eigene Gruppe, nicht andere Tage.
- **Löschbestätigung** (`HomeLoeschbestaetigungInstrumentedTest`): Abbrechen löscht nichts,
  Bestätigen löscht nur das ausgewählte Muster.
- **Tagesbericht-ZIP-Dialog** (`HomeBerichtZipDialogInstrumentedTest`): echte PDF-/ZIP-Erzeugung
  und Teilen über den Overflow-Menü- bzw. Tages-Header-Pfad.
- **Referenzton-Lern-Dialog** (`HomeReferenztonDialogInstrumentedTest`): Dialog-Zustand, Abbrechen
  — und seit Phase 9b der volle Erfolgspfad mit einer echten, synthetisch erzeugten WAV-Datei
  gegen den echten `NoiseClassifier` (echtes YAMNet-TFLite-Modell über MediaPipe, kein Fake).
  Erste Instanz in diesem Repo, die eine echte Modell-Inferenz in einem Test auslöst.

---

## 6. JVM- und Robolectric-Abdeckung

Die JVM-Ebene ist deutlich breiter als die Instrumentationstests. Sie liegt unter
`app/src/test/java/com/example/lrmprotokoll/` und wird im CI-Gate mit `test` ausgeführt.
Der gemessene Task-Graph enthält `testDebugUnitTest` und den DAO-Check. Die bisherige doppelte
Anforderung führte dieselbe Suite nur einmal aus. Eine Release-Unit-Test-Task ist mit der
aktuellen AGP-Konfiguration nicht Teil dieses Graphen; das ist keine Änderung der CI-Abdeckung. Repräsentative Bereiche:

| Bereich | Repräsentative Tests |
|---|---|
| UI / Compose | `HomeNavigationComposeTest`, `MeterScreenComposeTest`, `SettingsScreenComposeTest`, `ProtokollDetailScreenComposeTest`, `DiagnoseScreenComposeTest`, `ServiceControlComposeTest`, `AudioPlayerScreenComposeTest`, `LiveCockpitCardTest`, `OemDeviceHelperCardTest` |
| PCE-323 / BLE | `BleScannerTest`, `ConnectionSupervisorTest`, `FakeMeterTransportTest`, `GeraetePinningTest`, `Pce323FrameDecoderTest` |
| Alarmierung | `AlarmCoordinatorTest`, `AlarmManagerDeadlineSchedulerTest`, `AlertMessagesTest`, `LocalNotificationAlertChannelTest`, `NtfyAlertChannelTest`, `HeartbeatPingerTest`, `HeartbeatWorkerTest` |
| Audio / Klassifikation | `AudioRecordingServiceStartupTest`, `NoiseClassifierTest`, `SoundClassifierTest`, `BatchKlassifizierungTest`, `BaulaermBefundTest`, `ImpulsanalyseTest`, `TriggerWachhundTest` |
| Messreihe / Akustik / Retention | `MeasurementRecorderTest`, `AkustischeKennwerteTest`, `AusfallbaenderTest`, `RetentionCoordinatorTest`, `RetentionWorkerTest` |
| Datenbank / Migrationen | `AppDatabaseMigrationTest` und Versions-Migrationstests, `MeasurementDaoTest`, `SessionDaoTest` |
| Google Drive | `DriveSyncCoordinatorTest`, `DriveSyncWorkerTest`, `GoogleDriveApiClientTest`, `GoogleDriveResumableUploadTest`, `DriveWavUploadAndCsvTest` |
| **Backup** | `SicherungEinstellungenTest`, `SicherungManagerTest` — echter Datei-/DB-Roundtrip unter Robolectric; die UI-Verkabelung dazu ist jetzt in `SettingsSicherungInstrumentedTest` (Abschnitt 4). |
| Berichte | `BerichtDateiTest`, `MessreiheExportTest`, `PeriodenBerichtDatenTest`, `TagesberichtDatenTest`, `ReportManagerTest` |
| Foto / Video | `BildverarbeitungTest`, `VideoTonMitschnittTest`, `VideoTonSynchronisationTest`, `VideospeicherTest` |

---

## 7. Test-Fakes und Test-Hooks

Testbarkeit ist so umgesetzt, dass der Produktionspfad standardmäßig unverändert bleibt.
Test-Hooks sind nullable/optional oder über den testweise ausgetauschten `AppContainer`
gekapselt — `null`/Default bedeutet in Produktion immer: realen Systemzustand verwenden.

| Hook | Wo | Zweck |
|---|---|---|
| `AppContainer(context, meterTransportOverride, bleScanProviderOverride, ...)` | `AppContainer.kt` | Realer `BleMeterTransport`/Scanner in Produktion, `FakeMeterTransport`/deterministischer Flow in Tests. Containergebunden, nicht prozessglobal. |
| `LaermprotokollApp.setCustomContainer(...)` / `resetContainer()` | Instrumentierte Tests | Realen `AppContainer` gegen einen mit Fakes bestückten tauschen; Cleanup verhindert Leaks zwischen Testmethoden. |
| `OemDeviceHelperCard(notificationPermissionOverride, exactAlarmPermissionOverride, batteryOptimizedOverride)` | `OemDeviceHelperCard.kt` | Erzwingt Sichtbarkeit der jeweiligen Buttons, um die ausgelösten System-Intents zu prüfen. |
| `SettingsScreen(exakteAlarmeErlaubtOverride)` | `SettingsScreen.kt` (Phase 9a) | Gleiches Muster wie oben, für denselben `AlarmManager.canScheduleExactAlarms()`-Check an einer zweiten Stelle — bewusst kein zweiter, abweichender Seam-Stil. |
| `SettingsScreen(neustartAusloeser)` | `SettingsScreen.kt` (Phase 9c) | `SicherungManager.starteNeustart()` beendet nach Erfolg den Prozess hart (`Runtime.getRuntime().exit(0)`) — ein echter Testaufruf würde den Instrumentierungsprozess vor jeder Ergebnismeldung töten. Default ist der echte Aufruf; Tests ersetzen nur diese eine Zeile, alles davor (SAF-Auswahl, Bestätigungsdialog, echter Restore) läuft real. |
| `LocalNotificationAlertChannel(notificationPermissionOverride)` | `LocalNotificationAlertChannel.kt` | Vermeidet ein echtes Permission-Revoke mitten im Testprozess (kann den Prozess beenden). |
| `AudioPlayerScreen`-Release-Hook | `AudioPlayerScreen.kt` | Verifiziert `MediaPlayer.release()` beim echten Entfernen aus der Composition. |

Weitere deterministische Mechanismen: direkte Testdaten in Room/SQLite, Espresso `Intents` für
System-/Share-/SAF-Intents, `UiAutomation.grantRuntimePermission(...)`, `TestNavHostController`
für echte Backstack-Assertions, explizites `waitUntil{}` auf Service-/Flow-/Room-/Dateizustände
statt nur `waitForIdle()`, Wiederherstellung geänderter `SettingsManager`-Werte in
`@After`/`finally`.

---

## 8. Was ein grüner CI-Lauf beweist — und was nicht

Wenn `build-and-test` und `instrumented-tests` auf demselben Commit grün sind, ist unter anderem
nachgewiesen: App und AndroidTest-APK kompilieren, Lint und die volle JVM-/Robolectric-Suite
sind grün, zentrale Navigation/Screens funktionieren, BLE-/Meter-UI verhält sich mit
deterministischen Fakes korrekt, Foreground-Service-Lifecycle und Ongoing-Notification
funktionieren auf API 34, PDF/CSV/ZIP werden auf Scoped Storage tatsächlich erzeugt, die
Sicherung/Wiederherstellung läuft end-to-end gegen eine echte Datei und Datenbank, und eine
echte YAMNet-Inferenz gegen eine echte Audiodatei läuft erfolgreich durch.

**Nicht** durch den Emulator-CI ersetzt (unverändert gegenüber dem vorherigen Stand):

- echtes PCE-323 / reales BLE-GATT (Advertising, RF-Störungen, Hersteller-Bluetooth-Stack),
- physikalische Qualität von Mikrofon, Lautsprecher, Vibration, Kamera (`-noaudio -camera-back none`),
- Google Drive E2E (echter OAuth-Login, echter Cloud-Roundtrip),
- ntfy E2E (echtes Push-Eintreffen auf einem Zweitgerät),
- Android-Versionen außer API 34 (keine Geräte-/API-Matrix),
- ktlint ist Report, aber nicht blockierend (`continue-on-error: true`, Abschnitt 2.1),
- Release-Instrumentierung (`connectedReleaseAndroidTest` existiert nicht).

---

## 9. Lokale Ausführung

```bash
# JVM / Robolectric (inkl. checkUnusedDaoMethods)
./gradlew test --stacktrace --continue

# Android Lint
./gradlew lintDebug --no-daemon --stacktrace

# ktlint (Report, nicht blockierend)
./gradlew ktlintCheck --no-daemon --stacktrace

# Instrumentierung auf verbundenem Gerät/Emulator (API 34 fuer CI-Naehe)
./gradlew assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace

# Coverage
./gradlew koverXmlReportDebug koverHtmlReportDebug --no-daemon --stacktrace

# Kennzahlen (docs/KENNZAHLEN.md) neu generieren
./gradlew generiereKennzahlen
```

---

## 10. Pflege dieser Dokumentation

Aktualisieren, wenn mindestens einer dieser Punkte eintritt: eine wesentliche Funktionsfläche
wechselt von ungetestet/nur-Präsenz zu interaktions-getestet oder umgekehrt, ein neuer
produktionsneutraler Test-Hook/Fake wird eingeführt, CI-Kommandos, Workflows oder das API-Level
ändern sich, ein bislang manueller Hardware-/Cloud-Test wird automatisiert, ein bislang
blockierendes Gate wird non-blocking oder umgekehrt, eine neue wesentliche Testlücke entsteht.

Zahlen (Testanzahl, Dateianzahl, Schemaversion, Kadenz-Toleranz, Trigger-Defaults) gehören nach
`docs/KENNZAHLEN.md` (`./gradlew generiereKennzahlen`) — nicht von Hand in diese Datei
übernehmen, sonst veraltet sie wieder auf dieselbe Weise, die zur automatischen Generierung
geführt hat.
