package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import kotlin.math.roundToInt

/**
 * KI-Umbau Etappe 2.5: Einstufung, wie sicher diese Aufnahme Baulärm enthält. `UNKLAR` ist
 * bewusst ein eigener Wert statt `null` (Etappe 2.7 / Auftrag): ein leeres Feld waere im
 * Beweiskontext mehrdeutig - es koennte "noch nicht klassifiziert" oder "nichts erkannt"
 * bedeuten. Diese beiden Zustaende muessen unterscheidbar bleiben, `UNKLAR` steht fuer
 * "klassifiziert, aber keine verlaessliche Aussage moeglich" (z.B. keine auswertbaren Frames).
 */
enum class Einstufung { BAULAERM, MOEGLICH, KEIN_BAULAERM, UNKLAR }

/**
 * KI-Umbau Etappe 2.5: das neue Ergebnisobjekt von [leiteLabelAb] - ersetzt das schlichte
 * `Befund(label: String)` aus Etappe 1.5 durch eine Aussage ueber Anwesenheit UND Dauer von
 * Baulärm, nicht mehr nur ein einzelnes Label.
 *
 * [gelernteQuelle] ist eine Erweiterung ueber die im Auftrag Abschnitt 2.5 woertlich genannten
 * Felder hinaus: haelt das Ergebnis des (aus Etappe 1 unveraendert uebernommenen)
 * Referenzmuster-Abgleichs fest ("Gelernt: <Name> (<Prozent>%)", ohne Praefix). Begruendung:
 * Abschnitt 3.3 des Auftrags haelt fest, dass ein Referenz-Treffer den Gruppen-Score schlagen
 * soll - das war in Etappe 1 bereits so implementiert und wird hier unveraendert fortgefuehrt,
 * nicht neu entschieden. Die uebrigen Felder (anteil/laengsterBlockSekunden/...) werden auch bei
 * einem Referenz-Treffer aus den echten Rohdaten berechnet, nicht durch Platzhalter ersetzt -
 * sie bleiben so fuer die Tagessummenzeile (Etappe 2.7) nutzbar, unabhaengig vom Anzeigetext.
 *
 * [gesamtBaulaermSekunden] ist ebenfalls eine Erweiterung ueber Abschnitt 2.5 hinaus: die Summe
 * ALLER (nicht nur des laengsten) ueber der Hysterese-Schwelle liegenden Frames in Sekunden -
 * ohne dieses Feld liesse sich die in Etappe 2.7 geforderte Tagessummenzeile ("wie viele Minuten
 * des Tages als Baulärm eingestuft sind") nicht korrekt aus mehreren Bloecken pro Aufnahme
 * berechnen.
 */
data class BaulaermBefund(
    val anteil: Float,
    val laengsterBlockSekunden: Float,
    val blockAnzahl: Int,
    val spitzenKlasse: String?,
    val spitzenScore: Float,
    val einstufung: Einstufung,
    val gelernteQuelle: String? = null,
    val gesamtBaulaermSekunden: Float = 0f,
)

/**
 * KI-Umbau Etappe 2.4/2.6: alle Schwellen der Zeitaggregation an einem Ort, mit den im Auftrag
 * genannten Standardwerten fuer die Frame-Hysterese. [anteilFuerBaulaerm] und
 * [minimalerScoreFuerMoeglich] sind eigene, im Auftrag nicht konkret vorgegebene Ergaenzungen
 * fuer die Einstufung auf Clip-Ebene (BAULAERM/MOEGLICH/KEIN_BAULAERM) - der Auftrag liefert das
 * dafuer vorgesehene Werkzeug selbst: den "manuellen Gegentest auf mindestens 10 bekannten
 * Aufnahmen" (Akzeptanzkriterien Etappe 2). Diese zwei Werte sind bewusst NICHT an ein
 * UI-Element gebunden (anders als [einSchwelle]/[ausSchwelle]) und muessen nach dem Gegentest
 * ggf. im Code angepasst werden.
 */
data class BaulaermKonfiguration(
    val einSchwelle: Float = 0.50f,
    val ausSchwelle: Float = 0.35f,
    val glaettungsFenster: Int = 3,
    val anteilFuerBaulaerm: Float = 0.15f,
    val minimalerScoreFuerMoeglich: Float = 0.20f,
)

/**
 * KI-Umbau Etappe 2: der eigentliche Qualitätssprung - Gruppen-Score ([berechneGruppenScores])
 * plus Zeitaggregation ([medianGlaettung], [hysterese], [ermittleBloecke]) zu einem
 * [BaulaermBefund]. Reine Funktion (Arbeitsweise-Regel 3) ueber [rohdaten] und [konfiguration].
 */
internal fun leiteBaulaermBefundAb(
    rohdaten: KlassifikationsRohdaten,
    konfiguration: BaulaermKonfiguration,
): BaulaermBefund {
    val rohScores = berechneGruppenScores(rohdaten)
    if (rohScores.isEmpty()) {
        return BaulaermBefund(0f, 0f, 0, null, 0f, Einstufung.UNKLAR)
    }

    val geglaettet = medianGlaettung(rohScores, konfiguration.glaettungsFenster)
    val ueberSchwelle = hysterese(geglaettet, konfiguration.einSchwelle, konfiguration.ausSchwelle)
    val bloecke = ermittleBloecke(ueberSchwelle)

    val hopSekunden = rohdaten.frameHopMs / 1000f
    val anteil = ueberSchwelle.count { it }.toFloat() / ueberSchwelle.size
    val laengsterBlockFrames = bloecke.maxOfOrNull { it.frameAnzahl } ?: 0
    val staerksterBlock = bloecke.maxByOrNull { block -> (block.vonFrame..block.bisFrame).maxOf { geglaettet[it] } }
    val spitze = staerksterBlock?.let { spitzenklasseInBereich(rohdaten, it.vonFrame, it.bisFrame) }

    val maxRohScore = rohScores.max()
    val einstufung = when {
        anteil >= konfiguration.anteilFuerBaulaerm -> Einstufung.BAULAERM
        maxRohScore >= konfiguration.minimalerScoreFuerMoeglich -> Einstufung.MOEGLICH
        else -> Einstufung.KEIN_BAULAERM
    }

    return BaulaermBefund(
        anteil = anteil,
        laengsterBlockSekunden = laengsterBlockFrames * hopSekunden,
        blockAnzahl = bloecke.size,
        spitzenKlasse = spitze?.first,
        spitzenScore = spitze?.second ?: 0f,
        einstufung = einstufung,
        gesamtBaulaermSekunden = ueberSchwelle.count { it } * hopSekunden,
    )
}

/**
 * Die staerkste EINZELNE Klasse (nicht der Gruppen-Score) innerhalb eines Frame-Bereichs -
 * "die staerkste Einzelklasse im staerksten Block" (Auftrag 2.4). Liefert den rohen,
 * unuebersetzten YAMNet-Klassennamen zurueck - die Eindeutschung passiert erst beim Formatieren
 * ([formatiereBaulaermBefund]), damit dieser Befund selbst frei von Anzeige-/Uebersetzungslogik
 * bleibt.
 */
internal fun spitzenklasseInBereich(rohdaten: KlassifikationsRohdaten, vonFrame: Int, bisFrame: Int): Pair<String, Float>? {
    val positionFuerIndex = HashMap<Int, Int>(rohdaten.klassenIndizes.size)
    rohdaten.klassenIndizes.forEachIndexed { position, index -> positionFuerIndex[index] = position }
    val breite = rohdaten.klassenIndizes.size

    var besteKlasse: String? = null
    var besterScore = -1f
    for (klasse in BAULAERM_KLASSEN) {
        val position = positionFuerIndex[klasse.index] ?: continue
        for (frame in vonFrame..bisFrame) {
            val score = quantisierterScore(rohdaten.frameScores, frame * breite + position)
            if (score > besterScore) {
                besterScore = score
                besteKlasse = klasse.name
            }
        }
    }
    return besteKlasse?.let { it to besterScore }
}

/**
 * KI-Umbau Etappe 2.7: die im Auftrag als Beispiel genannte Anzeigeform -
 * "Baulärm · 68 % der Aufnahme · längster Block 4:12 · Spitze: Hämmern". Reine Funktion, damit
 * sie ohne Geraet testbar bleibt; [labelMapping] wird nur fuer die Eindeutschung der
 * Spitzenklasse gebraucht, exakt dieselbe Tabelle wie fuer den bisherigen Top-Label-Pfad.
 */
fun formatiereBaulaermBefund(befund: BaulaermBefund, labelMapping: Map<String, String>): String {
    befund.gelernteQuelle?.let { return "Gelernt: $it" }

    return when (befund.einstufung) {
        Einstufung.UNKLAR -> "Unklar"
        Einstufung.KEIN_BAULAERM -> "Kein Baulärm erkannt"
        Einstufung.MOEGLICH, Einstufung.BAULAERM -> buildString {
            append(if (befund.einstufung == Einstufung.MOEGLICH) "Möglicher Baulärm" else "Baulärm")
            append(" · ")
            append((befund.anteil * 100).roundToInt())
            append("% der Aufnahme")
            if (befund.blockAnzahl > 0) {
                append(" · längster Block ")
                append(formatiereDauer(befund.laengsterBlockSekunden))
            }
            befund.spitzenKlasse?.let { klasse ->
                append(" · Spitze: ")
                append(labelMapping[klasse] ?: klasse)
            }
        }
    }
}

internal fun formatiereDauer(sekunden: Float): String {
    val gesamtSekunden = sekunden.toInt().coerceAtLeast(0)
    val minuten = gesamtSekunden / 60
    val rest = gesamtSekunden % 60
    return "%d:%02d".format(minuten, rest)
}
