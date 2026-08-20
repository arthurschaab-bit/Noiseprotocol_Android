package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für M7c Aufgabe 1: Das Home-Dashboard (`ServiceControl`) zeigte den
 * Monitoring-Status bisher nur als einmaligen `ActivityManager.getRunningServices()`-Snapshot -
 * ein Servicestart/-stopp, während der Screen offen war, blieb unsichtbar. Jetzt beobachtet es
 * [AudioRecordingService.laeuft] (StateFlow) direkt per collectAsState().
 *
 * Setzt den StateFlow selbst, statt den echten Service zu starten (der bräuchte Mikrofon-
 * Hardware) - reicht, um zu belegen, dass die UI auf eine Änderung dieser Quelle reagiert, ohne
 * neu geöffnet zu werden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServiceControlComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun statusWechseltLiveOhneDenScreenNeuZuOeffnen() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {}, onNavigateToSettings = {}, onNavigateToMeter = {},
                onNavigateToProtokoll = {}, onNavigateToDiagnose = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Inaktiv").assertIsDisplayed()

        AudioRecordingService.testSetzeLaeuft(true)

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("AKTIV").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("AKTIV").assertIsDisplayed()

        AudioRecordingService.testSetzeLaeuft(false)
    }
}
