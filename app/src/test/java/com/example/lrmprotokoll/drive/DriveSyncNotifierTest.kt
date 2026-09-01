package com.example.lrmprotokoll.drive

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.alert.local.ALARM_NOTIFICATION_CHANNEL_ID
import com.example.lrmprotokoll.data.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Testluecken-Auftrag Stufe 3: [DriveSyncNotifier] war bisher komplett ungetestet. Wichtigster
 * Punkt aus dem Klassen-KDoc (Plan 8.4.6): ein Sync-Problem darf NICHT ueber denselben
 * IMPORTANCE_HIGH-Alarmkanal wie ein Verbindungsabbruch laufen, sonst stumpft der echte Alarm ab -
 * deshalb wird hier explizit auf IMPORTANCE_DEFAULT und einen eigenen Kanal geprueft, nicht nur
 * darauf, dass irgendeine Notification erscheint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveSyncNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var settings: SettingsManager
    private lateinit var notifier: DriveSyncNotifier

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        settings = SettingsManager(context)
        notifier = DriveSyncNotifier(context)
    }

    @Test
    fun unterDerSchwelleWirdNichtBenachrichtigt() {
        settings.driveSyncFehlschlaegeInFolge = 5 // Schwelle ist 6

        notifier.pruefeUndBenachrichtige(settings)

        assertEquals(0, shadowOf(manager).activeNotifications.size)
    }

    @Test
    fun abDerSchwelleWirdAufEinemEigenenRuhigenKanalBenachrichtigt() {
        settings.driveSyncFehlschlaegeInFolge = 6

        notifier.pruefeUndBenachrichtige(settings)

        val aktive = shadowOf(manager).activeNotifications
        assertEquals(1, aktive.size)
        val notification = aktive.single().notification
        assertTrue(
            "Ein Sync-Problem darf nicht ueber den Verbindungsalarm-Kanal laufen - sonst stumpft " +
                "der echte Alarm ab (Plan 8.4.6)",
            notification.channelId != ALARM_NOTIFICATION_CHANNEL_ID,
        )
        val kanal = manager.getNotificationChannel(notification.channelId)
        assertEquals(
            "Ein Sync-Problem ist aergerlich, aber kein Alarmfall - IMPORTANCE_HIGH waere hier " +
                "gleichbedeutend mit dem echten Verbindungsalarm",
            NotificationManager.IMPORTANCE_DEFAULT, kanal.importance,
        )
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("6"),
        )
    }

    @Test
    fun ordnerNichtGefundenBenachrichtigtUnabhaengigVomFehlschlagszaehler() {
        settings.driveSyncFehlschlaegeInFolge = 0

        notifier.ordnerNichtGefunden()

        val aktive = shadowOf(manager).activeNotifications
        assertEquals(1, aktive.size)
        assertEquals(
            "Drive-Ordner nicht gefunden",
            aktive.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun syncFehlerUndOrdnerWegSindZweiUnabhaengigeMeldungenNichtEinsGegenAndereErsetzt() {
        settings.driveSyncFehlschlaegeInFolge = 6

        notifier.pruefeUndBenachrichtige(settings)
        notifier.ordnerNichtGefunden()

        assertEquals(
            "Beide Meldungen betreffen unterschiedliche Probleme und muessen nebeneinander " +
                "sichtbar bleiben",
            2, shadowOf(manager).activeNotifications.size,
        )
    }
}
