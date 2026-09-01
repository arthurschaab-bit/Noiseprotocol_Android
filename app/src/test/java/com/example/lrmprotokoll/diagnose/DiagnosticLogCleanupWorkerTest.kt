package com.example.lrmprotokoll.diagnose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 2: prueft die WorkManager-Glue in [DiagnosticLogCleanupWorker.doWork]
 * - die Bereinigungslogik selbst ist bereits in [DiagnosticLogCleanupCoordinatorTest] getestet;
 * hier geht es nur um die Result-Uebersetzung (Erfolg -> success, jede Ausnahme -> retry) und
 * darum, dass [DiagnosticLogCleanupCoordinator.bereinige] ueberhaupt aufgerufen wird.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticLogCleanupWorkerTest {

    private class FakeDiagnosticLogDao(private val beimLoeschenFehlschlagen: Boolean = false) : DiagnosticLogDao {
        var loeschenAufrufe = 0
        var letzteGrenze: Long? = null
        override suspend fun insert(eintrag: DiagnosticLogEntity) {}
        override fun alle(): Flow<List<DiagnosticLogEntity>> = flowOf(emptyList())
        override suspend fun loescheAelterAls(grenze: Long) {
            loeschenAufrufe++
            letzteGrenze = grenze
            if (beimLoeschenFehlschlagen) error("Simulierter DB-Fehler fuer den Retry-Test")
        }
    }

    private lateinit var context: Context

    private fun bauWorker(coordinator: DiagnosticLogCleanupCoordinator) =
        TestListenableWorkerBuilder<DiagnosticLogCleanupWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
                ) = DiagnosticLogCleanupWorker(appContext, workerParameters, coordinator)
            })
            .build()

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun erfolgreicheBereinigungRuftDenKoordinatorAufUndLiefertSuccess() = runTest {
        val dao = FakeDiagnosticLogDao()
        val worker = bauWorker(DiagnosticLogCleanupCoordinator(dao))

        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        assertEquals(
            "doWork() muss den Koordinator tatsaechlich aufrufen, nicht nur success() liefern",
            1, dao.loeschenAufrufe,
        )
    }

    @Test
    fun fehlschlagBeimBereinigenLiefertResultRetry() = runTest {
        val dao = FakeDiagnosticLogDao(beimLoeschenFehlschlagen = true)
        val worker = bauWorker(DiagnosticLogCleanupCoordinator(dao))

        val ergebnis = worker.doWork()

        assertTrue(
            "Ein DB-Fehler darf den taeglichen Job nicht endgueltig scheitern lassen - der " +
                "naechste Lauf soll es erneut versuchen",
            ergebnis is Result.Retry,
        )
    }
}
