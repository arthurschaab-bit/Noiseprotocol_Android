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

/** Erhöhen, wenn sich [buildEinstellungenJson]/[wendeEinstellungenAn] inkompatibel ändern. */
internal const val SICHERUNG_FORMAT_VERSION = 1

/**
 * Sicherheitsaufschlag auf die reine Datenbankgröße für den Platzbedarf der Sicherungs-ZIP
 * (Bugfix, Owner-Entscheidung 23.09.2026: "streamend reparieren" -
 * docs/PROMPT_FIX_DATENBANK_SICHERUNG.md Schritt 1). ZIP-Header/Manifest/Einstellungen sind
 * verschwindend klein gegen eine ~500-MB-Datenbank; die 10% Puffer fangen das plus etwas
 * Dateisystem-Overhead ab, ohne unnötig konservativ zu sein.
 */
private const val SICHERUNG_SPEICHERPLATZ_FAKTOR = 1.1

data class SicherungsErgebnis(val erfolg: Boolean, val nachricht: String)

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
) : IOException("Zu wenig Speicherplatz für die Sicherung: frei $freierPlatz Bytes, nötig $benoetigterPlatz Bytes.")

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
    ) {
        withContext(Dispatchers.IO) {
            val dbDatei = checkpointeDatenbankDatei(context)

            // Vorab prüfen, BEVOR ueberhaupt etwas geschrieben wird (Schritt 1) - eine zu spaete
            // Pruefung liesse eine halb geschriebene Zieldatei zurueck.
            val benoetigterPlatz = (dbDatei.length() * SICHERUNG_SPEICHERPLATZ_FAKTOR).toLong()
            val zielVerzeichnis = ziel.absoluteFile.parentFile ?: ziel.absoluteFile
            val freierPlatz = freierPlatzErmitteln(zielVerzeichnis)
            if (freierPlatz < benoetigterPlatz) {
                throw UnzureichenderSpeicherplatzException(freierPlatz, benoetigterPlatz)
            }

            ziel.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(ziel).buffered()).use { zos ->
                schreibeEintrag(zos, MANIFEST_ENTRY, buildManifest().toString(2).toByteArray(Charsets.UTF_8))
                schreibeEintrag(
                    zos,
                    EINSTELLUNGEN_ENTRY,
                    buildEinstellungenJson(settings).toString(2).toByteArray(Charsets.UTF_8),
                )
                zos.putNextEntry(ZipEntry(DATENBANK_ENTRY))
                FileInputStream(dbDatei).use { it.copyTo(zos) }
                zos.closeEntry()
            }
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
    ): SicherungsErgebnis = withContext(Dispatchers.IO) {
        try {
            FileInputStream(quelle).buffered().use { spieleZipStreamEin(context, it, settings) }
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

            schreibeDatenbankDatei(context, dbDatei)
            // schreibeDatenbankDatei() hat die Datei uebernommen (verschoben oder kopiert+geloescht) -
            // im finally unten ist nichts mehr zu tun.
            datenbankTempDatei = null
            einstellungen?.let { wendeEinstellungenAn(it, settings) }

            return SicherungsErgebnis(true, "Sicherung eingespielt. Die App wird jetzt neu gestartet.")
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
    private fun schreibeDatenbankDatei(context: Context, quelle: File) {
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
    }
}
