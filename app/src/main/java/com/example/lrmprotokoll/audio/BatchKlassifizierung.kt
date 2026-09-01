package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.NoiseDao
import com.example.lrmprotokoll.data.NoiseRecord
import java.io.File

/**
 * Klassifiziert jede der übergebenen Aufnahmen per KI nach und schreibt ein erkanntes Label in
 * die Datenbank. Gemeinsame Schleife für den globalen ("Alle klassifizieren") und den
 * Pro-Tag-Batch auf dem Home-Screen (`NoiseProtocolApp`) - vorher zweimal fast identisch inline
 * in Compose-Callbacks, hier ohne Compose testbar.
 *
 * Die Auswahl DER Kandidaten (welche Aufnahmen überhaupt versucht werden) bleibt bewusst Sache
 * des Aufrufers, da sich globaler und Pro-Tag-Batch darin unterscheiden (Pro-Tag überspringt
 * zusätzlich manuell gelabelte Aufnahmen, siehe
 * [com.example.lrmprotokoll.messreihe.unklassifizierteAufnahmen]).
 *
 * @return Anzahl der tatsächlich neu klassifizierten Aufnahmen.
 */
suspend fun klassifiziereUndSpeichere(
    kandidaten: List<NoiseRecord>,
    classifier: SoundClassifier,
    dao: NoiseDao,
): Int {
    var anzahl = 0
    for (record in kandidaten) {
        val file = File(record.filePath)
        if (!file.exists() || !file.isFile) continue
        val erkannt = classifier.classify(file) ?: continue
        dao.update(record.copy(detectedLabel = erkannt))
        anzahl++
    }
    return anzahl
}
