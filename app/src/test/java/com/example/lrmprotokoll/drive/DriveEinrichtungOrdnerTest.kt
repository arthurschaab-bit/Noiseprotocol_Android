package com.example.lrmprotokoll.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.auth.DriveEinrichtung
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveEinrichtungOrdnerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsManager
    private lateinit var fakeDriveApi: TestFakeDriveApiClient
    private lateinit var driveEinrichtung: DriveEinrichtung

    private class TestFakeDriveApiClient : DriveApiClient {
        val existingFolders = mutableListOf<DriveDatei>()
        var createdFolders = mutableListOf<String>()
        var renamedFolders = mutableListOf<Pair<String, String>>()

        override suspend fun ordnerAnlegen(name: String, elternId: String?): Result<String> {
            createdFolders.add(name)
            val id = "id-$name"
            val datei = DriveDatei(id, name)
            existingFolders.add(datei)
            return Result.success(id)
        }

        override suspend fun ordnerSuchen(name: String, elternId: String?): Result<DriveDatei?> {
            val found = existingFolders.find { it.name == name }
            return Result.success(found)
        }

        override suspend fun ordnerAuflisten(): Result<List<DriveDatei>> {
            return Result.success(existingFolders.toList())
        }

        override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String): Result<Unit> {
            renamedFolders.add(ordnerId to neuerName)
            val index = existingFolders.indexOfFirst { it.id == ordnerId }
            if (index >= 0) {
                existingFolders[index] = DriveDatei(ordnerId, neuerName)
            }
            return Result.success(Unit)
        }

        override suspend fun dateienInOrdnerAuflisten(ordnerId: String): Result<Set<String>> = Result.success(emptySet())
        override suspend fun dateiSuchen(name: String, ordnerId: String): Result<DriveDatei?> = Result.success(null)
        override suspend fun dateiAnlegen(name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean): Result<String> = Result.success("file-id")
        override suspend fun dateiAktualisieren(fileId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean): Result<Unit> = Result.success(Unit)

        override suspend fun dateiHochladenResumable(
            name: String,
            ordnerId: String,
            datei: java.io.File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<String> = throw NotImplementedError("im Test nicht benoetigt")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsManager(context)
        settings.driveFolderName = "Lärmprotokoll"
        settings.driveFolderId = null
        fakeDriveApi = TestFakeDriveApiClient()
        driveEinrichtung = DriveEinrichtung(context, settings, fakeDriveApi, tokenProvider = null)
    }

    @Test
    fun richteEinErstelltOrdnerWennKeinerExistiert() = runTest {
        val ergebnis = driveEinrichtung.richteEin("Lärmprotokoll")

        assertTrue(ergebnis.isSuccess)
        assertEquals(1, fakeDriveApi.createdFolders.size)
        assertEquals("Lärmprotokoll", fakeDriveApi.createdFolders[0])
        assertEquals("id-Lärmprotokoll", settings.driveFolderId)
        assertEquals("Lärmprotokoll", settings.driveFolderName)
        assertTrue(settings.driveSyncEnabled)
        assertFalse(settings.driveOrdnerBlockiert)
    }

    @Test
    fun richteEinVerwendetBestehendenOrdnerWiederUndVerhindertDuplikate() = runTest {
        // Vorab existiert bereits ein Ordner gleichen Namens auf Drive
        fakeDriveApi.existingFolders.add(DriveDatei(id = "existing-folder-id", name = "Lärmprotokoll"))

        val ergebnis = driveEinrichtung.richteEin("Lärmprotokoll")

        assertTrue(ergebnis.isSuccess)
        // Es darf KEIN neuer Ordner auf Drive angelegt werden!
        assertEquals(0, fakeDriveApi.createdFolders.size)
        assertEquals("existing-folder-id", settings.driveFolderId)
        assertEquals("Lärmprotokoll", settings.driveFolderName)
        assertTrue(settings.driveSyncEnabled)
    }

    @Test
    fun ladeVerfuegbareOrdnerLiefertAlleOrdner() = runTest {
        fakeDriveApi.existingFolders.add(DriveDatei("id-1", "Ordner 1"))
        fakeDriveApi.existingFolders.add(DriveDatei("id-2", "Ordner 2"))

        val ergebnis = driveEinrichtung.ladeVerfuegbareOrdner()

        assertTrue(ergebnis.isSuccess)
        val liste = ergebnis.getOrThrow()
        assertEquals(2, liste.size)
        assertEquals("Ordner 1", liste[0].name)
        assertEquals("Ordner 2", liste[1].name)
    }

    @Test
    fun waehleBestehendenOrdnerAktualisiertSettings() = runTest {
        val folder = DriveDatei("selected-id", "Ausgewählter Ordner")

        val ergebnis = driveEinrichtung.waehleBestehendenOrdner(folder)

        assertTrue(ergebnis.isSuccess)
        assertEquals("selected-id", settings.driveFolderId)
        assertEquals("Ausgewählter Ordner", settings.driveFolderName)
        assertTrue(settings.driveSyncEnabled)
    }

    @Test
    fun erstelleNeuenOrdnerLegtOrdnerAnUndAktiviertIhn() = runTest {
        val ergebnis = driveEinrichtung.erstelleNeuenOrdner("Neues Projekt 2026")

        assertTrue(ergebnis.isSuccess)
        val created = ergebnis.getOrThrow()
        assertEquals("Neues Projekt 2026", created.name)
        assertEquals("id-Neues Projekt 2026", created.id)
        assertEquals("id-Neues Projekt 2026", settings.driveFolderId)
        assertEquals("Neues Projekt 2026", settings.driveFolderName)
        assertTrue(settings.driveSyncEnabled)
    }

    @Test
    fun benenneOrdnerUmAktualisiertDriveUndSettings() = runTest {
        settings.driveFolderId = "folder-to-rename"
        settings.driveFolderName = "Alter Name"
        fakeDriveApi.existingFolders.add(DriveDatei("folder-to-rename", "Alter Name"))

        val ergebnis = driveEinrichtung.benenneOrdnerUm("folder-to-rename", "Neuer Name")

        assertTrue(ergebnis.isSuccess)
        assertEquals(1, fakeDriveApi.renamedFolders.size)
        assertEquals("folder-to-rename" to "Neuer Name", fakeDriveApi.renamedFolders[0])
        assertEquals("Neuer Name", settings.driveFolderName)
    }
}
