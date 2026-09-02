package com.example.lrmprotokoll.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import com.example.lrmprotokoll.alert.TestUhr
import com.example.lrmprotokoll.meter.InstantSource
import java.time.Duration
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
 * Prueft die Sync-Entscheidungslogik aus Plan 8.4 gegen einen Fake-[DriveApiClient] - kein
 * WorkManager, kein echtes Netz, wie [com.example.lrmprotokoll.alert.AlarmCoordinatorTest] fuer
 * M5. Zeit ueber [InstantSource] injiziert.
 *
 * Robolectric wegen echtem [SettingsManager] (SharedPreferences).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveSyncCoordinatorTest {

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

    private open class FakeNoiseDao : NoiseDao {
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
        var ordnerAnlegenErgebnis: Result<String> = Result.success("ordner-id")
        var dateiSuchenErgebnis: Result<DriveDatei?> = Result.success(null)
        var dateiAnlegenErgebnis: Result<String> = Result.success("neue-datei-id")
        var dateiAktualisierenErgebnis: Result<Unit> = Result.success(Unit)

        var anlegenAufrufe = 0
        var suchenAufrufe = 0
        var aktualisierenAufrufe = 0
        var letzterAktualisierterInhalt: ByteArray? = null

        var ordnerSuchenErgebnis: Result<DriveDatei?> = Result.success(null)
        var ordnerAuflistenErgebnis: Result<List<DriveDatei>> = Result.success(emptyList())
        var ordnerUmbenennenErgebnis: Result<Unit> = Result.success(Unit)
        var dateienInOrdnerAuflistenErgebnis: Result<Set<String>> = Result.success(emptySet())

        override suspend fun ordnerAnlegen(name: String) = ordnerAnlegenErgebnis
        override suspend fun ordnerSuchen(name: String) = ordnerSuchenErgebnis
        override suspend fun ordnerAuflisten() = ordnerAuflistenErgebnis
        override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String) = ordnerUmbenennenErgebnis
        override suspend fun dateienInOrdnerAuflisten(ordnerId: String): Result<Set<String>> = dateienInOrdnerAuflistenErgebnis
        override suspend fun dateiSuchen(name: String, ordnerId: String): Result<DriveDatei?> {
            suchenAufrufe++
            return dateiSuchenErgebnis
        }
        override suspend fun dateiAnlegen(
            name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ): Result<String> {
            anlegenAufrufe++
            letzterAktualisierterInhalt = inhalt
            return dateiAnlegenErgebnis
        }
        override suspend fun dateiAktualisieren(
            fileId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ): Result<Unit> {
            aktualisierenAufrufe++
            letzterAktualisierterInhalt = inhalt
            return dateiAktualisierenErgebnis
        }
    }

    private lateinit var levelSampleDao: FakeLevelSampleDao
    private lateinit var dailyFileDao: FakeDailyFileDao
    private lateinit var noiseDao: FakeNoiseDao
    private lateinit var driveApi: FakeDriveApiClient
    private lateinit var settings: SettingsManager
    private lateinit var uhr: TestUhr
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun baueKoordinator() = DriveSyncCoordinator(
        driveApi = driveApi, levelSampleDao = levelSampleDao, dailyFileDao = dailyFileDao,
        noiseDao = noiseDao, settings = settings, now = uhr, zone = zone,
    )

    @Before
    fun aufbauen() {
        levelSampleDao = FakeLevelSampleDao()
        dailyFileDao = FakeDailyFileDao()
        noiseDao = FakeNoiseDao()
        driveApi = FakeDriveApiClient()
        settings = SettingsManager(ApplicationProvider.getApplicationContext<Context>())
        settings.driveSyncEnabled = true
        settings.driveFolderId = "ordner-id"
        settings.driveAggregationSekunden = 10
        uhr = TestUhr(Instant.parse("2026-08-19T07:00:00Z")) // 09:00 MESZ
    }

    private fun fuegeSampleHinzu(sekundenSeitMitternacht: Long, db: Double) {
        val mitternacht = uhr.now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        levelSampleDao.eingefuegt += LevelSampleEntity(
            at = mitternacht.plusSeconds(sekundenSeitMitternacht).toEpochMilli(),
            levelDb = db, source = LevelSource.PCE_323,
        )
    }

    @Test
    fun ausgeschalteterSyncTutNichts() = runTest {
        settings.driveSyncEnabled = false
        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertEquals(DriveSyncCoordinator.SyncErgebnis.SyncAusgeschaltet, ergebnis)
        assertEquals(0, driveApi.anlegenAufrufe + driveApi.aktualisierenAufrufe + driveApi.suchenAufrufe)
    }

    @Test
    fun ohneEingerichtetenOrdnerWirdNichtsHochgeladen() = runTest {
        settings.driveFolderId = null
        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertEquals(DriveSyncCoordinator.SyncErgebnis.KeinOrdnerEingerichtet, ergebnis)
        assertEquals(0, driveApi.anlegenAufrufe)
    }

    @Test
    fun blockierterOrdnerWirdNichtErneutVersucht() = runTest {
        settings.driveOrdnerBlockiert = true
        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertEquals(DriveSyncCoordinator.SyncErgebnis.OrdnerBlockiert, ergebnis)
        assertEquals(0, driveApi.anlegenAufrufe + driveApi.suchenAufrufe)
    }

    @Test
    fun ersterZyklusLegtEineNeueDateiAn() = runTest {
        fuegeSampleHinzu(0, 55.0)

        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(ergebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich)
        assertEquals(1, driveApi.suchenAufrufe) // Dedup-Absicherung gegen Waisen (Plan 8.4.4)
        assertEquals(1, driveApi.anlegenAufrufe)
        assertEquals(0, driveApi.aktualisierenAufrufe)
        assertEquals("neue-datei-id", dailyFileDao.byDate("2026-08-19")?.fileId)
        assertEquals(DriveSyncState.SYNCED, dailyFileDao.byDate("2026-08-19")?.state)
    }

    @Test
    fun zweiterZyklusMitBekannterFileIdAktualisiertStattNeuAnzulegen() = runTest {
        fuegeSampleHinzu(0, 55.0)
        baueKoordinator().syncEinenZyklus()

        uhr.vor(Duration.ofMinutes(30))
        fuegeSampleHinzu(1800, 60.0) // neuer Wert -> Zeilenzahl aendert sich
        val zweitesErgebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(zweitesErgebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich)
        assertEquals(
            "Bei bekannter fileId darf nicht erneut gesucht oder angelegt werden",
            1, driveApi.anlegenAufrufe,
        )
        assertEquals(1, driveApi.aktualisierenAufrufe)
    }

    /**
     * "Zeilenzahl unveraendert" greift bewusst nur bei einem echten Doppelaufruf zur exakt
     * gleichen Zeit, nicht schon bei ruhigen Perioden: Bei fortlaufender Zeit erzeugt jeder
     * Zyklus neue Luecken-Fenster bis zum aktuellen Zeitpunkt (Plan 8.4.2 - Luecken muessen
     * sichtbar sein), die Zeilenzahl waechst also so gut wie immer. Der Schutz greift dort, wo
     * er tatsaechlich noetig ist: wenn WorkManager (oder ein Retry) denselben Zyklus doppelt
     * ausloest, ohne dass zwischendurch Zeit vergangen ist.
     */
    @Test
    fun doppelterZyklusZurSelbenZeitUeberspringtDenErneutenUpload() = runTest {
        fuegeSampleHinzu(0, 55.0)
        baueKoordinator().syncEinenZyklus() // Uhr steht zwischen den beiden Aufrufen still

        val zweitesErgebnis = baueKoordinator().syncEinenZyklus()

        assertEquals(DriveSyncCoordinator.SyncErgebnis.KeineAenderung, zweitesErgebnis)
        assertEquals(1, driveApi.aktualisierenAufrufe + driveApi.anlegenAufrufe)
    }

    @Test
    fun verstricheneZeitMitNeuemSampleErzeugtLueckenzeilenUndWirdHochgeladen() = runTest {
        fuegeSampleHinzu(0, 55.0)
        baueKoordinator().syncEinenZyklus()

        uhr.vor(Duration.ofMinutes(30))
        fuegeSampleHinzu(1800, 56.0)
        val zweitesErgebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(zweitesErgebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich)
    }

    /**
     * Regressionstest fuer eine "Zeitbombe": [DriveSyncCoordinator.syncEinenZyklus] leitete
     * "heute" frueher per `LocalDate.now(zone)` aus der ECHTEN Systemuhr ab, obwohl "jetzt" aus
     * der injizierten [TestUhr] kam. Das lief nur solange gut, wie das echte Kalenderdatum noch
     * nicht ueber das fixe Testuhr-Datum hinaus war - real am 2026-08-20 eingetreten und in 11
     * Tests fehlgeschlagen ("bis darf nicht vor von liegen" in [PegelAggregator.aggregiere]).
     * Hier absichtlich acht Jahre Abstand, damit der Test unabhaengig vom tatsaechlichen
     * Aufrufdatum stabil bleibt.
     */
    @Test
    fun syncFunktioniertAuchWennDasEchteKalenderdatumWeitVonDerTestuhrAbweicht() = runTest {
        uhr = TestUhr(Instant.parse("2018-01-01T07:00:00Z"))
        fuegeSampleHinzu(0, 55.0)

        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(ergebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich)
    }

    @Test
    fun waiseAusVorherigemAbgebrochenemZyklusWirdGefundenStattDoppeltAngelegt() = runTest {
        // Simuliert: ein frueherer Zyklus hat dateiAnlegen serverseitig abgeschlossen, aber die
        // Antwort kam nie an - keine fileId in der Registry, obwohl die Datei existiert.
        driveApi.dateiSuchenErgebnis = Result.success(DriveDatei(id = "waise-id", name = "x"))
        fuegeSampleHinzu(0, 55.0)

        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(ergebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich)
        assertEquals(
            "Die gefundene Waise muss aktualisiert werden, nicht dupliziert",
            0, driveApi.anlegenAufrufe,
        )
        assertEquals(1, driveApi.aktualisierenAufrufe)
        assertEquals("waise-id", dailyFileDao.byDate("2026-08-19")?.fileId)
    }

    @Test
    fun vierNullVierBeiBekannterFileIdVerwirftNurDieFileIdNichtDenOrdner() = runTest {
        fuegeSampleHinzu(0, 55.0)
        baueKoordinator().syncEinenZyklus() // legt an, fileId bekannt

        driveApi.dateiAktualisierenErgebnis = Result.failure(DriveApiException("weg", httpCode = 404))
        uhr.vor(Duration.ofMinutes(30))
        fuegeSampleHinzu(1800, 60.0)
        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(ergebnis is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen)
        assertEquals(
            "Nur die Datei ist weg - der Ordner bleibt benutzbar",
            false, settings.driveOrdnerBlockiert,
        )
        assertEquals(null, dailyFileDao.byDate("2026-08-19")?.fileId)
    }

    @Test
    fun vierNullVierOhneBekannteFileIdBlockiertDenOrdner() = runTest {
        driveApi.dateiAnlegenErgebnis = Result.failure(DriveApiException("Ordner weg", httpCode = 404))
        fuegeSampleHinzu(0, 55.0)

        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(ergebnis is DriveSyncCoordinator.SyncErgebnis.OrdnerNichtGefunden)
        assertTrue(
            "Ein fehlender Ordner darf nicht stillschweigend in 'Meine Ablage' geschrieben werden - " +
                "der Sync muss pausieren, bis der Nutzer neu waehlt",
            settings.driveOrdnerBlockiert,
        )
    }

    @Test
    fun netzwerkfehlerWirdAlsFehlschlagOhneOrdnerBlockadeVermerkt() = runTest {
        driveApi.dateiAnlegenErgebnis = Result.failure(DriveApiException("kein Netz", httpCode = null))
        fuegeSampleHinzu(0, 55.0)

        val ergebnis = baueKoordinator().syncEinenZyklus()

        assertTrue(ergebnis is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen)
        assertEquals(false, settings.driveOrdnerBlockiert)
        assertEquals(1, settings.driveSyncFehlschlaegeInFolge)
    }

    @Test
    fun erfolgSetztDenFehlschlagszaehlerZurueck() = runTest {
        settings.driveSyncFehlschlaegeInFolge = 5
        fuegeSampleHinzu(0, 55.0)

        baueKoordinator().syncEinenZyklus()

        assertEquals(0, settings.driveSyncFehlschlaegeInFolge)
        assertTrue(settings.driveSyncLastSuccessAt > 0)
    }

    @Test
    fun aggregationsintervallAusDenEinstellungenWirdVerwendet() = runTest {
        settings.driveAggregationSekunden = 60
        fuegeSampleHinzu(0, 50.0)
        fuegeSampleHinzu(30, 60.0) // im selben 60s-Fenster wie oben

        baueKoordinator().syncEinenZyklus()

        val inhalt = driveApi.letzterAktualisierterInhalt!!.toString(Charsets.UTF_8)
        // 09:00 Uhr minus Mitternacht bei 60s-Fenstern ergibt 540 Zeilen, davon genau eine mit
        // echten Daten (die anderen 539 sind KEINE_VERBINDUNG-Luecken) - die interessiert hier.
        val datenzeilen = inhalt.lines().drop(1).filter { it.isNotBlank() && !it.contains(QUELLE_KEINE_VERBINDUNG) }
        assertEquals("Beide Samples muessen in einem einzigen 60s-Fenster verdichtet sein", 1, datenzeilen.size)
        assertTrue(
            "Die Fensterzeile muss beide Samples widerspiegeln (Anzahl=2)",
            datenzeilen.single().split(";")[8] == "2",
        )
    }

    @Test
    fun gzipWirdBeimUploadVerwendet() = runTest {
        var gzipGesehen = false
        val pruefenderClient = object : DriveApiClient by driveApi {
            override suspend fun dateiAnlegen(
                name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
            ): Result<String> {
                gzipGesehen = gzip
                return driveApi.dateiAnlegen(name, ordnerId, inhalt, mimeType, gzip)
            }
        }
        fuegeSampleHinzu(0, 55.0)

        DriveSyncCoordinator(
            pruefenderClient, levelSampleDao, dailyFileDao, noiseDao, settings, uhr, zone,
        ).syncEinenZyklus()

        assertTrue(gzipGesehen)
    }

    @Test
    fun wavUploadWirdErfolgreichSynchronisiertAuchWennKeinePegelzeilenVorliegen() = runTest {
        val tempWav = java.io.File.createTempFile("test_audio", ".wav").apply {
            writeBytes(ByteArray(100) { 1 })
            deleteOnExit()
        }
        val customNoiseDao = object : FakeNoiseDao() {
            override suspend fun getAlleAktiven(): List<NoiseRecord> = listOf(
                NoiseRecord(
                    id = 1L,
                    timestamp = uhr.now().toEpochMilli(),
                    amplitude = 50.0,
                    dbValue = 65.0,
                    filePath = tempWav.absolutePath,
                )
            )
        }
        settings.driveUploadWav = true

        val koordinator = DriveSyncCoordinator(
            driveApi, levelSampleDao, dailyFileDao, customNoiseDao, settings, uhr, zone,
        )
        val ergebnis = koordinator.syncEinenZyklus()

        assertTrue("Ergebnis muss Erfolgreich sein", ergebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich)
        assertEquals("1 WAV-Datei synchronisiert", 1, (ergebnis as DriveSyncCoordinator.SyncErgebnis.Erfolgreich).zeilen)
        assertTrue(settings.driveSyncLastSuccessAt > 0)
    }
}
