# Prompt: M11 — Fotodokumentation und Videobeweis

Auftrag des Owners, direkt eingeschoben wie schon M7b, M7c, M9 und M10. Er steht **nicht** im
`docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md` und widerspricht ihm nicht.

**Der Auftrag in den Worten des Owners:**

> 1. Foto-Doku für Bericht beim Starten eines Messvorgangs. Umfang soll konfigurierbar sein in
>    Einstellungen. Messaufbau, Kalibrierung.
> 2. Videobeweis starten während Aufzeichnung. Das soll die Kamera zur Videoaufnahme starten.
>    Idealerweise soll das genauso hochgeladen werden im Google Drive.

**Das Dokument ist in zwei Etappen geteilt, weil die beiden Punkte technisch nicht dasselbe
Risiko haben:**

- **Etappe A — Fotodokumentation.** Vollständig spezifizierbar, hardwarearm, baut auf
  vorhandener Infrastruktur (Room-Migration, PDF-Bericht, Drive-Upload) auf. **Kann sofort
  gebaut werden.**
- **Etappe B — Videobeweis.** Hatte ursprünglich zwei harte Vorbedingungen: einen möglichen
  Mikrofon-Konflikt mit dem laufenden Foreground Service (nur auf einem echten Gerät messbar)
  und einen Drive-Upload-Pfad, der große Dateien überhaupt verträgt — der heutige verträgt sie
  nicht. **Die erste ist mit Entscheidung E9 weggefallen:** Das Video wird ohne Tonspur
  aufgenommen und der Ton nachträglich einmultiplext (B.2a), es gibt also keinen zweiten
  Mikrofon-Zugriff und nichts zu messen. **Es bleibt der Upload-Pfad (B.6) — der ist echte
  Arbeit und keine Formsache.** Etappe B kann damit begonnen werden.

Etappe A und Etappe B sind **zwei getrennte PRs**, nicht einer.

---

## 0 · Bevor du eine Zeile Code schreibst

1. **`AGENTS.md` vollständig lesen.** Sie gilt unverändert. Insbesondere:
   - Neuer Branch von `main`, Namensschema `feature/m11a-fotodokumentation` bzw.
     `feature/m11b-videobeweis`. **Nie auf `main` committen oder pushen.**
   - Commit-Nachrichten auf Deutsch, kleine Commits je abgeschlossenem Teilschritt.
   - Code-Bezeichner englisch, UI-Texte deutsch, Doku deutsch.
   - `./gradlew assembleDebug` und `./gradlew test` müssen nach jedem Schritt grün sein, die
     Ausgabe kommt in den PR — nicht die Zusammenfassung der Ausgabe.
   - **`fallbackToDestructiveMigration()` ist verboten.** Jede Schemaänderung bekommt eine
     explizite `Migration` *und* einen Migrationstest.
   - **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.** Was du in dieser
     Umgebung nicht verifizieren konntest (kein Gerät, keine Kamera, kein Drive-Konto), schreibst
     du genau so in den PR.
2. **Nicht raten.** Dieses Dokument nennt zu jeder Behauptung über den Ist-Zustand die Datei und
   die Zeile. Wenn du beim Lesen feststellst, dass eine Angabe nicht mehr stimmt (der Code hat
   sich seit dem Schreiben dieses Prompts bewegt), **halte dich an den Code und melde die
   Abweichung im PR** — überschreib nicht stillschweigend die Absicht.
3. **Kein Vorgriff.** Etappe A baut kein Video, Etappe B baut keine Fotos nach. Keine
   „wenn ich schon mal hier bin"-Aufräumarbeiten.
4. **Offene Entscheidungen (Abschnitt 4) entscheidest du nicht selbst.** Du fragst den Owner.
   Wo dieses Dokument eine Empfehlung ausspricht, ist das eine Empfehlung, kein Beschluss.
   **Ausnahme: Abschnitt 4a.** Dort stehen die Punkte, die der Owner bereits entschieden hat
   (E1, E4 und E9) — die sind Vorgabe. **Lies Abschnitt 4a, bevor du mit Etappe A oder B
   anfängst**, er ändert an beiden Etappen etwas Wesentliches; E9 baut Etappe B sogar um.

---

## 1 · Ist-Zustand — überprüft, mit Fundstelle

Diese elf Befunde sind für den Auftrag entscheidend. Alle wurden im Code nachgesehen, nicht
angenommen.

### F-1 · Es gibt heute keinerlei Kamera-Infrastruktur

`app/src/main/AndroidManifest.xml` deklariert **keine** `android.permission.CAMERA`, **kein**
`FOREGROUND_SERVICE_CAMERA`, **kein** `<uses-feature android:name="android.hardware.camera">`.
`app/build.gradle.kts` enthält **keine** CameraX-Abhängigkeit und keine Bildbibliothek (kein
Coil, kein Glide). Foto und Video sind hier echte Neubauten, keine Erweiterungen.

### F-2 · Ein FileProvider existiert bereits und ist brauchbar

`AndroidManifest.xml`: Authority `com.example.lrmprotokoll.fileprovider`,
`app/src/main/res/xml/file_paths.xml` gibt `external-files-path` (`.`) und `cache-path` (`.`)
frei. Damit ist der Zielordner für ein `ACTION_IMAGE_CAPTURE`-Ausgabe-Uri bereits vorhanden —
**es ist keine Änderung an `file_paths.xml` nötig**, solange du unter
`context.getExternalFilesDir(null)` bleibst. Genau diesen Pfad benutzen `ReportManager`,
`MessreiheExport` und `PeriodenBerichtExport` schon.

### F-3 · Der Drive-Upload-Pfad lädt die **komplette Datei zweimal in den RAM**

`drive/DriveApiClient.kt` kennt nur `dateiAnlegen(name, ordnerId, inhalt: ByteArray, mimeType,
gzip)` und `dateiAktualisieren(fileId, inhalt: ByteArray, ...)` — die Schnittstelle nimmt ein
`ByteArray`, keinen Stream und keine `File`. `drive/GoogleDriveApiClient.kt:164–194` baut daraus
einen Multipart-Body und materialisiert ihn **noch einmal** komplett:

```kotlin
val vollstaendigerRumpf = okio.Buffer().also { multipart.writeTo(it) }.readByteArray()
... .url("$basisUrl/upload/drive/v3/files?uploadType=multipart")
```

Spitzenspeicher also ≈ 2 × Dateigröße. Für CSV-Tagesdateien und stündliche WAV-ZIPs ist das
tragbar. **Für ein Video ist es das nicht** — ein 3-minütiges 1080p-Video ist je nach Bitrate
150–400 MB, das ist ein sicherer `OutOfMemoryError`. Siehe Abschnitt B.6.
Für Fotos (herunterskaliertes JPEG, Zielgröße < 1 MB) ist der bestehende Pfad dagegen
**unverändert benutzbar** — Etappe A braucht hier nichts anzufassen.

### F-4 · Der Mikrofon-Konflikt — mit E9 umgangen, nicht mehr das zentrale Risiko

`AudioRecordingService` läuft als Foreground Service mit
`android:foregroundServiceType="microphone|connectedDevice"` und hält über `AudioRecord` das
Mikrofon. Eine Videoaufnahme **mit Tonspur** greift auf dieselbe Ressource zu. Was Android in
diesem Fall tut, hängt von Version, Hersteller und Audio-Policy ab und ist **nicht aus dem Code
ableitbar.**

Wichtige Differenzierung, die die Sache entschärfen kann: Bei
`settings.audioTriggerQuelle == "PCE_323"` (bzw. `"AUTO"` mit verbundenem Messgerät) kommt der
maßgebliche, kalibrierte Pegel vom PCE-323 über BLE, **nicht** vom Mikrofon
(`messreihe/MeterTriggerSource.kt:52`). In dieser Konstellation wiegt ein Mikrofon-Verlust
deutlich weniger als bei `"MIKROFON"`. Das ist ein Argument für eine differenzierte Behandlung,
kein Freibrief — auch im Messgerät-Betrieb schneidet der Service weiter Audio mit.

> **Nachtrag (Owner-Entscheidung E9, Abschnitt 4a): Dieser Konflikt wird umgangen, nicht
> gelöst.** Die Videoaufnahme greift gar nicht mehr aufs Mikrofon zu — CameraX nimmt **ohne
> Tonspur** auf, der Ton kommt aus dem ohnehin laufenden `AudioRecord` und wird nach dem Stopp
> einmultiplext (B.2a). Damit gibt es keine zweite Mikrofon-Nutzung, keine Messlücke und
> nichts zu messen. F-4 bleibt hier als Beschreibung der Ausgangslage stehen, ist aber für die
> Umsetzung nicht mehr das zentrale Risiko.

### F-5 · Es gibt zwei Kandidaten für „Starten eines Messvorgangs" — welcher gemeint ist, ist offen

- **Mikrofon-Überwachung:** `ui/MicrophoneControlCard.kt:59–72` startet
  `AudioRecordingService` mit `action = ACTION_START_AUDIO_MONITORING` und dem Extra
  `EXTRA_START_AUDIO_MONITORING`. `ui/LiveCockpitCard.kt:122–127` startet denselben Dienst mit
  demselben Extra, **setzt aber keine `action`** — die beiden Einstiegspunkte sind heute schon
  nicht deckungsgleich. Wenn du dich auf einen Startpunkt hängst, hängst du dich sonst nur an
  einen von zweien.
- **Messgerät-Session:** `ConnectionSupervisor.start` (aufgerufen u. a. aus
  `audio/AudioRecordingService.kt:208`) führt zu `MeasurementRecorder.start(device)`, das in
  `messreihe/MeasurementRecorder.kt:84` eine **`SessionEntity` anlegt** — das ist die
  fachliche Klammer „von wann bis wann wurde gemessen".

Fachlich ist die **`SessionEntity` der richtige Anker** für eine Fotodokumentation: sie hat eine
ID, einen Start, ein Ende, ein Gerät, und der Sessionbericht (`report/MessreiheExport.kt`) wird
genau daraus erzeugt. Ein reiner Mikrofonlauf erzeugt heute dagegen **keine** Session-Zeile.

→ **E1 ist entschieden: die Fotodokumentation gilt auch für den Mikrofonlauf.** Damit muss der
Mikrofonlauf eine Session bekommen — das ist eine Vorarbeit vor der eigentlichen Fotofunktion.
**Siehe Abschnitt 4a**, dort steht, wie und welche vier Nebenwirkungen zu prüfen sind.

### F-6 · Berichte sind handgezeichnete PdfDocument-Seiten

`report/MessreiheExport.kt:56–110` und `report/PeriodenBerichtExport.kt:42` erzeugen A4-Seiten
(`595 × 842` pt bei 72 dpi) mit `android.graphics.pdf.PdfDocument` und zeichnen jede Zeile
einzeln per `canvas.drawText(...)` an einer manuell hochgezählten `y`-Position. Es gibt keine
Layout-Engine und bewusst keine PDF-Bibliothek (KDoc in `MessreiheExport.kt:20–31`). Fotos
kommen folglich per `canvas.drawBitmap(...)` hinein, und ein **Seitenumbruch muss selbst
programmiert werden** — der heutige Code kennt nur `startPage(...)` einmal und lässt Text bei
Überlänge einfach über den unteren Rand hinauslaufen. Für einen Bildanhang ist das nicht
tragfähig.

### F-7 · Drive-Ablage ist ein flacher Ordner

`drive/DriveSyncCoordinator.kt:164` schreibt `laermprotokoll_<datum>.csv`, Zeile 89–126 lädt
stündliche WAV-ZIPs. Alles landet flach in einem einzigen Ordner (`settings.driveFolderId`).
Unterordner werden heute nicht angelegt — `ordnerAnlegen` existiert, wird aber nur für den
Wurzelordner benutzt. → **Entscheidung E5.**

### F-8 · Der Upload steht unter WLAN-Vorbehalt

`settings.driveWlanOnly` (Default **an**, `SettingsManager.kt:289`) setzt in
`drive/DriveSyncWorker.kt:92–101` `NetworkType.UNMETERED`. `settings.driveUploadWav` (Default
**an**, `SettingsManager.kt:300`) schaltet den WAV-Upload. **Jeder neue Upload muss sich unter
dieselbe WLAN-Beschränkung stellen** — ein Video über Mobilfunk hochzuladen, weil ein neuer
Codepfad die Einschränkung nicht kennt, wäre ein echter Schaden beim Nutzer.

### F-9 · Room steht auf Version 13

`data/AppDatabase.kt:286` (`version = 13`), Migrationen `MIGRATION_4_6` … `MIGRATION_12_13` in
`ALLE_MIGRATIONEN` (Zeile 274–277), exportierte Schemas `app/schemas/…/6.json … 13.json`.

> **Achtung:** PR #94 (KI-Umbau Etappe 3) hebt auf **14** an und ist zum Zeitpunkt dieses
> Prompts noch offen. **Prüfe vor dem Anlegen deiner Migration den tatsächlichen Stand von
> `main`** (`grep -n "version = " app/src/main/java/com/example/lrmprotokoll/data/AppDatabase.kt`)
> und baue auf der dort vorgefundenen Version auf — nicht auf der hier genannten.

Vorbild für Migration + Test: `MIGRATION_12_13` und
`app/src/test/java/com/example/lrmprotokoll/data/AppDatabaseV13MigrationTest.kt`.

### F-10 · Es gibt kein DI-Framework und keine Mock-Bibliothek

`AppContainer.kt:43` ist ein handgeschriebener Container mit `by lazy`-Feldern. Neue
Komponenten werden dort registriert. **Tests benutzen ausschließlich handgeschriebene Fakes** —
in diesem Repo gibt es weder Mockito noch MockK, und das bleibt so.

### F-12 · Ein zweiter, moderner Berichtsgenerator liegt als offener PR daneben

**PR #78 („Revisionssicherer Gesamtbericht (PDF) nach AVV Baulärm v10 mit TA-Lärm-Kennwerten &
SHA-256-Manifest", Draft, offen seit 25.08.)** legt ein komplettes zweites Berichtssystem an:
`report/gesamtbericht/` mit `GesamtberichtPdfGenerator`, `ChartRenderer` und `PdfCanvasExt`.
Nachgesehen in `PdfCanvasExt.kt` auf `refs/pull/78/head`: dort stehen bereits Seitenmaße,
Farbpalette, `drawHeader(..., pageNum)`, `drawFooter(..., pageNum, totalPages)`,
`drawParagraph`, `drawCard`, `drawTable` und `drawStatusPill` — also genau die Bausteine, die
der heutige `MessreiheExport` nicht hat (F-6), inklusive Mehrseitigkeit.

Derselbe PR ändert außerdem `MicrophoneControlCard.kt`, `LiveCockpitCard.kt`,
`MeasurementRecorder.kt` und `SessionDao.kt` — also **genau die Dateien, auf die sich F-5
stützt.**

**Was das für dich heißt:**

- **Prüf beim Start, ob PR #78 inzwischen gemergt ist.** Wenn ja, sind F-5 und F-6 in Teilen
  überholt: Der Fotoanhang gehört dann in den `GesamtberichtPdfGenerator` (der schon
  Seitenumbrüche, Kopf- und Fußzeilen beherrscht), **nicht** in einen selbstgebauten
  Seitenumbruch in `MessreiheExport` — und du kannst `PdfCanvasExt.drawCard`/`drawParagraph`
  für Bildunterschrift und Rahmen mitbenutzen, statt es neu zu schreiben.
- Wenn PR #78 noch offen ist: **bau nichts darauf auf** — ein offener Draft ist keine Grundlage.
  Halte dich an F-6, aber kapsle den Fotoanhang so, dass er später mit wenig Aufwand in den
  Gesamtbericht umziehen kann (eigene Funktion, die Canvas, Startposition und Fotoliste
  entgegennimmt — nicht Code, der mitten in `exportierePdf` verwoben ist).
- Das SHA-256-Manifest aus PR #78 zielt auf dieselbe Nachweisbarkeit wie die Foto-Prüfsumme in
  A.5. **Wenn #78 gemergt ist, prüf, ob die Fotos in dieses Manifest gehören**, statt eine
  zweite, konkurrierende Prüfsummen-Systematik daneben zu stellen. Das ist dann eine Frage an
  den Owner, keine Entscheidung von dir.

### F-11 · Diagnose-Infrastruktur ist vorhanden und wird erwartet

`diagnose/DiagnosticsReporter` bietet `breadcrumb(category, message, data: Map<String, Any?>,
level)` und `report(code, component, operation, severity, message)`. Der
`SupportBundleExporter` schreibt daraus `events.jsonl` / `breadcrumbs.jsonl`. **Neue
Fehlerpfade (Kamera abgebrochen, Datei fehlt, Upload gescheitert) gehören dort hinein** — nicht
in ein `Log.d`, das im Support-Bundle nicht auftaucht. Das war schon beim KI-Umbau Etappe 1 der
Befund, der die Verifikation zunächst unmöglich gemacht hat.

---

## 2 · Etappe A — Fotodokumentation

### A.0 Ziel

Beim Start eines Messvorgangs wird der Nutzer aufgefordert, den **Messaufbau** und die
**Kalibrierung** zu fotografieren. Die Fotos hängen an der Messung, erscheinen im PDF-Bericht
und werden nach Drive synchronisiert.

**Wozu das gut ist — und warum es die Genauigkeit der Umsetzung rechtfertigt:** Ein
Lärmprotokoll ist ein Beweismittel. Die häufigste Entkräftung eines privaten Messprotokolls ist
nicht „die Zahlen stimmen nicht", sondern „wir wissen nicht, wie und wo gemessen wurde". Ein
Foto vom aufgestellten Messgerät mit Datum und Uhrzeit beantwortet genau das. Deshalb sind
Zeitstempel und Zuordnung zur Session wichtiger als Bildqualität — und deshalb darf ein Foto
niemals nachträglich unbemerkt ausgetauschbar sein (siehe A.5).

### A.1 Datenmodell

Neue Tabelle `dokumentationsfotos`:

```kotlin
@Entity(tableName = "dokumentationsfotos")
data class DokumentationsFotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Zugehoerige Session - non-null, weil nach der Vorarbeit aus Abschnitt 4a (E1) auch
     *  ein Mikrofonlauf eine Session eroeffnet. Jeder Messvorgang hat damit genau einen Anker. */
    val sessionId: Long,
    /** Wert von [FotoKategorie], als String gespeichert — kein Room-TypeConverter nötig,
     *  gleiche Konvention wie ConnectionEventEntity.type. */
    val kategorie: String,
    /** Absoluter Pfad unter getExternalFilesDir(null)/fotos/. */
    val dateiPfad: String,
    /** Aufnahmezeitpunkt in Millisekunden (Systemuhr zum Zeitpunkt der Rückkehr aus der Kamera). */
    val aufgenommenAm: Long,
    /** Freitext des Nutzers, optional. */
    val notiz: String?,
    /** Drive-Datei-ID, sobald hochgeladen — null = noch nicht hochgeladen. */
    val driveFileId: String? = null,
    /** SHA-256 der Bilddatei zum Zeitpunkt der Aufnahme, hex, lowercase. */
    val pruefsumme: String? = null,
)
```

Kategorien als Kotlin-Enum, **nicht** als freier String in der UI:

```kotlin
enum class FotoKategorie(val anzeigename: String) {
    MESSAUFBAU("Messaufbau"),
    KALIBRIERUNG("Kalibrierung"),
    SONSTIGES("Sonstiges"),
}
```

`SONSTIGES` ist nicht vom Owner gefordert, aber die einzige Möglichkeit, ein spontan wichtiges
Foto (Baustellenschild, Uhrzeit auf dem Display, Fahrzeugkennzeichen) unterzubringen, ohne es
falsch zu etikettieren. **Wenn der Owner das nicht will, streich es** — dann aber ersatzlos, nicht
durch Umwidmung von `MESSAUFBAU`.

**DAO** `DokumentationsFotoDao` mit mindestens:
`insert(foto): Long`, `fuerSession(sessionId: Long): List<DokumentationsFotoEntity>`,
`fuerSessionFlow(sessionId: Long): Flow<List<…>>`, `byId(id: Long)`,
`nichtHochgeladene(): List<…>` (für den Drive-Sync), `setzeDriveFileId(id, fileId)`,
`loesche(id: Long)`.

**Migration:** neue Version = aktuelle Version + 1, `CREATE TABLE IF NOT EXISTS` mit exakt dem
SQL, das Room selbst erzeugen würde. **Vorgehen:** erst die Entity schreiben, dann
`./gradlew :app:kspDebugKotlin` laufen lassen, das erzeugte `app/schemas/<n>.json` öffnen und den
`createSql`-String **wörtlich** in die Migration übernehmen. Nicht von Hand formulieren — die
`identityHash`-Prüfung von Room ist byte-genau und ein handgeschriebenes `CREATE TABLE` weicht
fast immer in Kleinigkeiten ab.

**Migrationstest** nach dem Muster von `AppDatabaseV13MigrationTest`: alte Version anlegen,
mindestens eine Zeile in eine bestehende Tabelle schreiben (damit die Migration echte Daten
überlebt), migrieren, `validateMigration`, danach eine Zeile in die neue Tabelle schreiben und
zurücklesen.

> **Falle, die schon einmal zugeschlagen hat:** Beim `INSERT` in der alten Version müssen *alle*
> `NOT NULL`-Spalten ohne SQL-Default explizit gefüllt werden (bei `noise_records` z. B.
> `meterConnected`, `isQuietHour`, `favorite`), sonst schlägt der Test mit
> `SQLiteConstraintException` fehl.

### A.2 Einstellungen — „Umfang konfigurierbar"

Das ist die wörtliche Owner-Anforderung. Neuer Abschnitt in `ui/SettingsScreen.kt` als
`SettingsSectionCard` (Muster: die bestehenden Karten ab Zeile 371). Neue Werte in
`SettingsManager` (Muster: die Drive-Block ab Zeile 242, jeweils mit KDoc, das den Default
begründet):

| Einstellung | Typ | Default | Bedeutung |
|---|---|---|---|
| `fotoDokuAktiv` | `Boolean` | `false` | Schaltet die gesamte Funktion. Default aus: eine Kamera-Aufforderung bei jedem Start ist für den Bestandsnutzer eine unerwartete Verhaltensänderung. |
| `fotoDokuMessaufbau` | `String` | `"OPTIONAL"` | `AUS` / `OPTIONAL` / `PFLICHT` |
| `fotoDokuKalibrierung` | `String` | `"OPTIONAL"` | dito |
| `fotoDokuMaxProKategorie` | `Int` | `3` | Obergrenze, verhindert unbegrenztes Zumüllen von Speicher und Drive. |
| `fotoDokuDriveUpload` | `Boolean` | `true` | Eigener Schalter, analog zu `driveUploadWav` — Fotos können Dritte oder Wohnungsinneres zeigen, das ist dieselbe Datenschutzkategorie wie WAVs. |

**`PFLICHT` bedeutet ausdrücklich nicht, dass die Messung blockiert wird.** Es bedeutet: Der
Dialog erscheint, „Überspringen" ist beschriftet mit „Ohne Foto starten", und die Auslassung
wird protokolliert (Breadcrumb, siehe A.9). **Eine Messung darf nie an einer fehlenden
Fotodokumentation scheitern** — dieselbe Regel wie beim KI-Umbau: eine Nebenfunktion darf die
Kernfunktion nicht verhindern. Wenn der Owner echtes Blockieren will, ist das
**Entscheidung E2**.

Lite/Pro: Der Hauptschalter gehört in die Lite-Ansicht, `fotoDokuMaxProKategorie` und
`fotoDokuDriveUpload` hinter `if (isProMode)` (Muster: `SettingsScreen.kt:1126`).

### A.3 Aufnahme-Ablauf

1. Nutzer startet den Messvorgang — **beides zählt**: Messgerät-Session wie Mikrofonlauf
   (E1, entschieden; die Session für den Mikrofonlauf ist die Vorarbeit aus Abschnitt 4a).
2. `fotoDokuAktiv == false` → nichts passiert, Messung startet wie bisher. **Dieser Pfad muss
   bit-identisch zum heutigen Verhalten sein.**
3. Sonst: Die Messung startet **sofort und unverändert**, und *danach* erscheint ein
   Bottom Sheet (Muster: `ui/MarkNoiseEventBottomSheet.kt`) mit je einer Kachel pro aktivierter
   Kategorie: „Messaufbau fotografieren", „Kalibrierung fotografieren", darunter „Fertig".

   **Die Reihenfolge ist nicht verhandelbar:** erst messen, dann fotografieren. Ein Dialog vor
   dem Start würde bedeuten, dass der lauteste Moment — der, weswegen der Nutzer zum Handy
   greift — nicht aufgezeichnet wird, während er die Kamera bedient.
4. Tippen auf eine Kachel öffnet die Kamera (A.4). Rückkehr → Datei speichern, Zeile in
   `dokumentationsfotos` anlegen, Vorschaubild in der Kachel, Zähler `n/max`.
5. Optionales Notizfeld je Foto (ein Satz, z. B. „Messgerät 1,5 m über Boden, 3 m von Fassade").
6. „Fertig" schließt das Sheet. Das Sheet ist jederzeit über den Systemrücken schließbar.

### A.4 Kamera-Anbindung

**Empfehlung: `ActivityResultContracts.TakePicture()` mit einem `FileProvider`-Uri** — also die
System-Kamera-App, nicht CameraX.

Begründung: Für ein Beleg-Foto braucht niemand eine eigene Kameraoberfläche mit Fokus-, Blitz-
und Auflösungssteuerung. Die System-Kamera kann all das bereits, sie ist dem Nutzer vertraut,
und sie kostet **null neue Abhängigkeiten** — passend zum ausdrücklich minimalen
Abhängigkeitsstil dieses Projekts (siehe die KDoc-Begründungen in `DriveApiClient.kt` und
`MessreiheExport.kt`).

**Zwei Fallen, die du kennen musst:**

1. **Deklariere `android.permission.CAMERA` NICHT im Manifest.** `ACTION_IMAGE_CAPTURE` an eine
   fremde Kamera-App braucht die Berechtigung nicht. Sobald deine App sie aber im Manifest
   *deklariert*, verlangt Android, dass sie auch *gewährt* ist, bevor der Intent funktioniert —
   du handelst dir damit einen Berechtigungsdialog ein, den du sonst gar nicht bräuchtest.
2. **`intent.resolveActivity(packageManager)` liefert ab `targetSdk` 30 `null`**, wenn kein
   `<queries>`-Element im Manifest steht — auch wenn eine Kamera-App vorhanden ist. Prüfe die
   Verfügbarkeit deshalb **nicht** über `resolveActivity`, sondern fang den
   `ActivityNotFoundException` beim Start ab und zeig dann eine verständliche Meldung
   („Keine Kamera-App gefunden"). Das ist der Pfad, der auf Geräten ohne Kamera-App auch
   wirklich greift.

Wenn der Owner stattdessen eine In-App-Kamera will (etwa für ein eingeblendetes
Zeitstempel-Overlay), ist das **Entscheidung E3** — dann CameraX `ImageCapture`, `CAMERA`-
Berechtigung, eigener Vorschau-Screen; deutlich mehr Aufwand, und in Etappe A nicht vorgesehen.

### A.5 Speicherung, Skalierung, Nachweisbarkeit

- **Ablage:** `context.getExternalFilesDir(null)/fotos/`, Dateiname
  `foto_<sessionId>_<kategorie>_<yyyyMMdd_HHmmss>.jpg`. Ordner bei Bedarf mit `mkdirs()`
  anlegen. Kein `MediaStore`, keine Galerie — die Fotos sollen nicht in der Foto-App des
  Nutzers zwischen Urlaubsbildern liegen, und `getExternalFilesDir` ist bereits über
  `file_paths.xml` für den FileProvider freigegeben (F-2).
- **Skalierung:** Nach der Rückkehr aus der Kamera das Bild auf **max. 1600 px Kantenlänge**
  herunterrechnen und als JPEG mit Qualität 80 neu schreiben. Vollauflösende Handyfotos sind
  4–12 MB; bei 6 Fotos je Session wären das ~50 MB pro Messung in Speicher *und* Drive-Upload,
  für einen Beleg ohne jeden Nutzen. **Wichtig:** Zum Laden `BitmapFactory.Options.inSampleSize`
  benutzen (erst `inJustDecodeBounds = true` für die Maße, dann mit passendem `inSampleSize`
  dekodieren) — ein volles 12-MP-Bitmap direkt in den Speicher zu dekodieren ist auf schwachen
  Geräten selbst schon der OOM.
- **EXIF-Orientierung:** Viele Kameras schreiben das Bild in Sensor-Orientierung und vermerken
  die Drehung nur im EXIF-Tag. Nach dem Neuschreiben ist dieser Tag weg und das Foto liegt quer.
  Lies `ExifInterface.TAG_ORIENTATION` **vor** dem Skalieren und dreh das Bitmap entsprechend.
  Das ist der mit Abstand häufigste sichtbare Fehler bei genau dieser Funktion.
- **EXIF-Standort:** Falls die Kamera GPS-Tags gesetzt hat, **entferne sie** beim Neuschreiben.
  Die App fragt heute keine Standortberechtigung für eigene Zwecke ab (die
  `ACCESS_*_LOCATION`-Einträge im Manifest sind auf `maxSdkVersion="30"` beschränkt und dienen
  ausschließlich dem BLE-Scan auf Alt-Android). Ein Foto, das den Wohnort in die Drive-Cloud
  trägt, wäre eine stille Ausweitung, die niemand beauftragt hat. Falls der Owner den Standort
  im Beweisfoto ausdrücklich *will*, gehört das offen in die Einstellungen — **E6**.
- **Prüfsumme:** SHA-256 der fertigen Datei berechnen und in `pruefsumme` speichern. Das ist
  billig (`java.security.MessageDigest`, das Muster steht schon im `SupportBundleExporter` für
  `checksums.sha256`) und der einzige Weg, später zu zeigen, dass ein Foto seit der Aufnahme
  nicht ausgetauscht wurde. Für ein Beweismittel ist das der eigentliche Punkt.

### A.6 Einbindung in den Bericht

In `report/MessreiheExport.exportierePdf(...)` einen Abschnitt **„Fotodokumentation"** nach den
Ausfallbändern anhängen: je Foto ein auf max. 240 pt Breite eingepasstes Bild plus Bildunterschrift
`"<Kategorie> · <dd.MM.yyyy HH:mm>"` und, falls vorhanden, die Notiz.

**Der Seitenumbruch muss hier gebaut werden** (F-6): Der heutige Code legt genau eine Seite an
und lässt Überlänge unsichtbar auslaufen. Zieh die Hilfsfunktion `zeile(...)` und den
`y`-Zähler in einen kleinen Zustand heraus, der bei `y > 800f` `dokument.finishPage(seite)`
aufruft, eine neue Seite mit hochgezählter Seitenzahl startet und `y` zurücksetzt. **Die
bestehende Textausgabe muss sich dadurch nicht ändern**, sonst wird aus einer Ergänzung ein
Umbau.

**Fehlende Dateien dürfen den Bericht nicht sprengen.** Ein Foto kann gelöscht worden sein
(Nutzer hat aufgeräumt, App-Daten teilweise verloren). Dann zeichnest du an dieser Stelle den
Text `"[Foto nicht mehr verfügbar: <Dateiname>]"` und machst weiter. Ein Bericht, der wegen
eines fehlenden Bildes gar nicht erst entsteht, ist der schlimmere Fehler.

> **Vorher F-12 lesen.** Ist PR #78 zwischenzeitlich gemergt, existiert mit
> `GesamtberichtPdfGenerator` und `PdfCanvasExt` bereits ein mehrseitiger Berichtsgenerator mit
> Kopf-/Fußzeile und Textumbruch. Dann gehört der Fotoanhang dorthin, und der hier beschriebene
> selbstgebaute Seitenumbruch entfällt ersatzlos.

`PeriodenBerichtExport` bleibt in Etappe A **unverändert** — ein Zeitraumbericht über viele
Sessions mit allen Fotos wäre ein anderes Dokument. Falls gewünscht: **E7.**

### A.7 Drive-Upload

Der bestehende Pfad reicht (F-3): herunterskalierte JPEGs liegen weit unter 1 MB.

In `DriveSyncCoordinator.syncEinenZyklus()` einen Block analog zum WAV-Block (Zeile 89–126)
ergänzen:

- nur wenn `settings.fotoDokuDriveUpload`,
- `dokumentationsFotoDao.nichtHochgeladene()` holen,
- je Foto `driveApi.dateiAnlegen(name, ordnerId, bytes, "image/jpeg", gzip = false)`
  (**`gzip = false`** — JPEG ist bereits komprimiert, Gzip darüber kostet CPU und bringt nichts),
- Erfolg → `setzeDriveFileId(...)`. Das ist zugleich die Idempotenz-Sicherung: ein Foto mit
  gesetzter `driveFileId` wird nie erneut hochgeladen.
- **Vor** dem Anlegen `dateiSuchen(name, ordnerId)` — dieselbe Waisen-Absicherung, die der
  bestehende Code für CSV und WAV schon fährt (KDoc in `DriveApiClient.dateiSuchen`).
- Dateiname: `foto_<sessionId>_<kategorie>_<yyyyMMdd_HHmmss>.jpg`, also identisch zum lokalen
  Namen. Ablageort: **E5.**

Die WLAN-Beschränkung greift automatisch, weil der Upload im selben Worker läuft (F-8) —
**bau keinen eigenen Upload-Weg daneben**, der sie umgeht.

### A.8 UI zum Ansehen

- **Session-Detail** (`ui/ProtokollDetailScreen.kt`): Zeile „Fotos (n)" mit horizontaler
  Thumbnail-Reihe. Tippen öffnet eine Vollbildansicht mit Kategorie, Zeitpunkt, Notiz und
  „Löschen".
- Löschen entfernt **Datei und DB-Zeile**. Wenn `driveFileId` gesetzt ist, weise im
  Bestätigungsdialog darauf hin, dass die Kopie in Drive bestehen bleibt — die App löscht dort
  nichts (der `drive.file`-Scope erlaubt es zwar, aber ein stilles Fernlöschen ist etwas
  anderes als ein lokales Aufräumen, und der Nutzer erwartet es nicht).
- **Bildladen ohne neue Bibliothek:** `BitmapFactory` mit `inSampleSize` in einem
  `remember`+`LaunchedEffect`, nicht synchron im Composable. Kein Coil, kein Glide — siehe F-1
  und den Abhängigkeitsstil des Projekts.

### A.9 Robustheit und Diagnose

**Nichts an dieser Funktion darf eine Messung verhindern.** Dieselbe Garantie wie bei
`classifySafely()` im KI-Pfad (`audio/SoundClassifier.kt`). Konkret: Kamera nicht vorhanden,
Nutzer bricht ab, Speicher voll, Skalierung wirft, DB-Insert scheitert — jeder dieser Fälle
endet in einer Nutzermeldung und einem Diagnoseeintrag, **nie** in einem geworfenen Fehler
Richtung Aufnahmepfad.

Breadcrumbs über `container.diagnosticsReporter` (F-11), Kategorie `"FotoDoku"`:

- `"Fotodialog angezeigt"` mit `data = mapOf("sessionId" to …, "kategorien" to …)`
- `"Foto aufgenommen"` mit `data = mapOf("kategorie" to …, "groesseBytes" to …, "breite" to …, "hoehe" to …)`
- `"Foto uebersprungen"` mit `data = mapOf("kategorie" to …, "modus" to "PFLICHT"|"OPTIONAL")`
- `"Foto-Upload"` mit `data = mapOf("fotoId" to …, "erfolg" to …, "fehler" to …)`

Bei echten Fehlern zusätzlich `report(...)`. Prüf, ob ein passender `DiagnosticCode` existiert;
wenn nicht, leg einen an (`FOTO_CAPTURE_FAILED`, `FOTO_UPLOAD_FAILED`) — und **halte dich an die
vorhandene Namenskonvention in `diagnose/DiagnosticCode.kt`**, statt eine neue zu erfinden.

> **`null`-Werte in `data` sind selbst eine Aussage** und werden vom `SupportBundleExporter`
> seit dem KI-Umbau Etappe 1 als JSON-`null` geschrieben, nicht mehr verschluckt. Du darfst
> also `"fehler" to null` schreiben; das erscheint korrekt im Bundle.

### A.10 Tests

Reine JVM-/Robolectric-Tests, handgeschriebene Fakes (F-10):

1. **Migrationstest** für die neue DB-Version (Pflicht, `AGENTS.md` §5).
2. **DAO-Test**: Einfügen, Lesen je Session, `nichtHochgeladene()` liefert nur Zeilen ohne
   `driveFileId`, `setzeDriveFileId` wirkt.
3. **Skalierungstest**: Ein synthetisches Bitmap 4000×3000 → Ergebnis hat max. Kantenlänge 1600
   und behält das Seitenverhältnis (Toleranz ±1 px durch Rundung). Ein 800×600-Bild wird
   **nicht** vergrößert.
4. **EXIF-Rotation**: Bild mit `ORIENTATION_ROTATE_90` → Breite und Höhe sind im Ergebnis
   getauscht.
5. **Drive-Upload**: Fake-`DriveApiClient` (Muster:
   `app/src/test/java/com/example/lrmprotokoll/drive/DriveWavUploadAndCsvTest.kt`) — Foto wird
   hochgeladen, `driveFileId` gesetzt, ein zweiter Zyklus lädt es **nicht** erneut hoch.
   Gegenprobe: `fotoDokuDriveUpload = false` → kein Upload.
6. **PDF-Bericht**: Bericht mit drei Fotos erzeugt eine Datei > 0 Bytes und wirft nicht; Bericht
   mit einem Foto, dessen Datei fehlt, **wirft ebenfalls nicht** und erzeugt trotzdem eine
   Datei. (Der PDF-Inhalt selbst wird nicht geparst — kein PDF-Parser im Projekt, und das ist
   kein Grund, einen einzuführen.)
7. **Robustheitstest**: Ein DAO, dessen `insert` wirft, führt nicht dazu, dass der Aufrufer eine
   Exception sieht.

### A.11 Akzeptanzkriterien Etappe A

- [ ] `./gradlew assembleDebug` grün, Ausgabe im PR.
- [ ] `./gradlew test` grün, Ausgabe im PR, **alle** bestehenden Migrationstests weiterhin grün.
- [ ] Neue Migration + Migrationstest vorhanden; kein `fallbackToDestructiveMigration()`.
- [ ] `fotoDokuAktiv = false` (Default) verändert das bisherige Verhalten an keiner Stelle.
- [ ] Umfang ist in den Einstellungen konfigurierbar: je Kategorie AUS/OPTIONAL/PFLICHT plus
      Obergrenze — die wörtliche Owner-Anforderung.
- [ ] **Der Mikrofonlauf eröffnet eine Session** (E1, Abschnitt 4a), in einem eigenen Commit vor
      der Fotofunktion; die vier dort genannten Nebenwirkungen sind einzeln geprüft und im PR
      beantwortet.
- [ ] Fotos erscheinen im Session-PDF; ein fehlendes Foto erzeugt einen Platzhalter statt eines
      Absturzes.
- [ ] Fotos landen in Drive, genau einmal, nur unter der eingestellten WLAN-Bedingung.
- [ ] Kein Kamera-, Datei- oder Upload-Fehler kann eine laufende Messung beenden oder verhindern.
- [ ] Kein neuer Bibliotheks-Dependency (weder Kamera- noch Bild- noch PDF-Bibliothek).
- [ ] Im PR steht ausdrücklich, was **nicht** verifiziert werden konnte (Kamera-Intent und
      EXIF-Verhalten sind ohne echtes Gerät nicht prüfbar).

---

## 3 · Etappe B — Videobeweis

> **Der Aufbau dieser Etappe hat sich mit Owner-Entscheidung E9 geändert.** Früher begann sie
> mit einer Gerätemessung (B.1), weil unklar war, ob Videoaufnahme und Pegelmessung sich das
> Mikrofon teilen können. Diese Frage stellt sich nicht mehr: Das Video wird **ohne Tonspur**
> aufgenommen und der Ton nachträglich aus dem laufenden `AudioRecord` einmultiplext (B.2a).
> **B.1 entfällt damit als Vorbedingung** — lies trotzdem B.1, dort steht, was statt der
> Messung nachzuweisen ist.
>
> Was unverändert gilt: Die lückenlose Pegelaufzeichnung ist die Kernaufgabe. Jede Zeile dieser
> Etappe hat sich daran zu messen.

### B.0 Ziel

Während einer laufenden Aufzeichnung startet der Nutzer per Knopfdruck eine Videoaufnahme als
Beweismittel. Das Video wird der Session zugeordnet und nach Drive hochgeladen.

**E4 ist entschieden: mit Tonspur** (Abschnitt 4a). **E9 legt fest, wie der Ton dorthin
kommt: V4** — Video ohne Tonspur aufnehmen, den Ton aus dem laufenden `AudioRecord` mitschreiben
und nach dem Stopp einmultiplexen. V1, V2 und V3 entfallen alle; die Kamera-Frage aus B.2 ist
zugunsten von CameraX beantwortet.

### B.1 (entfällt) — was statt der Gerätemessung nachzuweisen ist

**Diese Messung ist mit E9 hinfällig.** Sie sollte klären, ob eine Videoaufnahme *mit* Tonspur
neben dem laufenden `AudioRecord` koexistieren kann. Da das Video keine Tonspur mehr aufnimmt,
öffnet die Kamera das Mikrofon nicht, und es gibt keinen Konflikt zu messen.

Die Varianten V1–V3 sind damit alle vom Tisch. Sie bleiben nur hier stehen, damit
nachvollziehbar ist, was abgewogen wurde:

| Variante | Beschreibung | Status |
|---|---|---|
| ~~**V1**~~ | Video ohne Tonspur, fertig | Verworfen (E4): Ein Beweisvideo ohne Ton ist deutlich weniger wert. |
| ~~**V2**~~ | Mikrofon-Monitoring für die Dauer der Videoaufnahme pausieren, Video mit Ton | Verworfen (E9): kauft den Ton mit einer Lücke in der Messreihe — ausgerechnet im lautesten Moment. |
| ~~**V3**~~ | Beides parallel, falls die Geräte es hergeben | Verworfen (E9): wäre der Idealfall gewesen, hängt aber an Audio-Policy, Hersteller und Android-Version. V4 braucht die Zusage nicht. |
| **V4** | **Video ohne Tonspur + parallel mitgeschriebener PCM-Ton + Muxen nach dem Stopp** | **Zu bauen.** Kein zweiter Mikrofon-Zugriff, keine Messlücke, kein Geräterisiko. |

**Was du stattdessen nachweisen musst — und zwar im PR, nicht als Annahme:**

1. **Die Kamera öffnet das Mikrofon nicht.** In CameraX ist Audio opt-in: Nur ein Aufruf von
   `PendingRecording.withAudioEnabled()` schaltet die Tonspur ein. Ohne diesen Aufruf verlangt
   die Aufnahme nicht einmal `RECORD_AUDIO`. **Lass den Aufruf weg und schreibe an die Stelle
   einen Kommentar, warum** — ein späterer Beitrag, der ihn „vergessen" wieder ergänzt, holt
   den Mikrofon-Konflikt zurück.
2. **Die Pegelmessung läuft während der Videoaufnahme durch.** Nachweisbar ohne Kamera: ein
   Test, der belegt, dass der Aufnahme-Loop in `AudioRecordingService` beim Start und Stopp
   einer Videoaufnahme weder angehalten noch neu gestartet wird. Zusätzlich am Gerät: die
   Messreihe der Session darf im Videozeitraum keine Lücke haben.
3. **Ton und Bild passen zusammen** (B.2a, Punkt „Synchronisation").

Ein Gerätetest bleibt trotzdem sinnvoll — aber als normale Abnahme am Ende, nicht als
Vorbedingung am Anfang. Trag das Ergebnis mit Gerätemodell und Android-Version in
`docs/CHECKLISTE_GERAETETEST.md` ein.

### B.2 Kamera-Anbindung

Hier ist die Abwägung anders als bei den Fotos.

- **`ActivityResultContracts.CaptureVideo()`** (System-Kamera): keine Abhängigkeit, kein
  `CAMERA`-Recht, kein Foreground Service. **Aber:** Der Nutzer muss die Aufnahme in der
  fremden Kamera-App selbst beenden, du kannst Dauer, Auflösung und Tonspur **nicht** steuern
  (`EXTRA_DURATION_LIMIT` und `EXTRA_VIDEO_QUALITY` sind Empfehlungen, die viele Kamera-Apps
  ignorieren) — und **eine Tonspur abzuschalten ist über den Intent gar nicht möglich.** Damit
  ist auf diesem Weg weder eine verlässliche Maximaldauer noch ein kontrolliertes Tonverhalten
  baubar.
- **CameraX `VideoCapture` + `Recorder`** (`androidx.camera:camera-video`): volle Kontrolle über
  Tonspur, Qualität und Maximaldauer. Preis: vier neue Abhängigkeiten (`camera-core`,
  `camera-camera2`, `camera-lifecycle`, `camera-video`), `CAMERA`-Berechtigung, eigener
  Vorschau-Screen und — auf Android 14+ — ein Foreground Service vom Typ `camera`, wenn die
  Aufnahme weiterlaufen soll, während die App im Hintergrund ist.

**Entschieden: CameraX.** Mit den Owner-Entscheidungen „mit Ton" (E4) und V4 (E9) ist die
System-Kamera keine tragfähige Option mehr: Sie nimmt immer mit Ton auf — genau das, was V4
verhindern muss —, und lässt weder Maximaldauer noch Auflösung zuverlässig steuern. Das ist ein
bewusster Bruch mit dem minimalen Abhängigkeitsstil des Projekts — **benenne ihn im PR
ausdrücklich**, statt ihn nebenbei einzuführen.

**Konkret für V4:**

```kotlin
// KEIN withAudioEnabled(): Die Kamera darf das Mikrofon nicht anfassen. Der Ton kommt aus
// dem laufenden AudioRecord des AudioRecordingService und wird nach dem Stopp einmultiplext
// (B.2a). Wer diesen Aufruf ergaenzt, holt den Mikrofon-Konflikt aus F-4 zurueck und reisst
// waehrend der Videoaufnahme ein Loch in die Pegelmessung.
val aufnahme = videoCapture.output
    .prepareRecording(context, FileOutputOptions.Builder(stummeDatei).build())
    .start(ContextCompat.getMainExecutor(context)) { ereignis -> /* ... */ }
```

Ohne `withAudioEnabled()` verlangt die Aufnahme nicht einmal `RECORD_AUDIO` — die App hat das
Recht zwar ohnehin, aber die Aufnahme benutzt es nicht.

### B.2a Tonspur nachträglich einmuxen — der Kern von V4

Das ist der Abschnitt, der V4 von V1 unterscheidet, und der einzige wirklich neue Baustein
dieser Etappe. Drei Schritte, alle offline nach dem Stopp der Aufnahme.

#### Schritt 1 · Ton mitschreiben, während das Video läuft

**Wichtig, sonst baust du auf einer falschen Annahme:** Es gibt **keine durchlaufende
WAV-Aufzeichnung**, aus der man den Videozeitraum ausschneiden könnte.
`AudioRecordingService.activeWavRecorder` ist nur während einer *Ereignisaufnahme* gesetzt
(`audio/AudioRecordingService.kt:911` und `starteWavAufnahme`), also für
`settings.recordDurationSeconds` je Trigger. Ein Video von drei Minuten würde davon höchstens
Bruchstücke abbekommen.

Der Ton für das Video braucht deshalb eine **eigene Senke** in derselben Schleife:

- Ein zweiter, paralleler Schreiber neben `activeWavRecorder`, aktiv genau für die Dauer der
  Videoaufnahme. `ActiveWavRecorder` ist die passende Vorlage — dieselbe `writeChunk`-Mechanik,
  dieselben `writeWavHeader`/`updateWavHeader`-Helfer.
- Er hängt sich an dieselbe Stelle wie der bestehende Schreiber
  (`activeWavRecorder?.let { rec -> rec.writeChunk(pcmBytes, pcmLen) }`) — **eine Zeile daneben,
  nicht statt dessen.** Ereignis-WAVs müssen unverändert weiterlaufen.
- Format ist damit automatisch das der laufenden Messung: 16 Bit PCM, Mono,
  `audioRecord.sampleRate`. **Lies die Rate vom `AudioRecord`, nicht aus den Einstellungen** —
  die ausgehandelte Rate kann abweichen (`aktiveAbtastrate`, siehe `waehleAufnahmerate()`).
  Eine falsch angenommene Rate ergibt einen Ton in falscher Tonhöhe und falscher Länge.
- Merke dir den Wandzeituhr-Zeitstempel des ersten geschriebenen Blocks. Den brauchst du in
  Schritt 3.

#### Schritt 2 · PCM nach AAC encodieren

`MediaMuxer` nimmt **keine** Rohdaten — es schreibt nur bereits encodierte Samples. Der PCM-Puffer
muss also durch einen `MediaCodec`-AAC-Encoder:

- `MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, abtastrate, 1)`, Profil
  `AACObjectLC`, Bitrate 128 kbit/s (für Sprache und Baulärm mehr als ausreichend).
- Blockweise füttern, nie die ganze Datei in den Speicher — dieselbe Disziplin wie beim
  resumable Upload in B.6. Drei Minuten Mono-PCM bei 44,1 kHz sind ~16 MB; das ginge noch, aber
  bei einer erhöhten Maximaldauer nicht mehr.
- Presentation-Timestamps fortlaufend aus der Sample-Position berechnen
  (`positionInSamples * 1_000_000L / abtastrate`), nicht aus `System.nanoTime()`. Sonst
  wandert der Ton mit jeder Verzögerung im Encoder.

#### Schritt 3 · Videospur und Tonspur zu einer MP4 zusammenführen

- `MediaExtractor` auf die stumme MP4, die Videospur auswählen, Sample für Sample mit
  `readSampleData` lesen.
- `MediaMuxer` auf die Zieldatei, zwei Spuren anlegen (Videoformat unverändert aus dem
  Extractor übernehmen, Audioformat aus dem Encoder-Output), Samples mit
  `writeSampleData` schreiben.
- **Synchronisation:** `offsetUs = (videoStartMs − tonStartMs) * 1000`. Ist der Offset positiv,
  begann der Ton früher — dann die ersten `offsetUs` des Tons überspringen. Ist er negativ,
  begann der Ton später — dann vorne Stille (Null-Samples) einfügen, statt den Ton
  vorzuziehen. **Rate nicht.** Ein Beweisvideo, in dem der Knall eine Sekunde neben dem Bild
  liegt, ist als Beweis wertlos.
- Nach Erfolg die stumme Zwischendatei und die PCM-Datei löschen. Bei Fehler **behalte beide**
  und schreib einen Diagnoseeintrag: Ein stummes Video plus separate Tondatei ist immer noch
  ein Beweismittel, ein gelöschtes Zwischenergebnis ist keines.

#### Wo das läuft

Nicht auf dem Main-Thread und nicht im UI-Lebenszyklus. Ein `CoroutineWorker` (WorkManager ist
schon Abhängigkeit) ist der passende Ort: Er überlebt das Verlassen des Screens, kann bei
Prozess-Tod erneut laufen, und der bestehende Upload kann als Folgeschritt daran hängen. Solange
das Muxen nicht durch ist, gilt das Video als **nicht hochladbar** — sonst landet die stumme
Fassung in Drive.

#### Ehrliche Grenzen — schreib sie in den PR

- **Uhren-Drift.** Kamera- und Audio-Pfad takten unabhängig. Über 30–180 Sekunden ist die
  Abweichung praktisch nicht wahrnehmbar; über zehn Minuten kann sie es werden. Das ist ein
  weiteres Argument für die harte Maximaldauer aus B.5 — sie ist hier nicht nur eine
  Speichergrenze.
- **Kein Ton, wenn das Mikrofon nicht läuft.** Ist die Überwachung ohne Mikrofon aktiv (reiner
  PCE-323-Betrieb ohne Audioaufnahme), gibt es nichts zu muxen. Dann bleibt das Video stumm und
  `hatTonspur = false` — mit sichtbarem Hinweis **vor** dem Start, nicht als Überraschung
  hinterher. Genau dafür existiert das Feld (B.4).

### B.3 Manifest und Foreground Service

Bei CameraX zusätzlich nötig:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.any" android:required="false" />
```

`required="false"` ist wichtig: Sonst verschwindet die App im Play Store für jedes Gerät ohne
Kamera, obwohl sie dort ihre Kernaufgabe voll erfüllen würde.

Wenn die Aufnahme im Hintergrund weiterlaufen soll:
`FOREGROUND_SERVICE_CAMERA` und `foregroundServiceType="…|camera"` an
`AudioRecordingService` — **oder** ein eigener Service. **Beachte die Android-14-Regel:** Ein
Foreground Service vom Typ `camera` darf **nicht aus dem Hintergrund gestartet** werden. Die
Videoaufnahme muss also durch eine sichtbare Nutzeraktion im Vordergrund beginnen. Eine
automatische Videoaufnahme bei Schwellenüberschreitung ist damit **nicht** ohne Weiteres
baubar — und ist auch nicht beauftragt („Videobeweis **starten** während Aufzeichnung" ist eine
Nutzeraktion). Bau sie nicht spekulativ dazu.

**Empfehlung:** Vorschau-Screen im Vordergrund, harte Maximaldauer (Default 3 Minuten,
einstellbar), kein Hintergrundbetrieb in der ersten Fassung. Das umgeht die
Android-14-Einschränkung vollständig und ist für einen Beweisclip ausreichend.

### B.4 Datenmodell

Analog zu A.1, Tabelle `beweisvideos`:

```kotlin
@Entity(tableName = "beweisvideos")
data class BeweisVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val dateiPfad: String,
    val gestartetAm: Long,
    val dauerMs: Long,
    val hatTonspur: Boolean,
    val groesseBytes: Long,
    val notiz: String?,
    /** Solange false, ist nur die stumme Fassung fertig - siehe B.2a. Ein Video ohne
     * abgeschlossenen Mux-Lauf wird NICHT hochgeladen. */
    val tonGemuxt: Boolean = false,
    val driveFileId: String? = null,
    /** Fortschritt einer unterbrochenen resumable-Übertragung, siehe B.6. */
    val uploadSessionUri: String? = null,
    val hochgeladeneBytes: Long = 0,
)
```

`hatTonspur` ist kein Beiwerk: Wenn das Video später als Beleg dient, muss aus dem Datensatz
hervorgehen, ob Stille im Video „es war leise" oder „es wurde bewusst ohne Ton aufgezeichnet"
bedeutet. Das ist der Unterschied zwischen einem Beweis und einem Missverständnis. Mit V4 kommt
der zweite Fall real vor — nämlich immer dann, wenn das Mikrofon nicht mitlief (B.2a).

`tonGemuxt` trennt „stumme Zwischenfassung" von „fertig": Zwischen dem Stopp der Aufnahme und
dem Ende des Mux-Laufs existiert die Datei bereits, ist aber noch nicht das, was hochgeladen
oder abgespielt werden soll.

Migration und Migrationstest wie in A.1 — **auf der zum Zeitpunkt der Umsetzung aktuellen
DB-Version aufsetzen** (F-9), nicht auf der in Etappe A verwendeten.

### B.5 Speicherbudget und Aufbewahrung

Ein 1080p-Video kostet je nach Bitrate 30–130 MB pro Minute. Das ist eine andere Größenordnung
als alles, was die App bisher schreibt.

- **Harte Maximaldauer**, Default 3 Minuten, in den Einstellungen änderbar. Beim Erreichen wird
  automatisch gestoppt und der Nutzer informiert.
- **Auflösungsvorwahl** (720p Default statt 1080p): Für einen Beweisclip ist 720p ausreichend
  und halbiert alles — Speicher, Uploadzeit, Mobilfunkvolumen.
- **Freien Speicher vor dem Start prüfen** (`StatFs` auf `getExternalFilesDir`). Unter 500 MB
  frei: Aufnahme nicht starten, klare Meldung. Ein mitten im Beweis abbrechendes Video ist
  schlimmer als eines, das gar nicht erst beginnt.
- **Aufbewahrung:** Videos gehören in den bestehenden `RetentionCoordinator`-Gedanken (Plan
  13.2: 90 Tage). Ob Videos derselben Frist unterliegen oder einer kürzeren, ist **E8** — sie
  sind das mit Abstand größte Datenvolumen der App.

### B.6 Drive-Upload — hier ist echte Arbeit nötig

**Der bestehende Pfad ist für Videos unbrauchbar** (F-3): `dateiAnlegen` nimmt ein `ByteArray`,
und `GoogleDriveApiClient` materialisiert den Multipart-Body ein zweites Mal. Ein 200-MB-Video
bedeutet ~400 MB Spitzenspeicher — auf Android ein sicherer `OutOfMemoryError`, kein
Randfall.

**Zu bauen: ein resumable-Upload-Pfad**, additiv neben dem bestehenden. Neue Methode in
`DriveApiClient`:

```kotlin
/**
 * Laedt [datei] per resumable Upload hoch (Drive v3, uploadType=resumable). Anders als
 * [dateiAnlegen] wird die Datei nie vollstaendig in den Speicher geladen - notwendig fuer
 * Videos, die ein Vielfaches des verfuegbaren Heaps gross sein koennen.
 *
 * [fortsetzenAb] erlaubt die Wiederaufnahme einer abgebrochenen Uebertragung; [fortschritt]
 * meldet die bisher bestaetigten Bytes.
 */
suspend fun dateiHochladenResumable(
    name: String,
    ordnerId: String,
    datei: java.io.File,
    mimeType: String,
    fortsetzenAb: String? = null,
    fortschritt: (gesendet: Long, gesamt: Long) -> Unit = { _, _ -> },
): Result<String>
```

Ablauf nach Drive-v3-Spezifikation:

1. `POST /upload/drive/v3/files?uploadType=resumable` mit den Metadaten als JSON-Body
   (`name`, `parents`). Antwort: **`Location`-Header = Session-URI.** Diesen URI in
   `uploadSessionUri` **persistieren**, bevor der erste Datenblock rausgeht — sonst beginnt ein
   Prozess-Neustart mitten im Upload wieder bei null.
2. Blockweise `PUT` an den Session-URI mit
   `Content-Range: bytes <von>-<bis>/<gesamt>`. **Blockgröße muss ein Vielfaches von 256 KiB
   sein**, empfohlen 8 MiB. Datei per `RequestBody` aus einem `source()` streamen — **nie**
   `readBytes()`.
3. **`308 Resume Incomplete`** ist der Normalfall zwischen den Blöcken, kein Fehler. Der
   `Range`-Antwortheader nennt die tatsächlich bestätigten Bytes — **richte dich danach**, nicht
   nach dem, was du gesendet zu haben glaubst. `200`/`201` beendet den Upload und liefert die
   Datei-ID.
4. Bei Abbruch: `hochgeladeneBytes` speichern und beim nächsten Zyklus mit
   `Content-Range: bytes */<gesamt>` den Serverstand abfragen, dann ab dort weitermachen.
5. Ein Session-URI ist **etwa eine Woche** gültig. Bei `404` auf den Session-URI:
   `uploadSessionUri` verwerfen und von vorn beginnen — **nicht** endlos wiederholen.

**Einschränkungen, die eingehalten werden müssen:**

- Nur unter `settings.driveWlanOnly` (F-8) — ein 200-MB-Upload über Mobilfunk ist ein realer
  Schaden beim Nutzer, nicht bloß ein Ärgernis.
- Eigener Einstellungsschalter `videoDriveUpload`, Default **aus**. Ein Video kann Dritte,
  Kennzeichen und Wohnungsinneres zeigen — das ist die datenschutzsensibelste Datenart der
  gesamten App, und ein Default-an wäre hier falsch. (Abweichung von `driveUploadWav`, dessen
  Default der Owner bewusst auf an gesetzt hat; begründet siehe `SettingsManager.kt:294–300`.)
- Der Upload läuft im bestehenden `DriveSyncWorker`, nicht in einem eigenen Mechanismus.
  **Beachte:** WorkManager gibt einem Worker nur begrenzt Laufzeit (10 Minuten). Ein großes
  Video überlebt das nicht in einem Durchgang — genau dafür ist die Wiederaufnahme aus Punkt 4
  da, sie ist kein optionaler Komfort.

**Testbar mit `MockWebServer`** — genau dafür ist die dünne HTTP-Schicht laut KDoc in
`DriveApiClient.kt` gebaut, und `GoogleDriveApiClientTest` macht es bereits so. Prüfe
mindestens: Session-URI wird aus dem `Location`-Header gelesen · Blöcke haben korrekte
`Content-Range`-Header · `308` führt zum nächsten Block statt zum Abbruch · ein abgebrochener
Upload nimmt an der vom Server gemeldeten Position wieder auf · `404` auf den Session-URI führt
zu einem Neustart, nicht zu einer Endlosschleife.

### B.7 UI

- Knopf **„Videobeweis"** in `ui/LiveCockpitCard.kt`, sichtbar **nur bei laufender
  Aufzeichnung** — der Owner-Auftrag lautet „während Aufzeichnung".
- Vorschau-Screen mit Aufnahmeknopf, laufender Dauer, Restdauer bis zum Maximum und
  Stopp-Knopf.
- **Keine Pausier-Warnung** — mit V4 pausiert nichts. Beleg im PR, dass die Messung während der
  Videoaufnahme durchläuft (B.1, Punkt 2), statt es anzunehmen.
- **Läuft das Mikrofon nicht**, muss der Hinweis „Dieses Video wird ohne Ton aufgezeichnet"
  **vor** dem Start kommen (B.2a). Nach der Aufnahme ist es zu spät.
- Nach dem Stopp einen sichtbaren Zustand „Ton wird hinzugefügt…", bis der Mux-Lauf durch ist
  (`tonGemuxt`). Ein Video, das kurz stumm ist und dann plötzlich Ton hat, wirkt sonst wie ein
  Fehler.
- Videos im Session-Detail auflisten, Abspielen über einen `ACTION_VIEW`-Intent mit
  FileProvider-Uri (kein eingebauter Player — dafür gibt es keinen Grund und keine
  Abhängigkeit).

### B.8 Robustheit

Dieselbe Garantie wie in A.9, mit einem Zusatz, der hier schwerer wiegt: **Ein Kamerafehler darf
den `AudioRecordingService` nicht mitreißen.** Wenn die Kamera nicht öffnet, die Aufnahme
abbricht oder der Speicher volläuft, endet das in einer Meldung und einem Diagnoseeintrag — die
Pegelmessung läuft weiter. Mit V4 gibt es nichts wieder anzuwerfen — die Messung wurde nie
angehalten. Genau deshalb gilt hier die schärfere Fassung: **Der Videopfad darf den
Aufnahme-Loop unter keinen Umständen anhalten, auch nicht kurz, auch nicht im Fehlerfall.**
Die zusätzliche PCM-Senke aus B.2a wird in einem `finally` geschlossen; ein Fehler beim
Schließen darf die Schleife nicht verlassen.

Der Mux-Lauf (B.2a) ist ebenfalls fehlertolerant: Schlägt er fehl, bleiben stumme MP4 **und**
PCM-Datei erhalten, `tonGemuxt` bleibt `false`, es gibt einen Diagnoseeintrag und eine Meldung.
Kein automatischer Upload der stummen Fassung.

Breadcrumbs (Kategorie `"Videobeweis"`): Start (mit Auflösung, Mikrofon läuft ja/nein, freier
Speicher), Stopp (mit Dauer und Dateigröße), Mux-Start/-Ende (mit Offset in ms und Ergebnis),
Fehler, Uploadfortschritt je Block, Upload-Ergebnis.

### B.9 Akzeptanzkriterien Etappe B

- [ ] Die Videoaufnahme ruft **nirgends** `withAudioEnabled()` auf, und an der Stelle steht ein
      Kommentar, warum (B.2).
- [ ] Das fertige Video hat eine Tonspur (E4), erzeugt über den Mux-Lauf aus B.2a — nicht über
      die Kamera.
- [ ] Der Aufnahme-Loop in `AudioRecordingService` wird durch Start und Stopp einer
      Videoaufnahme weder angehalten noch neu gestartet; ein Test belegt das.
- [ ] Die Messreihe der Session hat im Videozeitraum **keine Lücke** (am Gerät geprüft).
- [ ] Ton und Bild sind synchron; der berechnete Offset steht im Diagnoselog.
- [ ] Läuft das Mikrofon nicht, wird **vor** dem Start gewarnt, das Video bleibt stumm und
      `hatTonspur = false`.
- [ ] Ein fehlgeschlagener Mux-Lauf löscht nichts, setzt `tonGemuxt` nicht und löst keinen
      Upload aus.
- [ ] `assembleDebug` und `test` grün, Ausgabe im PR.
- [ ] Migration + Migrationstest vorhanden.
- [ ] Der resumable Upload ist gegen `MockWebServer` getestet, inklusive Wiederaufnahme nach
      Abbruch und `404` auf den Session-URI.
- [ ] Kein Video-Upload ohne `videoDriveUpload = true`; kein Upload außerhalb der eingestellten
      WLAN-Bedingung.
- [ ] Maximaldauer und Speicherprüfung greifen; bei zu wenig Speicher startet keine Aufnahme.
- [ ] Ein Kamerafehler beendet die laufende Pegelaufzeichnung unter keinen Umständen; die
      zusätzliche PCM-Senke wird im `finally` geschlossen.
- [ ] Neue Abhängigkeiten (CameraX) sind im PR ausdrücklich benannt und begründet.
- [ ] Im PR steht, was ohne Gerät nicht verifiziert werden konnte.

---

## 4 · Offene Entscheidungen — nicht selbst entscheiden

`AGENTS.md` §2: „Wenn du auf eine im Plan als offen markierte Entscheidung stößt, entscheide
nicht — frag den Owner."

| Nr. | Frage | Empfehlung |
|---|---|---|
| ~~**E1**~~ | Woran hängt die Fotodokumentation: nur an der Messgerät-Session oder auch am reinen Mikrofonlauf? | **ENTSCHIEDEN (Owner): auch mit Mikrofonlauf.** Siehe Abschnitt 4a — das zieht eine Vorarbeit nach sich. |
| **E2** | Soll `PFLICHT` die Messung tatsächlich **blockieren**, bis fotografiert wurde? | Nein. Eine Nebenfunktion darf die Kernfunktion nicht verhindern. `PFLICHT` = Dialog erscheint + Auslassung wird protokolliert. |
| **E3** | Foto über die **System-Kamera** (`ACTION_IMAGE_CAPTURE`, keine Abhängigkeit) oder **In-App-Kamera** (CameraX, Zeitstempel-Overlay möglich)? | System-Kamera. Der Zeitstempel steht bereits in der Datenbank und im Bericht; ein eingebranntes Overlay ist nicht mehr wert und kostet erheblich mehr. |
| ~~**E4**~~ | Video **mit oder ohne Tonspur** — V1, V2 oder V3? | **ENTSCHIEDEN (Owner): mit Ton.** V1 entfällt — siehe Abschnitt 4a. |
| ~~**E9**~~ | **Wie** kommt der Ton ins Video, ohne die Messung zu unterbrechen? | **ENTSCHIEDEN (Owner): V4** — Video ohne Tonspur, Ton aus dem laufenden `AudioRecord`, Muxen nach dem Stopp. V2 und V3 entfallen, B.1 entfällt — siehe Abschnitt 4a. |
| **E5** | Drive-Ablage: alles weiter **flach** in einen Ordner (F-7) oder **Unterordner** `fotos/` und `videos/`? | Unterordner. Bei mehreren Fotos je Messung wird der flache Ordner sonst schnell unbrauchbar. Kostet nur einen zusätzlichen `ordnerAnlegen`-Aufruf plus eine gecachte Ordner-ID. |
| **E6** | **GPS-Koordinaten** im Foto-EXIF behalten? | Nein, entfernen. Sichtbar in den Einstellungen anbieten, falls der Owner sie im Beweismittel haben will — aber nie als stiller Default. |
| **E7** | Sollen Fotos auch im **Zeitraumbericht** (`PeriodenBerichtExport`) erscheinen? | In Etappe A nicht. Über viele Sessions hinweg wird das Dokument unbrauchbar groß. Später als eigene Anlage denkbar. |
| **E8** | **Aufbewahrungsfrist für Videos** — dieselben 90 Tage wie für Messwerte (Plan 13.2), oder kürzer? | Kürzer, Vorschlag 30 Tage mit Einstellung. Videos sind das größte Datenvolumen der App; wer eines dauerhaft braucht, kann es als Favorit markieren. |

---

## 4a · Getroffene Entscheidungen des Owners — verbindlich

Drei der neun Punkte aus Abschnitt 4 sind entschieden. **Sie sind keine Empfehlung mehr,
sondern Vorgabe.** Beide ziehen Arbeit nach sich, die sonst nicht angefallen wäre; das steht
hier, damit niemand sie beim Schätzen übersieht.

### E1 (entschieden) · Fotodokumentation gilt auch für den reinen Mikrofonlauf

**Das ist mehr als ein Häkchen.** Heute erzeugt ein Mikrofonlauf **keine `SessionEntity`** —
`MeasurementRecorder.kt:83` ist die einzige Stelle im gesamten Code, die eine Session anlegt,
und sie wird ausschließlich aus `AudioRecordingService.kt:221` mit einem `BoundDevice`
aufgerufen. Ein Foto „zum Messvorgang" hätte bei einem Mikrofonlauf also nichts, woran es
hängen könnte.

**Vorgabe: Der Mikrofonlauf bekommt eine eigene Session** — nicht die Fotos einen zweiten,
konkurrierenden Anker.

Das ist die einzige Variante, die nicht neuen Wildwuchs erzeugt. Der KDoc von `SessionEntity`
definiert eine Session ausdrücklich als „die Klammer um *wie lange wurde überwacht*, nicht *wie
lange stand die Verbindung*" — ein Mikrofonlauf ist genau so eine Klammer. Die Alternative
(`sessionId = null` plus Zuordnung über ein Zeitfenster) würde zwei verschiedene Arten von
„Messvorgang" nebeneinander etablieren, die jede spätere Auswertung doppelt behandeln muss.

**Konkret, und ohne Schemaänderung machbar:**

- Beim Start der Mikrofon-Überwachung eine `SessionEntity` anlegen mit
  `deviceName = "Smartphone-Mikrofon"` und `deviceAddress = ""`; beim Stoppen `endedAt` setzen.
  Beide Spalten sind `String` (non-null) — ein leerer `deviceAddress` ist der ehrliche Wert
  („es gibt keine BLE-Adresse"), ein erfundener wäre eine gespeicherte Tatsachenbehauptung.
  **Keine Migration nötig.**
- `weighting`, `timeWeighting` und `range` bleiben `null` — beim Mikrofon ist nichts davon
  bekannt, und der `SessionEntity`-KDoc verlangt für Unbekanntes ausdrücklich `null`.

**Diese vier Nebenwirkungen musst du prüfen und im PR beantworten** — sie entstehen dadurch,
dass es plötzlich Sessions ohne Messgerät gibt:

1. `ui/ProtokollScreen.kt` listet Sessions auf. Mikrofon-Sessions erscheinen dort ab sofort
   mit. Ist die Darstellung mit leerem `deviceAddress` erträglich, oder braucht sie eine
   eigene Kennzeichnung („Mikrofon" statt Gerätename)?
2. `PeriodenBericht.sessionCount` (`report/PeriodenBerichtDaten.kt`) zählt ab sofort mehr
   Sessions. Ist das gewollt (ja — es *waren* Messvorgänge) und im Bericht verständlich?
3. `leiteAusfallbaenderAb` liefert für eine Mikrofon-Session immer eine leere Liste, weil es
   keine `ConnectionEventEntity` gibt. Das ist korrekt, darf aber im Bericht nicht als
   „keine Ausfälle, alles gut" missverstanden werden, wo in Wahrheit „nicht anwendbar" gilt.
4. `MessreiheExport.exportierePdf` für eine Mikrofon-Session hat **keine**
   `MeasurementEntity`-Zeilen, die Kennwerte sind also leer. Der Bericht muss das aushalten und
   verständlich beschriften, statt Nullen oder Striche zu zeigen.

**Reihenfolge:** Diese Vorarbeit kommt **vor** der Fototabelle und gehört in einen eigenen
Commit („Mikrofonlauf eröffnet eine Session"), damit sie im Review von der Fotofunktion
trennbar bleibt. `dokumentationsfotos.sessionId` kann dadurch **non-null** werden — jeder
Messvorgang hat dann eine Session.

### E4 (entschieden) · Videobeweis wird **mit Tonspur** aufgenommen

**V1 (ohne Ton) entfällt.** Ein Beweisvideo ohne Ton ist als Beleg deutlich weniger wert — bei
Lärm ist der Ton der eigentliche Gegenstand.

Damit ist auch die Kamera-Frage entschieden: Über den System-Kamera-Intent lässt sich die
Tonspur nicht steuern, aber ohne Steuerung auch keine verlässliche Maximaldauer setzen (B.2) —
es bleibt **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-video`) mit
`CAMERA`-Berechtigung. Der Bruch mit dem minimalen Abhängigkeitsstil des Projekts ist damit vom
Owner gedeckt; **benenne ihn im PR trotzdem ausdrücklich.**

### E9 (entschieden) · V4 — Ton nicht von der Kamera, sondern nachträglich einmuxen

**Der Owner hat den Mechanismus vorgegeben:** Die Kamera nimmt **ohne Tonspur** auf, der Ton
kommt aus dem ohnehin laufenden `AudioRecord`, und beides wird nach dem Stopp zu einer Datei
zusammengeführt (B.2a).

**Warum das die anderen Varianten schlägt:**

- **Es gibt keinen zweiten Mikrofon-Zugriff.** In CameraX ist Audio opt-in
  (`withAudioEnabled()`); wird der Aufruf weggelassen, fasst die Kamera das Mikrofon nicht an.
  Der Konflikt aus F-4 entsteht gar nicht erst.
- **Die Messung läuft durch.** V2 hätte den Ton mit einer Lücke in der Messreihe bezahlt —
  ausgerechnet in dem Moment, in dem der Nutzer filmt, also im lautesten. Für ein Beweismittel
  ist das die teuerste denkbare Lücke.
- **Kein Geräterisiko.** V3 hätte darauf gewettet, dass Audio-Policy, Hersteller und
  Android-Version den Parallelbetrieb erlauben — auf jedem Gerät neu. V4 braucht diese Zusage
  nicht.
- **Der Ton wird besser.** Das Mikrofon läuft bereits mit `UNPROCESSED` und abgeschalteten
  AEC/NS/AGC (KI-Umbau Etappe 1.2). Genau diese Rohsignal-Eigenschaften, die für die
  Klassifikation gebraucht werden, machen auch den Beweisclip aussagekräftiger als eine von der
  Kamera-App normalisierte Tonspur.

**Damit entfällt B.1 als Vorbedingung.** Etappe B kann ohne vorherige Gerätemessung begonnen
werden. Was stattdessen nachzuweisen ist, steht in B.1.

**Nicht optional:**

1. `withAudioEnabled()` wird **nirgends** aufgerufen, und an der Stelle steht ein Kommentar,
   warum. Ohne diesen Kommentar ergänzt ihn der nächste Beitrag als vermeintlichen Bugfix.
2. Der Ton für das Video braucht eine **eigene** PCM-Senke im Aufnahme-Loop. Die bestehende
   WAV-Aufzeichnung ist ereignisgebunden und deckt einen Videozeitraum nicht ab (B.2a,
   Schritt 1).
3. Vor dem Start wird gewarnt, wenn das Mikrofon nicht läuft — dann bleibt das Video stumm.
4. Ein fehlgeschlagener Mux-Lauf löscht keine Zwischendateien und löst keinen Upload aus.

**Was der Owner dabei in Kauf nimmt** (im PR erneut benennen, nicht verschweigen): Ton und Bild
werden über zwei unabhängige Uhren zusammengeführt. Über die vorgesehene Maximaldauer von
wenigen Minuten ist die Drift praktisch nicht wahrnehmbar, über sehr lange Aufnahmen kann sie
es werden. Die harte Maximaldauer aus B.5 ist damit nicht nur eine Speichergrenze.

**Wichtige Differenzierung aus F-4, die dadurch entfällt:** Die Unterscheidung zwischen
`audioTriggerQuelle = "MIKROFON"` und verbundenem PCE-323 war nur für V2 relevant (wo die Lücke
im Mikrofonbetrieb die Messung selbst gekostet hätte). Mit V4 entsteht in keiner der beiden
Konstellationen eine Lücke.

---

## 5 · Ausdrücklich **nicht** Teil dieses Auftrags

- Automatische Videoaufnahme bei Schwellenüberschreitung (siehe B.3 — technisch ab Android 14
  eingeschränkt und nicht beauftragt).
- Nachträgliches Einmuxen von Ton in **bereits vorhandene** Videos oder in Ereignis-WAVs. B.2a
  gilt für Videos, die dieser Auftrag selbst aufnimmt.
- Live-Stream oder Fernzugriff auf die Kamera.
- Gesichts- oder Kennzeichenunkenntlichmachung.
- Fotos oder Videos in Alarm-Benachrichtigungen (ntfy).
- Nachträgliche Bildbearbeitung (Zuschneiden, Markieren, Annotieren).
- Umbau der `DriveApiClient`-Bestandsmethoden auf Streaming. Der resumable Pfad kommt
  **additiv daneben**; CSV und WAV bleiben unverändert auf dem geprüften Weg.
- Konsolidierung von `LevelSampleEntity` und `MeasurementEntity` (steht als möglicher
  Folgeschritt im `SessionEntity`-KDoc — hier nicht anfassen).

---

## 6 · Definition of Done

Für **jede** der beiden Etappen einzeln, gemäß `AGENTS.md` §7:

1. `./gradlew assembleDebug` und `./gradlew test` grün — **Ausgabe im PR, nicht deren
   Zusammenfassung.**
2. Alle bestehenden Room-Migrationstests weiterhin grün.
3. Jedes Akzeptanzkriterium der Etappe einzeln adressiert — auch die, die du nicht erfüllen
   konntest, mit Begründung.
4. Branch gepusht, **Draft-PR** gegen `main`. Der PR-Text nennt:
   *was geändert wurde · was verifiziert wurde (Kommando + Ergebnis) · was bewusst offen blieb ·
   welche offene Entscheidung aus Abschnitt 4 dir begegnet ist und wie sie beantwortet wurde.*
5. Kurze Rückmeldung an den Owner: erledigt / nicht erledigt / aufgefallen.

**Und die Regel, die über allem steht:** Was du nicht ausgeführt hast, behauptest du nicht.
Kamera-Intents, EXIF-Verhalten, die A/V-Synchronität des gemuxten Videos und der echte
Drive-Upload sind in einer Sandbox ohne Gerät und ohne Google-Konto **nicht** verifizierbar. Schreib genau das in den PR —
das ist keine Schwäche des Ergebnisses, sondern die Voraussetzung dafür, dass der Owner weiß,
was er auf dem Gerät noch nachprüfen muss.
