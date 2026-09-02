package com.example.lrmprotokoll.report

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 4: [ReportManagerEinheitTest] deckt bereits die dBA/dB-Unterscheidung
 * im Tagesbericht ab - hier die restlichen Luecken: die beiden reinen Funktionen [pegelEinheit]/
 * [unbestaetigteBewertungHinweis] direkt (statt nur indirekt ueber den Berichtstext) und
 * Randfaelle (leere Liste, Einzelwert) fuer den Tagesbericht.
 *
 * Die Tests fuer `generatePeriodReport` sind mit der Funktion selbst entfallen: Sie war aus dem
 * Produktivcode nicht erreichbar (kein einziger Aufrufer), der sichtbare Zeitraumbericht kommt
 * von [PeriodenBerichtExport]. Die Tests haben also nur noch sich selbst am Leben gehalten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportManagerTest {

    private fun manager() = ReportManager(ApplicationProvider.getApplicationContext())

    private fun ereignis(
        timestamp: Long = 1_700_000_000_000L,
        dbValue: Double = 62.0,
        calibratedDbA: Double? = null,
        meterWeighting: String? = null,
        isQuietHour: Boolean = false,
        label: String? = null,
        detectedLabel: String? = null,
    ) = NoiseRecord(
        timestamp = timestamp, amplitude = 0.0, dbValue = dbValue, filePath = "/data/rec.wav",
        calibratedDbA = calibratedDbA, meterWeighting = meterWeighting, isQuietHour = isQuietHour,
        label = label, detectedLabel = detectedLabel,
    )

    // --- pegelEinheit ---

    @Test
    fun pegelEinheitLiefertDbaNurBeiBestaetigterABewertung() {
        assertEquals("dBA", pegelEinheit("A"))
    }

    @Test
    fun pegelEinheitLiefertDbcBeiBestaetigterCBewertung() {
        assertEquals("dBC", pegelEinheit("C"))
    }

    @Test
    fun pegelEinheitFaelltOhneBestaetigungAufReinesDbZurueck() {
        assertEquals("dB", pegelEinheit(null))
        assertEquals("dB", pegelEinheit("irgendetwas anderes"))
    }

    // --- unbestaetigteBewertungHinweis ---

    @Test
    fun unbestaetigteBewertungHinweisIstNullOhneEreignisse() {
        assertNull(unbestaetigteBewertungHinweis(emptyList()))
    }

    @Test
    fun unbestaetigteBewertungHinweisIstNullWennAlleEreignisseBestaetigtOderUnkalibriertSind() {
        val ereignisse = listOf(
            ereignis(calibratedDbA = 60.0, meterWeighting = "A"),
            ereignis(calibratedDbA = null, meterWeighting = null),
        )
        assertNull(unbestaetigteBewertungHinweis(ereignisse))
    }

    @Test
    fun unbestaetigteBewertungHinweisNenntDieBetroffeneAnzahl() {
        val ereignisse = listOf(
            ereignis(calibratedDbA = 60.0, meterWeighting = null), // betroffen
            ereignis(calibratedDbA = 65.0, meterWeighting = "A"), // nicht betroffen
            ereignis(calibratedDbA = 70.0, meterWeighting = null), // betroffen
        )
        val hinweis = unbestaetigteBewertungHinweis(ereignisse)
        assertTrue(hinweis != null && hinweis.contains("2 von 3"))
    }

    // --- generateDailyReport: Randfaelle ---

    @Test
    fun tagesberichtOhneEreignisseWirftNichtUndZeigtNullwerte() {
        val datei = manager().generateDailyReport(emptyList())
        val text = datei.readText()
        assertTrue(text.contains("Gesamtzahl Ereignisse: 0"))
        assertTrue(text.contains("Ereignisse in Ruhezeiten: 0"))
    }

    @Test
    fun tagesberichtMitEinzelnemEreignisZeigtDessenPegelAlsSpitzenpegel() {
        val datei = manager().generateDailyReport(listOf(ereignis(dbValue = 42.0)))
        val text = datei.readText()
        assertTrue(text.contains("Gesamtzahl Ereignisse: 1"))
        assertTrue(text.contains("42,0 dB") || text.contains("42.0 dB"))
    }
}
