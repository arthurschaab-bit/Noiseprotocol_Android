package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkNoiseEventBottomSheetTest {

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

        // 1. Titel & Header
        val titleStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.cockpit_mark_noise_event)
        composeRule.onNodeWithText(titleStr).assertIsDisplayed()

        val hammerStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.category_hammering)
        val drillStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.category_drilling)
        val footStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.category_footsteps)
        val voiceStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.category_voices)
        val notePlaceholder = composeRule.activity.getString(com.example.lrmprotokoll.R.string.mark_event_note_placeholder)

        // 2. Kategorien prüfen
        composeRule.onNodeWithText(hammerStr).assertIsDisplayed()
        composeRule.onNodeWithText(drillStr).assertIsDisplayed()
        composeRule.onNodeWithText(footStr).assertIsDisplayed()
        composeRule.onNodeWithText(voiceStr).assertIsDisplayed()

        // 3. Kategorie "Drilling" auswählen
        composeRule.onNodeWithText(drillStr).performClick()
        composeRule.waitForIdle()

        // 4. Notiz eingeben
        composeRule.onNodeWithText(notePlaceholder).performTextInput("Bauarbeiten Nachbar")
        composeRule.waitForIdle()

        // 5. Speichern
        composeRule.onNodeWithTag(SAVE_NOISE_EVENT_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(drillStr, savedCategory)
        assertEquals("Bauarbeiten Nachbar", savedNote)
        assertEquals(true, dismissed)
    }
}
