package com.example.lrmprotokoll.alert

import java.time.Duration

/**
 * Die Stellschrauben der Alarmlogik (Plan Abschnitt 7.2).
 *
 * Die Defaults sind Entscheidungen des Owners, keine Vorschlaege: Karenzzeit 60 s, Cooldown
 * 30 min, Eskalation nach 60 min, maximal 3 Wiederholungen, Entwarnung ueber Push und nicht
 * ueber SMS (Plan Abschnitt 13, Punkte 1 und 3).
 */
data class AlarmConfig(
    /**
     * Wartezeit zwischen Ausfallbeginn und Alarm. In dieser Zeit laufen die Reconnect-Versuche
     * des ConnectionSupervisor weiter; kehrt die Verbindung zurueck, entfaellt der Alarm
     * geraeuschlos. Ohne diese Entprellung meldet die App jeden Sekundenaussetzer - und wird
     * binnen einer Woche stummgeschaltet.
     */
    val grace: Duration = Duration.ofSeconds(60),

    /**
     * Nach einem Alarm bleibt es fuer diese Zeit still, auch wenn erneut ein Ausfall beginnt.
     * Besteht der Ausfall beim Ablauf des Cooldowns noch, wird der Alarm nachgeholt statt
     * verworfen - sonst waere ein Dauerausfall, der zufaellig in den Cooldown faellt, fuer
     * immer stumm.
     */
    val cooldown: Duration = Duration.ofMinutes(30),

    /** Besteht der Ausfall so lange nach dem ersten Alarm fort, wird wiederholt. */
    val escalateAfter: Duration = Duration.ofMinutes(60),

    /** Obergrenze der Wiederholungen. 0 schaltet die Eskalation ab. */
    val maxEscalations: Int = 3,

    /**
     * Kanaele, die eine Entwarnung bekommen.
     *
     * Der Owner hat entschieden: bei Push an. Die Einschraenkung, die diese Einstellung
     * ueberhaupt noetig machte, betraf SMS - dort haette die Entwarnung das Nachrichtenaufkommen
     * spuerbar verdoppelt. Bei Push und lokaler Meldung kostet sie nichts, deshalb beide an.
     * Die Einstellung bleibt trotzdem je Kanal schaltbar, weil der Grund fuer sie mit jedem
     * kuenftigen Kanal wiederkommen kann.
     */
    val entwarnungUeber: Set<ChannelId> = setOf(ChannelId.NTFY, ChannelId.LOCAL_NOTIFICATION),

    /**
     * Nachfrist nach einem Neustart des Prozesses. Wurde die App waehrend eines laufenden
     * Ausfalls abgeschossen, ist die urspruengliche Karenzzeit beim Wiederanlauf womoeglich
     * laengst abgelaufen - sofort zu alarmieren waere aber falsch, weil die Verbindung nach dem
     * Neustart erst wieder aufgebaut werden muss. Diese Frist gibt dem Verbindungsaufbau eine
     * Chance, bevor der faellige Alarm rausgeht.
     */
    val neustartNachfrist: Duration = Duration.ofSeconds(15),
)
