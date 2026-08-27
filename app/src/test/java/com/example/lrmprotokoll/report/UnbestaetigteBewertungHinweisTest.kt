package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROMPT_M9_UX.md Aufgabe 9: der Bericht muss ehrlich sagen, wenn er kalibrierte Werte ohne
 * bestätigte A-/C-Bewertung enthält - nicht "dBA" behaupten, wo es nicht belegt ist (dieselbe
 * Ehrlichkeit wie [pegelEinheit], hier nur als Kopf-Hinweis statt pro Zeile).
 */
class UnbestaetigteBewertungHinweisTest {

    private fun record(calibratedDbA: Double?, meterWeighting: String?) = NoiseRecord(
        timestamp = 0L,
        amplitude = 0.0,
        filePath = "",
        calibratedDbA = calibratedDbA,
        meterWeighting = meterWeighting,
    )

    @Test
    fun keinKalibrierterWertLiefertKeinenHinweis() {
        val records = listOf(record(calibratedDbA = null, meterWeighting = null))
        assertNull(unbestaetigteBewertungHinweis(records))
    }

    @Test
    fun kalibrierterWertMitBestaetigterBewertungLiefertKeinenHinweis() {
        val records = listOf(record(calibratedDbA = 72.0, meterWeighting = "A"))
        assertNull(unbestaetigteBewertungHinweis(records))
    }

    @Test
    fun kalibrierterWertOhneBestaetigteBewertungLoestDenHinweisAus() {
        // Gegenprobe: schlaegt fehl, wenn die calibratedDbA != null && meterWeighting == null -
        // Bedingung entfernt oder umgedreht wird.
        val records = listOf(record(calibratedDbA = 72.0, meterWeighting = null))
        val hinweis = unbestaetigteBewertungHinweis(records)
        assertTrue(hinweis != null && hinweis.contains("1 von 1"))
    }

    @Test
    fun zaehltNurDieBetroffenenDatensaetzeVonAllen() {
        val records = listOf(
            record(calibratedDbA = 72.0, meterWeighting = null),
            record(calibratedDbA = 65.0, meterWeighting = "A"),
            record(calibratedDbA = null, meterWeighting = null),
        )
        val hinweis = unbestaetigteBewertungHinweis(records)
        assertTrue(hinweis != null && hinweis.contains("1 von 3"))
    }
}
