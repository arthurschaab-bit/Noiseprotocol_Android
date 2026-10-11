package com.example.lrmprotokoll.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.backup.SicherungsDatenbank
import com.example.lrmprotokoll.backup.Vollsicherung
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Nächtliche Vollsicherung (docs/PROMPT_SICHERUNG_VOLL_UND_TEIL.md): der Stand für die
 * Teilsicherungen wird erst nach erfolgreichem Upload gemerkt, die gebaute Datei immer gelöscht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatenbankVollsicherungWorkerTest {
    private lateinit var context: Context
    private lateinit var app: LaermprotokollApp

    /** Handgeschriebener Drive-Client: nur der resumable Upload ist relevant. */
    private class FakeDrive(
        private val uploadErgebnis: kotlin.Result<String>,
    ) : DriveApiClient {
        var uploads = 0

        override suspend fun ordnerAnlegen(
            name: String,
            elternId: String?,
        ) = kotlin.Result.success("backup-ordner")

        override suspend fun ordnerSuchen(
            name: String,
            elternId: String?,
        ) = kotlin.Result.success<DriveDatei?>(null)

        override suspend fun ordnerAuflisten() = kotlin.Result.success(emptyList<DriveDatei>())

        override suspend fun ordnerUmbenennen(
            ordnerId: String,
            neuerName: String,
        ) = kotlin.Result.success(Unit)

        override suspend fun dateiSuchen(
            name: String,
            ordnerId: String,
        ) = kotlin.Result.success<DriveDatei?>(null)

        override suspend fun dateienInOrdnerAuflisten(ordnerId: String) = kotlin.Result.success(emptySet<String>())

        override suspend fun dateiAnlegen(
            name: String,
            ordnerId: String,
            inhalt: ByteArray,
            mimeType: String,
            gzip: Boolean,
        ): kotlin.Result<String> = throw NotImplementedError("im Test nicht benoetigt")

        override suspend fun dateiHochladenResumable(
            name: String,
            ordnerId: String,
            datei: File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): kotlin.Result<String> {
            uploads++
            return uploadErgebnis
        }

        override suspend fun dateiAktualisierenResumable(
            fileId: String,
            datei: File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): kotlin.Result<Unit> = throw NotImplementedError("im Test nicht benoetigt")

        override suspend fun dateiAktualisieren(
            fileId: String,
            inhalt: ByteArray,
            mimeType: String,
            gzip: Boolean,
        ): kotlin.Result<Unit> = throw NotImplementedError("im Test nicht benoetigt")

        override suspend fun dateiHerunterladen(fileId: String): kotlin.Result<ByteArray> =
            throw NotImplementedError("im Test nicht benoetigt")

        override suspend fun dateiHerunterladenNach(
            fileId: String,
            ziel: File,
        ): kotlin.Result<Unit> = throw NotImplementedError("im Test nicht benoetigt")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as LaermprotokollApp
        val settings = app.container.settingsManager
        settings.driveSyncEnabled = true
        settings.driveFolderId = "wurzel"
        settings.datenbankSicherungDriveUpload = true
    }

    private fun gebauteSicherung(): Vollsicherung =
        Vollsicherung(
            datei = File.createTempFile("voll_test", ".zip", context.cacheDir).apply { writeBytes(byteArrayOf(1)) },
            vollsicherungId = 4242L,
            stand = SicherungsDatenbank.Stand(basisMesswertId = 17, basisRohdatenId = 3),
        )

    private fun bauWorker(
        sicherung: Vollsicherung,
        drive: DriveApiClient,
    ) = TestListenableWorkerBuilder<DatenbankVollsicherungWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = DatenbankVollsicherungWorker(appContext, workerParameters, { sicherung }, drive)
            },
        ).build()

    @Test
    fun erfolgreicherUploadMerktDenStandUndLoeschtDieDatei() =
        runTest {
            val sicherung = gebauteSicherung()

            val ergebnis = bauWorker(sicherung, FakeDrive(kotlin.Result.success("datei-id"))).doWork()

            assertEquals(Result.success(), ergebnis)
            assertEquals(4242L to sicherung.stand, app.container.settingsManager.vollsicherungStand)
            assertFalse(sicherung.datei.exists())
        }

    /** Review zu #274: Nach einem Ordnerwechsel liegt die Basis im alten Ordner - sie gilt nicht mehr. */
    @Test
    fun ordnerwechselMachtDenStandUngueltig() =
        runTest {
            bauWorker(gebauteSicherung(), FakeDrive(kotlin.Result.success("datei-id"))).doWork()

            app.container.settingsManager.driveFolderId = "anderer-ordner"

            assertNull(app.container.settingsManager.vollsicherungStand)
        }

    @Test
    fun fehlgeschlagenerUploadMerktKeinenStand() =
        runTest {
            val sicherung = gebauteSicherung()

            val ergebnis =
                bauWorker(sicherung, FakeDrive(kotlin.Result.failure(DriveApiException("Drive nicht erreichbar")))).doWork()

            assertEquals(Result.retry(), ergebnis)
            assertNull(app.container.settingsManager.vollsicherungStand)
            assertFalse(sicherung.datei.exists())
        }

    @Test
    fun vollsicherungBeginntUmDreiUhr() {
        val zone = ZoneId.of("Europe/Berlin")
        val nachts = ZonedDateTime.of(2026, 10, 10, 2, 0, 0, 0, zone)
        val morgens = ZonedDateTime.of(2026, 10, 10, 8, 0, 0, 0, zone)

        assertEquals(Duration.ofHours(1), DatenbankVollsicherungPlanung.verzoegerungBisVollsicherung(nachts))
        assertEquals(Duration.ofHours(19), DatenbankVollsicherungPlanung.verzoegerungBisVollsicherung(morgens))
    }
}
