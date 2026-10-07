package com.example.lrmprotokoll.audio

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.LevelSampleEntity
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticId
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests fuer [AufzeichnungsFortsetzer] gemaess Auftrag PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md (Tests 2 bis 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AufzeichnungsFortsetzerTest {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager
    private lateinit var fakeDao: FakeLevelSampleDao
    private lateinit var fakeReporter: FakeDiagnosticsReporter
    private val gestarteteIntents = mutableListOf<Intent>()
    private val gezeigteHinweise = mutableListOf<String>()

    private class FakeLevelSampleDao(var maxAtWert: Long? = null) : LevelSampleDao {
        override suspend fun insert(sample: LevelSampleEntity) {}
        override suspend fun insertAll(samples: List<LevelSampleEntity>) {}
        override suspend fun zwischen(von: Long, bis: Long): List<LevelSampleEntity> = emptyList()
        override suspend fun loescheVor(vor: Long) {}
        override suspend fun loescheBereich(von: Long, bis: Long) {}
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
            val handled: Boolean,
            val cause: Throwable?,
            val message: String?,
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
            events.add(ReportedEvent(code, component, operation, severity, handled, cause, message, details))
            return DiagnosticId("test")
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
        gestarteteIntents.clear()
        gezeigteHinweise.clear()
    }

    private fun erstelleFortsetzer(
        dienstLaeuft: Boolean = false,
        kannInDenVordergrund: Boolean = true,
        jetzt: Long = 1_000_000L,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    ): AufzeichnungsFortsetzer {
        return AufzeichnungsFortsetzer(
            context = context,
            settingsManager = settingsManager,
            levelSampleDao = fakeDao,
            diagnosticsReporter = fakeReporter,
            dienstLaeuftProvider = { dienstLaeuft },
            kannInVordergrundProvider = { kannInDenVordergrund },
            serviceStarter = { _, intent -> gestarteteIntents.add(intent) },
            zeitProvider = { jetzt },
            ioDispatcher = dispatcher,
            hinweisZeiger = { text -> gezeigteHinweise.add(text) },
        )
    }

    /**
     * Test 2: Flags gesetzt, Dienst laeuft nicht:
     * - genau ein Start-Intent fuer AudioRecordingService mit EXTRA_START_AUDIO_MONITORING
     * - genau ein RECORDING_ENDED_UNEXPECTEDLY im Fake-Reporter
     * - Details enthalten letzteDatenAt aus dem Fake-DAO
     */
    @Test
    fun testUnerwartetesEndeFortsetzen() = runTest {
        val testLetzteDatenAt = 800_000L
        val testJetzt = 1_000_000L
        fakeDao.maxAtWert = testLetzteDatenAt

        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true

        val fortsetzer = erstelleFortsetzer(
            dienstLaeuft = false,
            kannInDenVordergrund = true,
            jetzt = testJetzt,
        )

        fortsetzer.pruefeUndSetzeFortSuspend()

        assertEquals("Genau ein Service-Start erwartet", 1, gestarteteIntents.size)
        val intent = gestarteteIntents.single()
        assertEquals(AudioRecordingService::class.java.name, intent.component?.className)
        assertEquals(true, intent.getBooleanExtra(EXTRA_START_AUDIO_MONITORING, false))

        assertEquals("Genau ein Diagnose-Event erwartet", 1, fakeReporter.events.size)
        val event = fakeReporter.events.single()
        assertEquals(DiagnosticCode.RECORDING_ENDED_UNEXPECTEDLY, event.code)
        assertEquals(DiagnosticSeverity.WARN, event.severity)
        assertEquals(testLetzteDatenAt, event.details["letzteDatenAt"])
        assertEquals(testJetzt, event.details["entdecktAt"])
        assertEquals("app_geoeffnet", event.details["quelle"])
        assertEquals(true, event.details["audioWarAktiv"])

        // Unterbrechung wurde in SettingsManager hinterlegt
        val unterbrechungen = settingsManager.aktiveUnterbrechungen()
        assertEquals(1, unterbrechungen.size)
        assertEquals(testLetzteDatenAt, unterbrechungen.single().beginn)
        assertEquals(testJetzt, unterbrechungen.single().ende)
    }

    /**
     * Test 3: Zweimal onResume hintereinander -> trotzdem nur ein Start und ein Eintrag (Doppelstart-Schutz).
     */
    @Test
    fun testDoppelstartVermeiden() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true
        fakeDao.maxAtWert = 500_000L

        val fortsetzer = erstelleFortsetzer(
            dienstLaeuft = false,
            kannInDenVordergrund = true,
        )

        // Erster Aufruf
        fortsetzer.pruefeUndSetzeFortSuspend()
        // Zweiter Aufruf (Dienst ist noch nicht aktiv)
        fortsetzer.pruefeUndSetzeFortSuspend()

        assertEquals("Nur genau ein Start-Intent trotz doppeltem Aufruf", 1, gestarteteIntents.size)
        assertEquals("Nur genau ein Diagnose-Eintrag trotz doppeltem Aufruf", 1, fakeReporter.events.size)
    }

    /**
     * Test 4: Flags nicht gesetzt (ausdruecklich gestoppt) -> kein Start, kein Eintrag.
     */
    @Test
    fun testAusdruecklichGestopptKeinStart() = runTest {
        settingsManager.monitoringWasActive = false
        settingsManager.audioMonitoringWasActive = false

        val fortsetzer = erstelleFortsetzer(
            dienstLaeuft = false,
            kannInDenVordergrund = true,
        )

        fortsetzer.pruefeUndSetzeFortSuspend()

        assertEquals("Kein Service-Start bei explizit gestoppter Aufzeichnung", 0, gestarteteIntents.size)
        assertEquals("Kein Diagnose-Eintrag bei explizit gestoppter Aufzeichnung", 0, fakeReporter.events.size)
    }

    /**
     * Test 5: Dienst laeuft (laeuft = true) -> kein Start, kein Eintrag.
     */
    @Test
    fun testDienstLaeuftBereitsKeinStart() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true

        val fortsetzer = erstelleFortsetzer(
            dienstLaeuft = true,
            kannInDenVordergrund = true,
        )

        fortsetzer.pruefeUndSetzeFortSuspend()

        assertEquals("Kein Service-Start wenn Dienst bereits laeuft", 0, gestarteteIntents.size)
        assertEquals("Kein Diagnose-Eintrag wenn Dienst bereits laeuft", 0, fakeReporter.events.size)
    }

    /**
     * Test 6: Keine Mikrofonberechtigung und kein Messgeraet -> kein Start, Hinweis und Eintrag.
     */
    @Test
    fun testNichtStartbarKeinStartHinweisUndEintrag() = runTest {
        settingsManager.monitoringWasActive = true
        settingsManager.audioMonitoringWasActive = true

        val fortsetzer = erstelleFortsetzer(
            dienstLaeuft = false,
            kannInDenVordergrund = false,
        )

        fortsetzer.pruefeUndSetzeFortSuspend()

        assertEquals("Kein Service-Start wenn nicht startbar", 0, gestarteteIntents.size)
        assertEquals("Genau ein Diagnose-Eintrag fuer PERMISSION_REVOKED", 1, fakeReporter.events.size)
        assertEquals(DiagnosticCode.PERMISSION_REVOKED_DURING_OPERATION, fakeReporter.events.single().code)
        assertEquals("Genau ein Hinweis gezeigt", 1, gezeigteHinweise.size)
    }
}
