package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val LETZTE_ZEILE_TAG = "letzte_zeile"

/**
 * Spike (Owner-Auftrag nach dem SettingsScreen-Scroll-Bug, siehe PR #27): funktionieren
 * Compose-UI-Tests unter Robolectric in diesem Projekt ueberhaupt? Das Projekt hat mit
 * AndroidKeyStore (M6) und PdfDocument (M7) bereits zwei echte Robolectric-Plattformluecken
 * erlebt - hier wird das NICHT angenommen, sondern belegt.
 *
 * Zwei Faelle in einem winzigen, festen Viewport (200x400dp): ein Screen mit verticalScroll,
 * dessen letzte Zeile erreichbar sein muss (Regressionstest fuer genau die Bug-Klasse aus
 * SettingsScreen.kt), und die Gegenprobe ohne verticalScroll, bei der dieselbe Zeile
 * NICHT sichtbar sein darf.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeRobolectricSpikeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun LangerInhalt(mitScroll: Boolean) {
        var modifier = Modifier.size(200.dp, 400.dp)
        if (mitScroll) {
            modifier = modifier.verticalScroll(rememberScrollState())
        }
        Column(modifier = modifier) {
            repeat(30) { index ->
                val zeilenModifier = if (index == 29) Modifier.testTag(LETZTE_ZEILE_TAG) else Modifier
                Text("Zeile $index", modifier = zeilenModifier.height(40.dp))
            }
        }
    }

    @Test
    fun mitVerticalScrollIstDieLetzteZeileErreichbar() {
        composeRule.setContent { LangerInhalt(mitScroll = true) }

        // assertIsDisplayed() allein scrollt nicht automatisch - es prueft nur den aktuellen
        // Sichtbarkeitszustand. Reichbarkeit "kann man dahin scrollen" ist performScrollTo();
        // erst danach ist assertIsDisplayed() die richtige Pruefung.
        composeRule.onNodeWithTag(LETZTE_ZEILE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test(expected = AssertionError::class)
    fun ohneVerticalScrollIstDieLetzteZeileNichtErreichbar() {
        composeRule.setContent { LangerInhalt(mitScroll = false) }

        composeRule.onNodeWithTag(LETZTE_ZEILE_TAG).assertIsDisplayed()
    }
}
