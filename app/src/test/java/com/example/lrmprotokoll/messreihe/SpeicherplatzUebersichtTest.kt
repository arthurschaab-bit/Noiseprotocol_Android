package com.example.lrmprotokoll.messreihe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.NoiseRecord
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M10_FUNKTIONEN.md F5: [formatiereBytes] ist reine Formatierung, [ermittleSpeicherplatz]/
 * [ermittleRetentionVorschau] brauchen echtes Datei-I/O bzw. eine echte Room-DB gegen ein
 * Robolectric-Sandbox-Verzeichnis - dasselbe Muster wie
 * [com.example.lrmprotokoll.diagnose.export.SupportBundleExporterTest] bzw.
 * [com.example.lrmprotokoll.data.MeasurementDaoTest]. Nicht-in-Memory-DB persistiert über
 * Testmethoden hinweg (siehe MeasurementDaoTest-KDoc) - deshalb weit auseinanderliegende
 * Zeitstempel je Testmethode, damit sich Kandidaten nicht gegenseitig verfälschen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeicherplatzUebersichtTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun formatiereBytesUnterEinemKilobyteBleibtInBytes() {
        assertEquals("512 B", formatiereBytes(512))
    }

    @Test
    fun formatiereBytesRechnetInKilobyteUm() {
        assertEquals("2.0 KB", formatiereBytes(2048))
    }

    @Test
    fun formatiereBytesRechnetInMegabyteUm() {
        assertEquals("1.5 MB", formatiereBytes((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun ermittleSpeicherplatzZaehltNurWavDateienAlsAudio() = runTest {
        val audioDir = context.getExternalFilesDir(null)!!
        File(audioDir, "aufnahme1.wav").writeBytes(ByteArray(1000))
        File(audioDir, "aufnahme2.wav").writeBytes(ByteArray(2000))
        // Gegenprobe: eine Nicht-WAV-Datei (z.B. ein Tagesbericht) darf NICHT als Audio zaehlen.
        File(audioDir, "Tagesbericht_01.01.2026.txt").writeBytes(ByteArray(5000))

        val ergebnis = ermittleSpeicherplatz(context)

        assertEquals(3000L, ergebnis.audioBytes)
    }

    @Test
    fun ermittleRetentionVorschauZaehltNurUngeschuetzteAelteneAufnahmen() = runTest {
        val dao = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database.noiseDao()
        val jetzt = System.currentTimeMillis()
        val vorZehnTagen = jetzt - 10L * 24 * 60 * 60 * 1000
        val vorEinemTag = jetzt - 1L * 24 * 60 * 60 * 1000

        fun record(timestamp: Long, label: String?, favorite: Boolean, filePath: String) = NoiseRecord(
            timestamp = timestamp,
            amplitude = 0.0,
            filePath = filePath,
            label = label,
            favorite = favorite,
        )

        // Kandidat: alt genug, kein Label, kein Favorit.
        dao.insert(record(vorZehnTagen, label = null, favorite = false, filePath = "retvorschau_kandidat.wav"))
        // Kein Kandidat: zu jung.
        dao.insert(record(vorEinemTag, label = null, favorite = false, filePath = "retvorschau_zu_jung.wav"))
        // Kein Kandidat: Favorit geschützt, obwohl alt genug.
        dao.insert(record(vorZehnTagen, label = null, favorite = true, filePath = "retvorschau_favorit.wav"))
        // Kein Kandidat: gelabelt, obwohl alt genug.
        dao.insert(record(vorZehnTagen, label = "Bohren", favorite = false, filePath = "retvorschau_label.wav"))

        val vorschau = ermittleRetentionVorschau(dao, aufbewahrungsTage = 7)

        assertEquals(1, vorschau.anzahlAufnahmen)
    }
}
