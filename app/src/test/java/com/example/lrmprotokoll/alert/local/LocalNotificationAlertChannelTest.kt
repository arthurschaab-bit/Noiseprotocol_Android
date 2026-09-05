package com.example.lrmprotokoll.alert.local

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.alert.Alert
import com.example.lrmprotokoll.alert.AlertKind
import com.example.lrmprotokoll.alert.AlertReason
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 3: [alarmVibrationsmuster] war bereits als reine Funktion getestet
 * (siehe die vier ersten Tests unten, unveraendert) - neu sind die Tests fuer [send] selbst: wird
 * ueberhaupt eine Notification erzeugt, auf dem richtigen Kanal, mit dem richtigen Titel/Text aus
 * [com.example.lrmprotokoll.alert.AlertMessages]. Ton/Vibration ueber Ringtone/Vibrator sind
 * bewusst nicht geprueft - beide sind in `send()` durch eigene try/catch-Bloecke bereits gegen
 * fehlende Hardware abgesichert, unter Robolectric ohne echtes Audio-/Vibrations-Backend nicht
 * sinnvoll beobachtbar, und Ton allein aendert nichts an der hier interessanten Frage (kommt die
 * Meldung mit dem richtigen Inhalt beim NotificationManager an).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNotificationAlertChannelTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    private fun alarm(kind: AlertKind = AlertKind.RAISED, message: String = "Testnachricht") = Alert(
        alertId = 1L, kind = kind, reason = AlertReason.STALE,
        since = Instant.parse("2026-08-19T09:00:00Z"), message = message,
    )

    @Test
    fun sendErzeugtEineNotificationAufDemRichtigenKanalMitTitelUndText() = runTest {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        val channel = LocalNotificationAlertChannel(context, notificationPermissionOverride = true)

        val ergebnis = channel.send(alarm(message = "Verbindung seit 09:00 unterbrochen"))

        assertTrue("send() darf nicht fehlschlagen", ergebnis.isSuccess)
        val aktive = shadowOf(manager).activeNotifications
        assertEquals("Es muss genau eine Alarm-Notification stehen", 1, aktive.size)
        val notification = aktive.single().notification
        assertEquals(ALARM_NOTIFICATION_CHANNEL_ID, notification.channelId)
        assertEquals(
            "Lärmprotokoll: Verbindung verloren",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(
            "Verbindung seit 09:00 unterbrochen",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun derAlarmkanalWirdMitHoherWichtigkeitAngelegt() = runTest {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        val channel = LocalNotificationAlertChannel(context, notificationPermissionOverride = true)

        channel.send(alarm())

        val kanal = manager.getNotificationChannel(ALARM_NOTIFICATION_CHANNEL_ID)
        assertEquals(
            "Ein Verbindungsalarm darf nicht in einem leisen Kanal untergehen",
            NotificationManager.IMPORTANCE_HIGH, kanal.importance,
        )
    }

    @Test
    fun resolvedBekommtEinenEigenenTitelUndText() = runTest {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        val channel = LocalNotificationAlertChannel(context, notificationPermissionOverride = true)

        channel.send(alarm(kind = AlertKind.RESOLVED, message = "Der Ausfall bestand seit 09:00."))

        val notification = shadowOf(manager).activeNotifications.single().notification
        assertEquals(
            "Lärmprotokoll: Verbindung wieder da",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun wiederholterVersandErsetztDieVorherigeMeldungStattSichDanebenZuStapeln() = runTest {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        val channel = LocalNotificationAlertChannel(context, notificationPermissionOverride = true)

        channel.send(alarm(kind = AlertKind.RAISED, message = "Erster Alarm"))
        channel.send(alarm(kind = AlertKind.RESOLVED, message = "Entwarnung"))

        val aktive = shadowOf(manager).activeNotifications
        assertEquals(
            "Die Entwarnung muss die stehende Alarmmeldung ersetzen (feste Notification-ID), " +
                "sonst stuenden am Morgen Alarm und Entwarnung nebeneinander",
            1, aktive.size,
        )
        assertEquals(
            "Entwarnung",
            aktive.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun mustertDauertMindestensDieAngeforderteZeit() {
        val muster = alarmVibrationsmuster(mindestdauerMillis = 60_000L)
        // muster[0] ist die Anfangsverzoegerung (0), der Rest wechselt vibrieren/pause -
        // die Summe AB Index 1 ist die tatsaechliche Gesamtdauer.
        val gesamtdauer = muster.drop(1).sum()
        assertTrue("Gesamtdauer $gesamtdauer sollte mindestens 60000ms betragen", gesamtdauer >= 60_000L)
    }

    @Test
    fun beginntOhneVerzoegerung() {
        val muster = alarmVibrationsmuster()
        assertEquals(0L, muster[0])
    }

    @Test
    fun wechseltStrengZwischenVibrierenUndPause() {
        val muster = alarmVibrationsmuster(mindestdauerMillis = 5_000L)
        // Ab Index 1: ungerade Positionen (1,3,5,...) sind Vibrieren, gerade (2,4,6,...) Pause -
        // beide muessen positiv sein, sonst gaebe es Luecken ohne Wirkung oder Dauervibration.
        for (i in 1 until muster.size step 2) {
            assertTrue("Vibrations-Segment bei Index $i muss positiv sein", muster[i] > 0)
        }
        for (i in 2 until muster.size step 2) {
            assertTrue("Pause-Segment bei Index $i muss positiv sein", muster[i] > 0)
        }
    }

    @Test
    fun kuerzereMindestdauerErgibtKuerzeresMuster() {
        val kurz = alarmVibrationsmuster(mindestdauerMillis = 1_200L)
        val lang = alarmVibrationsmuster(mindestdauerMillis = 60_000L)
        assertTrue(kurz.size < lang.size)
    }

    @Test
    fun actionStopAlarmKonstanteIstDefiniert() {
        assertEquals("com.example.lrmprotokoll.ACTION_STOP_ALARM", ACTION_STOP_ALARM)
    }
}
