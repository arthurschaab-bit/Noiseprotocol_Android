package com.example.lrmprotokoll.audio

/**
 * KI-Umbau Etappe 2.4: leitet aus der Gruppen-Score-Zeitreihe ([berechneGruppenScores]) die
 * eigentlichen Baulärm-Kennzahlen ab - Median-Glättung gegen Frame-zu-Frame-Flackern, Hysterese
 * gegen zerhackte Blöcke, danach Blockerkennung fuer Dauer/Anzahl.
 *
 * Alle Funktionen hier sind reine Funktionen (Arbeitsweise-Regel 3) ueber `FloatArray`/
 * `BooleanArray` - keine TFLite-/Android-/DB-Abhaengigkeit.
 */

/**
 * Median ueber ein zentriertes Fenster von [fenster] Frames (muss ungerade sein). Am Rand wird
 * das Fenster verkuerzt statt mit Fantasiewerten aufgefuellt - ein Clip-Anfang/-Ende bekommt so
 * keine kuenstlich geglaetteten Randwerte.
 */
internal fun medianGlaettung(scores: FloatArray, fenster: Int = 3): FloatArray {
    require(fenster % 2 == 1) { "Fenster muss ungerade sein, war $fenster" }
    if (scores.isEmpty()) return FloatArray(0)
    val radius = fenster / 2
    return FloatArray(scores.size) { i ->
        val von = (i - radius).coerceAtLeast(0)
        val bis = (i + radius).coerceAtMost(scores.size - 1)
        val fensterWerte = scores.copyOfRange(von, bis + 1)
        fensterWerte.sort()
        fensterWerte[fensterWerte.size / 2]
    }
}

/**
 * Hysterese-Schwellung: Einstieg erst bei [einSchwelle], Ausstieg erst bei [ausSchwelle]
 * (< [einSchwelle]). Verhindert, dass ein Score, der knapp um eine einzelne Schwelle pendelt,
 * einen zusammenhaengenden Laermblock in viele Kurz-Fragmente zerhackt.
 */
internal fun hysterese(scores: FloatArray, einSchwelle: Float, ausSchwelle: Float): BooleanArray {
    val ueberSchwelle = BooleanArray(scores.size)
    var aktiv = false
    for (i in scores.indices) {
        aktiv = if (aktiv) scores[i] >= ausSchwelle else scores[i] >= einSchwelle
        ueberSchwelle[i] = aktiv
    }
    return ueberSchwelle
}

/** Ein zusammenhaengender Bereich von Frames ueber der Hysterese-Schwelle (inklusive Grenzen). */
internal data class Block(val vonFrame: Int, val bisFrame: Int) {
    val frameAnzahl: Int get() = bisFrame - vonFrame + 1
}

internal fun ermittleBloecke(ueberSchwelle: BooleanArray): List<Block> {
    val bloecke = mutableListOf<Block>()
    var start = -1
    for (i in ueberSchwelle.indices) {
        if (ueberSchwelle[i]) {
            if (start < 0) start = i
        } else if (start >= 0) {
            bloecke.add(Block(start, i - 1))
            start = -1
        }
    }
    if (start >= 0) bloecke.add(Block(start, ueberSchwelle.lastIndex))
    return bloecke
}
