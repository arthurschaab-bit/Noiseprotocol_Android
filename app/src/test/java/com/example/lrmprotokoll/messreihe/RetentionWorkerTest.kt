package com.example.lrmprotokoll.messreihe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementDao
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateDao
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 2: prueft die WorkManager-Glue in [RetentionWorker.doWork] - drei
 * Schritte in einem gemeinsamen `runCatching` (Plan 13.2/F5/F9): Messreihen-Verdichtung, F5-
 * Auto-Retention und F9-Papierkorbbereinigung. Die Verdichtungslogik selbst ist bereits in
 * [RetentionCoordinatorTest] getestet; hier geht es um die Result-Uebersetzung und darum, dass
 * ein Fehlschlag in Schritt 1 die folgenden Schritte nicht mehr ausfuehrt (dieselbe
 * `runCatching`-Instanz umschliesst alle drei).
 *
 * Schritte 2/3 laufen bewusst gegen den echten [LaermprotokollApp]-Container (Room und
 * SharedPreferences sind unter Robolectric deterministisch, siehe andere Tests, die
 * `container.database` direkt nutzen) - nur Schritt 1 (der Koordinator) bekommt einen Testseam,
 * weil nur dort ein gezielter Fehlschlag erzeugt werden muss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionWorkerTest {

    private class ImmerFehlschlagenderMeasurementDao : MeasurementDao {
        override suspend fun insertAll(messwerte: List<MeasurementEntity>) {}
        override suspend fun fuerSession(sessionId: Long) = emptyList<MeasurementEntity>()
        override fun fuerSessionFlow(sessionId: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override fun fuerSessionAbFlow(sessionId: Long, ab: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun zwischen(von: Long, bis: Long) = emptyList<MeasurementEntity>()
        override suspend fun aelterAls(grenze: Long): List<MeasurementEntity> =
            error("Simulierter DB-Fehler fuer den Retry-Test")
        override suspend fun loescheAelterAls(grenze: Long) {}
        override suspend fun anzahl(): Int = 0
    }

    private class LeererMinuteAggregateDao : MinuteAggregateDao {
        override suspend fun insertAll(aggregate: List<MinuteAggregateEntity>) {}
        override suspend fun fuerSession(sessionId: Long) = emptyList<MinuteAggregateEntity>()
        override fun fuerSessionFlow(sessionId: Long) = throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun zwischen(von: Long, bis: Long) = emptyList<MinuteAggregateEntity>()
    }

    private lateinit var context: Context
    private lateinit var app: LaermprotokollApp

    private fun bauWorker(coordinatorOverride: RetentionCoordinator?) =
        TestListenableWorkerBuilder<RetentionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
                ) = RetentionWorker(appContext, workerParameters, coordinatorOverride)
            })
            .build()

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        app = context as LaermprotokollApp
    }

    @Test
    fun erfolgreicherLaufBereinigtAutoRetentionUndPapierkorb() = runTest {
        app.container.settingsManager.autoRetentionEnabled = true
        app.container.settingsManager.autoRetentionDays = 30

        val jetzt = System.currentTimeMillis()
        val alterTag = jetzt - 40L * 24 * 60 * 60 * 1000

        val dao = app.container.database.noiseDao()
        dao.insert(NoiseRecord(timestamp = alterTag, amplitude = 1.0, filePath = "/nicht/vorhanden.wav"))

        val trashDatei = File.createTempFile("retention_test", ".wav").apply { writeBytes(ByteArray(10)) }
        dao.insert(
            NoiseRecord(
                timestamp = jetzt, amplitude = 1.0, filePath = trashDatei.absolutePath,
                deletedAt = jetzt - 40L * 24 * 60 * 60 * 1000,
            ),
        )

        val worker = bauWorker(coordinatorOverride = null)
        val ergebnis = worker.doWork()

        assertTrue(ergebnis is Result.Success)
        val nochAktiv = dao.getAlleAktiven().any { it.timestamp == alterTag }
        assertTrue(
            "Ein altes, unbeschriftetes, nicht favorisiertes Geraeusch muss automatisch in den " +
                "Papierkorb wandern (F5) und darf danach nicht mehr aktiv sein",
            !nochAktiv,
        )
        val trashNachDemLauf = dao.getTrash().first()
        assertTrue(
            "Eine seit 30+ Tagen im Papierkorb liegende Aufnahme muss endgueltig geloescht werden " +
                "(F9) - im Papierkorb darf danach nur noch der gerade erst (Schritt 2) automatisch " +
                "eingetragene Kandidat stehen, der frisch geloescht und damit noch nicht 30 Tage alt ist",
            trashNachDemLauf.none { it.filePath == trashDatei.absolutePath },
        )
        assertTrue("Die zugehoerige WAV-Datei muss mitgeloescht werden", !trashDatei.exists())
    }

    @Test
    fun fehlschlagBeimVerdichtenLiefertResultRetryUndUeberspringtDenRest() = runTest {
        app.container.settingsManager.autoRetentionEnabled = true
        app.container.settingsManager.autoRetentionDays = 30

        val jetzt = System.currentTimeMillis()
        val alterTag = jetzt - 40L * 24 * 60 * 60 * 1000
        val dao = app.container.database.noiseDao()
        dao.insert(NoiseRecord(timestamp = alterTag, amplitude = 1.0, filePath = "/nicht/vorhanden.wav"))

        val fehlerhafterKoordinator = RetentionCoordinator(
            ImmerFehlschlagenderMeasurementDao(), LeererMinuteAggregateDao(),
        )
        val worker = bauWorker(coordinatorOverride = fehlerhafterKoordinator)

        val ergebnis = worker.doWork()

        assertTrue(
            "Ein DB-Fehler beim Verdichten muss zu Result.retry() fuehren, nicht zu einem " +
                "endgueltigen Fehlschlag - der naechste taegliche Lauf soll es erneut versuchen",
            ergebnis is Result.Retry,
        )
        val nochAktiv = dao.getAlleAktiven().any { it.timestamp == alterTag }
        assertTrue(
            "Schlaegt Schritt 1 fehl, duerfen Schritt 2/3 (dieselbe runCatching-Klammer) nicht " +
                "mehr laufen - der Auto-Retention-Kandidat muss unveraendert aktiv bleiben",
            nochAktiv,
        )
    }
}
