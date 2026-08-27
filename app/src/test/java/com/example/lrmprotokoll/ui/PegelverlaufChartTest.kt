package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.ChartSpalte
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PegelverlaufChartTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chartRendersEmptyStateWhenNoData() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                PegelverlaufChart(
                    spalten = emptyList(),
                    ausfallbaender = emptyList(),
                    sessionStart = 1000L,
                    sessionEnde = 2000L
                )
            }
        }
        composeRule.waitForIdle()

        val noDataText = composeRule.activity.getString(com.example.lrmprotokoll.R.string.protocol_detail_no_chart_data)
        composeRule.onNodeWithText(noDataText).assertIsDisplayed()
    }

    @Test
    fun chartRendersWithDataAndEvents() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        val spalten = listOf(
            ChartSpalte(zeitOffsetSekunden = 0, minDb = 45.0, maxDb = 65.0, mittelDb = 55.0, anzahl = 10),
            ChartSpalte(zeitOffsetSekunden = 60, minDb = 48.0, maxDb = 72.0, mittelDb = 60.0, anzahl = 10),
            ChartSpalte(zeitOffsetSekunden = 120, minDb = 42.0, maxDb = 50.0, mittelDb = 46.0, anzahl = 10),
        )
        val ausfall = listOf(Ausfallband(von = 1000L + 30_000L, bis = 1000L + 50_000L))
        val event = listOf(
            NoiseRecord(
                id = 1L,
                timestamp = 1000L + 60_000L,
                amplitude = 0.5,
                dbValue = 72.0,
                filePath = "",
                label = "Bohren",
                calibratedDbA = 72.0
            )
        )

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                PegelverlaufChart(
                    spalten = spalten,
                    ausfallbaender = ausfall,
                    sessionStart = 1000L,
                    sessionEnde = 1000L + 120_000L,
                    events = event,
                    thresholdDb = 60.0,
                    laeqDb = 54.2
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun chartTraegtEineContentDescriptionMitDenKernwertenFuerScreenreader() {
        // PROMPT_M9_UX.md Aufgabe 4: das Canvas zeichnet nur Pixel, ohne contentDescription
        // sieht ein Screenreader nichts davon - hier der Beweis, dass die Beschreibung
        // tatsaechlich am Chart-Container haengt und die richtigen Werte enthaelt (aktueller
        // Pegel = letzte Spalte, Mittelwert = laeqDb, Hoechstwert = Max ueber alle Spalten,
        // Ausfallzahl = Groesse von ausfallbaender).
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        val spalten = listOf(
            ChartSpalte(zeitOffsetSekunden = 0, minDb = 45.0, maxDb = 65.0, mittelDb = 55.0, anzahl = 10),
            ChartSpalte(zeitOffsetSekunden = 60, minDb = 48.0, maxDb = 72.0, mittelDb = 60.0, anzahl = 10),
            ChartSpalte(zeitOffsetSekunden = 120, minDb = 42.0, maxDb = 50.0, mittelDb = 46.0, anzahl = 10),
        )
        val ausfall = listOf(Ausfallband(von = 1030_000L, bis = 1050_000L))

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                PegelverlaufChart(
                    spalten = spalten,
                    ausfallbaender = ausfall,
                    sessionStart = 1000L,
                    sessionEnde = 1000L + 120_000L,
                    laeqDb = 54.2,
                )
            }
        }
        composeRule.waitForIdle()

        // Dieselbe Formatierung wie der Produktivcode (String.format ueber die Resource-API,
        // nicht ein hartcodiertes Dezimaltrennzeichen) - sonst haengt der Test vom
        // Standard-Locale des Testlaufs ab.
        val erwarteteBeschreibung = composeRule.activity.getString(
            com.example.lrmprotokoll.R.string.chart_content_description,
            46.0, 54.2, 72.0, 1,
        )
        composeRule.onNodeWithContentDescription(erwarteteBeschreibung).assertIsDisplayed()
    }

    @Test
    fun chartOhneUebergebenesLaeqNutztDenSpaltenMittelwertAlsNaeherung() {
        // Gegenprobe zum Fallback: kein laeqDb -> arithmetisches Mittel der Spalten-Mittelwerte
        // (50 + 60) / 2 = 55.0, nicht 0 oder ein Absturz.
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        val spalten = listOf(
            ChartSpalte(zeitOffsetSekunden = 0, minDb = 45.0, maxDb = 65.0, mittelDb = 50.0, anzahl = 10),
            ChartSpalte(zeitOffsetSekunden = 60, minDb = 48.0, maxDb = 72.0, mittelDb = 60.0, anzahl = 10),
        )

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                PegelverlaufChart(
                    spalten = spalten,
                    ausfallbaender = emptyList(),
                    sessionStart = 1000L,
                    sessionEnde = 1000L + 60_000L,
                )
            }
        }
        composeRule.waitForIdle()

        val erwarteteBeschreibung = composeRule.activity.getString(
            com.example.lrmprotokoll.R.string.chart_content_description,
            60.0, 55.0, 72.0, 0,
        )
        composeRule.onNodeWithContentDescription(erwarteteBeschreibung).assertIsDisplayed()
    }
}
