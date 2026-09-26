package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.ReportConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessintegritaetTest {
    @Test
    fun vollstaendigWennVerfuegbarkeitUeberSchwelleUndKeineGapsUndNichtVerdichtet() {
        val von = 1_000_000L
        val bis = von + 3_600_000L // 1 Stunde

        val befund =
            bewerteMessintegritaet(
                von = von,
                bis = bis,
                ausfallbaender = emptyList(),
                gapAnzahl = 0,
                verdichteteMinuten = 0,
                unbestaetigteWerte = 0,
            )

        assertEquals(Messintegritaet.VOLLSTAENDIG, befund.stufe)
        assertEquals(100.0, befund.verfuegbarkeitProzent, 0.001)
        assertEquals(0, befund.ausfaelle)
        assertEquals(0L, befund.ausfallDauerMs)
        assertFalse(befund.rohdatenVerdichtet)
        assertEquals(0, befund.gapAnzahl)
    }

    @Test
    fun eingeschraenktWennGapsVorhandenTrotzHoherVerfuegbarkeit() {
        val von = 1_000_000L
        val bis = von + 3_600_000L

        val befund =
            bewerteMessintegritaet(
                von = von,
                bis = bis,
                ausfallbaender = emptyList(),
                gapAnzahl = 2,
                verdichteteMinuten = 0,
            )

        assertEquals(Messintegritaet.EINGESCHRAENKT, befund.stufe)
        assertEquals(100.0, befund.verfuegbarkeitProzent, 0.001)
        assertEquals(2, befund.gapAnzahl)
    }

    @Test
    fun eingeschraenktWennVerfuegbarkeitZwischenSchwellen() {
        val von = 1_000_000L
        val bis = von + 100_000L // 100 s
        // 20 s Ausfall -> 80% Verfuegbarkeit (liegt zwischen 70% und 90%)
        val ausfall = listOf(Ausfallband(von + 10_000L, von + 30_000L))

        val befund =
            bewerteMessintegritaet(
                von = von,
                bis = bis,
                ausfallbaender = ausfall,
                gapAnzahl = 0,
                verdichteteMinuten = 0,
            )

        assertEquals(Messintegritaet.EINGESCHRAENKT, befund.stufe)
        assertEquals(80.0, befund.verfuegbarkeitProzent, 0.001)
        assertEquals(1, befund.ausfaelle)
        assertEquals(20_000L, befund.ausfallDauerMs)
    }

    @Test
    fun lueckenhaftWennVerfuegbarkeitUnterTeilerfassungsschwelle() {
        val von = 1_000_000L
        val bis = von + 100_000L
        // 40 s Ausfall -> 60% Verfuegbarkeit (< 70%)
        val ausfall = listOf(Ausfallband(von + 10_000L, von + 50_000L))

        val befund =
            bewerteMessintegritaet(
                von = von,
                bis = bis,
                ausfallbaender = ausfall,
                gapAnzahl = 0,
                verdichteteMinuten = 0,
            )

        assertEquals(Messintegritaet.LUECKENHAFT, befund.stufe)
        assertEquals(60.0, befund.verfuegbarkeitProzent, 0.001)
        assertEquals(1, befund.ausfaelle)
        assertEquals(40_000L, befund.ausfallDauerMs)
    }

    @Test
    fun lueckenhaftWennRohdatenVerdichtetSind() {
        val von = 1_000_000L
        val bis = von + 3_600_000L

        val befund =
            bewerteMessintegritaet(
                von = von,
                bis = bis,
                ausfallbaender = emptyList(),
                gapAnzahl = 0,
                verdichteteMinuten = 60,
            )

        assertEquals(Messintegritaet.LUECKENHAFT, befund.stufe)
        assertTrue(befund.rohdatenVerdichtet)
        assertEquals(100.0, befund.verfuegbarkeitProzent, 0.001)
    }

    @Test
    fun konfigurierbareSchwellenAusReportConfigWerdenAngewendet() {
        val von = 1_000_000L
        val bis = von + 100_000L
        val ausfall = listOf(Ausfallband(von + 10_000L, von + 18_000L)) // 92% Verfuegbarkeit
        val customConfig =
            ReportConfigEntity(
                tierSchwelleVollmessungProzent = 95.0,
                tierSchwelleTeilerfassungProzent = 85.0,
            )

        val befund =
            bewerteMessintegritaet(
                von = von,
                bis = bis,
                ausfallbaender = ausfall,
                gapAnzahl = 0,
                verdichteteMinuten = 0,
                config = customConfig,
            )

        // 92% ist unter 95%, aber >= 85% -> EINGESCHRAENKT
        assertEquals(Messintegritaet.EINGESCHRAENKT, befund.stufe)
        assertEquals(92.0, befund.verfuegbarkeitProzent, 0.001)
    }

    @Test
    fun grenzfallNullDauerErgibtLueckenhaft() {
        val befund =
            bewerteMessintegritaet(
                von = 1000L,
                bis = 1000L,
                ausfallbaender = emptyList(),
            )

        assertEquals(Messintegritaet.LUECKENHAFT, befund.stufe)
        assertEquals(0.0, befund.verfuegbarkeitProzent, 0.001)
    }
}
