package com.example.lrmprotokoll.drive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [DriveDatenbankSicherung] gegen ein Fake - die eigentliche HTTP-Anbindung ist bereits durch
 * [GoogleDriveApiClientTest]/[GoogleDriveResumableUploadTest] (MockWebServer) abgedeckt. Hier
 * geht es nur um die Ordner-/Datei-Aufloesung: BACKUP-Ordner finden-oder-anlegen, Datei
 * finden-oder-anlegen, dieselbe Waisen-Absicherung wie bei CSV/WAV/Fotos - UND (seit dem
 * Streaming-Umbau, Bugfix 23.09.2026, docs/PROMPT_FIX_DATENBANK_SICHERUNG.md) dass wirklich der
 * resumable/Datei-Pfad benutzt wird, NIE [DriveApiClient.dateiAnlegen]/[DriveApiClient.dateiAktualisieren]
 * (`ByteArray`) fuer die Sicherung - genau der Pfad, der auf dem Owner-Geraet mit einer
 * ~500-MB-Datenbank in einen `OutOfMemoryError` lief.
 */
class DriveDatenbankSicherungTest {

    @get:Rule
    val ordner = TemporaryFolder()

    private class FakeDriveApiClient : DriveApiClient {
        val angelegteOrdner = mutableListOf<Pair<String, String?>>()
        var bestehenderOrdner: DriveDatei? = null
        var bestehendeDatei: DriveDatei? = null

        // PROMPT_FIX_DATENBANK_SICHERUNG.md Test 4: nur die resumable/Datei-Aufrufe zaehlen fuer
        // die Sicherung - ByteArray-Aufrufe (dateiAnlegen/dateiAktualisieren) duerfen dafuer gar
        // nicht erst passieren.
        var neuanlagenResumable = 0
        var aktualisierungenResumable = 0
        var letzteHochgeladeneDatei: File? = null
        var letzteAktualisierteFileId: String? = null
        var byteArrayAnlegenAufrufe = 0
        var byteArrayAktualisierenAufrufe = 0

        var hochladenErgebnis: Result<String> = Result.success("neue-datei-id")
        var aktualisierenErgebnis: Result<Unit> = Result.success(Unit)

        // PROMPT_FIX_DATENBANK_SICHERUNG.md Test 5: der heruntergeladene Inhalt landet in einer
        // Datei statt in einem ByteArray.
        var herunterladenInhalt: ByteArray? = byteArrayOf()
        var herunterladenFehler: Throwable? = null
        var letztesHerunterladenZiel: File? = null

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

        override suspend fun dateiAnlegen(
            name: String,
            ordnerId: String,
            inhalt: ByteArray,
            mimeType: String,
            gzip: Boolean,
        ): Result<String> {
            byteArrayAnlegenAufrufe++
            return Result.success("sollte-fuer-die-sicherung-nie-benutzt-werden")
        }

        override suspend fun dateiAktualisieren(
            fileId: String,
            inhalt: ByteArray,
            mimeType: String,
            gzip: Boolean,
        ): Result<Unit> {
            byteArrayAktualisierenAufrufe++
            return Result.success(Unit)
        }

        override suspend fun dateiHerunterladen(fileId: String): Result<ByteArray> =
            throw NotImplementedError("im Test nicht benoetigt - siehe dateiHerunterladenNach")

        override suspend fun dateiHerunterladenNach(fileId: String, ziel: File): Result<Unit> {
            letztesHerunterladenZiel = ziel
            herunterladenFehler?.let { return Result.failure(it) }
            ziel.writeBytes(herunterladenInhalt ?: byteArrayOf())
            return Result.success(Unit)
        }

        override suspend fun dateiHochladenResumable(
            name: String,
            ordnerId: String,
            datei: File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<String> {
            neuanlagenResumable++
            letzteHochgeladeneDatei = datei
            return hochladenErgebnis
        }

        override suspend fun dateiAktualisierenResumable(
            fileId: String,
            datei: File,
            mimeType: String,
            fortsetzenAb: String?,
            sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<Unit> {
            aktualisierungenResumable++
            letzteHochgeladeneDatei = datei
            letzteAktualisierteFileId = fileId
            return aktualisierenErgebnis
        }
    }

    private fun tempDatei(inhalt: ByteArray = byteArrayOf(1, 2, 3)) =
        ordner.newFile("sicherung-${System.nanoTime()}.zip").apply { writeBytes(inhalt) }

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

    /**
     * PROMPT_FIX_DATENBANK_SICHERUNG.md Test 4 (Teil 1): keine Datei vorhanden -> Neuanlage per
     * resumable Upload, kein ByteArray-Aufruf.
     */
    @Test
    fun hochladenLegtNeueDateiPerResumableAnWennNochKeineExistiert() = runTest {
        val client = FakeDriveApiClient()
        val datei = tempDatei()

        val ergebnis = DriveDatenbankSicherung.hochladen(client, "wurzel-id", datei)

        assertTrue(ergebnis.isSuccess)
        assertEquals(1, client.neuanlagenResumable)
        assertEquals(0, client.aktualisierungenResumable)
        assertEquals(datei, client.letzteHochgeladeneDatei)
        assertEquals("Fuer die Sicherung darf NIE der ByteArray-Pfad benutzt werden", 0, client.byteArrayAnlegenAufrufe)
        assertEquals(0, client.byteArrayAktualisierenAufrufe)
    }

    /**
     * PROMPT_FIX_DATENBANK_SICHERUNG.md Test 4 (Teil 2): bestehende Datei -> Update per resumable
     * PATCH, keine zweite Datei, kein ByteArray-Aufruf.
     */
    @Test
    fun hochladenAktualisiertBestehendeDateiPerResumableStattEinerZweiten() = runTest {
        val client = FakeDriveApiClient()
        client.bestehendeDatei = DriveDatei(id = "bestehend", name = BACKUP_DATEINAME)
        val datei = tempDatei(byteArrayOf(9))

        val ergebnis = DriveDatenbankSicherung.hochladen(client, "wurzel-id", datei)

        assertTrue(ergebnis.isSuccess)
        assertEquals(0, client.neuanlagenResumable)
        assertEquals(1, client.aktualisierungenResumable)
        assertEquals("bestehend", client.letzteAktualisierteFileId)
        assertEquals(datei, client.letzteHochgeladeneDatei)
        assertEquals("Fuer die Sicherung darf NIE der ByteArray-Pfad benutzt werden", 0, client.byteArrayAnlegenAufrufe)
        assertEquals(0, client.byteArrayAktualisierenAufrufe)
    }

    @Test
    fun herunterladenOhneBackupOrdnerLiefertFehler() = runTest {
        val client = FakeDriveApiClient()

        val ergebnis = DriveDatenbankSicherung.herunterladen(client, "wurzel-id", ordner.newFile("ziel.zip"))

        assertTrue(ergebnis.isFailure)
    }

    @Test
    fun herunterladenOhneDateiImOrdnerLiefertFehler() = runTest {
        val client = FakeDriveApiClient()
        client.bestehenderOrdner = DriveDatei(id = "ordner", name = "BACKUP")

        val ergebnis = DriveDatenbankSicherung.herunterladen(client, "wurzel-id", ordner.newFile("ziel.zip"))

        assertTrue(ergebnis.isFailure)
    }

    /** PROMPT_FIX_DATENBANK_SICHERUNG.md Test 5: Download schreibt STREAMEND in eine Datei, Inhalt identisch. */
    @Test
    fun herunterladenSchreibtDenInhaltDerGefundenenDateiInDieZieldatei() = runTest {
        val client = FakeDriveApiClient()
        client.bestehenderOrdner = DriveDatei(id = "ordner", name = "BACKUP")
        client.bestehendeDatei = DriveDatei(id = "datei", name = BACKUP_DATEINAME)
        client.herunterladenInhalt = byteArrayOf(4, 5, 6, 7, 8)
        val ziel = ordner.newFile("herunter.zip")

        val ergebnis = DriveDatenbankSicherung.herunterladen(client, "wurzel-id", ziel)

        assertTrue(ergebnis.isSuccess)
        assertEquals(ziel, client.letztesHerunterladenZiel)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8), ziel.readBytes())
    }
}
