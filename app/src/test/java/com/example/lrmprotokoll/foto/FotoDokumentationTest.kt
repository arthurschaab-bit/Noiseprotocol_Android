package com.example.lrmprotokoll.foto

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.FotoKategorie
import com.example.lrmprotokoll.data.SessionEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner-Feature-Auftrag 12.09.2026 ("Fotos sollen auch nachträglich über Fotos des Handys
 * hinzugefügt werden können"): [FotoDokumentation.uebernehmeGalerieFoto] und
 * [FotoDokumentation.importiereAusGalerie] muessen ein importiertes Foto durchgaengig mit
 * [com.example.lrmprotokoll.data.DokumentationsFotoEntity.nachtraeglichHinzugefuegt] = `true`
 * speichern - anders als ein per Kamera aufgenommenes Foto ([FotoDokumentation.uebernehmeAufnahme]),
 * das `false` bleiben muss (Beweiskraft-Unterscheidung, Owner-Entscheidung).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FotoDokumentationTest {

    private fun neueSession(): Long {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        return runBlocking {
            container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = 1_700_000_000_000L,
                    endedAt = null,
                    deviceAddress = "AA:BB:CC:DD:EE:FF",
                    deviceName = "PCE-323",
                    weighting = "A",
                    timeWeighting = "F",
                    range = "30-130",
                )
            )
        }
    }

    private fun testDatei(inhalt: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("test_foto_", ".jpg").apply { writeBytes(inhalt); deleteOnExit() }

    @Test
    fun kameraAufnahmeBleibtNachtraeglichHinzugefuegtFalse() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val sessionId = neueSession()

        val id = runBlocking {
            container.fotoDokumentation.uebernehmeAufnahme(testDatei(), sessionId, FotoKategorie.MESSAUFBAU)
        }

        assertNotNull(id)
        val gespeichert = runBlocking { container.fotoDokumentation.fuerSession(sessionId).single() }
        assertFalse(gespeichert.nachtraeglichHinzugefuegt)
    }

    @Test
    fun uebernehmeGalerieFotoSetztDasFlagAufTrue() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val sessionId = neueSession()

        val id = runBlocking {
            container.fotoDokumentation.uebernehmeGalerieFoto(testDatei(), sessionId, FotoKategorie.SONSTIGES)
        }

        assertNotNull(id)
        val gespeichert = runBlocking { container.fotoDokumentation.fuerSession(sessionId).single() }
        assertTrue(gespeichert.nachtraeglichHinzugefuegt)
    }

    @Test
    fun uebernehmeGalerieFotoErlaubtAuchDiePflichtKategorien() {
        // Owner-Entscheidung: der Nutzer darf ein Galerie-Foto frei einer Kategorie zuordnen,
        // auch MESSAUFBAU/KALIBRIERUNG - die Kennzeichnung als "nachtraeglich" ersetzt eine
        // Einschraenkung der Kategorie.
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val sessionId = neueSession()

        val id = runBlocking {
            container.fotoDokumentation.uebernehmeGalerieFoto(testDatei(), sessionId, FotoKategorie.KALIBRIERUNG)
        }

        assertNotNull(id)
        val gespeichert = runBlocking { container.fotoDokumentation.fuerSession(sessionId).single() }
        assertEquals(FotoKategorie.KALIBRIERUNG.name, gespeichert.kategorie)
        assertTrue(gespeichert.nachtraeglichHinzugefuegt)
    }

    @Test
    fun importiereAusGalerieKopiertEineUriUndMarkiertSieAlsNachtraeglich() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val sessionId = neueSession()
        val quelle = testDatei(byteArrayOf(9, 9, 9))
        val uri = Uri.fromFile(quelle)

        val id = runBlocking {
            container.fotoDokumentation.importiereAusGalerie(uri, sessionId, FotoKategorie.SONSTIGES, notiz = "aus Galerie")
        }

        assertNotNull(id)
        val gespeichert = runBlocking { container.fotoDokumentation.fuerSession(sessionId).single() }
        assertTrue(gespeichert.nachtraeglichHinzugefuegt)
        assertEquals("aus Galerie", gespeichert.notiz)
        assertTrue("Zieldatei sollte angelegt worden sein", File(gespeichert.dateiPfad).exists())
    }

    @Test
    fun importiereAusGalerieMitNichtLesbarerUriLiefertNullStattZuWerfen() {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val sessionId = neueSession()
        val nichtLesbareUri = Uri.fromFile(File("/gibt/es/nicht.jpg"))

        val id = runBlocking {
            container.fotoDokumentation.importiereAusGalerie(nichtLesbareUri, sessionId, FotoKategorie.SONSTIGES)
        }

        assertEquals(null, id)
    }
}
