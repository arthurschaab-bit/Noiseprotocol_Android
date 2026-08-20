package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType

/**
 * Reconnect-Zähler für den Diagnose-Screen (Plan Abschnitt 9) - wird nirgends fertig geführt
 * (siehe [com.example.lrmprotokoll.meter.ConnectionSupervisor]s `consecutiveFailures`, eine rein
 * lokale Variable ohne persistiertes Gegenstück), deshalb aus den ohnehin gespeicherten
 * [ConnectionEventEntity]-Zeilen abgeleitet: jeder [ConnectionEventType.DEGRADED]/
 * [ConnectionEventType.DISCONNECTED]-Eintrag markiert den Beginn einer Ausfallperiode, die einen
 * Reconnect-Versuch ausgelöst hat (dank des `zuletztImAusfall`-Schutzes in `MeasurementRecorder`
 * genau einer je zusammenhängender Periode) - Zählen dieser Zeilen ist deshalb gleichbedeutend
 * mit Zählen der Reconnect-Zyklen.
 */
fun zaehleReconnects(events: List<ConnectionEventEntity>): Int =
    events.count { it.type == ConnectionEventType.DEGRADED || it.type == ConnectionEventType.DISCONNECTED }
