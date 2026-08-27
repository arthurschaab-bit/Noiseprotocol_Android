package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.ChartSpalte
import com.example.lrmprotokoll.messreihe.downsampleMesswerteFuerChart
import com.example.lrmprotokoll.messreihe.leiteAusfallbaenderAb

/**
 * Aggregierte Daten fuer einen Wochen-/Monatsbericht (F12, PROMPT_M10_FUNKTIONEN.md) - im
 * Unterschied zu [MessreiheExport] nicht auf eine einzelne Session begrenzt, sondern ueber alle
 * Sessions im Zeitraum [von, bis) zusammengefasst.
 */
data class PeriodenBericht(
    val von: Long,
    val bis: Long,
    val sessionCount: Int,
    val chartSpalten: List<ChartSpalte>,
    val kennwerte: AkustischeKennwerte.Kennwerte,
    val ausfallbaender: List<Ausfallband>,
    val events: List<NoiseRecord>,
)

/**
 * [downsampleMesswerteFuerChart] und [AkustischeKennwerte.berechne] pruefen keine
 * Session-Zugehoerigkeit der uebergebenen Messwerte - beide funktionieren deshalb unveraendert auf
 * der ueber [von, bis) zusammengefuehrten, sessionuebergreifenden Messwerteliste. Nur
 * [leiteAusfallbaenderAb] ist pro Session (es gibt keine sessionuebergreifende
 * ConnectionEventEntity-Abfrage), deshalb hier explizit je ueberlappender Session
 * ([com.example.lrmprotokoll.data.SessionDao.zwischen]) aufgerufen und aneinandergehaengt, dann
 * auf den tatsaechlich angefragten Zeitraum zurechtgeschnitten (eine Session kann vor [von]
 * beginnen oder nach [bis] enden).
 */
suspend fun ermittlePeriodenBericht(db: AppDatabase, von: Long, bis: Long): PeriodenBericht {
    val sessions = db.sessionDao().zwischen(von, bis)
    val messwerte = db.measurementDao().zwischen(von, bis)
    val events = db.noiseDao().zwischenZeitpunkt(von, bis)

    val ausfallbaender = sessions
        .flatMap { session ->
            val verbindungsEreignisse = db.connectionEventDao().fuerSession(session.id)
            leiteAusfallbaenderAb(verbindungsEreignisse, session.endedAt)
        }
        .mapNotNull { band ->
            val geschnittenesVon = maxOf(band.von, von)
            val geschnittenesBis = band.bis?.let { minOf(it, bis) }
            if (geschnittenesVon >= bis || (geschnittenesBis != null && geschnittenesBis <= geschnittenesVon)) {
                null
            } else {
                Ausfallband(geschnittenesVon, geschnittenesBis)
            }
        }
        .sortedBy { it.von }

    return PeriodenBericht(
        von = von,
        bis = bis,
        sessionCount = sessions.size,
        chartSpalten = downsampleMesswerteFuerChart(messwerte, von, bis),
        kennwerte = AkustischeKennwerte.berechne(messwerte),
        ausfallbaender = ausfallbaender,
        events = events,
    )
}
