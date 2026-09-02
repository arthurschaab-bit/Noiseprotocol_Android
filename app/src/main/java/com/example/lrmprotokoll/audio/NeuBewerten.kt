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
 * KI-Umbau Etappe 3.5 ("Fusion"): holt zusätzlich den kalibrierten PCE-323-Pegel des jeweiligen
 * [com.example.lrmprotokoll.data.NoiseRecord] und reicht ihn an [leiteLabelAb] durch - "Neu
 * bewerten" ist damit der einzige Pfad, der die Impuls-Regel mit dem kalibrierten Pegel
 * auswertet (das Live-Klassifizieren kennt ihn zum Zeitpunkt der Inferenz nicht zuverlässig,
 * siehe [leiteBaulaermBefundAb]-KDoc, und fällt dort auf den relativen Ersatzwert zurück).
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
        val kalibrierterPegel = noiseDao.getCalibratedDbA(rohdaten.recordId)
        val befund = leiteLabelAb(rohdaten, konfiguration, kalibrierterPegel)
        val neuesLabel = formatiereBaulaermBefund(befund, konfiguration.labelMapping)
        noiseDao.setDetectedLabel(rohdaten.recordId, neuesLabel)
    }
    return alleRohdaten.size
}
