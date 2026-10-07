package com.example.lrmprotokoll.audio

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.LevelSampleEntity
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticId
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests fuer [AufzeichnungsWaechterWorker] gemaess Auftrag PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md (Tests 1 bis 8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AufzeichnungsWaechterWorkerTest {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager
    private lateinit var fakeDao: FakeLevelSampleDao
    private lateinit var fakeReporter: FakeDiagnosticsReporter
    private lateinit var fakeNotifier: FakeNotifier
    private val gestarteteIntents = mutableListOf<Intent>()
    private var serviceStarterException: Throwable? = null

    private class FakeLevelSampleDao(
        var maxAtWert: Long? = null,
    ) : LevelSampleDao {
        override suspend fun insert(sample: LevelSampleEntity) {}

        override suspend fun insertAll(samples: List<LevelSampleEntity>) {}

        override suspend fun zwischen(
            von: Long,
            bis: Long,
        ): List<LevelSampleEntity> = emptyList()

        override suspend fun loescheVor(vor: Long) {}

        override suspend fun loescheBereich(
            von: Long,
            bis: Long,
        ) {}

        override suspend fun anzahl(): Int = 0

        override suspend fun maxAt(): Long? = maxAtWert
    }

    private class FakeDiagnosticsReporter(
        private val delegate: DiagnosticsReporter = CompositeDiagnosticsReporter(sinks = emptyList()),
    ) : DiagnosticsReporter by delegate {
        data class ReportedEvent(
            val code: DiagnosticCode,
            val component: String,
            val operation: String,
            val severity: DiagnosticSeverity,
            val details: Map<String, Any?>,
        )

        val events = mutableListOf<ReportedEvent>()

        override fun report(
            code: DiagnosticCode,
            component: String,
            operation: String,
            severity: DiagnosticSeverity,
            handled: Boolean,
            retryable: Boolean,
            userVisible: Boolean,
            cause: Throwable?,
            message: String?,
            statusCode: String?,
            details: Map<String, Any?>,
        ): DiagnosticId {
            events.add(ReportedEvent(code, component, operation, severity, details))
            return DiagnosticId("test")
        }
    }

    private class FakeNotifier(context: Context) : AufzeichnungsWaechterNotifier(context) {
        val benachrichtigungen = mutableListOf<Pair<String, String>>()

        override fun benachrichtigeZumFortsetzen(
            titel: String,
            nachricht: String,
        ): Boolean {
            benachrichtigungen.add(titel to nachricht)
            return true
        }
    }

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settingsManager = SettingsManager(context, securePrefs = null)
        fakeDao = FakeLevelSampleDao()
        fakeReporter = FakeDiagnosticsReporter()
        fakeNotifier = FakeNotifier(context)
        gestarteteIntents.clear()
        serviceStarterException = null
    }

    @After
    fun abbauen() {
        settingsManager.monitoringWasActive = false
        settingsManager.audioMonitoringWasActive = false
        settingsManager.bestaetigeUnterbrechungen()
    }

    private fun erstelleWorker(
        sdkInt: Int = 29,
        dienstLaeuft: Boolean = false,
        kannInVordergrund: Boolean = true,
        zeitProvider: () -> Long = { 1_000_000L },
        okHttpClient: OkHttpClient = OkHttpClient(),
    ): AufzeichnungsWaechterWorker {
        return TestListenableWorkerBuilder<AufzeichnungsWaechterWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): AufzeichnungsWaechterWorker {
                        return AufzeichnungsWaechterWorker(
                            context = appContext,
                            parameter = workerParameters,
                            serviceStarter = { _, intent ->
                                serviceStarterException?.let { throw it }
                                gestarteteIntents.add(intent)
                            },
                            sdkIntOverride = sdkInt,
                            zeitProviderOverride = zeitProvider,
                            okHttpClientOverride = okHttpClient,
                            notifierOverride = fakeNotifier,
                            dienstLaeuftProvider = { dienstLaeuft },
                            kannInVordergrundProvider = { kannInVordergrund },
                            settingsManagerOverride = settingsManager,
                            levelSampleDaoOverride = fakeDao,
                            diagnosticsReporterOverride = fakeReporter,
                        )
                    }
                },
            )
            .build()
    }

    /**
     * Test 1: Soll laeuft nicht, SDK 29 -> ein Startaufruf mit richtigem Extra und
     * RECORDING_ENDED_UNEXPECTEDLY mit quelle = "waechter".
     */
    @Test
    fun testSollLaeuftNichtSdk29StartetDienstMitExtraUndMeldetDiagnose() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true
        fakeDao.maxAtWert = 800_000L

        val worker = erstelleWorker(sdkInt = 29)
        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        assertEquals(1, gestarteteIntents.size)
        val intent = gestarteteIntents.single()
        assertEquals(AudioRecordingService::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false))

        val event = fakeReporter.events.firstOrNull { it.code == DiagnosticCode.RECORDING_ENDED_UNEXPECTEDLY }
        assertNotNull("RECORDING_ENDED_UNEXPECTEDLY muss gemeldet werden", event)
        assertEquals("waechter", event!!.details["quelle"])
        assertEquals(800_000L, event.details["letzteDatenAt"])
    }

    /**
     * Test 2: Soll laeuft nicht, SDK 34 -> kein Start, eine Benachrichtigung.
     */
    @Test
    fun testSollLaeuftNichtSdk34StartetNichtSondernBenachrichtigt() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true
        fakeDao.maxAtWert = 800_000L

        val worker = erstelleWorker(sdkInt = 34)
        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        assertTrue("Unter SDK 34 darf kein Dienst aus dem Hintergrund gestartet werden", gestarteteIntents.isEmpty())
        assertEquals("Es muss genau eine Benachrichtigung gezeigt werden", 1, fakeNotifier.benachrichtigungen.size)
        assertTrue(fakeNotifier.benachrichtigungen.single().second.contains("tippen zum Fortsetzen"))

        val event = fakeReporter.events.firstOrNull { it.code == DiagnosticCode.RECORDING_ENDED_UNEXPECTEDLY }
        assertNotNull("RECORDING_ENDED_UNEXPECTEDLY muss gemeldet werden", event)
        assertEquals("waechter", event!!.details["quelle"])
    }

    /**
     * Test 3: Dienst laeuft -> kein Start, kein Eintrag.
     */
    @Test
    fun testDienstLaeuftTutNichtsUndMeldetNichts() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true

        val worker = erstelleWorker(sdkInt = 29, dienstLaeuft = true)
        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        assertTrue(gestarteteIntents.isEmpty())
        assertTrue(fakeNotifier.benachrichtigungen.isEmpty())
        assertTrue("Wenn der Dienst laeuft, darf kein Diagnoseeintrag geschrieben werden", fakeReporter.events.isEmpty())
    }

    /**
     * Test 4: Ausdruecklich gestoppt (Flags false) -> kein Start, kein Eintrag.
     */
    @Test
    fun testAusdruecklichGestopptTutNichtsUndMeldetNichts() = runTest {
        settingsManager.monitoringWasActive = false
        settingsManager.audioMonitoringWasActive = false

        val worker = erstelleWorker(sdkInt = 29, dienstLaeuft = false)
        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        assertTrue(gestarteteIntents.isEmpty())
        assertTrue(fakeNotifier.benachrichtigungen.isEmpty())
        assertTrue("Bei explizit gestoppter Aufzeichnung darf kein Diagnoseeintrag geschrieben werden", fakeReporter.events.isEmpty())
    }

    /**
     * Test 5: Vierter Neustart innerhalb einer Stunde -> kein Start, nur Benachrichtigung und Eintrag (Schleifenschutz).
     */
    @Test
    fun testSchleifenschutzVerhindertViertenNeustartInnerhalbEinerStunde() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true
        val jetzt = 1_000_000L
        settingsManager.registriereWaechterNeustart(jetzt - 30_000L)
        settingsManager.registriereWaechterNeustart(jetzt - 20_000L)
        settingsManager.registriereWaechterNeustart(jetzt - 10_000L)
        assertEquals(3, settingsManager.waechterNeustartCount)

        val worker = erstelleWorker(sdkInt = 29, zeitProvider = { jetzt })
        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        assertTrue("Schleifenschutz muss Dienststart verhindern", gestarteteIntents.isEmpty())
        assertEquals("Benachrichtigung muss angezeigt werden", 1, fakeNotifier.benachrichtigungen.size)
        assertTrue(fakeNotifier.benachrichtigungen.single().second.contains("wiederholt gescheitert"))

        val schleifenschutzEvent = fakeReporter.events.firstOrNull { it.operation == "schleifenschutz" }
        assertNotNull("Schleifenschutz-Ereignis muss gemeldet werden", schleifenschutzEvent)
        assertEquals(DiagnosticCode.RECORDING_ENDED_UNEXPECTEDLY, schleifenschutzEvent!!.code)
    }

    /**
     * Test 6: Der Starter wirft eine Ausnahme -> Result.success(), Eintrag mit Fehler, kein Absturz.
     */
    @Test
    fun testStarterWirftAusnahmeFuehrtZuResultSuccessUndFehlerEintrag() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true
        serviceStarterException = IllegalStateException("ForegroundServiceStartNotAllowedException")

        val worker = erstelleWorker(sdkInt = 29)
        val ergebnis = worker.doWork()

        assertTrue("Worker darf trotz Starter-Ausnahme nicht abstuerzen", ergebnis is Result.Success)
        val fehlerEvent = fakeReporter.events.firstOrNull { it.code == DiagnosticCode.AUDIO_FOREGROUND_SERVICE_FAILED }
        assertNotNull("AUDIO_FOREGROUND_SERVICE_FAILED muss gemeldet werden", fehlerEvent)
        assertEquals("serviceStarter", fehlerEvent!!.operation)
    }

    /**
     * Test 7: ACTION_STOP_SERVICE / stoppe storniert die Arbeit.
     */
    @Test
    fun testStoppeStorniertArbeit() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        try {
            AufzeichnungsWaechterPlanung.plane(context)
            val geplanteWorkInfos =
                WorkManager.getInstance(context).getWorkInfosForUniqueWork("aufzeichnungs_waechter").get()
            assertEquals("Es muss genau ein Waechter-Job eingeplant sein", 1, geplanteWorkInfos.size)
            assertFalse(geplanteWorkInfos.single().state.isFinished)

            AufzeichnungsWaechterPlanung.stoppe(context)
            val gestoppteWorkInfos =
                WorkManager.getInstance(context).getWorkInfosForUniqueWork("aufzeichnungs_waechter").get()
            assertEquals(1, gestoppteWorkInfos.size)
            assertEquals(WorkInfo.State.CANCELLED, gestoppteWorkInfos.single().state)
        } finally {
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    /**
     * Test 8a: ntfy eingerichtet, SDK 29 -> genau eine Nachricht mit "neu gestartet".
     */
    @Test
    fun testNtfySdk29SendetNeustartMeldung() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            settingsManager.monitoringWasActive = true
            settingsManager.audioMonitoringWasActive = true
            settingsManager.ntfyAktiv = true
            settingsManager.ntfyServer = server.url("/").toString()
            settingsManager.ntfyTopic = "waechter-test"

            val worker = erstelleWorker(sdkInt = 29, okHttpClient = OkHttpClient())
            val ergebnis = worker.doWork()

            assertTrue(ergebnis is Result.Success)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/waechter-test", request.path)
            assertTrue(request.body.readUtf8().contains("wurde neu gestartet"))
        } finally {
            server.shutdown()
        }
    }

    /**
     * Test 8b: ntfy eingerichtet, SDK 34 -> genau eine Nachricht mit "Zum Fortsetzen die App öffnen".
     */
    @Test
    fun testNtfySdk34SendetHinweisMeldung() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            settingsManager.monitoringWasActive = true
            settingsManager.audioMonitoringWasActive = true
            settingsManager.ntfyAktiv = true
            settingsManager.ntfyServer = server.url("/").toString()
            settingsManager.ntfyTopic = "waechter-test"

            val worker = erstelleWorker(sdkInt = 34, okHttpClient = OkHttpClient())
            val ergebnis = worker.doWork()

            assertTrue(ergebnis is Result.Success)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/waechter-test", request.path)
            assertTrue(request.body.readUtf8().contains("Zum Fortsetzen die App öffnen"))
        } finally {
            server.shutdown()
        }
    }

    /**
     * Test 8c: ntfy nicht eingerichtet -> keine Anfrage.
     */
    @Test
    fun testNtfyNichtEingerichtetSendetKeineAnfrage() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            settingsManager.monitoringWasActive = true
            settingsManager.audioMonitoringWasActive = true
            settingsManager.ntfyAktiv = false
            settingsManager.ntfyServer = server.url("/").toString()
            settingsManager.ntfyTopic = "waechter-test"

            val worker = erstelleWorker(sdkInt = 29, okHttpClient = OkHttpClient())
            val ergebnis = worker.doWork()

            assertTrue(ergebnis is Result.Success)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    /**
     * Test 8d: Sendefehler -> Neustart trotzdem ausgefuehrt.
     */
    @Test
    fun testNtfySendefehlerBlockiertNeustartNicht() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            settingsManager.monitoringWasActive = true
            settingsManager.audioMonitoringWasActive = true
            settingsManager.ntfyAktiv = true
            settingsManager.ntfyServer = server.url("/").toString()
            settingsManager.ntfyTopic = "waechter-test"

            val worker = erstelleWorker(sdkInt = 29, okHttpClient = OkHttpClient())
            val ergebnis = worker.doWork()

            assertTrue(ergebnis is Result.Success)
            assertEquals("Dienst muss trotz ntfy-Fehler gestartet werden", 1, gestarteteIntents.size)
            val fehlerEvent = fakeReporter.events.firstOrNull { it.code == DiagnosticCode.ALERT_NTFY_FAILED }
            assertNotNull("ALERT_NTFY_FAILED muss gemeldet werden", fehlerEvent)
        } finally {
            server.shutdown()
        }
    }
}
