package com.example.lrmprotokoll.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SettingsManager
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
        // Nicht ntfyTopic: seit Befund 05/C-6 bewusst nicht Teil der Sicherung (siehe
        // SicherungEinstellungenTest.ntfyGeheimnisseWerdenNichtInDieSicherungAufgenommen).
        quellSettings.driveFolderName = "Sicherung-Test-Ordner"

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
        assertEquals("Sicherung-Test-Ordner", zielSettings.driveFolderName)
    }

    /**
     * PROMPT_FIX_DATENBANK_SICHERUNG.md Test 1 (Roundtrip): [SicherungManager.baueSicherungsDatei]
     * direkt auf eine Datei statt eines SAF-[Uri] - der Weg, den die automatische Drive-Sicherung
     * ([com.example.lrmprotokoll.drive.DriveDatenbankSicherung]) seit dem Streaming-Umbau nutzt.
     * Belegt zusaetzlich zum Roundtrip die beiden im Prompt genannten Struktur-Anforderungen:
     * die ZIP hat genau die drei bekannten Eintraege, und der Datenbank-Eintrag ist BYTEWEISE
     * identisch zur Datenbankdatei nach dem Checkpoint. Ersetzt das fruehere, auf
     * `baueSicherungsBytes()`/`spieleSicherungBytesEin(ByteArray)` aufsetzende
     * `baueUndSpieleSicherungsBytesEinUebertraegtAufnahmen` - beide Funktionen sind mit dem
     * Streaming-Umbau entfallen.
     */
    @Test
    fun baueUndSpieleSicherungsDateiEinUebertraegtAufnahmenUndPruefBytegleicheDatenbank() = runTest {
        AppDatabase.resetInstance()
        val dao = AppDatabase.getDatabase(context).noiseDao()
        val eindeutigerZeitstempel = 1_800_100_000_000L + System.nanoTime() % 1_000_000L
        dao.insert(
            NoiseRecord(
                timestamp = eindeutigerZeitstempel,
                amplitude = 0.0,
                dbValue = 65.0,
                filePath = "",
                label = "SicherungManagerTest-Datei-Marker",
            )
        )

        val zielZip = File(context.cacheDir, "sicherung_datei_${System.nanoTime()}.zip")
        SicherungManager.baueSicherungsDatei(context, neueSettings(), zielZip)
        assertTrue(zielZip.exists() && zielZip.length() > 0)

        // Die DB-Datei jetzt separat einlesen, um sie byteweise mit dem ZIP-Eintrag zu
        // vergleichen - baueSicherungsDatei() hat gerade erst per PRAGMA wal_checkpoint(FULL)
        // dafuer gesorgt, dass sie konsistent ist; ein zweiter Checkpoint hier waere ein
        // No-op auf einer zwischenzeitlich unveraenderten Datenbank.
        val writableDatabase = AppDatabase.getDatabase(context).openHelper.writableDatabase
        val dbDatei = File(writableDatabase.path!!)
        val erwarteteDbBytes = dbDatei.readBytes()

        val eintragsNamen = mutableListOf<String>()
        var dbEintragsBytes: ByteArray? = null
        java.util.zip.ZipInputStream(zielZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                eintragsNamen += entry.name
                if (entry.name == "noise_database") dbEintragsBytes = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertEquals(setOf("manifest.json", "settings.json", "noise_database"), eintragsNamen.toSet())
        assertArrayEquals(
            "Der DB-Eintrag muss byteweise der DB-Datei nach dem Checkpoint entsprechen",
            erwarteteDbBytes,
            dbEintragsBytes,
        )

        val vorhandeneEintraege = dao.getAlleAktiven()
        val treffer = vorhandeneEintraege.filter { it.timestamp == eindeutigerZeitstempel }
        dao.deleteMultiple(treffer.map { it.id })
        assertTrue(dao.getAlleAktiven().none { it.timestamp == eindeutigerZeitstempel })

        val restoreErgebnis = SicherungManager.spieleSicherungDateiEin(context, zielZip, neueSettings())
        assertTrue(restoreErgebnis.nachricht, restoreErgebnis.erfolg)

        val wiederhergestellteDao = AppDatabase.getDatabase(context).noiseDao()
        assertTrue(wiederhergestellteDao.getAlleAktiven().any { it.timestamp == eindeutigerZeitstempel })
    }

    /**
     * PROMPT_FIX_DATENBANK_SICHERUNG.md Test 2: eine Sicherung, wie sie der ALTE, nicht
     * streamende Code baute - alle drei Eintraege ueber einen einzigen
     * `ByteArrayOutputStream`/`ZipOutputStream`, wie es das fruehere `baueSicherungsBytes()` tat -
     * muss mit dem neuen, streamenden Code weiterhin einspielbar sein: Das Sicherungsformat
     * aendert sich nicht (SICHERUNG_FORMAT_VERSION, Eintragsnamen). Bewusst in einer vom
     * Produktivcode ABWEICHENDEN Reihenfolge gebaut (Datenbank vor Manifest vor Einstellungen) -
     * der Plan verlangt ausdruecklich, keine Reihenfolge der ZIP-Eintraege vorauszusetzen
     * (Schritt 2), weil ein [java.util.zip.ZipInputStream] nicht zurueckspulen kann.
     */
    @Test
    fun eineAlteSicherungOhneStreamingBleibtMitDemNeuenCodeEinspielbar() = runTest {
        AppDatabase.resetInstance()
        val dao = AppDatabase.getDatabase(context).noiseDao()
        val marker = 1_800_300_000_000L + System.nanoTime() % 1_000_000L
        dao.insert(NoiseRecord(timestamp = marker, amplitude = 0.0, dbValue = 70.0, filePath = "", label = "Alte-Sicherung-Marker"))

        val writableDb = AppDatabase.getDatabase(context).openHelper.writableDatabase
        writableDb.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val dbBytes = File(writableDb.path!!).readBytes()
        val manifestJson = org.json.JSONObject().apply {
            put("formatVersion", 1)
            put("appVersionName", "alt-1.0")
            put("appVersionCode", 1)
            put("createdAt", "2026-01-01T00:00:00Z")
        }
        val manifestBytes = manifestJson.toString(2).toByteArray(Charsets.UTF_8)
        val settingsJson = org.json.JSONObject()
        val settingsBytes = settingsJson.toString(2).toByteArray(Charsets.UTF_8)

        // Wie das alte baueSicherungsBytes(): ALLES ueber einen ByteArrayOutputStream, dann als
        // Ganzes in eine Datei geschrieben - bewusst NICHT ueber baueSicherungsDatei(), um
        // wirklich den ALTEN Baupfad nachzubilden (siehe Test 7: im PRODUKTIONSCODE darf das
        // nicht mehr vorkommen, hier ist es bewusst Testcode).
        val zip = File(context.cacheDir, "alte_sicherung_${System.nanoTime()}.zip")
        val puffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(puffer).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("noise_database"))
            zos.write(dbBytes)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zos.write(manifestBytes)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("settings.json"))
            zos.write(settingsBytes)
            zos.closeEntry()
        }
        zip.writeBytes(puffer.toByteArray())

        val treffer = dao.getAlleAktiven().filter { it.timestamp == marker }
        dao.deleteMultiple(treffer.map { it.id })
        assertTrue(dao.getAlleAktiven().none { it.timestamp == marker })

        val ergebnis = SicherungManager.spieleSicherungEin(context, Uri.fromFile(zip), neueSettings())

        assertTrue(ergebnis.nachricht, ergebnis.erfolg)
        val wiederhergestellteDao = AppDatabase.getDatabase(context).noiseDao()
        assertTrue(wiederhergestellteDao.getAlleAktiven().any { it.timestamp == marker })
    }

    /**
     * PROMPT_FIX_DATENBANK_SICHERUNG.md Test 3: Fehlt das Manifest oder hat es eine falsche
     * formatVersion, bleibt die laufende Datenbank unangetastet UND die temporaere
     * Wiederherstellungsdatei ist danach weg. [manifestMitFalscherFormatVersionWirdAbgelehnt]/
     * [sicherungOhneManifestWirdAbgelehnt] oben belegen bereits die Ablehnung selbst, aber ihre
     * Test-ZIPs enthalten keinen Datenbank-Eintrag - hier zusaetzlich MIT einem, um wirklich zu
     * pruefen, dass [SicherungManager] die dafuer angelegte temporaere Datei wieder loescht.
     */
    @Test
    fun ungueltigeSicherungLoeschtIhreTemporaereDatenbankdateiWiederUndLaesstDieLaufendeUnangetastet() = runTest {
        AppDatabase.resetInstance()
        val dao = AppDatabase.getDatabase(context).noiseDao()
        val marker = 1_800_200_000_000L + System.nanoTime() % 1_000_000L
        dao.insert(NoiseRecord(timestamp = marker, amplitude = 0.0, dbValue = 60.0, filePath = "", label = "Unberuehrt-Marker"))

        val tempVorher = context.cacheDir.listFiles { f -> f.name.startsWith("wiederherstellung_") }?.size ?: 0

        val zip = File(context.cacheDir, "kaputt_${System.nanoTime()}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("noise_database"))
            zos.write(ByteArray(50_000) { it.toByte() }) // gross genug fuer eine eigene Temp-Datei
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            val ungueltigesManifest = org.json.JSONObject().put("formatVersion", 999)
            zos.write(ungueltigesManifest.toString().toByteArray())
            zos.closeEntry()
        }

        val ergebnis = SicherungManager.spieleSicherungEin(context, Uri.fromFile(zip), neueSettings())

        assertFalse(ergebnis.erfolg)
        assertTrue(
            "Die laufende Datenbank darf bei einer ungueltigen Sicherung nicht angetastet werden",
            dao.getAlleAktiven().any { it.timestamp == marker },
        )
        val tempNachher = context.cacheDir.listFiles { f -> f.name.startsWith("wiederherstellung_") }?.size ?: 0
        assertEquals("Keine liegen gebliebene temporaere Wiederherstellungsdatei", tempVorher, tempNachher)
    }

    /**
     * PROMPT_FIX_DATENBANK_SICHERUNG.md Schritt 1 ("Vorab den Speicherplatz pruefen") + Test 6:
     * die eigentliche Pruefung in [SicherungManager.baueSicherungsDatei] - ueber die injizierte
     * `freierPlatzErmitteln`-Funktion simuliert, ohne wirklich hunderte MB Platz belegen zu
     * muessen. Die Ergebnis-Seite (BACKUP_CREATE_FAILED-Meldung, kein Upload,
     * datenbankSicherungLastAttemptAt trotzdem gesetzt) deckt
     * `DriveSyncCoordinatorTest.platzmangelBeimBauenMeldetBackupCreateFailedUndLaedtNichtsHoch`
     * ab - hier geht es nur um die Pruefung selbst.
     */
    @Test
    fun baueSicherungsDateiBrichtBeiZuWenigSpeicherplatzAbUndSchreibtNichts() = runTest {
        AppDatabase.resetInstance()
        AppDatabase.getDatabase(context) // sicherstellen, dass die DB-Datei existiert
        val ziel = File(context.cacheDir, "kein_platz_${System.nanoTime()}.zip")

        val ausnahme = try {
            SicherungManager.baueSicherungsDatei(context, neueSettings(), ziel, freierPlatzErmitteln = { 1L })
            null
        } catch (e: UnzureichenderSpeicherplatzException) {
            e
        }

        assertTrue("Muss mit UnzureichenderSpeicherplatzException abbrechen", ausnahme != null)
        assertTrue(
            "Die Fehlermeldung muss den Speicherplatz-Grund erkennen lassen",
            ausnahme!!.message?.contains("Speicherplatz") == true,
        )
        assertFalse("Bei zu wenig Platz darf keine (halbfertige) Zieldatei zurueckbleiben", ziel.exists())
    }
}
