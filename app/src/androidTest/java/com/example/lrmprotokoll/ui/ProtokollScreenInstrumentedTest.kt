package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für die Protokollübersicht ([ProtokollScreen]) gemäß Testplan.
 *
 * Prüft den Leerzustand ("Noch keine aufgezeichnete Überwachungsperiode...") und die Navigation.
 */
@RunWith(AndroidJUnit4::class)
class ProtokollScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.database.clearAllTables()
    }

    @Test
    fun protokollScreenZeigtTitelUndLeerzustandWennKeineSessionsExistieren() {
        var backed = false
        var openedSessionId: Long? = null

        composeRule.setContent {
            ProtokollScreen(
                onBack = { backed = true },
                onOpenSession = { openedSessionId = it }
            )
        }
        composeRule.waitForIdle()

        // 1. Titel "Protokoll" in TopAppBar sichtbar
        composeRule.onNodeWithText("Protokoll").assertIsDisplayed()

        // 2. Leerzustandstext sichtbar
        composeRule.onNodeWithText(
            "Noch keine aufgezeichnete Überwachungsperiode",
            substring = true
        ).assertIsDisplayed()

        // 3. Zurück-Button klickbar
        composeRule.onNodeWithContentDescription("Zurück").assertIsDisplayed().performClick()
        assertTrue(backed)
    }
}
