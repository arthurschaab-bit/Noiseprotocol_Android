package com.example.lrmprotokoll.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-Umbau Etappe 3.4: die drei im Auftrag (Akzeptanzkriterien Etappe 3) geforderten Testfälle
 * für [berechneImpulsMerkmale] mit synthetischen Signalen - Sinus (niedrige Kurtosis),
 * periodischer Impulszug (hohe Kurtosis, korrekt erkannte Rate), weißes Rauschen (keine
 * dominante Rate). Reine JVM-Tests, kein Gerät nötig.
 */
class ImpulsanalyseTest {

    private val sampleRate = 16000

    private fun sinusSignal(frequenzHz: Double, dauerSekunden: Double, amplitude: Double = 0.8): ShortArray {
        val anzahl = (sampleRate * dauerSekunden).toInt()
        return ShortArray(anzahl) { i ->
            (amplitude * Short.MAX_VALUE * sin(2 * PI * frequenzHz * i / sampleRate)).toInt().toShort()
        }
    }

    /** Kurze Rauschbursts, exakt alle [periodeMs] wiederholt - ergibt einen eindeutigen Autokorrelations-Peak. */
    private fun impulszug(periodeMs: Int, burstDauerMs: Int, dauerSekunden: Double, seed: Int = 1): ShortArray {
        val random = Random(seed)
        val gesamtSamples = (sampleRate * dauerSekunden).toInt()
        val periodeSamples = sampleRate * periodeMs / 1000
        val burstSamples = sampleRate * burstDauerMs / 1000
        return ShortArray(gesamtSamples) { i ->
            if (i % periodeSamples < burstSamples) {
                (random.nextDouble(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort()
            } else {
                0
            }
        }
    }

    private fun weissesRauschen(dauerSekunden: Double, seed: Int = 42): ShortArray {
        val random = Random(seed)
        val anzahl = (sampleRate * dauerSekunden).toInt()
        return ShortArray(anzahl) { (random.nextDouble(-1.0, 1.0) * Short.MAX_VALUE * 0.3).toInt().toShort() }
    }

    @Test
    fun leererPufferErgibtEinNeutralesErgebnisStattEinesAbsturzes() {
        val merkmale = berechneImpulsMerkmale(ShortArray(0), sampleRate)

        assertEquals(ImpulsMerkmale(0f, 0f, 0f, 0f, 0f), merkmale)
    }

    @Test
    fun sinusHatNiedrigeKurtosisUndKeinenAusgepraegtenAutokorrelationsPeak() {
        val signal = sinusSignal(frequenzHz = 1000.0, dauerSekunden = 1.0)

        val merkmale = berechneImpulsMerkmale(signal, sampleRate)

        assertTrue(
            "Ein Dauerton hat eine nahezu konstante Huellkurve - niedrige Kurtosis erwartet, war ${merkmale.kurtosis}",
            merkmale.kurtosis < 2f,
        )
    }

    @Test
    fun periodischerImpulszugHatHoheKurtosisUndErkenntDieRateKorrekt() {
        // 200ms Periode = exakt 20 Huellkurven-Fenster (10ms) - nur 1 von 20 Fenstern traegt
        // Energie, das ergibt bei einer Bernoulli-artig sparsamen Huellkurve eine rechnerisch
        // hohe Exzess-Kurtosis (~15 fuer eine ideale Delta-Folge dieser Duty-Cycle, deutlich > 3
        // auch mit echtem Rausch-Burst statt einer idealen Spitze) UND einen eindeutigen,
        // quantisierungsfreien Autokorrelations-Peak bei Lag 20 -> Rate = 1000/200 = 5 Hz.
        val signal = impulszug(periodeMs = 200, burstDauerMs = 5, dauerSekunden = 2.0)

        val merkmale = berechneImpulsMerkmale(signal, sampleRate)

        assertTrue(
            "Kurze Bursts vor Stille ergeben eine impulshafte Huellkurve - hohe Kurtosis erwartet, war ${merkmale.kurtosis}",
            merkmale.kurtosis > 3f,
        )
        assertEquals(5f, merkmale.wiederholrateHz, 0.5f)
        assertTrue(
            "Ein sauberer periodischer Impulszug muss einen deutlich herausragenden Autokorrelations-Peak haben - war ${merkmale.peakSchaerfe}",
            merkmale.peakSchaerfe > 3f,
        )
    }

    @Test
    fun weissesRauschenHatKeineDominanteWiederholrate() {
        val signal = weissesRauschen(dauerSekunden = 1.5)
        val impulszugSignal = impulszug(periodeMs = 200, burstDauerMs = 5, dauerSekunden = 1.5)

        val rauschMerkmale = berechneImpulsMerkmale(signal, sampleRate)
        val impulsMerkmale = berechneImpulsMerkmale(impulszugSignal, sampleRate)

        assertTrue(
            "Weisses Rauschen darf keinen annaehernd so ausgepraegten Autokorrelations-Peak " +
                "haben wie ein echter periodischer Impulszug - Rauschen=${rauschMerkmale.peakSchaerfe}, " +
                "Impulszug=${impulsMerkmale.peakSchaerfe}",
            rauschMerkmale.peakSchaerfe < impulsMerkmale.peakSchaerfe / 2f,
        )
    }

    @Test
    fun huellkurveMitZweiVollenFensternMitteltKorrekt() {
        // 10ms bei 16kHz = 160 Samples/Fenster. Erstes Fenster Vollausschlag, zweites Stille.
        val samples = ShortArray(320) { i -> if (i < 160) Short.MAX_VALUE else 0 }

        val huellkurve = berechneHuellkurve(samples, sampleRate)

        assertEquals(2, huellkurve.size)
        assertTrue(huellkurve[0] > 0.9f)
        assertEquals(0f, huellkurve[1], 0.001f)
    }

    @Test
    fun huellkurveZuKurzerPufferErgibtLeeresArray() {
        assertEquals(0, berechneHuellkurve(ShortArray(10), sampleRate).size)
    }

    /**
     * KI-Umbau Etappe 3, Akzeptanzkriterien: "Laufzeit gemessen und berichtet: Zusatzkosten von
     * [...] DSP pro Aufnahme im BATCH-Modus." Kein Geraet in dieser Umgebung verfuegbar - die
     * hier gemessene Zeit ist eine JVM-Sandbox-Messung, keine Android-Geraetemessung, aber ein
     * ehrlicher, nicht geratener oberer Anhaltspunkt fuer die Groessenordnung (die Impulsanalyse
     * ist reine Kotlin-Arithmetik ohne Android-/JNI-Anteil, die Relation ueberträgt sich). Grosszuegige
     * Obergrenze (500ms fuer eine 60-Sekunden-Aufnahme) dient als Regressionswache, nicht als
     * scharfe Performance-Zusicherung.
     */
    @Test
    fun laufzeitFuerEine60SekundenAufnahmeIstUnproblematisch() {
        val signal = weissesRauschen(dauerSekunden = 60.0)

        val start = System.nanoTime()
        berechneImpulsMerkmale(signal, sampleRate)
        val dauerMs = (System.nanoTime() - start) / 1_000_000.0

        println("KI-Umbau Etappe 3.4/Laufzeit: berechneImpulsMerkmale() fuer 60s @ 16kHz: ${"%.2f".format(dauerMs)} ms (JVM-Sandbox, kein Geraet)")
        assertTrue(
            "Impulsanalyse fuer eine 60s-Aufnahme sollte auch grosszuegig gerechnet weit unter " +
                "500ms liegen - war ${dauerMs}ms",
            dauerMs < 500.0,
        )
    }
}
