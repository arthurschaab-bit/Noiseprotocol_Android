# Prompt: Bugfix — Drive-Sync treibt die App in den Speichermangel

**Priorität 1.** Die App stürzt auf dem Owner-Gerät (Huawei P30, Android 10) im normalen Betrieb
alle paar Minuten mit `OutOfMemoryError` ab: 89 Abstürze in einer Woche. Dieser Auftrag behebt
den größten Verursacher. Belege und Zeitlinie: [`BEFUNDE_P30_2026-09-23.md`](BEFUNDE_P30_2026-09-23.md),
Abschnitt 2, A1.

Die Ursache ist analysiert und im Code lokalisiert. Deine Aufgabe ist die Umsetzung. **Prüfe jede
genannte Stelle trotzdem am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c fix/drive-sync-speicher origin/main`
  (AGENTS.md §5 „Sync before you start“). Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten. Beispiel:
  `fix(drive): Nachholen ueberspringt abgeschlossene Tage ohne Rohwerte zu laden`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`.
  - ktlint: keine neuen Befunde in den geänderten Dateien.
  - Nur bei Grün committen und pushen. Ist ein Test rot, den deine Änderung nicht berührt: nicht
    einfach committen, sondern mit Ausgabe im PR melden und den Owner fragen.
- **Nur handgeschriebene Fakes**, kein Mockito, kein MockK.
- **Keine Schemaänderung.** Wenn du glaubst, eine zu brauchen: anhalten und den Owner fragen.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR, nicht Zusammenfassungen davon.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Die Ursache

### 1.1 Das Nachholen lädt 29 Tage Rohwerte, bevor es prüft, ob überhaupt etwas fehlt

`drive/DriveSyncCoordinator.kt`, `holeVersaeumteTageNach()` (Zeile 333ff.):

```kotlin
for (tagOffset in 1..29) {
    ...
    val samples = levelSampleDao.zwischen(tagVon.toEpochMilli(), tagBis.toEpochMilli())   // ganzer Tag als Liste
    ...
    val zeilen = PegelAggregator.aggregiere(samples, ereignisse, tagVon, tagBis, fensterDauer)
    val registry = dailyFileDao.byDate(tagesSchluessel)
    if (registry != null && registry.state == DriveSyncState.SYNCED && registry.lastRowCount == zeilen.size) {
        return@runCatching // erst HIER steht fest, dass der Tag gar nicht fehlte
    }
```

- `level_samples` hat auf dem Owner-Gerät 8,6 Millionen Zeilen, rund 290.000 pro Tag.
- Jeder Sync-Lauf (alle 30 min **und** bei jedem Lärmereignis) lädt damit bis zu 29 volle Tage
  nacheinander in den Speicher, nur um festzustellen, dass sie schon synchronisiert sind.
- Der heutige Tag wird in `syncEinenZyklus()` (Zeile 117) ebenfalls als ganze Liste geladen.

### 1.2 Mehrere Läufe gleichzeitig, und laufende Läufe werden abgebrochen

`drive/DriveSyncWorker.kt`:

- `DriveSyncPlanung.plane()` (Zeile 91) plant den periodischen Lauf unter `WORK_NAME`.
- `starteSofort()` (Zeile 120) plant einen Sofortlauf unter `"${WORK_NAME}_immediate"` mit
  `ExistingWorkPolicy.REPLACE` (Zeile 134). Es gibt folgende Aufrufer:
  - `AudioRecordingService` (bei jedem Ereignis)
  - `FotoDokumentationSheet`
  - `ProtokollDetailScreen`
  - `VideoMuxWorker`
- Die zwei Namen laufen **parallel**; auf dem Gerät starten bis zu vier Läufe in derselben
  Zehntelsekunde.
- `REPLACE` **bricht einen laufenden Sofortlauf ab**. Das Nachholen wird deshalb nie fertig
  (Logcat: „Nachholen von 2026-09-20 fehlgeschlagen: Job was cancelled“; `drive_daily_files` hat
  nur 9 Einträge).
- Der nächste Lauf fängt wieder bei 29 Tagen an: ein Teufelskreis.

---

## 2 · Auftrag

### Schritt 1 — Nur ein Sync-Lauf gleichzeitig

- In `DriveSyncCoordinator` einen `kotlinx.coroutines.sync.Mutex` einführen und den gesamten Rumpf
  von `syncEinenZyklus()` darunter ausführen (`withLock`).
- Der Coordinator ist ein Singleton im `AppContainer`. Prüfe das; der Mutex ist dann ein Feld der
  Instanz.
- Wartet ein Lauf auf das Lock, einen Breadcrumb schreiben: `"Drive-Sync wartet auf laufenden
  Zyklus"`. Nur einmal pro Lauf, **vor** dem Warten; mit `tryLock()` prüfbar.

### Schritt 2 — Laufende Sofortläufe nicht mehr abbrechen

- In `DriveSyncPlanung.starteSofort()` `ExistingWorkPolicy.REPLACE` durch
  **`ExistingWorkPolicy.KEEP`** ersetzen.
- **Owner-Vorgabe (vorbelegt), im PR ausdrücklich nennen:** Kommt ein Ereignis, während ein
  Sofortlauf gerade *läuft*, wird dafür kein zweiter Lauf eingeplant. Das Ereignis geht spätestens
  mit dem nächsten periodischen Lauf (≤ 30 min) nach Drive.
- Falls du einen Grund findest, der gegen `KEEP` spricht (z. B. ein Aufrufer, der sich auf den
  sofortigen Upload verlassen muss): **nicht selbst entscheiden**, im PR beschreiben und den Owner
  fragen.

### Schritt 3 — Abgeschlossene Tage überspringen, ohne Rohwerte zu laden

In `holeVersaeumteTageNach()` **vor** `levelSampleDao.zwischen(...)`:

```kotlin
val registry = dailyFileDao.byDate(tagesSchluessel)
if (registry != null && registry.state == DriveSyncState.SYNCED &&
    registry.lastSyncedAt >= tagBis.toEpochMilli()) {
    return@runCatching // nach Tagesende synchronisiert -> endgueltig, nichts zu tun
}
```

Begründung:
- Rohwerte eines vergangenen Tages kommen nicht nachträglich hinzu; die Messung läuft live.
- Wer nach dem Tagesende erfolgreich synchronisiert hat, hat also den vollständigen Tag
  hochgeladen.
- Ein Tag, der nur *während* des Tages synchronisiert wurde (`lastSyncedAt < tagBis`), wird genau
  einmal nachgeholt und ist danach endgültig.

Vorher prüfen: Wie setzen die beiden Schreibstellen (heute und Nachholen) `lastSyncedAt`? Die
Regel oben setzt voraus, dass `lastSyncedAt` der Zeitpunkt des Syncs ist, nicht der des Tages.
Stimmt das nicht, anhalten und melden.

Die bestehende Prüfung `lastRowCount == zeilen.size` bleibt für nicht endgültige Tage erhalten.

### Schritt 4 — Rohwerte stückweise aggregieren statt als ganzen Tag

- Für die Tage, die tatsächlich geladen werden müssen, und für den heutigen Tag in
  `syncEinenZyklus()`: die Rohwerte in **Zeitabschnitten** laden und aggregieren statt mit einem
  `zwischen()` über den ganzen Tag.
- Die Abschnittslänge muss ein **ganzzahliges Vielfaches der Fensterdauer**
  (`settings.driveAggregationSekunden`) sein, gerechnet ab dem Tagesbeginn. Ziel sind etwa 60
  Minuten. So fallen Fenstergrenzen nie in die Mitte eines Abschnitts.
- Die Ereignisse (`noiseDao.zwischenZeitpunkt`) sind wenige; sie dürfen einmal pro Tag geladen und
  dann je Abschnitt gefiltert werden.
- Das Ergebnis muss **identisch** mit dem bisherigen Ergebnis sein: gleiche Zeilen, gleiche
  Reihenfolge, gleiche Werte.
- **Vorher `PegelAggregator.aggregiere()` lesen.** Hängt das Ergebnis eines Fensters von Werten
  außerhalb des Fensters ab (gleitende Mittel, Übertrag zwischen Fenstern): **anhalten und
  melden**, nicht umbauen.
- Keine neue DAO-Methode nötig; `levelSampleDao.zwischen(von, bis)` je Abschnitt genügt.

### Nicht Teil dieses Auftrags

- Die Datenbanksicherung (`ladeDatenbankSicherungHoch`): eigener Auftrag
  `PROMPT_FIX_DATENBANK_SICHERUNG.md`. **Nicht anfassen.**
- CSV-Format, Ordnerstruktur und Aggregationslogik selbst bleiben unverändert.

---

## 3 · Tests (JVM, handgeschriebene Fakes)

Die Fakes aus den vorhandenen Tests wiederverwenden, vor allem aus
`app/src/test/.../drive/DriveSyncCoordinatorTest.kt` und `DriveSyncWorkerTest.kt`.

1. **Endgültige Tage werden nicht geladen:**
   - Fake-`LevelSampleDao`, das jeden `zwischen`-Aufruf mit `(von, bis)` protokolliert.
   - Registry für Tag X mit `SYNCED` und `lastSyncedAt` nach dem Tagesende.
   - Nach `syncEinenZyklus()` gab es **keinen** Aufruf im Zeitraum von Tag X.
2. **Einmal nachholen, danach endgültig:** Registry mit `lastSyncedAt` *vor* dem Tagesende. Der
   erste Lauf lädt und schreibt, der zweite lädt nicht mehr.
3. **Stückweise = ganz:**
   - Ein Tag mit mindestens 20.000 zufälligen Rohwerten (fester Seed) plus einigen Ereignissen.
   - Das neue stückweise Ergebnis ist `==` dem Ergebnis von `PegelAggregator.aggregiere()` über den
     ganzen Tag.
   - Für mehrere Fensterdauern, darunter eine, die 3600 nicht teilt (z. B. 7 s).
4. **Speichergrenze als Proxy:** Kein protokollierter `zwischen`-Aufruf umfasst mehr als eine
   Abschnittslänge (≈ 60 min).
5. **Nur ein Lauf gleichzeitig:**
   - Das Fake-DAO suspendiert in `zwischen` (z. B. `CompletableDeferred`) und zählt gleichzeitig
     aktive Aufrufe.
   - Zwei parallel gestartete `syncEinenZyklus()` ergeben maximal 1 gleichzeitig.
6. **`KEEP`:** Mit `WorkManagerTestInitHelper` prüfen, dass ein zweites `starteSofort()` einen
   laufenden bzw. eingeplanten Sofortlauf nicht ersetzt. Die Hilfe wird im Repo schon benutzt,
   z. B. in `SupportOutboxReportSenderTest` und `SupportBundleHealthWorkerTest`; dort abschauen.

Jeder Test muss **ohne deine Änderung rot** sein, sofern er eine Verhaltensänderung prüft. Zeig
das im PR (Ausgabe vorher/nachher).

---

## 4 · Akzeptanzkriterien

- [ ] `syncEinenZyklus()` läuft nie parallel (Test 5).
- [ ] `starteSofort()` nutzt `KEEP`; der Zielkonflikt ist im PR genannt.
- [ ] Nach Tagesende synchronisierte Tage werden ohne Rohwert-Abfrage übersprungen (Test 1, 2).
- [ ] Kein `levelSampleDao.zwischen()` über mehr als eine Abschnittslänge (Test 4).
- [ ] Aggregationsergebnis unverändert (Test 3).
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Gerätecheck (macht der Owner nach dem Merge)

Debug-Build installieren, 24 h laufen lassen, dann ein Bundle erstellen:

- Im Crash-Puffer (`log/logcat.txt`) keine neuen `OutOfMemoryError`.
- `drive_daily_files` in `state/db_stats.json` wächst auf rund 30 Einträge.
- In den Breadcrumbs taucht „Drive-Sync wartet auf laufenden Zyklus“ auf, aber nie zwei Läufe, die
  sich überlappen.

Trag diesen Check als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein (Ergebnis-Spalte
leer).
