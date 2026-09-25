# Prompt: CI-Infrastruktur — nicht-deterministische Ktlint- und Compose-Test-Fehlschläge

**Herkunft:** Nicht aus `BEFUNDE_P30_2026-09-23.md`, sondern aus der Umsetzung der sechs dort
ausgelösten Aufträge (PRs #194–#199) am 24./25.09.2026 selbst festgestellt: mehrere PRs zeigten
auf **demselben, unveränderten Commit** bei wiederholten CI-Läufen unterschiedliche Ergebnisse.
Das ist unabhängig vom Inhalt dieser PRs — deren Code wurde jeweils separat verifiziert (gelesene
Diffs, lokale Testläufe der Autor-Agenten) und ist **nicht** Gegenstand dieses Auftrags.

**Kein akuter Blocker:** Alle sechs ursprünglichen PRs sind inzwischen gemergt (teils nach
manuellem Neustart der betroffenen CI-Jobs). Dieser Auftrag ist eine Ursachenuntersuchung für die
CI-Infrastruktur selbst, damit künftige PRs nicht denselben Preis (mehrfache Neustarts, Unsicherheit
ob „rot" echt ist) zahlen müssen.

**Prüfe jede genannte Stelle am aktuellen Code/Workflow**, bevor du etwas änderst. Stimmt eine
Angabe nicht mehr, richte dich nach dem tatsächlichen Stand und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c <branch> origin/main`.
- **Scope:** Nur die hier beschriebene CI-/Build-Infrastruktur. Keine Änderungen an App-Code,
  die nicht direkt zur Behebung der unten beschriebenen Befunde nötig sind.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7, soweit anwendbar (kein
  `assembleDebug`/Test-Bezug im klassischen Sinn, aber: zeig, dass dein Fix das Problem tatsächlich
  behebt, nicht nur plausibel klingt — siehe „Verifikation" unten).
- Nie behaupten, ein CI-Verhalten sei behoben, ohne es über mehrere tatsächliche Workflow-Läufe
  hinweg beobachtet zu haben. Ein einzelner grüner Lauf beweist bei einem **nicht-deterministischen**
  Problem gar nichts.

---

## 1 · Befund A — `ktlintAndroidTestSourceSetCheck` liefert bei identischem Commit unterschiedliche Ergebnisse

### Belege (alle auf Repo `arthurschaab-bit/Noiseprotocol_Android`)

**PR #198**, Kopf-Commit `19a0003` (unverändert über alle drei Läufe hinweg), Job `build-and-test`:

| Lauf | Zeitpunkt (UTC) | Von `ktlintAndroidTestSourceSetCheck` gemeldete Dateien |
|---|---|---|
| 1 | 24.09. 19:35 | `ServiceControlInstrumentedTest.kt`, `SettingsHilfeSectionInstrumentedTest.kt` |
| 2 (Re-Run) | 24.09. 19:56 | `ProtokollDetailScreenInstrumentedTest.kt`, `ProtokollScreenAndroidTest.kt` |
| 3 (Re-Run) | 25.09. 04:48 | `OnboardingScreenInstrumentedTest.kt` |

**Keine dieser fünf Dateien gehört zum Diff von PR #198** (der änderte nur Dateien unter
`report/` und `ui/BerichtErstellenSheet*`). Trotzdem meldet derselbe Ktlint-Task bei **drei Läufen
desselben Commits drei völlig unterschiedliche Dateimengen** als Verstoß.

Zum Vergleich, **derselbe Basis-Commit** `01216be` (Basis von sowohl PR #197 als auch PR #198):
- **PR #197** (`build-and-test` auf Commit `9ed99b1`): `ktlintAndroidTestSourceSetCheck` lief
  **ohne jeden gemeldeten Fund** durch — Job-Ergebnis `success`.
- **PR #199** (anderer Basis-Commit `0c60e33`, eine Ebene später): erster und einziger Lauf
  **`success`**, keine Ktlint-Meldung.

Das Gradle-Log jedes Laufs zeigt außerdem `Cache is read-only: will not save state for use in
subsequent builds` — es wird also ein **gemeinsamer, nur lesbarer Remote-Cache** verwendet
(vermutlich `gradle/actions/setup-gradle`s Cache-Feature oder ein GitHub-Actions-Cache-Bucket).

### Arbeitshypothese (nicht verifiziert — das ist der eigentliche Auftrag)

Der Ktlint-Task scheint pro Datei UP-TO-DATE-Zustand aus diesem geteilten Cache zu übernehmen,
und welche Dateien dabei als „bereits geprüft/sauber" übersprungen werden, variiert zwischen
Läufen — mutmaßlich abhängig davon, was parallel laufende, unabhängige Workflow-Läufe (andere PRs,
andere Commits) zeitgleich in denselben Cache-Bucket geschrieben haben. Ob das an der
Cache-Konfiguration von `gradle/actions/setup-gradle`, an fehlenden/falschen Cache-Keys für die
Ktlint-Tasks, oder an etwas anderem liegt, ist offen.

### Auftrag

1. Den Workflow (`.github/workflows/*.yml`) und die Ktlint-/Gradle-Cache-Konfiguration darauf
   untersuchen, warum `ktlintAndroidTestSourceSetCheck` nicht deterministisch dieselben Dateien
   prüft.
2. Einen Fix vorschlagen (typischerweise: Ktlint entweder vom geteilten Build-Cache ausnehmen,
   oder mit einem PR-/Commit-spezifischen statt geteiltem Cache-Scope laufen lassen — aber das ist
   eine Vermutung, keine Vorgabe. Bitte selbst recherchieren, was zur tatsächlichen
   Cache-Konfiguration passt).
3. **Verifikation, die dem nicht-deterministischen Charakter gerecht wird:** Nicht nur ein grüner
   Lauf. Mindestens 3–5 aufeinanderfolgende Workflow-Läufe auf demselben, unveränderten Commit
   anstoßen (z. B. über `workflow_dispatch` oder mehrere `rerun`s) und zeigen, dass
   `ktlintAndroidTestSourceSetCheck` dabei **stabil dasselbe Ergebnis** liefert (im Idealfall: die
   tatsächlichen, vorbestehenden ~8000 Altbefunde, konstant über alle Läufe).

---

## 2 · Befund B — `.editorconfig` existiert nicht, obwohl ein Code-Kommentar das Gegenteil behauptet

`app/build.gradle.kts:607–610`:

```kotlin
// Testluecken-Auftrag Stufe 1: android.set(true) passt u.a. die Import-Reihenfolge an das in
// Android-Projekten uebliche Schema an. Wildcard-Importe (import ...*) sind im Bestand
// durchgaengiger, bewusster Stil (siehe MainActivity.kt etc.) - die Regel dagegen bleibt
// deshalb ueber .editorconfig deaktiviert statt den ganzen Bestand umzuschreiben.
ktlint {
    android.set(true)
}
```

Tatsächlich existiert **an keiner Stelle im Repository, auch nicht in der Git-Historie**, eine
`.editorconfig`-Datei (geprüft mit `find` und `git log --all --diff-filter=A --name-only`).

### Konsequenz

Solange `ktlintAndroidTestSourceSetCheck` als erster der drei Ktlint-Tasks fehlschlägt (Regelfall,
siehe Befund A), bricht Gradles Standard-Scheduling ab, bevor `ktlintMainSourceSetCheck` und
`ktlintTestSourceSetCheck` überhaupt laufen — die fehlende `.editorconfig` bleibt unbemerkt.
Werden diese beiden Tasks aber erreicht (z. B. durch `--continue` oder Kombination mit anderen
Gradle-Aufrufen in einem Schritt), greift **Ktlints strengeres Standard-Regelwerk** statt der im
Kommentar behaupteten Ausnahme für Wildcard-Importe — das erzeugt zusätzlich ca. 7700 bisher nie
sichtbare Altbefunde repo-weit (empirisch ermittelt bei der Umsetzung von PR #199).

### Auftrag

1. Klären, ob eine `.editorconfig` mit der im Kommentar beschriebenen Wildcard-Import-Ausnahme
   jemals existiert hat und versehentlich gelöscht/nicht committet wurde, oder ob der Kommentar von
   Anfang an falsch war.
2. Entweder die fehlende `.editorconfig` mit der beschriebenen Regel nachtragen, **oder** falls das
   nicht der ursprünglichen Absicht entspricht, den Kommentar korrigieren und stattdessen den
   Ktlint-Codeblock (`ktlint { ... }` in `app/build.gradle.kts`) so konfigurieren, dass die
   Wildcard-Import-Regel tatsächlich deaktiviert ist.
3. Nicht im selben Zug die ~7700 dadurch neu sichtbaren Altbefunde reparieren — das ist ein eigener,
   viel größerer Auftrag (AGENTS.md: kein Drive-by-Refactoring). Falls die Fundzahl nach dem Fix
   spürbar sinkt (weil die Wildcard-Import-Regel jetzt korrekt greift), das im PR mit Zahl nennen.

---

## 3 · Befund C — wiederkehrende `ComposeTimeoutException`/`AppNotIdleException` in wachsender Zahl unabhängiger Testklassen

Bereits als Kategorie bekannt (`AppContainer.kt`, Kommentar zu Issue #160/PR #179), aber bei der
Umsetzung von PR #194–#199 in folgenden, jeweils **voneinander unabhängigen** Testklassen erneut
aufgetreten (nicht abschließend, nur was in dieser Session direkt beobachtet wurde):

- `ServiceControlInstrumentedTest` (Emulator, `instrumented-tests`-Job)
- `MeterScreenComposeTest` (2 verschiedene Testfälle)
- `MeterScreenPermissionAndScanTest`
- `ReportConfigSettingsTest` (mehrfach, auch bei unterschiedlichen PRs)
- `HomeNavigationComposeTest`
- `BerichtErstellenSheetTest` (eine der in PR #198 neu hinzugefügten Tests — lief im selben
  CI-Job einmal grün und wenige Minuten später auf demselben Commit rot)

Alle mit `ComposeTimeoutException: Condition still not satisfied after …ms` bzw.
`AppNotIdleException`. Jeweils gegen den unveränderten Basis-Commit reproduziert (also nicht durch
die jeweiligen PR-Änderungen verursacht).

### Auftrag

1. Prüfen, ob PR #179 (referenziert in `AppContainer.kt`) das Problem vollständig oder nur
   teilweise adressiert hat, und ob die Häufung seither zugenommen hat (z. B. durch mehr
   Compose-Tests insgesamt, oder durch langsamere/stärker ausgelastete CI-Runner).
2. Eine robustere Wartestrategie für Compose-/Espresso-Idle-Checks vorschlagen (z. B. gezieltere
   `waitUntil`-Bedingungen statt globalem Idle-Warten, oder höhere/adaptive Timeouts) — das ist
   mutmaßlich der größte Einzelposten der drei Befunde und passt am ehesten zu Antigravitys Rolle
   laut AGENTS.md §9 („UI-heavy work with emulator/visual feedback").
3. Falls die Ursache nicht eindeutig auf einen einzigen Fix zurückzuführen ist: das explizit im PR
   benennen und nach AGENTS.md §8a einen konkreten Prüfpunkt mit dem Owner vereinbaren, statt die
   Untersuchung stillschweigend abzubrechen.

---

## 4 · Akzeptanzkriterien

- [ ] Befund A: Ursache identifiziert und durch mehrere (nicht nur einen) wiederholte CI-Läufe auf
      demselben Commit belegt behoben, oder — falls die Ursache außerhalb dieses Repos liegt (z. B.
      GitHub-Actions-Cache-Verhalten selbst) — klar dokumentiert mit einem konkreten
      Workaround-Vorschlag.
- [ ] Befund B: `.editorconfig`/Kommentar-Widerspruch aufgelöst, ohne die ~8000 vorbestehenden
      Altbefunde in diesem PR mit zu reparieren.
- [ ] Befund C: Root-Cause-Einschätzung und entweder ein Fix oder ein vom Owner bestätigter
      Prüfpunkt für die weitere Untersuchung.
- [ ] Für jeden behobenen Befund: Beleg über mehrere echte CI-Läufe, nicht nur einen lokalen Testlauf.
