# Prompt: Nachbesserung zu UX-Phase 3 (PR #218)

Nachlauf zu `docs/PROMPT_UX_PHASE3.md`. PR #218 ist **gemergt** (`main` = `0653b55`) und im Kern
in Ordnung — F-08, F-07 und F-28 sind erfüllt, F-15 nur teilweise. Dieser Auftrag räumt die
Befunde eines Reviews auf, das nach dem Merge gelaufen ist (Reviewer: Claude Code, nicht der
Implementierer).

**Was am Review belegt ist, damit du nicht nachmisst:** `assembleDebug lintDebug test` auf
`0653b55` grün (BUILD SUCCESSFUL in 4m 20s, Lint 0 Fehler), **1149 Tests, 0 Fehlschläge**,
Room-Schema v25 unberührt, `app/schemas/` unberührt, alle 21 Migrationstestklassen grün. Die
Tagesregel, der Stammdatenvergleich und die Presets sind instrumentiert getestet. Das ist gute
Arbeit — die Punkte unten sind Nacharbeit, keine Reparatur eines kaputten Stands.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen. Insbesondere:

- `git fetch origin`, dann `git switch -c fix/ux-phase3-nachbesserung origin/main`.
  **Nie auf `main` pushen.**
- Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten, **ein Commit je
  Punkt unten**. Bitte mit Commit-Text unter der Kopfzeile: *warum*, nicht *was* — das ist in
  diesem Repo die Regel, und #218 hatte sechs Commits ohne jeden Rumpf.
- **Keine Schemaänderung.** Keiner der Punkte braucht eine. Wenn du glaubst, doch eine zu
  brauchen: anhalten und den Owner fragen (AGENTS.md §8a).
- Nicht anfassen: F-16 (→ Phase 4, braucht Migration 25 → 26), S-4 (→ Phase 6), die Sperre
  ungeprüfter Gebiete in `ReportAreaSelection` (fachlich gewollt).
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.** Siehe Abschnitt 8 — bei
  ktlint ist genau das in #218 schiefgegangen.

---

## 1 · Die Stammdaten des laufenden Tages sind nicht mehr korrigierbar (F-15, offen)

**ERST FRAGEN, DANN BAUEN.** Dieser Punkt braucht eine Owner-Entscheidung.

Befund: `MainActivity.kt:452-463` unterdrückt die Stammdatenabfrage, sobald für den heutigen
Messtag ein Eintrag existiert — richtig nach der Tagesregel. Das Sheet hat aber nur zwei
Aufrufstellen (`MainActivity.kt:467` und `BerichtErstellenSheet.kt:379`). Damit ist der einzige
verbleibende Weg zu einer Korrektur:

> Bericht-Tab → „High-End-Bericht jetzt erzeugen" → einen Zeitraum wählen, der den heutigen Tag
> enthält → „Angaben für diesen Tag nachtragen"

Die Owner-Entscheidung vom 25.09.2026 lautet aber: *Tagesregel **mit Korrekturmöglichkeit** —
korrigieren muss der Nutzer jederzeit können.* Über den Berichtsflow ist das formal erfüllt,
praktisch findet es niemand, der um 9:00 den Messort vertippt hat.

Varianten, die dem Owner zur Wahl zu stellen sind — **nicht selbst entscheiden:**

- **(a)** Ein Eintrag im Cockpit-/Messbildschirm („Stammdaten dieser Messung bearbeiten"), der
  `GesamtberichtStammdatenSheet` mit `giltFuerTagStart = null` öffnet.
- **(b)** Ein Eintrag im Überlaufmenü des Protokolls, gleiche Wirkung.
- **(c)** Nichts bauen, sondern den Weg über den Berichtsflow im Sheet benennen (ein Satz beim
  Unterdrücken der Abfrage). Billigste Variante, löst das Finden-Problem nur halb.

Empfehlung des Reviews: **(a)**, weil die Korrektur dort passiert, wo der Fehler auffällt.

Wenn (a) oder (b) gebaut wird: Das Sheet setzt beim Öffnen mit vorhandenem Tageseintrag bereits
alle Felder aus diesem Eintrag vor (`LaunchedEffect`, `fuerTag != null`-Zweig) — der Korrekturfall
funktioniert also schon, es fehlt nur der Weg dorthin. Ein JVM- oder instrumentierter Test muss
zeigen: zweiter Aufruf am selben Tag, ein Feld geändert, **genau eine** zusätzliche Zeile in
`stammdaten_verlauf`.

---

## 2 · Der Breadcrumb für die abgelehnte Vorprüfung ist toter Code

`BerichtErstellenSheet.kt:136` schreibt weiterhin

```kotlin
diagnosticsReporter.breadcrumb("Bericht", "High-End-Bericht nicht gestartet: $fehler")
```

Diese Zeile ist seit #218 **praktisch unerreichbar**: der Knopf ist gesperrt, solange
`ersterBlocker != null` oder `laedt`, also kann `erzeugen()` den Fehlerzweig nicht mehr betreten.
Gleichzeitig wurde der Test, der den Breadcrumb zusicherte
(`abgelehnteVorpruefungHinterlaesstBreadcrumbOhneReportEvent`), gelöscht und durch einen ersetzt,
der nur noch die UI prüft. Der Diagnosepfad aus `docs/BEFUNDE_P30_2026-09-23.md` §3 fällt damit
still weg — im Diagnoseprotokoll steht künftig nichts mehr darüber, dass ein Nutzer keinen Bericht
erzeugen konnte.

Zu tun, eines von beiden, und im PR begründen:

- **entfernen:** Zeile und Kommentar raus, wenn der Owner das Signal nicht mehr braucht.
- **verlagern (Empfehlung):** einmal je Sheet-Öffnung protokollieren, welche Bedingungen den Start
  blockieren.

**Falle, wenn du verlagerst:** `pruefeBerichtVoraussetzungen(...)` wird im Kompositionskörper
aufgerufen (`BerichtErstellenSheet.kt:100`). Ein Breadcrumb an dieser Stelle läuft bei **jeder
Rekomposition** und flutet die Ringdatei (256 KiB je Datei, `BreadcrumbRingFile`). Der Aufruf
gehört in ein `LaunchedEffect`, dessen Key die ID des ersten Blockers ist — nicht die Liste, nicht
der Text. Ein Test muss zusichern: **genau ein** Breadcrumb, auch nach mehreren Rekompositionen.

---

## 3 · Die Voraussetzungsliste zeigt während des Ladens Unwahrheiten

`pruefeBerichtVoraussetzungen` bekommt `config` als `ReportConfigEntity?` und behandelt `null` als
„erfüllt" (`BerichtVoraussetzungen.kt:39,41` — `config?.let { … }` ergibt `fehler = null`).
Solange der `LaunchedEffect` lädt, steht also **„✓ Gebietseinstufung gesetzt"** da, obwohl noch
nichts geladen ist. Umgekehrt meldet ROHDATEN rot „Im gewählten Zeitraum liegen keine Rohdaten
vor", solange `tage` noch leer ist, obwohl gerade geladen wird.

Beides ist transient und sperrt den Knopf nicht falsch (`laedt` sperrt ihn ohnehin) — aber ein
Panel, das während des Ladens „✓" für eine ungeprüfte Bedingung zeigt, ist genau die Sorte
Fehlinformation, die der Audit an anderer Stelle als Befund führt.

Zu tun: Während `laedt` einen Ladezustand je Zeile zeigen statt ein Urteil (z. B. „… wird
geprüft"), oder die Liste erst nach dem Laden rendern. Kein Urteil auf unvollständigen Daten.
Test dazu auf JVM-Ebene: `pruefeBerichtVoraussetzungen(zeitraum, emptyList(), null, emptyMap())`
darf für GEBIET **nicht** „erfüllt" liefern — entweder eigener Zustand oder `fehler != null`.

---

## 4 · Begründungen sind hart auf Deutsch, Labels sind lokalisiert

Die Labels liegen vollständig in `values/`, `values-de/` und `values-en/` — das ist korrekt
gemacht. Die **Begründungen** stehen dagegen als deutsche Literale in
`BerichtVoraussetzungen.kt`. Im englischen Locale (die App kann umschalten, siehe Kommentar in
`MainActivity.kt`) liest sich das so:

```
✗ Area classification set: Bitte zuerst einen Datumsbereich wählen.
```

Ebenfalls neu hart kodiert, entgegen der ausdrücklichen Vorgabe aus Phase 3 („neue Texte nach
`strings.xml` in allen drei Ressourcenordnern"): in `GesamtberichtStammdatenSheet.kt` die Texte in
`zeitgebundenerWertHinweis` („… zuletzt bestätigt am …", „Letzten Wert bewusst übernehmen"), die
Feldnamen „Kalibrierung" / „Wetter" / „Datenqualität", „Speichert …" und „Speichern
fehlgeschlagen. Bitte erneut versuchen.".

Zu tun: Diese Texte nach `strings.xml` in **alle drei** Ordner. Für die Begründungen heißt das,
dass `pruefeBerichtVoraussetzungen` keine fertigen Sätze mehr zurückgeben darf — gib eine
String-Ressourcen-ID plus Argumente zurück (die Funktion bleibt rein und testbar, sie kennt nur
IDs), und lass die UI daraus Text machen. Der bestehende Test
`BerichtVoraussetzungenTest` vergleicht heute ganze Sätze und ist entsprechend mitzuziehen.

Dass die Datei vorher schon viele deutsche Literale enthielt, ist bekannt — **nur die in #218 neu
hinzugekommenen** sind hier gemeint. Kein Massen-Umbau des Altbestands (AGENTS.md §5, kein
drive-by).

---

## 5 · 41 neue ktlint-Befunde

Gemessen auf `0653b55` gegen `35d050b` (Basis von #218), Nachrichten-Multimenge je Datei:
Gesamtzahl im Repo **7593 → 7625**, in den geänderten Dateien **41 neue** Befunde bei 9
weggefallenen. Aufschlüsselung:

| Anzahl | Datei | Regel |
|---|---|---|
| 8 | `BerichtScreenAndroidTest.kt` | `chain-method-continuation` |
| 8 | `BerichtErstellenSheet.kt` | `chain-method-continuation` (`?.`) |
| 6 | `BerichtErstellenSheetTest.kt` | `chain-method-continuation` |
| 3 | `BerichtScreen.kt` | `multiline-expression-wrapping` |
| 2 | `BerichtErstellenSheetTest.kt` | `chain-method-continuation` (`?.`) |
| 2 | `SettingsManagerReportRangeTest.kt` | `chain-method-continuation` |
| 4 | `SettingsManager.kt` | `function-signature` (3×), `chain-method-continuation` (1×) |
| 2 | `BerichtScreenAndroidTest.kt`, `ServiceControlInstrumentedTest.kt` | `import-ordering` |
| 1 je | `GesamtberichtStammdatenSheetInstrumentedTest.kt`, `ServiceControlInstrumentedTest.kt`, `SessionDaoTest.kt` (2), `MeasurementRecorderTest.kt`, `BerichtVoraussetzungenTest.kt` | diverse |

Die zwei `import-ordering`-Befunde sind in einer Zeile behoben. Die
`chain-method-continuation`-Befunde entstehen durch `a.b().c()` in einer Zeile, wo ktlint einen
Umbruch vor dem `.` will.

**Wenn du einen dieser Befunde bewusst stehen lässt, weil das Umbrechen den Code schlechter macht
als die Nachbarzeilen** (das ist ein legitimes Argument, AGENTS.md §5 gegen §6): lass ihn stehen
und **schreib es mit Zahl und Begründung in den PR**. Was nicht geht, ist die Behauptung aus #218
(„in den hinzugefügten oder geänderten Zeilen wurden die neuen Befunde behoben") bei 41 offenen
Befunden.

---

## 6 · Zwei Kleinigkeiten

- `zeitgebundenerWertHinweis` (`GesamtberichtStammdatenSheet.kt`, am Dateiende) ist ein
  `@Composable` in camelCase. Compose-Konvention und der Rest der Datei: **PascalCase**.
- `messtagGrenzen()` ist neu und richtig — aber `ladeBerichtstage` (`BerichtErstellung.kt:181-182`)
  berechnet dieselben Tagesgrenzen weiter inline. Beide stimmen heute überein
  (`atStartOfDay(zone)`), und **nur deshalb** trifft die Dedup-Gleichheit
  `fuerTag`/`giltFuerTagStart = :von`. Zwei Wahrheiten für dieselbe Grenze sind hier ein
  Korrektheitsrisiko, nicht nur Doppelung: `ladeBerichtstage` soll `messtagGrenzen()` benutzen.

---

## 7 · Was ausdrücklich in Ordnung ist (nicht anfassen)

Damit die Nachbesserung nicht mehr umbaut als nötig — das folgende ist geprüft und soll so
bleiben:

- `letzteBeendete()` statt `letzte()`: schließt eine noch offene Messung aus. Besser als der
  Auftrag verlangt hat, mit Test.
- Der Stammdatenvergleich deckt **alle 13** fachlichen Felder ab, Metadaten (`id`, `erstelltAm`,
  `giltFuerTagStart`) bleiben außen vor, Seriennummer bleibt zu Recht Groß-/Kleinschreibungs-
  sensibel. Der Test prüft jedes Feld einzeln.
- `BreadcrumbRingFileTest`: 800 × ~1 KiB gegen 256 KiB Obergrenze je Datei ⇒ die Rotation
  A → B → A wird weiterhin erreicht. Keine Prüfung abgeschwächt, nur schneller gemacht.
- `ServiceControlInstrumentedTest`: `performScrollToNode` statt `waitUntil` ist die **richtige**
  Lösung — der gesuchte Knoten lag in einer `LazyColumn` und war nie komponiert, der 30-s-Timeout
  war kein Zeitproblem. Bleibt so. (Falls der Test doch wieder rot wird: der 30-s-Puffer ist jetzt
  weg, dann wäre eine langsame Rekomposition die Ursache — melden, nicht wieder aufweichen.)

---

## 8 · Verifikation — und wie ktlint korrekt gemessen wird

```bash
./gradlew assembleDebug lintDebug test          # muss grün sein, Ausgabe in den PR
./gradlew connectedAndroidTest                  # du hast einen Emulator - ausführen
```

ktlint-Vergleich, damit die Aussage im PR trägt (das ist die Methode, mit der die 41 Befunde
gemessen wurden):

```bash
git checkout --detach origin/main
./gradlew ktlintCheck --continue
cat app/build/reports/ktlint/*Check/*.txt > /tmp/kt_basis.txt
git checkout <dein-branch>
./gradlew ktlintCheck --continue
cat app/build/reports/ktlint/*Check/*.txt > /tmp/kt_neu.txt
```

Dann je geänderter Datei die **Multimenge der Nachrichten** vergleichen (Zeilennummern vorher
wegschneiden, sie verschieben sich durch jede Einfügung). Der Report enthält
ANSI-Farbcodes — vor dem Vergleich entfernen, sonst greift kein Dateinamen-Filter.

---

## 9 · Definition of Done

1. `assembleDebug`, `lintDebug`, `test` grün — **Ausgabe im PR**, nicht zusammengefasst.
2. `connectedAndroidTest` gelaufen, Ergebnis im PR.
3. Room-Migrationstests grün, Schema weiterhin v25, `app/schemas/` unberührt.
4. Punkte 2–6 umgesetzt; Punkt 1 umgesetzt **oder** mit der Antwort des Owners dokumentiert.
5. ktlint: entweder 0 neue Befunde in den geänderten Dateien, **oder** die verbleibende Zahl mit
   Begründung im PR. Gemessen nach Abschnitt 8, nicht geschätzt.
6. Draft-PR gegen `main`, Kurzmeldung an den Owner: erledigt / nicht erledigt / aufgefallen.
