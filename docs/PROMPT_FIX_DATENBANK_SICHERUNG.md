# Prompt: Bugfix — Datenbanksicherung streamend statt im Speicher

**Priorität 1.** Seit dem 16.09.2026 gibt es auf dem Owner-Gerät **keine funktionierende
Datenbanksicherung**: 95 gescheiterte Versuche mit `OutOfMemoryError`. Jeder Versuch treibt
außerdem den Heap an die Grenze und trägt zu den Abstürzen bei. Belege:
[`BEFUNDE_P30_2026-09-23.md`](BEFUNDE_P30_2026-09-23.md), Abschnitt 2, A2.

**Owner-Entscheidung vom 23.09.2026:** streamend reparieren. Die Funktion wird nicht abgeschaltet
oder gestrichen.

Die Ursache ist analysiert. **Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas
änderst. Stimmt eine Angabe nicht mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c fix/datenbank-sicherung-streaming
  origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten. Beispiel:
  `fix(backup): Sicherungs-ZIP streamend in eine Datei schreiben`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`.
  - ktlint: keine neuen Befunde in den geänderten Dateien.
  - Nur bei Grün committen und pushen. Ist ein Test rot, den deine Änderung nicht berührt: mit
    Ausgabe im PR melden und den Owner fragen.
- **Nur handgeschriebene Fakes**, kein Mockito, kein MockK.
- **Keine Schemaänderung.** **Das Sicherungsformat bleibt gleich** (`SICHERUNG_FORMAT_VERSION`
  unverändert, gleiche ZIP-Einträge). Alte Sicherungen müssen weiter einspielbar sein.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.
- Parallel läuft `PROMPT_FIX_OOM_DRIVE_SYNC.md` in einem eigenen PR. Er berührt
  `DriveSyncCoordinator.kt`; halte deine Änderung dort auf `ladeDatenbankSicherungHoch()` und den
  Konstruktor-Parameter `datenbankSicherungQuelle` beschränkt, damit sich die PRs nicht in die
  Quere kommen.

---

## 1 · Die Ursache

Die Datenbank auf dem Owner-Gerät ist 491.978.752 Bytes groß. **Jeder** Sicherungsweg hält sie
komplett im Speicher (Heap-Grenze des Geräts: 402 MB):

| Stelle | Was passiert |
|---|---|
| `backup/SicherungManager.kt` `leseDatenbankNachCheckpoint()` (Zeile 186) | `File(...).readBytes()`: die ganze DB-Datei als `ByteArray` |
| `SicherungManager.baueSicherungsBytes()` (Zeile 68) | ZIP in einen `ByteArrayOutputStream`, ergibt ein zweites großes `ByteArray` |
| `SicherungManager.erstelleSicherung()` (Zeile 46) | lokale Sicherung (SAF) über `baueSicherungsBytes()` |
| `SicherungManager.spieleSicherungEin()` (Zeile 83) | Wiederherstellung: `input.readBytes()`, dann `zis.readBytes()` für den DB-Eintrag (Zeile 119) |
| `SicherungManager.schreibeDatenbankDatei()` (Zeile 202) | nimmt `ByteArray` |
| `drive/DriveDatenbankSicherung.kt` `hochladen()` / `herunterladen()` | `ByteArray` rein und raus; nutzt `dateiAnlegen`/`dateiAktualisieren`/`dateiHerunterladen` mit `ByteArray` |
| `drive/DriveSyncCoordinator.kt` `ladeDatenbankSicherungHoch()` (Zeile 527) | Quelle ist `suspend () -> ByteArray` aus `AppContainer.kt` (Zeile 276) |

Fehler meldet `ladeDatenbankSicherungHoch()` nur als **INFO-Breadcrumb**:

```
[INFO] DriveSync: Datenbank-Sicherung konnte nicht erstellt werden: Failed to allocate a 491298832 byte allocation …
```

---

## 2 · Auftrag

**Ziel:** Kein Sicherungs- oder Wiederherstellungsweg hält die Datenbank oder die ZIP als Ganzes im
Speicher. Puffer in Kilobyte-Größe (`copyTo`, Standardpuffer) sind in Ordnung.

### Schritt 1 — Sicherung in eine Datei bauen

- Neue Funktion `SicherungManager.baueSicherungsDatei(context, settings, ziel: File)`.
- Sie schreibt die ZIP per `ZipOutputStream(FileOutputStream(ziel).buffered())`:
  - Manifest und Einstellungen wie bisher (klein, dürfen `ByteArray` bleiben).
  - Die Datenbank **nach** dem bestehenden `PRAGMA wal_checkpoint(FULL)` per
    `FileInputStream(dbDatei).use { it.copyTo(zos) }`.
- Konsistenz wie bisher: Checkpoint, dann Kopie. Nicht verschlechtern. **`VACUUM INTO` gibt es auf
  Android 10 (SQLite 3.22) nicht**, also nicht verwenden.
- **Vorab den Speicherplatz prüfen:** freier Platz im Zielverzeichnis (`StatFs` bzw.
  `File.usableSpace`) mindestens DB-Größe × 1,1. Sonst abbrechen mit einer klaren Fehlermeldung
  (siehe Schritt 4).
- `baueSicherungsBytes()` und `leseDatenbankNachCheckpoint()` entfallen. Prüfe vorher alle
  Aufrufer, auch in Tests.

### Schritt 2 — Lokale Sicherung und Wiederherstellung streamend

- `erstelleSicherung(ziel: Uri)`: in eine temporäre Datei in `cacheDir` bauen, dann per `copyTo` in
  den SAF-`OutputStream` kopieren. Die temporäre Datei im `finally` löschen.
- `spieleSicherungEin(quelle: Uri)`: den SAF-`InputStream` direkt als `ZipInputStream` lesen.
  - Manifest und Einstellungen in den Speicher (klein).
  - Den DB-Eintrag **in eine temporäre Datei** streamen.
  - **Erst nach** erfolgreicher Manifest-Prüfung die laufende DB schließen und die Datei ersetzen.
    Reihenfolge der ZIP-Einträge nicht voraussetzen.
- `schreibeDatenbankDatei()` nimmt dann eine Quelldatei statt `ByteArray`. Verschieben per
  `renameTo`, falls möglich, sonst kopieren. Die bestehende Behandlung von `-wal`/`-shm` bleibt.
- `spieleSicherungBytesEin(zipBytes)` wird durch eine Variante mit `File` oder `InputStream`
  ersetzt. Alle Aufrufer anpassen.

### Schritt 3 — Drive: hochladen und herunterladen streamend

`DriveApiClient` / `GoogleDriveApiClient` bekommen zwei Methoden:

1. **Bestehende Datei per Resumable Upload aktualisieren.**
   - Drive-API: `PATCH https://www.googleapis.com/upload/drive/v3/files/{fileId}?uploadType=resumable`.
   - Die vorhandene `dateiHochladenResumable()` (Neuanlage per `POST`) als Vorlage nehmen.
   - Die gemeinsame Chunk-Logik möglichst **wiederverwenden statt kopieren**, z. B. Methode und URL
     als Parameter.
   - Ziel: Es bleibt **genau eine** Sicherungsdatei `laermprotokoll_datenbank.zip` in Drive, die
     alte wird erst durch einen erfolgreichen Upload ersetzt.
2. **Datei streamend herunterladen:** `dateiHerunterladenNach(fileId, ziel: File)`. Den
   Response-Body per `byteStream().copyTo(...)` in die Datei schreiben, nie `bytes()`.

Dann:
- `DriveDatenbankSicherung.hochladen(client, wurzel, datei: File)`: bei vorhandener Datei die neue
  Update-Methode, sonst `dateiHochladenResumable`.
- `herunterladen(...)` in eine Datei.
- `DriveSyncCoordinator.ladeDatenbankSicherungHoch()`:
  - Die Quelle wird `suspend (ziel: File) -> Unit` (baut die Datei).
  - Der Coordinator legt die temporäre Datei in `cacheDir` an und löscht sie im `finally`.
  - Die Drosselung über `datenbankSicherungLastAttemptAt` bleibt unverändert.
- **Fakes erweitern:** die Drive-Client-Fakes in `app/src/test` (Implementierungen von
  `DriveApiClient` suchen).

### Schritt 4 — Fehlschläge sichtbar machen

- Neuer Code `BACKUP_CREATE_FAILED` in `diagnose/DiagnosticCode.kt`, direkt neben
  `BACKUP_RESTORE_FAILED`, mit KDoc wie dort.
- `ladeDatenbankSicherungHoch()` meldet Fehlschläge (Bauen, Platzmangel, Upload) per
  `diagnosticsReporter.report(code = BACKUP_CREATE_FAILED, severity = WARN, …)`.
  - Nicht mehr nur als INFO-Breadcrumb.
  - Das Event muss den Grund enthalten: Ausnahme-Nachricht bzw. „zu wenig Speicherplatz: frei X,
    nötig Y“.
- `SettingsScreen` meldet bisher nur Fehler bei der **Wiederherstellung**
  (`BACKUP_RESTORE_FAILED`, Operationen `vonDriveWiederherstellen` und
  `lokaleWiederherstellung`); das bleibt so. Ein Fehlschlag der **lokalen Sicherung**
  (`erstelleSicherung`) wird bisher nirgends gemeldet. Melde ihn ebenfalls mit
  `BACKUP_CREATE_FAILED`, im selben Muster wie die beiden bestehenden Meldungen.

### Nicht Teil dieses Auftrags

- Drive-Sync-Nachholen und parallele Läufe: `PROMPT_FIX_OOM_DRIVE_SYNC.md`.
- Keine Verschlüsselung und kein neues Format.
- Keine Änderung am Intervall (30 min) oder am Schalter `datenbankSicherungDriveUpload`.

---

## 3 · Tests (JVM/Robolectric, handgeschriebene Fakes)

Vorhandene Tests zu `SicherungManager` und `DriveDatenbankSicherung` suchen
(`grep -rl "SicherungManager\|DriveDatenbankSicherung" app/src/test`) und anpassen, nicht löschen.

1. **Roundtrip:**
   - Echte Room-DB mit einigen Zeilen, `baueSicherungsDatei()` bauen.
   - Die ZIP hat die drei bekannten Einträge; der DB-Eintrag ist **byteweise** gleich der DB-Datei
     nach dem Checkpoint.
   - Einspielen über den neuen Streaming-Weg liefert dieselben Zeilen.
2. **Alte Sicherung bleibt einspielbar:** Eine ZIP im alten Format (so wie der alte Code sie baute)
   lässt sich mit dem neuen Code einspielen.
3. **Ungültige Sicherung:**
   - Fehlt das Manifest oder hat es eine falsche `formatVersion`, bleibt die **laufende Datenbank
     unangetastet**.
   - Die temporäre Datei ist danach weg.
4. **Drive-Upload:**
   - Das Fake zeichnet auf, dass bei vorhandener Datei die Update-per-Resumable-Methode mit einer
     `File` aufgerufen wurde, sonst `dateiHochladenResumable`.
   - Es gibt **keinen** Aufruf mit `ByteArray` für die Sicherung.
5. **Drive-Download** schreibt in eine Datei, der Inhalt ist gleich.
6. **Platzmangel:**
   - Ein Fake bzw. eine injizierte Prüfung meldet zu wenig Platz.
   - Ergebnis: Event `BACKUP_CREATE_FAILED` mit Grund, **kein** Upload, `datenbankSicherungLastAttemptAt`
     trotzdem gesetzt.
7. **Kein Voll-Einlesen mehr:** Im Produktionscode unter `backup/` und in
   `drive/DriveDatenbankSicherung.kt` kommen `readBytes()` und `ByteArrayOutputStream` auf dem
   DB-/ZIP-Pfad nicht mehr vor. Belege das im PR mit einem `grep` samt Ausgabe.

Tests, die eine Verhaltensänderung prüfen (4, 6), müssen ohne deine Änderung rot sein. Zeig das im
PR.

---

## 4 · Akzeptanzkriterien

- [ ] Sicherung (Drive und lokal) und Wiederherstellung (Drive und lokal) laufen streamend (Test 1–5, 7).
- [ ] Sicherungsformat unverändert, alte Sicherungen einspielbar (Test 2).
- [ ] Es bleibt eine Sicherungsdatei in Drive, die alte wird erst nach Erfolg ersetzt.
- [ ] Fehlschläge erscheinen als `BACKUP_CREATE_FAILED` (WARN) im Diagnose-Log und damit in jedem Bundle (Test 6).
- [ ] Temporäre Dateien werden in jedem Fall gelöscht.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Gerätecheck (macht der Owner nach dem Merge)

- In Drive liegt nach spätestens einem Sync-Zyklus ein aktuelles `BACKUP/laermprotokoll_datenbank.zip`
  (Änderungsdatum heute, Größe plausibel).
- Im nächsten Bundle stehen keine neuen „Datenbank-Sicherung konnte nicht erstellt werden“ und
  kein `OutOfMemoryError` zu den Sicherungszeitpunkten.
- **Wiederherstellung** auf einem Testgerät oder Emulator: Sicherung einspielen, die App startet
  mit den Daten neu.

Trag diese Checks als neue Zeilen in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein (Ergebnis-Spalte
leer).
