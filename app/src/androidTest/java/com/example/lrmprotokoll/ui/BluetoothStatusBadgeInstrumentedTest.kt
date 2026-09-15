package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.meter.ConnectionState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [BluetoothStatusBadgeTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026). Reiner,
 * isolierter Komponententest ohne Dialog/Fenster-Kontext - fuer die konkrete Bug-Klasse (echter
 * Fensterueberlauf) strukturell nicht relevant, aber auf Owner-Wunsch fuer volle Paritaet mit
 * ergaenzt.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothStatusBadgeInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun zeigtGeraetenamenUndVerbundenBeiStreamingAn() {
        var clicked = false
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.STREAMING,
                deviceName = "PCE-323",
                onClick = { clicked = true }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323: Verbunden").assertIsDisplayed().performClick()
        assertTrue("Badge-Klick-Callback muss ausgefuehrt werden", clicked)
    }

    @Test
    fun zeigtVerbindungsstatusBeiScanningAn() {
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.SCANNING,
                deviceName = null
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323: Suche…").assertIsDisplayed()
    }

    @Test
    fun zeigtNichtVerbundenBeiIdleAn() {
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.IDLE,
                deviceName = null
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323: Nicht verbunden").assertIsDisplayed()
    }
}
