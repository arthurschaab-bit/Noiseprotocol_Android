package com.example.lrmprotokoll.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SettingsManager
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M10_FUNKTIONEN.md F13: End-zu-Ende gegen echte Datei-I/O (Robolectric-Sandbox,
 * `file://`-URIs statt einem echten SAF-Picker - dasselbe Prinzip wie
 * [com.example.lrmprotokoll.diagnose.export.SupportBundleExporterTest]) und eine echte Room-DB.
 *
 * [AppDatabase.getDatabase] ist ein Prozess-weites Singleton ([AppDatabase]s companion object,
 * nicht pro Test zurueckgesetzt) - [schreibeDatenbankDatei] setzt es nach einer Wiederherstellung
 * ueber [AppDatabase.resetInstance] zurueck, deshalb muss ausschliesslich ueber
 * `AppDatabase.getDatabase(context)` verifiziert werden, NICHT ueber
 * `LaermprotokollApp.container.database` - dessen `by lazy` wuerde weiterhin die alte,
 * geschlossene Instanz liefern (siehe KDoc an SicherungManager.starteNeustart).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungManagerTest {

    private val context get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

    private fun neueSettings() = SettingsManager(context, securePrefs = null)

    @Test
    fun manifestMitFalscherFormatVersionWirdAbgelehnt() = runTest {
        // Gegenprobe: eine Sicherung mit unbekannter formatVersion darf NICHT eingespielt werden.
        val zip = File(context.cacheDir, "falsche_version_${System.nanoTime()}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zos.write(org.json.JSONObject().put("formatVersion", 999).toString().toByteArray())
            zos.closeEntry()
        }

        val ergebnis = SicherungManager.spieleSicherungEin(context, Uri.fromFile(zip), neueSettings())

        assertFalse(ergebnis.erfolg)
        assertTrue(ergebnis.nachricht.contains("Format"))
    }

    @Test
    fun sicherungOhneManifestWirdAbgelehnt() = runTest {
        val zip = File(context.cacheDir, "kein_manifest_${System.nanoTime()}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("noise_database"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        val ergebnis = SicherungManager.spieleSicherungEin(context, Uri.fromFile(zip), neueSettings())

        assertFalse(ergebnis.erfolg)
    }

    @Test
    fun erstelleUndSpieleSicherungEinUebertraegtAufnahmenUndEinstellungen() = runTest {
        // AppDatabase.INSTANCE ist ein JVM-weites Singleton, das eine frühere Testklasse im
        // selben Gradle-Testlauf bereits gegen EINEN ANDEREN Robolectric-Sandbox-Kontext
        // geöffnet haben könnte. In echter Produktion gibt es immer nur einen Prozess/Kontext,
        // dort kann das nicht divergieren - hier schon, deshalb hier explizit auf einen
        // garantiert zu DIESEM Testkontext passenden Zustand zurücksetzen, bevor irgendetwas
        // an der Datenbank hängt.
        AppDatabase.resetInstance()
        val dao = AppDatabase.getDatabase(context).noiseDao()
        val eindeutigerZeitstempel = 1_800_000_000_000L + System.nanoTime() % 1_000_000L
        dao.insert(
            NoiseRecord(
                timestamp = eindeutigerZeitstempel,
                amplitude = 0.0,
                dbValue = 71.0,
                filePath = "",
                label = "SicherungManagerTest-Marker",
            )
        )

        val quellSettings = neueSettings()
        quellSettings.ntfyTopic = "sicherung-test-topic"

        val zielDatei = File(context.cacheDir, "sicherung_${System.nanoTime()}.zip")
        val erstellErgebnis = SicherungManager.erstelleSicherung(context, Uri.fromFile(zielDatei), quellSettings)
        assertTrue(erstellErgebnis.nachricht, erstellErgebnis.erfolg)
        assertTrue(zielDatei.exists() && zielDatei.length() > 0)

        // Datenbank "beschaedigen": den Marker-Datensatz entfernen, um zu belegen, dass die
        // Wiederherstellung tatsaechlich den gesicherten Stand zurueckbringt, nicht nur den
        // ohnehin schon vorhandenen.
        dao.deleteMultiple(dao.getAlleAktiven().filter { it.timestamp == eindeutigerZeitstempel }.map { it.id })
        assertTrue(dao.getAlleAktiven().none { it.timestamp == eindeutigerZeitstempel })

        val zielSettings = neueSettings()
        val restoreErgebnis = SicherungManager.spieleSicherungEin(context, Uri.fromFile(zielDatei), zielSettings)
        assertTrue(restoreErgebnis.erfolg)

        // Nicht ueber container.database (by lazy, veraltet) - siehe Klassen-KDoc.
        val wiederhergestellteDao = AppDatabase.getDatabase(context).noiseDao()
        assertTrue(wiederhergestellteDao.getAlleAktiven().any { it.timestamp == eindeutigerZeitstempel })
        assertEquals("sicherung-test-topic", zielSettings.ntfyTopic)
    }
}
