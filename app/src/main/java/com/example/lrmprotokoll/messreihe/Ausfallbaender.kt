package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType

/**
 * Ein zusammenhängender Ausfall innerhalb einer Session, für die Protokollansicht (Plan
 * Abschnitt 9: "Ausfallmarkierungen als rote Bänder im Verlauf"). [bis] ist `null`, solange der
 * Ausfall bei Abfragezeitpunkt noch andauert (kein [ConnectionEventType.RECOVERED] gefolgt) - das
 * ist entweder eine laufende Session mit aktivem Ausfall, oder eine beendete Session, die
 * mittendrin im Ausfall geendet hat (dann wird [bis] auf das Sessionende gesetzt, siehe
 * [leiteAusfallbaenderAb]).
 */
data class Ausfallband(val von: Long, val bis: Long?)

/**
 * Rein funktionale Ableitung aus den in M4 geschriebenen [ConnectionEventEntity]-Zeilen einer
 * Session - kein eigener Zustand, keine Persistenz. [MeasurementRecorder] garantiert bereits, dass
 * eine zusammenhängende Ausfallperiode genau EIN [ConnectionEventType.DEGRADED]/[ConnectionEventType.DISCONNECTED]
 * erzeugt (siehe dessen KDoc zum `zuletztImAusfall`-Schutz) - diese Funktion muss deshalb nur noch
 * öffnende und schließende Ereignisse chronologisch paaren, keine Duplikate mehr filtern.
 *
 * [sessionEndedAt] schließt ein am Ende noch offenes Band auf das Sessionende, falls die Session
 * bereits beendet ist (`null` heißt: Session läuft noch, das Band bleibt offen/[Ausfallband.bis]
 * = `null`).
 */
fun leiteAusfallbaenderAb(events: List<ConnectionEventEntity>, sessionEndedAt: Long?): List<Ausfallband> {
    val chronologisch = events.sortedBy { it.at }
    val baender = mutableListOf<Ausfallband>()
    var offenerBeginn: Long? = null

    for (event in chronologisch) {
        when (event.type) {
            ConnectionEventType.DEGRADED, ConnectionEventType.DISCONNECTED -> {
                if (offenerBeginn == null) offenerBeginn = event.at
            }
            ConnectionEventType.RECOVERED -> {
                val beginn = offenerBeginn
                if (beginn != null) {
                    baender += Ausfallband(beginn, event.at)
                    offenerBeginn = null
                }
            }
        }
    }

    offenerBeginn?.let { beginn -> baender += Ausfallband(beginn, sessionEndedAt) }
    return baender
}
