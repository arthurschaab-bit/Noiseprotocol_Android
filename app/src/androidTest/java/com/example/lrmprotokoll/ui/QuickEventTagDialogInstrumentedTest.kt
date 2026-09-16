package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [LiveCockpitCardTest] (Robolectric, app/src/test) - dort wird nur
 * [QuickEventTagContent] gerendert, der umschliessende [QuickEventTagDialog] (AlertDialog) nie
 * wirklich geoeffnet. Teil der Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag
 * 15.09.2026).
 */
@RunWith(AndroidJUnit4::class)
class QuickEventTagDialogInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialogZeigtTitelSchliessenButtonUndKategorien() {
        var dismissed = false
        composeRule.setContent {
            LaermprotokollTheme {
                QuickEventTagDialog(currentDb = 65.4, onDismiss = { dismissed = true }, onSave = { _, _ -> })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Lärmereignis markieren").assertIsDisplayed()
        composeRule.onNodeWithText("🔨 Hämmern").assertIsDisplayed()
        composeRule.onNodeWithText("Ereignis jetzt speichern").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Schließen").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("onDismiss muss nach Klick auf Schliessen aufgerufen werden", dismissed)
    }

    @Test
    fun auswahlEinerAnderenKategorieUndNotizWerdenBeimSpeichernKorrektUebergeben() {
        var gespeicherteKategorie: String? = null
        var gespeicherteNotiz: String? = null

        composeRule.setContent {
            LaermprotokollTheme {
                QuickEventTagDialog(
                    currentDb = 65.4,
                    onDismiss = {},
                    onSave = { kategorie, notiz -> gespeicherteKategorie = kategorie; gespeicherteNotiz = notiz },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("🎵 Musik / Bass").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Zusatznotiz (optional)").performTextInput("Nachbar Party")
        composeRule.onNodeWithText("Ereignis jetzt speichern").performClick()

        assertEquals("🎵 Musik / Bass", gespeicherteKategorie)
        assertEquals("Nachbar Party", gespeicherteNotiz)
    }

    /**
     * Instrumentiertes Pendant zu [LiveCockpitCardTest]s
     * ohneKategorieAuswahlWirdDieErsteKategorieAlsDefaultGespeichert (Robolectric, app/src/test) -
     * dieser Fall hatte bislang kein echtes Geraete-Pendant (Checkliste Button/Screen-Coverage,
     * Phase 1b).
     */
    @Test
    fun ohneKategorieAuswahlWirdDieErsteKategorieAlsDefaultGespeichert() {
        var gespeicherteKategorie: String? = null

        composeRule.setContent {
            LaermprotokollTheme {
                QuickEventTagContent(currentDb = null, onSave = { kategorie, _ -> gespeicherteKategorie = kategorie })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ereignis jetzt speichern").performClick()

        assertEquals(QUICK_EVENT_CATEGORIES.first(), gespeicherteKategorie)
    }
}
