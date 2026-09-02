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
 *
 * [ueberImpulsRegelErkannt]/[impulsRateHz] (Etappe 3.5): gesetzt, wenn NICHT der Gruppen-Score,
 * sondern die von YAMNet unabhaengige Impuls-Regel (siehe [leiteBaulaermBefundAb]) den Ausschlag
 * fuer BAULAERM gegeben hat - "die Herkunft der Entscheidung [muss] in der UI erkennbar" sein
 * (Auftrag, Akzeptanzkriterien Etappe 3). [anteil]/[gesamtBaulaermSekunden] bleiben in diesem
 * Fall bewusst UNVERAENDERT (weiterhin nur, was der Gruppen-Score tatsaechlich gemessen hat) -
 * die Impuls-Regel aendert nur die Einstufung selbst, nicht die davon unabhaengigen Kennzahlen.
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
    val ueberImpulsRegelErkannt: Boolean = false,
    val impulsRateHz: Float? = null,
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
 *
 * [impulsKurtosisSchwelle]/[impulsRateBereichHz]/[impulsPegelSchwelleDbA]/
 * [impulsPegelSchwelleRelativ] (Etappe 3.5, "Fusion"): eigene Schwellen fuer die YAMNet-
 * unabhaengige Impuls-Regel `kurtosis > K UND wiederholrateHz in [5,30] UND pegel > X`. Der
 * Auftrag nennt den Rate-Bereich konkret (5-30 Hz - bewusst enger als der allgemeine
 * Detektionsbereich der Huellkurven-Analyse selbst, 0.5-80 Hz, um gezielt maschinelle
 * Taktraten wie Presslufthammer/Ruettelplatte zu treffen, nicht das langsamere manuelle
 * Haemmern), aber keinen konkreten Kurtosis- oder Pegelschwellenwert - auch diese sind
 * dokumentierte Startwerte, ueber den Gegentest nachjustierbar.
 */
data class BaulaermKonfiguration(
    val einSchwelle: Float = 0.50f,
    val ausSchwelle: Float = 0.35f,
    val glaettungsFenster: Int = 3,
    val anteilFuerBaulaerm: Float = 0.15f,
    val minimalerScoreFuerMoeglich: Float = 0.20f,
    val impulsKurtosisSchwelle: Float = 3.0f,
    val impulsRateBereichHz: ClosedFloatingPointRange<Float> = 5f..30f,
    val impulsPegelSchwelleDbA: Double = 55.0,
    val impulsPegelSchwelleRelativ: Float = 0.05f,
)

/**
 * KI-Umbau Etappe 2: der eigentliche Qualitätssprung - Gruppen-Score ([berechneGruppenScores])
 * plus Zeitaggregation ([medianGlaettung], [hysterese], [ermittleBloecke]) zu einem
 * [BaulaermBefund]. Reine Funktion (Arbeitsweise-Regel 3) ueber [rohdaten] und [konfiguration].
 *
 * KI-Umbau Etappe 3.5 ("Fusion"): [kalibrierterPegelDbA] ist der PCE-323-Messwert fuer den
 * Aufnahmezeitraum, falls vorhanden (`null` sonst - dann greift innerhalb der Impuls-Regel der
 * relative Hüllkurven-Pegel [KlassifikationsRohdaten.impulsMittlererPegel] als Ersatz, exakt wie
 * im Auftrag beschrieben: "Der Pegel kommt vom PCE-323, [...] sonst ersatzweise der relative
 * RMS"). Bewusst als eigener Parameter statt Teil von [rohdaten]: der kalibrierte Pegel gehoert
 * zum [com.example.lrmprotokoll.data.NoiseRecord], nicht zu den YAMNet-Rohdaten, und ist beim
 * Live-Klassifizieren (online/Batch) nicht ohne Weiteres verfuegbar - dort greift IMMER der
 * relative Ersatzwert. Bei "Neu bewerten" (mit Datenbankzugriff auf den vollstaendigen
 * [com.example.lrmprotokoll.data.NoiseRecord]) wird der kalibrierte Wert dagegen mitgegeben,
 * siehe [leiteLabelAb] und `bewerteAlleNeu`.
 */
internal fun leiteBaulaermBefundAb(
    rohdaten: KlassifikationsRohdaten,
    konfiguration: BaulaermKonfiguration,
    kalibrierterPegelDbA: Double? = null,
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
    var einstufung = when {
        anteil >= konfiguration.anteilFuerBaulaerm -> Einstufung.BAULAERM
        maxRohScore >= konfiguration.minimalerScoreFuerMoeglich -> Einstufung.MOEGLICH
        else -> Einstufung.KEIN_BAULAERM
    }

    // KI-Umbau Etappe 3.5: die Fusion greift nur, wenn der Gruppen-Score ALLEIN (noch) nicht zu
    // BAULAERM fuehrt - ein bereits per Gruppen-Score erkannter Befund wird durch die Impuls-Regel
    // nicht "entwertet", sie kann eine Einstufung nur nach oben, nie nach unten korrigieren.
    var ueberImpulsRegel = false
    var impulsRate: Float? = null
    if (einstufung != Einstufung.BAULAERM) {
        val kurtosis = rohdaten.impulsKurtosis
        val rate = rohdaten.impulsWiederholrateHz
        val pegelUeberSchwelle = when {
            kalibrierterPegelDbA != null -> kalibrierterPegelDbA > konfiguration.impulsPegelSchwelleDbA
            rohdaten.impulsMittlererPegel != null -> rohdaten.impulsMittlererPegel > konfiguration.impulsPegelSchwelleRelativ
            else -> false
        }
        if (kurtosis != null && rate != null && pegelUeberSchwelle &&
            kurtosis > konfiguration.impulsKurtosisSchwelle && rate in konfiguration.impulsRateBereichHz
        ) {
            einstufung = Einstufung.BAULAERM
            ueberImpulsRegel = true
            impulsRate = rate
        }
    }

    return BaulaermBefund(
        anteil = anteil,
        laengsterBlockSekunden = laengsterBlockFrames * hopSekunden,
        blockAnzahl = bloecke.size,
        spitzenKlasse = spitze?.first,
        spitzenScore = spitze?.second ?: 0f,
        einstufung = einstufung,
        gesamtBaulaermSekunden = ueberSchwelle.count { it } * hopSekunden,
        ueberImpulsRegelErkannt = ueberImpulsRegel,
        impulsRateHz = impulsRate,
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
            // KI-Umbau Etappe 3.5: "Ein über die DSP-Regel erkannter Treffer wird in der UI als
            // solcher gekennzeichnet (z.B. 'Baulärm (impulsiv, 12 Hz)'), damit die Herkunft der
            // Aussage nachvollziehbar bleibt - im Beweiskontext ist das relevant."
            if (befund.ueberImpulsRegelErkannt && befund.impulsRateHz != null) {
                append(" (impulsiv, ")
                append(befund.impulsRateHz.roundToInt())
                append(" Hz)")
            }
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
