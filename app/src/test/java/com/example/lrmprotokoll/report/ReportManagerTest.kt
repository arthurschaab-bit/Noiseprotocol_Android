package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Was vom frueheren Tagesbericht-Test uebrig bleibt: die beiden reinen Funktionen
 * [pegelEinheit] und [unbestaetigteBewertungHinweis].
 *
 * Alle Pruefungen, die den fertigen Berichtstext gelesen haben, sind nach
 * [TagesberichtDatenTest] gewandert - der Tagesbericht ist jetzt ein PDF, und `PdfDocument`
 * laesst sich unter Robolectric weder erzeugen noch zurueckliesen. Geprueft wird deshalb das
 * Inhaltsmodell aus [ermittleTagesbericht] statt der gerenderten Ausgabe; die fachlichen
 * Zusagen (insbesondere "dBA nur bei bestaetigter Bewertung") sind dort vollstaendig erhalten
 * und brauchen dafuer nicht einmal mehr Robolectric.
 */
class ReportManagerTest {

    private fun ereignis(
        dbValue: Double = 62.0,
        calibratedDbA: Double? = null,
        meterWeighting: String? = null,
    ) = NoiseRecord(
        timestamp = 1_700_000_000_000L, amplitude = 0.0, dbValue = dbValue,
        filePath = "/data/rec.wav", calibratedDbA = calibratedDbA, meterWeighting = meterWeighting,
    )

    @Test
    fun pegelEinheitLiefertNurBeiBelegterBewertungEineGewichteteEinheit() {
        assertEquals("dBA", pegelEinheit("A"))
        assertEquals("dBC", pegelEinheit("C"))
        assertEquals("dB", pegelEinheit(null))
        assertEquals("dB", pegelEinheit("unbekannt"))
    }

    @Test
    fun hinweisNennntDieAnzahlDerBetroffenenEreignisse() {
        val hinweis = unbestaetigteBewertungHinweis(
            listOf(
                ereignis(calibratedDbA = 70.0, meterWeighting = null),
                ereignis(calibratedDbA = 71.0, meterWeighting = null),
                ereignis(calibratedDbA = 72.0, meterWeighting = "A"),
            )
        )
        assertNotNull(hinweis)
        assertTrue(hinweis!!.contains("2 von 3"))
    }

    @Test
    fun ohneBetroffeneEreignisseGibtEsKeinenHinweis() {
        assertNull(unbestaetigteBewertungHinweis(listOf(ereignis(calibratedDbA = 70.0, meterWeighting = "A"))))
        assertNull(unbestaetigteBewertungHinweis(listOf(ereignis(calibratedDbA = null))))
        assertNull(unbestaetigteBewertungHinweis(emptyList()))
    }
}
