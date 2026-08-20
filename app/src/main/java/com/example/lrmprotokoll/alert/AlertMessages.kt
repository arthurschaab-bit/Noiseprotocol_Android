package com.example.lrmprotokoll.alert

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Formuliert die Alarmtexte - zentral und nicht je Kanal.
 *
 * Zwei Gruende: SMS und Push sollen nicht auseinanderlaufen, und die Auflage aus Plan 7.4
 * ("keine Messwerte, keine Orte, keine Geraetekennungen im Alarmtext") laesst sich nur an einer
 * Stelle durchsetzen. Beim oeffentlichen ntfy-Server ist der Topic-Name die einzige
 * Zugangskontrolle; ein Alarmtext, der mehr verraet als noetig, ist dort ein Datenleck.
 */
object AlertMessages {

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun formatiere(
        kind: AlertKind,
        reason: AlertReason,
        since: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val zeit = FORMAT.format(since.atZone(zone))
        return when (kind) {
            AlertKind.RAISED ->
                "Lärmprotokoll: Verbindung zum Messgerät unterbrochen seit $zeit " +
                    "(Grund: ${reason.beschreibung()}). Aufzeichnung pausiert."

            AlertKind.ESCALATED ->
                "Lärmprotokoll: Verbindung zum Messgerät weiterhin unterbrochen, seit $zeit " +
                    "(Grund: ${reason.beschreibung()})."

            AlertKind.RESOLVED ->
                "Lärmprotokoll: Verbindung zum Messgerät wieder hergestellt. " +
                    "Der Ausfall bestand seit $zeit."

            AlertKind.TEST ->
                "Lärmprotokoll: Testnachricht. Dieser Alarmkanal funktioniert."
        }
    }

    /** Titelzeile fuer Kanaele, die eine haben (ntfy). */
    fun titel(kind: AlertKind): String = when (kind) {
        AlertKind.RAISED, AlertKind.ESCALATED -> "Lärmprotokoll: Verbindung verloren"
        AlertKind.RESOLVED -> "Lärmprotokoll: Verbindung wieder da"
        AlertKind.TEST -> "Lärmprotokoll: Test"
    }
}
