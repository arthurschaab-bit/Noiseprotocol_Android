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
        composeRule.onNodeWithText("Mark noise event").assertIsDisplayed()

        // 2. Kategorien prüfen
        composeRule.onNodeWithText("Hammering").assertIsDisplayed()
        composeRule.onNodeWithText("Drilling").assertIsDisplayed()
        composeRule.onNodeWithText("Footsteps").assertIsDisplayed()
        composeRule.onNodeWithText("Voices").assertIsDisplayed()

        // 3. Kategorie "Drilling" auswählen
        composeRule.onNodeWithText("Drilling").performClick()
        composeRule.waitForIdle()

        // 4. Notiz eingeben
        composeRule.onNodeWithText("Describe the noise source...").performTextInput("Bauarbeiten Nachbar")
        composeRule.waitForIdle()

        // 5. Speichern
        composeRule.onNodeWithTag(SAVE_NOISE_EVENT_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals("Drilling", savedCategory)
        assertEquals("Bauarbeiten Nachbar", savedNote)
        assertEquals(true, dismissed)
    }
}
