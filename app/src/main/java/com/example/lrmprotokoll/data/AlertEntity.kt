package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ein Ausfall und der daraus entstandene Alarm (Plan Abschnitt 8.1).
 *
 * Die Zeile entsteht **beim Beginn des Ausfalls**, nicht erst beim Absenden des Alarms. Das ist
 * kein Detail: Zwischen Ausfallbeginn und Alarm liegt die Karenzzeit (Default 60 s), und stirbt
 * der Prozess in dieser Zeit - Hersteller-ROM, Absturz, Speicherdruck -, dann ist diese Zeile die
 * einzige Spur, aus der sich der faellige Alarm nach dem Neustart rekonstruieren laesst. Ein
 * Alarmsystem, das seinen Zustand nur im Speicher haelt, verschluckt genau die Alarme, die aus
 * einem ernsten Problem stammen.
 *
 * Abweichung vom Schema in Plan 8.1, im PR vermerkt: [outageSince] und [escalations] kommen
 * hinzu. `outageSince` entspricht dem `since` aus Plan 7.2 und traegt die oben beschriebene
 * Wiederaufnahme; `escalations` zaehlt die Wiederholungen, ohne die sich die beschlossene Grenze
 * von maximal drei nicht durchsetzen laesst.
 */
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Bleibt bis M4 immer null - Sessions entstehen erst mit der Persistenz der Messreihe.
     * Die Spalte existiert trotzdem schon, damit M4 sie nur noch fuellen muss und keine zweite
     * Migration derselben Tabelle noetig wird.
     */
    val sessionId: Long? = null,

    /** Beginn des Ausfalls (epoch millis). Ab hier laeuft die Karenzzeit. */
    val outageSince: Long,

    /** Zeitpunkt des tatsaechlich ausgeloesten Alarms, oder null solange die Karenzzeit laeuft. */
    val raisedAt: Long? = null,

    /** Zeitpunkt der Rueckkehr der Verbindung, oder null solange der Ausfall besteht. */
    val resolvedAt: Long? = null,

    /** [com.example.lrmprotokoll.alert.AlertReason], als String abgelegt. */
    val reason: String,

    /** Kanaele, ueber die versendet wurde - kommagetrennte [com.example.lrmprotokoll.alert.ChannelId]. */
    val recipients: String = "",

    /** PENDING, SENT oder FAILED. Aggregat ueber alle Kanaele, siehe [DeliveryState]. */
    val deliveryState: String = DeliveryState.PENDING,

    /** Zahl der Versandversuche ueber alle Kanaele, fuer die Wiederholung nach Netzausfall. */
    val attempts: Int = 0,

    /** Zahl der bereits gesendeten Eskalationswiederholungen (Grenze: 3). */
    val escalations: Int = 0,
) {
    val isOpen: Boolean get() = resolvedAt == null
}

/**
 * Zustand des Versands. Bewusst ein Aggregat ueber alle Kanaele und keine Zeile pro Kanal:
 * Fuer die Alarmlogik zaehlt allein, ob der Alarm ueberhaupt jemanden erreicht hat. SENT gilt
 * deshalb, sobald **ein** Kanal erfolgreich war; FAILED erst, wenn alle gescheitert sind.
 */
object DeliveryState {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
}
