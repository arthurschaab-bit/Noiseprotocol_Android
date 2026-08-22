package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.LevelSampleEntity
import com.example.lrmprotokoll.data.LevelSource
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PegelAggregatorTest {

    private val t0 = Instant.parse("2026-08-16T06:00:00Z") // 08:00 MESZ

    private fun sample(sekundenNachT0: Long, db: Double, quelle: String = LevelSource.PCE_323) =
        LevelSampleEntity(at = t0.plusSeconds(sekundenNachT0).toEpochMilli(), levelDb = db, source = quelle)

    @Test
    fun einzelnesFensterMitEinemWertLiefertDiesenWertUnveraendert() {
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0)),
            ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )

        assertEquals(1, zeilen.size)
        assertEquals(60.0, zeilen[0].laeqDb!!, 0.0001)
        assertEquals(60.0, zeilen[0].lafMaxDb!!, 0.0001)
        assertEquals(60.0, zeilen[0].lafMinDb!!, 0.0001)
        assertEquals(1, zeilen[0].samples)
    }

    @Test
    fun laeqIstDerEnergetischeMittelwertNichtDerArithmetische() {
        // Zwei Werte mit 10 dB Unterschied: 60 dB und 70 dB. Arithmetisch waere der Mittelwert
        // 65 dB - energetisch liegt er naeher am lauteren Wert (Plan 8.3, der klassische Fehler).
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0), sample(1, 70.0)),
            ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )

        // 10*log10((10^6 + 10^7) / 2) = 10*log10(5.500.000) ~ 67,40 - naeher am lauteren Wert
        // als das arithmetische Mittel (65), aber nicht bei ihm: die Formel gewichtet
        // energetisch, nicht linear.
        val laeq = zeilen[0].laeqDb!!
        assertEquals(67.4036, laeq, 0.001)
        assertTrue("LAeq muss ueber dem arithmetischen Mittel (65) liegen", laeq > 65.0)
        assertEquals(70.0, zeilen[0].lafMaxDb!!, 0.0001)
        assertEquals(60.0, zeilen[0].lafMinDb!!, 0.0001)
    }

    @Test
    fun zweiGleicheWerteLiefernDenselbenWertAlsLaeq() {
        // Gegenprobe zum vorigen Test: bei identischen Pegeln muss energetisch = arithmetisch
        // gelten (10*log10(2*10^6/2) = 60), sonst waere die Formel schlicht falsch angewendet.
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0), sample(1, 60.0)),
            ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )
        assertEquals(60.0, zeilen[0].laeqDb!!, 0.0001)
    }

    @Test
    fun fensterOhneSampleWirdAlsLueckeAusgegebenNichtAusgelassen() {
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0), sample(25, 65.0)), // Im 1. und 3. Fenster -> 2. Fenster ist Lücke
            ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(30), fensterDauer = Duration.ofSeconds(10),
        )

        assertEquals("Drei Fenster muessen entstehen, mit Lücke im 2. Fenster", 3, zeilen.size)
        assertEquals(QUELLE_KEINE_VERBINDUNG, zeilen[1].quelle)
        assertNull(zeilen[1].laeqDb)
        assertEquals(0, zeilen[1].samples)
    }

    @Test
    fun leereZeitenVorUndNachMessungWerdenNichtAlsLeereZeilenErzeugt() {
        // Messung lief nur von Minute 10 bis 12 am Tag
        val startM10 = t0.plusSeconds(600)
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(LevelSampleEntity(at = startM10.toEpochMilli(), levelDb = 55.0, source = LevelSource.PCE_323)),
            ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(3600), fensterDauer = Duration.ofSeconds(60),
        )
        // Nur 1 Fenster für die tatsächliche Messung, keine 60 leeren Fenster
        assertEquals(1, zeilen.size)
        assertEquals(55.0, zeilen[0].laeqDb!!, 0.001)
    }

    @Test
    fun gemischteQuellenImSelbenFensterWerdenAlsGemischtMarkiert() {
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0, LevelSource.PCE_323), sample(1, 62.0, LevelSource.MIKROFON)),
            ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )
        assertEquals(QUELLE_GEMISCHT, zeilen[0].quelle)
    }

    @Test
    fun reineQuelleBleibtAlsSolcheErkennbar() {
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0, LevelSource.MIKROFON), sample(1, 61.0, LevelSource.MIKROFON)),
            ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )
        assertEquals(LevelSource.MIKROFON, zeilen[0].quelle)
    }

    @Test
    fun ereignisImFensterWirdAlsJaMarkiertMitKlassifikation() {
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0)),
            ereignisse = listOf(ProtokollEreignis(at = t0.plusSeconds(2), klassifikation = "Hämmern")),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )
        assertTrue(zeilen[0].ereignis)
        assertEquals("Hämmern", zeilen[0].klassifikation)
    }

    @Test
    fun ereignisAusserhalbDesFenstersWirdNichtZugeordnet() {
        val zeilen = PegelAggregator.aggregiere(
            samples = listOf(sample(0, 60.0)),
            ereignisse = listOf(ProtokollEreignis(at = t0.plusSeconds(15), klassifikation = "Hämmern")),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )
        assertEquals(1, zeilen.size)
        assertTrue("Das Ereignis liegt im naechsten Fenster, nicht in diesem", !zeilen[0].ereignis)
    }

    @Test
    fun ereignisInEinemLueckenfensterWirdTrotzdemVermerkt() {
        // Denkbar bei Mikrofon-Ereignissen waehrend eines Messgeraet-Ausfalls.
        val zeilen = PegelAggregator.aggregiere(
            samples = emptyList(),
            ereignisse = listOf(ProtokollEreignis(at = t0.plusSeconds(2), klassifikation = "Sirene")),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(10),
        )
        assertEquals(QUELLE_KEINE_VERBINDUNG, zeilen[0].quelle)
        assertTrue(zeilen[0].ereignis)
        assertEquals("Sirene", zeilen[0].klassifikation)
    }

    @Test
    fun leererZeitraumLiefertKeineZeilen() {
        val zeilen = PegelAggregator.aggregiere(
            samples = emptyList(), ereignisse = emptyList(),
            von = t0, bis = t0, fensterDauer = Duration.ofSeconds(10),
        )
        assertEquals(0, zeilen.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeFensterDauerWirdAbgelehnt() {
        PegelAggregator.aggregiere(
            samples = emptyList(), ereignisse = emptyList(),
            von = t0, bis = t0.plusSeconds(10), fensterDauer = Duration.ofSeconds(-1),
        )
    }
}
