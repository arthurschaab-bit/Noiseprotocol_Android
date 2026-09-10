package com.example.lrmprotokoll.ui

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import com.example.lrmprotokoll.meter.ble.BluetoothPermissions
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressionstest fuer dieselbe Fehlerklasse wie #129 (siehe
 * [FotoDokumentationSheetPermissionInstrumentedTest]), hier fuer den Scan-Button in
 * [MeterScreen]: ohne BLUETOOTH_SCAN/BLUETOOTH_CONNECT (API 31+) darf der Button nicht einfach
 * nichts tun, sondern muss danach fragen - siehe [BluetoothPermissions.requiredPermissions].
 *
 * Beschraenkt auf API 31+ ([Build.VERSION_CODES.S]): Vor API 31 laeuft die Berechtigung ueber
 * `ACCESS_FINE_LOCATION` (Legacy-Bluetooth-Pfad, siehe [BluetoothPermissions]) - der CI-Emulator
 * (siehe `emulator-tests.yml`) ist auf API 34 gepinnt, dieser Test deckt also den tatsaechlich
 * geprueften Pfad ab.
 *
 * **Testtiefe:** [BerechtigungsTestHelfer.erlaubeFallsVorhanden] wird bewusst bis zu zweimal
 * aufgerufen - BLUETOOTH_SCAN und BLUETOOTH_CONNECT werden ueber
 * `RequestMultiplePermissions()` auf einmal angefragt, ob Android dafuer einen gemeinsamen oder
 * zwei aufeinanderfolgende Dialoge zeigt, ist geraete-/versionsabhaengig und in dieser Umgebung
 * nicht vorab pruefbar (kein Emulator lokal verfuegbar) - siehe PR-Beschreibung.
 */
@RunWith(AndroidJUnit4::class)
class MeterScreenPermissionInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun vorbedingungApi31Plus() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }

    @org.junit.After
    fun tearDown() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.BLUETOOTH_SCAN)
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test
    fun ohneBerechtigungFragtDerScanButtonErstNachUndBesitztDanachDieBerechtigung() {
        BerechtigungsTestHelfer.entziehe(Manifest.permission.BLUETOOTH_SCAN)
        BerechtigungsTestHelfer.entziehe(Manifest.permission.BLUETOOTH_CONNECT)
        composeRule.setContent { MeterScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SCAN_BUTTON_TAG).performClick()
        // Der eigentliche #129-Nachweis: ohne dass der Button tatsaechlich den echten
        // Systemdialog ausloest, kommt dieser Aufruf nie durch.
        repeat(2) { BerechtigungsTestHelfer.erlaubeFallsVorhanden() }

        composeRule.waitUntil(timeoutMillis = 5_000L) { BluetoothPermissions.hasPermissions(context) }
        assertTrue(BluetoothPermissions.hasPermissions(context))
    }
}
