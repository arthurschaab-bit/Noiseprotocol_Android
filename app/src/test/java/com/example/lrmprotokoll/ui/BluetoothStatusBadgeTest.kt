package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.lrmprotokoll.meter.ConnectionState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BluetoothStatusBadgeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun zeigtGeraetenamenBeiStreamingAn() {
        var clicked = false
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.STREAMING,
                deviceName = "PCE-323",
                onClick = { clicked = true }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("PCE-323").assertIsDisplayed().performClick()
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

        composeRule.onNodeWithText("BT: Suche…").assertIsDisplayed()
    }

    @Test
    fun zeigtAusBeiIdleAn() {
        composeRule.setContent {
            BluetoothStatusBadge(
                state = ConnectionState.IDLE,
                deviceName = null
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("BT: Aus").assertIsDisplayed()
    }
}
