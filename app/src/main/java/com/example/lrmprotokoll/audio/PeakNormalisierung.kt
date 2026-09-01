package com.example.lrmprotokoll.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KI-Umbau Etappe 1.6: Aufnahmen durch geschlossene Fenster/aus groesserer Entfernung sind
 * leise - ein leises Signal drueckt ALLE YAMNet-Scores nach unten (die Konfidenzschwelle
 * greift dann faelschlich, obwohl das Geraeusch eindeutig erkennbar waere). Skaliert
 * [samples] so, dass der lauteste Abtastwert genau [zielPeak] * [Short.MAX_VALUE] erreicht -
 * echte Peak-Normalisierung, skaliert also sowohl leise Clips hoch als auch (selten) bereits
 * lautere Clips auf den Zielpegel herunter.
 *
 * Reine Funktion, keine Seiteneffekte - nur der an den Klassifikator uebergebene Puffer wird
 * normalisiert, siehe Aufrufstelle in [NoiseClassifier]. Die gespeicherte WAV-Datei bleibt
 * unangetastet: sie ist Beweismittel, der massgebliche Pegel fuer die Beweisfuehrung kommt
 * ohnehin vom PCE-323-Messgeraet, nicht vom (unkalibrierten) Mikrofonsignal.
 *
 * Stille (`maxAbs == 0`) bleibt unveraendert - eine Division durch 0 waere hier keine
 * Normalisierung, sondern ein Rauschverstaerker auf digitale Null.
 */
internal fun normalisierePeak(samples: ShortArray, zielPeak: Float = 0.95f): ShortArray {
    if (samples.isEmpty()) return samples

    var maxAbs = 0
    for (sample in samples) {
        val betrag = abs(sample.toInt())
        if (betrag > maxAbs) maxAbs = betrag
    }
    if (maxAbs == 0) return samples

    val faktor = (zielPeak * Short.MAX_VALUE) / maxAbs
    return ShortArray(samples.size) { i ->
        (samples[i] * faktor).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}
