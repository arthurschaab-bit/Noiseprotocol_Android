# CI-Audit und Optimierung – September 2026

Basis: `32186efa06bfff9e6ec3ead6d7fdafe15f9c12c9` (`main`). Audit vor Änderungen am
22.09., Implementierung/Validierung ab 23.09.2026. Keine Produkt- oder Testfälle gelöscht.

## 1. Messmethode und Baseline

300 letzte Runs: **13.09.2026 05:41:55 bis 22.09.2026 18:25:26 UTC**, 150 je Workflow.
Quelle: GitHub REST `actions/runs?per_page=100&page=1..3` und je Run
`actions/runs/{id}/jobs?per_page=100`; alle 300 Jobantworten sind ausgewertet.
294 abgeschlossene Jobs haben verwertbare Zeitstempel. Run-Zustände stammen aus dem ersten
Snapshot; ein damals noch laufender Emulator-Run ist in der Zählung separat ausgewiesen.
Es werden die **zuletzt abrufbaren Versuche**, nicht die Summe sämtlicher Wiederholungen,
betrachtet. 7 Android- und 10 Emulator-Runs hatten bereits `run_attempt=2`.

Dauer = `completed_at - started_at` des Jobs, einschließlich Setup und Post-Actions.
Erfolgsstatistik separat, damit schnelle Abbrüche oder lange rote Tests die typische
Feedbackzeit nicht verzerren. P90 ist Nearest Rank. Warteschlange = erster Jobstart minus
Run-Erstellung, nur bei Versuch 1 (API-Daten erlauben keine genauere Scheduler-Aufteilung).
Runner-Minuten sind Laufzeit, **keine Abrechnung**. Aggregierte Daten: [Baseline JSON](ci-baseline-2026-09.json).

| Kennzahl | Android CI | Android Emulator Tests |
|---|---:|---:|
| Runs | 150 | 150 |
| `pull_request` / `push main` | 106 / 44 | 106 / 44 |
| Erfolg / Fehler / Abbruch / noch laufend | 120 / 12 / 18 / 0 | 92 / 45 / 12 / 1 |
| Erfolgreiche Jobdauer: Mittel | 468,8 s | 479,4 s |
| Erfolgreiche Jobdauer: Median | 463 s | 478,5 s |
| Erfolgreiche Jobdauer: P90 | 535 s | 534 s |
| Alle beendeten Jobdauern: Mittel / Median / P90 | 494,2 / 469 / 643 s | 490,1 / 463 / 766 s |
| Queue: Median / P90 / Maximum | 3 / 71 / 303 s | 3 / 7 / 94 s |
| Summierte Runner-Minuten der verfügbaren letzten Versuche | 1.210,8 | 1.200,8 |

206 der 212 PR-Runs lassen sich 42 PRs zuordnen: zuerst über `pull_requests.number`,
sonst über einen eindeutigen Branch in den letzten 100 PR-Metadaten. 6 Runs bleiben ohne
sichere Zuordnung. Pro zugeordnetem PR im **Stichprobenfenster**: Mittel 4,90 Runs,
Median 3, Maximum 48 (#182). Das ist keine Lebenszeitstatistik kompletter PRs.
52 erfolgreiche Update-Paare (gleicher Head-SHA und Erstellungsminute, beide Workflows):
Runnerzeit Median **914 s = 15,23 min**; erster abgeschlossener Workflow Median **442,5 s**;
beide Gates Median **478 s**. Ein Update startet regulär zwei Workflows.

Die Anwendung wuchs im Zeitraum. Als zeitnähere Vergleichsgruppe ab
18.09.2026 18:23:33 UTC: je 21 Runs, davon 12 bzw. 6 erfolgreich; Erfolgsmedian
Android **600 s**, Emulator **525 s**. Diese kleine Gruppe darf nicht als gleichwertig
zu einer großen Nachher-Stichprobe verstanden werden.

Langsame Beispiele: [Android 995 s](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/35720212056)
und [Emulator 1.013 s](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/35702788773),
beide rot. Fehlerdiagnose und Flake-Ursachen sind daher neben Buildzeit besonders relevant.

### Zeitanteile erfolgreicher Runs

| Schritt | Android Median / P90 | Emulator Median / P90 |
|---|---:|---:|
| APK-/Test-APK-Build | 143 / 167 s | 143,5 / 165 s |
| Android Lint | 122 / 146 s | – |
| JVM/Robolectric | 100 / 109 s | – |
| Coverage | 18 / 19 s | – |
| Emulator-Action einschließlich Setup/Tests | – | 295,5 / 328 s |
| Gradle-Cache-Setup | 12 / 15 s | 10 / 13 s |
| Gradle-Cache-Post | 0 / 12 s | 1 / 30 s |
| ADB nach Teardown | – | 10 / 10 s, unbrauchbar |

Konkreter aktueller main-Lauf: [Android](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/35741543230)
617 s, darin Build 166 s, Lint 191 s, JVM 103 s, Coverage 19 s;
[Emulator](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/35741543359)
555 s, darin Prebuild 147 s und Emulator-Action 336 s.
Im Emulator-Joblog: SDK/Image 101,9 s (Image ca. 84,9 s), AVD 0,8 s, Boot 26,5 s,
`connectedDebugAndroidTest` 129 s (216 Haupttests 108,6 s), Wiederinstallation 15 s,
vier isolierte Permission-Fälle ca. 59,7 s. Die Gradle-Aufrufe nach Prebuild melden
je 82 Tasks UP-TO-DATE. Sie bauen im selben Job also nicht alles neu.

## 2. Vollständige Architektur vor dem Umbau

| Workflow | Trigger / Runner / Jobs | Aufgaben / Artefakte |
|---|---|---|
| Android CI | PR→main, push main, manuell; ubuntu-latest; ein Job, 30 min | Python-Pytest; Debug-/Test-APK und AndroidTest-Compile; Lint; ktlint informativ; `testDebugUnitTest test` einschließlich Robolectric/DAO; Kover informativ; Lint, ktlint, HTML-Testreports und Coverage 7 Tage, Debug-APK 14 Tage |
| Android Emulator Tests | gleiche Trigger; ubuntu-latest; unabhängiger Job, 25 min, Matrix nur API 34 | dieselben APKs erneut; Gradle UTP; vier isolierte Berechtigungsfälle via ADB; JUnit/HTML 7 Tage; ADB-Diagnosen nur bei Fehler |
| Release | Tags `v*.*.*`, manuell mit tag_name; ubuntu-latest; ein Job, 30 min | `test`, Release-Build, optionale Signierung; APK und R8-Mapping als GitHub-Release-Assets; kein Emulator-, Lint- oder Python-Gate |

Alle Jobs: checkout, Java 21, Python 3.11 für Chaquopy, setup-gradle mit Dependency-/Gradle-
User-Home-Cache. Android CI und Release installieren SDK 36 explizit; Emulatorrunner
installiert seine benötigten SDK-/Image-Pakete. Kein pip-/AVD-Cache, kein aktivierter
Gradle-Task-Output-Build-Cache. Kein allgemeiner Configuration Cache.

Der letzte Release-Lauf [32581889641](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/32581889641)
vom 22.08. war erfolgreich und liegt außerhalb der 300-Run-Stichprobe. Keine belastbare
Release-Laufzeitstatistik. Seine Trigger erzeugen keine reguläre PR-Doppelarbeit;
Signierung/Publikation bleibt unverändert und wurde nicht testweise ausgelöst.

## 3. Entscheidungen, Nutzen, Risiko

| Priorität | Maßnahme | Erwarteter Nutzen | Risiko / Komplexität |
|---|---|---|---|
| 1 | Konservative Git-Dateierkennung | Keine Android-/Emulatorzeit für reine Dokumentation | Niedrig; getestet mit Rename, Löschung, PR-Merge-Base und >300 Dateien |
| 2 | Gemeinsamer Parent, paralleles Fast/Heavy Gate | 2→1 Workflow-Run; Tests beginnen vor APK-Verpackung/Lint | Niedrig bis mittel; keine neue Testengine |
| 3 | APK-Verpackung/AndroidTest-Compile nur im Heavy Gate | Doppelte APK-Produktion entfällt; Anteil an bisherigen 143 s Fast-Build | Niedrig; **nicht** volle 143 s Einsparung, gemeinsame Debug-Kompilation bleibt nötig |
| 4 | Konstante Versionsinputs, Daemon je Job | Vermeidet BuildConfig-Invalidierung und JVM-Neustarts | Niedrig; echte CI validiert Signatur/Installation |
| 5 | Live-EXIT-Diagnose statt Post-Teardown-ADB | Verhindert 0-Byte-Überschreiben, spart 10 s pro Emulatorlauf | Niedrig; Fehler-/Signalpfade mit simuliertem ADB getestet |
| 6 | pip-Cache, keine Coverage-Testwiederholung nach roten Tests | Kürzere Python-Installation; kein zweiter identischer Testfehler | Niedrig; Coverage bleibt nach erfolgreicher Suite informativ |
| 7 | Kleine Phasenzeit-Summary | Build, JVM, Lint, Coverage, Emulator und Jobdauer sichtbar | Niedrig; Standardbibliothek, kein externer Dienst |

### APK-Wiederverwendung: geprüfte Alternativen

A: APK-Artefakte an neuen Emulatorjob übertragen. `connectedDebugAndroidTest` besitzt eigene
Build-Abhängigkeiten; APKs allein verhindern diese nicht. Interne AGP-Tasks mit `-x` zu
überspringen wäre fragil. Direkte ADB-Ausführung der ganzen Suite verlangte einen eigenen
zuverlässigen JUnit-/Crash-/Installationsreporter anstelle von UTP.

B: Emulator per `needs` hinter den bisherigen kompletten Android-CI-Job setzen. Spart mit
passender Übergabe Arbeit, aber wartet auf JVM, Lint und Coverage: aktuelles Beispiel allein
617 s **vor** Emulatorstart. Schlechter für Feedback.

C (umgesetzt): gemeinsamer Parent mit unabhängigen parallelen Gates. Nur Heavy erstellt APKs,
Fast kompiliert für JVM/Lint. Gleiche Suite, gleicher UTP-Runner, kein APK-Transport. Verbleibende
Debug-Kompilation auf beiden Runnern ist ausdrücklich **nicht vollständig eliminiert**.

D: alte getrennte Workflows unverändert lassen. Dependency-Cache verhindert keine volle
APK-Paketierung auf dem zweiten frischen Runner; gemessene Prebuilds ca. 2,4 Minuten je Job.
Daher schlechter als C. Eine genaue gesparte CPU-Taskzeit ist aus Step-Zeiten nicht ableitbar.

## 4. Trigger, Sicherheit und Erhalt der Testqualität

`ci-changes.py` erlaubt das Überspringen nur für `.md` im Root und unter `docs/`.
`app/**` (auch Markdown-Assets), `.github/**`, Gradle, Manifest, Ressourcen, Kotlin/Java,
Python/Chaquopy, Testdaten und unbekannte Pfade führen immer zu beiden Gates. Dateien unter
`docs/`, die kein Markdown sind, ebenfalls. Kein breites `paths-ignore: docs/**`.
Git-Diff ist NUL-getrennt, ohne Rename-Erkennung (Code→Docs zählt auch als Codelöschung),
PR-Diff vom Merge-Base zum gesamten Head, Push-Diff von before nach after. Bei jeder
Unsicherheit/fehlenden Historie/leerem Diff wird vollständig geprüft. Manueller Dispatch immer voll.

Docs-only hat einen kleinen Run mit Änderungserkennung und stabilem Gesamtcheck `CI`.
Das vermeidet dauerhaft ausstehende Required Checks durch Workflow-Pfadfilter. Es bedeutet
**nicht null Workflow-Runs**, sondern null teure Jobs. Reale historische Referenz:
[Docs-PR #178](https://github.com/arthurschaab-bit/Noiseprotocol_Android/pull/178) startete beide Workflows.

Vollständiger PR- **und** main-Lauf bleibt: main hat laut API beim Audit keine Branch Protection
und keine Rulesets. Direkte Pushes und ein veränderter Integrationsstand sind möglich;
ein grüner PR allein beweist nicht den späteren main-Stand. Kein sicherer Nachweis für ein
kleineres Post-Merge-Gate vorhanden. Auch reine Python-/Test-/CI-Änderungen erhalten Heavy.
Concurrency im Parent trennt PR-Nummern und main-Ref, mit `cancel-in-progress: true`.
Der aufgerufene Workflow erhält keine konkurrierende zweite Gruppe.

`test` enthält laut lokalem `--task-graph`/`--dry-run` 50 Tasks wie `testDebugUnitTest test`,
einschließlich `checkUnusedDaoMethods`. Keine Release-Unit-Test-Task im aktuellen Graphen;
die alte Kommentarannahme war falsch. APK-Graph: 94 Tasks mit oder ohne zusätzliches
`compileDebugAndroidTestKotlin`. Gradle dedupliziert innerhalb eines Aufrufs ohnehin.
Konstante `ORG_GRADLE_PROJECT_build*` vermeiden den bisherigen Wechsel von CI-Version auf
lokale Git-Version zwischen Gradle-Aufrufen. Der Heavy-Keystore bleibt bis nach Tests bestehen.

Keine echten Quality Gates mit `continue-on-error`. Nur vorhandenes ktlint (bekannter
Stilbestand) und Coverage-Reporting bleiben informativ. Nach fehlgeschlagenen JVM-Tests
wird Coverage nicht durch ein erneutes Ausführen derselben Suite erzwungen. Fehlender Report
wird ausdrücklich gemeldet. Lint läuft auch nach einem Testfehler, soweit Gradle eingerichtet ist.

## 5. Emulator, Diagnose und Artefakte

API 34, aosp_atd, x86_64, KVM, 2 Kerne, angeforderte 2048 MB RAM/512 MB Heap unverändert.
Das vorhandene Image erhöht RAM intern laut Log auf 2560 MB; kein Grund für blinde Änderungen.
Kein zusätzliches Snapshot-/AVD-Cache-System: Imageinstallation ist messbar teuer, aber ein
sauberer Kaltstart und überschaubare Invalidierung bleiben für Phase 1 wichtiger.

Der alte Collector sicherte Fehlerlogs bereits im Skript; der spätere Workflow-Schritt
überschrieb sie nach Emulatorende. Jetzt fängt ein EXIT-Trap Setup-, UTP-, Installations-,
Berechtigungs- und Signalfehler ab, begrenzt Diagnose-ADB-Aufrufe und bewahrt den ersten
Testfehlerstatus. Screenshot/UI-Hierarchie/Logcat/dumpsys/Geräteinfo bleiben beim Fehler erhalten.
Ein abgebrochener Runner oder ein Emulator, der nie bootet, kann naturgemäß nicht zuverlässig
Live-ADB liefern; das Action-Log bleibt die Quelle. Harte Prozessbeendigung umgeht EXIT-Traps.

UTP-Suite und dieselben vier isolierten Methoden bleiben. Letztere liefern jetzt Text und
JUnit-XML, und ein nicht bestätigtes Revoke ist rot. Die Mock-Tests prüfen u.a. Gradle-Status 42,
Installationsfehler, fehlenden Runner, JUnit-Fehler bei ADB-Status 0, Fehler trotz Erfolgstext,
Diagnosefehler und TERM. Kein Test wird durch Retry/Fehlerunterdrückung grün gemacht.

APK sofort nach Build, gleiche CI-Version/Secret-Signatur, 14 Tage, ZIP-Kompression 0 für
bereits komprimiertes APK. Alle bisherigen Lint/ktlint/Test-/Coverage-Reports bleiben 7 Tage,
zusätzlich rohe JVM-/Python-JUnit-Dateien und die Permission-Ergebnisse. Die kleinen
Report-Uploads kosten typischerweise 1–2 s: Weglassen lohnt nicht. Große ADB-Diagnosen nur
bei Fehler. Kein separates Test-APK-Upload ohne Konsumenten. Abgebrochene Runs laden keine
nutzlosen nachfolgenden Reportpakete hoch; Keystore-Cleanup bleibt `always()`.

## 6. Validierung und Vorher/Nachher

Lokaler Stand vor dem ersten GitHub-Lauf:

- `python -m unittest discover -s .github/scripts/tests -v`: **33 Tests, OK** (55,277 s auf Windows).
- `actionlint 1.7.7 -shellcheck= -pyflakes=`: Exit 0, alle drei Workflows; `git diff --check`: sauber.
- Python 3.11, isolierte Umgebung mit `requirements-test-python.txt`: **65 passed in 112.00s**.
- `gradlew.bat assembleDebug assembleDebugAndroidTest test lintDebug --stacktrace --continue`:
  beide APK-Tasks fertig; 962 JVM-Tests, 2 Fehler: `SupportBundleExporterTest` (Windows/Robolectric
  FileProvider findet den `external-files`-Root nicht) und `HomeNavigationComposeTest`
  (`IndexOutOfBoundsException` in Compose LazyList). Produkt-/Testsources unverändert.
  Dies ist ausdrücklich **kein vollständig grüner lokaler JVM-Lauf**. Linux-CI und gezielte
  Fehlerreproduktion werden vor Abschluss geprüft; Fehler werden nicht unterdrückt.

Nachher-Zeiten sind bis zum echten GitHub-Lauf **nicht gemessen**.

| Kennzahl | Vorher gemessen / strukturell | Nachher |
|---|---|---|
| Workflows pro normalem PR-Update | 2 | 1 Parent mit zwei parallelen Gates |
| Runner-Minuten pro erfolgreichem PR-Update | Median 15,23 (52 Update-Paare) | Messung folgt; keine Hochrechnung als Istwert |
| Erstes abgeschlossenes Workflow-Gate | Median 442,5 s (52 Paare) | Messung folgt; Python/JVM stehen früher in der Reihenfolge |
| Android CI Job-Median | 463 s (120 Erfolge); jüngste Gruppe 600 s | Messung folgt |
| Emulator Job-Median | 478,5 s (92 Erfolge); jüngste Gruppe 525 s | Messung folgt |
| Docs-only | 2 vollständige Runs pro Update | 1 leichter Run, 0 Android-/Emulatorjobs |
| Doppelte APK-Erstellung | beide Runner | nur Heavy; gemeinsame Compile-Arbeit teilweise weiterhin nötig |

## 7. Bewusst offen und mögliche Phase 2

- Kein Configuration Cache: lokaler Versuch scheitert an der Script-Objektreferenz von
  `checkUnusedDaoMethods` und externen Prozessen der Chaquopy-Konfiguration.
- Kein ungemessener globaler Gradle-Build-Cache, keine zusätzliche Cache-Action neben setup-gradle,
  keine erhöhte Worker-/Heap-Parallelität im einzelnen App-Modul.
- Kein Entfernen von Release-/Post-Merge-/Emulatorprüfungen, kein Sharding oder neues API-Level.
- Kein Zeitgewinn durch geringere Testzahlen oder kürzere Timeouts.
- Phase 2: nach 20–30 vergleichbaren grünen Runs AVD/Systemimage-Cache und Gradle-Task-Build-Cache
  getrennt messen, Cache-Transfer gegen echten Gewinn abwägen; Flakes anhand neuer Live-Logs
  gezielt beheben; Release-Voraussetzungen separat härten (hier unverändert).

Offizielle Grundlagen: [GitHub Workflow-Syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax),
[Gradle Build Cache](https://docs.gradle.org/current/userguide/build_cache.html),
[Gradle Daemon](https://docs.gradle.org/current/userguide/gradle_daemon.html),
[setup-gradle Cache](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
[Android Tests über Gradle/ADB](https://developer.android.com/studio/test/command-line),
[Emulatorrunner](https://github.com/ReactiveCircus/android-emulator-runner).
