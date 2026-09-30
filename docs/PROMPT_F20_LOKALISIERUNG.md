# Prompt: F-20 — UI-Literale lokalisieren

Umsetzung von **F-20** aus `docs/UX_UI_AUDIT.md`: hartkodierte deutsche UI-Texte nach
`strings.xml` verschieben.

Vorgesehener Bearbeiter: **Antigravity** (AGENTS.md §9 — UI-lastige Arbeit mit visueller
Rückmeldung).

**Lies zuerst `AGENTS.md` vollständig.** Danach den F-20-Eintrag im Audit (Suche nach `#### F-20`).

---

## 0 · Arbeitsregeln

- `git fetch origin`, dann `git switch -c feature/f20-lokalisierung-stapel1 origin/main`.
  **Nie auf `main` pushen.**
- Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten — **ein Commit
  je Datei**.
- Neue Zeichenketten in **allen drei** Ressourcenordnern: `values/`, `values-de/` (beide
  deutsch), `values-en/` (englisch).
- `./gradlew assembleDebug lintDebug test` grün. **Wichtig in Sandboxen:** `lintDebug` und
  `test` **nicht im selben Gradle-Aufruf** — das erzeugt Speicherdruck und acht falsche
  `AppNotIdleException`-Fehlschläge in fremden Testklassen (gemessen 30.09.2026 in PR #236).
  Getrennt aufrufen.
- `ktlintCheck` ohne neue Befunde in den geänderten Dateien. Der Formatierer läuft über ganze
  Sourcesets — danach **alle fremden Dateien zurückdrehen**, sonst enthält der PR
  Drive-by-Umbrüche.
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

## 1 · Stand, gemessen am 30.09.2026

Die Zahlen im Audit stammen von vor mehreren Phasen und sind überholt:

| | Audit | heute |
|---|---|---|
| `Text("…")` unter `ui/` | 156 | **148** |
| `contentDescription = "…"` unter `ui/` | nicht genannt | **8** |
| Einträge je `strings.xml` | 441 | **509** (alle drei gleich) |
| `ResourceIntegrityTest` prüft | 9 Strings | 9 Strings |

Messbefehl: `grep -rn 'Text("' app/src/main/java/com/example/lrmprotokoll/ui | wc -l`

## 2 · Was zu tun ist

**Nur sichtbarer UI-Text.** Nicht anfassen: `testTag(...)`, Log-Meldungen, Exception-Texte,
`DiagnosticCode`-Werte, Dateinamen, alles unter `report/pdf/`.

**Namenskonvention** — Präfix nach Bildschirm, wie im Bestand: `settings_*`, `meter_*`,
`bericht_*`, `cockpit_*`, `drive_*`, `foto_*`, `diagnose_*`.

**Keine Verhaltensänderung.** Reine Textextraktion. Kein Layout, keine Logik, keine neuen
Bedienelemente. Texte **nicht umformulieren** — verschieben heißt verschieben.

**Keine Plurals einführen.** Das Repo hat null `<plurals>`. Wo ein Zähler im Text steht,
entweder neutral formulieren („Messtage: %1$d") oder zwei Zeichenketten anlegen
(`…_eine` / `…_mehrere`). Android Lint meldet dafür `PluralsCandidate` als Warnung — in diesem
Repo akzeptiert.

## 3 · Stapel 1 — diese zwölf Dateien, in dieser Reihenfolge

Alle anderen UI-Dateien sind durch offene PRs blockiert (#216, #233, #234, #235, #236).
**Fass sie nicht an** — insbesondere nicht `SettingsScreen.kt`, mit 32 Literalen der größte
Brocken, aber von #233 belegt.

| # | Datei | Literale |
|---|---|---|
| 1 | `GesamtberichtStammdatenSheet.kt` | 17 |
| 2 | `BerichtErstellenSheet.kt` | 14 |
| 3 | `DriveFolderPickerDialog.kt` | 13 |
| 4 | `DriveStatusCard.kt` | 9 |
| 5 | `DiagnoseScreen.kt` | 9 |
| 6 | `FotoDokumentationSheet.kt` | 7 |
| 7 | `MeterControlCard.kt` | 6 |
| 8 | `ProtokollDetailScreen.kt` | 4 |
| 9 | `VideoAufnahmeScreen.kt` | 3 |
| 10 | `ReportAreaSelection.kt` | 3 |
| 11 | `OemDeviceHelperCard.kt` | 3 |
| 12 | `MicrophoneStatusBadge.kt` | 3 |

Zusammen **91 Literale**. Reihenfolge nach Sichtbarkeit: das Stammdaten-Sheet und das
Berichts-Sheet sieht der Nutzer bei jeder Messung bzw. jedem Bericht.

Vor dem Start prüfen, ob inzwischen PRs gemergt sind — dann wächst der Stapel entsprechend.

## 4 · Der eigentliche Aufwand — die Tests

Der Audit sagt es deutlich: *„Compose-Tests, die auf deutschen Text matchen, müssen auf
`stringResource` umgestellt werden — das betrifft viele Tests und ist der eigentliche Aufwand."*

Vor jeder Datei: `grep -rn "onNodeWithText" app/src/test app/src/androidTest` nach den Texten
dieser Datei durchsuchen. Jeder Treffer wird auf
`composeRule.activity.getString(R.string.…)` umgestellt — **nicht** die Oberfläche um den Test
herumbauen (AGENTS.md; derselbe Fehler ist in S-5 ausdrücklich benannt).

## 5 · Neuer Test — die eigentliche Absicherung

`ResourceIntegrityTest` prüft heute **neun Beispielstrings**. Das fängt genau das nicht ab,
worum es hier geht.

Ergänze einen **JVM-Test** (kein Robolectric nötig), der die drei `strings.xml` direkt aus dem
Quellbaum liest und die **Schlüsselmengen vergleicht**:

- jeder Schlüssel in `values/` existiert auch in `values-de/` und `values-en/`
- keine Datei hat Schlüssel, die die anderen nicht haben
- kein Wert ist leer

Damit fällt künftig jede Zeichenkette auf, die jemand nur in einen Ordner legt — der Fehler,
der bei rund 150 Verschiebungen garantiert passiert. Heute stehen alle drei Dateien bei
**509** Einträgen; der Test hält das fest.

## 6 · Definition of Done

1. `assembleDebug` grün, `lintDebug` grün, `test` grün — Ausgabe im PR, **getrennt aufgerufen**.
2. Alle drei `strings.xml` haben dieselbe Schlüsselmenge; der neue Test belegt das.
3. Die Sprachumschaltung auf Englisch zeigt in den zwölf Dateien **keinen deutschen Text mehr**
   — am Emulator geprüft, mit Bildschirmfotos im PR.
   (`app/src/test/.../BildschirmfotoWerkstatt.kt` nimmt Screenshots ohne Emulator auf, falls
   keiner verfügbar ist.)
4. `grep -rn 'Text("' app/src/main/java/com/example/lrmprotokoll/ui | wc -l` ist von **148**
   auf **≤ 57** gefallen.
5. Draft-PR gegen `main` mit: was geändert · was verifiziert (Befehl + Ergebnis) · was bewusst
   offen blieb.

## 7 · Ausdrücklich **nicht** Teil des Auftrags

- Die Dateien, die offene PRs anfassen. Die kommen in Stapel 2, nachdem #216/#233/#234/#235/#236
  gemergt sind.
- `Messintegritaet.anzeigetext()` und andere fest verdrahtete deutsche Texte **außerhalb** von
  `ui/` — eigener Befund, eigener Stapel.
- Neue Sprachen über Deutsch/Englisch hinaus.
- Texte verbessern, kürzen oder vereinheitlichen.
