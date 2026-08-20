package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

class ChartDatenTest {

    private fun messwert(t: Long, db: Double) =
        MeasurementEntity(sessionId = 1, timestamp = t, levelDb = db, weighting = null, flags = 0)

    private fun aggregat(minuteStart: Long, min: Double, max: Double, leq: Double, anzahl: Int) =
        MinuteAggregateEntity(
            sessionId = 1, minuteStart = minuteStart, leqDb = leq, maxDb = max, minDb = min,
            sampleCount = anzahl, weighting = null,
        )

    @Test
    fun leereMesswerteErgebenKeineSpalten() {
        val ergebnis = downsampleMesswerteFuerChart(emptyList(), sessionStart = 0, sessionEnde = 10_000)
        assertTrue(ergebnis.isEmpty())
    }

    @Test
    fun sessionEndeVorSessionStartErgibtKeineSpalten() {
        val werte = listOf(messwert(0, 50.0))
        val ergebnis = downsampleMesswerteFuerChart(werte, sessionStart = 10_000, sessionEnde = 0)
        assertTrue(ergebnis.isEmpty())
    }

    @Test
    fun einZeitfensterFasstMinMaxMittelKorrektZusammen() {
        // 3 Werte in einem einzigen Fenster (maxSpalten=1 -> genau ein Fenster über die ganze Session).
        val werte = listOf(messwert(0, 40.0), messwert(1_000, 60.0), messwert(2_000, 50.0))

        val ergebnis = downsampleMesswerteFuerChart(werte, sessionStart = 0, sessionEnde = 3_000, maxSpalten = 1)

        assertEquals(1, ergebnis.size)
        val spalte = ergebnis.single()
        assertEquals(0L, spalte.zeitOffsetSekunden)
        assertEquals(40.0, spalte.minDb, 0.0001)
        assertEquals(60.0, spalte.maxDb, 0.0001)
        assertEquals(3, spalte.anzahl)
        val erwarteterMittelwert = 10.0 * log10((10.0.pow(4.0) + 10.0.pow(6.0) + 10.0.pow(5.0)) / 3.0)
        assertEquals(erwarteterMittelwert, spalte.mittelDb, 0.0001)
    }

    @Test
    fun zweiGetrennteFensterErgebenZweiSpaltenMitKorrektemZeitoffset() {
        // Fenstergröße = (10_000 - 0) / 2 = 5_000ms. Ein Wert früh, einer spät -> zwei Spalten.
        val werte = listOf(messwert(0, 40.0), messwert(9_000, 80.0))

        val ergebnis = downsampleMesswerteFuerChart(werte, sessionStart = 0, sessionEnde = 10_000, maxSpalten = 2)

        assertEquals(2, ergebnis.size)
        assertEquals(0L, ergebnis[0].zeitOffsetSekunden)
        assertEquals(40.0, ergebnis[0].mittelDb, 0.0001)
        assertEquals(5L, ergebnis[1].zeitOffsetSekunden)
        assertEquals(80.0, ergebnis[1].mittelDb, 0.0001)
    }

    @Test
    fun luekeOhneMesswerteErzeugtKeineSpalteFuerDiesesFenster() {
        // Fenster 1 (Index 1) bleibt ohne Werte - es darf keine Spalte mit 0 dB dafür entstehen.
        val werte = listOf(messwert(0, 40.0), messwert(9_000, 80.0))

        val ergebnis = downsampleMesswerteFuerChart(werte, sessionStart = 0, sessionEnde = 15_000, maxSpalten = 3)

        assertEquals(2, ergebnis.size)
        assertTrue(ergebnis.none { it.mittelDb == 0.0 })
    }

    @Test
    fun aggregateWerdenMitSampleCountGewichtetZusammengefasst() {
        // Zwei Minuten mit unterschiedlichem sampleCount in einem Fenster - die Minute mit mehr
        // Samples muss den Mittelwert staerker beeinflussen als eine gleichgewichtete Mittelung.
        val aggregate = listOf(
            aggregat(minuteStart = 0, min = 30.0, max = 50.0, leq = 40.0, anzahl = 100),
            aggregat(minuteStart = 60_000, min = 60.0, max = 90.0, leq = 80.0, anzahl = 1),
        )

        val ergebnis = downsampleAggregateFuerChart(aggregate, sessionStart = 0, sessionEnde = 120_000, maxSpalten = 1)

        assertEquals(1, ergebnis.size)
        val spalte = ergebnis.single()
        assertEquals(30.0, spalte.minDb, 0.0001)
        assertEquals(90.0, spalte.maxDb, 0.0001)
        assertEquals(101, spalte.anzahl)
        val erwarteterMittelwert = 10.0 * log10((100 * 10.0.pow(4.0) + 1 * 10.0.pow(8.0)) / 101.0)
        assertEquals(erwarteterMittelwert, spalte.mittelDb, 0.0001)
        // Energetische Mittelung: die einzelne 80-dB-Minute traegt trotz sampleCount=1 so viel
        // Schallleistung bei wie 10.000 Minuten bei 40 dB (40 dB Differenz = Faktor 10^4) - sie
        // dominiert das Ergebnis trotz der 100 leiseren Samples, anders als eine arithmetische
        // (gleichgewichtete) Mittelung es täte.
        assertTrue(spalte.mittelDb > 55.0)
    }
}
