package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.BeweisVideoEntity
import com.example.lrmprotokoll.data.DokumentationsFotoEntity
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Die Upload-Uebersicht (Owner-Wunsch: "Was wurde hochgeladen, was wird gerade hochgeladen").
 * Was hier steht, ist eine Aussage darueber, ob die Beweise gesichert sind - sie muss stimmen.
 */
class DriveUploadUebersichtTest {

    private fun tagesdatei(datum: String, zustand: String, synced: Long = 1_000) =
        DriveDailyFileEntity(date = datum, fileId = "f", lastSyncedAt = synced, lastRowCount = 1, state = zustand)

    private fun foto(id: Long, hochgeladen: Boolean, am: Long = 2_000) = DokumentationsFotoEntity(
        id = id, sessionId = 1, kategorie = "MESSAUFBAU", dateiPfad = "/pfad/foto_$id.jpg",
        aufgenommenAm = am, driveFileId = if (hochgeladen) "drive-$id" else null,
    )

    private fun video(
        id: Long,
        driveFileId: String? = null,
        sessionUri: String? = null,
        gesendet: Long = 0,
        gesamt: Long = 0,
        am: Long = 3_000,
    ) = BeweisVideoEntity(
        id = id, sessionId = 1, dateiPfad = "/pfad/video_$id.mp4", gestartetAm = am, dauerMs = 1000,
        hatTonspur = true, groesseBytes = gesamt, tonGemuxt = true, driveFileId = driveFileId,
        uploadSessionUri = sessionUri, hochgeladeneBytes = gesendet,
    )

    @Test
    fun einBegonnenerVideoUploadGiltAlsLaufend() {
        val eintraege = DriveUploadUebersicht.baue(
            emptyList(), emptyList(),
            listOf(video(1, sessionUri = "https://drive/session", gesendet = 25, gesamt = 100)),
        )

        val eintrag = eintraege.single()
        assertEquals(UploadZustand.LAEUFT, eintrag.zustand)
        assertEquals(25, eintrag.prozent)
    }

    @Test
    fun einFertigesVideoGiltAlsHochgeladen() {
        val eintraege = DriveUploadUebersicht.baue(
            emptyList(), emptyList(), listOf(video(1, driveFileId = "drive-1", sessionUri = "rest")),
        )
        assertEquals(UploadZustand.HOCHGELADEN, eintraege.single().zustand)
    }

    @Test
    fun ohneBegonnenenUploadIstEinVideoOffen() {
        val eintraege = DriveUploadUebersicht.baue(emptyList(), emptyList(), listOf(video(1)))
        assertEquals(UploadZustand.OFFEN, eintraege.single().zustand)
    }

    @Test
    fun fuerNichtLaufendeUploadsGibtEsKeinenProzentwert() {
        // Fotos und CSV gehen in einem einzigen Aufruf raus - einen Fortschritt zu erfinden,
        // wo keiner gemessen wird, waere gelogen.
        val eintraege = DriveUploadUebersicht.baue(emptyList(), listOf(foto(1, hochgeladen = false)), emptyList())
        assertNull(eintraege.single().prozent)
    }

    @Test
    fun eineLaufendeUebertragungOhneBekannteGroesseZeigtKeinenProzentwert() {
        val eintraege = DriveUploadUebersicht.baue(
            emptyList(), emptyList(), listOf(video(1, sessionUri = "https://drive/session", gesendet = 10, gesamt = 0)),
        )
        assertNull(eintraege.single().prozent)
    }

    @Test
    fun dieTagesdateiUebernimmtIhrenSyncZustand() {
        val eintraege = DriveUploadUebersicht.baue(
            listOf(
                tagesdatei("2026-09-04", DriveSyncState.SYNCED),
                tagesdatei("2026-09-03", DriveSyncState.FAILED),
                tagesdatei("2026-09-02", DriveSyncState.PENDING),
            ),
            emptyList(), emptyList(),
        )

        assertEquals(
            listOf(UploadZustand.HOCHGELADEN, UploadZustand.FEHLGESCHLAGEN, UploadZustand.OFFEN),
            eintraege.map { it.zustand },
        )
    }

    @Test
    fun neuesteEintraegeStehenOben() {
        val eintraege = DriveUploadUebersicht.baue(
            listOf(tagesdatei("2026-09-01", DriveSyncState.SYNCED, synced = 1_000)),
            listOf(foto(1, hochgeladen = true, am = 5_000)),
            listOf(video(1, driveFileId = "d", am = 3_000)),
        )

        assertEquals(listOf(5_000L, 3_000L, 1_000L), eintraege.map { it.zeitpunkt })
    }

    @Test
    fun dieZusammenfassungZaehltJedenZustandAuchDenLeeren() {
        // Eine fehlende Null waere in der Kopfzeile eine Luecke, keine Aussage.
        val eintraege = DriveUploadUebersicht.baue(
            emptyList(),
            listOf(foto(1, hochgeladen = true), foto(2, hochgeladen = false)),
            emptyList(),
        )

        val zusammenfassung = DriveUploadUebersicht.zusammenfassung(eintraege)
        assertEquals(1, zusammenfassung[UploadZustand.HOCHGELADEN])
        assertEquals(1, zusammenfassung[UploadZustand.OFFEN])
        assertEquals(0, zusammenfassung[UploadZustand.LAEUFT])
        assertEquals(0, zusammenfassung[UploadZustand.FEHLGESCHLAGEN])
    }

    @Test
    fun eineLeereUebersichtIstKeinFehler() {
        assertEquals(emptyList<UploadEintrag>(), DriveUploadUebersicht.baue(emptyList(), emptyList(), emptyList()))
    }
}
