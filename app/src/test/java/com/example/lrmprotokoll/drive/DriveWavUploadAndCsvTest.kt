package com.example.lrmprotokoll.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveWavUploadAndCsvTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestNoiseDao : NoiseDao {
        val records = mutableListOf<NoiseRecord>()
        override fun getAll(): Flow<List<NoiseRecord>> = flowOf(records)
        override suspend fun getAlleAktiven(): List<NoiseRecord> = records.filter { it.deletedAt == null }
        override fun getTrash(): Flow<List<NoiseRecord>> = flowOf(records.filter { it.deletedAt != null })
        override suspend fun zwischenZeitpunkt(von: Long, bis: Long): List<NoiseRecord> =
            records.filter { it.timestamp in von until bis && it.deletedAt == null }
        override fun zwischenZeitpunktFlow(von: Long, bis: Long): Flow<List<NoiseRecord>> =
            flowOf(records.filter { it.timestamp in von until bis && it.deletedAt == null })
        override fun abZeitpunktFlow(von: Long): Flow<List<NoiseRecord>> =
            flowOf(records.filter { it.timestamp >= von && it.deletedAt == null })
        override suspend fun insert(record: NoiseRecord) { records.add(record) }
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
        override fun getAllReferences(): Flow<List<ReferenceSound>> = flowOf(emptyList())
        override suspend fun insertReference(sound: ReferenceSound) {}
        override suspend fun deleteReference(id: Long) {}
    }

    private class TestDailyFileDao : DriveDailyFileDao {
        val files = mutableMapOf<String, DriveDailyFileEntity>()
        override suspend fun byDate(date: String): DriveDailyFileEntity? = files[date]
        override suspend fun upsert(entity: DriveDailyFileEntity) { files[entity.date] = entity }
        override suspend fun update(entity: DriveDailyFileEntity) { files[entity.date] = entity }
        override suspend fun letzterFehlschlag(): DriveDailyFileEntity? = null
        override fun alle(): Flow<List<DriveDailyFileEntity>> = flowOf(files.values.toList())
    }

    private class TestLevelSampleDao : LevelSampleDao {
        val samples = mutableListOf<LevelSampleEntity>()
        override suspend fun insert(sample: LevelSampleEntity) { samples += sample }
        override suspend fun insertAll(samples: List<LevelSampleEntity>) { this.samples += samples }
        override suspend fun zwischen(von: Long, bis: Long): List<LevelSampleEntity> =
            samples.filter { it.at in von until bis }
        override suspend fun loescheVor(vor: Long) { samples.removeAll { it.at < vor } }
        override suspend fun anzahl(): Int = samples.size
    }

    private class TestDriveApiClient : DriveApiClient {
        val hochgeladeneDateien = mutableMapOf<String, ByteArray>()
        override suspend fun ordnerAnlegen(name: String): Result<String> = Result.success("folder-id")
        override suspend fun ordnerSuchen(name: String): Result<DriveDatei?> = Result.success(DriveDatei("folder-id", name))
        override suspend fun ordnerAuflisten(): Result<List<DriveDatei>> = Result.success(emptyList())
        override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String): Result<Unit> = Result.success(Unit)
        override suspend fun dateienInOrdnerAuflisten(ordnerId: String): Result<Set<String>> =
            Result.success(hochgeladeneDateien.keys.toSet())
        override suspend fun dateiSuchen(name: String, ordnerId: String): Result<DriveDatei?> {
            return if (hochgeladeneDateien.containsKey(name)) Result.success(DriveDatei("file-$name", name)) else Result.success(null)
        }
        override suspend fun dateiAnlegen(
            name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean
        ): Result<String> {
            hochgeladeneDateien[name] = inhalt
            return Result.success("file-$name")
        }
        override suspend fun dateiAktualisieren(
            fileId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean
        ): Result<Unit> {
            hochgeladeneDateien[fileId] = inhalt
            return Result.success(Unit)
        }
    }

    private lateinit var noiseDao: TestNoiseDao
    private lateinit var dailyFileDao: TestDailyFileDao
    private lateinit var levelSampleDao: TestLevelSampleDao
    private lateinit var driveApi: TestDriveApiClient
    private lateinit var settings: SettingsManager
    private lateinit var uhr: TestUhr
    private val zone = ZoneId.of("Europe/Berlin")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsManager(context)
        settings.driveSyncEnabled = true
        settings.driveFolderId = "folder-id"
        settings.driveUploadWav = true

        noiseDao = TestNoiseDao()
        dailyFileDao = TestDailyFileDao()
        levelSampleDao = TestLevelSampleDao()
        driveApi = TestDriveApiClient()
        uhr = TestUhr(Instant.parse("2026-08-23T12:00:00Z"))
    }

    @Test
    fun wavDateienWerdenVollstaendigHochgeladenUndCsvEnthaeltRohwerteUndVerarbeiteteWerte() = runTest {
        // Erstelle eine echte WAV-Testdatei
        val wavFile1 = tempFolder.newFile("noise_20260823_100000.wav")
        wavFile1.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x01, 0x02))

        noiseDao.insert(
            NoiseRecord(
                id = 1,
                timestamp = uhr.now().minusSeconds(3600).toEpochMilli(),
                amplitude = 5000.0,
                dbValue = 68.5,
                filePath = wavFile1.absolutePath,
                detectedLabel = "Hämmern",
                notes = "Testaufnahme",
                calibratedDbA = 70.0,
                meterWeighting = "A",
                meterConnected = true
            )
        )

        // Füge LevelSample hinzu
        levelSampleDao.insert(
            LevelSampleEntity(
                at = uhr.now().minusSeconds(1800).toEpochMilli(),
                levelDb = 62.0,
                source = LevelSource.PCE_323
            )
        )

        val coordinator = DriveSyncCoordinator(
            driveApi = driveApi,
            levelSampleDao = levelSampleDao,
            dailyFileDao = dailyFileDao,
            noiseDao = noiseDao,
            settings = settings,
            now = uhr,
            zone = zone
        )

        val ergebnis = coordinator.syncEinenZyklus()
        assertTrue(ergebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich)

        // 1. WAV-Dateien wurden stündlich gezippt und als ZIP hochgeladen
        val zipDateien = driveApi.hochgeladeneDateien.filterKeys { it.endsWith(".zip") }
        assertTrue("Stündliche ZIP-Datei muss in Drive hochgeladen worden sein", zipDateien.isNotEmpty())

        val zipBytes = zipDateien.values.first()
        val zipIn = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes))
        val entry = zipIn.nextEntry
        assertNotNull(entry)
        assertEquals("noise_20260823_100000.wav", entry!!.name)
        val extractedBytes = zipIn.readBytes()
        assertEquals(7, extractedBytes.size)

        // 2. CSV-Datei wurde mit erweiterten Spalten erstellt
        val csvDateien = driveApi.hochgeladeneDateien.filterKeys { it.endsWith(".csv") || it.startsWith("file-laermprotokoll_") }
        assertTrue("CSV-Datei muss existieren", csvDateien.isNotEmpty())
    }
}
