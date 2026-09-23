# Prompt: CI — Diagnose-Workflow, schnellere Rückmeldung, Flake-Policy, hermetische Emulator-Tests

Stand: 22.09.2026. Auftraggeber: Owner. Umsetzung: Codex. Grundlage: die Erfahrungen aus PR #182
(M12, Einführung des Test Orchestrators), belegt durch CI-Läufe und Logcat-Artefakte.

**Warum dieser Auftrag.** In PR #182 schlugen instrumentierte Tests sporadisch fehl (je Lauf 1–4
von ~20 Home-Screen-Tests, nie dieselben). Jede einzelne Diagnose-Hypothese kostete einen
kompletten Emulator-Lauf aller 219 Tests (~16 min) und lieferte nur „diesmal rot/grün" statt einer
Fehlerquote. Sieben solcher Läufe waren nötig. Dieser Auftrag baut die Infrastruktur, die das in
Zukunft in einem Bruchteil der Zeit und mit belastbaren Zahlen erledigt, und macht die
instrumentierten Tests hermetischer.

**Verifiziere jede Angabe in Abschnitt 1 am Code, bevor du etwas änderst.** Stimmt etwas nicht mehr,
gilt der Code — melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Zusätzlich für diesen Auftrag:

- **Zwei getrennte PRs:**
  - **PR A** (Etappen A1–A3, CI-Infrastruktur): neuer Branch von `main`,
    `feature/ci-flake-diagnose`. Unabhängig von PR #182, sofort umsetzbar.
  - **PR B** (Etappe B, Hermetik): erst **nachdem PR #182 in `main` gemerged ist** (er führt den
    Test Orchestrator ein). Vorbedingung prüfen:
    `git show origin/main:app/build.gradle.kts | grep ANDROIDX_TEST_ORCHESTRATOR`. Fehlt der
    Treffer: **Etappe B nicht beginnen**, im PR A vermerken und aufhören. Branch dann
    `feature/ci-hermetische-emulator-tests` von `main`.
- **Nie auf `main` pushen.** Draft-PRs gegen `main`. Commit-Nachrichten auf Deutsch, klein
  geschnitten (ein Commit je Teilschritt).
- **Nichts anfassen, woran PR #182 gerade arbeitet:** `app/src/androidTest/.../ui/AsyncListTestHelper.kt`,
  die Klassen `Home*InstrumentedTest`, `MeterScreenAndroidTest`, `CrashDiagnoseInstrumentedTest`,
  `app/src/main/.../ui/MainActivity.kt`. Keine Drive-by-Refactorings.
- **Nie einen Test überspringen, `@Ignore`n, löschen oder sein Timeout erhöhen, um grün zu werden.**
  Quarantäne nur über die in A3 definierte, vom Owner gepflegte Liste — du trägst dort nichts ein.
- **Deine Umgebung hat vermutlich keinen Emulator (kein KVM).** Workflow-Änderungen werden durch
  echte GitHub-Actions-Läufe auf deinem PR-Branch verifiziert. Verlinke jeden Lauf, der ein
  Akzeptanzkriterium belegt, im PR. Was du nicht ausführen konntest, schreibst du genau so hin.
- Vor jedem Push lokal prüfen: YAML syntaktisch gültig (mindestens
  `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" <datei>`, besser `actionlint`,
  falls verfügbar), Shell-Skripte mit `shellcheck` (falls verfügbar) und `bash -n`.
  `./gradlew assembleDebug` und `./gradlew test` grün, sofern du Gradle-Dateien berührst.
- Das Repository ist **öffentlich** — Standard-Runner-Minuten sind frei, parallel laufen maximal
  20 Jobs. Wiederholungsläufe und Sharding sind damit bezahlbar; trotzdem keine unnötig großen
  Matrizen.
- Lege diesen Prompt unverändert als `docs/PROMPT_CI_FLAKE_STRATEGIE.md` im PR A ab (Konvention
  der übrigen `docs/PROMPT_*.md`).

---

## 1 · Ist-Zustand (überprüft, mit Fundstelle)

**`.github/workflows/emulator-tests.yml`**
- Ein Job `instrumented-tests`, Matrix `api-level: [34]`, `timeout-minutes: 25`,
  `concurrency: group emulator-${{ github.workflow }}-${{ github.head_ref || github.ref }}`,
  `cancel-in-progress: true`.
- Schritt „Pre-build Debug and Test APKs" (`assembleDebug assembleDebugAndroidTest`), ~4 min.
- `reactivecircus/android-emulator-runner@v2`: `target: aosp_atd`, `arch: x86_64`,
  `emulator-options: -no-snapshot -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim
  -camera-back none`, `cores: 2`, `ram-size: 2048M`, `disable-animations: true`. Kein
  Snapshot-Caching — jeder Lauf bootet kalt. Testphase ~11–12 min.
- Wichtig: der Runner führt **jede Zeile des `script:`-Inputs als eigenes `sh -c`** aus (siehe
  Kopfkommentar in `.github/scripts/run-instrumented-tests.sh`). Mehrzeilige Logik gehört deshalb
  in Skriptdateien.

**`.github/scripts/run-instrumented-tests.sh`**
- `connectedDebugAndroidTest` mit `notClass` für vier `ohneBerechtigung…`-Tests, danach
  `installDebug installDebugAndroidTest` (UTP deinstalliert am Ende von `connected…` beide APKs)
  und eine Schleife aus `pm revoke` + `adb shell am instrument -w -e class <Klasse#Methode>
  <Runner>` für genau diese vier Tests. Die Runner-Komponente wird per
  `pm list instrumentation | grep target=$APP_ID` ermittelt. **Genau dieses Muster ist die Vorlage
  für A1.**
- `sichere_emulator_diagnosen()` sichert bei Fehlern Logcat, dumpsys, Screenshot und
  UI-Hierarchie — aber nur einmal am Ende, nicht je Test.

**`.github/scripts/testbericht.py`** schreibt JUnit-XML als Markdown in `$GITHUB_STEP_SUMMARY`.
Per-Test-Logcat (`logcat-<Klasse>-<Methode>.txt`) liegt bereits im Artefakt
`instrumented-test-reports-api-34` unter `app/build/outputs/androidTest-results/connected/`.

**`.github/workflows/androidci.yml`** läuft `./gradlew testDebugUnitTest test` — das führt die
JVM-Tests für die Debug- **und** die Release-Variante aus. Nicht Teil dieses Auftrags, nur messen
(siehe A2).

**Test Orchestrator** (nur in PR #182, noch nicht auf `main`): `app/build.gradle.kts`,
`testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"` + `androidTestUtil(libs.androidx.test.orchestrator)`,
**bewusst ohne `clearPackageData`** (Kommentar an `testInstrumentationRunner`). Jede Testmethode
läuft in einem frischen Prozess, App-Daten (Room-DB `noise_database`, SharedPreferences,
WorkManager-Datenbank) bleiben zwischen den Tests erhalten.

**WorkManager** 2.9.1, Standard-Initialisierung über `androidx.startup` (die App implementiert
kein `Configuration.Provider`). `work-testing` ist nur `testImplementation`, nicht
`androidTestImplementation`. Geplante Worker (jeweils `WorkManager.getInstance(context)…`):
`drive/DriveSyncWorker.kt`, `messreihe/RetentionWorker.kt`, `alert/heartbeat/HeartbeatWorker.kt`,
`video/VideoMuxWorker.kt`, `diagnose/export/SupportBundleHealthWorker.kt` (wird in
`LaermprotokollApp.onCreate()` bei **jedem** Prozessstart eingeplant, KEEP),
`diagnose/export/SupportBundleUploadWorker.kt`, `diagnose/DiagnosticLogCleanupWorker.kt`.
Kein instrumentierter Test verwendet WorkManager direkt.

**Belegte Befunde aus PR #182 (Logcat-Artefakte der Läufe 472–476):**
- Persistierte WorkManager-Jobs laufen **innerhalb der Testprozesse**: u.a.
  `SupportBundleHealthWorker`, `DiagnosticLogCleanupWorker`, `RetentionWorker`, `DriveSyncWorker`
  melden dort „Worker result SUCCESS". Die Tests sind damit nicht hermetisch.
- Persistierte Filtereinstellungen einer Testklasse wirkten in späteren Tests weiter (siehe
  Kommentar in `HomeReferenztonDialogInstrumentedTest.setUp()`).
- Zwischen zwei Tests wird der vorherige Prozess sauber per SIGKILL beendet (`am force-stop`
  durch den Orchestrator) — prozessübergreifende Störungen spielen keine Rolle.

---

## 2 · Etappe A1 — Manuell startbarer Diagnose-Workflow mit Wiederholungen

Ziel: einen oder wenige Tests **N-mal hintereinander**, jeweils im frischen Prozess, auf demselben
Emulator-Image wie die PR-Pipeline laufen lassen und eine **Fehlerquote** erhalten („4/20 vorher,
0/50 nachher"), in etwa einem Drittel der Zeit eines vollen Laufs.

### Aufgaben
1. Neuer Workflow `.github/workflows/emulator-flake-diagnose.yml`, nur `workflow_dispatch`, Inputs:
   - `tests` (Pflicht): kommagetrennte Liste aus `Klasse` oder `Klasse#methode`
     (vollqualifiziert oder relativ zu `com.example.lrmprotokoll`, beides unterstützen).
   - `wiederholungen` (Standard 20, validieren: 1–100).
   - `api_level` (Standard 34).
   Der zu testende Stand ist der beim Start gewählte Branch (eingebautes Verhalten von
   `workflow_dispatch`).
2. **Eigene Concurrency-Gruppe**, die weder PR-Läufe abbricht noch von Pushes abgebrochen wird
   (z.B. `group: flake-diagnose-${{ github.run_id }}`, `cancel-in-progress: false`).
3. Emulator-Konfiguration **identisch** zu `emulator-tests.yml` (Image, Kerne, RAM, Optionen) —
   sonst sind die Fehlerquoten nicht vergleichbar. Wenn du die Konfiguration nicht dupliziert
   pflegen willst: gemeinsames Composite-Action- oder Skript-Stück, aber nur, wenn das die
   bestehende Pipeline nicht verändert.
4. Neues Skript `.github/scripts/wiederhole-instrumentierte-tests.sh` (eine Zeile im `script:`),
   Ablauf je Test und Iteration:
   `adb shell am force-stop <APP_ID>` → `adb logcat -c` →
   `adb shell am instrument -w -e class <Test> <Runner>` → Ergebnis an „OK (1 test)" erkennen
   (Vorlage: bestehende Schleife in `run-instrumented-tests.sh`) → Dauer messen → bei Fehlschlag
   `adb logcat -d`, Screenshot und UI-Hierarchie unter `diagnose/<Test>/<Iteration>/` sichern.
   APKs vorher mit `./gradlew installDebug installDebugAndroidTest` installieren (nicht über
   `connectedDebugAndroidTest`, das deinstalliert am Ende). Hinweis: `am instrument` startet den
   Zielprozess immer frisch — das entspricht dem Orchestrator-Verhalten ohne `clearPackageData`.
   Wird in Etappe B `clearPackageData` aktiviert, muss dieses Skript vor jeder Iteration
   zusätzlich `adb shell pm clear <APP_ID>` ausführen (Kommentar im Skript vorbereiten).
5. Ergebnis in `$GITHUB_STEP_SUMMARY`: Tabelle je Test mit `bestanden / fehlgeschlagen /
   Fehlerquote / mittlere und maximale Dauer`, dazu die Nummern der fehlgeschlagenen Iterationen.
   Artefakt mit allen Fehlschlag-Diagnosen (Aufbewahrung 7 Tage). Der Job endet **rot, sobald
   mindestens eine Iteration fehlschlug** — so kann „0/50" als Beleg für einen Fix dienen.
6. Kurze Bedienungsanleitung (GitHub-Oberfläche **und** `gh workflow run …`-Beispiel) in einem
   neuen Abschnitt von `docs/TESTEN_EINES_PR.md` oder, falls dort unpassend, in
   `docs/CI_FLAKE_DIAGNOSE.md`.

### Akzeptanzkriterien
- Ein Lauf mit `tests=ui.HomeScreenInstrumentedTest#filterPanelLaesstSichAufUndZuklappen`,
  `wiederholungen=5` zeigt fünf Iterationen in der Zusammenfassung (Link im PR).
- Ein zweiter Lauf mit zwei Tests und `wiederholungen=20` — Gesamtdauer und Dauer je Iteration im
  PR dokumentiert.
- Nachweis, dass ein gleichzeitig laufender PR-Emulator-Lauf nicht abgebrochen wurde (und
  umgekehrt).
- Ungültige Eingaben (unbekannte Klasse, `wiederholungen=0`) enden mit einer verständlichen
  Fehlermeldung statt eines stillen Durchlaufs.

---

## 3 · Etappe A2 — Schnellere Rückmeldung der PR-Pipeline

### Aufgaben
1. **Messen, bevor du änderst:** Dauer je Schritt aus drei aktuellen `emulator-tests`-Läufen auf
   `main` (Build, Emulator-Boot, Testphase, Diagnose/Upload) als Tabelle in den PR.
2. **AVD-Snapshot-Caching** nach der Dokumentation von `reactivecircus/android-emulator-runner`:
   `actions/cache` auf `~/.android/avd/*` und `~/.android/adb*`, Schlüssel aus API-Level, Target,
   Arch und Emulator-Optionen; bei Cache-Miss ein Schritt, der nur bootet und den Snapshot erzeugt
   (`force-avd-creation: false`, `disable-animations: false`, Skript `echo …`); im Testschritt
   `-no-snapshot-save` statt `-no-snapshot`. **Der Snapshot muss einen sauberen Boot-Zustand ohne
   installierte App enthalten** — prüfe im Log bzw. `emulator-info.txt`, dass kein Zustand aus
   früheren Läufen mitkommt.
3. **Sharding** der Testphase: Matrix `shard: [0, 1, 2]` (Startwert 3; anhand der Messung
   begründen oder anpassen), Übergabe per
   `-Pandroid.testInstrumentationRunnerArguments.numShards=…` und `…shardIndex=${{ matrix.shard }}`
   zusätzlich zum bestehenden `notClass`-Argument. Die `ohneBerechtigung…`-Schleife läuft **nur in
   Shard 0**. Artefaktnamen müssen den Shard enthalten (`upload-artifact@v4` bricht bei doppelten
   Namen ab). `testbericht.py` je Shard. Verifiziere, dass die Summe der ausgeführten Tests über
   alle Shards **exakt** der Testanzahl eines ungeshardeten Laufs entspricht (keine Lücken, keine
   Doppelten) — sofern PR #182 bereits gemerged ist, auch mit aktivem Orchestrator.
4. Ob sich ein getrennter Build-Job lohnt, der die APKs als Artefakt an die Shards weitergibt
   (statt dass jeder Shard selbst baut), entscheidest du anhand der Messung — nur umsetzen, wenn es
   deutlich spart und die Komplexität im PR begründet ist.
5. `androidci.yml` (`testDebugUnitTest test` → Debug- und Release-Variante): **nur messen und
   berichten**, wie viel Zeit die Release-Variante kostet. Nicht ändern — Owner-Entscheidung.

### Akzeptanzkriterien
- Vorher/Nachher-Tabelle der Wanduhrzeit bis zum Ergebnis „instrumented-tests" (je drei Läufe).
- Testanzahl-Abgleich geshardet vs. ungeshardet im PR belegt.
- Bei einem absichtlich roten Test (nur auf deinem Branch, vor dem Merge wieder entfernen) sind
  Zusammenfassung und Artefakte weiterhin vollständig.

---

## 4 · Etappe A3 — Flake-Policy: automatisch, sichtbar, nie still

### Owner-Entscheidung F-1 (vor Übergabe an Codex prüfen, ggf. Kreuz umsetzen)

- [x] **Variante 1 (empfohlen):** Ein Test, der erst fehlschlägt und bei der einmaligen
  Wiederholung besteht, gilt als **FLAKY**: der Job bleibt grün, aber jeder FLAKY-Test erzeugt eine
  sichtbare `::warning::`-Annotation, eine eigene Tabelle „Flaky" in der Zusammenfassung und das
  Diagnose-Artefakt des fehlgeschlagenen Versuchs.
- [ ] **Variante 2 (streng):** wie Variante 1, aber der Job wird bei FLAKY **rot**. Die
  Wiederholung dient dann nur der Einordnung (FLAKY vs. FAILED), nicht dem Grünmachen.

### Aufgaben
1. Nach der Haupttestphase: alle fehlgeschlagenen Testmethoden aus den JUnit-XMLs ermitteln und
   **genau einmal** einzeln wiederholen (`am instrument` wie in A1, frischer Prozess).
   Einstufung: bestanden bei Wiederholung → FLAKY; erneut fehlgeschlagen → FAILED. Job-Ergebnis
   gemäß F-1; FAILED ist immer rot.
2. Quarantäne-Liste `.github/flaky-quarantaene.txt`, **anfangs leer**, Format je Zeile
   `Klasse#methode | Issue-Link | Datum | Begründung`. Tests darauf laufen weiterhin und erscheinen
   in der Zusammenfassung, lassen den Job aber nicht rot werden. Einträge nur durch den Owner — du
   trägst nichts ein. Einträge ohne Issue-Link oder älter als 30 Tage erzeugen eine Warnung.
3. `testbericht.py` erweitern: Abschnitte „Fehlgeschlagen", „Flaky (bei Wiederholung
   bestanden)", „In Quarantäne".
4. Die Policy in `docs/CI_FLAKE_DIAGNOSE.md` (oder dem Abschnitt aus A1) in drei, vier Sätzen
   festhalten, inklusive des Grundsatzes: **Flaky ist kein Befund, sondern ein Auftrag** — jeder
   FLAKY-Test bekommt ein Issue, Ursache wird mit dem A1-Workflow gesucht, Timeouts werden nicht
   ohne Messung erhöht.

### Akzeptanzkriterien
- Mit einem absichtlich flakigen Hilfstest (nur auf deinem Branch, z.B. schlägt bei gerader
  Sekunde fehl; vor dem Merge entfernen) wird FLAKY korrekt erkannt, annotiert und gemäß F-1
  bewertet; ein absichtlich immer roter Test wird FAILED und der Job rot. Beide Läufe verlinkt.
- Ein Eintrag in der Quarantäne-Liste (nur testweise) verhindert Rot, erscheint aber sichtbar.

---

## 5 · Etappe B — Hermetische instrumentierte Tests (erst nach Merge von PR #182)

Vorbedingung aus Abschnitt 0 prüfen. Ohne Orchestrator auf `main` nicht beginnen.

### B1 — Test-WorkManager in jedem Testprozess (Owner hat zugestimmt)
1. Eigener Runner, z.B. `app/src/androidTest/java/com/example/lrmprotokoll/LaermprotokollTestRunner.kt`,
   abgeleitet von `AndroidJUnitRunner`, der in `callApplicationOnCreate(app)` **vor**
   `super.callApplicationOnCreate(app)` aufruft:
   `WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
   .setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build())`.
   Bereits geprüfter Fakt (work-testing 2.9.1): `initializeTestWorkManager` ruft
   `WorkManagerImpl.setDelegate(…)` auf, das **nicht** wirft, wenn die Standardinstanz über
   `androidx.startup` schon existiert; `WorkManager.getInstance()` liefert danach die Testinstanz.
   Verifiziere das trotzdem im CI-Log.
2. `testInstrumentationRunner` in `app/build.gradle.kts` auf den neuen Runner umstellen,
   `androidTestImplementation(libs.androidx.work.testing)` ergänzen. Die Runner-Ermittlung in
   `run-instrumented-tests.sh` (`pm list instrumentation`) muss den neuen Runner automatisch finden
   — prüfen.
3. Grenzen dokumentieren: Nur der instrumentierte Hauptprozess bekommt den Test-WorkManager. Die
   Prozesse `:crashprobe` und `:acra` starten ohne Instrumentierung mit der normalen Instanz — das
   ist beabsichtigt, im KDoc des Runners vermerken.

**Akzeptanzkriterium B1:** Im Logcat eines vollständigen Emulator-Laufs erscheint zwischen
„TestRunner: started" und „finished" eines Tests **keine** „Worker result …"-Zeile eines App-Workers
mehr (vorher nachweisbar, siehe Abschnitt 1). Alle bisher grünen Tests bleiben grün. Beides mit
Lauf-Link und Grep-Ergebnis im PR.

### B2 — `clearPackageData` bewerten, **nicht eigenmächtig aktivieren**
Die bewusste Repo-Konvention „keine globalen Datenlöschungen" (Kommentar in
`app/build.gradle.kts`) ändert sich damit. Deshalb: messen, bewerten, dem Owner eine Empfehlung
vorlegen — aktivieren erst nach seiner Zustimmung (AGENTS.md 8a).
1. Auf deinem Branch testweise `testInstrumentationRunnerArguments["clearPackageData"] = "true"`
   setzen und einen vollen Lauf fahren.
2. Berichten: Welche Tests werden rot und warum (insbesondere `CrashDiagnoseInstrumentedTest`:
   Auslösen und Prüfen laufen seit PR #182 in **einem** Test über den Prozess `:crashprobe` — sollte
   verträglich sein; `ProcessExitCollector` liest `ApplicationExitInfo`, das vom System gehalten und
   nicht gelöscht wird)? Wie stark verlängert sich die Laufzeit (`pm clear` je Test × 219)? Welche
   `@Before`-Aufräumarbeiten würden dadurch überflüssig (nur auflisten, **nicht** entfernen)?
3. Empfehlung mit Zahlen in den PR B, Teständerung danach wieder zurücknehmen, sofern der Owner
   nicht im Laufe des PRs zustimmt.

---

## 6 · Ausdrücklich nicht Teil dieses Auftrags

- Die Ursache der sporadisch leeren Home-Liste aus PR #182 — daran arbeitet Claude Code in PR #182
  (Befundlage dort: Room ist entlastet, die Emission geht zwischen Collector und Compose-State
  verloren; wird dort weiter eingegrenzt).
- Jede Änderung an den in Abschnitt 0 genannten Dateien.
- ktlint-Altlasten, die Debug/Release-Doppelung in `androidci.yml` (nur messen), Gradle Managed
  Devices (späterer Schritt, braucht lokal ebenfalls KVM), ein Test-Evidence-Rule
  (Thread-Dump/Screenshot je fehlgeschlagenem Test) — separat vom Owner zu beauftragen.

---

## 7 · Definition of Done

Je PR nach `AGENTS.md` §7, zusätzlich:
- Jedes Akzeptanzkriterium einzeln adressiert, mit Link auf den belegenden Actions-Lauf.
- Vorher/Nachher-Zeittabelle (A2) und Testanzahl-Abgleich (Sharding) im PR-Text.
- Alle absichtlich roten/flakigen Hilfstests vor dem Merge wieder entfernt — im PR bestätigt.
- Kurze Zusammenfassung an den Owner: erledigt / nicht erledigt / aufgefallen, inklusive allem,
  was mangels Emulator nur in CI und nicht lokal verifiziert werden konnte.
