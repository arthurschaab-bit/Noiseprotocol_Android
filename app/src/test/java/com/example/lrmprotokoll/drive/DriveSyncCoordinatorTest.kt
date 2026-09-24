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
import com.example.lrmprotokoll.meter.InstantSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

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
        override suspend fun getCalibratedDbA(id: Long): Double? = null
        override fun getAllReferences(): Flow<List<ReferenceSound>> = flowOf(emptyList())
        override fun getAllReferencesBlocking(): List<ReferenceSound> = emptyList()
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

        override suspend fun ordnerAnlegen(name: String, elternId: String?) = ordnerAnlegenErgebnis
        override suspend fun ordnerSuchen(name: String, elternId: String?) = ordnerSuchenErgebnis
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

        override suspend fun dateiHerunterladen(fileId: String): Result<ByteArray> =
            throw NotImplementedError("im Test nicht benoetigt")

        override suspend fun dateiHerunterladenNach(
            fileId: String,
            ziel: java.io.File,
        ): Result<Unit> = throw NotImplementedError("im Test nicht benoetigt")

        var resumableNeuanlagenErgebnis: Result<String> = Result.success("resumable-neue-datei-id")
        var resumableAktualisierenErgebnis: Result<Unit> = Result.success(Unit)
        var resumableNeuanlagenAufrufe = 0
        var resumableAktualisierenAufrufe = 0
        var letzteResumableAktualisierteFileId: String? = null

        // Bewusst der INHALT (nicht die File-Referenz): der Koordinator loescht die temporaere
        // Sicherungsdatei im finally, SOBALD dateiHochladenResumable/dateiAktualisierenResumable
        // zurueckgekehrt sind - eine spaetere Pruefung der Datei selbst wuerde ins Leere laufen.
        var letzterSicherungsInhalt: ByteArray? = null

        override suspend fun dateiHochladenResumable(
            name: String,
            ordnerId: String,
            datei: java.io.File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<String> {
            resumableNeuanlagenAufrufe++
            letzterSicherungsInhalt = datei.readBytes()
            return resumableNeuanlagenErgebnis
        }

        override suspend fun dateiAktualisierenResumable(
            fileId: String,
            datei: java.io.File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<Unit> {
            resumableAktualisierenAufrufe++
            letzteResumableAktualisierteFileId = fileId
            letzterSicherungsInhalt = datei.readBytes()
            return resumableAktualisierenErgebnis
        }
    }

    /** M11 Etappe B: Beweisvideos - dieselbe Fake-Disziplin wie bei den anderen DAOs. */
    private class FakeBeweisVideoDao(gespeichert: List<com.example.lrmprotokoll.data.BeweisVideoEntity> = emptyList()) :
        com.example.lrmprotokoll.data.BeweisVideoDao {
        val zeilen = gespeichert.associateBy { it.id }.toMutableMap()
        override suspend fun insert(video: com.example.lrmprotokoll.data.BeweisVideoEntity): Long {
            zeilen[video.id] = video
            return video.id
        }
        override suspend fun fuerSession(sessionId: Long) = zeilen.values.filter { it.sessionId == sessionId }
        override fun fuerSessionFlow(sessionId: Long) = flowOf(zeilen.values.filter { it.sessionId == sessionId })
        override fun neuesteFlow(grenze: Int) = flowOf(zeilen.values.sortedByDescending { it.gestartetAm }.take(grenze))
        override suspend fun byId(id: Long) = zeilen[id]
        override suspend fun nichtHochgeladene() = zeilen.values.filter { it.driveFileId == null && it.tonGemuxt }
        override suspend fun ungemuxte() = zeilen.values.filter { !it.tonGemuxt && !it.muxFehlgeschlagen }
        override suspend fun setzeMuxFehlgeschlagen(id: Long) {
            zeilen[id] = zeilen.getValue(id).copy(muxFehlgeschlagen = true)
        }
        override suspend fun setzeDriveFileId(id: Long, fileId: String) {
            zeilen[id] = zeilen.getValue(id).copy(driveFileId = fileId)
        }
        override suspend fun setzeUploadFortschritt(id: Long, sessionUri: String?, bytes: Long) {
            zeilen[id] = zeilen.getValue(id).copy(uploadSessionUri = sessionUri, hochgeladeneBytes = bytes)
        }
        override suspend fun setzeAufnahmeergebnis(id: Long, dauerMs: Long, groesseBytes: Long) {
            zeilen[id] = zeilen.getValue(id).copy(dauerMs = dauerMs, groesseBytes = groesseBytes)
        }
        override suspend fun setzeGemuxt(id: Long, dateiPfad: String, groesseBytes: Long, hatTonspur: Boolean) {
            zeilen[id] = zeilen.getValue(id).copy(
                dateiPfad = dateiPfad, groesseBytes = groesseBytes, hatTonspur = hatTonspur,
                tonGemuxt = true, muxFehlgeschlagen = false,
            )
        }
        override suspend fun loesche(id: Long) { zeilen.remove(id) }
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

    /** Wie [fuegeSampleHinzu], aber fuer einen beliebigen Tag relativ zu [uhr] - fuer die
     * 30-Tage-Nachhol-Tests (holeVersaeumteTageNach). */
    private fun fuegeSampleFuerTagHinzu(tageZurueck: Long, sekundenSeitMitternacht: Long, db: Double) {
        val mitternacht = uhr.now().atZone(zone).toLocalDate().minusDays(tageZurueck).atStartOfDay(zone).toInstant()
        levelSampleDao.eingefuegt += LevelSampleEntity(
            at = mitternacht.plusSeconds(sekundenSeitMitternacht).toEpochMilli(),
            levelDb = db, source = LevelSource.PCE_323,
        )
    }

    // ---------------------------------------------------------------- 30-Tage-Nachholsync (Praefprotokoll-Anhang)

    @Test
    fun einVersaeumterGestrigerTagWirdNachgeholt() = runTest {
        // Kein Sample fuer heute - nur fuer gestern (z.B. weil das Geraet ueber Mitternacht
        // offline war). Vor der Korrektur haette syncEinenZyklus() das nie gesehen.
        fuegeSampleFuerTagHinzu(tageZurueck = 1, sekundenSeitMitternacht = 3600, db = 55.0)

        val ergebnis = baueKoordinator().syncEinenZyklus()

        val gestern = uhr.now().atZone(zone).toLocalDate().minusDays(1)
        val gestrigerSchluessel = DriveAblage.tagesordner(gestern.atStartOfDay(zone).toInstant(), zone)
        assertEquals(
            "Der gestrige Tag muss als SYNCED registriert sein",
            DriveSyncState.SYNCED,
            dailyFileDao.zeilen[gestrigerSchluessel]?.state,
        )
        assertTrue("Es muss tatsaechlich eine Datei fuer gestern angelegt worden sein", driveApi.anlegenAufrufe >= 1)
        // Heute selbst bleibt unveraendert (keine Samples heute -> KeineAenderung), der
        // Nachholsync darf das Ergebnis fuer HEUTE nicht verfaelschen.
        assertEquals(DriveSyncCoordinator.SyncErgebnis.KeineAenderung, ergebnis)
    }

    @Test
    fun einBereitsVollstaendigSynchronisierterVersaeumterTagWirdNichtErneutHochgeladen() = runTest {
        fuegeSampleFuerTagHinzu(tageZurueck = 1, sekundenSeitMitternacht = 3600, db = 55.0)
        val gestern = uhr.now().atZone(zone).toLocalDate().minusDays(1)
        val gestrigerSchluessel = DriveAblage.tagesordner(gestern.atStartOfDay(zone).toInstant(), zone)
        // Bereits als SYNCED mit der (hier bekannten) korrekten Zeilenzahl 1 registriert.
        dailyFileDao.zeilen[gestrigerSchluessel] = DriveDailyFileEntity(
            date = gestrigerSchluessel, fileId = "schon-da", lastSyncedAt = 0L,
            lastRowCount = 1, state = DriveSyncState.SYNCED,
        )

        baueKoordinator().syncEinenZyklus()

        assertEquals(
            "Ein bereits vollstaendig synchronisierter Tag darf keinen neuen Upload ausloesen",
            0, driveApi.anlegenAufrufe,
        )
        assertEquals(0, driveApi.aktualisierenAufrufe)
    }

    @Test
    fun einFehlschlagBeiEinemVersaeumtenTagVerhindertNichtDenSyncFuerHeute() = runTest {
        fuegeSampleFuerTagHinzu(tageZurueck = 1, sekundenSeitMitternacht = 3600, db = 55.0)
        fuegeSampleHinzu(sekundenSeitMitternacht = 3600, db = 60.0) // heutiges Sample
        driveApi.dateiAnlegenErgebnis = Result.failure(RuntimeException("simulierter Netzfehler"))

        val ergebnis = baueKoordinator().syncEinenZyklus()

        // Der gestrige Nachholversuch scheitert (dateiAnlegenErgebnis ist ein Fehler) - das darf
        // syncEinenZyklus() nicht abbrechen lassen, heute muss trotzdem verarbeitet werden.
        assertTrue(
            "Heute muss trotz gescheitertem Nachholversuch fuer gestern verarbeitet werden",
            ergebnis is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen || ergebnis is DriveSyncCoordinator.SyncErgebnis.Erfolgreich,
        )
    }

    // ---------------------------------------------------------------- loescheVor()-Verdrahtung (Praefprotokoll C-4)

    @Test
    fun einSampleAelterAls30TageWirdBeimSyncGeloescht() = runTest {
        // 35 Tage zurueck: ausserhalb des 29-Tage-Nachholfensters von holeVersaeumteTageNach(),
        // dieser Wert ist also so oder so schon vom Nachholsync nicht mehr erreichbar.
        fuegeSampleFuerTagHinzu(tageZurueck = 35, sekundenSeitMitternacht = 3600, db = 55.0)

        baueKoordinator().syncEinenZyklus()

        assertTrue(
            "Ein Sample aelter als 30 Tage muss nach syncEinenZyklus() geloescht sein",
            levelSampleDao.eingefuegt.isEmpty(),
        )
    }

    @Test
    fun einSampleInnerhalbVon30TagenUeberlebtDenSync() = runTest {
        // 10 Tage zurueck: liegt innerhalb des 29-Tage-Nachholfensters - loescheVor() darf diesen
        // Wert nicht wegraeumen, bevor holeVersaeumteTageNach() ihn je sehen konnte.
        fuegeSampleFuerTagHinzu(tageZurueck = 10, sekundenSeitMitternacht = 3600, db = 55.0)

        baueKoordinator().syncEinenZyklus()

        assertEquals(
            "Ein Sample innerhalb der 30-Tage-Frist darf nicht geloescht werden",
            1, levelSampleDao.eingefuegt.size,
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

    /**
     * Bugfix (Owner-Meldung 12.09.2026, "WAV fehlt in Drive"): ein fehlgeschlagener WAV-ZIP-
     * Upload landete bisher NUR in Logcat (Log.w), nie im Diagnoseprotokoll/Support-Bundle -
     * anders als bei Fotos ([ladeFotosHoch]) oder der Datenbank-Sicherung. Genau das hat die
     * Fehlersuche zum gemeldeten Fall verhindert. Dieser Test belegt, dass ein fehlgeschlagener
     * WAV-Upload jetzt als [com.example.lrmprotokoll.diagnose.DiagnosticCode.DRIVE_UPLOAD_FAILED]
     * im [com.example.lrmprotokoll.diagnose.DiagnosticsReporter] auftaucht.
     */
    @Test
    fun fehlgeschlagenerWavUploadWirdImDiagnoseprotokollGemeldet() = runTest {
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
        driveApi.dateiAnlegenErgebnis = Result.failure(
            DriveApiException("Kein Zugriffstoken verfügbar", httpCode = 401)
        )
        val reporter = com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter(
            initialContext = com.example.lrmprotokoll.diagnose.DiagnosticContext(appVersion = "1.0", buildType = "debug"),
        )

        val koordinator = DriveSyncCoordinator(
            driveApi = driveApi, levelSampleDao = levelSampleDao, dailyFileDao = dailyFileDao,
            noiseDao = customNoiseDao, settings = settings, now = uhr, zone = zone,
            diagnosticsReporter = reporter,
        )
        koordinator.syncEinenZyklus()

        val gemeldet = reporter.recentEvents().filter {
            it.code == com.example.lrmprotokoll.diagnose.DiagnosticCode.DRIVE_UPLOAD_FAILED
        }
        assertTrue("Ein fehlgeschlagener WAV-Upload muss gemeldet werden", gemeldet.isNotEmpty())
        assertEquals("DriveSyncCoordinator", gemeldet.single().component)
    }

    // ------------------------------------------------------------------ M11 Etappe B: Videos

    private fun videoEintrag(
        id: Long = 1,
        pfad: String,
        tonGemuxt: Boolean = true,
        driveFileId: String? = null,
        uploadSessionUri: String? = null,
    ) = com.example.lrmprotokoll.data.BeweisVideoEntity(
        id = id, sessionId = 5, dateiPfad = pfad, gestartetAm = uhr.now().toEpochMilli(),
        dauerMs = 30_000, hatTonspur = true, groesseBytes = 4096, tonGemuxt = tonGemuxt,
        driveFileId = driveFileId, uploadSessionUri = uploadSessionUri,
    )

    private fun tempVideo() = java.io.File.createTempFile("beweisvideo", ".mp4").apply {
        writeBytes(ByteArray(4096) { 7 })
        deleteOnExit()
    }

    /** Merkt sich, womit der resumable Upload aufgerufen wurde, und spielt die Rueckrufe durch. */
    private class ResumableClient(
        val basis: DriveApiClient,
        val ergebnis: Result<String> = Result.success("drive-video-1"),
    ) : DriveApiClient by basis {
        var aufrufe = 0
        var letzterName: String? = null
        var letzterMimeTyp: String? = null
        var letztesFortsetzenAb: String? = null

        override suspend fun dateiHochladenResumable(
            name: String,
            ordnerId: String,
            datei: java.io.File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<String> {
            aufrufe++
            letzterName = name
            letzterMimeTyp = mimeType
            letztesFortsetzenAb = fortsetzenAb
            if (fortsetzenAb == null) sessionGestartet("https://drive.example/session/neu")
            fortschritt(2048, datei.length())
            return ergebnis
        }
    }

    private fun koordinatorMitVideos(dao: FakeBeweisVideoDao, client: DriveApiClient) = DriveSyncCoordinator(
        driveApi = client, levelSampleDao = levelSampleDao, dailyFileDao = dailyFileDao,
        noiseDao = noiseDao, settings = settings, now = uhr, zone = zone, beweisVideoDao = dao,
    )

    @Test
    fun beiDeaktiviertemVideoUploadGehtKeinVideoRaus() = runTest {
        // videoDriveUpload ist seit 09.09.2026 default AN (Owner-Entscheidung), aber weiterhin
        // abschaltbar: Ein Video kann Dritte, Kennzeichen und Wohnungsinneres zeigen - die
        // datenschutzsensibelste Datenart der App. Wer widerspricht, muss wirksam widersprechen
        // koennen.
        settings.videoDriveUpload = false
        val dao = FakeBeweisVideoDao(listOf(videoEintrag(pfad = tempVideo().absolutePath)))
        val client = ResumableClient(driveApi)

        koordinatorMitVideos(dao, client).syncEinenZyklus()

        assertEquals(0, client.aufrufe)
        assertEquals(null, dao.zeilen.getValue(1L).driveFileId)
    }

    @Test
    fun mitZustimmungWirdUeberDenResumablePfadHochgeladen() = runTest {
        // Nicht ueber dateiAnlegen: Das waere bei einem Video ein sicherer OutOfMemoryError.
        settings.videoDriveUpload = true
        val video = tempVideo()
        val dao = FakeBeweisVideoDao(listOf(videoEintrag(pfad = video.absolutePath)))
        val client = ResumableClient(driveApi)

        koordinatorMitVideos(dao, client).syncEinenZyklus()

        assertEquals(1, client.aufrufe)
        assertEquals("video/mp4", client.letzterMimeTyp)
        assertEquals(video.name, client.letzterName)
        assertEquals("drive-video-1", dao.zeilen.getValue(1L).driveFileId)
    }

    @Test
    fun einNochNichtGemuxtesVideoBleibtLiegen() = runTest {
        // Sonst landete die stumme Zwischenfassung in Drive.
        settings.videoDriveUpload = true
        val dao = FakeBeweisVideoDao(listOf(videoEintrag(pfad = tempVideo().absolutePath, tonGemuxt = false)))
        val client = ResumableClient(driveApi)

        koordinatorMitVideos(dao, client).syncEinenZyklus()

        assertEquals(0, client.aufrufe)
    }

    @Test
    fun einBereitsHochgeladenesVideoWirdNichtNochEinmalGesendet() = runTest {
        settings.videoDriveUpload = true
        val dao = FakeBeweisVideoDao(listOf(videoEintrag(pfad = tempVideo().absolutePath, driveFileId = "schon-da")))
        val client = ResumableClient(driveApi)

        koordinatorMitVideos(dao, client).syncEinenZyklus()

        assertEquals(0, client.aufrufe)
    }

    @Test
    fun einAngefangenerUploadWirdFortgesetztStattNeuBegonnen() = runTest {
        settings.videoDriveUpload = true
        val dao = FakeBeweisVideoDao(
            listOf(videoEintrag(pfad = tempVideo().absolutePath, uploadSessionUri = "https://drive.example/session/alt"))
        )
        val client = ResumableClient(driveApi)

        koordinatorMitVideos(dao, client).syncEinenZyklus()

        assertEquals("https://drive.example/session/alt", client.letztesFortsetzenAb)
    }

    @Test
    fun derFortschrittWirdGespeichertDamitEinAbbruchFortsetzbarBleibt() = runTest {
        // WorkManager gibt einem Worker nur rund zehn Minuten; ein grosses Video ueberlebt das
        // nicht in einem Durchgang.
        settings.videoDriveUpload = true
        val dao = FakeBeweisVideoDao(listOf(videoEintrag(pfad = tempVideo().absolutePath)))
        val client = ResumableClient(driveApi, ergebnis = Result.failure(DriveApiException("Verbindung weg")))

        koordinatorMitVideos(dao, client).syncEinenZyklus()

        val zeile = dao.zeilen.getValue(1L)
        assertEquals("https://drive.example/session/neu", zeile.uploadSessionUri)
        assertEquals(2048L, zeile.hochgeladeneBytes)
        assertEquals("Ohne Erfolg keine Datei-ID", null, zeile.driveFileId)
    }

    @Test
    fun einVideoOhneDateiWirdUebersprungenStattDenZyklusZuSprengen() = runTest {
        settings.videoDriveUpload = true
        val dao = FakeBeweisVideoDao(listOf(videoEintrag(pfad = "/pfad/gibt/es/nicht.mp4")))
        val client = ResumableClient(driveApi)

        val ergebnis = koordinatorMitVideos(dao, client).syncEinenZyklus()

        assertEquals(0, client.aufrufe)
        assertTrue(ergebnis !is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen)
    }

    // ---------------------------------------------------------------- Datenbank-Sicherung (Drive)
    //
    // Bugfix 23.09.2026 (docs/PROMPT_FIX_DATENBANK_SICHERUNG.md): datenbankSicherungQuelle liefert
    // seitdem eine bereits gebaute Datei statt eines ByteArray - der Koordinator loescht sie nach
    // dem Versuch wieder. Da die Datei zu diesem Zeitpunkt schon geloescht ist, liest das Fake
    // ihren Inhalt SOFORT beim Aufruf (letzterSicherungsInhalt), nicht erst in der Testassertion.

    private fun koordinatorMitDatenbankSicherung(quelle: (suspend () -> java.io.File)?) = DriveSyncCoordinator(
        driveApi = driveApi, levelSampleDao = levelSampleDao, dailyFileDao = dailyFileDao,
        noiseDao = noiseDao, settings = settings, now = uhr, zone = zone,
        datenbankSicherungQuelle = quelle,
    )

    private fun tempSicherungsDatei(inhalt: ByteArray = byteArrayOf(1, 2, 3, 4)) =
        java.io.File.createTempFile("test_sicherung", ".zip").apply {
            writeBytes(inhalt)
            deleteOnExit()
        }

    @Test
    fun ohneQuelleGehtKeineDatenbankSicherungRaus() = runTest {
        // datenbankSicherungQuelle ist optional (wie dokumentationsFotoDao/beweisVideoDao) -
        // `null` (der Default in AppContainer NICHT gesetzt) darf den Zyklus nicht crashen.
        koordinatorMitDatenbankSicherung(null).syncEinenZyklus()

        assertEquals(0, driveApi.resumableNeuanlagenAufrufe)
        assertEquals(0, driveApi.resumableAktualisierenAufrufe)
    }

    @Test
    fun beiDeaktiviertemSchalterGehtKeineDatenbankSicherungRausObwohlEineQuelleDaIst() = runTest {
        settings.datenbankSicherungDriveUpload = false
        var quelleAufgerufen = false

        koordinatorMitDatenbankSicherung({ quelleAufgerufen = true; tempSicherungsDatei() }).syncEinenZyklus()

        assertTrue("Die Quelle darf gar nicht erst aufgerufen werden", !quelleAufgerufen)
        assertEquals(0, driveApi.resumableNeuanlagenAufrufe)
    }

    @Test
    fun mitAktiviertemSchalterWirdDieDatenbankSicherungHochgeladen() = runTest {
        settings.datenbankSicherungDriveUpload = true
        val bytes = byteArrayOf(1, 2, 3, 4)

        koordinatorMitDatenbankSicherung({ tempSicherungsDatei(bytes) }).syncEinenZyklus()

        assertEquals(1, driveApi.resumableNeuanlagenAufrufe)
        assertArrayEquals(bytes, driveApi.letzterSicherungsInhalt)
    }

    @Test
    fun eineBestehendeSicherungWirdAktualisiertStattEinerZweiten() = runTest {
        settings.datenbankSicherungDriveUpload = true
        driveApi.dateiSuchenErgebnis = Result.success(DriveDatei(id = "bestehend", name = BACKUP_DATEINAME))

        koordinatorMitDatenbankSicherung({ tempSicherungsDatei(byteArrayOf(9)) }).syncEinenZyklus()

        assertEquals(0, driveApi.resumableNeuanlagenAufrufe)
        assertEquals(1, driveApi.resumableAktualisierenAufrufe)
        assertEquals("bestehend", driveApi.letzteResumableAktualisierteFileId)
    }

    @Test
    fun einFehlerBeimErstellenDerSicherungReisstDenRestDesZyklusNichtMit() = runTest {
        settings.datenbankSicherungDriveUpload = true

        val ergebnis = koordinatorMitDatenbankSicherung({ throw java.io.IOException("Checkpoint fehlgeschlagen") })
            .syncEinenZyklus()

        assertTrue(ergebnis !is DriveSyncCoordinator.SyncErgebnis.Fehlgeschlagen)
    }

    /**
     * PROMPT_FIX_DATENBANK_SICHERUNG.md Test 6 (Platzmangel): die Quelle wirft dieselbe Ausnahme,
     * die [com.example.lrmprotokoll.backup.SicherungManager.baueSicherungsDatei] bei zu wenig
     * Speicherplatz wirft (die "injizierte Pruefung" fuer diesen Koordinator-Test - die echte
     * Platz-Pruefung selbst ist in `SicherungManagerTest.baueSicherungsDateiBrichtBeiZuWenigSpeicherplatzAbUndSchreibtNichts`
     * abgedeckt). Erwartet: BACKUP_CREATE_FAILED mit erkennbarem Grund, kein Upload,
     * `datenbankSicherungLastAttemptAt` trotzdem gesetzt (Drosselung greift auch nach einem
     * Fehlschlag).
     */
    @Test
    fun platzmangelBeimBauenMeldetBackupCreateFailedUndLaedtNichtsHoch() = runTest {
        settings.datenbankSicherungDriveUpload = true
        val diagnoseContext = com.example.lrmprotokoll.diagnose.DiagnosticContext(appVersion = "1.0", buildType = "debug")
        val reporter =
            com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter(
                initialContext = diagnoseContext,
            )
        val koordinator =
            DriveSyncCoordinator(
                driveApi = driveApi,
                levelSampleDao = levelSampleDao,
                dailyFileDao = dailyFileDao,
                noiseDao = noiseDao,
                settings = settings,
                now = uhr,
                zone = zone,
                diagnosticsReporter = reporter,
                datenbankSicherungQuelle = {
                    throw com.example.lrmprotokoll.backup.UnzureichenderSpeicherplatzException(
                        freierPlatz = 10_000_000L,
                        benoetigterPlatz = 541_176_627L,
                    )
                },
            )
        assertEquals(0L, settings.datenbankSicherungLastAttemptAt)

        koordinator.syncEinenZyklus()

        assertEquals("Ohne genug Platz darf kein Upload versucht werden", 0, driveApi.resumableNeuanlagenAufrufe)
        assertEquals(0, driveApi.resumableAktualisierenAufrufe)
        assertTrue(
            "datenbankSicherungLastAttemptAt muss trotz Fehlschlag gesetzt sein (Drosselung)",
            settings.datenbankSicherungLastAttemptAt > 0,
        )
        val gemeldet =
            reporter.recentEvents().filter {
                it.code == com.example.lrmprotokoll.diagnose.DiagnosticCode.BACKUP_CREATE_FAILED
            }
        assertTrue("Ein Platzmangel muss als BACKUP_CREATE_FAILED gemeldet werden", gemeldet.isNotEmpty())
        assertEquals("DriveSyncCoordinator", gemeldet.single().component)
        assertTrue(
            "Der Grund muss den Platzmangel erkennen lassen",
            gemeldet.single().message?.contains("Speicherplatz") == true,
        )
    }

    /**
     * Owner-Meldung 12.09.2026 ("Sicherung läuft sporadisch, nicht täglich"): ohne einen eigenen
     * Zeitstempel fuer die Datenbank-Sicherung liess sich in der UI nicht von
     * [SettingsManager.driveSyncLastSuccessAt] unterscheiden, wann die Sicherung selbst zuletzt
     * tatsaechlich gelang - siehe [SettingsManager.datenbankSicherungLastSuccessAt].
     */
    @Test
    fun erfolgreicherSicherungsUploadSetztEigenenZeitstempel() = runTest {
        settings.datenbankSicherungDriveUpload = true
        assertEquals(0L, settings.datenbankSicherungLastSuccessAt)

        koordinatorMitDatenbankSicherung({ tempSicherungsDatei(byteArrayOf(1, 2, 3)) }).syncEinenZyklus()

        assertEquals(uhr.now().toEpochMilli(), settings.datenbankSicherungLastSuccessAt)
    }

    @Test
    fun fehlgeschlagenerSicherungsUploadSetztDenZeitstempelNicht() = runTest {
        settings.datenbankSicherungDriveUpload = true
        driveApi.resumableNeuanlagenErgebnis = Result.failure(java.io.IOException("Netzwerkfehler"))

        koordinatorMitDatenbankSicherung({ tempSicherungsDatei(byteArrayOf(1, 2, 3)) }).syncEinenZyklus()

        assertEquals(0L, settings.datenbankSicherungLastSuccessAt)
    }

    /**
     * Bugfix-Regressionstest (Owner-Meldung 16.09.2026, "Upload schmiert nach 1-2h ab" - siehe
     * [SettingsManager.datenbankSicherungLastAttemptAt]-KDoc): [DriveSyncWorker.starteSofort]
     * loest bei jedem Laermereignis sofort einen ganzen Sync-Zyklus aus. Ohne Drosselung baute
     * jeder dieser Zyklen die komplette Datenbank neu auf und lud sie hoch - bei Ereignissen im
     * Minutentakt unnoetig wiederholter Festplatten-I/O und Upload-Traffic (Support-Bundle).
     */
    @Test
    fun zweiterVersuchKurzNachDemErstenWirdUebersprungen() = runTest {
        settings.datenbankSicherungDriveUpload = true
        var quelleAufrufe = 0

        val koordinator = koordinatorMitDatenbankSicherung { quelleAufrufe++; tempSicherungsDatei() }
        koordinator.syncEinenZyklus()
        koordinator.syncEinenZyklus()

        assertEquals("Die Quelle darf beim zweiten Versuch innerhalb des Intervalls nicht erneut aufgerufen werden", 1, quelleAufrufe)
        assertEquals(1, driveApi.resumableNeuanlagenAufrufe)
    }

    /**
     * Die Drosselung greift auf den letzten VERSUCH, nicht nur auf den letzten ERFOLG - im
     * Bundle waren die meisten Zyklen Fehlschlaege ("Job was cancelled" durch das REPLACE der
     * naechsten Sofort-Anfrage). Ein Zeitstempel nur fuer Erfolge haette in genau diesem Fall
     * gar nicht gedrosselt.
     */
    @Test
    fun zweiterVersuchNachFehlgeschlagenemErstenVersuchWirdEbenfallsUebersprungen() = runTest {
        settings.datenbankSicherungDriveUpload = true
        var zweiteQuelleAufgerufen = false

        koordinatorMitDatenbankSicherung { throw java.io.IOException("Checkpoint fehlgeschlagen") }
            .syncEinenZyklus()
        koordinatorMitDatenbankSicherung { zweiteQuelleAufgerufen = true; tempSicherungsDatei() }
            .syncEinenZyklus()

        assertTrue("Nach einem fehlgeschlagenen Versuch darf der naechste innerhalb des Intervalls nicht erneut versuchen", !zweiteQuelleAufgerufen)
    }

    @Test
    fun nachAblaufDesIntervallsWirdErneutVersucht() = runTest {
        settings.datenbankSicherungDriveUpload = true
        var quelleAufrufe = 0

        val koordinator = koordinatorMitDatenbankSicherung { quelleAufrufe++; tempSicherungsDatei() }
        koordinator.syncEinenZyklus()
        uhr.vor(Duration.ofMinutes(31))
        koordinator.syncEinenZyklus()

        assertEquals(2, quelleAufrufe)
    }

    // ---------------------------------------------------------------- OOM-Bugfix Schritt 1: nur ein Zyklus gleichzeitig

    /**
     * Zaehlt, wie viele [zwischen]-Aufrufe gleichzeitig aktiv sind, und legt dabei eine echte
     * (kurze) Verzoegerung ein - so wird ein fehlender Mutex in
     * [DriveSyncCoordinator.syncEinenZyklus] sichtbar (PROMPT_FIX_OOM_DRIVE_SYNC.md Abschnitt 3,
     * Test 5), ohne echte Nebenlaeufigkeit auf echten Threads zu brauchen: [runTest] fuehrt alles
     * kooperativ auf einem Thread aus, Ueberlappung entsteht rein durch die Suspension in
     * [delay]. Kein `AtomicInteger` noetig - ohne echte Parallelitaet sind die Zaehler-Zugriffe
     * bereits sicher.
     */
    private class GleichzeitigkeitZaehlendesLevelSampleDao : LevelSampleDao {
        val eingefuegt = mutableListOf<LevelSampleEntity>()
        private var aktiveAufrufe = 0
        var maxGleichzeitigeAufrufe = 0
            private set

        override suspend fun insert(sample: LevelSampleEntity) {
            eingefuegt += sample
        }

        override suspend fun insertAll(samples: List<LevelSampleEntity>) {
            eingefuegt += samples
        }

        override suspend fun zwischen(
            von: Long,
            bis: Long,
        ): List<LevelSampleEntity> {
            aktiveAufrufe++
            if (aktiveAufrufe > maxGleichzeitigeAufrufe) maxGleichzeitigeAufrufe = aktiveAufrufe
            delay(10)
            aktiveAufrufe--
            return eingefuegt.filter { it.at in von until bis }
        }

        override suspend fun loescheVor(vor: Long) {
            eingefuegt.removeAll { it.at < vor }
        }

        override suspend fun anzahl(): Int = eingefuegt.size
    }

    /**
     * Regressionstest fuer Befund A1 (BEFUNDE_P30_2026-09-23.md): Auf dem Owner-Geraet liefen bis
     * zu vier Sync-Zyklen gleichzeitig, jeder mit einer bis zu 29-taegigen Rohwerte-Nachhol-
     * Schleife - der Heap lief voll (CursorWindow-Ueberlauf, anschliessend OutOfMemoryError).
     */
    @Test
    fun syncEinenZyklusLaeuftNieParallel() =
        runTest {
            val gleichzeitigkeitsDao = GleichzeitigkeitZaehlendesLevelSampleDao()
            val mitternacht =
                uhr
                    .now()
                    .atZone(zone)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant()
            gleichzeitigkeitsDao.eingefuegt +=
                LevelSampleEntity(
                    at = mitternacht.toEpochMilli(),
                    levelDb = 55.0,
                    source = LevelSource.PCE_323,
                )
            val koordinator =
                DriveSyncCoordinator(
                    driveApi = driveApi,
                    levelSampleDao = gleichzeitigkeitsDao,
                    dailyFileDao = dailyFileDao,
                    noiseDao = noiseDao,
                    settings = settings,
                    now = uhr,
                    zone = zone,
                )

            val ersterLauf = async { koordinator.syncEinenZyklus() }
            val zweiterLauf = async { koordinator.syncEinenZyklus() }
            ersterLauf.await()
            zweiterLauf.await()

            assertEquals(
                "syncEinenZyklus() darf level_samples nie aus zwei gleichzeitigen Laeufen heraus lesen",
                1,
                gleichzeitigkeitsDao.maxGleichzeitigeAufrufe,
            )
        }

    // ---------------------------------------------------------------- OOM-Bugfix Schritt 3: endgueltige Tage ueberspringen

    /** Protokolliert jeden [zwischen]-Aufruf mit seinem `(von, bis)`-Zeitraum (Test 1/2). */
    private class ProtokollierendesLevelSampleDao : LevelSampleDao {
        val eingefuegt = mutableListOf<LevelSampleEntity>()
        val zwischenAufrufe = mutableListOf<Pair<Long, Long>>()

        override suspend fun insert(sample: LevelSampleEntity) {
            eingefuegt += sample
        }

        override suspend fun insertAll(samples: List<LevelSampleEntity>) {
            eingefuegt += samples
        }

        override suspend fun zwischen(
            von: Long,
            bis: Long,
        ): List<LevelSampleEntity> {
            zwischenAufrufe += von to bis
            return eingefuegt.filter { it.at in von until bis }
        }

        override suspend fun loescheVor(vor: Long) {
            eingefuegt.removeAll { it.at < vor }
        }

        override suspend fun anzahl(): Int = eingefuegt.size
    }

    /** True, wenn ein protokollierter `(von, bis)`-Aufruf den Zeitraum [tagVon, tagBis) beruehrt. */
    private fun ueberschneidetTag(
        aufruf: Pair<Long, Long>,
        tagVon: Instant,
        tagBis: Instant,
    ): Boolean {
        val (von, bis) = aufruf
        return von < tagBis.toEpochMilli() && bis > tagVon.toEpochMilli()
    }

    /**
     * PROMPT_FIX_OOM_DRIVE_SYNC.md Abschnitt 3, Test 1: Ein Tag, der NACH seinem Tagesende
     * erfolgreich synchronisiert wurde, ist endgueltig fertig - Rohwerte eines vergangenen Tages
     * kommen nicht nachtraeglich hinzu (Befund A1: bislang lud `holeVersaeumteTageNach()` fuer
     * jeden der letzten 29 Tage erst die komplette Rohwerteliste, bevor es ueberhaupt prueft, ob
     * der Tag schon fertig ist).
     */
    @Test
    fun nachTagesendeSynchronisierterTagWirdOhneRohwertAbfrageUebersprungen() =
        runTest {
            val protokollDao = ProtokollierendesLevelSampleDao()
            val tag =
                uhr
                    .now()
                    .atZone(zone)
                    .toLocalDate()
                    .minusDays(5)
            val tagVon = tag.atStartOfDay(zone).toInstant()
            val tagBis = tag.plusDays(1).atStartOfDay(zone).toInstant()
            val schluessel = DriveAblage.tagesordner(tagVon, zone)
            // Ein Sample ist absichtlich vorhanden - selbst WENN der Tag geladen wuerde, gaebe es
            // etwas zu finden. Der Test soll gerade zeigen, dass gar nicht erst geladen wird.
            protokollDao.eingefuegt +=
                LevelSampleEntity(
                    at = tagVon.plusSeconds(3600).toEpochMilli(),
                    levelDb = 55.0,
                    source = LevelSource.PCE_323,
                )
            dailyFileDao.zeilen[schluessel] =
                DriveDailyFileEntity(
                    date = schluessel,
                    fileId = "schon-da",
                    lastSyncedAt = tagBis.toEpochMilli() + 1,
                    lastRowCount = 1,
                    state = DriveSyncState.SYNCED,
                )
            val koordinator =
                DriveSyncCoordinator(
                    driveApi = driveApi,
                    levelSampleDao = protokollDao,
                    dailyFileDao = dailyFileDao,
                    noiseDao = noiseDao,
                    settings = settings,
                    now = uhr,
                    zone = zone,
                )

            koordinator.syncEinenZyklus()

            assertTrue(
                "Ein nach Tagesende synchronisierter Tag darf keine Rohwerte-Abfrage fuer seinen " +
                    "eigenen Zeitraum ausloesen",
                protokollDao.zwischenAufrufe.none { ueberschneidetTag(it, tagVon, tagBis) },
            )
        }

    /**
     * PROMPT_FIX_OOM_DRIVE_SYNC.md Abschnitt 3, Test 2: Ein noch nicht registrierter Tag wird
     * beim ersten Lauf ganz normal nachgeholt (inkl. Rohwerte-Laden) - danach ist er endgueltig
     * und ein zweiter Lauf darf ihn nicht mehr laden.
     */
    @Test
    fun versaeumterTagWirdEinmalNachgeholtDanachEndgueltigUebersprungen() =
        runTest {
            val protokollDao = ProtokollierendesLevelSampleDao()
            val tag =
                uhr
                    .now()
                    .atZone(zone)
                    .toLocalDate()
                    .minusDays(5)
            val tagVon = tag.atStartOfDay(zone).toInstant()
            val tagBis = tag.plusDays(1).atStartOfDay(zone).toInstant()
            val schluessel = DriveAblage.tagesordner(tagVon, zone)
            protokollDao.eingefuegt +=
                LevelSampleEntity(
                    at = tagVon.plusSeconds(3600).toEpochMilli(),
                    levelDb = 55.0,
                    source = LevelSource.PCE_323,
                )
            val koordinator =
                DriveSyncCoordinator(
                    driveApi = driveApi,
                    levelSampleDao = protokollDao,
                    dailyFileDao = dailyFileDao,
                    noiseDao = noiseDao,
                    settings = settings,
                    now = uhr,
                    zone = zone,
                )

            koordinator.syncEinenZyklus() // erster Lauf: der Tag ist noch nicht registriert

            assertTrue(
                "Der erste Lauf muss den bislang unregistrierten Tag tatsaechlich laden",
                protokollDao.zwischenAufrufe.any { ueberschneidetTag(it, tagVon, tagBis) },
            )
            assertEquals(DriveSyncState.SYNCED, dailyFileDao.zeilen[schluessel]?.state)
            assertTrue(
                "lastSyncedAt muss nach Tagesende liegen - der Sync fand HEUTE statt, der Tag " +
                    "liegt 5 Tage zurueck",
                (dailyFileDao.zeilen[schluessel]?.lastSyncedAt ?: 0L) >= tagBis.toEpochMilli(),
            )

            protokollDao.zwischenAufrufe.clear()
            koordinator.syncEinenZyklus() // zweiter Lauf: derselbe Tag ist jetzt endgueltig

            assertTrue(
                "Der zweite Lauf darf denselben, jetzt endgueltig synchronisierten Tag nicht mehr laden",
                protokollDao.zwischenAufrufe.none { ueberschneidetTag(it, tagVon, tagBis) },
            )
        }

    // ---------------------------------------------------------------- OOM-Bugfix Schritt 4: stueckweise Aggregation

    /**
     * PROMPT_FIX_OOM_DRIVE_SYNC.md Abschnitt 3, Test 4: Kein einzelner `zwischen()`-Aufruf darf
     * mehr als eine Abschnittslaenge (rund eine Stunde) umfassen - Speichergrenze als Proxy,
     * siehe Testklasse. Deckt sowohl den heutigen Tag als auch alle 29 Nachhol-Tage ab (die 29
     * Tage sind leer, werden aber trotzdem stueckweise angefragt statt als ein Aufruf pro Tag).
     *
     * Bewusst NUR Fensterdauern, die 60s glatt teilen (siehe `aggregiereInAbschnitten()`-KDoc,
     * Abschnitt zum Bucket/Label-Fund): Fuer eine nicht teilende Fensterdauer (z. B. 7s) faellt
     * die Implementierung absichtlich auf einen einzigen Aufruf ueber den ganzen Zeitraum
     * zurueck, um nachweisbare Korrektheit ueber den Speichervorteil zu stellen - fuer DIESEN
     * schmalen Fall gilt die Abschnittslaengen-Grenze also bewusst NICHT, siehe Test 3.
     */
    @Test
    fun keinRohwertAufrufUeberschreitetEineAbschnittslaenge() =
        runTest {
            for (fensterSekunden in listOf(1, 10, 60, 3600)) {
                val protokollDao = ProtokollierendesLevelSampleDao()
                val mitternacht =
                    uhr
                        .now()
                        .atZone(zone)
                        .toLocalDate()
                        .atStartOfDay(zone)
                        .toInstant()
                for (stunde in 0 until 9) {
                    protokollDao.eingefuegt +=
                        LevelSampleEntity(
                            at = mitternacht.plusSeconds(stunde * 3600L + 60).toEpochMilli(),
                            levelDb = 55.0,
                            source = LevelSource.PCE_323,
                        )
                }
                settings.driveAggregationSekunden = fensterSekunden
                val koordinator =
                    DriveSyncCoordinator(
                        driveApi = FakeDriveApiClient(),
                        levelSampleDao = protokollDao,
                        dailyFileDao = FakeDailyFileDao(),
                        noiseDao = noiseDao,
                        settings = settings,
                        now = uhr,
                        zone = zone,
                    )

                koordinator.syncEinenZyklus()

                val vielfaches = Math.round(3600.0 / fensterSekunden.coerceAtLeast(1)).coerceAtLeast(1)
                val maxAbschnittMillis = fensterSekunden * 1000L * vielfaches
                assertTrue(
                    "Kein zwischen()-Aufruf darf bei Fensterdauer ${fensterSekunden}s mehr als " +
                        "eine Abschnittslaenge ($maxAbschnittMillis ms) umfassen - war " +
                        "${protokollDao.zwischenAufrufe.maxOfOrNull { it.second - it.first }}",
                    protokollDao.zwischenAufrufe.all { (von, bis) -> (bis - von) <= maxAbschnittMillis },
                )
            }
        }

    /**
     * Gegenprobe zum dokumentierten Fallback (siehe `aggregiereInAbschnitten()`-KDoc): Bei einer
     * Fensterdauer, die 60s NICHT glatt teilt, muss tatsaechlich EIN Aufruf ueber den ganzen
     * angeforderten Zeitraum erfolgen (nicht stueckweise) - sonst waere die Korrektheitsgarantie
     * aus Test 3 fuer diesen Fall unbelegt.
     */
    @Test
    fun beiNichtTeilenderFensterdauerFaelltDieAggregationAufEinenGesamtaufrufZurueck() =
        runTest {
            val protokollDao = ProtokollierendesLevelSampleDao()
            protokollDao.eingefuegt +=
                LevelSampleEntity(
                    at = uhr.now().minusSeconds(3600).toEpochMilli(),
                    levelDb = 55.0,
                    source = LevelSource.PCE_323,
                )
            settings.driveAggregationSekunden = 7
            val koordinator =
                DriveSyncCoordinator(
                    driveApi = driveApi,
                    levelSampleDao = protokollDao,
                    dailyFileDao = dailyFileDao,
                    noiseDao = noiseDao,
                    settings = settings,
                    now = uhr,
                    zone = zone,
                )
            val von =
                uhr
                    .now()
                    .atZone(zone)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant()

            koordinator.syncEinenZyklus()

            assertTrue(
                "Bei 7s (teilt 60s nicht) muss der heutige Tag als EIN Aufruf ueber [von, jetzt) " +
                    "geladen werden (dokumentierter Fallback), nicht stueckweise",
                protokollDao.zwischenAufrufe.any { (a, b) -> a == von.toEpochMilli() && b == uhr.now().toEpochMilli() },
            )
        }

    /**
     * PROMPT_FIX_OOM_DRIVE_SYNC.md Abschnitt 3, Test 3: Stueckweise Aggregation muss fuer
     * dieselben Rohwerte exakt dasselbe Ergebnis liefern wie ein einziger Aufruf von
     * [PegelAggregator.aggregiere] ueber den gesamten Zeitraum - verglichen ueber die
     * tatsaechlich hochgeladene CSV (identischer Text = identische Zeilen, Reihenfolge und
     * Werte). Geprueft fuer mehrere Fensterdauern, darunter eine (7s), die 3600 nicht teilt.
     */
    @Test
    fun stueckweiseAggregationLiefertDasselbeErgebnisWieEinAufrufUeberDenGanzenTag() =
        runTest {
            val zufall = kotlin.random.Random(42)
            uhr = TestUhr(Instant.parse("2026-08-19T21:59:50Z")) // kurz vor Mitternacht MESZ
            val mitternacht =
                uhr
                    .now()
                    .atZone(zone)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant()
            val tagesspanneMillis = Duration.between(mitternacht, uhr.now()).toMillis()

            val samples =
                (1..20_000).map {
                    LevelSampleEntity(
                        at = mitternacht.toEpochMilli() + zufall.nextLong(tagesspanneMillis),
                        levelDb = 30.0 + zufall.nextDouble() * 50.0,
                        source = if (zufall.nextBoolean()) LevelSource.PCE_323 else LevelSource.MIKROFON,
                    )
                }
            val ereignisRecords =
                (0 until 5).map { i ->
                    NoiseRecord(
                        id = i.toLong() + 1,
                        timestamp = mitternacht.toEpochMilli() + zufall.nextLong(tagesspanneMillis),
                        amplitude = 50.0,
                        dbValue = 60.0 + i,
                        filePath = "/pfad/nicht/relevant_$i.wav",
                        detectedLabel = "Ereignis $i",
                    )
                }
            val ereignisseDao =
                object : FakeNoiseDao() {
                    override suspend fun zwischenZeitpunkt(
                        von: Long,
                        bis: Long,
                    ): List<NoiseRecord> = ereignisRecords.filter { it.timestamp in von until bis }
                }
            val erwarteteEreignisse =
                ereignisRecords.map {
                    ProtokollEreignis(
                        at = Instant.ofEpochMilli(it.timestamp),
                        pegelDb = it.calibratedDbA ?: it.dbValue,
                        klassifikation = it.detectedLabel ?: it.label,
                        notes = it.notes,
                        weighting = it.meterWeighting,
                    )
                }

            for (fensterSekunden in listOf(1, 10, 60, 300, 3600, 7)) {
                val dao = FakeLevelSampleDao().apply { eingefuegt += samples }
                settings.driveAggregationSekunden = fensterSekunden
                val eigenerDriveApi = FakeDriveApiClient()
                val koordinator =
                    DriveSyncCoordinator(
                        driveApi = eigenerDriveApi,
                        levelSampleDao = dao,
                        dailyFileDao = FakeDailyFileDao(),
                        noiseDao = ereignisseDao,
                        settings = settings,
                        now = uhr,
                        zone = zone,
                    )

                koordinator.syncEinenZyklus()

                val erwartet =
                    PegelAggregator.aggregiere(
                        samples,
                        erwarteteEreignisse,
                        mitternacht,
                        uhr.now(),
                        Duration.ofSeconds(fensterSekunden.toLong()),
                    )
                val erwarteteCsv = DriveCsv.schreibe(erwartet, zone).toByteArray(Charsets.UTF_8)

                assertArrayEquals(
                    "Stueckweise Aggregation muss bei Fensterdauer ${fensterSekunden}s identisch " +
                        "mit einem Aufruf ueber den ganzen Tag sein",
                    erwarteteCsv,
                    eigenerDriveApi.letzterAktualisierterInhalt,
                )
            }
        }

    /**
     * Ueber die im Prompt geforderten Tests hinaus (siehe `aggregiereInAbschnitten()`-KDoc in
     * [DriveSyncCoordinator]): belegt konkret den Fall, auf den dieser Bugfix in der Praxis
     * abzielt - eine mehrstuendige Luecke (BLE-Aussetzer, Geraete-Neustart) MITTEN im Tag muss
     * stueckweise genauso als KEINE_VERBINDUNG erscheinen wie bei einem einzigen Aufruf ueber
     * den ganzen Tag. Dichte Zufallsdaten (Test 3) allein wuerden diesen Fall nie erzeugen - die
     * Luecke faengt und endet hier ABSICHTLICH nicht auf einer Abschnittsgrenze, um auch eine
     * Luecke zu pruefen, die einen Abschnitt nur teilweise fuellt.
     */
    @Test
    fun mehrstuendigeLueckeMittenImTagWirdGenauWieBeimGesamtaufrufBehandelt() =
        runTest {
            uhr = TestUhr(Instant.parse("2026-08-19T21:59:50Z"))
            val mitternacht =
                uhr
                    .now()
                    .atZone(zone)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant()
            val samples = mutableListOf<LevelSampleEntity>()
            var t = 6 * 3600L
            while (t < 10 * 3600L + 900) {
                samples +=
                    LevelSampleEntity(
                        at = mitternacht.plusSeconds(t).toEpochMilli(),
                        levelDb = 50.0 + t % 10,
                        source = LevelSource.PCE_323,
                    )
                t += 5
            }
            t = 14 * 3600L + 2400
            while (t < 22 * 3600L) {
                samples +=
                    LevelSampleEntity(
                        at = mitternacht.plusSeconds(t).toEpochMilli(),
                        levelDb = 55.0 + t % 7,
                        source = LevelSource.PCE_323,
                    )
                t += 5
            }

            for (fensterSekunden in listOf(10, 7)) {
                val dao = FakeLevelSampleDao().apply { eingefuegt += samples }
                settings.driveAggregationSekunden = fensterSekunden
                val eigenerDriveApi = FakeDriveApiClient()
                val koordinator =
                    DriveSyncCoordinator(
                        driveApi = eigenerDriveApi,
                        levelSampleDao = dao,
                        dailyFileDao = FakeDailyFileDao(),
                        noiseDao = noiseDao,
                        settings = settings,
                        now = uhr,
                        zone = zone,
                    )

                koordinator.syncEinenZyklus()

                val erwartet =
                    PegelAggregator.aggregiere(
                        samples,
                        emptyList(),
                        mitternacht,
                        uhr.now(),
                        Duration.ofSeconds(fensterSekunden.toLong()),
                    )
                val erwarteteCsv = DriveCsv.schreibe(erwartet, zone).toByteArray(Charsets.UTF_8)

                assertArrayEquals(
                    "Eine mehrstuendige Luecke mitten im Tag muss stueckweise identisch mit " +
                        "einem Aufruf ueber den ganzen Tag behandelt werden (Fensterdauer " +
                        "${fensterSekunden}s)",
                    erwarteteCsv,
                    eigenerDriveApi.letzterAktualisierterInhalt,
                )
            }
        }
}
