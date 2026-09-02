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
- **Etappe B — Videobeweis.** Hat zwei harte Vorbedingungen, die *vor* der Umsetzung geklärt
  werden müssen: ein möglicher Mikrofon-Konflikt mit dem laufenden Foreground Service (nur auf
  einem echten Gerät messbar) und ein Drive-Upload-Pfad, der große Dateien überhaupt verträgt —
  der heutige verträgt sie nicht. **Etappe B startet erst, wenn Abschnitt B.1 abgearbeitet und
  Entscheidung E4 gefallen ist.**

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

### F-4 · Der Mikrofon-Konflikt ist das zentrale Risiko von Etappe B

`AudioRecordingService` läuft als Foreground Service mit
`android:foregroundServiceType="microphone|connectedDevice"` und hält über `AudioRecord` das
Mikrofon. Eine Videoaufnahme **mit Tonspur** greift auf dieselbe Ressource zu. Was Android in
diesem Fall tut, hängt von Version, Hersteller und Audio-Policy ab und ist **nicht aus dem Code
ableitbar — es muss gemessen werden.** Siehe B.1.

Wichtige Differenzierung, die die Sache entschärfen kann: Bei
`settings.audioTriggerQuelle == "PCE_323"` (bzw. `"AUTO"` mit verbundenem Messgerät) kommt der
maßgebliche, kalibrierte Pegel vom PCE-323 über BLE, **nicht** vom Mikrofon
(`messreihe/MeterTriggerSource.kt:52`). In dieser Konstellation wiegt ein Mikrofon-Verlust
deutlich weniger als bei `"MIKROFON"`. Das ist ein Argument für eine differenzierte Behandlung,
kein Freibrief — auch im Messgerät-Betrieb schneidet der Service weiter Audio mit.

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
→ **Entscheidung E1.**

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
    /** Zugehörige Session; null, falls E1 einen mikrofonbasierten Lauf ohne Session zulässt. */
    val sessionId: Long?,
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

1. Nutzer startet den Messvorgang (welcher Startpunkt genau: **E1**).
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
- [ ] Fotos erscheinen im Session-PDF; ein fehlendes Foto erzeugt einen Platzhalter statt eines
      Absturzes.
- [ ] Fotos landen in Drive, genau einmal, nur unter der eingestellten WLAN-Bedingung.
- [ ] Kein Kamera-, Datei- oder Upload-Fehler kann eine laufende Messung beenden oder verhindern.
- [ ] Kein neuer Bibliotheks-Dependency (weder Kamera- noch Bild- noch PDF-Bibliothek).
- [ ] Im PR steht ausdrücklich, was **nicht** verifiziert werden konnte (Kamera-Intent und
      EXIF-Verhalten sind ohne echtes Gerät nicht prüfbar).

---

## 3 · Etappe B — Videobeweis

> **Etappe B beginnt nicht mit Code.** Sie beginnt mit B.1. Wer B.1 überspringt, baut mit hoher
> Wahrscheinlichkeit eine Funktion, die die Kernaufgabe der App — die lückenlose
> Pegelaufzeichnung — im Betrieb sabotiert.

### B.0 Ziel

Während einer laufenden Aufzeichnung startet der Nutzer per Knopfdruck eine Videoaufnahme als
Beweismittel. Das Video wird der Session zugeordnet und nach Drive hochgeladen.

### B.1 Vorbedingung — Gerätemessung des Mikrofon-Konflikts

`AudioRecordingService` hält das Mikrofon (F-4). Was passiert, wenn parallel eine Videoaufnahme
mit Tonspur startet, ist von der Audio-Policy des Geräts abhängig und **nicht aus dem Code
ableitbar**. Mögliche Ausgänge, alle real: Video bekommt Stille · Mikrofon-Monitoring wird still
stumm (Pegel fällt auf ~0, ohne Fehler) · `AudioRecord` liefert Fehler · beides funktioniert.

**Bau eine minimale Messung — noch keine Funktion:**

1. Einen Debug-Knopf (oder ein instrumentierter Test) startet bei laufendem
   `AudioRecordingService` eine 20-Sekunden-Videoaufnahme mit Tonspur.
2. Protokolliere über den `DiagnosticsReporter` mit: den Mikrofonpegel je Sekunde vor, während
   und nach der Videoaufnahme; ob `AudioRecord.getRecordingState()` sich ändert; ob ein
   `AudioRecord`-Fehler auftritt; ob die Videodatei eine hörbare Tonspur enthält.
3. Wiederhole für **beide** Konstellationen: `audioTriggerQuelle = "MIKROFON"` und
   `= "PCE_323"` mit verbundenem Messgerät.
4. **Schreib das Ergebnis in den PR und in `docs/CHECKLISTE_GERAETETEST.md`.** Mit Gerätemodell
   und Android-Version — das Ergebnis ist geräteabhängig und in einem Jahr sonst wertlos.

**Erst danach** entscheidet sich (mit dem Owner, **E4**), welche Variante gebaut wird:

| Variante | Beschreibung | Kosten |
|---|---|---|
| **V1** | Video **ohne** Tonspur | Mikrofon-Konflikt existiert nicht. Preis: Das Video belegt, *was* zu sehen war, nicht *wie laut* es war. Für den Nachweis „Bagger stand um 6:12 Uhr vor dem Haus" reicht das oft. |
| **V2** | Mikrofon-Monitoring für die Dauer der Videoaufnahme pausieren, Video **mit** Ton | Ehrlich und einfach zu erklären. Preis: eine bewusste, sichtbare **Lücke in der Messreihe** — die muss dann auch als Lücke protokolliert werden, wie die Ausfallbänder in `ConnectionEventEntity`. Bei verbundenem PCE-323 entsteht **keine** Pegel-Lücke (F-4), nur eine Lücke in der WAV-Erfassung. |
| **V3** | Beides parallel, weil B.1 gezeigt hat, dass es auf den Zielgeräten funktioniert | Das Beste — **nur wenn gemessen.** Niemals als Annahme. |

**Empfehlung:** V1 als Default, V2 als Option, V3 nur nach positivem B.1-Ergebnis auf mindestens
zwei verschiedenen Geräten. Die Entscheidung trifft der Owner.

### B.2 Kamera-Anbindung

Hier ist die Abwägung anders als bei den Fotos.

- **`ActivityResultContracts.CaptureVideo()`** (System-Kamera): keine Abhängigkeit, kein
  `CAMERA`-Recht, kein Foreground Service. **Aber:** Der Nutzer muss die Aufnahme in der
  fremden Kamera-App selbst beenden, du kannst Dauer, Auflösung und Tonspur **nicht** steuern
  (`EXTRA_DURATION_LIMIT` und `EXTRA_VIDEO_QUALITY` sind Empfehlungen, die viele Kamera-Apps
  ignorieren) — und **eine Tonspur abzuschalten ist über den Intent gar nicht möglich.** Damit
  ist V1 auf diesem Weg nicht baubar.
- **CameraX `VideoCapture` + `Recorder`** (`androidx.camera:camera-video`): volle Kontrolle über
  Tonspur, Qualität und Maximaldauer. Preis: vier neue Abhängigkeiten (`camera-core`,
  `camera-camera2`, `camera-lifecycle`, `camera-video`), `CAMERA`-Berechtigung, eigener
  Vorschau-Screen und — auf Android 14+ — ein Foreground Service vom Typ `camera`, wenn die
  Aufnahme weiterlaufen soll, während die App im Hintergrund ist.

**Empfehlung: CameraX**, weil ohne Kontrolle über die Tonspur weder V1 noch eine verlässliche
Maximaldauer machbar ist. Das ist ein bewusster Bruch mit dem minimalen Abhängigkeitsstil des
Projekts — **benenne ihn im PR ausdrücklich**, statt ihn nebenbei einzuführen.

Falls CameraX abgelehnt wird, ist nur V2 mit der System-Kamera möglich, mit allen genannten
Einschränkungen. Auch das ist **E4**.

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
    val sessionId: Long?,
    val dateiPfad: String,
    val gestartetAm: Long,
    val dauerMs: Long,
    val hatTonspur: Boolean,
    val groesseBytes: Long,
    val notiz: String?,
    val driveFileId: String? = null,
    /** Fortschritt einer unterbrochenen resumable-Übertragung, siehe B.6. */
    val uploadSessionUri: String? = null,
    val hochgeladeneBytes: Long = 0,
)
```

`hatTonspur` ist kein Beiwerk: Wenn das Video später als Beleg dient, muss aus dem Datensatz
hervorgehen, ob Stille im Video „es war leise" oder „es wurde bewusst ohne Ton aufgezeichnet"
bedeutet. Das ist der Unterschied zwischen einem Beweis und einem Missverständnis.

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
- Bei V1 (ohne Ton) **muss** sichtbar dastehen: „Aufnahme ohne Ton (Mikrofon misst weiter)".
  Ein Nutzer, der erst beim Abspielen merkt, dass der Beweis stumm ist, ist zu Recht verärgert.
- Bei V2 **muss** vor dem Start eine Bestätigung kommen: „Die Pegelmessung pausiert für die
  Dauer der Videoaufnahme." Das ist eine Lücke im Beweismittel — sie darf nicht stillschweigend
  entstehen.
- Videos im Session-Detail auflisten, Abspielen über einen `ACTION_VIEW`-Intent mit
  FileProvider-Uri (kein eingebauter Player — dafür gibt es keinen Grund und keine
  Abhängigkeit).

### B.8 Robustheit

Dieselbe Garantie wie in A.9, mit einem Zusatz, der hier schwerer wiegt: **Ein Kamerafehler darf
den `AudioRecordingService` nicht mitreißen.** Wenn die Kamera nicht öffnet, die Aufnahme
abbricht oder der Speicher volläuft, endet das in einer Meldung und einem Diagnoseeintrag — die
Pegelmessung läuft weiter. Bei V2 heißt das ausdrücklich: **Das Mikrofon-Monitoring muss auch
dann wieder anlaufen, wenn die Videoaufnahme mit einem Fehler endet** — nicht nur im
Erfolgsfall. Bau den Wiederanlauf in ein `finally`, nicht hinter den Erfolgspfad.

Breadcrumbs (Kategorie `"Videobeweis"`): Start (mit Auflösung, Tonspur ja/nein, freier
Speicher), Stopp (mit Dauer und Dateigröße), Fehler, Uploadfortschritt je Block, Upload-Ergebnis.

### B.9 Akzeptanzkriterien Etappe B

- [ ] **B.1 ist durchgeführt und das Ergebnis dokumentiert** — mit Gerät, Android-Version und
      gemessenen Werten, nicht mit Vermutungen.
- [ ] E4 ist vom Owner entschieden; die gebaute Variante entspricht der Entscheidung.
- [ ] `assembleDebug` und `test` grün, Ausgabe im PR.
- [ ] Migration + Migrationstest vorhanden.
- [ ] Der resumable Upload ist gegen `MockWebServer` getestet, inklusive Wiederaufnahme nach
      Abbruch und `404` auf den Session-URI.
- [ ] Kein Video-Upload ohne `videoDriveUpload = true`; kein Upload außerhalb der eingestellten
      WLAN-Bedingung.
- [ ] Maximaldauer und Speicherprüfung greifen; bei zu wenig Speicher startet keine Aufnahme.
- [ ] Bei V2: Die Messlücke ist im UI angekündigt **und** in der Datenbank protokolliert.
- [ ] Ein Kamerafehler beendet die laufende Pegelaufzeichnung unter keinen Umständen —
      Wiederanlauf im `finally`, nicht im Erfolgspfad.
- [ ] Neue Abhängigkeiten (CameraX) sind im PR ausdrücklich benannt und begründet.
- [ ] Im PR steht, was ohne Gerät nicht verifiziert werden konnte.

---

## 4 · Offene Entscheidungen — nicht selbst entscheiden

`AGENTS.md` §2: „Wenn du auf eine im Plan als offen markierte Entscheidung stößt, entscheide
nicht — frag den Owner."

| Nr. | Frage | Empfehlung |
|---|---|---|
| **E1** | Woran hängt die Fotodokumentation: an der **Messgerät-Session** (`SessionEntity`, F-5) oder auch an einem reinen Mikrofonlauf, der heute gar keine Session-Zeile erzeugt? | An der Session. Ein Beleg-Foto ohne Messvorgang, dem es zugeordnet ist, hat wenig Wert. Falls Mikrofonläufe mitsollen, müssten die erst eine Session bekommen — das wäre ein eigener Auftrag. |
| **E2** | Soll `PFLICHT` die Messung tatsächlich **blockieren**, bis fotografiert wurde? | Nein. Eine Nebenfunktion darf die Kernfunktion nicht verhindern. `PFLICHT` = Dialog erscheint + Auslassung wird protokolliert. |
| **E3** | Foto über die **System-Kamera** (`ACTION_IMAGE_CAPTURE`, keine Abhängigkeit) oder **In-App-Kamera** (CameraX, Zeitstempel-Overlay möglich)? | System-Kamera. Der Zeitstempel steht bereits in der Datenbank und im Bericht; ein eingebranntes Overlay ist nicht mehr wert und kostet erheblich mehr. |
| **E4** | Video **mit oder ohne Tonspur** — V1, V2 oder V3 (B.1)? Und damit: CameraX oder System-Kamera? | Erst B.1 messen. Danach: V1 (ohne Ton) als Default, V2 als Option. V3 nur bei bestätigt konfliktfreiem Parallelbetrieb auf ≥ 2 Geräten. |
| **E5** | Drive-Ablage: alles weiter **flach** in einen Ordner (F-7) oder **Unterordner** `fotos/` und `videos/`? | Unterordner. Bei mehreren Fotos je Messung wird der flache Ordner sonst schnell unbrauchbar. Kostet nur einen zusätzlichen `ordnerAnlegen`-Aufruf plus eine gecachte Ordner-ID. |
| **E6** | **GPS-Koordinaten** im Foto-EXIF behalten? | Nein, entfernen. Sichtbar in den Einstellungen anbieten, falls der Owner sie im Beweismittel haben will — aber nie als stiller Default. |
| **E7** | Sollen Fotos auch im **Zeitraumbericht** (`PeriodenBerichtExport`) erscheinen? | In Etappe A nicht. Über viele Sessions hinweg wird das Dokument unbrauchbar groß. Später als eigene Anlage denkbar. |
| **E8** | **Aufbewahrungsfrist für Videos** — dieselben 90 Tage wie für Messwerte (Plan 13.2), oder kürzer? | Kürzer, Vorschlag 30 Tage mit Einstellung. Videos sind das größte Datenvolumen der App; wer eines dauerhaft braucht, kann es als Favorit markieren. |

---

## 5 · Ausdrücklich **nicht** Teil dieses Auftrags

- Automatische Videoaufnahme bei Schwellenüberschreitung (siehe B.3 — technisch ab Android 14
  eingeschränkt und nicht beauftragt).
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
Kamera-Intents, EXIF-Verhalten, der Mikrofon-Konflikt und der echte Drive-Upload sind in einer
Sandbox ohne Gerät und ohne Google-Konto **nicht** verifizierbar. Schreib genau das in den PR —
das ist keine Schwäche des Ergebnisses, sondern die Voraussetzung dafür, dass der Owner weiß,
was er auf dem Gerät noch nachprüfen muss.
