# Prompt: Flakiness-Brainstorming (Gemini × Claude) — `AppNotIdleException` in `ReportConfigSettingsTest`

**Für:** Gemini / Antigravity, frische Sitzung. **Gegenüber:** Claude Code, arbeitet parallel am
selben Problem. **Ort der Zusammenarbeit:** der Draft-PR zu diesem Branch — jede Seite schreibt ihre
Beiträge als PR-Kommentare, die andere liest und widerlegt oder stützt.

**Ziel:** die Ursache des Compose-Leerlauf-Hängers (`AppNotIdleException`, 60 s) **verstehen und
beheben**. Nicht raten: Wo die vorhandene Diagnose nicht reicht, wird sie erweitert, bis sie die
Ursache zeigt.

**Prüfe jede Angabe unten am aktuellen Code**, bevor du darauf aufbaust. Stimmt etwas nicht mehr,
melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Hier besonders:

- **Sync:** `git fetch origin`; deinen Experiment-Branch mit `git switch -c <branch> origin/main`
  anlegen. Nie auf `main` committen oder pushen.
- **Zwei Autoren, ein PR-Branch — ohne Merge-Streit.** Der PR-Branch `investigate/appnotidle-brainstorm`
  hat **einen** Schreiber: Claude. Du arbeitest auf `investigate/appnotidle-gemini`, Claude auf
  `investigate/appnotidle-claude`. Experimente, Messcode und Diagnoseerweiterungen liegen auf
  diesen Branches; im PR-Kommentar verlinkst du den Commit. Was beide für richtig halten, übernimmt
  Claude auf den PR-Branch. Kein Rebase und kein Force-Push auf fremde Branches.
- **Scope:** Testcode (`app/src/test/`) und Testhilfen (`testhilfen/`). **Kein App-Code**, solange
  die Ursache nicht belegt in App-Code liegt — dann zuerst im PR begründen, nicht ändern.
- **Verboten:** Tests überspringen, deaktivieren, abschwächen oder Timeouts hochdrehen, um grün zu
  werden. Kein Mockito/MockK (handgeschriebene Fakes).
- **Sprache:** Doku und Kommentare deutsch, Bezeichner englisch, Commits
  `<typ>(<scope>): <Beschreibung auf Deutsch>`.
- **Offene Owner-Entscheidungen:** Bericht Abschnitt 5a. Heute (14.10.2026) vom Owner freigegeben:
  **die Diagnose des Zeitwächters weiter aufzubohren** (5a Nr. 2) und eine Ursache am einzelnen Test
  bzw. in den Testhilfen zu beheben. **Nicht freigegeben:** ein Umbau der Wartestrategie über die
  sieben betroffenen Klassen (5a Nr. 4) — dazu nur einen begründeten Vorschlag machen.
- **Ehrlichkeit vor Ergebnis:** „gemessen", „abgeleitet" und „vermutet" getrennt ausweisen. Ein
  einzelner grüner Lauf beweist bei einem nicht-deterministischen Fehler nichts. Konnte etwas nicht
  geprüft werden, schreib genau das.

## 1 · Ausgangslage (gemessen, mit Quelle)

**Der Vorfall.** CI-Lauf [38553441763](https://github.com/arthurschaab-bit/Noiseprotocol_Android/actions/runs/38553441763)
(PR #279, Head `e5bb6d2`): von 1399 JVM-Tests schlägt genau einer fehl —
`ReportConfigSettingsTest.ungepruefteGebieteKoennenNichtAusgewaehltWerden`, 60,141 s,
`AppNotIdleException: Compose did not get idle after 2116175 attempts in 60 SECONDS`. Die fünf
anderen Tests der Klasse liefen in 0,1–0,8 s. Der Lauf auf `main` (`1c37a71`, gleicher App-Code,
2:39 min für den Testschritt) war grün. Der Fehler ist also sporadisch und hat mit dem PR nichts
zu tun. Das Artefakt `unit-test-reports` verfällt am 21.10.2026 — die Wächterdatei liegt deshalb
im Repo unter `docs/flakiness-evidenz/`.

**Der Test** (`ReportConfigSettingsTest.kt`, `waitUntil` in Zeile 119) rendert **nur** `ReportAreaSelection`
(kein `SettingsScreen`), öffnet das `ExposedDropdownMenuBox` per
`performSemanticsAction(OnClick)` und ruft danach `waitUntil(10_000)`, dessen Prädikat
`onAllNodesWithTag(...).fetchSemanticsNodes(...)` ist. Dieses löst über `getRoots()` ein
*verschachteltes* `waitForIdle` aus, das unter Espressos Master-Idling-Policy (60 s) steht, nicht
unter den 10 s des `waitUntil`.

**Die Wächter-Diagnose** (`docs/flakiness-evidenz/2026-10-14_…zeitwaechter-diagnose.txt`, 97 Zeilen):

1. Der Robolectric-Main-Thread (`SDK 34 Main Thread`) steht `RUNNABLE` in
   `RobolectricIdlingStrategy.runUntilIdle → ComposeIdlingResource.isIdleNow →
   AbstractMainTestClock.advanceTimeByFrame → advanceDispatcher → TestCoroutineScheduler.advanceTimeBy`.
2. **100 Stichproben alle 50 ms: kein einziger App- oder Compose-Rahmen** (gezählt werden u. a.
   `androidx.compose.ui.`, `.foundation.`, `.material3.`, `.animation.`, `Recomposer`; ausgenommen
   ist `androidx.compose.ui.test.`). Es läuft also **keine Layout-, Mess-, Zeichen- oder
   Rekompositionsarbeit** — die Schleife dreht sich leer.
3. **Main-Looper-Warteschlange: im Schnitt 0,1 Nachrichten**, in 3 von 100 Stichproben eine
   `Handler ? what=0`. Der Looper ist praktisch leer.
4. Drei Threads heißen `SDK … Main Thread`: einer `SDK 29` (WAITING), zwei `SDK 34` (einer WAITING,
   einer RUNNABLE). Es existierten also mindestens zwei SDK-34-Sandboxes im selben JVM. Gewicht:
   unklar, nicht überbewerten.

**Was das für die bisherige Theorie bedeutet.** Der Bericht (Abschnitt 4.8, „Der Takt wird
weitergestellt") erklärt den Hänger damit, dass etwas fortlaufend Frames anfordert und jeder
`isIdleNow`-Aufruf neue Arbeit erzeugt. Diese Instanz stützt das **nicht**: Wer Frames anforderte
und bediente, müsste in 100 Stichproben als `Recomposer`/`androidx.compose.ui.`-Rahmen auftauchen.
Gemessen ist das Gegenteil — `isIdleNow` bleibt `false`, ohne dass irgendwo Arbeit stattfindet.
Das ist ein Hinweis, **kein Beweis**: eine Stichprobe, ein Vorfall, Rahmenfilter nicht geprüft
auf Lücken (zum Beispiel `kotlinx.coroutines.test.`-Rahmen werden nicht gezählt).

**Weiteres Vorwissen, das du nicht neu entdecken musst** — `docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md`:
Abschnitt 4.2 (Material-3-Popups unter `GraphicsMode.NATIVE`), 4.3 (Überlaufmenü), 4.5
(`BluetoothStatusBadge`, behoben), 4.8 (dieser Hänger in sechs Klassen, 1 von 4 lokalen Vollläufen,
nicht durch Einzelklassenläufe auslösbar — **es braucht die ganze Suite**), Abschnitt 5 (Prüfpunkte),
5a (offene Punkte). Außerdem die Historie von `ReportConfigSettingsTest` (`ee6bb86`, `7954757`,
`c47fc76`). Lies `testhilfen/ZeitwaechterRegel.kt` ganz, bevor du die Diagnose erweiterst.

**Versionen (am Code prüfen):** Robolectric 4.16.1, `GraphicsMode.NATIVE`, SDK 34 (einzelne Tests
SDK 29), Compose-BOM und Espresso 3.5.1 aus `gradle/libs.versions.toml`. Beschaffe dir die
Quellen von `compose-ui-test` in **genau** der Version des Projekts (Gradle-Cache oder
`-sources.jar`) — die Aussagen unten über `ComposeIdlingResource` sind Erinnerung, nicht Beleg.

## 2 · Ablauf

**Runde 1 — unabhängig (blind).** Lies Abschnitt 1, den Bericht und den Code. **Bevor du
irgendeinen Kommentar von Claude im PR liest**, poste deine Erstanalyse als PR-Kommentar:
Hypothesen H1…Hn (Format unten) und die Experimente, die du als Erstes fahren würdest. Der Zweck
ist Vielfalt: Zwei Analysen, die einander nicht kennen, ankern sich nicht gegenseitig. Claude hält
seine Runde 1 so lange zurück, bis deine erscheint.

**Runde 2 — gegenseitig.** Jetzt Claudes Runde 1 lesen. Zu **jeder** fremden Hypothese eine Antwort:
stützt / widerlegt / nicht entscheidbar — mit Begründung und, wo möglich, einer Messung statt
einer Meinung. Eigene Hypothesen anpassen, streichen oder aufteilen. Widersprüche stehen lassen und
benennen; sie sind der eigentliche Gewinn.

**Runde 3 — Messen.** Das informativste Experiment zuerst, nicht das einfachste. Ranglisten im
PR-Kommentar; jede Seite nimmt Experimente, die sie ohne die andere fahren kann, und meldet das
Ergebnis mit Befehl, Rohausgabe und Anzahl der Läufe.

**Runde 4 — Fix.** Erst wenn eine Ursache **belegt** ist (nicht: plausibel). Der Fix liegt am
einzelnen Test bzw. in den Testhilfen und kommt mit einem Beleg, dass er wirkt: ausreichend viele
Läufe unter den Bedingungen, die den Fehler auslösen (siehe 3.3), vorher/nachher.

## 3 · Wohin die Untersuchung gehen sollte (Vorschläge, keine Vorgabe)

Deine eigenen Ideen haben Vorrang vor dieser Liste.

### 3.1 Die Diagnose aufbohren (`ZeitwaechterRegel`, freigegeben)

Der Wächter sagt heute, *dass* nichts passiert, nicht *worauf* `isIdleNow` wartet. Mögliche
Zusätze, nach Informationsgewinn grob geordnet:

- Den Zustand abfragen, den `isIdleNow` tatsächlich auswertet (per Reflexion auf die
  Laufzeitklassen von `compose-ui-test`, falls nicht öffentlich): offene Rekompositionsarbeit,
  Frame-Wartende, ausstehendes Messen/Layouten je Root, Anzahl der Roots (das Popup ist ein
  eigener Root).
- Ob die **Testuhr** vorankommt: `mainClock.currentTime` und `SystemClock.uptimeMillis()` über die
  Stichproben. Wenn die virtuelle Zeit stillsteht, die Schleife aber weiterzählt, ist das ein
  anderer Fehler als „Zeit läuft, Arbeit entsteht".
- Choreographer- und Looper-Zustand unter Robolectric (`ShadowPausedLooper`/`ShadowChoreographer`):
  stehen Frame-Callbacks aus, die nie ausgeliefert werden?
- `Recomposer`-Zustand (`Recomposer.runningRecomposers`/`RecomposerInfo`) je Sandbox.
- Welche Tests vorher im selben JVM/Sandbox liefen (Klassenreihenfolge, Sandbox-Identität,
  Anzahl der `SDK 34`-Sandboxes) — für die Hypothese „Verunreinigung über Klassengrenzen".
- Rahmen-Filter prüfen: zählt er `kotlinx.coroutines.test.`-Rahmen? Ein leerer Treffer kann ein
  Filter-Artefakt sein.

### 3.2 Hypothesen-Raum, damit er breit bleibt

Nicht vorsortiert nach Wahrscheinlichkeit: (a) ein Frame-Wartender ohne Frame-Lieferung
(Choreographer/Looper-Uhr); (b) ein zweiter Root (Popup-Fenster) mit eigenem, nie idle werdendem
Zustand; (c) Zustand, der aus einer früheren Testklasse im selben Prozess überlebt (Einstellungen,
Room, statische Singletons, Animationsskala, Locale/Konfiguration); (d) Wanduhr- statt
Testuhr-Abhängigkeit (`System.nanoTime`, `Dispatchers.Default`, echte `delay`); (e) Sandbox-
Wechsel mitten in der Suite; (f) ein Fehler in Compose-/Robolectric-Version selbst (dann: gibt es
einen bekannten Issue? Belege mit Link, nicht aus dem Gedächtnis).

### 3.3 Reproduzieren — die Voraussetzung für jeden Beleg

Laut Bericht Abschnitt 4.8 waren auf der CI 6 von 33 aussagekräftigen Läufen rot (Stand
06.10.2026), lokal etwa einer von vier Vollläufen; Einzelklassenläufe blieben in den
dokumentierten Messungen grün (6 von 6). Die Zahlen sind älter als dieser Vorfall — miss neu. Plane Reproduktion deshalb als eigene
Aufgabe: Vollauf in Schleife mit der erweiterten Diagnose; Bisektion der Suite-Reihenfolge, um
den Vorgänger zu finden, der den Zustand hinterlässt; ein gezielter Test, der den vermuteten
Zustand absichtlich herstellt, dann prüfen, ob der Hänger folgt.

CI-Hilfsmittel: `gh run download <run-id> -n unit-test-reports` holt Wächterdatei und XML-Berichte.
Ein einmaliger manueller Workflow (`workflow_dispatch`) mit dem Vollauf in Schleife und
hochgeladener Diagnose ist denkbar, **aber** ein neuer Workflow ist eine Änderung an der
CI-Infrastruktur — vor dem Anlegen im PR vorschlagen und von Claude gegenlesen lassen.

## 4 · Format der PR-Kommentare

Jeder Kommentar beginnt mit `**[Gemini]**` bzw. `**[Claude]**` und der Rundennummer. Hypothesen
als Block:

```
**H3 — <ein Satz>**
- Aussage: <was genau sein soll>
- Gemessen: <was dafür oder dagegen spricht, mit Quelle/Datei:Zeile/Lauf>
- Abgeleitet/Vermutet: <klar getrennt>
- Widerlegungstest: <was müsste man sehen, wenn H3 FALSCH ist>
- Experiment: <Befehl, erwartete Beobachtung, Aufwand>
- Status: offen | gestützt | widerlegt | nicht entscheidbar
```

Die **Widerlegungstest-Zeile ist Pflicht**. Eine Hypothese ohne sie ist eine Meinung.

## 5 · Definition of Done

1. Die Ursache ist **belegt** (Messung, die ohne sie nicht erklärbar ist) **oder** ehrlich
   eingegrenzt, mit der Angabe, was als Nächstes zu messen wäre.
2. Die erweiterte Diagnose ist auf dem PR-Branch, mit Test, und hat in mindestens einem echten
   Hängerlauf mehr gezeigt als die heutige.
3. Ein Fix am Test bzw. in den Testhilfen — oder die Begründung, warum keiner möglich ist, und ein
   Vorschlag für die Owner-Entscheidung 5a Nr. 4.
4. `./gradlew assembleDebug test` grün; Befehl und Ausgabe im PR, keine Zusammenfassung davon.
5. Kurzfassung für den Owner: gelöst / eingegrenzt / offen, und was du nicht prüfen konntest.
