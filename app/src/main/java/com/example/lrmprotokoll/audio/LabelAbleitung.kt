package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.example.lrmprotokoll.data.ReferenceSound

/**
 * Alles, was [leiteLabelAb] zur Entscheidung braucht, gebuendelt statt einzeln uebergeben - macht
 * die Signatur stabil, wenn weitere Schwellen dazukommen (Etappe 2: [baulaermKonfiguration]).
 */
data class AbleitungsKonfiguration(
    val referenzMuster: List<ReferenceSound>,
    val labelMapping: Map<String, String>,
    val baulaermKonfiguration: BaulaermKonfiguration = BaulaermKonfiguration(),
)

/**
 * Leitet aus den persistierten Rohdaten einer Klassifizierung einen [BaulaermBefund] ab - OHNE
 * die WAV-Datei erneut zu lesen oder eine neue Inferenz zu starten. Das ist die Grundlage fuer
 * "Neu bewerten" (Etappe 1.5) und fuer jede spaetere Schwellen-/Gruppierungsaenderung, die dann
 * ohne Neu-Inferenz ueber den gesamten Bestand auskommt.
 *
 * Keine TFLite-, Android- oder DB-Abhaengigkeit (Arbeitsweise-Regel 3) - reine Funktion ueber
 * [rohdaten] und [konfiguration], in einem JVM-Unit-Test ohne Geraet pruefbar.
 *
 * KI-Umbau Etappe 2.5: liefert seit dieser Etappe einen [BaulaermBefund] (Anteil, laengster
 * Block, Einstufung) statt des schlichten `Befund(label: String)` aus Etappe 1.5 - der
 * eigentliche Qualitaetssprung des Auftrags (Gruppen-Score + Zeitaggregation statt Top-1-Label).
 * Der Referenzmuster-Abgleich (Ueberlappung der erkannten Kategorienamen mit einem gespeicherten
 * Muster >50%) ist dabei UNVERAENDERT aus Etappe 1 uebernommen und hat weiterhin Vorrang - siehe
 * [BaulaermBefund.gelernteQuelle]-KDoc fuer die Begruendung (Auftrag Abschnitt 3.3). Nur wenn
 * KEIN Referenzmuster passt, entscheidet der neue Gruppen-Score/Zeitaggregations-Pfad
 * ([leiteBaulaermBefundAb]) ueber Einstufung/Spitzenklasse.
 */
fun leiteLabelAb(rohdaten: KlassifikationsRohdaten, konfiguration: AbleitungsKonfiguration): BaulaermBefund {
    val befund = leiteBaulaermBefundAb(rohdaten, konfiguration.baulaermKonfiguration)

    val kandidaten = dekodiereTopKlassen(rohdaten.topKlassen)
    val erkannteNamen = kandidaten.map { it.name }
    konfiguration.referenzMuster.forEach { ref ->
        val refPattern = ref.pattern.split(",").toSet()
        val ueberschneidung = erkannteNamen.intersect(refPattern)
        val anteil = ueberschneidung.size.toFloat() / refPattern.size
        if (anteil > 0.5f) {
            val prozent = (anteil * 100).toInt().coerceIn(1, 100)
            return befund.copy(gelernteQuelle = "${ref.name} ($prozent%)")
        }
    }

    return befund
}

/**
 * `topKlassen` ist bewusst KEIN echtes JSON (siehe [KlassifikationsRohdaten]-KDoc): ein simples
 * `name:score`-Format, mit `;` getrennt, kommt ohne JSON-Bibliothek aus - genau das haelt
 * [leiteLabelAb] frei von jeder Android-/Library-Abhaengigkeit (Arbeitsweise-Regel 3).
 * Verifiziert gegen die eingebettete YAMNet-Labelliste: kein Kategoriename enthaelt `:`, `;`
 * oder `|`, das Format ist damit kollisionsfrei.
 */
internal fun kodiereTopKlassen(kandidaten: List<NoiseClassifier.ScoredCategory>): String =
    kandidaten.joinToString(";") { "${it.name}:${it.score}" }

internal fun dekodiereTopKlassen(text: String): List<NoiseClassifier.ScoredCategory> =
    if (text.isBlank()) {
        emptyList()
    } else {
        text.split(";").mapNotNull { eintrag ->
            val trenner = eintrag.lastIndexOf(':')
            if (trenner < 0) return@mapNotNull null
            val name = eintrag.substring(0, trenner)
            val score = eintrag.substring(trenner + 1).toFloatOrNull() ?: return@mapNotNull null
            NoiseClassifier.ScoredCategory(name, score)
        }
    }
