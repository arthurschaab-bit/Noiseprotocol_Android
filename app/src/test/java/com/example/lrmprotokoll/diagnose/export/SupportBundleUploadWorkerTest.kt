package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.DriveApiClient
import com.example.lrmprotokoll.drive.DriveDatei
import java.io.File
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
 * M12 Schritt 5: [SupportBundleUploadWorker] gegen einen handgeschriebenen Fake-[DriveApiClient]
 * (AGENTS.md Abschnitt 3: keine Mocking-Bibliothek) - Erfolg, Fehlschlag mit Retry, Loeschen nach
 * Erfolg, kein Doppelversand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportBundleUploadWorkerTest {

    private class FakeDriveApiClient(
        private val vorhandeneDateien: MutableSet<String> = mutableSetOf(),
    ) : DriveApiClient {
        var hochgeladeneDateien = mutableListOf<String>()
        var hochladenSchlaegtFehl = false
        var suchAufrufe = 0

        override suspend fun ordnerAnlegen(name: String, elternId: String?) = kotlin.Result.success("support-ordner-id")
        override suspend fun ordnerSuchen(name: String, elternId: String?): kotlin.Result<DriveDatei?> =
            kotlin.Result.success(DriveDatei("support-ordner-id", name))
        override suspend fun ordnerAuflisten() = kotlin.Result.success(emptyList<DriveDatei>())
        override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String) = kotlin.Result.success(Unit)
        override suspend fun dateiSuchen(name: String, ordnerId: String): kotlin.Result<DriveDatei?> {
            suchAufrufe++
            return kotlin.Result.success(if (name in vorhandeneDateien) DriveDatei("bereits-da", name) else null)
        }
        override suspend fun dateienInOrdnerAuflisten(ordnerId: String) = kotlin.Result.success(emptySet<String>())
        override suspend fun dateiAnlegen(
            name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ) = kotlin.Result.success("datei-id")
        override suspend fun dateiAktualisieren(
            fileId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ) = kotlin.Result.success(Unit)
        override suspend fun dateiHerunterladen(fileId: String): kotlin.Result<ByteArray> =
            throw NotImplementedError("im Test nicht benoetigt")
        override suspend fun dateiHochladenResumable(
            name: String, ordnerId: String, datei: File, mimeType: String,
            fortsetzenAb: String?, sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): kotlin.Result<String> {
            if (hochladenSchlaegtFehl) return kotlin.Result.failure(RuntimeException("Simulierter Netzfehler"))
            hochgeladeneDateien += name
            vorhandeneDateien += name
            return kotlin.Result.success("hochgeladen-id")
        }
    }

    private lateinit var context: Context
    private lateinit var settings: SettingsManager
    private lateinit var outboxDir: File

    private fun bauWorker(driveApi: DriveApiClient) =
        TestListenableWorkerBuilder<SupportBundleUploadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
                ) = SupportBundleUploadWorker(appContext, workerParameters, driveApi)
            })
            .build()

    private fun bundleDatei(name: String, inhalt: String = "fake-zip-inhalt"): File {
        val datei = File(outboxDir, name)
        datei.writeText(inhalt)
        return datei
    }

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsManager(context)
        settings.driveFolderId = "wurzel-id"
        outboxDir = File(context.filesDir, com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR)
        outboxDir.deleteRecursively()
        outboxDir.mkdirs()
        // Isoliert diesen Test von anderen, die dieselbe geteilte Robolectric-Applikation nutzen.
        ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.settingsManager.driveFolderId = "wurzel-id"
    }

    @After
    fun aufraeumen() {
        outboxDir.deleteRecursively()
    }

    @Test
    fun erfolgreicherUploadLoeschtDieDateiUndVermerktErfolg() = runTest {
        val datei = bundleDatei("2026-09-17_230000_absturz.zip")
        val driveApi = FakeDriveApiClient()

        val ergebnis = bauWorker(driveApi).doWork()

        assertTrue(ergebnis is Result.Success)
        assertFalse("Datei muss nach erfolgreichem Upload geloescht sein", datei.exists())
        assertEquals(listOf("2026-09-17_230000_absturz.zip"), driveApi.hochgeladeneDateien)
        assertTrue(
            ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.settingsManager.supportBundleLastUploadAt > 0,
        )
    }

    @Test
    fun fehlgeschlagenerUploadLiefertRetryUndBehaeltDieDatei() = runTest {
        val datei = bundleDatei("2026-09-17_230000_absturz.zip")
        val driveApi = FakeDriveApiClient().apply { hochladenSchlaegtFehl = true }

        val ergebnis = bauWorker(driveApi).doWork()

        assertTrue(ergebnis is Result.Retry)
        assertTrue("Bei einem Fehlschlag darf die Datei nicht verloren gehen", datei.exists())
    }

    @Test
    fun bereitsHochgeladeneDateiWirdNurGeloeschtNichtErneutHochgeladen() = runTest {
        val datei = bundleDatei("2026-09-17_230000_absturz.zip")
        val driveApi = FakeDriveApiClient(vorhandeneDateien = mutableSetOf("2026-09-17_230000_absturz.zip"))

        val ergebnis = bauWorker(driveApi).doWork()

        assertTrue(ergebnis is Result.Success)
        assertFalse(datei.exists())
        assertTrue(
            "Eine bereits auf Drive vorhandene Datei darf nicht noch einmal hochgeladen werden",
            driveApi.hochgeladeneDateien.isEmpty(),
        )
    }

    @Test
    fun keinDriveOrdnerEingerichtetLaesstDieOutboxUnangetastet() = runTest {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.settingsManager.driveFolderId = null
        val datei = bundleDatei("2026-09-17_230000_manuell.zip")
        val driveApi = FakeDriveApiClient()

        val ergebnis = bauWorker(driveApi).doWork()

        assertTrue(ergebnis is Result.Success)
        assertTrue("Ohne eingerichteten Ordner darf nichts geloescht werden", datei.exists())
        assertTrue(driveApi.hochgeladeneDateien.isEmpty())
    }

    @Test
    fun mehrereDateienWerdenAlleHochgeladen() = runTest {
        bundleDatei("2026-09-17_230000_absturz.zip")
        bundleDatei("2026-09-17_231000_periodisch.zip")
        val driveApi = FakeDriveApiClient()

        val ergebnis = bauWorker(driveApi).doWork()

        assertTrue(ergebnis is Result.Success)
        assertEquals(2, driveApi.hochgeladeneDateien.size)
    }
}
