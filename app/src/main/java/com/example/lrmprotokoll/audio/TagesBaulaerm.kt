package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdatenDao
import com.example.lrmprotokoll.data.NoiseRecord

/**
 * KI-Umbau Etappe 2.7: "In der Tagesgruppe eine Summenzeile: wie viele Minuten des Tages als
 * Baulärm eingestuft sind - das ist die beweisrelevante Kennzahl." Summiert
 * [BaulaermBefund.gesamtBaulaermSekunden] über alle übergebenen Aufnahmen des Tages, die bereits
 * Rohdaten haben - eine Aufnahme ohne Rohdaten (noch nicht klassifiziert) trägt 0 bei, statt die
 * ganze Berechnung abzubrechen (Etappe-1-Grundsatz: Altbestand/Unklassifiziertes darf die
 * restliche Anzeige nicht stören).
 *
 * Reine Aggregation über bereits gespeicherte Rohdaten - keine Inferenz, keine WAV-Datei wird
 * gelesen. Dass sich das bei jedem UI-Rendering neu berechnen lässt, ohne teuer zu sein, ist
 * genau das Versprechen der Rohdaten-Persistenz aus Etappe 1.
 */
suspend fun berechneBaulaermMinutenDesTages(
    records: List<NoiseRecord>,
    rohdatenDao: KlassifikationsRohdatenDao,
    konfiguration: BaulaermKonfiguration,
): Float {
    var summeSekunden = 0f
    for (record in records) {
        val rohdaten = rohdatenDao.fuerRecord(record.id) ?: continue
        summeSekunden += leiteBaulaermBefundAb(rohdaten, konfiguration).gesamtBaulaermSekunden
    }
    return summeSekunden / 60f
}
