package com.example.lrmprotokoll.messreihe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TAG_MS = 24L * 60 * 60 * 1000

/**
 * Das tatsaechliche Loeschen (Owner-Entscheidung E8) gegen ein echtes Verzeichnis - die
 * Auswahllogik ist in [SpeicheraufraeumerTest] ohne Dateisystem geprueft, hier geht es darum,
 * dass genau die ausgewaehlten Dateien verschwinden und keine anderen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeicherAufraeumenDateiTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val verzeichnis: File get() = context.getExternalFilesDir(null)!!

    @Before
    fun leereVerzeichnis() {
        // Robolectric behaelt das Sandbox-Verzeichnis ueber Testmethoden hinweg - ohne dieses
        // Aufraeumen wuerden sich die Faelle gegenseitig verfaelschen.
        verzeichnis.listFiles()?.forEach { it.delete() }
    }

    private fun lege(name: String, bytes: Int, alterTage: Long): File {
        val datei = File(verzeichnis, name)
        datei.writeBytes(ByteArray(bytes))
        datei.setLastModified(System.currentTimeMillis() - alterTage * TAG_MS)
        return datei
    }

    @Test
    fun nurDieGewaehlteKategorieVerschwindet() = runTest {
        val wav = lege("alt.wav", 1000, 40)
        val video = lege("video.mp4", 5000, 40)
        val bericht = lege("Tagesbericht.pdf", 200, 40)

        val ergebnis = raeumeSpeicherAuf(context, setOf(Speicherkategorie.AUDIO), aelterAlsTage = null)

        assertEquals(1, ergebnis.geloescht)
        assertEquals(1000L, ergebnis.bytes)
        assertEquals(0, ergebnis.fehlgeschlagen)
        assertFalse(wav.exists())
        assertTrue("Videos waren nicht gewaehlt", video.exists())
        assertTrue("Berichte sind nie loeschbar", bericht.exists())
    }

    @Test
    fun derZeitraumWirdEingehalten() = runTest {
        val alt = lege("alt.wav", 1000, 100)
        val neu = lege("neu.wav", 1000, 5)

        raeumeSpeicherAuf(context, setOf(Speicherkategorie.AUDIO), aelterAlsTage = 30)

        assertFalse(alt.exists())
        assertTrue("Innerhalb des Aufbewahrungszeitraums bleibt alles", neu.exists())
    }

    @Test
    fun eineFrischeDateiUeberlebtAuchDenAllesLoeschenLauf() = runTest {
        // Waehrend einer laufenden Messung schreibt der Dienst gerade an dieser Datei.
        val laufend = lege("laeuft.wav", 1000, alterTage = 0)

        val ergebnis = raeumeSpeicherAuf(context, setOf(Speicherkategorie.AUDIO), aelterAlsTage = null)

        assertEquals(0, ergebnis.geloescht)
        assertTrue(laufend.exists())
    }

    @Test
    fun videoUndSeineTonspurVerschwindenGemeinsam() = runTest {
        val mp4 = lege("video_1.mp4", 9000, 40)
        val pcm = lege("video_1.pcm", 3000, 40)

        val ergebnis = raeumeSpeicherAuf(context, setOf(Speicherkategorie.VIDEO), aelterAlsTage = null)

        assertEquals(2, ergebnis.geloescht)
        assertEquals(12_000L, ergebnis.bytes)
        assertFalse(mp4.exists())
        assertFalse(pcm.exists())
    }

    @Test
    fun dieVorschauNenntGenauDasWasDannGeloeschtWird() = runTest {
        // Vorschau und Ausfuehrung muessen aus derselben Rechnung stammen - sonst loescht die
        // App etwas anderes, als sie angekuendigt hat.
        lege("a.wav", 1000, 40)
        lege("b.wav", 2000, 40)
        lege("jung.wav", 4000, 1)

        val vorschau = ermittleAufraeumVorschau(context, setOf(Speicherkategorie.AUDIO), aelterAlsTage = 30)
        val ergebnis = raeumeSpeicherAuf(context, setOf(Speicherkategorie.AUDIO), aelterAlsTage = 30)

        assertEquals(vorschau.anzahl, ergebnis.geloescht)
        assertEquals(vorschau.bytes, ergebnis.bytes)
        assertEquals(2, ergebnis.geloescht)
    }

    @Test
    fun ohneAuswahlPassiertNichts() = runTest {
        val wav = lege("alt.wav", 1000, 40)

        val ergebnis = raeumeSpeicherAuf(context, emptySet(), aelterAlsTage = null)

        assertEquals(0, ergebnis.geloescht)
        assertTrue(wav.exists())
    }

    @Test
    fun dieBelegungZeigtAlleKategorienUndDenRest() = runTest {
        lege("a.wav", 1000, 40)
        lege("v.mp4", 5000, 40)
        lege("f.jpg", 300, 40)
        lege("bericht.pdf", 200, 40)

        val belegung = ermittleSpeicherbelegung(context)

        assertEquals(1000L, belegung.posten(Speicherkategorie.AUDIO).bytes)
        assertEquals(5000L, belegung.posten(Speicherkategorie.VIDEO).bytes)
        assertEquals(300L, belegung.posten(Speicherkategorie.FOTO).bytes)
        assertEquals(200L, belegung.sonstigesBytes)
    }
}
