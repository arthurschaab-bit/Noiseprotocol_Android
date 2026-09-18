package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.Versionskennung
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * docs/PROMPT_VERSIONSKENNUNG.md Abschnitt 4.5: die "Über die App"-Karte ganz unten in den
 * Einstellungen muss die volle Versionskennung zeigen, kopierbar sein, und bei einem
 * Nicht-Release-Build (wie diesem Testlauf - siehe [Versionskennung.istReleaseBuild]) auf den
 * Test-/Entwicklungsstand hinweisen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUeberDieAppSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ausgeklapptZeigtSieKennungKopierKnopfUndDevHinweis() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_about_title)
        composeRule.onNodeWithText(titel).performScrollTo().performClick()

        // onNodeWithText(kennung) waere hier zweideutig: die Kopfzeile der Karte zeigt dieselbe
        // Kennung schon als summary (kollabiert glanceable), zusaetzlich zur Zeile im
        // aufgeklappten Inhalt - assertTextContains auf dem getaggten Knoten bleibt eindeutig.
        val kennung = Versionskennung.formatiere(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        composeRule.onNodeWithTag(VERSIONSKENNUNG_TEXT_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(VERSIONSKENNUNG_TEXT_TAG).assertTextContains(kennung)

        val kopierenText = composeRule.activity.getString(R.string.action_copy)
        composeRule.onNodeWithTag(VERSIONSKENNUNG_KOPIEREN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(kopierenText).assertIsDisplayed()

        // BuildConfig.VERSION_NAME ist im Testlauf niemals ein reines X.Y.Z (siehe
        // Versionskennung Abschnitt 3.1) - der Hinweis muss also sichtbar sein. Eigener
        // performScrollTo(), weil er unterhalb des Kopier-Knopfes liegt und auf dem kleinen
        // Testbildschirm (w320dp-h480dp) sonst ausserhalb des Viewports bleibt.
        composeRule.onNodeWithTag(VERSIONSKENNUNG_DEV_HINWEIS_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun antippenDesKopierKnopfesQuittiertSichtbar() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        composeRule.setContent { SettingsScreen(onBack = {}) }

        val titel = composeRule.activity.getString(R.string.settings_about_title)
        composeRule.onNodeWithText(titel).performScrollTo().performClick()
        composeRule.onNodeWithTag(VERSIONSKENNUNG_KOPIEREN_TAG).performScrollTo().performClick()

        // Toasts haengen nicht im Compose-Semantik-Baum - die sichtbare Quittung wird deshalb
        // ueber Robolectrics ShadowToast geprueft, nicht ueber onNodeWithText.
        val quittungsText = composeRule.activity.getString(R.string.settings_about_copied)
        assertEquals(quittungsText, ShadowToast.getTextOfLatestToast())
    }
}
