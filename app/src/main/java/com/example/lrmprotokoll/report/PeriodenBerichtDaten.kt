package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
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
    /**
     * Mikrofon-Pegel (unkalibriert), NUR zur dezenten, deutlich beschrifteten Anzeige im
     * Pegelverlauf. Fliesst in KEINE Berechnung ein ([kennwerte] und [chartSpalten] enthalten sie
     * nie) - Owner-Entscheidung 10.10.2026.
     */
    val mikrofonChartSpalten: List<ChartSpalte> = emptyList(),
    val kennwerte: AkustischeKennwerte.Kennwerte,
    val ausfallbaender: List<Ausfallband>,
    val events: List<NoiseRecord>,
    /**
     * `true`, wenn JEDE Session im Zeitraum ein reiner Mikrofonlauf war
     * ([com.example.lrmprotokoll.data.SessionEntity.deviceAddress] leer) - Grundlage für
     * [com.example.lrmprotokoll.report.leqBezeichnung] in den Exporten (Befund 01 / C-2). `false`
     * bei mindestens einer Messgerät-Session ODER wenn der Zeitraum gar keine Session enthält
     * (dann sind [kennwerte] ohnehin leer, die Bezeichnung ist irrelevant).
     */
    val nurMikrofon: Boolean,
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
    val nurMikrofon = nurMikrofonSessions(sessions)

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

    // Owner-Entscheidung 10.10.2026: Mikrofonwerte fliessen in KEINE Berechnung (Kennwerte,
    // Diagramm-Hauptkurve) ein, sondern werden nur separat und dezent im Pegelverlauf gezeigt.
    val getrennt = trenneMesswerte(messwerte, sessions)

    return PeriodenBericht(
        von = von,
        bis = bis,
        sessionCount = sessions.size,
        chartSpalten = downsampleMesswerteFuerChart(getrennt.kalibriert, von, bis),
        mikrofonChartSpalten = downsampleMesswerteFuerChart(getrennt.mikrofon, von, bis),
        kennwerte = AkustischeKennwerte.berechne(getrennt.kalibriert),
        ausfallbaender = ausfallbaender,
        events = events,
        nurMikrofon = nurMikrofon,
    )
}

/**
 * `true`, wenn [sessions] nicht leer ist und JEDE Session darin ein reiner Mikrofonlauf war
 * (leere [SessionEntity.deviceAddress]). Als eigenständige Funktion testbar ohne Room/Robolectric
 * (Befund 01 / Korrekturliste C-2) - siehe [PeriodenBericht.nurMikrofon] für die Verwendung.
 */
internal fun nurMikrofonSessions(sessions: List<SessionEntity>): Boolean =
    sessions.isNotEmpty() && sessions.all { it.deviceAddress.isBlank() }

/** Messwerte, getrennt nach kalibriertem Messgeraet und unkalibriertem Mikrofon. */
internal data class GetrennteMesswerte(
    val kalibriert: List<com.example.lrmprotokoll.data.MeasurementEntity>,
    val mikrofon: List<com.example.lrmprotokoll.data.MeasurementEntity>,
)

/**
 * Mikrofon-Messwerte sind die Messwerte reiner Mikrofon-Sessions (leere
 * [SessionEntity.deviceAddress]; [com.example.lrmprotokoll.messreihe.MeasurementRecorder] schreibt
 * Mikrofonwerte nie in eine Messgeraet-Session). Alles andere gilt als kalibriert.
 */
internal fun trenneMesswerte(
    messwerte: List<com.example.lrmprotokoll.data.MeasurementEntity>,
    sessions: List<SessionEntity>,
): GetrennteMesswerte {
    val mikrofonSessionIds = sessions.filter { it.deviceAddress.isBlank() }.mapTo(mutableSetOf()) { it.id }
    val (mikrofon, kalibriert) = messwerte.partition { it.sessionId in mikrofonSessionIds }
    return GetrennteMesswerte(kalibriert = kalibriert, mikrofon = mikrofon)
}
