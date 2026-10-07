package com.example.lrmprotokoll.alert.heartbeat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Totmannschaltung ist der einzige Schutz gegen den Totalausfall des Ueberwachungsgeraets -
 * und seit dem Wegfall des SMS-Kanals auch die einzige Absicherung gegen "Internet weg".
 *
 * Der Schwerpunkt liegt entsprechend darauf, wann NICHT gepingt werden darf: Ein Ping, der
 * faelschlich rausgeht, macht die ganze Konstruktion wertlos, weil er Leben meldet, wo keins ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartbeatPingerTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsManager
    private lateinit var pinger: HeartbeatPinger

    @Before
    fun aufbauen() {
        server = MockWebServer()
        server.start()
        settings = SettingsManager(ApplicationProvider.getApplicationContext<Context>())
        settings.heartbeatUrl = server.url("/ping/geheim").toString()
        settings.monitoringWasActive = true
        pinger = HeartbeatPinger(settings)
    }

    @After
    fun abbauen() {
        settings.heartbeatUrl = ""
        settings.monitoringWasActive = false
        runCatching { server.shutdown() }
    }

    @Test
    fun beiAktiverUeberwachungGehtDerPingAnDieHinterlegteUrl() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))

            assertEquals(HeartbeatPinger.Ergebnis.GESENDET, pinger.ping())

            val anfrage = server.takeRequest()
            assertEquals("GET", anfrage.method)
            assertEquals("/ping/geheim", anfrage.path)
        }

    @Test
    fun ohneHinterlegteUrlWirdNichtsGesendetUndNichtsWiederholt() =
        runTest {
            settings.heartbeatUrl = ""

            assertEquals(
                "Leere URL heißt aus - niemand wird zu einem Fremddienst gezwungen",
                HeartbeatPinger.Ergebnis.UEBERSPRUNGEN,
                pinger.ping(),
            )
            assertEquals(0, server.requestCount)
        }

    @Test
    fun beiAbgeschalteterUeberwachungWirdNichtGepingt() =
        runTest {
            settings.monitoringWasActive = false

            assertEquals(
                "Sonst meldet der Ping Leben, obwohl der Nutzer die Überwachung bewusst aus hat",
                HeartbeatPinger.Ergebnis.UEBERSPRUNGEN,
                pinger.ping(),
            )
            assertEquals(0, server.requestCount)
        }

    @Test
    fun fehlerantwortGiltAlsFehlschlagUndNichtAlsErfolg() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            assertEquals(HeartbeatPinger.Ergebnis.FEHLGESCHLAGEN, pinger.ping())
        }

    @Test
    fun unerreichbarerDienstGiltAlsFehlschlag() =
        runTest {
            server.shutdown()

            assertEquals(HeartbeatPinger.Ergebnis.FEHLGESCHLAGEN, pinger.ping())
        }

    /**
     * Die Ping-URL ist eine Capability-URL: Wer sie kennt, kann die Totmannschaltung
     * stillstellen, indem er selbst pingt. Sie darf deshalb nicht ueber eine durchgereichte
     * Ausnahme in einem Protokoll landen - OkHttp packt die vollstaendige URL ungefragt hinein.
     */
    @Test
    fun keineAusnahmeVerlaesstDenPingerUndKannDieUrlVerraten() =
        runTest {
            server.shutdown()

            val ergebnis = runCatching { pinger.ping() }

            assertTrue("ping() darf nie werfen, sondern nur ein Ergebnis liefern", ergebnis.isSuccess)
            assertEquals(HeartbeatPinger.Ergebnis.FEHLGESCHLAGEN, ergebnis.getOrNull())
        }

    // ---- probe(): Probe-Ping aus den Einstellungen (Befund J) ----

    @Test
    fun probeSendetAuchOhneLaufendeUeberwachung() =
        runTest {
            settings.monitoringWasActive = false
            server.enqueue(MockResponse().setResponseCode(200))

            val probe = pinger.probe()

            assertEquals(HeartbeatPinger.Ergebnis.GESENDET, probe.ergebnis)
            assertEquals("Ein Probe-Ping muss auch vor dem ersten Messstart senden", 1, server.requestCount)
            assertEquals("/ping/geheim", server.takeRequest().path)
        }

    @Test
    fun probeMeldetHttpFehlerOhneUrl() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val probe = pinger.probe()

            assertEquals(HeartbeatPinger.Ergebnis.FEHLGESCHLAGEN, probe.ergebnis)
            assertEquals("Heartbeat-Dienst antwortete mit HTTP 404", probe.hinweis)
            assertFalse("Der Hinweis darf die Capability-URL nicht enthalten", probe.hinweis!!.contains("geheim"))
        }

    @Test
    fun probeMeldetUnerreichbarenDienstOhneUrl() =
        runTest {
            server.shutdown()

            val probe = pinger.probe()

            assertEquals(HeartbeatPinger.Ergebnis.FEHLGESCHLAGEN, probe.ergebnis)
            assertEquals("Heartbeat-Dienst nicht erreichbar", probe.hinweis)
        }

    @Test
    fun probeOhneUrlUeberspringtUndSendetNichts() =
        runTest {
            settings.heartbeatUrl = ""

            assertEquals(HeartbeatPinger.Ergebnis.UEBERSPRUNGEN, pinger.probe().ergebnis)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun probeWirftNieUndVerraetDieUrlAuchBeiKaputterUrlNicht() =
        runTest {
            settings.heartbeatUrl = "kein-url-geheim"

            val ergebnis = runCatching { pinger.probe() }

            assertTrue("probe() darf nie werfen", ergebnis.isSuccess)
            val probe = ergebnis.getOrThrow()
            assertEquals(HeartbeatPinger.Ergebnis.FEHLGESCHLAGEN, probe.ergebnis)
            assertFalse(probe.hinweis.orEmpty().contains("geheim"))
        }

    @Test
    fun urlPruefungErlaubtLeerUndHttpsAberNichtHttpOderMuell() {
        assertTrue(HeartbeatPinger.istGueltigeUrl(""))
        assertTrue(HeartbeatPinger.istGueltigeUrl("   "))
        assertTrue(HeartbeatPinger.istGueltigeUrl("https://hc-ping.com/abc-123"))
        assertTrue(HeartbeatPinger.istGueltigeUrl("  https://hc-ping.com/abc-123  "))
        assertFalse(HeartbeatPinger.istGueltigeUrl("http://hc-ping.com/abc-123"))
        assertFalse(HeartbeatPinger.istGueltigeUrl("kein-url"))
        assertFalse(HeartbeatPinger.istGueltigeUrl("https://"))
    }
}
