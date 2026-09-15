package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Geraetetest-Checkliste F6: [KiErklaerungScreen] war bislang kompiliert und lint-sauber, aber
 * noch nie auf einem echten Bildschirm gesehen worden.
 */
@RunWith(AndroidJUnit4::class)
class KiErklaerungScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun kiErklaerungScreenZeigtTitelScrolltBisZumEndeUndErlaubtZurueck() {
        var backed = false

        composeRule.setContent {
            KiErklaerungScreen(onBack = { backed = true })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Wie die Lärmerkennung arbeitet").assertIsDisplayed()

        // Letzter Abschnitt (Index 5 von 6, "Was das fuer Sie praktisch bedeutet") ist in der
        // LazyColumn anfangs nicht komponiert - onNodeWithText(...).performScrollTo() faende ihn
        // deshalb nie (der Knoten muss fuer performScrollTo() schon im Semantics-Baum existieren).
        // performScrollToIndex() auf dem LazyColumn-Tag scrollt dagegen schrittweise, bis der
        // Index komponiert ist - gleiches Muster wie DiagnoseScreenInstrumentedTest.
        composeRule.onNodeWithTag(KI_ERKLAERUNG_LAZY_COLUMN_TAG).performScrollToIndex(5)
        composeRule.onNodeWithText(
            "Behandeln Sie die Einstufung als Hinweis, nicht als Beweis. Der Beweis ist die " +
                "Aufnahme selbst, der gemessene Pegel und – wenn vorhanden – der kalibrierte Wert " +
                "des Messgeräts.",
            substring = true,
        ).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Zurück").assertIsDisplayed().performClick()
        assertTrue(backed)
    }
}
