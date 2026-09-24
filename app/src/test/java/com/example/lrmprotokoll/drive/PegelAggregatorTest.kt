package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.LevelSampleEntity
import com.example.lrmprotokoll.data.LevelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PegelAggregatorTest {
    private val t0 = Instant.parse("2026-08-16T06:00:00Z") // 08:00 MESZ

    private fun sample(
        sekundenNachT0: Long,
        db: Double,
        quelle: String = LevelSource.PCE_323,
    ) = LevelSampleEntity(at = t0.plusSeconds(sekundenNachT0).toEpochMilli(), levelDb = db, source = quelle)

    @Test
    fun einzelnesFensterMitEinemWertLiefertDiesenWertUnveraendert() {
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0)),
                ereignisse = emptyList(),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
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
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0), sample(1, 70.0)),
                ereignisse = emptyList(),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
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
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0), sample(1, 60.0)),
                ereignisse = emptyList(),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
            )
        assertEquals(60.0, zeilen[0].laeqDb!!, 0.0001)
    }

    @Test
    fun fensterOhneSampleWirdAlsLueckeAusgegebenNichtAusgelassen() {
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0), sample(25, 65.0)), // Im 1. und 3. Fenster -> 2. Fenster ist Lücke
                ereignisse = emptyList(),
                von = t0,
                bis = t0.plusSeconds(30),
                fensterDauer = Duration.ofSeconds(10),
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
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(LevelSampleEntity(at = startM10.toEpochMilli(), levelDb = 55.0, source = LevelSource.PCE_323)),
                ereignisse = emptyList(),
                von = t0,
                bis = t0.plusSeconds(3600),
                fensterDauer = Duration.ofSeconds(60),
            )
        // Nur 1 Fenster für die tatsächliche Messung, keine 60 leeren Fenster
        assertEquals(1, zeilen.size)
        assertEquals(55.0, zeilen[0].laeqDb!!, 0.001)
    }

    @Test
    fun gemischteQuellenImSelbenFensterWerdenAlsGemischtMarkiert() {
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0, LevelSource.PCE_323), sample(1, 62.0, LevelSource.MIKROFON)),
                ereignisse = emptyList(),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
            )
        assertEquals(QUELLE_GEMISCHT, zeilen[0].quelle)
    }

    @Test
    fun reineQuelleBleibtAlsSolcheErkennbar() {
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0, LevelSource.MIKROFON), sample(1, 61.0, LevelSource.MIKROFON)),
                ereignisse = emptyList(),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
            )
        assertEquals(LevelSource.MIKROFON, zeilen[0].quelle)
    }

    @Test
    fun ereignisImFensterWirdAlsJaMarkiertMitKlassifikation() {
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0)),
                ereignisse = listOf(ProtokollEreignis(at = t0.plusSeconds(2), klassifikation = "Hämmern")),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
            )
        assertTrue(zeilen[0].ereignis)
        assertEquals("Hämmern", zeilen[0].klassifikation)
    }

    @Test
    fun ereignisAusserhalbDesFenstersWirdNichtZugeordnet() {
        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sample(0, 60.0)),
                ereignisse = listOf(ProtokollEreignis(at = t0.plusSeconds(15), klassifikation = "Hämmern")),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
            )
        assertEquals(1, zeilen.size)
        assertTrue("Das Ereignis liegt im naechsten Fenster, nicht in diesem", !zeilen[0].ereignis)
    }

    @Test
    fun ereignisInEinemLueckenfensterWirdTrotzdemVermerkt() {
        // Denkbar bei Mikrofon-Ereignissen waehrend eines Messgeraet-Ausfalls.
        val zeilen =
            PegelAggregator.aggregiere(
                samples = emptyList(),
                ereignisse = listOf(ProtokollEreignis(at = t0.plusSeconds(2), klassifikation = "Sirene")),
                von = t0,
                bis = t0.plusSeconds(10),
                fensterDauer = Duration.ofSeconds(10),
            )
        assertEquals(QUELLE_KEINE_VERBINDUNG, zeilen[0].quelle)
        assertTrue(zeilen[0].ereignis)
        assertEquals("Sirene", zeilen[0].klassifikation)
    }

    @Test
    fun leererZeitraumLiefertKeineZeilen() {
        val zeilen =
            PegelAggregator.aggregiere(
                samples = emptyList(),
                ereignisse = emptyList(),
                von = t0,
                bis = t0,
                fensterDauer = Duration.ofSeconds(10),
            )
        assertEquals(0, zeilen.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeFensterDauerWirdAbgelehnt() {
        PegelAggregator.aggregiere(
            samples = emptyList(),
            ereignisse = emptyList(),
            von = t0,
            bis = t0.plusSeconds(10),
            fensterDauer = Duration.ofSeconds(-1),
        )
    }

    /**
     * Nachbesserung 24.09.2026 (Review-Befund zu #195), Fall 1 von 2 - der DATENGETRIEBENE
     * Rasterbeginn bindet (die ersten Werte setzen deutlich nach [von] ein). Siehe [aggregiere]s
     * eigenen KDoc fuer die volle Herleitung inkl. zweier Zwischenfassungen, die dies allein noch
     * nicht ausreichend behoben. Hier bewusst NICHT rasteraligniert (`von` = 2000ms,
     * `fensterDauer` = 7000ms, `2000 % 7000 != 0`) und eine Fensterdauer, die 3600s nicht glatt
     * teilt (7s) - dieselbe Kombination wie im vormaligen Fallback in
     * `DriveSyncCoordinator.aggregiereInAbschnitten()`.
     *
     * Von Hand nachgerechnet (VON-RELATIVES Raster, `von + k*fensterMillis` fuer `k=0,1,2,...`,
     * also 2000/9000/16000/23000/30000ms): minTs=10000ms liegt in `[9000,16000)` (`k=1`),
     * maxTs=24000ms liegt in `[23000,30000)` (`k=3`) -> effektiverStartMillis=9000,
     * effektivesEndeMillis=minOf(30000, 2000+(3+1)*7000)=30000 -> drei Fenster bei
     * 9000/16000/23000ms. Sample A (10000ms) gehoert damit in Fenster 9000, Sample C (24000ms) in
     * Fenster 23000, Fenster 16000 ist eine echte Luecke.
     */
    @Test
    fun nichtRasterausgerichtetesVonBeiDatengetriebenemFensterbeginnGruppiertKorrekt() {
        val von = Instant.ofEpochMilli(2_000)
        val bis = Instant.ofEpochMilli(30_000)
        val sampleA = LevelSampleEntity(at = 10_000, levelDb = 60.0, source = LevelSource.PCE_323)
        val sampleC = LevelSampleEntity(at = 24_000, levelDb = 50.0, source = LevelSource.PCE_323)

        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sampleA, sampleC),
                ereignisse = emptyList(),
                von = von,
                bis = bis,
                fensterDauer = Duration.ofMillis(7000),
            )

        assertEquals("Es muessen genau drei Fenster entstehen (9000/16000/23000ms)", 3, zeilen.size)

        assertEquals(Instant.ofEpochMilli(9_000), zeilen[0].fensterStart)
        assertEquals("Sample A (10000ms) gehoert in das Fenster ab 9000ms", LevelSource.PCE_323, zeilen[0].quelle)
        assertEquals(60.0, zeilen[0].laeqDb!!, 0.0001)
        assertEquals(1, zeilen[0].samples)

        assertEquals(Instant.ofEpochMilli(16_000), zeilen[1].fensterStart)
        assertEquals("Das Fenster ab 16000ms enthaelt tatsaechlich keinen der beiden Werte", QUELLE_KEINE_VERBINDUNG, zeilen[1].quelle)
        assertNull(zeilen[1].laeqDb)
        assertEquals(0, zeilen[1].samples)

        assertEquals(Instant.ofEpochMilli(23_000), zeilen[2].fensterStart)
        assertEquals("Sample C (24000ms) darf nicht aus der Ausgabe verschwinden", LevelSource.PCE_323, zeilen[2].quelle)
        assertEquals(50.0, zeilen[2].laeqDb!!, 0.0001)
        assertEquals(1, zeilen[2].samples)
    }

    /**
     * Nachbesserung 24.09.2026 (Review-Befund zu #195), Fall 2 von 2 - [von] SELBST bindet (dicht
     * gefuellte Daten setzen direkt ab [von] ein, wie an fast jeder Abschnittsgrenze in
     * `DriveSyncCoordinator.aggregiereInAbschnitten()` bei nicht rasteraligniertem [von]): DAS ist
     * der Fall, den eine ERSTE Zwischenfassung dieser Nachbesserung (Gruppierung auf dem
     * absoluten Epoch-Raster statt von-relativ) NICHT abdeckte - aufgefallen erst ueber
     * `DriveSyncCoordinatorTest.stueckweiseAggregationLiefertDasselbeErgebnisWieEinAufrufUeberDenGanzenTag`,
     * das mit jener Zwischenfassung fuer 20.000 dichte Zufallswerte bei Fensterdauer 7s
     * fehlschlug (laengeres CSV als der Vergleichs-Gesamtaufruf - Werte VOR einer
     * Abschnittsgrenze wurden dem FALSCHEN, spaeteren Fenster zugeordnet statt richtig
     * einsortiert). Siehe [aggregiere]s eigenen KDoc fuer die volle Herleitung.
     *
     * Von Hand nachgerechnet (VON-RELATIVES Raster `von + k*fensterMillis`, also
     * 3000/10000/17000/24000ms): `von`=3000ms (NICHT rasteraligniert, `3000 % 7000 != 0`),
     * `fensterDauer`=7000ms, Samples X(3000ms), Y(9500ms), Z(17000ms). minTs=3000ms liegt in
     * `[3000,10000)` (`k=0`, [von] SELBST gewinnt, da minTs bereits im ERSTEN von-relativen
     * Fenster liegt - deshalb "Fall 2"), maxTs=17000ms liegt in `[17000,24000)` (`k=2`) ->
     * effektiverStartMillis=3000, effektivesEndeMillis=minOf(25000, 3000+(2+1)*7000)=24000 ->
     * drei Fenster bei 3000/10000/17000ms. Fenster 0 ist [3000,10000) und muss deshalb BEIDE X
     * (3000ms) UND Y (9500ms) enthalten (samples=2) - mit der auf dem absoluten Epoch-Raster
     * gruppierenden Zwischenfassung waere Y faelschlich dem naechsten Fenster (10000ms)
     * zugeordnet worden (9500.floorDiv(7000)=1, nicht 0), wo es nicht hingehoert (9500 < 10000).
     */
    @Test
    fun nichtRasterausgerichtetesVonAlsBindenderFensterbeginnGruppiertKorrekt() {
        val von = Instant.ofEpochMilli(3_000)
        val bis = Instant.ofEpochMilli(25_000)
        val sampleX = LevelSampleEntity(at = 3_000, levelDb = 40.0, source = LevelSource.PCE_323)
        val sampleY = LevelSampleEntity(at = 9_500, levelDb = 44.0, source = LevelSource.PCE_323)
        val sampleZ = LevelSampleEntity(at = 17_000, levelDb = 48.0, source = LevelSource.PCE_323)

        val zeilen =
            PegelAggregator.aggregiere(
                samples = listOf(sampleX, sampleY, sampleZ),
                ereignisse = emptyList(),
                von = von,
                bis = bis,
                fensterDauer = Duration.ofMillis(7000),
            )

        assertEquals("Es muessen genau drei Fenster entstehen (3000/10000/17000ms)", 3, zeilen.size)

        assertEquals(Instant.ofEpochMilli(3_000), zeilen[0].fensterStart)
        assertEquals(
            "Fenster [3000,10000) muss BEIDE Samples X (3000ms) und Y (9500ms) enthalten, nicht nur X",
            2,
            zeilen[0].samples,
        )
        assertEquals(44.0, zeilen[0].lafMaxDb!!, 0.0001)
        assertEquals(40.0, zeilen[0].lafMinDb!!, 0.0001)
        assertEquals(LevelSource.PCE_323, zeilen[0].quelle)

        assertEquals(Instant.ofEpochMilli(10_000), zeilen[1].fensterStart)
        assertEquals(
            "Fenster [10000,17000) enthaelt tatsaechlich keinen Wert - Y (9500ms) gehoert NICHT hierher",
            QUELLE_KEINE_VERBINDUNG,
            zeilen[1].quelle,
        )
        assertEquals(0, zeilen[1].samples)

        assertEquals(Instant.ofEpochMilli(17_000), zeilen[2].fensterStart)
        assertEquals(1, zeilen[2].samples)
        assertEquals(48.0, zeilen[2].laeqDb!!, 0.0001)
        assertEquals(LevelSource.PCE_323, zeilen[2].quelle)
    }
}
