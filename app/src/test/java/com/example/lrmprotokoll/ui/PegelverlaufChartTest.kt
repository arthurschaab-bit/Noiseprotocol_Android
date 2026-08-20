package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.ChartSpalte
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PegelverlaufChartTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun leereSpaltenZeigenHinweistext() {
        composeRule.setContent {
            PegelverlaufChart(
                spalten = emptyList(),
                ausfallbaender = emptyList(),
                sessionStart = 1_700_000_000_000L,
                sessionEnde = 1_700_000_010_000L,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Keine Messwerte für den Pegelverlauf.").assertIsDisplayed()
    }

    @Test
    fun mitSpaltenWirdChartOhneFehlerGerendert() {
        val spalten = listOf(
            ChartSpalte(zeitOffsetSekunden = 0, minDb = 40.0, maxDb = 50.0, mittelDb = 45.0, anzahl = 10),
            ChartSpalte(zeitOffsetSekunden = 10, minDb = 42.0, maxDb = 55.0, mittelDb = 48.0, anzahl = 10),
            ChartSpalte(zeitOffsetSekunden = 20, minDb = 38.0, maxDb = 60.0, mittelDb = 52.0, anzahl = 10),
        )
        val ausfallbaender = listOf(
            Ausfallband(von = 1_700_000_005_000L, bis = 1_700_000_008_000L)
        )

        composeRule.setContent {
            PegelverlaufChart(
                spalten = spalten,
                ausfallbaender = ausfallbaender,
                sessionStart = 1_700_000_000_000L,
                sessionEnde = 1_700_000_030_000L,
                isLive = true
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Keine Messwerte für den Pegelverlauf.").assertDoesNotExist()
    }
}
