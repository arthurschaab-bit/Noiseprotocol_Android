package com.example.lrmprotokoll.drive

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Die Ablagestruktur auf Drive (Owner-Vorgabe: `<Ordner>/JJJJMMTT/<Kategorie>`). Ohne
 * Dateisystem und ohne Netz - ein Fehler hier legt Dateien an einer Stelle ab, an der sie
 * niemand sucht.
 */
class DriveAblageTest {

    @Test
    fun derTagesordnerHeisstNachDemDatumInDerZeitzoneDesNutzers() {
        // 23:30 Ortszeit ist 21:30 UTC - der Ordner muss trotzdem der des laufenden Tages sein.
        val abends = Instant.parse("2026-09-04T21:30:00Z")
        assertEquals("20260904", DriveAblage.tagesordner(abends, BERLIN))
    }

    @Test
    fun kurzNachMitternachtBeginntEinNeuerTagesordner() {
        val kurzNachZwoelf = Instant.parse("2026-09-04T22:10:00Z") // 00:10 Ortszeit am 05.
        assertEquals("20260905", DriveAblage.tagesordner(kurzNachZwoelf, BERLIN))
    }

    @Test
    fun jedeKategorieHatIhrenEigenenUnterordner() {
        assertEquals(listOf("20260904", "WAV"), DriveAblage.pfad("20260904", DriveKategorie.WAV))
        assertEquals(listOf("20260904", "Schallmessung"), DriveAblage.pfad("20260904", DriveKategorie.SCHALLMESSUNG))
        assertEquals(listOf("20260904", "Fotos"), DriveAblage.pfad("20260904", DriveKategorie.FOTOS))
        assertEquals(listOf("20260904", "Videos"), DriveAblage.pfad("20260904", DriveKategorie.VIDEOS))
        assertEquals(listOf("20260904", "Bericht"), DriveAblage.pfad("20260904", DriveKategorie.BERICHT))
    }

    @Test
    fun derAnzeigepfadIstDerPfadDenDerNutzerInDriveSieht() {
        assertEquals(
            "Lärmprotokoll/20260904/Videos",
            DriveAblage.anzeigepfad("Lärmprotokoll", "20260904", DriveKategorie.VIDEOS),
        )
    }

    // ------------------------------------------------------------------ Ordnerbaum

    private class FakeOrdnerApi(
        val vorhandene: MutableMap<String, String> = mutableMapOf(),
    ) : DriveApiClient {
        val angelegt = mutableListOf<Pair<String, String?>>()
        var suchAufrufe = 0
        private var naechsteId = 1

        override suspend fun ordnerAnlegen(name: String, elternId: String?): Result<String> {
            angelegt += name to elternId
            val id = "ordner-${naechsteId++}"
            vorhandene["$elternId/$name"] = id
            return Result.success(id)
        }

        override suspend fun ordnerSuchen(name: String, elternId: String?): Result<DriveDatei?> {
            suchAufrufe++
            val id = vorhandene["$elternId/$name"] ?: return Result.success(null)
            return Result.success(DriveDatei(id, name))
        }

        override suspend fun ordnerAuflisten() = Result.success(emptyList<DriveDatei>())
        override suspend fun ordnerUmbenennen(ordnerId: String, neuerName: String) = Result.success(Unit)
        override suspend fun dateiSuchen(name: String, ordnerId: String) = Result.success<DriveDatei?>(null)
        override suspend fun dateienInOrdnerAuflisten(ordnerId: String) = Result.success(emptySet<String>())
        override suspend fun dateiAnlegen(
            name: String, ordnerId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ) = Result.success("datei")
        override suspend fun dateiAktualisieren(
            fileId: String, inhalt: ByteArray, mimeType: String, gzip: Boolean,
        ) = Result.success(Unit)
        override suspend fun dateiHochladenResumable(
            name: String, ordnerId: String, datei: java.io.File, mimeType: String,
            fortsetzenAb: String?, sessionGestartet: suspend (String) -> Unit,
            fortschritt: suspend (Long, Long) -> Unit,
        ): Result<String> = throw NotImplementedError("im Test nicht benoetigt")
    }

    @Test
    fun fehlendeEbenenWerdenAngelegt() = runTest {
        val api = FakeOrdnerApi()
        val baum = DriveOrdnerbaum(api)

        val id = baum.ordnerFuer("wurzel", "20260904", DriveKategorie.FOTOS).getOrThrow()

        assertEquals(listOf("20260904" to "wurzel", "Fotos" to "ordner-1"), api.angelegt)
        assertEquals("ordner-2", id)
    }

    @Test
    fun einVorhandenerOrdnerWirdWiederverwendetStattDupliziert() = runTest {
        // Drive erlaubt gleichnamige Ordner nebeneinander - ohne die Suche haette der Nutzer
        // nach fuenf Zyklen fuenf "Fotos"-Ordner.
        val api = FakeOrdnerApi(mutableMapOf("wurzel/20260904" to "tag-1", "tag-1/Fotos" to "fotos-1"))
        val baum = DriveOrdnerbaum(api)

        val id = baum.ordnerFuer("wurzel", "20260904", DriveKategorie.FOTOS).getOrThrow()

        assertEquals("fotos-1", id)
        assertTrue("Nichts anzulegen", api.angelegt.isEmpty())
    }

    @Test
    fun derZwischenspeicherSpartDieWiederholteSuche() = runTest {
        // Sonst kostete jeder einzelne Upload zwei zusaetzliche API-Aufrufe und liefe bei
        // Dutzenden Fotos in Drives Rate-Limit.
        val api = FakeOrdnerApi()
        val baum = DriveOrdnerbaum(api)

        baum.ordnerFuer("wurzel", "20260904", DriveKategorie.FOTOS).getOrThrow()
        val nachErstemLauf = api.suchAufrufe
        repeat(5) { baum.ordnerFuer("wurzel", "20260904", DriveKategorie.FOTOS).getOrThrow() }

        assertEquals("Kein einziger weiterer Suchaufruf", nachErstemLauf, api.suchAufrufe)
    }

    @Test
    fun verschiedeneKategorienTeilenSichDenTagesordner() = runTest {
        val api = FakeOrdnerApi()
        val baum = DriveOrdnerbaum(api)

        baum.ordnerFuer("wurzel", "20260904", DriveKategorie.FOTOS).getOrThrow()
        baum.ordnerFuer("wurzel", "20260904", DriveKategorie.VIDEOS).getOrThrow()

        assertEquals(
            "Der Tagesordner darf nur einmal entstehen",
            1,
            api.angelegt.count { it.first == "20260904" },
        )
    }

    @Test
    fun einFehlerBeimAnlegenWirdDurchgereichtStattStillInDieWurzelAbzulegen() = runTest {
        val api = object : DriveApiClient by FakeOrdnerApi() {
            override suspend fun ordnerSuchen(name: String, elternId: String?) =
                Result.failure<DriveDatei?>(DriveApiException("kein Zugriff", httpCode = 403))
        }
        val baum = DriveOrdnerbaum(api)

        val ergebnis = baum.ordnerFuer("wurzel", "20260904", DriveKategorie.VIDEOS)

        assertTrue(ergebnis.isFailure)
    }
}
