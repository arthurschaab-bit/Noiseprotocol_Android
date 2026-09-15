package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BerichtErstellungTest {

    private val datum = LocalDate.of(2026, 9, 12)

    private fun tag(
        rohwerte: Int = 10,
        verdichteteMinuten: Int = 0,
        unbestaetigteWerte: Int = 0,
        stammdaten: List<StammdatenVerlaufEntity> = emptyList(),
    ) = BerichtTag(datum, 1_000, 2_000, listOf(1), rohwerte, verdichteteMinuten, unbestaetigteWerte, stammdaten)

    private fun stammdaten(id: Long) = StammdatenVerlaufEntity(
        id = id, erstelltAm = 1_500, geraetHersteller = "PCE", geraetTyp = "323",
        geraetGenauigkeitsklasse = "2", geraetSeriennummer = "SN$id", geraetKalibrierung = "Kalibriert",
        messort = "Musterort", mikrofonposition = "Fenster", mikrofonhoehe = "1m",
        entfernungZurQuelle = "5m", innenAussen = "Außen", fensterzustand = "",
        wetter = "trocken", datenqualitaetHinweis = "",
    )

    @Test fun verdichteterTagWirdAuchBeiVerbliebenenRohwertenAbgelehnt() {
        assertNull(retentionFehler(listOf(tag(rohwerte = 10))))
        val fehler = retentionFehler(listOf(tag(rohwerte = 10, verdichteteMinuten = 1)))
        assertTrue(fehler!!.contains("12.09.2026"))
    }

    @Test fun fehlendeUndEinzelneStammdatenSindOhneAuswahlZulaessig() {
        assertNull(auswahlFehler(listOf(tag(stammdaten = emptyList())), emptyMap()))
        assertNull(gewaehlteStammdaten(tag(stammdaten = emptyList()), emptyMap()))
        assertNull(auswahlFehler(listOf(tag(stammdaten = listOf(stammdaten(1)))), emptyMap()))
        assertEquals(1L, gewaehlteStammdaten(tag(stammdaten = listOf(stammdaten(1))), emptyMap())?.id)
    }

    @Test fun mehrereStammdatenBrauchenExpliziteAuswahl() {
        val tag = tag(stammdaten = listOf(stammdaten(1), stammdaten(2)))
        assertTrue(auswahlFehler(listOf(tag), emptyMap())!!.contains("12.09.2026"))
        assertNull(auswahlFehler(listOf(tag), mapOf(datum to 2L)))
        assertEquals(2L, gewaehlteStammdaten(tag, mapOf(datum to 2L))?.id)
        assertTrue(auswahlFehler(listOf(tag), mapOf(datum to 99L)) != null)
    }

    @Test fun unbestaetigteBewertungNurMitBewusstemOverrideZulaessig() {
        val bestaetigt = listOf(tag(unbestaetigteWerte = 0))
        val unbestaetigt = listOf(tag(unbestaetigteWerte = 1))
        assertNull(bewertungsFehler(bestaetigt, ReportConfigEntity()))
        assertTrue(bewertungsFehler(unbestaetigt, ReportConfigEntity())!!.contains("A-/Zeitbewertung"))
        assertNull(bewertungsFehler(unbestaetigt, ReportConfigEntity(erzwingeBerichtOhneBestaetigteBewertung = true)))
        assertNull(bewertungsFehler(bestaetigt, ReportConfigEntity(erzwingeBerichtOhneBestaetigteBewertung = true)))
    }

    @Test fun fehlendeFelderBleibenAlsLueckeErkennbar() {
        assertEquals(listOf("Stammdaten insgesamt"), fehlendeStammdatenFelder(null))
        assertTrue(fehlendeStammdatenFelder(stammdaten(1).copy(messort = "")).contains("Messort"))
        assertFalse(fehlendeStammdatenFelder(stammdaten(1)).contains("Fensterzustand"))
        assertTrue(fehlendeStammdatenFelder(stammdaten(1).copy(innenAussen = "Innen")).contains("Fensterzustand"))
    }

    @Test fun dateRangePickerUtcDatumWirdNichtAlsLokalerZeitstempelMissverstanden() {
        val zeitraum = BerichtZeitraum.ausPicker(1_789_171_200_000L, 1_789_257_600_000L)
        assertEquals(2, zeitraum.tage().size)
    }
}
