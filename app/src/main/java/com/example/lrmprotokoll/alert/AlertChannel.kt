package com.example.lrmprotokoll.alert

import java.time.Instant

/**
 * Ein Alarmkanal (Plan Abschnitt 7.6).
 *
 * Die Abstraktion hat sich bereits bewaehrt, bevor der erste Kanal fertig war: Der urspruenglich
 * geplante SMS-Kanal wurde vom Owner gestrichen (`SEND_SMS` ist eine von Google eingeschraenkte
 * Berechtigung, siehe Plan 7.6). Weil der [AlarmCoordinator] nur dieses Interface kennt, war das
 * ein Streichen aus der Kanalliste - kein Eingriff in die Alarmlogik.
 */
interface AlertChannel {

    val id: ChannelId

    /**
     * Ob der Kanal ueberhaupt versenden koennte: Berechtigung erteilt, Konfiguration vollstaendig.
     * Wird vor jedem Versand geprueft, nicht einmalig zwischengespeichert - eine Berechtigung kann
     * dem Nutzer jederzeit entzogen werden, und eine Rufnummer kann er jederzeit loeschen.
     */
    val isAvailable: Boolean

    suspend fun send(alert: Alert): Result<Unit>
}

enum class ChannelId {
    /** Push auf das Zweitgeraet ueber ntfy (Plan 7.4, Option A). */
    NTFY,

    /**
     * Meldung auf dem Ueberwachungsgeraet selbst (Plan 7.6 nennt sie als
     * `LocalNotificationAlertChannel`).
     *
     * Deckt einen anderen Ausfall ab als ntfy und ist deshalb kein Ersatz, sondern eine
     * Ergaenzung: Sie braucht kein Netz und keinen Fremddienst. Faellt genau das Internet aus,
     * ist sie der einzige Kanal, der noch etwas melden kann - und sei es nur fuer den, der das
     * Geraet ohnehin in der Hand hat.
     */
    LOCAL_NOTIFICATION,
}

/** Warum alarmiert wird. Landet als String in [com.example.lrmprotokoll.data.AlertEntity.reason]. */
enum class AlertReason {
    /** Die Verbindung wurde hart abgebrochen. */
    DISCONNECTED,

    /** Die Verbindung steht, aber es kommen keine Daten mehr - der haeufigste stille Ausfall. */
    STALE,

    /** Bluetooth wurde abgeschaltet. */
    ADAPTER_OFF,

    /** Alle Wiederverbindungsversuche sind erschoepft. */
    RECONNECT_EXHAUSTED,
    ;

    fun beschreibung(): String = when (this) {
        DISCONNECTED -> "Verbindung abgebrochen"
        STALE -> "keine Daten"
        ADAPTER_OFF -> "Bluetooth aus"
        RECONNECT_EXHAUSTED -> "Geraet nicht erreichbar"
    }
}

/** Was ein Alarm mitteilt. */
enum class AlertKind {
    /** Ein Ausfall besteht ueber die Karenzzeit hinaus. */
    RAISED,

    /** Wiederholung, weil der Ausfall nach der Eskalationsfrist immer noch besteht. */
    ESCALATED,

    /** Die Verbindung ist zurueck. */
    RESOLVED,

    /** Vom Nutzer ausgeloeste Probenachricht aus den Einstellungen. */
    TEST,
}

/**
 * Die zu versendende Nachricht.
 *
 * [message] wird zentral formuliert und nicht je Kanal, damit SMS und Push nicht auseinanderlaufen
 * und der Text nur an einer Stelle darauf geprueft werden muss, dass er keine Messwerte, Orte oder
 * Geraetekennungen enthaelt (Plan 7.4, Sicherheitshinweis).
 */
data class Alert(
    val alertId: Long,
    val kind: AlertKind,
    val reason: AlertReason,
    val since: Instant,
    val message: String,
)
