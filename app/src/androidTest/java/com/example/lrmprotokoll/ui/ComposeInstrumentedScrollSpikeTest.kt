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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LETZTE_ZEILE_TAG = "letzte_zeile"

/**
 * Echtes Geraete-Pendant zu [ComposeRobolectricSpikeTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026), auf Owner-Wunsch
 * fuer volle Paritaet mit ergaenzt. Anders als das Robolectric-Original (das erst belegen musste,
 * ob Compose-UI-Tests unter Robolectric ueberhaupt funktionieren) testet dieser Spike auf einem
 * echten Geraet keine offene Frage - Scrollen funktioniert dort erwartungsgemaess. Der Wert liegt
 * allein darin, dieselbe verticalScroll-Erreichbarkeits-Bug-Klasse (siehe SettingsScreen.kt,
 * PR #27) auch im Instrumented-Testlauf abgedeckt zu haben, nicht nur unter Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class ComposeInstrumentedScrollSpikeTest {

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

        composeRule.onNodeWithTag(LETZTE_ZEILE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test(expected = AssertionError::class)
    fun ohneVerticalScrollIstDieLetzteZeileNichtErreichbar() {
        composeRule.setContent { LangerInhalt(mitScroll = false) }

        composeRule.onNodeWithTag(LETZTE_ZEILE_TAG).assertIsDisplayed()
    }
}
