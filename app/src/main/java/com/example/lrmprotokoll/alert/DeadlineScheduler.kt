package com.example.lrmprotokoll.alert

import java.time.Instant

/**
 * Weckt den [AlarmCoordinator] zu einem festen Zeitpunkt.
 *
 * Eigene Abstraktion statt eines Coroutine-`delay()`, aus zwei Gruenden:
 *
 * 1. **Doze.** Ein `delay()` haelt sich nicht an die Uhr, wenn das Geraet schlaeft - es feuert
 *    unter Umstaenden Stunden spaeter, also genau dann nicht, wenn der Alarm gebraucht wird.
 *    Die Android-Implementierung benutzt deshalb `AlarmManager.setExactAndAllowWhileIdle()`
 *    (Plan Abschnitt 7.2).
 * 2. **Testbarkeit.** Karenzzeit 60 s, Cooldown 30 min und Eskalation 60 min lassen sich gegen
 *    einen Fake in Millisekunden durchspielen; mit echten Timern dauerte ein einziger
 *    Eskalationstest eine Stunde und wuerde danach "vereinfacht", bis er nichts mehr prueft.
 */
interface DeadlineScheduler {

    /** Setzt die naechste Weckzeit. Eine bereits gesetzte wird ersetzt, nicht ergaenzt. */
    fun schedule(at: Instant)

    fun cancel()

    /**
     * Ob exakte Alarme erlaubt sind (ab Android 14 eine eigene Berechtigung). Ist das nicht der
     * Fall, feuert die Karenzzeit im Doze ungenau - das gehoert dem Nutzer angezeigt, statt es
     * stillschweigend hinzunehmen.
     */
    val kannExakt: Boolean
}
