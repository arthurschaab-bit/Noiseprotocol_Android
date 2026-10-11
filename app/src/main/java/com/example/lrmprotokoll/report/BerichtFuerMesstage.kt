package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.AkustischeKennwerte
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.berechneDatenverfuegbarkeitProzent
import com.example.lrmprotokoll.messreihe.downsampleMesswerteFuerChart
import com.example.lrmprotokoll.messreihe.leiteAusfallbaenderAb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Bericht ueber eine **Liste ausgewaehlter Messtage** statt ueber ein zusammenhaengendes Fenster
 * (S-4).
 *
 * **Warum das noetig ist:** Im Bericht-Reiter kann der Nutzer einzelne Tage abwaehlen, auch mitten
 * im Zeitraum. [ermittlePeriodenBericht] nimmt `von`/`bis` - ein abgewaehlter Tag dazwischen waere
 * trotzdem im PDF, ohne dass es jemand sieht. Bei einem Dokument, das als Nachweis dient, ist ein
 * stiller Mehrinhalt der schlimmere Fehler.
 *
 * **Was gleich bleibt:** Sind alle Messtage des Zeitraums gewaehlt - der Normalfall - liefert
 * [ermittlePeriodenBerichtFuerTage] dasselbe wie [ermittlePeriodenBericht] ueber denselben
 * Zeitraum. Die Tagesfenster kacheln den Zeitraum luecken- und ueberschneidungsfrei, und Tage ohne
 * Session enthalten ohnehin keine Messwerte.
 *
 * Im Pegelverlauf erscheint ein abgewaehlter Tag als **Luecke** - dieselbe Darstellung wie bei
 * einem Tag ohne Messung. Das ist beabsichtigt: die Kurve behauptet nichts ueber Zeit, zu der
 * nichts berichtet wird.
 */
suspend fun ermittlePeriodenBerichtFuerTage(
    db: AppDatabase,
    tage: List<Messtag>,
): PeriodenBericht {
    require(tage.isNotEmpty()) { "Es wurde kein Messtag gewählt." }
    val sortiert = tage.sortedBy { it.von }
    val von = sortiert.first().von
    val bis = sortiert.last().bis

    val gewaehlteSessionIds = sortiert.flatMap { it.sessionIds }.toSet()
    val sessions =
        db
            .sessionDao()
            .zwischen(von, bis)
            .filter { it.id in gewaehlteSessionIds }

    val messwerte = ladeJeTag(sortiert) { tag -> db.measurementDao().zwischen(tag.von, tag.bis) }
    val events = ladeJeTag(sortiert) { tag -> db.noiseDao().zwischenZeitpunkt(tag.von, tag.bis) }
    val ausfallbaender = ausfallbaenderFuerTage(db, sessions, sortiert)
    val nurMikrofon = nurMikrofonSessions(sessions)
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

/** Wie [ermittleGesamtbericht], aber mit Tagesseiten nur fuer die gewaehlten Messtage. */
suspend fun ermittleGesamtberichtFuerTage(
    db: AppDatabase,
    tage: List<Messtag>,
): Gesamtbericht {
    require(tage.isNotEmpty()) { "Es wurde kein Messtag gewählt." }
    val sortiert = tage.sortedBy { it.von }
    val gesamt = ermittlePeriodenBerichtFuerTage(db, sortiert)
    val tagesberichte =
        coroutineScope {
            sortiert
                .map { tag ->
                    async {
                        val tagesBericht = ermittlePeriodenBericht(db, tag.von, tag.bis)
                        GesamtberichtTag(
                            von = tag.von,
                            bis = tag.bis,
                            bericht = tagesBericht,
                            datenverfuegbarkeitProzent =
                                berechneDatenverfuegbarkeitProzent(
                                    tag.von,
                                    tag.bis,
                                    tagesBericht.ausfallbaender,
                                ),
                        )
                    }
                }.awaitAll()
        }
    return Gesamtbericht(gesamt = gesamt, tage = tagesberichte)
}

private suspend fun <T> ladeJeTag(
    tage: List<Messtag>,
    laden: suspend (Messtag) -> List<T>,
): List<T> = coroutineScope { tage.map { tag -> async { laden(tag) } }.awaitAll().flatten() }

/**
 * Die Ausfallbaender der [sessions], zugeschnitten auf die gewaehlten [tage] - ein Ausfall, der in
 * einen abgewaehlten Tag faellt, zaehlt nicht mit. Die Ereignisse werden je Session **einmal**
 * geholt.
 */
private suspend fun ausfallbaenderFuerTage(
    db: AppDatabase,
    sessions: List<SessionEntity>,
    tage: List<Messtag>,
): List<Ausfallband> {
    val jeSession =
        coroutineScope {
            sessions
                .map { session ->
                    async { leiteAusfallbaenderAb(db.connectionEventDao().fuerSession(session.id), session.endedAt) }
                }.awaitAll()
                .flatten()
        }
    return jeSession
        .flatMap { band ->
            tage.mapNotNull { tag ->
                val geschnittenesVon = maxOf(band.von, tag.von)
                val geschnittenesBis = minOf(band.bis ?: tag.bis, tag.bis)
                if (geschnittenesBis > geschnittenesVon) Ausfallband(geschnittenesVon, geschnittenesBis) else null
            }
        }.sortedBy { it.von }
}
