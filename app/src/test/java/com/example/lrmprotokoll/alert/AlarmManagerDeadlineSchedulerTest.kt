package com.example.lrmprotokoll.alert

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Testluecken-Auftrag Stufe 3: die einzige Klasse aus dem Alarm-Pfad, die tatsaechlich mit dem
 * echten [AlarmManager] spricht (Plan 7.2) - der Rest der Alarmlogik ([AlarmCoordinator]) ist
 * bereits gegen den Fake [com.example.lrmprotokoll.alert.TestScheduler] getestet, der diese
 * Klasse selbst nie beruehrt. Prueft ueber [ShadowAlarmManager], dass tatsaechlich ein exakter
 * Wecker mit dem richtigen Zeitpunkt gesetzt wird - kein exakter Wecker waere kein Bug im
 * Testcode, sondern genau die "Karenzzeit taugt nichts mehr"-Regression aus dem Klassen-KDoc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmManagerDeadlineSchedulerTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var scheduler: AlarmManagerDeadlineScheduler

    @Before
    fun aufbauen() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        shadowAlarmManager = shadowOf(alarmManager)
        scheduler = AlarmManagerDeadlineScheduler(context)
    }

    @Test
    fun scheduleSetztEinenExaktenWeckerAufDenAngegebenenZeitpunkt() {
        val zeitpunkt = Instant.parse("2026-08-19T09:00:00Z")

        scheduler.schedule(zeitpunkt)

        val alarm = requireNotNull(shadowAlarmManager.peekNextScheduledAlarm())
        assertEquals(zeitpunkt.toEpochMilli(), alarm.triggerAtTime)
        assertEquals(
            "Ohne RTC_WAKEUP wuerde der Wecker im Schlaf des Geraets nicht auslösen",
            AlarmManager.RTC_WAKEUP, alarm.type,
        )
        assertTrue(
            "Ohne allowWhileIdle haelt sich der Wecker im Doze-Modus nicht an die Uhr - siehe " +
                "Klassen-KDoc",
            alarm.allowWhileIdle,
        )
        assertEquals(
            "Mit Berechtigung muss setExactAndAllowWhileIdle() verwendet werden (windowLength=0, " +
                "WINDOW_EXACT) - eine Karenzzeit mit Toleranzfenster waere keine Karenzzeit mehr",
            ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs,
        )
    }

    @Test
    fun erneutesScheduleErsetztDenVorherigenWeckerStattIhnZuErgaenzen() {
        scheduler.schedule(Instant.parse("2026-08-19T09:00:00Z"))
        scheduler.schedule(Instant.parse("2026-08-19T10:00:00Z"))

        assertEquals(
            "Ein bereits gesetzter Wecker wird ersetzt, nicht ergaenzt (dieselbe " +
                "PendingIntent-Request-Code identifiziert ihn eindeutig)",
            1, shadowAlarmManager.scheduledAlarms.size,
        )
        assertEquals(
            Instant.parse("2026-08-19T10:00:00Z").toEpochMilli(),
            requireNotNull(shadowAlarmManager.peekNextScheduledAlarm()).triggerAtTime,
        )
    }

    @Test
    fun cancelHebtDenGeplantenWeckerWiederAuf() {
        scheduler.schedule(Instant.parse("2026-08-19T09:00:00Z"))

        scheduler.cancel()

        assertNull(shadowAlarmManager.peekNextScheduledAlarm())
    }

    @Test
    fun cancelOhneZuvorGeplantenWeckerWirftNicht() {
        scheduler.cancel()

        assertNull(shadowAlarmManager.peekNextScheduledAlarm())
    }

    @Test
    fun kannExaktIstWahrWennDieBerechtigungVorliegt() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        assertTrue(scheduler.kannExakt)
    }

    @Test
    fun ohneBerechtigungFaelltScheduleAufEinenUngenauenWeckerZurueck() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertTrue("kannExakt muss den fehlenden Zustand widerspiegeln", !scheduler.kannExakt)

        scheduler.schedule(Instant.parse("2026-08-19T09:00:00Z"))

        val alarm = requireNotNull(shadowAlarmManager.peekNextScheduledAlarm())
        assertEquals(
            "Ohne Berechtigung darf kein exakter Wecker (setExactAndAllowWhileIdle) gesetzt werden - " +
                "das waere ein SecurityException-Risiko am echten Geraet. setAndAllowWhileIdle() " +
                "setzt windowLength auf WINDOW_HEURISTIC statt WINDOW_EXACT (0).",
            ShadowAlarmManager.WINDOW_HEURISTIC, alarm.windowLengthMs,
        )
        assertEquals(zeitpunktMillis(), alarm.triggerAtTime)
    }

    private fun zeitpunktMillis() = Instant.parse("2026-08-19T09:00:00Z").toEpochMilli()
}
