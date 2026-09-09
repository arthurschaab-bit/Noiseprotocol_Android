package com.example.lrmprotokoll.drive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DriveDatenbankSicherung] gegen ein Fake - die eigentliche HTTP-Anbindung ist bereits durch
 * [GoogleDriveApiClientTest] (MockWebServer) abgedeckt. Hier geht es nur um die
 * Ordner-/Datei-Aufloesung: BACKUP-Ordner finden-oder-anlegen, Datei finden-oder-anlegen, dieselbe
 * Waisen-Absicherung wie bei CSV/WAV/Fotos.
 */
class DriveDatenbankSicherungTest {

    private class FakeDriveApiClient : DriveApiClient {
        val angelegteOrdner = mutableListOf<Pair<String, String?>>()
        var bestehenderOrdner: DriveDatei? = null
        var bestehendeDatei: DriveDatei? = null
        var angelegteDateien = 0
        var aktualisierungen = 0
        var letzterHochgeladenerInhalt: ByteArray? = null
        var herunterladenErgebnis: Result<ByteArray> = Result.success(byteArrayOf())

        override suspend fun ordnerAnlegen(name: String, elternId: String?): Result<String> {
            angelegteOrdner += name to elternId
            return Result.success("neuer-ordner-id")
        }

        override suspend fun ordnerSuchen(name: String, elternId: String?): Result<DriveDatei?> =
            Result.success(bestehenderOrdner)

        override suspend fun ordnerAuflisten(): Result<List<DriveDatei>> = Result.success(emptyList())
        override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String): Result<Unit> = Result.success(Unit)

        override suspend fun dateiSuchen(name: String, ordnerId: String): Result<DriveDatei?> =
            Result.success(bestehendeDatei)

        override suspend fun dateienInOrdnerAuflisten(ordnerId: String): Result<Set<String>> = Result.success(emptySet())

        override suspend fun dateiAnlegen(name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean): Result<String> {
            angelegteDateien++
            letzterHochgeladenerInhalt = inhalt
            return Result.success("neue-datei-id")
        }

        override suspend fun dateiAktualisieren(fileId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean): Result<Unit> {
            aktualisierungen++
            letzterHochgeladenerInhalt = inhalt
            return Result.success(Unit)
        }

        override suspend fun dateiHerunterladen(fileId: String): Result<ByteArray> = herunterladenErgebnis

        override suspend fun dateiHochladenResumable(
            name: String, ordnerId: String, datei: java.io.File, mimeType: String,
            fortsetzenAb: String?, sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<String> = throw NotImplementedError("im Test nicht benoetigt")
    }

    @Test
    fun ordnerSicherstellenLegtBackupOrdnerUnterDerWurzelAn() = runTest {
        val client = FakeDriveApiClient()

        val ergebnis = DriveDatenbankSicherung.ordnerSicherstellen(client, "wurzel-id")

        assertEquals("neuer-ordner-id", ergebnis.getOrThrow())
        assertEquals(listOf("BACKUP" to "wurzel-id"), client.angelegteOrdner)
    }

    @Test
    fun ordnerSicherstellenLegtKeinenNeuenAnWennErSchonExistiert() = runTest {
        val client = FakeDriveApiClient()
        client.bestehenderOrdner = DriveDatei(id = "bestehend", name = "BACKUP")

        val ergebnis = DriveDatenbankSicherung.ordnerSicherstellen(client, "wurzel-id")

        assertEquals("bestehend", ergebnis.getOrThrow())
        assertTrue(client.angelegteOrdner.isEmpty())
    }

    @Test
    fun hochladenLegtNeueDateiAnWennNochKeineExistiert() = runTest {
        val client = FakeDriveApiClient()
        val bytes = byteArrayOf(1, 2, 3)

        val ergebnis = DriveDatenbankSicherung.hochladen(client, "wurzel-id", bytes)

        assertTrue(ergebnis.isSuccess)
        assertEquals(1, client.angelegteDateien)
        assertEquals(0, client.aktualisierungen)
        assertArrayEquals(bytes, client.letzterHochgeladenerInhalt)
    }

    @Test
    fun hochladenAktualisiertBestehendeDateiStattEinerZweiten() = runTest {
        val client = FakeDriveApiClient()
        client.bestehendeDatei = DriveDatei(id = "bestehend", name = BACKUP_DATEINAME)

        val ergebnis = DriveDatenbankSicherung.hochladen(client, "wurzel-id", byteArrayOf(9))

        assertTrue(ergebnis.isSuccess)
        assertEquals(0, client.angelegteDateien)
        assertEquals(1, client.aktualisierungen)
    }

    @Test
    fun herunterladenOhneBackupOrdnerLiefertFehler() = runTest {
        val client = FakeDriveApiClient()

        val ergebnis = DriveDatenbankSicherung.herunterladen(client, "wurzel-id")

        assertTrue(ergebnis.isFailure)
    }

    @Test
    fun herunterladenOhneDateiImOrdnerLiefertFehler() = runTest {
        val client = FakeDriveApiClient()
        client.bestehenderOrdner = DriveDatei(id = "ordner", name = "BACKUP")

        val ergebnis = DriveDatenbankSicherung.herunterladen(client, "wurzel-id")

        assertTrue(ergebnis.isFailure)
    }

    @Test
    fun herunterladenLiefertDenInhaltDerGefundenenDatei() = runTest {
        val client = FakeDriveApiClient()
        client.bestehenderOrdner = DriveDatei(id = "ordner", name = "BACKUP")
        client.bestehendeDatei = DriveDatei(id = "datei", name = BACKUP_DATEINAME)
        client.herunterladenErgebnis = Result.success(byteArrayOf(4, 5, 6))

        val ergebnis = DriveDatenbankSicherung.herunterladen(client, "wurzel-id")

        assertArrayEquals(byteArrayOf(4, 5, 6), ergebnis.getOrThrow())
    }
}
