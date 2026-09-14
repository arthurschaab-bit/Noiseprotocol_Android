# Prompt: Bericht-Umbau Nachträge — Konfigurationsfelder & Bericht-Erstellungs-Ablauf

Zwei Nachträge aus `docs/DATENMAPPING_BERICHT_SCHRITT4.md` (Abschnitt 7), die vor dem eigentlichen
Python-Port fehlen: zwei zusätzliche `ReportConfigEntity`-Felder und ein neuer "Bericht jetzt
erzeugen"-UI-Ablauf im Bericht-Tab. **`report_bridge.py` selbst ist nicht Teil dieses Auftrags**
(siehe `docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md`, `docs/PROMPT_BERICHT_GEBIETSEINSTUFUNG.md` und
`docs/PROMPT_BERICHT_CHAQUOPY_INTEGRATION.md` für die weiteren Schritte) —
`ChaquopyReportRunner.erzeugeBericht()` existiert bereits und schlägt bis dahin erwartungsgemäß
mit `PyException` ("Modul nicht gefunden") fehl; dieser Auftrag muss diesen Fehlschlag nur sauber
im UI anzeigen, nicht beheben.

Zwei getrennte PRs, wie bei M11 üblich — Teil A ist unabhängig nutzbar, Teil B baut auf Teil A auf.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen, sie gilt unverändert. Insbesondere:

- Neuer Branch von `main`: `feature/bericht-config-nachtraege` (Teil A) bzw.
  `feature/bericht-erstellen-ablauf` (Teil B, nach A). **Nie auf `main` pushen.**
- Commit-Nachrichten deutsch, klein geschnitten. Code-Bezeichner englisch, UI-Texte deutsch.
- `./gradlew assembleDebug` und `./gradlew test` müssen grün sein — **Ausgabe in den PR**, nicht
  deren Zusammenfassung.
- `fallbackToDestructiveMigration()` ist verboten. Jede Schemaänderung bekommt eine explizite
  `Migration` **und** einen Migrationstest (siehe `AppDatabaseV23MigrationTest.kt` als Vorlage).
- **Doku und Tests sind Teil jedes Teilschritts, nicht Nachtrag am Ende.** Siehe Abschnitt 4 —
  das ist keine Kür.
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7 und Abschnitt 5 hier.

---

## 1 · Ist-Zustand — mit Fundstelle

### Aktueller Stand von `ReportConfigEntity`

`app/src/main/java/com/example/lrmprotokoll/data/ReportConfigEntity.kt` (Schema-Version 23, nach
PR #140):

```kotlin
data class ReportConfigEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val schaetzpegelTeilerfassungDb: Double = 50.0,
    val schaetzpegelMessfensterAbbruchDb: Double = 55.0,
    val tierSchwelleVollmessungProzent: Double = 90.0,
    val tierSchwelleTeilerfassungProzent: Double = 70.0,
    val gebietseinstufung: String = "",
    val geraeteUnsicherheitDb: Double = 1.4,
)
```

`adresse`/`hardwareId` wurden in PR #140 bewusst wieder entfernt (Migration 22→23, redundant zu
`StammdatenVerlaufEntity.messort`/`.geraetSeriennummer`) — **nicht wieder einführen.**

### Migrationsmuster

`app/src/main/java/com/example/lrmprotokoll/data/AppDatabase.kt`: additive Spalten via
`ALTER TABLE ... ADD COLUMN` (z. B. `MIGRATION_21_22`), Spaltenentfernung via
Neuanlegen-Kopieren-Umbenennen (`MIGRATION_22_23`, da SQLite < 3.35 kein
`DROP COLUMN` kennt und minSdk 29 das nicht garantiert). `ALLE_MIGRATIONEN`-Array am Ende
ergänzen, `@Database(version = ...)` hochzählen. Aktuell `version = 23` — die neue Migration ist
**24**.

### Bestehende Settings-UI, die erweitert werden muss

`app/src/main/java/com/example/lrmprotokoll/ui/SettingsScreen.kt`, Sektion
`"Berichtsparameter (§ 287 ZPO)"` (unter `SettingsTab.BERICHT`, `expBerichtParameter`-Zustand):
lädt/speichert `ReportConfigEntity` via `container.database.reportConfigDao()`, ein
`LaunchedEffect(expBerichtParameter)` lädt beim Aufklappen, `speichereReportConfig()` schreibt bei
jeder Änderung sofort (kein Speichern-Button). Bestehende Test-Tags:
`slider_report_tier_vollmessung`, `slider_report_tier_teilerfassung`,
`slider_report_schaetzpegel_teilerfassung`, `slider_report_schaetzpegel_messfenster`,
`slider_report_geraeteunsicherheit`, `input_report_gebietseinstufung`. Zugehöriger Test:
`app/src/test/java/com/example/lrmprotokoll/ui/ReportConfigSettingsTest.kt` (Robolectric).

### Der zu portierende Originalwert

`gesamtbericht_lib_v3.py` (öffentliches Referenz-Repo `arthurschaab-bit/Baul-rm`), Zeile 52f.:

```python
KONSERVATIV_FENSTER_START = 15    # Messende in diesem Fenster (15-19 Uhr) -> Rest bis 20h bruecken
KONSERVATIV_FENSTER_ENDE  = 19
```

Wird zusammen mit den bereits portierten `schaetzpegel*`/`tierSchwelle*`-Feldern für die
konservative Volltag-Hochrechnung gebraucht (siehe `DATENMAPPING_BERICHT_SCHRITT4.md`
Abschnitt 2/5).

### Relevante Entities/DAOs für Teil B

- `MeasurementEntity` (`sessionId, timestamp, levelDb, weighting, flags, timeWeighting, range`),
  `MeasurementFlags.GAP`/`.GAP_REASON_SENSOR_ERROR` — `weighting`/`timeWeighting` bleiben `null`,
  solange `MeterFrame.modeAssumptionConfirmed` nicht auf Hardware bestätigt ist
  (`CHECKLISTE_GERAETETEST.md` Teil F, noch offen).
- `MeasurementDao.zwischen(von, bis)`, `MinuteAggregateDao.zwischen(von, bis)`,
  `SessionDao.zwischen(von, bis)` (alle in `SessionDao.kt`) — Bausteine für die
  Retention-Prüfung.
- `RetentionCoordinator` (`messreihe/RetentionCoordinator.kt`, `aufbewahrungstage = 90`):
  verdichtet `MeasurementEntity` zu `MinuteAggregateEntity` und löscht die Rohzeilen.
- `StammdatenVerlaufEntity`/`StammdatenVerlaufDao` (`data/StammdatenVerlaufEntity.kt`): aktuell
  nur `insert()` und `letzte(anzahl)` — **kein** Query nach Zeitraum, muss ergänzt werden.
- `gruppiereSessionsNachTag()` (`messreihe/SessionFilterUndGruppierung.kt:83`): die im Projekt
  etablierte Kalendertag-Definition (`SimpleDateFormat("dd.MM.yyyy")`, lokale Zeitzone) — **für
  Teil B wiederverwenden, keine neue Tagesgrenze erfinden.**
- `ChaquopyReportRunner` (`report/ChaquopyReportRunner.kt`): `suspend fun
  erzeugeBericht(parameterJson: String): Ergebnis` mit `sealed interface Ergebnis { Erfolg(pdfPfad);
  Fehler(nachricht, ursache) }`.
- `BerichtScreen.kt`: aktuell nur der alte Zeitraum-/Gesamtbericht-Dialog (Presets 7/30 Tage/
  dieser Monat über `TextButton`, **kein** `DatePicker` im gesamten Projekt vorhanden). Der neue
  Ablauf ist ein **zusätzlicher** Einstiegspunkt, der alte bleibt unverändert bestehen (Owner-
  Vorgabe: erst ablösen, wenn der Chaquopy-Bericht ihn funktional ersetzt).

---

## 2 · Teil A — Zwei neue `ReportConfigEntity`-Felder

**2.1** Zwei neue Felder, additive Migration 23→24 (`ALTER TABLE ... ADD COLUMN`, analog
`MIGRATION_21_22`):

```kotlin
val konservativFensterStartStunde: Int = 15,
val konservativFensterEndeStunde: Int = 19,
val erzwingeBerichtOhneBestaetigteBewertung: Boolean = false,
```

(Drei Felder, nicht zwei — das Override-Feld aus `DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 6.3
gehört fachlich hierher, auch wenn es für Teil B gebraucht wird; sinnvollerweise in derselben
Migration, damit nicht zwei Migrationen für dieselbe Owner-Entscheidung entstehen.)

**2.2** Settings-UI: die bestehende `"Berichtsparameter (§ 287 ZPO)"`-Sektion um zwei Slider
(Konservativ-Fenster Start/Ende, ganze Stunden, `valueRange = 0f..23f`, Start darf Ende nicht
überschreiten — bei Widerspruch sinnvoll klemmen statt stillschweigend ungültige Werte zu
speichern) und einen Switch (Override) erweitern. Gleiches Muster wie die bestehenden Felder:
sofortiges Speichern über `speichereReportConfig()`, eigene Test-Tags
(`slider_report_konservativ_start`, `slider_report_konservativ_ende`,
`switch_report_erzwinge_override`).

**2.3** `KDoc` an `ReportConfigEntity` ergänzen (wie bei den vorherigen Feldern), das die
Herkunft (Originalskript-Konstanten) und die Owner-Entscheidung zum Override-Feld referenziert.

### Tests Teil A

- `AppDatabaseV24MigrationTest.kt` (Vorlage: `AppDatabaseV23MigrationTest.kt`): v23-Datenbank mit
  einer `report_config`-Zeile anlegen, migrieren, neue Spalten mit Default-Werten prüfen.
- `ReportConfigSettingsTest.kt` um die zwei neuen Slider/den Switch ergänzen (gleiches Muster wie
  die bestehenden Fälle dort — `performSemanticsAction(SemanticsActions.SetProgress)` für Slider,
  nicht `swipeRight()`, siehe Kommentar im bestehenden Test, warum).

---

## 3 · Teil B — "Bericht jetzt erzeugen"-Ablauf

Neuer Button in `BerichtScreen.kt`, deutlich getrennt vom bestehenden Zeitraumbericht-Button
(eigener Text/Test-Tag, z. B. `btn_bericht_erstellen_v2` — Name bewusst offen für einen besseren
Vorschlag). Öffnet einen neuen Screen oder ein `ModalBottomSheet` (Vorschlag: eigene Datei
`BerichtErstellenSheet.kt`, analog `GesamtberichtStammdatenSheet.kt`).

**3.1 · Zeitraumauswahl.**

**Offene Entscheidung, nicht selbst treffen:** Presets wie beim alten Dialog (7/30 Tage/dieser
Monat) sind für einen Rechtsbericht vermutlich unpassend — ein Fall hat einen konkreten,
dokumentierten Zeitraum, keinen "letzte 30 Tage"-Bezug zu heute. Ein echter Datums-Bereich wäre
die erste Verwendung von `DatePicker`/`DateRangePicker` im Projekt. **Frag den Owner**, bevor du
eins von beidem baust.

**3.2 · Retention-Prüfung** (`DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 6.1, entschieden:
ablehnen statt approximieren). Für jeden Kalendertag im gewählten Zeitraum (via
`gruppiereSessionsNachTag`-Logik bzw. `SessionDao.zwischen`): prüfen, ob für die zugehörigen
Sessions noch `MeasurementEntity`-Rohzeilen existieren (`MeasurementDao.zwischen`/`fuerSession`)
oder ob dieser Tag bereits ausschließlich als `MinuteAggregateEntity` vorliegt
(`MinuteAggregateDao.zwischen`). Bei mindestens einem betroffenen Tag: Bericht **nicht**
erzeugen, stattdessen eine Meldung, die den/die betroffenen Tag(e) konkret nennt.

**3.3 · Stammdaten-Auswahl** (Abschnitt 6.2, entschieden: Nutzer wählt explizit). Neue Methode an
`StammdatenVerlaufDao` (z. B. `zwischen(von: Long, bis: Long): List<StammdatenVerlaufEntity>`,
analog den anderen DAOs). Für jeden Kalendertag im Zeitraum mit **mehr als einem** Eintrag: dem
Nutzer eine Auswahl anbieten (welcher gilt für diesen Tag). Bei genau einem Eintrag: automatisch
übernehmen. **Offene Entscheidung, nicht selbst treffen:** Was passiert bei **keinem** Eintrag für
einen Tag im Zeitraum (kein Stammdatensatz je erfasst)? Bericht ablehnen, mit Lücke fortfahren,
oder Nutzer nachträglich einen Eintrag ausfüllen lassen? Frag den Owner.

**3.4 · Override-Prüfung** (Abschnitt 6.3, entschieden: default verweigern, konfigurierbar
übersteuerbar). Prüfen, ob im Zeitraum `MeasurementEntity`-Zeilen mit `weighting == null` oder
`timeWeighting == null` vorkommen. Wenn ja und
`ReportConfigEntity.erzwingeBerichtOhneBestaetigteBewertung == false`: Bericht ablehnen, mit
Verweis auf die Einstellung. Wenn `true`: fortfahren, aber das JSON-Parameter-Objekt (3.5) muss
ein Flag mitgeben, das `report_bridge.py` später zwingt, einen sichtbaren Disclaimer in den
Bericht zu drucken (Feldname z. B. `"unconfirmedWeightingOverride": true` — nur ein Vorschlag,
der eigentliche Vertrag entsteht mit `PROMPT_BERICHT_CHAQUOPY_INTEGRATION.md`).

**3.5 · Aufruf von `ChaquopyReportRunner.erzeugeBericht()`.** Baue aus Zeitraum, gewählten
Stammdaten je Tag, `ReportConfigEntity` und dem Override-Flag ein JSON-Objekt (Struktur an
`DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 2 orientieren, aber **ausdrücklich als vorläufig
kennzeichnen** — `PROMPT_BERICHT_CHAQUOPY_INTEGRATION.md` legt den echten Vertrag fest) und rufe
die Methode auf. Zeige `Ergebnis.Erfolg`/`Ergebnis.Fehler` im UI. **Der aktuell zu erwartende
Fehler (`PyException`, Modul nicht gefunden) ist korrektes Verhalten für diesen Auftrag** — er
muss nur klar und verständlich angezeigt werden, nicht als Absturz oder unklare Fehlermeldung.

### Tests Teil B

- Reine Funktionstests (JVM, kein Robolectric nötig wo möglich) für: Retentions-Prüfung
  (Tag mit/ohne Rohdaten), Stammdaten-Auswahl-Logik (0/1/mehrere Einträge pro Tag),
  Override-Prüfung (bestätigt/unbestätigt × Override an/aus) — jeweils mit handgeschriebenen
  Fakes, kein Mockito/MockK.
- Ein Compose/Robolectric-Test (Vorlage: `ReportConfigSettingsTest.kt`), der den neuen Button
  öffnet und mindestens den Erfolgsfall der Validierung bis zum (erwarteten) `PyException`-Fehler
  durchspielt.

---

## 4 · Doku — kein Nachtrag, Teil des Auftrags

1. `docs/DATENMAPPING_BERICHT_SCHRITT4.md`, Abschnitt 7: die beiden dort genannten Nachträge als
   umgesetzt markieren, mit Verweis auf die tatsächlichen Feldnamen/Dateien (falls du von den
   Vorschlägen hier abweichst).
2. `docs/CHECKLISTE_GERAETETEST.md`, Teil F: neuen Eintrag **F13** im Format der bestehenden
   Einträge (siehe F10-F12) für den neuen Bericht-Erstellungs-Ablauf — er hat nie ein Display
   gesehen. Testfälle mindestens: Zeitraum mit vollständigen Rohdaten → Bericht wird angestoßen
   (erwarteter `PyException`-Fehler erscheint verständlich); Zeitraum mit bereits verdichtetem Tag
   → Ablehnung mit korrekter Tagesangabe; Tag mit mehreren Stammdaten-Einträgen → Auswahl
   erscheint; unbestätigte Bewertung ohne Override → Ablehnung; mit Override → Warnhinweis
   sichtbar.
3. KDoc an allen neuen/geänderten Entities, DAOs und Composables — Stil wie im übrigen Projekt
   (WARUM, nicht WAS; Owner-Entscheidung mit Datum referenzieren, wo zutreffend).

---

## 5 · Was ausdrücklich **nicht** Teil des Auftrags ist

- `report_bridge.py` selbst (das eigentliche Python-Modul) — siehe die drei weiteren Prompts.
- Gebietseinstufungs-Generalisierung (andere BauNVO-Zonen, angepasster Fließtext) — siehe
  `docs/PROMPT_BERICHT_GEBIETSEINSTUFUNG.md`.
- Bautagebuch-Erfassung, Vergleichstag-Kennzeichnung, Dauerlärm-Phasenerkennung,
  Quellenverteilung nach Lärmart (alle laut Owner-Entscheidung 13.09.2026 out of scope für "V1
  minimal", siehe `DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 1).
- Der alte Zeitraum-/Gesamtbericht-Dialog in `BerichtScreen.kt` — bleibt unverändert bestehen.

---

## 6 · Definition of Done

1. `./gradlew assembleDebug` und `./gradlew test` grün — Ausgabe im PR.
2. Alle bestehenden **und** die neue Room-Migration mit Test abgesichert.
3. Jeder Punkt aus Abschnitt 2 und 3 einzeln adressiert — auch die als "offene Entscheidung"
   markierten, mit der Antwort des Owners oder mit der Frage, falls sie noch aussteht.
4. Abschnitt 4 (Doku) tatsächlich erledigt, nicht nur erwähnt.
5. Draft-PR gegen `main`: was geändert wurde · was verifiziert wurde (Kommando + Ergebnis) · was
   offen blieb · jede Abweichung von diesem Prompt mit Begründung.
