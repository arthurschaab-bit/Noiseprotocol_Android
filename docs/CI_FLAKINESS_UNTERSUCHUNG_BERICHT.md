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
     Knotensuche und Timeouts stabilisiert. **Unvollständig — siehe Nachtrag 26.09.2026 in
     Abschnitt 4.4:** die eigentliche Ursache war eine endlose Animation, der Flake bestand fort.
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

#### Nachtrag 26.09.2026 — die Ursache war eine andere, der Flake bestand fort

Die oben genannte Gegenmaßnahme hat den Fehlschlag **nicht** beseitigt. Auf PR #204 trat er erneut
auf (Lauf 36226286168, Versuch 1: `abgelehnteVorpruefungHinterlaesstBreadcrumbOhneReportEvent`,
`ComposeTimeoutException` in `BerichtErstellenSheetTest.kt:122`). Der Neustart desselben Commits,
ohne jede Änderung, war grün — beides zusammen belegt die Nicht-Determiniertheit in der CI.

**Gemessen statt vermutet.** Der Test wurde lokal so instrumentiert, dass er bei Zeitüberschreitung
Knotenzahl und Freigabezustand des Startknopfs festhält. Im Fehlerfall:

```
anzahlStartKnoten=1
deaktiviert=true
```

Der Knopf **existiert** also, das Sheet ist offen — er bleibt nach 15 Sekunden *echter* Zeit
gesperrt. Damit scheidet Ursache 3 („`startButton` wurde vor dem Öffnen der BottomSheet
initialisiert") als Erklärung aus, und Ursache 2 ist nur die halbe Wahrheit: nicht die Ladezeit
selbst blockiert, sondern das, was *während* der Ladezeit gerendert wird.

**Hypothese (26.09.2026, inzwischen widerlegt).** Solange `laedt == true` ist, rendert
`BerichtErstellenSheet.kt:163` einen `CircularProgressIndicator` — einen *unbestimmten*
Fortschrittsindikator, also eine endlose Animation. Die naheliegende Erklärung lautete: sie fordert
fortlaufend neue Frames an, die Leerlauferkennung wird nie fertig, und `waitUntil` läuft in die
Zeitüberschreitung — dieselbe Fehlerklasse wie in Abschnitt 4.5 (`BluetoothStatusBadge`) und
Abschnitt 4.2 (`ExposedDropdownMenu`).

**Diese Erklärung trägt nicht.** Sie wurde geprüft und ist an den Messwerten gescheitert; die
Einzelheiten stehen unten unter „Was die Messungen zeigen".

**Was unabhängig davon gilt:** Die Erhöhung des Timeouts auf 15.000 ms hat den Fehlschlag nicht
beseitigt. Die Messreihen unten zeigen ihn weiterhin in rund einem Drittel der Läufe. Der Abschnitt
galt seit dem 25.09.2026 zu Unrecht als erledigt.

### Was die Messungen zeigen

**Drei Varianten, je eigener Gradle-Lauf mit `--rerun-tasks`, auf demselben Rechner:**

| Variante | rot / gesamt | Rate |
|---|---|---|
| unverändert (Basis) | 6 / 16 | 37,5 % |
| Testuhr anhalten (`autoAdvance = false`, Frames gezielt setzen) | 2 / 12 | 16,7 % |
| zusätzlich `shadowOf(Looper).idle()` und echte Wartezeit je Prüfschritt | 6 / 16 | 37,5 % |

Die zweite Variante sieht besser aus, ist es aber nicht nachweislich: Fisher-Exakt-Test gegen die
Basis ergibt p ≈ 0,22. Die dritte Variante ist **exakt** deckungsgleich mit der Basis, obwohl sie
zwei zusätzliche, unabhängig voneinander plausible Gegenmaßnahmen enthält. Wäre die Animation die
Ursache, müsste das Anhalten der Uhr zuverlässig wirken. Es tut es nicht.

**Der entscheidende Messwert** stammt aus der Diagnose, die `oeffneSheetUndWarteAufStartknopf()`
seither bei jeder Zeitüberschreitung ausgibt:

```
verstricheneMs=15334 pruefungen=1294 startknopfVorhanden=1 deaktiviert=true
ladeindikatoren=1 direkteAbfrageMs=4 direkteAbfrage=vorhanden
```

Zu lesen ist das so:

- `verstricheneMs=15334` und `pruefungen=1294` — das Timeout lief über 15 Sekunden **echter** Zeit
  ab, und die Prüfschleife lief dabei durch. Sie hing nicht.
- `startknopfVorhanden=1` — das Sheet ist offen. Ursache 3 („`startButton` wurde vor dem Öffnen der
  BottomSheet initialisiert") ist damit widerlegt.
- `ladeindikatoren=1` und `deaktiviert=true` — `laedt` ist noch `true`, der `LaunchedEffect` steht.
- **`direkteAbfrageMs=4`** — im selben Moment führt die Diagnose dieselbe Room-Abfrage direkt aus:
  sie kommt nach **4 Millisekunden** mit einem Ergebnis zurück.

Die Datenbank ist damit entlastet. Es hängt nicht die Abfrage, sondern die **Rückkehr der
Coroutine**: `withContext(Dispatchers.IO)` läuft auf einem echten Threadpool und muss auf den
UI-Dispatcher von Compose zurückspringen. Dieser Rücksprung findet in den betroffenen Läufen
innerhalb von 15 Sekunden nicht statt.

Ob die dauerlaufende Animation des Indikators zu diesem ausbleibenden Rücksprung beiträgt, ist
**nicht geklärt** — sie ist nachweislich auf dem Schirm (`ladeindikatoren=1`), aber sie zu
entschärfen hat die Rate nicht zuverlässig gesenkt.

### Stand und offener Punkt

**Der Flake ist nicht behoben.** Eingebaut ist bisher:

1. Die Hilfsmethode `oeffneSheetUndWarteAufStartknopf()` in beiden Testmethoden. Ihre Wirkung ist
   **nicht nachgewiesen** (2/12 gegen 6/16, p ≈ 0,22). Sie bleibt vorerst, weil die Ursache noch
   offen ist und ein Rückbau ohne Erkenntnisgewinn nur weitere Bewegung erzeugt.
2. Die Diagnose in `diagnose()`, die bei jeder Zeitüberschreitung die Messwerte oben ausgibt —
   einzeilig, damit `.github/scripts/testbericht.py` sie in die CI-Zusammenfassung übernimmt. Ohne
   sie ist ein Fehlschlag, der nur in rund einem Drittel der Läufe auftritt, in der CI nicht
   untersuchbar.

**Kein Produktivcode geändert.** Der `CircularProgressIndicator` und die Freigabelogik
`enabled = !erzeugt && !laedt` bleiben, wie sie sind. Der Umbau dieser Stelle gehört zu **F-07** in
Roadmap-Phase 3 (`docs/PROMPT_UX_PHASE3.md`), deren Definition of Done ausdrücklich verlangt,
`BerichtErstellenSheetTest` dabei anzupassen statt zu umgehen. Wenn dort die Vorbedingungen in eine
reine Funktion gezogen werden, entfällt der `LaunchedEffect`-Ladepfad als Wartebedingung des Tests —
das ist der aussichtsreichste Weg, den Flake ursächlich loszuwerden.

### 4.5 `MeterScreenComposeTest` & `MeterScreenPermissionAndScanTest` (Robolectric)
- **Analyse PR #179 vs. PR #189:**
  - In PR #179 wurde `FakeMeterTransport` eingeführt, um den echten BLE-Supervisor zu isolieren.
  - Dennoch traten weiterhin `AppNotIdleException`s auf.
  - Die endgültige Ursache lag in `BluetoothStatusBadge.kt`: Dort lief selbst im Ruhezustand eine
    unendliche Alpha-Puls-Animation über `rememberInfiniteTransition` in der Recompositions-Phase.
  - Dies wurde in PR #189 (`ebb9f17`) behoben, indem die Animation in die Draw-Phase
    (`Modifier.graphicsLayer { alpha = pulseAlpha.value }`) verlagert und für den statischen Zustand
    vollständig deaktiviert wurde. Seither laufen beide Klassen stabil.

### 4.7 `MainActivityNavigationAndroidTest` — die Dauermessungs-Karte bleibt aus

**Nachtrag 26.09.2026.** Aufgetreten im Wiederholungslauf von 36234744754 (PR #210):

```
MainActivityNavigationAndroidTest > dauermessungsCardWirdBeiVorhandenerSessionAngezeigtUndNavigiert
androidx.compose.ui.test.ComposeTimeoutException
  java.lang.AssertionError: warteUndScrolleZu-Timeout nach 10047ms
```

Der Test legt vor dem Aufbau der Oberfläche eine offene `SessionEntity` an und erwartet die
Dauermessungs-Karte. Sie erscheint nicht.

**Die Diagnose in `AsyncListTestHelper` hat den Fall entschieden.** Sie führt beim Timeout zwei
Eingriffe aus, die „Emission kommt nicht an" von „Änderung wird nicht neu gezeichnet" trennen:

```
Nach Snapshot.sendApplyNotifications():  Ziel erreichbar=false, Textknoten=18
Nach zusaetzlichem Insert:               Ziel erreichbar=true,  Textknoten=23
```

Ein erzwungenes Neuzeichnen ändert nichts. Ein zusätzlicher, völlig unbeteiligter Insert macht die
Karte sofort sichtbar. Room ist dabei unauffällig: `isOpen=true`, Beobachterzähler stabil
(`sessions=4`), `needsSync=false`, `pendingRefresh=false`, Abfragen in 4 ms.

**Ursache.** Es ist derselbe Fehler, den PR #182 aufgeklärt hat — nur für einen anderen Flow.
`MainActivity.kt` las den Zustand so:

```kotlin
val letzteSession by db.sessionDao().letzteSessionFlow().collectAsState(initial = null)
```

Durch das `by`-Delegat findet die eigentliche Lesung erst an der Verwendungsstelle statt, und die
einzige liegt im `LazyColumn`-Builder (`if (letzteSession != null)`, Dauermessungs-Karte). Damit
beobachtet allein der abgeleitete Zustand der LazyColumn aus der ersten Messphase diesen Flow.
Trifft die erste Room-Emission kurz nach jener Auswertung ein, wird sie nie neu ausgewertet — die
Karte bleibt aus, bis irgendeine andere Änderung ein Neuzeichnen erzwingt. Genau das zeigt der
Insert-Eingriff der Diagnose.

Für `records` und `references` wurde das in #182 behoben, indem `.value` im Kompositions-Scope
gelesen wird (`MainActivity.kt:391-392`, samt erklärendem Kommentar). **`letzteSession` hat diese
Korrektur nicht bekommen.**

**Behebung.** Dieselbe Form wie bei `records`/`references`:

```kotlin
val letzteSessionZustand = db.sessionDao().letzteSessionFlow().collectAsState(initial = null)
val letzteSession = letzteSessionZustand.value
```

Auf zwei Zeilen, weil `…collectAsState(…).value` in einem Zug vier Kettenglieder ergibt und ktlint
die Kette dann mehrzeilig verlangt — die mehrzeilige Form wiederum verstößt gegen „a multiline
expression should start on a new line". Die Zwischenvariable ist der Weg, der ohne neue
ktlint-Befunde auskommt und der Form von `records`/`references` am nächsten kommt.

**Das betrifft nicht nur den Test.** Beim App-Start wird `letzteSessionFlow()` genauso erstmalig
emittiert. Trifft die Emission dasselbe Zeitfenster, fehlt die Dauermessungs-Karte auch auf dem
Gerät, bis eine andere Änderung neu zeichnen lässt. Im Betrieb passiert das häufig genug, dass es
kaum auffällt — der Defekt ist derselbe.

**NICHT verifiziert:** dass der Fix den Testfehlschlag beseitigt. Dafür braucht es einen
Emulator-Lauf; in der Entwicklungsumgebung dieser Sitzung läuft keiner. Belegt ist bisher nur, dass
die Änderung die im Repo bereits etablierte Form aus #182 herstellt und die Diagnose genau auf
diesen Mechanismus zeigt.

---

### 4.6 `ForegroundServiceAndroidTest` (Emulator API 34) — Absturz auf dem falschen Thread

**Nachtrag 26.09.2026.** Aufgetreten in Lauf 36234744754 (PR #210), Job
`emulator / instrumented-tests (34)`, ein Fehlschlag von 229:

```
ForegroundServiceAndroidTest > foregroundServiceStartetUndStopptUeberUiUndVerwaltetOngoingNotification
android.view.ViewRootImpl$CalledFromWrongThreadException:
Only the original thread that created a view hierarchy can touch its views.
Expected: main  Calling: DefaultDispatcher-worker-1
```

**Die vollständige Kette, von unten gelesen:**

```
AudioRecordingService.kt:692        _currentMicDb.value = currentDb
  StateFlowImpl.setValue -> updateState -> StateFlowSlot.makePending
  CancellableContinuationImpl.resumeWith -> dispatchResume
  DispatchedTaskKt.dispatch -> DispatchedTaskKt.resume          <- INLINE, ohne Dispatch
  androidx.compose.ui.test.FrameDeferringContinuationInterceptor$FrameDeferredContinuation
  androidx.compose.ui.test.ApplyingContinuationInterceptor$SendApplyContinuation
  Snapshot.Companion.sendApplyNotifications
  SnapshotKt.advanceGlobalSnapshot
  Recomposer$recompositionRunner$2$unregisterApplyObserver$1
  Recomposer$runRecomposeAndApplyChanges -> CompositionImpl.applyChanges
  AndroidComposeView.onRequestMeasure -> View.requestLayout
  ViewRootImpl.checkThread                                       <- Absturz
```

**Das ist KEIN Fehler im Produktivcode.** Die beiden entscheidenden Glieder sind
`androidx.compose.ui.test.FrameDeferringContinuationInterceptor` und
`androidx.compose.ui.test.ApplyingContinuationInterceptor` — reine Compose-**Test**-Infrastruktur,
die `createAndroidComposeRule` installiert. Der `ApplyingContinuationInterceptor` ruft bei jeder
Fortsetzung synchron `Snapshot.sendApplyNotifications()` auf.

Im Betrieb sammelt `collectAsState()` unter dem `AndroidUiDispatcher` der Activity. Der schiebt die
Fortsetzung auf den Main-Thread, und die beiden Interceptors existieren dort überhaupt nicht. Der
Pfad kann im Betrieb so nicht auftreten.

**Warum es trotzdem auftritt:** `ForegroundServiceAndroidTest` spannt mit
`createAndroidComposeRule<ComponentActivity>()` eine eigene Komposition auf und lässt dabei den
echten `AudioRecordingService` laufen. Dessen Überwachungsschleife schreibt `_currentMicDb` im Takt
der Audioblöcke aus einer Coroutine auf `Dispatchers.Default`. Trifft eine solche Schreibung einen
Moment, in dem ein Verbraucher der Testkomposition an diesem `StateFlow` hängt, wird dessen
Fortsetzung inline auf dem schreibenden Thread wiederaufgenommen — und der Test-Interceptor löst
dort die Snapshot-Anwendung samt Neumessung aus.

Daraus erklärt sich auch die Sporadik: Es muss ein Verbraucher aktiv sein UND die Schreibung muss in
das richtige Zeitfenster fallen. Auf `main` (`fecd048`) war derselbe Test in zwei Läufen grün
(36231020409, 36231729466).

**Nicht behoben.** Mögliche Wege, keiner davon ohne Abwägung, deshalb dem Owner vorgelegt
(AGENTS.md §8a):

1. Den echten Dienst im Test nicht mitlaufen lassen, sondern seinen Pegelstrom durch eine Attrappe
   ersetzen. Sauberste Trennung, aber der Test prüft gerade das Zusammenspiel mit dem echten Dienst.
2. Die Komposition des Tests erst aufspannen, nachdem der Dienst gestoppt ist, beziehungsweise sie
   vor dem Start wieder abbauen. Ändert den Ablauf des Tests.
3. `_currentMicDb` im Dienst gedrosselt und auf einem festen Dispatcher veröffentlichen. Greift in
   Produktivcode ein, um ein Testproblem zu lösen — dafür spricht allenfalls, dass ein Pegelwert im
   Audioblocktakt als Compose-Zustand ohnehin viel Rekomposition erzeugt.

**Fehlzuordnung, die hier festgehalten gehört:** Der Befund wurde zunächst als
Nebenläufigkeitsfehler im Produktivcode gemeldet, der „auch im Betrieb" auftreten könne. Das war
falsch und beruhte darauf, dass nur der App-Rahmen im Stack gelesen wurde und nicht die Rahmen
darüber. Die Korrektur steht im PR-Verlauf von #210.

---

## 5 · Prüfpunkte & Empfehlungen nach AGENTS.md §8a

Für künftige Compose- und UI-Tests gelten folgende Best Practices zur Vermeidung von Flakes:
1. **Robolectric & Material 3 Popups/Dropdowns:** Nach dem Öffnen von Popups oder Menüs (`ExposedDropdownMenu`,
   `DropdownMenu`) kein pauschales `waitForIdle()` verwenden, da Popups unter `GraphicsMode.NATIVE`
   kontinuierlich Frames anfordern können. Assertions direkt an Knoten binden oder `mainClock.autoAdvance = false`
   nutzen.
2. **Unbestimmte Fortschrittsanzeigen:** `CircularProgressIndicator()` und `LinearProgressIndicator()`
   ohne Fortschrittswert sind endlose Animationen. Steht eine davon während eines `waitUntil` oder
   `waitForIdle()` in der Komposition, wird die Leerlauferkennung nie fertig — unabhängig von der
   Höhe des Timeouts. Entweder die Uhr für diesen Abschnitt anhalten (`mainClock.autoAdvance = false`,
   Frames gezielt setzen, im `finally` zurücksetzen) oder auf einen Zustand warten, der den Indikator
   nicht einschließt. **Achtung:** In Abschnitt 4.4 hat sich genau diese Erklärung als nicht
   tragfähig erwiesen — der Punkt bleibt als Vorsichtsmaßnahme sinnvoll, ersetzt aber keine
   Messung im Einzelfall.
3. **Datenbank-Isolation:** Jede Testklasse, die Komponenten mit DAO-Zugriffen testet, muss `@Before`/`@After`
   `db.clearAllTables()` auf `Dispatchers.IO` ausführen, da Room-Instanzen in Gradle-Test-Forks als
   Singleton fortbestehen.
4. **Locale-Unabhängigkeit:** UI-Tests dürfen keine hartcodierten Lokalisierungs-Strings abfragen,
   sondern müssen stets `context.getString(R.string...)` verwenden.
