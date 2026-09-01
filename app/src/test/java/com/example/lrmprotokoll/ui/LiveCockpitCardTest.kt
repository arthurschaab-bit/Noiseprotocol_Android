package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
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

    /**
     * Testluecken-Auftrag Stufe 6: echte Interaktionskette statt nur "wird gerendert" -
     * Kategorie waehlen, Notiz eintippen, speichern - und pruefen, dass genau diese beiden
     * Werte (nicht die Default-Kategorie) beim Callback ankommen.
     */
    @Test
    fun auswahlEinerAnderenKategorieUndNotizWerdenBeimSpeichernKorrektUebergeben() {
        var gespeicherteKategorie: String? = null
        var gespeicherteNotiz: String? = null

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                QuickEventTagContent(
                    currentDb = 65.4,
                    onSave = { kategorie, notiz -> gespeicherteKategorie = kategorie; gespeicherteNotiz = notiz }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("🎵 Musik / Bass").performClick()
        composeRule.onNodeWithText("Zusatznotiz (optional)").performTextInput("Nachbar Party")
        composeRule.onNodeWithText("Ereignis jetzt speichern").performClick()

        assertEquals("🎵 Musik / Bass", gespeicherteKategorie)
        assertEquals("Nachbar Party", gespeicherteNotiz)
    }

    /**
     * Ohne Interaktion muss die zuerst gelistete Kategorie ("🔨 Hämmern") als Default gelten -
     * die Gegenprobe zum obigen Test, damit "Auswahl bleibt bei falscher Kategorie haengen"
     * nicht unbemerkt bliebe.
     */
    @Test
    fun ohneKategorieAuswahlWirdDieErsteKategorieAlsDefaultGespeichert() {
        var gespeicherteKategorie: String? = null

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                QuickEventTagContent(currentDb = null, onSave = { kategorie, _ -> gespeicherteKategorie = kategorie })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ereignis jetzt speichern").performClick()

        assertEquals(QUICK_EVENT_CATEGORIES.first(), gespeicherteKategorie)
    }
}
