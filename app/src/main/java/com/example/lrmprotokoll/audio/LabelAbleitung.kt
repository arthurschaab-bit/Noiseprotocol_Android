package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.example.lrmprotokoll.data.ReferenceSound

/**
 * KI-Umbau Etappe 1.5: das Ergebnis einer Label-Ableitung. Bewusst nur der Anzeigetext - die
 * reichere [BaulaermBefund]-Struktur (Anteil, laengster Block, Einstufung) kommt erst mit
 * Etappe 2, wenn die Zeitaggregation ueber die Frame-Scores existiert. Hier geht es nur darum,
 * das bisherige [NoiseClassifier.classify]-Verhalten aus persistierten Rohdaten zu reproduzieren.
 */
data class Befund(val label: String)

/**
 * Alles, was [leiteLabelAb] zur Entscheidung braucht, gebuendelt statt einzeln uebergeben - macht
 * die Signatur stabil, wenn (Etappe 2) weitere Schwellen dazukommen.
 */
data class AbleitungsKonfiguration(
    val referenzMuster: List<ReferenceSound>,
    val labelMapping: Map<String, String>,
    val ausschlussLabels: Set<String> = setOf("Background noise", "Silence", "Noise"),
)

/**
 * Leitet aus den persistierten Rohdaten einer Klassifizierung genau das Label ab, das
 * [NoiseClassifier.classify] vorher direkt aus der Inferenz berechnet hat - OHNE die WAV-Datei
 * erneut zu lesen oder eine neue Inferenz zu starten. Das ist die Grundlage fuer "Neu bewerten"
 * (Etappe 1.5) und fuer jede spaetere Schwellen-/Gruppierungsaenderung (Etappe 2/3), die dann
 * ohne Neu-Inferenz ueber den gesamten Bestand auskommt.
 *
 * Keine TFLite-, Android- oder DB-Abhaengigkeit (Arbeitsweise-Regel 3) - reine Funktion ueber
 * [rohdaten] und [konfiguration], in einem JVM-Unit-Test ohne Geraet pruefbar.
 *
 * Verhalten ist Zeile fuer Zeile identisch zur vorherigen Implementierung in
 * [NoiseClassifier.classify]:
 * 1. Abgleich gegen jedes [AbleitungsKonfiguration.referenzMuster] - Ueberlappung der erkannten
 *    Kategorienamen mit dem gespeicherten Muster >50% ergibt "Gelernt: <Name> (X%)".
 * 2. Sonst das staerkste Label, das nicht in [AbleitungsKonfiguration.ausschlussLabels] steht,
 *    ueber [AbleitungsKonfiguration.labelMapping] eingedeutscht, mit Konfidenz-Prozentwert.
 * 3. Kein Kandidat uebrig -> `null` (kein Absturz, keine erzwungene Aussage).
 */
fun leiteLabelAb(rohdaten: KlassifikationsRohdaten, konfiguration: AbleitungsKonfiguration): Befund? {
    val kandidaten = dekodiereTopKlassen(rohdaten.topKlassen)
    val erkannteNamen = kandidaten.map { it.name }

    konfiguration.referenzMuster.forEach { ref ->
        val refPattern = ref.pattern.split(",").toSet()
        val ueberschneidung = erkannteNamen.intersect(refPattern)
        val anteil = ueberschneidung.size.toFloat() / refPattern.size
        if (anteil > 0.5f) {
            val prozent = (anteil * 100).toInt().coerceIn(1, 100)
            return Befund("Gelernt: ${ref.name} ($prozent%)")
        }
    }

    val top = kandidaten.firstOrNull { it.name !in konfiguration.ausschlussLabels } ?: return null
    val eingedeutscht = konfiguration.labelMapping[top.name] ?: top.name
    val prozent = (top.score * 100).toInt().coerceIn(1, 100)
    return Befund("$eingedeutscht ($prozent%)")
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
