package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten

/**
 * KI-Umbau Etappe 2.3: bildet pro Frame einen Baulärm-Gruppen-Score aus den quantisierten
 * Frame-Scores der [BAULAERM_KLASSEN] - noisy-OR statt Summierung, weil eine Summe > 1 werden
 * kann und die Gewichtung zwischen den Gruppen (Kern/Kontext/Impuls) verzerren würde:
 *
 * `gruppenScore(frame) = 1 - Π_i (1 - gewicht_i * score_i)`
 *
 * Liest ausschließlich aus [KlassifikationsRohdaten.frameScores] - unabhängig davon, welche
 * Kandidaten frueher (Etappe 1, ueber `aiConfidenceThreshold`) in `topKlassen` gelandet sind.
 * Das ist entscheidend: `frameScores` enthaelt die UNGEFILTERTEN Rohwerte fuer die getrackten
 * Klassen, `topKlassen` dagegen nur das, was ueber der (mit Etappe 2.6 abgeschafften)
 * Einheitsschwelle lag. Ein Frame mit einem Baulärm-Score knapp unter der alten 30%-Schwelle
 * waere in Etappe 1 unsichtbar gewesen - hier fließt er korrekt ein.
 *
 * Reine Funktion (Arbeitsweise-Regel 3) - keine TFLite-/Android-/DB-Abhängigkeit.
 */
fun berechneGruppenScores(rohdaten: KlassifikationsRohdaten): FloatArray {
    if (rohdaten.frameAnzahl <= 0 || rohdaten.klassenIndizes.isEmpty()) return FloatArray(0)

    val positionFuerIndex = HashMap<Int, Int>(rohdaten.klassenIndizes.size)
    rohdaten.klassenIndizes.forEachIndexed { position, index -> positionFuerIndex[index] = position }

    // Nur die Klassen beruecksichtigen, die tatsaechlich in dieser Aufnahme mitgespeichert
    // wurden - schuetzt vor einem zukuenftigen Formatwechsel von ROHDATEN_KLASSEN_INDIZES, ohne
    // dass diese Funktion angepasst werden muesste.
    val relevanteKlassen = BAULAERM_KLASSEN.mapNotNull { klasse ->
        positionFuerIndex[klasse.index]?.let { position -> position to klasse.gewicht }
    }

    val breite = rohdaten.klassenIndizes.size
    return FloatArray(rohdaten.frameAnzahl) { frame ->
        var produkt = 1.0
        for ((position, gewicht) in relevanteKlassen) {
            produkt *= (1.0 - gewicht * quantisierterScore(rohdaten.frameScores, frame * breite + position))
        }
        (1.0 - produkt).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * `frameScores` speichert `round(score * 255)` in einem VORZEICHENBEHAFTETEN Byte (Etappe 1.4).
 * Werte ab 128 werden dabei als negative Byte-Werte abgelegt (z.B. 255 -> -1) - ohne die
 * `and 0xFF`-Maskierung wuerde ein hoher Score faelschlich als (Betrags-)hohe, aber NEGATIVE
 * Zahl interpretiert und den Gruppen-Score systematisch verfaelschen.
 */
internal fun quantisierterScore(frameScores: ByteArray, position: Int): Float {
    val byteWert = frameScores.getOrElse(position) { 0 }
    return (byteWert.toInt() and 0xFF) / 255f
}
