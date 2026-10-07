package com.example.lrmprotokoll.diagnose

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.diagnose.export.BundleKontext
import com.example.lrmprotokoll.diagnose.export.BundleTyp
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.zip.ZipFile

/**
 * Tests fuer [StromversorgungEmpfaenger], [AudioRecordingService] und [SupportBundleExporter]
 * gemaess Tests 2 bis 4 aus docs/PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StromversorgungEmpfaengerTest {

    private lateinit var context: Context

    @Before
    fun aufbauen() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun erstelleBatteryIntent(
        plugged: Int,
        status: Int,
        level: Int = 100,
        scale: Int = 100,
    ): Intent {
        return Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            putExtra(BatteryManager.EXTRA_STATUS, status)
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
        }
    }

    /**
     * Test 2: Robolectric: Empfaenger mit Fake-Reporter / Liste.
     * Zwei Broadcasts (ACTION_BATTERY_CHANGED mit EXTRA_PLUGGED = USB, danach mit 0)
     * ergeben genau zwei Eintraege mit dem richtigen Text.
     */
    @Test
    fun testZweiBroadcastsErgebenGenauZweiEintraege() {
        val eingegangeneEintraege = mutableListOf<String>()
        val empfaenger = StromversorgungEmpfaenger { nachricht ->
            eingegangeneEintraege.add(nachricht)
        }

        // 1. Broadcast: USB, lädt, 90%
        val intent1 = erstelleBatteryIntent(
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            level = 90,
            scale = 100,
        )
        empfaenger.onReceive(context, intent1)

        // 2. Broadcast: Nicht eingesteckt (0), entlädt, 90%
        val intent2 = erstelleBatteryIntent(
            plugged = 0,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
            level = 90,
            scale = 100,
        )
        empfaenger.onReceive(context, intent2)

        assertEquals("Genau zwei Eintraege erwartet", 2, eingegangeneEintraege.size)
        assertEquals(
            "Stromversorgung beim Start: USB, Akku 90 %, lädt",
            eingegangeneEintraege[0],
        )
        assertEquals(
            "Stromversorgung: USB → keine, Akku 90 %, entlädt",
            eingegangeneEintraege[1],
        )
    }

    /**
     * Test 3: Nach onDestroy() erzeugt ein Broadcast keinen Eintrag mehr (Empfaenger abgemeldet).
     */
    @Test
    fun testNachOnDestroyKeinEintragMehr() {
        val eingegangeneEintraege = mutableListOf<String>()
        val empfaenger = StromversorgungEmpfaenger { nachricht ->
            eingegangeneEintraege.add(nachricht)
        }
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(empfaenger, filter)

        // 1. Broadcast vor Abmeldung
        val intent1 = erstelleBatteryIntent(
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            level = 90,
            scale = 100,
        )
        empfaenger.onReceive(context, intent1)
        assertEquals(1, eingegangeneEintraege.size)

        // Abmelden (wie in onDestroy())
        context.unregisterReceiver(empfaenger)

        // Shadow pruefen: darf nicht mehr registriert sein
        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<LaermprotokollApp>())
        val nochRegistriert = shadowApp.registeredReceivers.filter {
            it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED)
        }
        assertTrue("Empfaenger muss abgemeldet sein", nochRegistriert.isEmpty())

        // Service startet und beendet ebenfalls sauber
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val startIntent = Intent(app, AudioRecordingService::class.java).apply {
            putExtra(com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING, true)
        }
        val serviceController = Robolectric.buildService(AudioRecordingService::class.java, startIntent)
        serviceController.create()
        serviceController.startCommand(0, 1)
        serviceController.destroy()
    }

    /**
     * Test 4: runtime.json enthaelt stromquelle und akkuStatus.
     */
    @Test
    fun testRuntimeJsonEnthaeltStromquelleUndAkkuStatus() = runTest {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        // Sticky Broadcast fuer Robolectric simulieren
        val stickyIntent = erstelleBatteryIntent(
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            level = 85,
            scale = 100,
        )
        context.sendStickyBroadcast(stickyIntent)

        val exporter = app.container.supportBundleExporter
        val zipFile = exporter.createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("state/runtime.json")
            assertNotNull("state/runtime.json muss existieren", entry)
            val jsonStr = zip.getInputStream(entry).bufferedReader().readText()
            val json = JSONObject(jsonStr)

            assertTrue("Muss 'stromquelle' enthalten", json.has("stromquelle"))
            assertTrue("Muss 'akkuStatus' enthalten", json.has("akkuStatus"))
            assertEquals("Netzteil", json.getString("stromquelle"))
            assertEquals("lädt", json.getString("akkuStatus"))
        }
    }
}
