package com.example.lrmprotokoll.drive

import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueft den Drive-REST-Client gegen einen echten HTTP-Server ([MockWebServer]), nicht gegen
 * einen gefakten Client - wie [com.example.lrmprotokoll.alert.ntfy.NtfyAlertChannelTest]. Nur so
 * ist belegt, dass Pfad, Header und Rumpf tatsaechlich so rausgehen wie gedacht.
 *
 * Robolectric, weil org.json (JSONObject/JSONArray) auf dem reinen JVM-Testlaufwerk nur als
 * nicht implementierter Android-Stub vorliegt - Robolectric shadowt sie mit echter Funktion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleDriveApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GoogleDriveApiClient
    private val token = FakeTokenProvider()

    private class FakeTokenProvider(var token: Result<String> = Result.success("test-token")) : AccessTokenProvider {
        override suspend fun holeToken(): Result<String> = token
    }

    @Before
    fun aufbauen() {
        server = MockWebServer()
        server.start()
        client = GoogleDriveApiClient(token, OkHttpClient(), basisUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun abbauen() {
        server.shutdown()
    }

    @Test
    fun ordnerAnlegenSendetKorrektenPfadUndKoerper() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"ordner-123"}""").setResponseCode(200))

        val ergebnis = client.ordnerAnlegen("Lärmprotokoll")

        assertEquals("ordner-123", ergebnis.getOrThrow())
        val anfrage = server.takeRequest()
        assertEquals("POST", anfrage.method)
        assertEquals("/drive/v3/files", anfrage.path)
        val koerper = anfrage.body.readUtf8()
        assertTrue(koerper.contains("\"mimeType\":\"application\\/vnd.google-apps.folder\""))
        assertTrue(koerper.contains("Lärmprotokoll"))
        assertEquals("Bearer test-token", anfrage.getHeader("Authorization"))
    }

    @Test
    fun ordnerSuchenLiefertGefundenenOrdner() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"files":[{"id":"ordner-found","name":"Lärmprotokoll"}]}""")
        )

        val ergebnis = client.ordnerSuchen("Lärmprotokoll")

        val ordner = ergebnis.getOrThrow()
        assertEquals("ordner-found", ordner?.id)
        assertEquals("Lärmprotokoll", ordner?.name)
        val anfrage = server.takeRequest()
        assertEquals("GET", anfrage.method)
        assertTrue(anfrage.path!!.contains("mimeType"))
    }

    @Test
    fun ordnerAuflistenLiefertAlleGefundenenOrdner() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"files":[{"id":"ordner-1","name":"Ordner A"},{"id":"ordner-2","name":"Ordner B"}]}""")
        )

        val ergebnis = client.ordnerAuflisten()

        val liste = ergebnis.getOrThrow()
        assertEquals(2, liste.size)
        assertEquals("ordner-1", liste[0].id)
        assertEquals("Ordner A", liste[0].name)
        assertEquals("ordner-2", liste[1].id)
        assertEquals("Ordner B", liste[1].name)
    }

    @Test
    fun ordnerUmbenennenSendetPatchMitNeuemNamen() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"ordner-123","name":"Neuer Name"}""").setResponseCode(200))

        val ergebnis = client.ordnerUmbenennen("ordner-123", "Neuer Name")

        assertTrue(ergebnis.isSuccess)
        val anfrage = server.takeRequest()
        assertEquals("PATCH", anfrage.method)
        assertEquals("/drive/v3/files/ordner-123", anfrage.path)
        val koerper = anfrage.body.readUtf8()
        assertTrue(koerper.contains("\"name\":\"Neuer Name\""))
    }

    @Test
    fun dateiSuchenLiefertGefundeneDatei() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"files":[{"id":"datei-1","name":"laermprotokoll_2026-08-19.csv"}]}""")
        )

        val ergebnis = client.dateiSuchen("laermprotokoll_2026-08-19.csv", "ordner-123")

        val datei = ergebnis.getOrThrow()
        assertEquals("datei-1", datei?.id)
        val anfrage = server.takeRequest()
        assertEquals("GET", anfrage.method)
        assertTrue(anfrage.path!!.contains("q="))
    }

    @Test
    fun dateiSuchenOhneTrefferLiefertNull() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))

        val ergebnis = client.dateiSuchen("nichtvorhanden.csv", "ordner-123")

        assertNull(ergebnis.getOrThrow())
    }

    @Test
    fun dateienInOrdnerAuflistenLiefertAlleNamenImOrdner() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"files":[{"name":"datei1.wav"},{"name":"datei2.wav"}]}""")
        )

        val ergebnis = client.dateienInOrdnerAuflisten("ordner-123")

        val namen = ergebnis.getOrThrow()
        assertEquals(2, namen.size)
        assertTrue(namen.contains("datei1.wav"))
        assertTrue(namen.contains("datei2.wav"))
        val anfrage = server.takeRequest()
        assertEquals("GET", anfrage.method)
        assertTrue(anfrage.path!!.contains("ordner-123"))
    }

    @Test
    fun suchanfrageEscaptAnfuehrungszeichenImDateinamen() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))

        client.dateiSuchen("ein'name.csv", "ordner-123")

        val anfrage = server.takeRequest()
        assertTrue(
            "Ein ungeeschapptes Anfuehrungszeichen wuerde die Drive-Query-Syntax brechen",
            anfrage.path!!.contains("ein%5C%27name.csv") || anfrage.requestUrl!!.queryParameter("q")!!.contains("ein\\'name.csv"),
        )
    }

    @Test
    fun dateiAnlegenSendetMultipartMitMetadatenUndInhalt() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"neue-datei"}"""))

        val ergebnis = client.dateiAnlegen(
            name = "laermprotokoll_2026-08-19.csv", ordnerId = "ordner-123",
            inhalt = "Zeit;LAeq_dB\n".toByteArray(), mimeType = "text/csv", gzip = false,
        )

        assertEquals("neue-datei", ergebnis.getOrThrow())
        val anfrage = server.takeRequest()
        assertEquals("/upload/drive/v3/files?uploadType=multipart", anfrage.path)
        assertTrue(anfrage.getHeader("Content-Type")!!.startsWith("multipart/related"))
        val koerper = anfrage.body.readUtf8()
        assertTrue(koerper.contains("laermprotokoll_2026-08-19.csv"))
        assertTrue(koerper.contains("Zeit;LAeq_dB"))
    }

    @Test
    fun dateiAnlegenMitGzipKomprimiertDenInhaltUndSetztDenHeader() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"neue-datei"}"""))
        val klartext = "Zeit;LAeq_dB\n2026-08-19T08:00:00+02:00;52,3\n".repeat(50)

        client.dateiAnlegen(
            name = "x.csv", ordnerId = "ordner-123",
            inhalt = klartext.toByteArray(), mimeType = "text/csv", gzip = true,
        )

        val anfrage = server.takeRequest()
        assertEquals("gzip", anfrage.getHeader("Content-Encoding"))
        val entpackt = GZIPInputStream(anfrage.body.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertTrue(
            "Der entpackte Rumpf muss wieder dem Klartext entsprechen",
            entpackt.contains("2026-08-19T08:00:00+02:00;52,3"),
        )
    }

    @Test
    fun dateiAnlegenMitGzipBleibtNachDemEntpackenEinGueltigesMultipartPaket() = runTest {
        // Regressionstest: Content-Encoding: gzip muss fuer den GESAMTEN Multipart-Rumpf gelten,
        // nicht nur fuer den Dateianteil. Waere nur der Dateianteil komprimiert, stuenden nach
        // dem Entpacken Multipart-Grenzen und JSON-Metadaten unveraendert MIT dem eigentlich
        // schon entpackten Dateiinhalt vermischt - kein gueltiges Dokument mehr.
        server.enqueue(MockResponse().setBody("""{"id":"neue-datei"}"""))

        client.dateiAnlegen(
            name = "laermprotokoll_2026-08-19.csv", ordnerId = "ordner-123",
            inhalt = "Zeit;LAeq_dB\n2026-08-19T08:00:00+02:00;52,3\n".toByteArray(),
            mimeType = "text/csv", gzip = true,
        )

        val anfrage = server.takeRequest()
        assertEquals("gzip", anfrage.getHeader("Content-Encoding"))
        val entpackt = GZIPInputStream(anfrage.body.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertTrue("Multipart-Grenze muss nach dem Entpacken wieder lesbar sein", entpackt.contains("Content-Disposition") || entpackt.contains("laermprotokoll_2026-08-19.csv"))
        assertTrue(entpackt.contains("2026-08-19T08:00:00+02:00;52,3"))
    }

    @Test
    fun dateiAktualisierenMitGzipKomprimiertDenRumpf() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"datei-1"}"""))
        val klartext = "Zeit;LAeq_dB\n".repeat(50)

        client.dateiAktualisieren("datei-1", klartext.toByteArray(), "text/csv", gzip = true)

        val anfrage = server.takeRequest()
        assertEquals("gzip", anfrage.getHeader("Content-Encoding"))
        val entpackt = GZIPInputStream(anfrage.body.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertEquals(klartext, entpackt)
    }

    @Test
    fun ohneGzipWirdKeinContentEncodingGesetztUndDerRumpfBleibtKlartext() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"x"}"""))

        client.dateiAnlegen(
            name = "x.csv", ordnerId = "ordner-123",
            inhalt = "Klartext".toByteArray(), mimeType = "text/csv", gzip = false,
        )

        val anfrage = server.takeRequest()
        assertNull(anfrage.getHeader("Content-Encoding"))
    }

    @Test
    fun dateiAktualisierenPatchtDenBestehendenInhalt() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"datei-1"}"""))

        val ergebnis = client.dateiAktualisieren(
            fileId = "datei-1", inhalt = "neuer Inhalt".toByteArray(), mimeType = "text/csv", gzip = false,
        )

        assertTrue(ergebnis.isSuccess)
        val anfrage = server.takeRequest()
        assertEquals("PATCH", anfrage.method)
        assertEquals("/upload/drive/v3/files/datei-1?uploadType=media", anfrage.path)
        assertEquals("neuer Inhalt", anfrage.body.readUtf8())
    }

    @Test
    fun httpFehlercodeWirdAlsDriveApiExceptionMitCodeDurchgereicht() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}"""))

        val ergebnis = client.dateiAktualisieren("weg", ByteArray(0), "text/csv")

        assertTrue(ergebnis.isFailure)
        val fehler = ergebnis.exceptionOrNull()
        assertTrue(fehler is DriveApiException)
        assertEquals(404, (fehler as DriveApiException).httpCode)
    }

    @Test
    fun fehlgeschlagenesTokenFuehrtZuFehlschlagOhneHttpAufruf() = runTest {
        token.token = Result.failure(IllegalStateException("nicht angemeldet"))

        val ergebnis = client.ordnerAnlegen("Ordner")

        assertTrue(ergebnis.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unerreichbarerServerLiefertFehlerStattAusnahme() = runTest {
        server.shutdown()

        val ergebnis = client.ordnerAnlegen("Ordner")

        assertTrue(ergebnis.isFailure)
    }

    @Test
    fun escapeFuerDriveQueryEscaptAnfuehrungszeichenUndBackslashes() {
        assertEquals("ein\\'name", escapeFuerDriveQuery("ein'name"))
        assertEquals("pfad\\\\datei", escapeFuerDriveQuery("pfad\\datei"))
        assertFalse(escapeFuerDriveQuery("normal").contains("\\"))
    }
}
