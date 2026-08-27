package com.example.lrmprotokoll.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SettingsManager
import java.io.File
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

data class SicherungsErgebnis(val erfolg: Boolean, val nachricht: String)

/**
 * F13 (PROMPT_M10_FUNKTIONEN.md): Sicherung/Wiederherstellung über das Storage Access Framework,
 * als Ersatz für die bisherige `adb exec-out`-Anleitung (README) - für jeden ohne Terminal
 * unbenutzbar. Enthält eine rohe Kopie der Room-Datenbankdatei (nach WAL-Checkpoint, siehe
 * [leseDatenbankNachCheckpoint]) plus die Nutzer-Einstellungen als Klartext-JSON (siehe
 * `SicherungEinstellungen.kt` für die genaue Feldauswahl und Begründung).
 *
 * Die verschlüsselten Werte (ntfyTopic/-Server, heartbeatUrl, Plan Abschnitt 6) werden über
 * [SettingsManager]s eigene Getter/Setter gelesen/geschrieben, nie die rohe
 * EncryptedSharedPreferences-Datei kopiert - eine kopierte Ciphertext-Datei wäre nach einer
 * Neuinstallation (neuer Android-Keystore-Schlüssel) nicht mehr entschlüsselbar, ein Klartext-
 * Roundtrip über die bestehende API funktioniert dagegen unabhängig vom Keystore-Zustand. Die
 * Kehrseite: diese drei Werte liegen im Sicherungs-JSON als Klartext - die Sicherungsdatei ist
 * deshalb selbst schützenswert, genau wie ein CSV-/PDF-Export.
 */
object SicherungManager {

    suspend fun erstelleSicherung(
        context: Context,
        ziel: Uri,
        settings: SettingsManager,
    ): SicherungsErgebnis = withContext(Dispatchers.IO) {
        try {
            val datenbankBytes = leseDatenbankNachCheckpoint(context)
            val manifestBytes = buildManifest().toString(2).toByteArray(Charsets.UTF_8)
            val einstellungenBytes = buildEinstellungenJson(settings).toString(2).toByteArray(Charsets.UTF_8)

            val output = context.contentResolver.openOutputStream(ziel)
                ?: return@withContext SicherungsErgebnis(false, "Zieldatei konnte nicht geöffnet werden.")
            output.use { out ->
                ZipOutputStream(out).use { zos ->
                    schreibeEintrag(zos, MANIFEST_ENTRY, manifestBytes)
                    schreibeEintrag(zos, EINSTELLUNGEN_ENTRY, einstellungenBytes)
                    schreibeEintrag(zos, DATENBANK_ENTRY, datenbankBytes)
                }
            }
            SicherungsErgebnis(true, "Sicherung erstellt.")
        } catch (e: Exception) {
            SicherungsErgebnis(false, "Sicherung fehlgeschlagen: ${e.message}")
        }
    }

    suspend fun spieleSicherungEin(
        context: Context,
        quelle: Uri,
        settings: SettingsManager,
    ): SicherungsErgebnis = withContext(Dispatchers.IO) {
        try {
            var manifest: JSONObject? = null
            var einstellungen: JSONObject? = null
            var datenbankBytes: ByteArray? = null

            val input = context.contentResolver.openInputStream(quelle)
                ?: return@withContext SicherungsErgebnis(false, "Sicherungsdatei konnte nicht geöffnet werden.")
            input.use { inp ->
                ZipInputStream(inp).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            MANIFEST_ENTRY -> manifest = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                            EINSTELLUNGEN_ENTRY -> einstellungen = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                            DATENBANK_ENTRY -> datenbankBytes = zis.readBytes()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val gueltigesManifest = manifest
                ?: return@withContext SicherungsErgebnis(false, "Keine gültige Sicherungsdatei (manifest.json fehlt).")
            val formatVersion = gueltigesManifest.optInt("formatVersion", -1)
            if (formatVersion != SICHERUNG_FORMAT_VERSION) {
                return@withContext SicherungsErgebnis(
                    false,
                    "Sicherung hat ein unbekanntes Format (Version $formatVersion, erwartet " +
                        "$SICHERUNG_FORMAT_VERSION) - vermutlich von einer inkompatiblen App-Version."
                )
            }
            val dbBytes = datenbankBytes
                ?: return@withContext SicherungsErgebnis(false, "Keine gültige Sicherungsdatei (Datenbank fehlt).")

            schreibeDatenbankDatei(context, dbBytes)
            einstellungen?.let { wendeEinstellungenAn(it, settings) }

            SicherungsErgebnis(true, "Sicherung eingespielt. Die App wird jetzt neu gestartet.")
        } catch (e: Exception) {
            SicherungsErgebnis(false, "Wiederherstellung fehlgeschlagen: ${e.message}")
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
        json.put("appVersionName", BuildConfig.VERSION_NAME)
        json.put("appVersionCode", BuildConfig.VERSION_CODE)
        json.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        return json
    }

    /**
     * WAL-Checkpoint erzwingen, bevor die Datei roh kopiert wird - sonst könnten kürzlich
     * geschriebene, aber noch nicht in die Haupt-Datenbankdatei übernommene Zeilen (im
     * `-wal`-Journal) in der Sicherung fehlen.
     */
    private fun leseDatenbankNachCheckpoint(context: Context): ByteArray {
        // db.path statt context.getDatabasePath(NAME): garantiert dieselbe Datei wie die gerade
        // gecheckpointete Verbindung. Ein unabhaengig ueber den Namen neu aufgeloester Pfad
        // koennte theoretisch abweichen, wenn INSTANCE schon gegen einen anderen Kontext/Pfad
        // geoeffnet wurde (beobachtet unter Robolectric, wo mehrere Testklassen denselben
        // Prozess, aber unterschiedliche Sandbox-Verzeichnisse teilen).
        val writableDb = AppDatabase.getDatabase(context).openHelper.writableDatabase
        writableDb.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        return File(writableDb.path!!).readBytes()
    }

    /**
     * Die laufende Room-Instanz hält eine offene Verbindung zur alten Datei - deshalb erst
     * schließen, dann überschreiben. [starteNeustart] danach ist zwingend, sonst würde die
     * nächste Anfrage über die bereits geöffnete, jetzt veraltete Verbindung laufen.
     */
    private fun schreibeDatenbankDatei(context: Context, bytes: ByteArray) {
        // Pfad VOR dem Schliessen abfragen (siehe Begründung in leseDatenbankNachCheckpoint) -
        // danach ist die Verbindung weg.
        val database = AppDatabase.getDatabase(context)
        val dbPath = database.openHelper.writableDatabase.path!!
        database.close()
        AppDatabase.resetInstance()
        val dbFile = File(dbPath)
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(bytes)
        // Alte WAL-/SHM-Beileger der VORHERIGEN Datenbank dürfen nicht überleben - sie gehören
        // zu einem anderen Dateiinhalt und würden beim nächsten Öffnen mit den neu eingespielten
        // Daten kollidieren.
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }
}
