package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [MarkNoiseEventBottomSheetTest] (Robolectric, app/src/test) - Teil
 * der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026). Portiert
 * dieselbe Interaktionskette (Kategorie waehlen, Notiz eintippen, speichern) 1:1 auf einen echten
 * connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class MarkNoiseEventBottomSheetInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bottomSheetRendersCategoriesAndSavesEvent() {
        var savedCategory: String? = null
        var savedNote: String? = null
        var dismissed = false

        composeRule.setContent {
            MarkNoiseEventBottomSheet(
                currentDb = 64.2,
                currentWeighting = "dB(A)",
                onSaveEvent = { cat, note ->
                    savedCategory = cat
                    savedNote = note
                },
                onDismiss = { dismissed = true }
            )
        }
        composeRule.waitForIdle()

        val titleStr = composeRule.activity.getString(R.string.cockpit_mark_noise_event)
        composeRule.onNodeWithText(titleStr).assertIsDisplayed()

        val hammerStr = composeRule.activity.getString(R.string.category_hammering)
        val drillStr = composeRule.activity.getString(R.string.category_drilling)
        val footStr = composeRule.activity.getString(R.string.category_footsteps)
        val voiceStr = composeRule.activity.getString(R.string.category_voices)
        val notePlaceholder = composeRule.activity.getString(R.string.mark_event_note_placeholder)

        // Regressionsklasse Datumsbereich-Dialog: alle Kategorien und der Speichern-Button
        // muessen auf dem echten Bildschirm sichtbar sein, nicht nur im Semantics-Baum existieren.
        composeRule.onNodeWithText(hammerStr).assertIsDisplayed()
        composeRule.onNodeWithText(drillStr).assertIsDisplayed()
        composeRule.onNodeWithText(footStr).assertIsDisplayed()
        composeRule.onNodeWithText(voiceStr).assertIsDisplayed()
        composeRule.onNodeWithTag(SAVE_NOISE_EVENT_BUTTON_TAG).assertIsDisplayed()

        composeRule.onNodeWithText(drillStr).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(notePlaceholder).performTextInput("Bauarbeiten Nachbar")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SAVE_NOISE_EVENT_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(drillStr, savedCategory)
        assertEquals("Bauarbeiten Nachbar", savedNote)
        assertEquals(true, dismissed)
    }
}
