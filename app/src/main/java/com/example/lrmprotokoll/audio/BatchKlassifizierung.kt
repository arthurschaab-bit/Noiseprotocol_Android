package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdatenDao
import com.example.lrmprotokoll.data.NoiseDao
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.messreihe.NICHT_ERKANNT_MARKER
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Klassifiziert jede der übergebenen Aufnahmen per KI nach und schreibt das erkannte Label oder
 * den Status "Nicht erkannt" in die Datenbank. Gemeinsame Schleife für den globalen
 * ("Alle klassifizieren") und den
 * Pro-Tag-Batch auf dem Home-Screen (`NoiseProtocolApp`) - vorher zweimal fast identisch inline
 * in Compose-Callbacks, hier ohne Compose testbar.
 *
 * Die Auswahl DER Kandidaten (welche Aufnahmen überhaupt versucht werden) bleibt bewusst Sache
 * des Aufrufers, da sich globaler und Pro-Tag-Batch darin unterscheiden (Pro-Tag überspringt
 * zusätzlich manuell gelabelte Aufnahmen, siehe
 * [com.example.lrmprotokoll.messreihe.unklassifizierteAufnahmen]).
 *
 * KI-Umbau Etappe 1.4: nutzt [NoiseClassifier.klassifiziereMitRohdaten] statt der reinen
 * [SoundClassifier.classify]-Schnittstelle, damit auch nachtraeglich (Batch-)klassifizierte
 * Aufnahmen einen [com.example.lrmprotokoll.data.KlassifikationsRohdaten]-Datensatz bekommen -
 * jede Aufnahme wird sonst nur einmal ueberhaupt inferenziert, hier ist bereits eine `recordId`
 * bekannt (anders als beim Online-Pfad), die Rohdaten koennen also direkt mitgespeichert werden.
 *
 * Prueft vor jeder Aufnahme, ob der Aufrufer abgebrochen wurde - der [KiBatchWorker] laeuft
 * ueber Stunden, und "Abbrechen" in seiner Benachrichtigung soll nicht erst nach dem letzten
 * Kandidaten greifen. [onGespeichert] meldet jede Aufnahme mit gespeicherter Entscheidung, damit
 * der Worker die betroffenen Tage fuer den Drive-Nachtrag vormerken kann.
 *
 * @return Anzahl der Aufnahmen mit gespeicherter Klassifizierungsentscheidung.
 */
suspend fun klassifiziereUndSpeichere(
    kandidaten: List<NoiseRecord>,
    classifier: RohdatenClassifier,
    dao: NoiseDao,
    rohdatenDao: KlassifikationsRohdatenDao,
    onGespeichert: (NoiseRecord) -> Unit = {},
    onFortschritt: (bearbeitet: Int, gesamt: Int) -> Unit = { _, _ -> },
): Int {
    var anzahl = 0
    onFortschritt(0, kandidaten.size)
    for ((index, record) in kandidaten.withIndex()) {
        currentCoroutineContext().ensureActive()
        try {
            val file = File(record.filePath)
            if (!file.exists() || !file.isFile) continue
            val ergebnis = classifier.klassifiziereMitRohdaten(file) ?: continue
            // Ersetzt statt anzuhaeufen: eine erneute Batch-Klassifizierung derselben Aufnahme
            // (z.B. weil der erste Versuch kein Label ueber der Schwelle fand) soll nicht mehrere
            // Rohdatensaetze fuer dieselbe recordId hinterlassen.
            rohdatenDao.loescheFuerRecord(record.id)
            rohdatenDao.insert(ergebnis.rohdaten.mitRecordId(record.id))
            val erkannt = ergebnis.label ?: NICHT_ERKANNT_MARKER
            val aktualisiert = record.copy(detectedLabel = erkannt)
            dao.update(aktualisiert)
            anzahl++
            onGespeichert(aktualisiert)
        } finally {
            onFortschritt(index + 1, kandidaten.size)
        }
    }
    return anzahl
}
