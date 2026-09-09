package com.example.lrmprotokoll.messreihe

/**
 * Anteil von `[von, bis)` OHNE Verbindungsausfall, als Prozentsatz (0.0..100.0) - fuer die
 * "Datenqualitaet"-Angabe im Gesamtbericht. Reine Arithmetik auf den bereits vorhandenen
 * [Ausfallband]ern ([leiteAusfallbaenderAb]): keine neue Erfassung, nur eine neue Kennzahl aus
 * bereits vorhandenen Daten.
 *
 * [ausfallbaender] muessen nicht auf `[von, bis)` zurechtgeschnitten sein - diese Funktion
 * schneidet selbst zu (dieselbe Coercion wie in [com.example.lrmprotokoll.report.ermittlePeriodenBericht]),
 * damit ein Band, das vor [von] beginnt oder ueber [bis] hinausreicht, nicht mehr als seinen
 * tatsaechlichen Anteil am Zeitraum zaehlt.
 */
fun berechneDatenverfuegbarkeitProzent(von: Long, bis: Long, ausfallbaender: List<Ausfallband>): Double {
    val gesamtDauer = (bis - von).coerceAtLeast(0)
    if (gesamtDauer == 0L) return 0.0

    val ausfallDauer = ausfallbaender.sumOf { band ->
        val bandVon = band.von.coerceIn(von, bis)
        val bandBis = (band.bis ?: bis).coerceIn(von, bis)
        (bandBis - bandVon).coerceAtLeast(0)
    }
    val verfuegbareDauer = (gesamtDauer - ausfallDauer).coerceAtLeast(0)
    return (verfuegbareDauer.toDouble() / gesamtDauer.toDouble()) * 100.0
}
