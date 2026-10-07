package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compose-Test fuer die Hinweiskarte bei Aufzeichnungsunterbrechungen (Test 7 aus PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveCockpitUnterbrechungTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun aufbauen() {
        app = ApplicationProvider.getApplicationContext()
        app.container.settingsManager.bestaetigeUnterbrechungen()
    }

    @Test
    fun hinweiskarteSichtbarMitBeginnUndEndeUndVerschwindetNachVerstandenUndBleibtWeg() {
        val beginn = 1728211283000L
        val ende = 1728276720000L
        app.container.settingsManager.speichereUnterbrechung(beginn = beginn, ende = ende)

        var mounted by mutableStateOf(true)
        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                if (mounted) {
                    LiveCockpitCard()
                }
            }
        }
        composeRule.waitForIdle()

        val format = SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
        val beginnStr = format.format(Date(beginn))
        val endeStr = format.format(Date(ende))

        composeRule.onNodeWithTag("cockpit_unterbrechung_karte").assertIsDisplayed()
        composeRule.onNodeWithText(beginnStr, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(endeStr, substring = true).assertIsDisplayed()

        // Tipp auf "Verstanden"
        composeRule.onNodeWithTag("btn_unterbrechung_bestaetigen").performClick()
        composeRule.waitForIdle()

        // Jetzt weg
        composeRule.onNodeWithTag("cockpit_unterbrechung_karte").assertDoesNotExist()

        // Auch nach erneutem Rendern weg
        mounted = false
        composeRule.waitForIdle()
        mounted = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("cockpit_unterbrechung_karte").assertDoesNotExist()
        assertEquals(0, app.container.settingsManager.aktiveUnterbrechungen().size)
    }

    @Test
    fun zweiUnbestaetigteUnterbrechungenWerdenBeideGenannt() {
        val beginn1 = 1728200000000L
        val ende1 = 1728210000000L
        val beginn2 = 1728220000000L
        val ende2 = 1728230000000L

        app.container.settingsManager.speichereUnterbrechung(beginn = beginn1, ende = ende1)
        app.container.settingsManager.speichereUnterbrechung(beginn = beginn2, ende = ende2)

        assertEquals(2, app.container.settingsManager.aktiveUnterbrechungen().size)

        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                LiveCockpitCard()
            }
        }
        composeRule.waitForIdle()

        val format = SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
        val beginnStr1 = format.format(Date(beginn1))
        val endeStr1 = format.format(Date(ende1))
        val beginnStr2 = format.format(Date(beginn2))
        val endeStr2 = format.format(Date(ende2))

        composeRule.onNodeWithTag("cockpit_unterbrechung_karte").assertIsDisplayed()
        composeRule.onNodeWithText(beginnStr1, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(endeStr1, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(beginnStr2, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(endeStr2, substring = true).assertIsDisplayed()
    }
}
