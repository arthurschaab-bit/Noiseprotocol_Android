package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.ConnectionEventDao
import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticContext
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
 * M12 Schritt 6: [SupportBundleHealthWorker]/[SupportBundleHealthCoordinator] mit
 * `androidx.work:work-testing` und handgeschriebenen Fake-DAOs (AGENTS.md Abschnitt 3) - erzeugt
 * Bundle, reiht Upload ein, respektiert den Schalter, "Kein Bundle ohne Not".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportBundleHealthWorkerTest {

    /** Muss mit der privaten `WORK_NAME`-Konstante in [SupportBundleUploadPlanung] uebereinstimmen. */
    private val uploadWorkName = "support_bundle_upload"

    private class FakeConnectionEventDao(
        private val events: List<ConnectionEventEntity> = emptyList(),
    ) : ConnectionEventDao {
        override suspend fun insert(event: ConnectionEventEntity) {}
        override suspend fun fuerSession(sessionId: Long): List<ConnectionEventEntity> = emptyList()
        override fun fuerSessionFlow(sessionId: Long): Flow<List<ConnectionEventEntity>> = flowOf(emptyList())
        override suspend fun seit(von: Long): List<ConnectionEventEntity> = events
    }

    private class FakeDiagnosticLogDao(
        private val anzahl: Long = 0L,
    ) : DiagnosticLogDao {
        override suspend fun insert(eintrag: DiagnosticLogEntity) {}
        override fun neueste(grenze: Int): Flow<List<DiagnosticLogEntity>> = flowOf(emptyList())
        override suspend fun loescheAelterAls(grenze: Long) {}
        override suspend fun seite(nachId: Long, seitengroesse: Int): List<DiagnosticLogEntity> = emptyList()
        override suspend fun anzahlSeit(von: Long): Long = anzahl
    }

    private lateinit var context: Context
    private lateinit var outboxDir: File

    private fun coordinator(
        connectionEvents: List<ConnectionEventEntity> = emptyList(),
        diagnosticLogAnzahl: Long = 0L,
        dbDatei: File = File(context.cacheDir, "nicht_vorhanden_${System.nanoTime()}.db"),
    ): SupportBundleHealthCoordinator {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val reporter = CompositeDiagnosticsReporter(
            sinks = emptyList(),
            initialContext = DiagnosticContext(appVersion = "1.0", buildType = "debug"),
        )
        return SupportBundleHealthCoordinator(
            context = context,
            connectionEventDao = FakeConnectionEventDao(connectionEvents),
            diagnosticLogDao = FakeDiagnosticLogDao(diagnosticLogAnzahl),
            settingsManager = container.settingsManager,
            reporter = reporter,
            exporter = container.supportBundleExporter,
            dbDateiProvider = { dbDatei },
        )
    }

    private fun bauWorker(coordinator: SupportBundleHealthCoordinator) =
        TestListenableWorkerBuilder<SupportBundleHealthWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
                ) = SupportBundleHealthWorker(appContext, workerParameters, coordinator)
            })
            .build()

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        outboxDir = File(context.filesDir, com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR)
        outboxDir.deleteRecursively()
        outboxDir.mkdirs()
        // Isoliert diesen Test von anderen, die dieselbe geteilte Robolectric-Applikation nutzen
        // (wie SupportBundleUploadWorkerTest).
        val settings = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.settingsManager
        settings.periodischesGesundheitsBundleAktiv = true
        settings.supportBundleGesundheitLetzterLaufAt = 0L
        settings.supportBundleGesundheitLetzteDbGroesseBytes = 0L
    }

    @After
    fun aufraeumen() {
        outboxDir.deleteRecursively()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun ausstehendeUploads() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uploadWorkName).get()

    @Test
    fun erstelltBundleUndReihtUploadEinBeiReconnects() = runTest {
        val events = listOf(
            ConnectionEventEntity(sessionId = 1L, at = 1L, type = ConnectionEventType.DEGRADED, reason = "Test"),
        )

        val ergebnis = bauWorker(coordinator(connectionEvents = events)).doWork()

        assertTrue(ergebnis is Result.Success)
        val dateien = outboxDir.listFiles().orEmpty()
        assertEquals(1, dateien.size)
        assertTrue(dateien[0].name.matches(Regex("\\d{4}-\\d{2}-\\d{2}_\\d{6}_periodisch\\.zip")))
        assertTrue("Der Upload-Worker muss eingereiht worden sein", ausstehendeUploads().isNotEmpty())
    }

    @Test
    fun keinBundleOhneNotWennNichtsPassiertIst() = runTest {
        val ergebnis = bauWorker(coordinator()).doWork()

        assertTrue(ergebnis is Result.Success)
        assertTrue("Ohne Aenderung darf kein Bundle in der Outbox landen", outboxDir.listFiles().orEmpty().isEmpty())
        assertTrue("Ohne Bundle darf auch kein Upload eingereiht werden", ausstehendeUploads().isEmpty())
    }

    @Test
    fun zeitstempelWirdAuchOhneBundleFortgeschrieben() = runTest {
        val settings = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.settingsManager

        bauWorker(coordinator()).doWork()

        assertTrue(
            "Sonst wuerde derselbe Zeitraum beim naechsten Lauf doppelt gezaehlt",
            settings.supportBundleGesundheitLetzterLaufAt > 0L,
        )
    }

    @Test
    fun abschaltschalterVerhindertErzeugungUndUpload() = runTest {
        val settings = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.settingsManager
        settings.periodischesGesundheitsBundleAktiv = false
        val events = listOf(
            ConnectionEventEntity(sessionId = 1L, at = 1L, type = ConnectionEventType.DISCONNECTED, reason = "Test"),
        )

        val ergebnis = bauWorker(coordinator(connectionEvents = events)).doWork()

        assertTrue(ergebnis is Result.Success)
        assertTrue("Abgeschaltet darf kein Bundle entstehen", outboxDir.listFiles().orEmpty().isEmpty())
        assertTrue("Abgeschaltet darf kein Upload eingereiht werden", ausstehendeUploads().isEmpty())
        assertEquals(
            "Abgeschaltet darf der Lauf-Zeitstempel nicht fortgeschrieben werden",
            0L,
            settings.supportBundleGesundheitLetzterLaufAt,
        )
    }

    @Test
    fun bundleEnthaeltDieBerechnetenKennzahlen() = runTest {
        val events = listOf(
            ConnectionEventEntity(sessionId = 1L, at = 1L, type = ConnectionEventType.DEGRADED, reason = "Test"),
            ConnectionEventEntity(sessionId = 1L, at = 2L, type = ConnectionEventType.DEGRADED, reason = "Test"),
        )

        bauWorker(coordinator(connectionEvents = events, diagnosticLogAnzahl = 7L)).doWork()

        val zipFile = outboxDir.listFiles()!!.single()
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("state/health_metrics.json")
            assertTrue("Das Bundle muss die Kennzahlen enthalten", entry != null)
            val inhalt = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue(inhalt.contains("\"reconnectCount\":2"))
            assertTrue(inhalt.contains("\"diagnosticLogEntryCount\":7"))
        }
    }

    @Test
    fun dbWachstumAlleinLoestEinBundleAus() = runTest {
        val dbDatei = File(context.cacheDir, "wachstum_test_${System.nanoTime()}.db")
        dbDatei.writeBytes(ByteArray(5_000))
        val settings = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.settingsManager
        settings.supportBundleGesundheitLetzteDbGroesseBytes = 1_000L

        val ergebnis = bauWorker(coordinator(dbDatei = dbDatei)).doWork()

        assertTrue(ergebnis is Result.Success)
        assertEquals(1, outboxDir.listFiles()?.size)
        dbDatei.delete()
    }

    @Test
    fun fehlerBeimSammelnFuehrtZuRetryNichtZumAbsturz() = runTest {
        val fehlerhafteDao = object : DiagnosticLogDao by FakeDiagnosticLogDao() {
            override suspend fun anzahlSeit(von: Long): Long = error("Simulierter DB-Fehler")
        }
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val fehlerhafterCoordinator = SupportBundleHealthCoordinator(
            context = context,
            connectionEventDao = FakeConnectionEventDao(),
            diagnosticLogDao = fehlerhafteDao,
            settingsManager = container.settingsManager,
            reporter = CompositeDiagnosticsReporter(sinks = emptyList()),
            exporter = container.supportBundleExporter,
        )

        val ergebnis = bauWorker(fehlerhafterCoordinator).doWork()

        assertTrue(ergebnis is Result.Retry)
    }
}
