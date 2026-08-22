package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
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
class LiveCockpitCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun liveCockpitCardRendersStartButtonWhenInactive() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                LiveCockpitCard()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Start measurement").assertIsDisplayed()
        composeRule.onNodeWithText("Ready to measure").assertIsDisplayed()
    }

    @Test
    fun quickEventTagDialogRendersCategories() {
        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                QuickEventTagContent(
                    currentDb = 65.4,
                    onSave = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ereignis jetzt speichern").assertIsDisplayed()
        composeRule.onNodeWithText("🔨 Hämmern").assertIsDisplayed()
    }
}
