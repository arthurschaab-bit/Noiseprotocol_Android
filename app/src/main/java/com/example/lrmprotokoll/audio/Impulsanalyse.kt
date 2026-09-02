package com.example.lrmprotokoll.audio

import kotlin.math.sqrt

/**
 * KI-Umbau Etappe 3.4: physikalische Merkmale einer Aufnahme, UNABHAENGIG von YAMNet - eine
 * zweite Meinung ueber reine Signalverarbeitung. Hämmern und Presslufthammer sind impulshaft
 * (hohe Kurtosis, hoher Crest-Faktor der Hüllkurve) und oft periodisch (dominante Wiederholrate
 * in einem plausiblen Bereich); Verkehr, Lüftung und Regen sind es nicht.
 *
 * [peakSchaerfe] ist eine eigene, im Auftrag nicht konkretisierte Ergänzung (dort nur als
 * Feldname genannt): das Verhältnis des stärksten Autokorrelations-Peaks zum Durchschnitt der
 * durchsuchten Autokorrelationswerte - ein Maß dafür, wie verlässlich [wiederholrateHz] ist.
 * Weißes Rauschen hat keinen herausragenden Peak (Wert nahe 1), ein sauberer Impulszug einen
 * deutlich über dem Durchschnitt liegenden.
 *
 * [mittlererPegel] ist die mittlere Hüllkurven-Amplitude (0..~1, relativ zur Short-Aussteuerung)
 * - der in Etappe 3.5 geforderte "relative RMS"-Ersatzwert für die Pegel-Bedingung der Fusion,
 * wenn kein kalibrierter PCE-323-Messwert für den Aufnahmezeitraum vorliegt.
 */
data class ImpulsMerkmale(
    val crest: Float,
    val kurtosis: Float,
    val wiederholrateHz: Float,
    val peakSchaerfe: Float,
    val mittlererPegel: Float,
)

internal const val HUELLKURVE_FENSTER_MS = 10
private val WIEDERHOLRATE_BEREICH_HZ = 0.5f..80f

/**
 * RMS-Hüllkurve in nicht überlappenden [fensterMs]-Fenstern - reduziert den Sample-Puffer auf
 * eine grobe Energie-Zeitreihe, auf der Impulshaftigkeit/Periodizität überhaupt erst sichtbar
 * wird (ein einzelnes Sample sagt dazu nichts). `internal` statt `private`, damit die
 * Zwischenstufe unabhängig testbar bleibt (Arbeitsweise-Regel 3).
 */
internal fun berechneHuellkurve(samples: ShortArray, sampleRateHz: Int, fensterMs: Int = HUELLKURVE_FENSTER_MS): FloatArray {
    if (samples.isEmpty() || sampleRateHz <= 0) return FloatArray(0)
    val fensterGroesse = (sampleRateHz.toLong() * fensterMs / 1000).toInt().coerceAtLeast(1)
    val anzahlFenster = samples.size / fensterGroesse
    if (anzahlFenster == 0) return FloatArray(0)

    return FloatArray(anzahlFenster) { fenster ->
        var summeQuadrate = 0.0
        val start = fenster * fensterGroesse
        for (i in start until start + fensterGroesse) {
            val normalisiert = samples[i] / 32768.0
            summeQuadrate += normalisiert * normalisiert
        }
        sqrt(summeQuadrate / fensterGroesse).toFloat()
    }
}

/** Crest-Faktor der Hüllkurve: Spitze geteilt durch Effektivwert - hoch bei kurzen Ausschlägen vor ruhigem Hintergrund. */
internal fun berechneCrestFaktor(huellkurve: FloatArray): Float {
    if (huellkurve.isEmpty()) return 0f
    val rms = sqrt(huellkurve.sumOf { (it * it).toDouble() } / huellkurve.size).toFloat()
    if (rms <= 0f) return 0f
    return huellkurve.max() / rms
}

/**
 * Exzess-Kurtosis der Hüllkurve (Normalverteilung = 0) - hoch bei wenigen Spitzen vor flachem
 * Rest, wie bei Hammerschlägen; niedrig bei gleichmäßiger Energie, wie bei einem Dauerton.
 */
internal fun berechneKurtosis(huellkurve: FloatArray): Float {
    if (huellkurve.size < 4) return 0f
    val mittelwert = huellkurve.average()
    val abweichungen = DoubleArray(huellkurve.size) { huellkurve[it] - mittelwert }
    val varianz = abweichungen.sumOf { it * it } / huellkurve.size
    // Eine (nahezu) konstante Huellkurve macht Kurtosis nicht sinnvoll definierbar - ohne diese
    // Absicherung wuerde die Division durch varianz^2 NaN/Infinity liefern statt eines ehrlichen
    // "keine Aussage" (Robustheitsgebot: darf die Klassifizierung nie zum Absturz bringen).
    if (varianz <= 1e-12) return 0f
    val viertesMoment = abweichungen.sumOf { it * it * it * it } / huellkurve.size
    return (viertesMoment / (varianz * varianz) - 3.0).toFloat()
}

/**
 * Dominante Wiederholrate über die Autokorrelation der Hüllkurve - der stärkste Peak (außerhalb
 * von Lag 0) im plausiblen Bereich [WIEDERHOLRATE_BEREICH_HZ]. Liefert (Rate in Hz, Peakschärfe).
 */
internal fun berechneWiederholrate(huellkurve: FloatArray, huellkurveHz: Float): Pair<Float, Float> {
    if (huellkurve.size < 4) return 0f to 0f
    val minLag = (huellkurveHz / WIEDERHOLRATE_BEREICH_HZ.endInclusive).toInt().coerceAtLeast(1)
    val maxLag = (huellkurveHz / WIEDERHOLRATE_BEREICH_HZ.start).toInt().coerceAtMost(huellkurve.size - 1)
    if (minLag > maxLag) return 0f to 0f

    val mittelwert = huellkurve.average().toFloat()
    val zentriert = FloatArray(huellkurve.size) { huellkurve[it] - mittelwert }
    val normierung = zentriert.sumOf { (it * it).toDouble() }
    if (normierung <= 1e-12) return 0f to 0f

    var bestLag = -1
    var bestWert = Float.NEGATIVE_INFINITY
    var summeBetraege = 0.0
    var anzahlWerte = 0
    for (lag in minLag..maxLag) {
        var summe = 0.0
        for (i in 0 until zentriert.size - lag) {
            summe += zentriert[i] * zentriert[i + lag]
        }
        val wert = (summe / normierung).toFloat()
        // Betrag statt Signalwert: die durchsuchten Lags schwanken um einen Wert nahe 0 (mal
        // positiv, mal negativ), ein vorzeichenbehafteter Durchschnitt waere kein brauchbares
        // Mass fuer die "typische Hintergrund-Staerke", gegen die der Peak abgesetzt sein muss.
        summeBetraege += kotlin.math.abs(wert)
        anzahlWerte++
        if (wert > bestWert) {
            bestWert = wert
            bestLag = lag
        }
    }
    if (bestLag <= 0 || anzahlWerte == 0) return 0f to 0f
    val durchschnittBetrag = (summeBetraege / anzahlWerte).toFloat()
    val peakSchaerfe = if (durchschnittBetrag > 1e-6f) bestWert / durchschnittBetrag else bestWert
    return (huellkurveHz / bestLag) to peakSchaerfe
}

/**
 * KI-Umbau Etappe 3.4: reine Funktion (Arbeitsweise-Regel 3) - liest denselben Sample-Puffer,
 * der ohnehin schon für die YAMNet-Inferenz gelesen wird ([NoiseClassifier.leseUndKlassifiziere]),
 * kein zweiter Datei-/Inferenzzugriff nötig.
 */
fun berechneImpulsMerkmale(samples: ShortArray, sampleRateHz: Int): ImpulsMerkmale {
    val huellkurve = berechneHuellkurve(samples, sampleRateHz)
    if (huellkurve.isEmpty()) return ImpulsMerkmale(0f, 0f, 0f, 0f, 0f)

    val huellkurveHz = 1000f / HUELLKURVE_FENSTER_MS
    val (rateHz, peakSchaerfe) = berechneWiederholrate(huellkurve, huellkurveHz)
    return ImpulsMerkmale(
        crest = berechneCrestFaktor(huellkurve),
        kurtosis = berechneKurtosis(huellkurve),
        wiederholrateHz = rateHz,
        peakSchaerfe = peakSchaerfe,
        mittlererPegel = huellkurve.average().toFloat(),
    )
}
