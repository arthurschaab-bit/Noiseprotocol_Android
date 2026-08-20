package com.example.lrmprotokoll.alert.ntfy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertKind
import com.example.lrmprotokoll.alert.AlertReason
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.data.erzeugeNtfyTopic
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueft den ntfy-Versand gegen einen echten HTTP-Server ([MockWebServer]) statt gegen einen
 * gefakten Client. Der Unterschied ist nicht akademisch: Was hier geprueft wird, ist gerade,
 * dass Pfad, Header und Rumpf so auf der Leitung landen wie gedacht - ein Fake-Client wuerde
 * genau diesen Schritt ueberspringen und trotzdem gruen sein.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NtfyAlertChannelTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsManager
    private lateinit var kanal: NtfyAlertChannel

    private val topic = "TestTopicMitDreissigZeichenXYZ12"

    @Before
    fun aufbauen() {
        server = MockWebServer()
        server.start()
        settings = SettingsManager(ApplicationProvider.getApplicationContext<Context>())
        settings.ntfyAktiv = true
        settings.ntfyTopic = topic
        settings.ntfyServer = server.url("/").toString().trimEnd('/')
        kanal = NtfyAlertChannel(settings)
    }

    @After
    fun abbauen() {
        server.shutdown()
    }

    private fun alarm(kind: AlertKind = AlertKind.RAISED) = Alert(
        alertId = 1,
        kind = kind,
        reason = AlertReason.STALE,
        since = Instant.parse("2026-08-18T12:00:00Z"),
        message = "Lärmprotokoll: Verbindung zum Messgerät unterbrochen seit 18.08.2026 14:00.",
    )

    @Test
    fun alarmGehtAlsPostAufServerUndTopic() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val ergebnis = kanal.send(alarm())

        assertTrue(ergebnis.isSuccess)
        val anfrage = server.takeRequest()
        assertEquals("POST", anfrage.method)
        assertEquals("/$topic", anfrage.path)
        assertEquals(
            "Lärmprotokoll: Verbindung zum Messgerät unterbrochen seit 18.08.2026 14:00.",
            anfrage.body.readUtf8(),
        )
    }

    @Test
    fun alarmKommtMitPrioritaetFuenfUndDurchbrichtDamitNichtStoeren() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        kanal.send(alarm())

        val anfrage = server.takeRequest()
        assertEquals(
            "Ein Alarm, der nachts um drei lautlos ankommt, ist wertlos",
            "5",
            anfrage.getHeader("Priority"),
        )
        assertEquals("warning", anfrage.getHeader("Tags"))
    }

    @Test
    fun entwarnungKommtLeiserAlsDerAlarm() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        kanal.send(alarm(AlertKind.RESOLVED))

        val anfrage = server.takeRequest()
        assertEquals(
            "Die Entwarnung soll ankommen, aber niemanden wecken - der Ausfall ist vorbei",
            "3",
            anfrage.getHeader("Priority"),
        )
        assertEquals("white_check_mark", anfrage.getHeader("Tags"))
    }

    @Test
    fun fehlerantwortDesServersGiltAlsFehlschlag() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val ergebnis = kanal.send(alarm())

        assertTrue("HTTP 503 darf nicht als zugestellt durchgehen", ergebnis.isFailure)
        assertTrue(ergebnis.exceptionOrNull()!!.message!!.contains("503"))
    }

    @Test
    fun unerreichbarerServerLiefertFehlerStattEinerAusnahme() = runTest {
        server.shutdown() // ab hier nimmt niemand mehr an

        val ergebnis = kanal.send(alarm())

        assertTrue(ergebnis.isFailure)
    }

    /**
     * Der Topic-Name ist beim oeffentlichen Server die einzige Zugangskontrolle. Er darf deshalb
     * in keiner Fehlermeldung auftauchen - die wandert ins Protokoll, und OkHttp packt die
     * vollstaendige URL ungefragt in seine Ausnahmen.
     */
    @Test
    fun keineFehlermeldungVerraetDasTopic() = runTest {
        server.shutdown()
        val ergebnisVerbindungsfehler = kanal.send(alarm())

        val server2 = MockWebServer()
        server2.start()
        server2.enqueue(MockResponse().setResponseCode(401))
        settings.ntfyServer = server2.url("/").toString().trimEnd('/')
        val ergebnisHttpFehler = NtfyAlertChannel(settings).send(alarm())
        server2.shutdown()

        listOf(ergebnisVerbindungsfehler, ergebnisHttpFehler).forEach { ergebnis ->
            val text = ergebnis.exceptionOrNull()?.toString().orEmpty()
            assertFalse("Fehlermeldung verrät das Topic: $text", text.contains(topic))
        }
    }

    /**
     * HTTP-Header duerfen nur ASCII enthalten. "Lärmprotokoll" tut das nicht - ohne Kodierung
     * scheitert der Versand an OkHttp, und zwar bei jedem einzelnen Alarm.
     */
    @Test
    fun umlautImTitelWirdKodiertUndBleibtDekodierbar() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val ergebnis = kanal.send(alarm())

        assertTrue("Ein Umlaut im Titel darf den Versand nicht verhindern", ergebnis.isSuccess)
        val titel = server.takeRequest().getHeader("Title")!!
        assertTrue("Header muss reines ASCII sein", titel.all { it.code in 32..126 })
        assertEquals("=?UTF-8?B?", titel.take(10))

        val kodiert = titel.removePrefix("=?UTF-8?B?").removeSuffix("?=")
        val entschluesselt = String(java.util.Base64.getDecoder().decode(kodiert), Charsets.UTF_8)
        assertEquals("Lärmprotokoll: Verbindung verloren", entschluesselt)
    }

    @Test
    fun reinAsciiBleibtUnveraendertLesbar() {
        assertEquals("Noise alert", alsHeaderWert("Noise alert"))
    }

    @Test
    fun ohneTopicIstDerKanalNichtVerfuegbarUndVersendetNicht() = runTest {
        settings.ntfyTopic = ""

        assertFalse(kanal.isAvailable)
        assertTrue(kanal.send(alarm()).isFailure)
        assertEquals("Es darf gar keine Anfrage rausgehen", 0, server.requestCount)
    }

    @Test
    fun abgeschalteterKanalMeldetSichAlsNichtVerfuegbar() {
        settings.ntfyAktiv = false
        assertFalse(kanal.isAvailable)
    }

    @Test
    fun erzeugtesTopicIstLangUndNichtVorhersagbar() {
        val a = erzeugeNtfyTopic()
        val b = erzeugeNtfyTopic()

        assertEquals(32, a.length)
        assertNotEquals("Zwei Aufrufe duerfen nie dasselbe Topic liefern", a, b)
        assertTrue("Nur URL-sichere Zeichen", a.all { it.isLetterOrDigit() })
    }

    @Test
    fun serverUrlWirdOhneDoppelteSchraegstricheZusammengesetzt() = runTest {
        settings.ntfyServer = server.url("/").toString() // endet auf "/"
        server.enqueue(MockResponse().setResponseCode(200))

        NtfyAlertChannel(settings).send(alarm())

        assertEquals("/$topic", server.takeRequest().path)
    }
}
