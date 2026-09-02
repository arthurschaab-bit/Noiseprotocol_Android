package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdatenDao
import com.example.lrmprotokoll.data.NoiseDao

/**
 * KI-Umbau Etappe 1.5, "Neu bewerten": wendet [leiteLabelAb] auf ALLE gespeicherten
 * [com.example.lrmprotokoll.data.KlassifikationsRohdaten] an - ohne eine einzige WAV-Datei zu
 * lesen und ohne eine neue Inferenz zu starten. Das ist der Sinn der ganzen Etappe: eine
 * geaenderte Konfidenzschwelle oder ein korrigiertes [AbleitungsKonfiguration.labelMapping]
 * (Etappe 2.2) laesst sich damit auf den gesamten Bestand anwenden, ohne den YAMNet-Klassifikator
 * erneut ueber jede Aufnahme laufen zu lassen.
 *
 * Altaufnahmen OHNE Rohdaten (vor dieser Etappe entstanden) werden gar nicht erst angefasst -
 * [KlassifikationsRohdatenDao.alle] liefert nur Aufnahmen, für die überhaupt Rohdaten existieren
 * (Akzeptanzkriterium Etappe 1: "kein Absturz, kein leerer Screen" für den Altbestand).
 *
 * @return Anzahl der neu bewerteten Aufnahmen (mit vorhandenen Rohdaten - nicht die Anzahl
 * tatsächlicher Label-Änderungen).
 */
suspend fun bewerteAlleNeu(
    noiseDao: NoiseDao,
    rohdatenDao: KlassifikationsRohdatenDao,
    konfiguration: AbleitungsKonfiguration,
): Int {
    val alleRohdaten = rohdatenDao.alle()
    alleRohdaten.forEach { rohdaten ->
        val befund = leiteLabelAb(rohdaten, konfiguration)
        val neuesLabel = formatiereBaulaermBefund(befund, konfiguration.labelMapping)
        noiseDao.setDetectedLabel(rohdaten.recordId, neuesLabel)
    }
    return alleRohdaten.size
}
