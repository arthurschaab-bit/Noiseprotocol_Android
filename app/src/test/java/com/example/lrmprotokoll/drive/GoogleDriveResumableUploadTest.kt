package com.example.lrmprotokoll.drive

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val BLOCK = 8 * 1024 * 1024

/**
 * Der resumable Upload aus M11 Etappe B (B.6) gegen einen echten HTTP-Server. Geprueft wird
 * genau das, was hier schiefgehen kann und in Produktion teuer waere: die Content-Range-Header,
 * `308` als Normalfall statt als Fehler, die Wiederaufnahme an der vom **Server** gemeldeten
 * Position und der Neuanfang nach einem verfallenen Session-URI.
 *
 * Robolectric wie [GoogleDriveApiClientTest] - wegen org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleDriveResumableUploadTest {

    @get:Rule
    val ordner = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: GoogleDriveApiClient

    private class FakeTokenProvider : AccessTokenProvider {
        override suspend fun holeToken(): Result<String> = Result.success("test-token")
    }

    @Before
    fun aufbauen() {
        server = MockWebServer()
        server.start()
        client = GoogleDriveApiClient(
            FakeTokenProvider(),
            OkHttpClient(),
            basisUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun abbauen() {
        server.shutdown()
    }

    private fun datei(groesse: Int): File {
        val f = ordner.newFile("video.mp4")
        // Fortlaufendes Muster statt Nullen: Ein vertauschter oder doppelt gesendeter Block
        // faellt beim Vergleich damit auf, bei lauter Nullen nicht.
        f.writeBytes(ByteArray(groesse) { (it % 251).toByte() })
        return f
    }

    private fun sessionUri() = server.url("/upload/session/abc").toString()

    @Test
    fun kleineDateiGehtInEinemBlockRausUndLiefertDieId() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-1"}"""))

        val f = datei(1024)
        val ergebnis = client.dateiHochladenResumable("video.mp4", "ordner-1", f, "video/mp4")

        assertEquals("datei-1", ergebnis.getOrThrow())

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/upload/drive/v3/files?uploadType=resumable", start.path)
        assertEquals("video/mp4", start.getHeader("X-Upload-Content-Type"))
        assertTrue("Metadaten muessen Name und Elternordner tragen", start.body.readUtf8().contains("ordner-1"))

        val block = server.takeRequest()
        assertEquals("PUT", block.method)
        assertEquals("bytes 0-1023/1024", block.getHeader("Content-Range"))
        assertEquals(1024, block.bodySize)
    }

    @Test
    fun derSessionUriWirdVorDemErstenBlockGemeldet() = runTest {
        // Sonst faengt ein Prozess-Neustart mitten im Upload wieder bei null an.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-1"}"""))

        val reihenfolge = mutableListOf<String>()
        client.dateiHochladenResumable(
            "video.mp4", "ordner-1", datei(1024), "video/mp4",
            sessionGestartet = { reihenfolge += "session:$it" },
            fortschritt = { bestaetigt, _ -> reihenfolge += "fortschritt:$bestaetigt" },
        ).getOrThrow()

        assertEquals(listOf("session:${sessionUri()}", "fortschritt:1024"), reihenfolge)
    }

    @Test
    fun dreihundertachtIstDerNormalfallZwischenZweiBloecken() = runTest {
        val groesse = BLOCK + 4096
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${BLOCK - 1}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-2"}"""))

        val ergebnis = client.dateiHochladenResumable("video.mp4", "ordner-1", datei(groesse), "video/mp4")

        assertEquals("datei-2", ergebnis.getOrThrow())
        server.takeRequest() // Sitzungsstart
        assertEquals("bytes 0-${BLOCK - 1}/$groesse", server.takeRequest().getHeader("Content-Range"))
        assertEquals("bytes $BLOCK-${groesse - 1}/$groesse", server.takeRequest().getHeader("Content-Range"))
    }

    @Test
    fun derServerstandZaehlt_nichtDasWasGesendetSchien() = runTest {
        // Der Server bestaetigt weniger, als der Block gross war. Wer sich auf die gesendete
        // Menge verlaesst, hinterlaesst ein Loch in der Datei.
        val groesse = BLOCK + 4096
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${BLOCK - 2049}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-3"}"""))

        client.dateiHochladenResumable("video.mp4", "ordner-1", datei(groesse), "video/mp4").getOrThrow()

        server.takeRequest()
        server.takeRequest()
        assertEquals(
            "Der zweite Block muss dort ansetzen, wo der Server aufgehoert hat",
            "bytes ${BLOCK - 2048}-${groesse - 1}/$groesse",
            server.takeRequest().getHeader("Content-Range"),
        )
    }

    @Test
    fun abgebrochenerUploadNimmtAnDerGemeldetenPositionWiederAuf() = runTest {
        val groesse = BLOCK + 4096
        // Erst der Serverstand (leeres PUT mit "bytes */gesamt"), dann der Rest.
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${BLOCK - 1}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-4"}"""))

        val gestartet = mutableListOf<String>()
        val ergebnis = client.dateiHochladenResumable(
            "video.mp4", "ordner-1", datei(groesse), "video/mp4",
            fortsetzenAb = sessionUri(),
            sessionGestartet = { gestartet += it },
        )

        assertEquals("datei-4", ergebnis.getOrThrow())
        assertTrue("Eine fortgesetzte Uebertragung startet keine neue Sitzung", gestartet.isEmpty())

        val standAbfrage = server.takeRequest()
        assertEquals("PUT", standAbfrage.method)
        assertEquals("bytes */$groesse", standAbfrage.getHeader("Content-Range"))
        assertEquals(0, standAbfrage.bodySize)

        assertEquals("bytes $BLOCK-${groesse - 1}/$groesse", server.takeRequest().getHeader("Content-Range"))
    }

    @Test
    fun einBereitsFertigerUploadWirdNichtNochEinmalGesendet() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-5"}"""))

        val ergebnis = client.dateiHochladenResumable(
            "video.mp4", "ordner-1", datei(4096), "video/mp4", fortsetzenAb = sessionUri(),
        )

        assertEquals("datei-5", ergebnis.getOrThrow())
        assertEquals("Nur die Standabfrage, kein Datenblock", 1, server.requestCount)
    }

    @Test
    fun verfallenerSessionUriFuehrtZuGenauEinemNeuanfang() = runTest {
        // Ein Session-URI ist rund eine Woche gueltig. Danach: einmal von vorn - nicht in einer
        // Schleife, die den Nutzer sein Datenvolumen kostet.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-6"}"""))

        var neueSitzung: String? = null
        val ergebnis = client.dateiHochladenResumable(
            "video.mp4", "ordner-1", datei(2048), "video/mp4",
            fortsetzenAb = sessionUri(),
            sessionGestartet = { neueSitzung = it },
        )

        assertEquals("datei-6", ergebnis.getOrThrow())
        assertNotNull("Der Aufrufer muss den neuen Session-URI erfahren", neueSitzung)
        assertEquals(3, server.requestCount)

        server.takeRequest()
        assertEquals("POST", server.takeRequest().method)
        assertEquals("bytes 0-2047/2048", server.takeRequest().getHeader("Content-Range"))
    }

    @Test
    fun einZweitesVierhundertvierBrichtAbStattEndlosZuWiederholen() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(404))

        val ergebnis = client.dateiHochladenResumable(
            "video.mp4", "ordner-1", datei(2048), "video/mp4", fortsetzenAb = sessionUri(),
        )

        val fehler = ergebnis.exceptionOrNull() as DriveApiException
        assertEquals(404, fehler.httpCode)
        assertEquals("Kein dritter Anlauf", 3, server.requestCount)
    }

    @Test
    fun einStehengebliebenerServerstandBrichtAbStattEwigZuLaufen() = runTest {
        // Ein 308, das zweimal dieselbe Position meldet, waere sonst eine Endlosschleife.
        val groesse = BLOCK + 4096
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-2047"))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-2047"))

        val ergebnis = client.dateiHochladenResumable("video.mp4", "ordner-1", datei(groesse), "video/mp4")

        assertEquals(308, (ergebnis.exceptionOrNull() as DriveApiException).httpCode)
        assertEquals("Nach dem Stillstand wird nicht weiter gesendet", 3, server.requestCount)
    }

    @Test
    fun eineSitzungOhneLocationHeaderIstEinFehler() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val ergebnis = client.dateiHochladenResumable("video.mp4", "ordner-1", datei(1024), "video/mp4")

        assertTrue(ergebnis.exceptionOrNull() is DriveApiException)
    }

    @Test
    fun derHochgeladeneInhaltIstByteGleichZurDatei() = runTest {
        val groesse = BLOCK + 12_345
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", sessionUri()))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${BLOCK - 1}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"datei-7"}"""))

        val f = datei(groesse)
        client.dateiHochladenResumable("video.mp4", "ordner-1", f, "video/mp4").getOrThrow()

        server.takeRequest()
        val ersterBlock = server.takeRequest().body.readByteArray()
        val zweiterBlock = server.takeRequest().body.readByteArray()

        assertTrue(
            "Zusammengesetzt muessen die Bloecke exakt die Datei ergeben",
            (ersterBlock + zweiterBlock).contentEquals(f.readBytes()),
        )
    }
}
