package com.example.lrmprotokoll.alert.heartbeat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.lrmprotokoll.data.SettingsManager
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
 * Testluecken-Auftrag Stufe 2: prueft die WorkManager-Glue in [HeartbeatWorker.doWork] - die
 * Uebersetzung eines [HeartbeatPinger.Ergebnis] in ein WorkManager-[Result]. Die Ping-Logik
 * selbst ist bereits in [HeartbeatPingerTest] ausfuehrlich getestet; hier geht es nur um die
 * Result-Uebersetzung, insbesondere retry() statt failure() bei einem einzelnen Fehlschlag (siehe
 * KDoc [HeartbeatWorker.doWork]: ein endgueltiger Fehlschlag liesse die Totmannschaltung bis zum
 * naechsten regulaeren Lauf verstummen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartbeatWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsManager

    private fun bauWorker(pinger: HeartbeatPinger) =
        TestListenableWorkerBuilder<HeartbeatWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
                ) = HeartbeatWorker(appContext, workerParameters, pinger)
            })
            .build()

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        settings = SettingsManager(context)
        settings.heartbeatUrl = server.url("/ping").toString()
        settings.monitoringWasActive = true
    }

    @After
    fun abbauen() {
        runCatching { server.shutdown() }
    }

    @Test
    fun erfolgreicherPingLiefertResultSuccess() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val worker = bauWorker(HeartbeatPinger(settings))

        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun uebersprungenerPingOhneUrlLiefertEbenfallsResultSuccess() = runTest {
        settings.heartbeatUrl = ""
        val worker = bauWorker(HeartbeatPinger(settings))

        val ergebnis = worker.doWork()

        assertTrue(
            "Kein Ping noetig ist kein Fehler - der Worker darf hier nicht retryen",
            ergebnis is Result.Success,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fehlgeschlagenerPingLiefertResultRetryNichtFailure() = runTest {
        server.shutdown()
        val worker = bauWorker(HeartbeatPinger(settings))

        val ergebnis = worker.doWork()

        assertTrue(
            "Ein einzelner Fehlschlag darf die Totmannschaltung nicht endgueltig verstummen " +
                "lassen (siehe HeartbeatWorker-KDoc) - WorkManager muss es erneut versuchen",
            ergebnis is Result.Retry,
        )
    }
}
