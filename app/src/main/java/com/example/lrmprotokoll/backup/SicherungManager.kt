package com.example.lrmprotokoll.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.Versionskennung
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SettingsManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MANIFEST_ENTRY = "manifest.json"
private const val DATENBANK_ENTRY = "noise_database"
private const val EINSTELLUNGEN_ENTRY = "settings.json"
private const val TEIL_DATENBANK_ENTRY = "teil_database"

/** Art einer Sicherung im Manifest (docs/PROMPT_SICHERUNG_VOLL_UND_TEIL.md). */
internal const val ART_VOLL = "VOLL"
internal const val ART_TEIL = "TEIL"

/** Erhöhen, wenn sich [buildEinstellungenJson]/[wendeEinstellungenAn] inkompatibel ändern. */
internal const val SICHERUNG_FORMAT_VERSION = 1

/**
 * Schätzt den benötigten freien Speicherplatz im Zielverzeichnis für die Sicherungs-ZIP ab.
 *
 * Hintergrund (Befund 2 aus docs/BEFUNDE_SUPPORT_BUNDLES_2026-10-02.md):
 * Früher wurde pauschal `dbDatei.length() * 1.1` verlangt. SQLite-Datenbanken komprimieren im
 * ZIP-Format jedoch typischerweise um 40–80 % (reale ZIP-Größe meist deutlich unter der Rohgröße).
 * Bei einer 1-GB-Datenbank forderte die alte Formel mindestens 1,16 GB freien internen Speicher,
 * was auf Geräten mit z. B. 650 MB freiem Speicher zu dauerhaften Fehlschlägen führte, obwohl der
 * Platz für die komprimierte ZIP (~250–350 MB) vollkommen ausgereicht hätte.
 *
 * Schätzformel:
 * - Bei kleinen Datenbanken (< 50 MB): konservativ 1.1x der Dateigröße plus 10 MB Sicherheitszuschlag.
 * - Bei großen Datenbanken (>= 50 MB): Berücksichtigung der DEFLATE-Kompression mit konservativem
 *   Faktor 0.60x der Rohgröße plus 30 MB Sicherheitszuschlag.
 */
internal fun schatzeBenoetigtenSpeicherplatz(dbGroesse: Long): Long {
    val schwellwertGross = 50L * 1024L * 1024L // 50 MB
    val basis = if (dbGroesse < schwellwertGross) {
        (dbGroesse * 1.1).toLong() + 10L * 1024L * 1024L
    } else {
        (dbGroesse * 0.60).toLong() + 30L * 1024L * 1024L
    }
    return maxOf(10L * 1024L * 1024L, basis)
}

data class SicherungsErgebnis(val erfolg: Boolean, val nachricht: String)

/**
 * Eine gebaute Vollsicherung für Drive: die Datei plus das, was eine spätere Teilsicherung über
 * sie wissen muss - ihre Kennung ([vollsicherungId], im Manifest beider Dateien) und ab welchen
 * IDs der Zuwachs beginnt ([stand]).
 */
data class Vollsicherung(
    val datei: File,
    val vollsicherungId: Long,
    val stand: SicherungsDatenbank.Stand,
)

/**
 * Zu wenig freier Speicherplatz im Zielverzeichnis, um die Sicherungs-ZIP verlustfrei zu
 * schreiben (Bugfix 23.09.2026, siehe [SicherungManager.baueSicherungsDatei]). Vor diesem Fix gab
 * es hier GAR KEINE Prüfung - ein `OutOfMemoryError` beim (bis dahin im Speicher gehaltenen)
 * `ByteArray` war das sichtbare Symptom, ein `IOException: No space left on device` mitten im
 * Schreiben der Datei wäre ohne diese Prüfung der nächste Fehlermodus gewesen und hätte eine
 * halb geschriebene, kaputte Sicherungsdatei hinterlassen.
 */
class UnzureichenderSpeicherplatzException(
    val freierPlatz: Long,
    val benoetigterPlatz: Long,
) : IOException(
    "Zu wenig Speicherplatz für die Sicherung: frei $freierPlatz Bytes (${freierPlatz / 1_048_576} MB), nötig $benoetigterPlatz Bytes (${benoetigterPlatz / 1_048_576} MB).",
)

/**
 * F13 (PROMPT_M10_FUNKTIONEN.md): Sicherung/Wiederherstellung über das Storage Access Framework,
 * als Ersatz für die bisherige `adb exec-out`-Anleitung (README) - für jeden ohne Terminal
 * unbenutzbar. Enthält eine rohe Kopie der Room-Datenbankdatei (nach WAL-Checkpoint, siehe
 * [checkpointeDatenbankDatei]) plus die Nutzer-Einstellungen als Klartext-JSON (siehe
 * `SicherungEinstellungen.kt` für die genaue Feldauswahl und Begründung).
 *
 * Die verschlüsselten Werte (ntfyTopic/-Server, heartbeatUrl, Plan Abschnitt 6) werden über
 * [SettingsManager]s eigene Getter/Setter gelesen/geschrieben, nie die rohe
 * EncryptedSharedPreferences-Datei kopiert - eine kopierte Ciphertext-Datei wäre nach einer
 * Neuinstallation (neuer Android-Keystore-Schlüssel) nicht mehr entschlüsselbar, ein Klartext-
 * Roundtrip über die bestehende API funktioniert dagegen unabhängig vom Keystore-Zustand. Die
 * Kehrseite: diese drei Werte liegen im Sicherungs-JSON als Klartext - die Sicherungsdatei ist
 * deshalb selbst schützenswert, genau wie ein CSV-/PDF-Export.
 *
 * **Streaming-Umbau (Bugfix 23.09.2026, docs/PROMPT_FIX_DATENBANK_SICHERUNG.md):** Bis dahin
 * hielt jeder Sicherungs-/Wiederherstellungsweg die komplette Datenbank (auf dem Owner-Gerät
 * ~492 MB, Heap-Grenze 402 MB) als `ByteArray` im Speicher - 95 gescheiterte Versuche mit
 * `OutOfMemoryError` zwischen dem 16. und 23.09.2026 (siehe
 * `docs/BEFUNDE_P30_2026-09-23.md`, Abschnitt 2/A2). Seitdem läuft jeder Weg über [File]/
 * [InputStream]/[java.util.zip.ZipOutputStream] mit Standardpuffer (8 KiB) - nur Manifest und
 * Einstellungen (verschwindend klein) dürfen noch als `ByteArray` im Speicher landen.
 */
object SicherungManager {

    suspend fun erstelleSicherung(
        context: Context,
        ziel: Uri,
        settings: SettingsManager,
    ): SicherungsErgebnis = withContext(Dispatchers.IO) {
        // Streamend in eine temporaere Datei bauen, dann in den SAF-OutputStream kopieren - ein
        // SAF-Uri liefert keinen direkten Dateipfad, den ZipOutputStream bräuchte, und manche
        // SAF-Provider puffern/validieren ohnehin erst nach dem vollständigen Schreiben.
        val tempDatei = File.createTempFile("sicherung_", ".zip", context.cacheDir)
        try {
            baueSicherungsDatei(context, settings, tempDatei)
            val output = context.contentResolver.openOutputStream(ziel)
                ?: return@withContext SicherungsErgebnis(false, "Zieldatei konnte nicht geöffnet werden.")
            output.use { out -> FileInputStream(tempDatei).use { it.copyTo(out) } }
            SicherungsErgebnis(true, "Sicherung erstellt.")
        } catch (e: Exception) {
            SicherungsErgebnis(false, "Sicherung fehlgeschlagen: ${e.message}")
        } finally {
            tempDatei.delete()
        }
    }

    /**
     * Baut die eigentliche Sicherungs-ZIP (Manifest + Einstellungen + Datenbank nach
     * WAL-Checkpoint) STREAMEND direkt in [ziel] - Ersatz für das frühere `baueSicherungsBytes()`
     * (Bugfix 23.09.2026): [ziel] wird nie komplett im Speicher gehalten, Manifest/Einstellungen
     * sind klein und dürfen es bleiben, die Datenbank läuft per [FileInputStream.copyTo] mit
     * Standardpuffer durch. Der Kern von [erstelleSicherung], separat aufrufbar für Ziele ohne
     * SAF-[Uri] (die automatische Drive-Sicherung, siehe
     * [com.example.lrmprotokoll.drive.DriveDatenbankSicherung]).
     *
     * Konsistenz wie vorher: erst `PRAGMA wal_checkpoint(FULL)` erzwingen, dann die (jetzt
     * konsistente) Datei kopieren. `VACUUM INTO` (das den Checkpoint und die Kopie in einem
     * SQLite-Aufruf erledigen würde) gibt es auf Android 10/SQLite 3.22 (Owner-Gerät) nicht.
     *
     * @param freierPlatzErmitteln Testnaht für die Platzprüfung unten - im Produktivbetrieb
     * [File.usableSpace] (echter freier Platz im Zielverzeichnis). Ein Test kann hier einen
     * kleinen Fixwert injizieren, ohne tatsächlich hunderte MB Platz belegen zu müssen.
     */
    suspend fun baueSicherungsDatei(
        context: Context,
        settings: SettingsManager,
        ziel: File,
        freierPlatzErmitteln: (File) -> Long = { it.usableSpace },
        ohneRohwerte: Boolean = false,
        vollsicherungId: Long = System.currentTimeMillis(),
    ): SicherungsDatenbank.Stand? =
        withContext(Dispatchers.IO) {
            val dbDatei = checkpointeDatenbankDatei(context)
            val schemaVersion =
                AppDatabase
                    .getDatabase(context)
                    .openHelper
                    .writableDatabase
                    .version

            // Vorab prüfen, BEVOR ueberhaupt etwas geschrieben wird (Schritt 1) - eine zu spaete
            // Pruefung liesse eine halb geschriebene Zieldatei zurueck. Ohne Rohwerte kommt eine
            // volle Kopie der Datenbank dazu, aus der sie entfernt werden.
            val benoetigterPlatz =
                schatzeBenoetigtenSpeicherplatz(dbDatei.length()) + if (ohneRohwerte) dbDatei.length() else 0L
            val zielVerzeichnis = ziel.absoluteFile.parentFile ?: ziel.absoluteFile
            val freierPlatz = freierPlatzErmitteln(zielVerzeichnis)
            if (freierPlatz < benoetigterPlatz) {
                throw UnzureichenderSpeicherplatzException(freierPlatz, benoetigterPlatz)
            }

            // Vollsicherung fuer Drive (docs/PROMPT_SICHERUNG_VOLL_UND_TEIL.md): `level_samples`
            // wird in einer KOPIE geleert, nie in der laufenden Datenbank.
            val kopie =
                if (ohneRohwerte) {
                    File.createTempFile("sicherung_kopie_", ".db", context.cacheDir).also { dbDatei.copyTo(it, overwrite = true) }
                } else {
                    null
                }
            try {
                val stand = kopie?.let { SicherungsDatenbank.entferneRohwerteUndErmittleStand(it) }
                val manifest =
                    buildManifest().apply {
                        put("art", ART_VOLL)
                        put("schemaVersion", schemaVersion)
                        put("vollsicherungId", vollsicherungId)
                        put("ohneRohwerte", ohneRohwerte)
                    }
                ziel.parentFile?.mkdirs()
                ZipOutputStream(FileOutputStream(ziel).buffered()).use { zos ->
                    schreibeEintrag(zos, MANIFEST_ENTRY, manifest.toString(2).toByteArray(Charsets.UTF_8))
                    schreibeEintrag(
                        zos,
                        EINSTELLUNGEN_ENTRY,
                        buildEinstellungenJson(settings).toString(2).toByteArray(Charsets.UTF_8),
                    )
                    zos.putNextEntry(ZipEntry(DATENBANK_ENTRY))
                    FileInputStream(kopie ?: dbDatei).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                stand
            } finally {
                kopie?.delete()
            }
        }

    /**
     * Vollsicherung fuer Drive: ohne `level_samples`, mit Stand fuer die Teilsicherungen. Legt
     * die Datei selbst im `cacheDir` an und raeumt sie bei einem Fehlschlag wieder weg (wie
     * [baueSicherungsDateiMitAufraeumen]); im Erfolgsfall gehoert sie dem Aufrufer.
     */
    suspend fun baueVollsicherung(
        context: Context,
        settings: SettingsManager,
        freierPlatzErmitteln: (File) -> Long = { it.usableSpace },
    ): Vollsicherung {
        val ziel = File.createTempFile("drive_vollsicherung_", ".zip", context.cacheDir)
        var erfolgreich = false
        try {
            val id = System.currentTimeMillis()
            val stand =
                baueSicherungsDatei(context, settings, ziel, freierPlatzErmitteln, ohneRohwerte = true, vollsicherungId = id)!!
            erfolgreich = true
            return Vollsicherung(ziel, id, stand)
        } finally {
            if (!erfolgreich) ziel.delete()
        }
    }

    /**
     * Kumulative Teilsicherung (docs/PROMPT_SICHERUNG_VOLL_UND_TEIL.md): alles, was sich seit
     * der Vollsicherung [vollsicherungId] geaendert hat - kleine Tabellen komplett, Messwerte und
     * Klassifikations-Rohdaten nur der Zuwachs ueber [stand]. Liegt im `cacheDir`; bei einem
     * Fehlschlag wird sie wieder entfernt, im Erfolgsfall gehoert sie dem Aufrufer.
     */
    suspend fun baueTeilsicherung(
        context: Context,
        vollsicherungId: Long,
        stand: SicherungsDatenbank.Stand,
    ): File =
        withContext(Dispatchers.IO) {
            val dbDatei = checkpointeDatenbankDatei(context)
            val schemaVersion =
                AppDatabase
                    .getDatabase(context)
                    .openHelper
                    .writableDatabase
                    .version
            val teilDb = File.createTempFile("teilsicherung_", ".db", context.cacheDir)
            val ziel = File.createTempFile("drive_teilsicherung_", ".zip", context.cacheDir)
            var erfolgreich = false
            try {
                SicherungsDatenbank.baueTeilsicherung(dbDatei, teilDb, stand)
                val manifest =
                    buildManifest().apply {
                        put("art", ART_TEIL)
                        put("schemaVersion", schemaVersion)
                        put("basisVollsicherungId", vollsicherungId)
                    }
                ZipOutputStream(FileOutputStream(ziel).buffered()).use { zos ->
                    schreibeEintrag(zos, MANIFEST_ENTRY, manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zos.putNextEntry(ZipEntry(TEIL_DATENBANK_ENTRY))
                    FileInputStream(teilDb).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                erfolgreich = true
                ziel
            } finally {
                teilDb.delete()
                if (!erfolgreich) ziel.delete()
            }
        }

    /**
     * Wie [baueSicherungsDatei], legt die Zieldatei aber selbst per [File.createTempFile] im
     * `cacheDir` an - fuer Aufrufer wie die automatische Drive-Sicherung
     * ([com.example.lrmprotokoll.AppContainer]s `datenbankSicherungQuelle`), die nur an dem
     * fertigen [File] interessiert sind, nicht an dessen Lebenszyklus bei einem Fehlschlag.
     *
     * Nachbesserung (Review-Befund zu #194, 24.09.2026): Vorher legte der Aufrufer die Temp-Datei
     * selbst per `createTempFile` an und rief [baueSicherungsDatei] direkt auf - warf DAS
     * unterwegs (Platzmangel, I/O-Fehler mitten im Schreiben), blieb die bereits angelegte, leere
     * oder teilbeschriebene Temp-Datei fuer immer im `cacheDir` liegen: Die Lambda gab in diesem
     * Fall nie ein [File] zurueck, also bekam
     * [com.example.lrmprotokoll.drive.DriveSyncCoordinator.ladeDatenbankSicherungHoch] auch nie
     * etwas, das es in seinem eigenen `finally` haette loeschen koennen. Auf einem Geraet, das
     * wiederholt an Speicherproblemen scheitert (genau der Fall, den der Streaming-Umbau oben
     * beheben soll), hinterliess das bei jedem Fehlschlag eine Leiche. Diese Funktion kapselt
     * Anlegen + Bauen + Aufraeumen-bei-Fehlschlag an einer Stelle, damit kein Aufrufer das
     * Zusammenspiel selbst nachbauen muss.
     */
    suspend fun baueSicherungsDateiMitAufraeumen(
        context: Context,
        settings: SettingsManager,
        freierPlatzErmitteln: (File) -> Long = { it.usableSpace },
    ): File {
        val ziel = File.createTempFile("drive_sicherung_", ".zip", context.cacheDir)
        var erfolgreich = false
        try {
            baueSicherungsDatei(context, settings, ziel, freierPlatzErmitteln)
            erfolgreich = true
            return ziel
        } finally {
            // Nur bei einem Fehlschlag aufraeumen - im Erfolgsfall gehoert [ziel] jetzt dem
            // Aufrufer (siehe KDoc oben), der es nach seinem eigenen Upload-Versuch loescht.
            if (!erfolgreich) ziel.delete()
        }
    }

    suspend fun spieleSicherungEin(
        context: Context,
        quelle: Uri,
        settings: SettingsManager,
    ): SicherungsErgebnis = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(quelle)
            ?: return@withContext SicherungsErgebnis(false, "Sicherungsdatei konnte nicht geöffnet werden.")
        input.use { spieleZipStreamEin(context, it, settings) }
    }

    /**
     * Wie [spieleSicherungEin], aber für eine bereits lokal vorliegende Datei statt eines
     * SAF-[Uri] - der Weg für eine aus Drive heruntergeladene Sicherung (siehe
     * [com.example.lrmprotokoll.drive.DriveDatenbankSicherung.herunterladen], das seit dem
     * Streaming-Umbau selbst in eine Datei statt eines `ByteArray` herunterlädt). Ersetzt das
     * frühere `spieleSicherungBytesEin(zipBytes: ByteArray)`.
     */
    suspend fun spieleSicherungDateiEin(
        context: Context,
        quelle: File,
        settings: SettingsManager,
        teilsicherung: File? = null,
    ): SicherungsErgebnis = withContext(Dispatchers.IO) {
        try {
            FileInputStream(quelle).buffered().use { spieleZipStreamEin(context, it, settings, teilsicherung) }
        } catch (e: Exception) {
            SicherungsErgebnis(false, "Wiederherstellung fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Kern von [spieleSicherungEin]/[spieleSicherungDateiEin]: liest [zipStream] als
     * [ZipInputStream], OHNE die Reihenfolge der Einträge vorauszusetzen - der Datenbank-Eintrag
     * kann vor oder nach dem Manifest stehen, ein [ZipInputStream] kann nicht zurückspulen, jeder
     * Eintrag muss beim ersten Durchlauf verarbeitet werden. Manifest/Einstellungen sind klein
     * und dürfen im Speicher landen, der Datenbank-Eintrag wird in eine temporäre Datei
     * gestreamt.
     *
     * Erst NACH erfolgreicher Manifest-Prüfung wird die laufende Datenbank angefasst
     * ([schreibeDatenbankDatei]) - eine ungültige Sicherung darf den laufenden Zustand nie
     * berühren. Die temporäre Datenbank-Datei wird in JEDEM Fall (Erfolg wie Fehlschlag) im
     * `finally` gelöscht.
     */
    private suspend fun spieleZipStreamEin(
        context: Context,
        zipStream: InputStream,
        settings: SettingsManager,
        teilsicherung: File? = null,
    ): SicherungsErgebnis {
        var manifest: JSONObject? = null
        var einstellungen: JSONObject? = null
        var datenbankTempDatei: File? = null
        try {
            ZipInputStream(zipStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        MANIFEST_ENTRY -> manifest = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        EINSTELLUNGEN_ENTRY -> einstellungen = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        DATENBANK_ENTRY -> {
                            val temp = File.createTempFile("wiederherstellung_", ".db", context.cacheDir)
                            FileOutputStream(temp).use { out -> zis.copyTo(out) }
                            datenbankTempDatei = temp
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val gueltigesManifest = manifest
                ?: return SicherungsErgebnis(false, "Keine gültige Sicherungsdatei (manifest.json fehlt).")
            val formatVersion = gueltigesManifest.optInt("formatVersion", -1)
            if (formatVersion != SICHERUNG_FORMAT_VERSION) {
                return SicherungsErgebnis(
                    false,
                    "Sicherung hat ein unbekanntes Format (Version $formatVersion, erwartet " +
                        "$SICHERUNG_FORMAT_VERSION) - vermutlich von einer inkompatiblen App-Version.",
                )
            }
            val dbDatei = datenbankTempDatei
                ?: return SicherungsErgebnis(false, "Keine gültige Sicherungsdatei (Datenbank fehlt).")

            val wiederhergestellt = schreibeDatenbankDatei(context, dbDatei)
            // schreibeDatenbankDatei() hat die Datei uebernommen (verschoben oder kopiert+geloescht) -
            // im finally unten ist nichts mehr zu tun.
            datenbankTempDatei = null
            einstellungen?.let { wendeEinstellungenAn(it, settings) }

            val teilHinweis =
                teilsicherung?.let { spieleTeilsicherungEin(context, it, gueltigesManifest, wiederhergestellt) }.orEmpty()
            return SicherungsErgebnis(true, "Sicherung eingespielt.$teilHinweis Die App wird jetzt neu gestartet.")
        } catch (e: Exception) {
            return SicherungsErgebnis(false, "Wiederherstellung fehlgeschlagen: ${e.message}")
        } finally {
            datenbankTempDatei?.delete()
        }
    }

    /**
     * Hard-Restart des Prozesses (nicht nur der Activity): [AppContainer][com.example.lrmprotokoll.AppContainer]
     * und alle bereits laufenden Services halten Referenzen auf die VOR der Wiederherstellung
     * geöffnete [AppDatabase]-Instanz - ein einfacher Recreate der Activity würde diese nicht
     * ersetzen. Nur ein echter Prozess-Neustart öffnet [AppDatabase.getDatabase] wieder frisch.
     */
    fun starteNeustart(context: Context) {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val restartIntent = Intent.makeRestartActivityTask(launchIntent.component)
        context.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun schreibeEintrag(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
    }

    private fun buildManifest(): JSONObject {
        val json = JSONObject()
        json.put("formatVersion", SICHERUNG_FORMAT_VERSION)
        // Saubere Versionskennung (docs/PROMPT_VERSIONSKENNUNG.md Abschnitt 4.6): appVersionName
        // traegt jetzt die volle Kennung (Basisversion + PR/CI/SHA bzw. lokaler Git-Stand) statt
        // nur "1.0"/"1.0.0" - geprueft, dass spieleZipStreamEin() dieses Feld nirgends parst
        // oder vergleicht, nur formatVersion. appVersionCode bleibt bewusst numerisch.
        json.put("appVersionName", Versionskennung.aktuelleKennung())
        json.put("appVersionCode", BuildConfig.VERSION_CODE)
        json.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        return json
    }

    /**
     * WAL-Checkpoint erzwingen, dann die (jetzt konsistente) Datenbankdatei zurückgeben - OHNE
     * sie zu lesen. Ersetzt das frühere `leseDatenbankNachCheckpoint()`, das die ganze Datei per
     * `readBytes()` einlas.
     *
     * db.path statt context.getDatabasePath(NAME): garantiert dieselbe Datei wie die gerade
     * gecheckpointete Verbindung. Ein unabhaengig ueber den Namen neu aufgeloester Pfad koennte
     * theoretisch abweichen, wenn INSTANCE schon gegen einen anderen Kontext/Pfad geoeffnet
     * wurde (beobachtet unter Robolectric, wo mehrere Testklassen denselben Prozess, aber
     * unterschiedliche Sandbox-Verzeichnisse teilen).
     */
    private fun checkpointeDatenbankDatei(context: Context): File {
        val writableDb = AppDatabase.getDatabase(context).openHelper.writableDatabase
        writableDb.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        return File(writableDb.path!!)
    }

    /**
     * Spielt die kumulative Teilsicherung [teil] in die eben aus der Vollsicherung
     * wiederhergestellte Datei [dbDatei] ein - nur, wenn sie zu genau dieser Vollsicherung und
     * derselben Schema-Version gehoert. Passt sie nicht oder scheitert das Einspielen, bleibt der
     * Stand der Vollsicherung (das Einspielen laeuft in einer Transaktion). Liefert einen Satz
     * fuer die Meldung an den Nutzer.
     */
    private fun spieleTeilsicherungEin(
        context: Context,
        teil: File,
        vollManifest: JSONObject,
        dbDatei: File,
    ): String {
        var teilManifest: JSONObject? = null
        val teilDb = File.createTempFile("teil_wiederherstellung_", ".db", context.cacheDir)
        try {
            var dbGefunden = false
            ZipInputStream(FileInputStream(teil).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        MANIFEST_ENTRY -> teilManifest = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        TEIL_DATENBANK_ENTRY -> {
                            FileOutputStream(teilDb).use { out -> zis.copyTo(out) }
                            dbGefunden = true
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val m = teilManifest
            val passt =
                dbGefunden &&
                    m != null &&
                    m.optString("art") == ART_TEIL &&
                    m.optLong("basisVollsicherungId", -1L) == vollManifest.optLong("vollsicherungId", -2L) &&
                    m.optInt("schemaVersion", -1) == vollManifest.optInt("schemaVersion", -2)
            if (!passt) return " Die Teilsicherung gehörte nicht zu dieser Vollsicherung und wurde übergangen."
            SicherungsDatenbank.spieleTeilsicherungEin(dbDatei, teilDb)
            return " Die Änderungen seit der Vollsicherung (Teilsicherung vom ${m!!.optString("createdAt")}) sind enthalten."
        } catch (e: Exception) {
            return " Die Teilsicherung ließ sich nicht einspielen (${e.message}); es gilt der Stand der Vollsicherung."
        } finally {
            teilDb.delete()
        }
    }

    /**
     * Die laufende Room-Instanz hält eine offene Verbindung zur alten Datei - deshalb erst
     * schließen, dann überschreiben. [starteNeustart] danach ist zwingend, sonst würde die
     * nächste Anfrage über die bereits geöffnete, jetzt veraltete Verbindung laufen.
     *
     * Nimmt eine Quelldatei statt eines `ByteArray` (Bugfix 23.09.2026) - [quelle] wird per
     * [File.renameTo] verschoben, falls moeglich (beide Dateien liegen unter derselben
     * App-internen Ablage, i.d.R. also demselben Dateisystem - `renameTo` ist dann eine reine
     * Metadaten-Operation ohne Datenkopie), sonst per [File.copyTo] (streamt mit Standardpuffer,
     * kein `readBytes()`) kopiert und die Quelle danach geloescht.
     */
    private fun schreibeDatenbankDatei(
        context: Context,
        quelle: File,
    ): File {
        // Pfad VOR dem Schliessen abfragen (siehe Begründung in checkpointeDatenbankDatei) -
        // danach ist die Verbindung weg.
        val database = AppDatabase.getDatabase(context)
        val dbPath = database.openHelper.writableDatabase.path!!
        database.close()
        AppDatabase.resetInstance()
        val dbFile = File(dbPath)
        dbFile.parentFile?.mkdirs()
        if (!quelle.renameTo(dbFile)) {
            quelle.copyTo(dbFile, overwrite = true)
            quelle.delete()
        }
        // Alte WAL-/SHM-Beileger der VORHERIGEN Datenbank dürfen nicht überleben - sie gehören
        // zu einem anderen Dateiinhalt und würden beim nächsten Öffnen mit den neu eingespielten
        // Daten kollidieren.
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        return dbFile
    }
}
