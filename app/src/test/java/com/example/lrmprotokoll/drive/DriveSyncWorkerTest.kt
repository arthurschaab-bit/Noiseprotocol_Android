package com.example.lrmprotokoll.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.lrmprotokoll.alert.TestUhr
import com.example.lrmprotokoll.data.DriveDailyFileDao
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.LevelSampleEntity
import com.example.lrmprotokoll.data.LevelSource
import com.example.lrmprotokoll.data.NoiseDao
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.ReferenceSound
import com.example.lrmprotokoll.data.SettingsManager
import java.time.Instant
import java.time.ZoneId
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
 * Testluecken-Auftrag Stufe 2: prueft die WorkManager-Glue in [DriveSyncWorker.doWork] - die
 * Uebersetzung eines [DriveSyncCoordinator.SyncErgebnis] in ein WorkManager-[Result]. Die
 * Sync-Entscheidungslogik selbst ist bereits in [DriveSyncCoordinatorTest] ausfuehrlich getestet;
 * hier geht es nur darum, dass [DriveSyncWorker] das Ergebnis nicht falsch abbildet (Plan 8.4.6:
 * Netzfehler/Quota -> retry, fehlender Ordner -> failure, alles andere -> success).
 *
 * Der echte Container ist fuer diesen Worker nicht nutzbar (der dortige [DriveSyncCoordinator]
 * haengt an echten Play-Services/Netz-Abhaengigkeiten, siehe [GoogleSignInAccessTokenProvider]-
 * KDoc) - deshalb der [DriveSyncWorker.coordinatorOverride]-Testseam ueber eine eigene
 * [WorkerFactory], mit denselben Fake-Bausteinen wie [DriveSyncCoordinatorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveSyncWorkerTest {

    private class FakeLevelSampleDao : LevelSampleDao {
        val eingefuegt = mutableListOf<LevelSampleEntity>()
        override suspend fun insert(sample: LevelSampleEntity) { eingefuegt += sample }
        override suspend fun insertAll(samples: List<LevelSampleEntity>) { eingefuegt += samples }
        override suspend fun zwischen(von: Long, bis: Long): List<LevelSampleEntity> =
            eingefuegt.filter { it.at in von until bis }
        override suspend fun loescheVor(vor: Long) { eingefuegt.removeAll { it.at < vor } }
        override suspend fun anzahl(): Int = eingefuegt.size
    }

    private class FakeDailyFileDao : DriveDailyFileDao {
        val zeilen = mutableMapOf<String, DriveDailyFileEntity>()
        override suspend fun byDate(date: String): DriveDailyFileEntity? = zeilen[date]
        override suspend fun upsert(entity: DriveDailyFileEntity) { zeilen[entity.date] = entity }
        override suspend fun update(entity: DriveDailyFileEntity) { zeilen[entity.date] = entity }
        override suspend fun letzterFehlschlag(): DriveDailyFileEntity? =
            zeilen.values.filter { it.state == DriveSyncState.FAILED }.maxByOrNull { it.date }
        override fun alle() = flowOf(zeilen.values.sortedByDescending { it.date })
    }

    private class FakeNoiseDao : NoiseDao {
        override fun getAll(): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun getAlleAktiven(): List<NoiseRecord> = emptyList()
        override fun getTrash(): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun zwischenZeitpunkt(von: Long, bis: Long): List<NoiseRecord> = emptyList()
        override fun zwischenZeitpunktFlow(von: Long, bis: Long): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override fun abZeitpunktFlow(von: Long): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun insert(record: NoiseRecord): Long = 0
        override suspend fun update(record: NoiseRecord) {}
        override suspend fun softDelete(id: Long, deletedAt: Long) {}
        override suspend fun softDeleteMultiple(ids: List<Long>, deletedAt: Long) {}
        override suspend fun restore(id: Long) {}
        override suspend fun restoreMultiple(ids: List<Long>) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteMultiple(ids: List<Long>) {}
        override suspend fun deleteTrashAelterAls(cutoff: Long): Int = 0
        override suspend fun getTrashAelterAls(cutoff: Long): List<NoiseRecord> = emptyList()
        override suspend fun getAutoRetentionCandidates(cutoff: Long): List<NoiseRecord> = emptyList()
        override suspend fun setFavorite(id: Long, isFavorite: Boolean) {}
        override suspend fun setNotes(id: Long, notes: String?) {}
        override suspend fun setDetectedLabel(id: Long, label: String?) {}
        override fun getAllReferences(): Flow<List<ReferenceSound>> = flowOf(emptyList())
        override suspend fun insertReference(sound: ReferenceSound) {}
        override suspend fun deleteReference(id: Long) {}
    }

    private class FakeDriveApiClient : DriveApiClient {
        var dateiSuchenErgebnis: kotlin.Result<DriveDatei?> = kotlin.Result.success(null)
        var dateiAnlegenErgebnis: kotlin.Result<String> = kotlin.Result.success("neue-datei-id")
        var anlegenAufrufe = 0

        override suspend fun ordnerAnlegen(name: String) = kotlin.Result.success("ordner-id")
        override suspend fun ordnerSuchen(name: String) = kotlin.Result.success<DriveDatei?>(null)
        override suspend fun ordnerAuflisten() = kotlin.Result.success(emptyList<DriveDatei>())
        override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String) = kotlin.Result.success(Unit)
        override suspend fun dateienInOrdnerAuflisten(ordnerId: String): kotlin.Result<Set<String>> =
            kotlin.Result.success(emptySet())
        override suspend fun dateiSuchen(name: String, ordnerId: String) = dateiSuchenErgebnis
        override suspend fun dateiAnlegen(
            name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ): kotlin.Result<String> {
            anlegenAufrufe++
            return dateiAnlegenErgebnis
        }
        override suspend fun dateiAktualisieren(
            fileId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ) = kotlin.Result.success(Unit)
    }

    private lateinit var context: Context
    private lateinit var driveApi: FakeDriveApiClient
    private lateinit var settings: SettingsManager
    private lateinit var uhr: TestUhr
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun baueKoordinator(
        levelSampleDao: LevelSampleDao = FakeLevelSampleDao(),
        noiseDao: NoiseDao = FakeNoiseDao(),
    ) = DriveSyncCoordinator(
        driveApi = driveApi,
        levelSampleDao = levelSampleDao,
        dailyFileDao = FakeDailyFileDao(),
        noiseDao = noiseDao,
        settings = settings,
        now = uhr,
        zone = zone,
    )

    private fun bauWorker(coordinator: DriveSyncCoordinator) =
        TestListenableWorkerBuilder<DriveSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
                ) = DriveSyncWorker(appContext, workerParameters, coordinator)
            })
            .build()

    /**
     * Ein Pegelwert eine Stunde vor der fixen Testuhr-Zeit ([uhr]) - unabhaengig von der echten
     * Systemuhr. Vorher fiel dieser Test sporadisch auf CI durch: `at` kam aus
     * `System.currentTimeMillis()`, [DriveSyncCoordinator] las "jetzt" aber ueber die echte
     * Systemuhr erst beim spaeteren `worker.doWork()`-Aufruf - lag der Sample-Zeitstempel durch
     * Runner-Last oder eine NTP-Korrektur zwischen beiden Aufrufen einmal NACH "jetzt", fiel er
     * aus dem `zwischen(von, jetzt)`-Fenster heraus und der Koordinator meldete faelschlich
     * KeineAenderung statt Retry/Failure. Mit der injizierten [TestUhr] ist "jetzt" fest und die
     * Race-Bedingung strukturell ausgeschlossen.
     */
    private fun sampleVorEinerStunde() = LevelSampleEntity(
        at = uhr.now().minusSeconds(3600).toEpochMilli(), levelDb = 55.0, source = LevelSource.PCE_323,
    )

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        driveApi = FakeDriveApiClient()
        settings = SettingsManager(context)
        settings.driveSyncEnabled = true
        settings.driveFolderId = "ordner-id"
        settings.driveAggregationSekunden = 10
        uhr = TestUhr(Instant.parse("2026-08-19T12:00:00Z"))
    }

    @Test
    fun erfolgreicherSyncLiefertResultSuccess() = runTest {
        settings.driveAggregationSekunden = 3600
        val worker = bauWorker(baueKoordinator())

        // Ohne Pegelwerte meldet der Koordinator KeineAenderung/Erfolgreich(0) - fuer den Test
        // reicht das, weil hier nur die Result-Uebersetzung geprueft wird, nicht der Upload-Inhalt
        // (den prueft DriveSyncCoordinatorTest bereits erschoepfend).
        val ergebnis = worker.doWork()

        assertTrue(
            "Ohne Fehler muss Result.success() zurueckkommen, unabhaengig vom genauen SyncErgebnis",
            ergebnis is Result.Success,
        )
    }

    @Test
    fun fehlgeschlagenerSyncOhneOrdnerbezugLiefertResultRetry() = runTest {
        driveApi.dateiAnlegenErgebnis = kotlin.Result.failure(DriveApiException("kein Netz", httpCode = null))
        settings.driveAggregationSekunden = 3600
        // Mindestens ein Pegelwert, sonst gibt es nichts hochzuladen und der Fehlerpfad greift nie.
        val koordinatorMitDaten = baueKoordinator(
            levelSampleDao = FakeLevelSampleDao().apply { eingefuegt += sampleVorEinerStunde() },
        )
        val worker = bauWorker(koordinatorMitDaten)

        val ergebnis = worker.doWork()

        assertTrue(
            "Ein Netzfehler beim Hochladen muss WorkManager zum Wiederholen bewegen (Plan 8.4.6)",
            ergebnis is Result.Retry,
        )
        assertEquals(1, driveApi.anlegenAufrufe)
    }

    @Test
    fun ordnerNichtGefundenLiefertResultFailureStattEndlosemRetry() = runTest {
        driveApi.dateiAnlegenErgebnis = kotlin.Result.failure(DriveApiException("Ordner weg", httpCode = 404))
        settings.driveAggregationSekunden = 3600
        val koordinatorMitDaten = baueKoordinator(
            levelSampleDao = FakeLevelSampleDao().apply { eingefuegt += sampleVorEinerStunde() },
        )
        val worker = bauWorker(koordinatorMitDaten)

        val ergebnis = worker.doWork()

        assertTrue(
            "Ein endgueltig fehlender Ordner darf nicht endlos wiederholt werden, sondern ist ein " +
                "echter Fehlschlag, bis der Nutzer neu einrichtet",
            ergebnis is Result.Failure,
        )
    }

    @Test
    fun abgeschalteterSyncLaeuftUeberDenWorkerTrotzdemAlsErfolgDurch() = runTest {
        settings.driveSyncEnabled = false
        val worker = bauWorker(baueKoordinator())

        val ergebnis = worker.doWork()

        assertTrue(
            "SyncAusgeschaltet ist kein Fehler des Geraets - der Worker darf hier nicht ewig " +
                "retryen, sondern muss den Zyklus als erledigt betrachten",
            ergebnis is Result.Success,
        )
        assertEquals(0, driveApi.anlegenAufrufe)
    }
}
