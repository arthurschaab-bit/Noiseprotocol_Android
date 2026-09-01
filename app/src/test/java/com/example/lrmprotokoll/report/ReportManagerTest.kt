package com.example.lrmprotokoll.report

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 4: [ReportManagerEinheitTest] deckt bereits die dBA/dB-Unterscheidung
 * im Tagesbericht ab - hier die restlichen Luecken: die beiden reinen Funktionen [pegelEinheit]/
 * [unbestaetigteBewertungHinweis] direkt (statt nur indirekt ueber den Berichtstext), der bisher
 * komplett ungetestete [ReportManager.generatePeriodReport] (F12), und Randfaelle (leere Liste,
 * Einzelwert) fuer beide Berichte.
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

    // --- generatePeriodReport (F12): bisher komplett ungetestet ---

    @Test
    fun zeitraumberichtOhneEreignisseWirftNichtUndZeigtNullwerte() {
        val datei = manager().generatePeriodReport(emptyList(), title = "Testzeitraum")
        val text = datei.readText()
        assertTrue(text.contains("Testzeitraum"))
        assertTrue(text.contains("Dokumentierte Lärmereignisse: 0"))
        assertFalse(
            "Ohne Ereignisse darf keine Geraeuschquellen-Statistik erscheinen",
            text.contains("HÄUFIGSTE GERÄUSCHQUELLEN"),
        )
    }

    @Test
    fun zeitraumberichtBerechnetDurchschnittUndMaximumKorrekt() {
        val ereignisse = listOf(ereignis(dbValue = 40.0), ereignis(dbValue = 60.0))
        val datei = manager().generatePeriodReport(ereignisse, title = "Testzeitraum")
        val text = datei.readText()
        assertTrue("Maximum muss der groessere Wert sein", text.contains("60,0 dB") || text.contains("60.0 dB"))
        assertTrue(
            "Durchschnitt von 40 und 60 muss 50 sein",
            text.contains("50,0 dB") || text.contains("50.0 dB"),
        )
    }

    @Test
    fun zeitraumberichtListetDieHaeufigstenGeraeuschquellenAbsteigendSortiert() {
        val ereignisse = listOf(
            ereignis(detectedLabel = "Verkehr"),
            ereignis(detectedLabel = "Verkehr"),
            ereignis(detectedLabel = "Baustelle"),
        )
        val datei = manager().generatePeriodReport(ereignisse, title = "Testzeitraum")
        val text = datei.readText()
        val positionVerkehr = text.indexOf("Verkehr: 2")
        val positionBaustelle = text.indexOf("Baustelle: 1")
        assertTrue(
            "Das haeufigere Label muss vor dem selteneren stehen",
            positionVerkehr in 0 until positionBaustelle,
        )
    }

    @Test
    fun zeitraumberichtMarkiertRuhezeitEreignisseInDerChronologischenListe() {
        val datei = manager().generatePeriodReport(listOf(ereignis(isQuietHour = true)), title = "Testzeitraum")
        assertTrue(datei.readText().contains("[RUHEZEIT]"))
    }

    @Test
    fun zeitraumberichtOhneErkanntesLabelZeigtUnbekannt() {
        val datei = manager().generatePeriodReport(listOf(ereignis()), title = "Testzeitraum")
        assertTrue(datei.readText().contains("Unbekannt"))
    }
}
