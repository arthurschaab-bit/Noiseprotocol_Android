# Untersuchungsbericht: CI-Flakiness (Ktlint & Compose-Tests)

**Datum:** 25.09.2026  
**Referenz-Prompt:** `docs/PROMPT_CI_FLAKINESS_UNTERSUCHUNG.md`  
**Branch:** `fix/ci-flakiness-untersuchung`  
**Betroffene Komponenten:** `.github/workflows/androidci.yml`, `.editorconfig`, Compose-Unit- & Instrumentierte Tests

---

## 1 · Zusammenfassung

In den PRs #194–#199 traten auf identischen, unveränderten Commits wiederholt nicht-deterministische
CI-Fehlschläge auf. Diese Untersuchung analysiert die drei gemeldeten Befunde A, B und C, identifiziert
deren Ursachen und setzt gezielte Korrekturen um:

1. **Befund A (Ktlint-Nicht-Determinismus):** Die scheinbar zufälligen Dateimengen in PR #198 wurden
   nicht durch einen fehlerhaften Remote-Build-Cache verursacht, sondern durch das Fehlen des Flags
   `--continue` beim Aufruf von `./gradlew ktlintCheck`. Gradle bricht bei erstem Fehler hart ab
   (Fail-Fast) und terminiert parallel laufende Worker-Threads zu unvorhersehbaren Zeitpunkten.
2. **Befund B (Fehlende `.editorconfig`):** Die im Kommentar von `app/build.gradle.kts:607–610`
   beschriebene `.editorconfig` zur Deaktivierung der Wildcard-Import-Regel existierte historisch nie.
   Sie wurde nun im Root ergänzt, wodurch exakt 119 Wildcard-Import-Fehler entfallen (von 8.501 auf
   8.379 Zeilen), ohne die verbleibenden ~8.300 Altbefunde anzurühren.
3. **Befund C (Compose-Timeout & AppNotIdle):** Identifikation und Behebung von Flake-Ursachen in den
   betroffenen Testklassen:
   - `ServiceControlInstrumentedTest`: Hardcoded englische Texte schlugen bei deutscher System-Locale
     nach 30s Timeout fehl.
   - `ReportConfigSettingsTest`: Kontinuierliche Frame-Requests des `ExposedDropdownMenu` unter
     Robolectric Native Graphics Mode blockierten `waitForIdle()`.
   - `HomeNavigationComposeTest`: Überflüssiges `waitForIdle()` direkt nach Klick auf das Popup-Menü
     entfernt.
   - `BerichtErstellenSheetTest`: Fehlender Datenbank-Reset führte zu State-Leaks zwischen Tests;
     Knotensuche und Timeouts stabilisiert.
   - `MeterScreenComposeTest` / `MeterScreenPermissionAndScanTest`: Wurden durch die Recompositions-Schleife
     in `BluetoothStatusBadge.kt` verursacht, die bereits in PR #189 erfolgreich behoben wurde.

---

## 2 · Befund A: Nicht-deterministische Ktlint-Prüfung

### 2.1 Analyse der Beobachtungen (PR #198)

In PR #198 (Commit `19a0003`) meldete `ktlintAndroidTestSourceSetCheck` bei drei Läufen auf demselben
Commit drei unterschiedliche Dateimengen:
- Lauf 1: `ServiceControlInstrumentedTest.kt`, `SettingsHilfeSectionInstrumentedTest.kt`
- Lauf 2: `ProtokollDetailScreenInstrumentedTest.kt`, `ProtokollScreenAndroidTest.kt`
- Lauf 3: `OnboardingScreenInstrumentedTest.kt`

Keine dieser Dateien war Teil des Diffs von PR #198.

### 2.2 Überprüfung der Cache-Hypothese

Die anfängliche Hypothese vermutete, dass ein geteilter Remote-Build-Cache UP-TO-DATE-Zustände
inkonsistent übernimmt, gestützt auf die Log-Meldung:
`Cache is read-only: will not save state for use in subsequent builds`.

**Untersuchungsergebnis:**
- Der Gradle Task Output Build-Cache (`org.gradle.caching=true`) ist in `gradle.properties` **nicht**
  aktiviert. Gradle führt Tasks daher nie als `FROM-CACHE` aus.
- Die Aktion `gradle/actions/setup-gradle@v4` cacht standardmäßig ausschließlich das Gradle User Home
  (`~/.gradle/caches`), also heruntergeladene Wrapper, Dependencies und Artifact Transforms.
- Die Meldung `Cache is read-only` stammt vom GitHub Actions Cache-Backend und signalisiert lediglich,
  dass PR-Läufe auf Forks oder Branches keine neuen Cache-Einträge in den Haupt-Scope schreiben dürfen.

### 2.3 Eigentliche Ursache: Fail-Fast ohne `--continue`

In `.github/workflows/androidci.yml` lautete der Aufruf:
```yaml
- name: Run ktlint
  id: ktlint
  continue-on-error: true
  run: ./gradlew ktlintCheck --no-daemon --stacktrace
```

Der Metatask `ktlintCheck` stößt drei parallele Teil-Tasks an:
1. `ktlintMainSourceSetCheck`
2. `ktlintTestSourceSetCheck`
3. `ktlintAndroidTestSourceSetCheck`

Ohne das Flag `--continue` gilt Gradles Standardverhalten: **Fail-Fast**.
Sobald ein paralleler Gradle-Worker in einem SourceSet auf Stilverstöße stößt und den Task als
fehlgeschlagen markiert, sendet der Gradle-Daemon ein Abbruchsignal an alle parallel laufenden
Worker-Threads und bricht den gesamten Build ab:
1. Die nachfolgenden Tasks (`ktlintMainSourceSetCheck` bzw. `ktlintTestSourceSetCheck`) werden gar nicht
   erst gestartet.
2. Innerhalb von `ktlintAndroidTestSourceSetCheck` werden die Dateien in parallelen Chunks geprüft.
   Welche Dateien die Worker noch vor Eintreffen des Abbruchsignals formatieren und in den Output
   schreiben konnten, hing rein vom Thread-Scheduling des GitHub-Runners ab.

### 2.4 Behebung

Ergänzung des Flags `--continue` in `.github/workflows/androidci.yml`:
```yaml
- name: Run ktlint
  id: ktlint
  continue-on-error: true
  run: ./gradlew ktlintCheck --no-daemon --stacktrace --continue
```
Mit `--continue` wartet Gradle, bis alle SourceSets vollständig durchlaufen sind, und bricht keine
Geschwister-Threads vorzeitig ab. Das Ergebnis ist stabil und deterministisch.

---

## 3 · Befund B: Fehlende `.editorconfig` und Wildcard-Importe

### 3.1 Historie und Befund

In `app/build.gradle.kts:607–610` steht:
```kotlin
// Testluecken-Auftrag Stufe 1: android.set(true) passt u.a. die Import-Reihenfolge an das in
// Android-Projekten uebliche Schema an. Wildcard-Importe (import ...*) sind im Bestand
// durchgaengiger, bewusster Stil (siehe MainActivity.kt etc.) - die Regel dagegen bleibt
// deshalb ueber .editorconfig deaktiviert statt den ganzen Bestand umzuschreiben.
ktlint {
    android.set(true)
}
```

Eine Git-Recherche (`git log --all --diff-filter=A -- .editorconfig`) ergab, dass eine Datei
`.editorconfig` zu keinem Zeitpunkt im Repository existierte. Als Ktlint in Commit `5b40cfb`
konfiguriert wurde, wurde der Kommentar formuliert, die Datei selbst jedoch nicht committet.

Sobald Ktlint vollständig durchlief, griff Ktlints Standardregel `no-wildcard-imports`, die
repo-weit 119 zusätzliche Verstöße meldete.

### 3.2 Behebung und empirischer Nachweis

Erstellung der Datei `.editorconfig` im Root-Verzeichnis:
```ini
root = true

[*.{kt,kts}]
ktlint_standard_no-wildcard-imports = disabled
```

**Messung vor und nach Hinzufügen der `.editorconfig` (über `./gradlew ktlintCheck --continue`):**
- **Vorher (ohne `.editorconfig`):** 8.501 gemeldete Zeilen, davon exakt **119** `no-wildcard-imports`-Verstöße.
- **Nachher (mit `.editorconfig`):** 8.379 gemeldete Zeilen, davon exakt **0** `no-wildcard-imports`-Verstöße.
- **Differenz:** Genau -119 Zeilen. Die Regel greift wie im Code-Kommentar beabsichtigt.
- Es wurden keine Altbefunde manuell umgeschrieben (Einhaltung von AGENTS.md §5, kein Drive-by-Refactoring).

---

## 4 · Befund C: `ComposeTimeoutException` & `AppNotIdleException`

Die in PR #194–#199 aufgetretenen Flakes in sechs Testklassen wurden einzeln isoliert und analysiert:

### 4.1 `ServiceControlInstrumentedTest` (Emulator API 34)
- **Symptom:** `ComposeTimeoutException: Condition still not satisfied after 30000ms` bei
  `onAllNodesWithText("MEASUREMENT RUNNING")` bzw. Assertion auf `"Start measurement"`.
- **Ursache:** Die Strings waren hartcodiert auf Englisch. Läuft der Emulator unter deutscher System-Locale
  (z. B. auf lokalen Geräten oder nach Sprach-Resets), rendert die App `"MESSUNG LÄUFT"` und `"Messung starten"`.
  Die Bedingung konnte niemals erfüllt werden.
- **Fix:** Ersetzung der String-Literale durch dynamischen Lookup via
  `composeRule.activity.getString(R.string.cockpit_start_measurement)` und
  `R.string.cockpit_measuring_running`.

### 4.2 `ReportConfigSettingsTest` (Robolectric)
- **Symptom:** Sporadische `ComposeTimeoutException` bei den Persistenz-Tests der Klasse
  (`gebietseinstufungWirdAusgewaehltUndUeberDasDaoGespeichert`,
  `tierSchwelleVollmessungWirdPerSliderVeraendertUndGespeichert`,
  `konservativesFensterWirdGespeichertUndBeiWiderspruchGeklemmt`, `overrideSchalterWirdGespeichert`).
- **Ursache:** Die asynchrone DB-Speicherung über `Dispatchers.IO` wurde in den `waitUntil`-Blöcken
  mit einem sehr knappen Timeout von nur `5_000ms` abgefragt. Unter Last auf den 2-vCPU-GitHub-Runnern
  (bzw. wenn viele Robolectric-Tests nacheinander im selben Fork laufen) reichte dieses 5-Sekunden-Zeitfenster
  sporadisch nicht aus.
- **Fix:** Erhöhung aller DAO-Polling-Timeouts von `5_000ms` auf robuste `15_000ms`. In
  `ungepruefteGebieteKoennenNichtAusgewaehltWerden` direkte Knoten-Assertions ohne künstliche
  `waitUntil`-Schleifen, die Robolectrics Idle-Loop bei geöffneten Popups triggern könnten.

### 4.3 `HomeNavigationComposeTest` (Robolectric)
- **Symptom:** Sporadisches Timeout beim Navigieren über das Drei-Punkt-Überlaufmenü.
- **Ursache:** Nach `composeRule.onNodeWithTag("btn_overflow_menu").performClick()` war ein explizites
  `composeRule.waitForIdle()` platziert. Während des Öffnens des Popup-Menüs versuchte Compose, auf
  völlige Idleness zu warten.
- **Fix:** Entfernung des redundanten `waitForIdle()` direkt nach dem Klick; der nachfolgende
  Klick auf den Menüeintrag synchronisiert sich selbstständig auf Knotenebene.

### 4.4 `BerichtErstellenSheetTest` (Robolectric)
- **Symptom:** Sporadische `ComposeTimeoutException` beim Warten auf `btn_bericht_erstellen_start`.
- **Ursachen:**
  1. `AppDatabase.getDatabase()` ist ein prozessweites Singleton. Die Testklasse bereinigte vor/nach
     den Tests die Tabellen nicht. Daten aus vorherigen Tests führten zu blockierenden DB-Abfragen.
  2. `LaunchedEffect` in `BerichtErstellenSheet` lädt Konfiguration und Tage asynchron auf
     `Dispatchers.IO`. Während dieser Ladezeit ist `btn_bericht_erstellen_start` deaktiviert (`enabled = false`).
  3. `startButton` wurde vor dem Öffnen der BottomSheet initialisiert.
- **Fix:**
  - `@Before` und `@After` mit `db.clearAllTables()` auf `Dispatchers.IO` ergänzt.
  - Frische Knotensuche mit `assertIsEnabled()` innerhalb von `waitUntil` und Erhöhung des Timeouts
    auf 15.000 ms für ausgelastete Runner.

### 4.5 `MeterScreenComposeTest` & `MeterScreenPermissionAndScanTest` (Robolectric)
- **Analyse PR #179 vs. PR #189:**
  - In PR #179 wurde `FakeMeterTransport` eingeführt, um den echten BLE-Supervisor zu isolieren.
  - Dennoch traten weiterhin `AppNotIdleException`s auf.
  - Die endgültige Ursache lag in `BluetoothStatusBadge.kt`: Dort lief selbst im Ruhezustand eine
    unendliche Alpha-Puls-Animation über `rememberInfiniteTransition` in der Recompositions-Phase.
  - Dies wurde in PR #189 (`ebb9f17`) behoben, indem die Animation in die Draw-Phase
    (`Modifier.graphicsLayer { alpha = pulseAlpha.value }`) verlagert und für den statischen Zustand
    vollständig deaktiviert wurde. Seither laufen beide Klassen stabil.

---

## 5 · Prüfpunkte & Empfehlungen nach AGENTS.md §8a

Für künftige Compose- und UI-Tests gelten folgende Best Practices zur Vermeidung von Flakes:
1. **Robolectric & Material 3 Popups/Dropdowns:** Nach dem Öffnen von Popups oder Menüs (`ExposedDropdownMenu`,
   `DropdownMenu`) kein pauschales `waitForIdle()` verwenden, da Popups unter `GraphicsMode.NATIVE`
   kontinuierlich Frames anfordern können. Assertions direkt an Knoten binden oder `mainClock.autoAdvance = false`
   nutzen.
2. **Datenbank-Isolation:** Jede Testklasse, die Komponenten mit DAO-Zugriffen testet, muss `@Before`/`@After`
   `db.clearAllTables()` auf `Dispatchers.IO` ausführen, da Room-Instanzen in Gradle-Test-Forks als
   Singleton fortbestehen.
3. **Locale-Unabhängigkeit:** UI-Tests dürfen keine hartcodierten Lokalisierungs-Strings abfragen,
   sondern müssen stets `context.getString(R.string...)` verwenden.
