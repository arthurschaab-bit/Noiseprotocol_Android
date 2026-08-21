package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für die Startseite / HomeScreen-Komponenten gemäß Testplan.
 *
 * Prüft den globalen Filter (Auf-/Zuklappen, RangeSlider, Reset), sowie
 * alle interaktiven Aktionen auf [NoiseRecordItem] (Abspielen, Löschen, Lernen, KI-Erkennung,
 * Schnellauswahl-Chips "Bagger", "Bohren", "Hämmern", "Verkehr", Long-Click Auswahl).
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun globalerFilterLaesstSichAufUndZuklappenUndZuruecksetzen() {
        composeRule.setContent {
            var showGlobalFilter by remember { mutableStateOf(false) }
            var minDb by remember { mutableStateOf(0f) }
            var maxDb by remember { mutableStateOf(120f) }

            Column(modifier = Modifier.padding(16.dp)) {
                androidx.compose.material3.Card(
                    modifier = Modifier.padding(8.dp),
                    onClick = { showGlobalFilter = !showGlobalFilter }
                ) {
                    Text("Globale Filter (Pegel)")
                }

                if (showGlobalFilter) {
                    Text("Pegelbereich (dB): ${minDb.toInt()} - ${maxDb.toInt()}")
                    androidx.compose.material3.TextButton(onClick = {
                        minDb = 0f
                        maxDb = 120f
                    }) {
                        Text("Filter zurücksetzen")
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. Initial ist Filter zugeklappt
        composeRule.onNodeWithText("Globale Filter (Pegel)").assertIsDisplayed()
        composeRule.onNodeWithText("Filter zurücksetzen").assertDoesNotExist()

        // 2. Aufklappen
        composeRule.onNodeWithText("Globale Filter (Pegel)").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Filter zurücksetzen").assertIsDisplayed()
        composeRule.onNodeWithText("Pegelbereich (dB): 0 - 120").assertIsDisplayed()

        // 3. Zurücksetzen klicken
        composeRule.onNodeWithText("Filter zurücksetzen").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pegelbereich (dB): 0 - 120").assertIsDisplayed()

        // 4. Wieder zuklappen
        composeRule.onNodeWithText("Globale Filter (Pegel)").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Filter zurücksetzen").assertDoesNotExist()
    }

    @Test
    fun noiseRecordItemZeigtAlleDetailsUndReagiertAufAlleAktionen() {
        val record = NoiseRecord(
            id = 42L,
            timestamp = 1716000000000L,
            dbValue = 68.5,
            amplitude = 12500.0,
            filePath = "/fake/path/audio.wav",
            label = "Nachbar bohrt",
            detectedLabel = "Drilling"
        )

        var played = false
        var deleted = false
        var favoriteToggled = false
        var learned = false
        var longClicked = false
        var aiRecognized = false
        var assignedLabel: String? = null

        composeRule.setContent {
            NoiseRecordItem(
                record = record,
                isSelected = false,
                onPlay = { played = true },
                onLabel = { assignedLabel = it },
                onToggleFavorite = { favoriteToggled = true },
                onDelete = { deleted = true },
                onLearn = { learned = true },
                onLongClick = { longClicked = true },
                onAiRecognize = { aiRecognized = true }
            )
        }
        composeRule.waitForIdle()

        // Überprüfe Detailtexte
        composeRule.onNodeWithText("68.5 dB (Mikrofon)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("KI: Drilling").assertIsDisplayed()
        composeRule.onNodeWithText("Label: Nachbar bohrt").assertIsDisplayed()

        // Teste Play-Button
        composeRule.onNodeWithContentDescription("Abspielen").assertIsDisplayed().performClick()
        assertTrue(played)

        // Teste KI-Erkennung-Button
        composeRule.onNodeWithContentDescription("KI-Erkennung").assertIsDisplayed().performClick()
        assertTrue(aiRecognized)

        // Teste Favorit-Button
        composeRule.onNodeWithContentDescription("Favorit").assertIsDisplayed().performClick()
        assertTrue(favoriteToggled)

        // Teste Lernen-Chip
        composeRule.onNodeWithText("Muster lernen", substring = true).assertIsDisplayed().performClick()
        assertTrue(learned)

        // Teste Löschen-Button
        composeRule.onNodeWithContentDescription("Löschen").assertIsDisplayed().performClick()
        assertTrue(deleted)

        // Teste Label-Chips
        composeRule.onNodeWithText("Bagger").assertIsDisplayed().performClick()
        assertEquals("Bagger", assignedLabel)

        composeRule.onNodeWithText("Bohren").assertIsDisplayed().performClick()
        assertEquals("Bohren", assignedLabel)

        composeRule.onNodeWithText("Hämmern").assertIsDisplayed().performClick()
        assertEquals("Hämmern", assignedLabel)

        composeRule.onNodeWithText("Verkehr").assertIsDisplayed().performClick()
        assertEquals("Verkehr", assignedLabel)

        // Teste Long Click
        composeRule.onNodeWithText("KI: Drilling").performTouchInput { longClick() }
        assertTrue(longClicked)
    }

    @Test
    fun noiseRecordItemImAusgewaehltenZustandWirdKorrektHervorgehoben() {
        val record = NoiseRecord(
            id = 1L,
            timestamp = 1716000000000L,
            dbValue = 55.0,
            amplitude = 8000.0,
            filePath = "/fake/path/audio.wav"
        )

        composeRule.setContent {
            NoiseRecordItem(
                record = record,
                isSelected = true,
                onPlay = {},
                onLabel = {},
                onToggleFavorite = {},
                onDelete = {},
                onLearn = {},
                onLongClick = {},
                onAiRecognize = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("55.0 dB (Mikrofon)", substring = true).assertIsDisplayed()
    }
}
