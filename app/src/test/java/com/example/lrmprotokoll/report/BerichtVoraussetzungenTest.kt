package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.ReportConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BerichtVoraussetzungenTest {
    private val tag = LocalDate.of(2026, 9, 25)

    private val berichtTag = BerichtTag(
        datum = tag,
        von = 0,
        bis = 1,
        sessionIds = emptyList(),
        rohwerte = 1,
        verdichteteMinuten = 0,
        unbestaetigteWerte = 0,
        stammdatenKandidaten = emptyList(),
    )

    @Test fun fehlenderZeitraumUndGebietSindSchonVorDemExportSichtbar() {
        val ergebnis = pruefeBerichtVoraussetzungen(null, emptyList(), ReportConfigEntity(), emptyMap())

        assertEquals("Bitte zuerst einen Datumsbereich wählen.", ergebnis.first().fehler)
        assertTrue(ergebnis.single { it.id == BerichtVoraussetzungId.GEBIET }.fehler != null)
    }

    @Test fun rohwerteUndGueltigesGebietGebenDenExportFrei() {
        val ergebnis =
            pruefeBerichtVoraussetzungen(
                BerichtZeitraum(tag, tag),
                listOf(berichtTag),
                ReportConfigEntity(gebietseinstufung = "WA"),
                emptyMap(),
            )

        assertTrue(ergebnis.all { it.erfuellt })
    }

    @Test fun verdichteteRohdatenBleibenBlockierend() {
        val ergebnis =
            pruefeBerichtVoraussetzungen(
                BerichtZeitraum(tag, tag),
                listOf(berichtTag.copy(verdichteteMinuten = 1)),
                ReportConfigEntity(gebietseinstufung = "WA"),
                emptyMap(),
            )

        assertTrue(ergebnis.single { it.id == BerichtVoraussetzungId.RETENTION }.fehler != null)
    }
}
