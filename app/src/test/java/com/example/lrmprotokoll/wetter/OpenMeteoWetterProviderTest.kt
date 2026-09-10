package com.example.lrmprotokoll.wetter

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueft den Wetterabruf gegen einen echten HTTP-Server ([MockWebServer]) statt gegen einen
 * gefakten Client - derselbe Grund wie bei
 * [com.example.lrmprotokoll.alert.ntfy.NtfyAlertChannelTest]: geprueft wird, dass Pfad,
 * Query-Parameter und Antwort-Parsing tatsaechlich zusammenpassen. Robolectric wird allein wegen
 * `org.json.JSONObject` gebraucht - im android.jar-Stub der reinen JVM-Unit-Tests wirft das ohne
 * Robolectric (dieselbe Konvention wie [com.example.lrmprotokoll.drive.GoogleDriveApiClientTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenMeteoWetterProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenMeteoWetterProvider

    @Before
    fun aufbauen() {
        server = MockWebServer()
        server.start()
        provider = OpenMeteoWetterProvider(basisUrl = server.url("/v1/forecast").toString())
    }

    @After
    fun abbauen() {
        server.shutdown()
    }

    @Test
    fun parstEineErfolgreicheAntwort() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":12.3,"wind_speed_10m":8.4,"weather_code":3}}"""
            )
        )

        val ergebnis = provider.aktuelleWetterlage(48.13, 11.58)

        assertTrue(ergebnis.isSuccess)
        val wetter = ergebnis.getOrThrow()
        assertEquals(12.3, wetter.temperaturCelsius, 0.01)
        assertEquals(8.4, wetter.windgeschwindigkeitKmh, 0.01)
        assertEquals("bedeckt", wetter.beschreibung)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("latitude=48.13"))
        assertTrue(request.path!!.contains("longitude=11.58"))
    }

    @Test
    fun unbekannterWettercodeErgibtLesbarenFallback() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"temperature_2m":5.0,"wind_speed_10m":1.0,"weather_code":9999}}"""
            )
        )

        val wetter = provider.aktuelleWetterlage(0.0, 0.0).getOrThrow()

        assertEquals("unbekannte Wetterlage", wetter.beschreibung)
    }

    @Test
    fun httpFehlerWirdAlsFehlschlagGemeldet() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val ergebnis = provider.aktuelleWetterlage(48.13, 11.58)

        assertTrue(ergebnis.isFailure)
    }

    @Test
    fun kurztextFasstAlleDreiWerteZusammen() {
        val wetter = Wetterlage(temperaturCelsius = 12.3, windgeschwindigkeitKmh = 8.4, beschreibung = "bedeckt")
        assertEquals("12 °C, bedeckt, Wind 8 km/h", wetter.alsKurztext())
    }
}
