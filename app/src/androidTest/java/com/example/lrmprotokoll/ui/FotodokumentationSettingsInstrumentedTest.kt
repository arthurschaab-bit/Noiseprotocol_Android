package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 6.2 ("Risiko zuerst"), naechste Stufe nach PR #165.
 * Die Sektion "Fotodokumentation" (BERICHT-Tab) war laut Phase-6.1-Audit zu 0% abgedeckt.
 * Zustaende werden in den meisten Tests direkt vorgesetzt statt per Klick eingeschaltet, damit
 * die betroffenen Elemente schon bei der ersten Komposition sichtbar sind (Lehre aus PR #161:
 * vermeidet die Scroll-Timing-Falle bei erst nachtraeglich erscheinenden Elementen). Die eine
 * Ausnahme (echtes Ein-/Ausschalten des Hauptschalters) nutzt performScrollTo() defensiv fuer
 * die dadurch neu erscheinenden Elemente.
 */
@RunWith(AndroidJUnit4::class)
class FotodokumentationSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun oeffneFotodokumentationSektion() {
        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fotodokumentation", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun fotoDokuAktivSchalterSchaltetEinUndZeigtKategorienAn() {
        val settingsManager = app.container.settingsManager
        val oldValue = settingsManager.fotoDokuAktiv
        try {
            settingsManager.fotoDokuAktiv = false

            oeffneFotodokumentationSektion()

            composeRule.onAllNodesWithText("Messaufbau", substring = true).assertCountEquals(0)

            composeRule.onNodeWithTag("switch_foto_doku_aktiv").performScrollTo().assertIsOff()
            composeRule.onNodeWithTag("switch_foto_doku_aktiv").performClick()

            assertTrue("Schalter muss fotoDokuAktiv tatsaechlich einschalten", settingsManager.fotoDokuAktiv)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Messaufbau", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Messaufbau", substring = true).performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithTag("switch_foto_doku_aktiv").performClick()
            assertFalse("Schalter muss fotoDokuAktiv tatsaechlich ausschalten", settingsManager.fotoDokuAktiv)
            composeRule.onAllNodesWithText("Messaufbau", substring = true).assertCountEquals(0)
        } finally {
            settingsManager.fotoDokuAktiv = oldValue
        }
    }

    @Test
    fun fotoMessaufbauFilterChipsAendernEinstellung() {
        val settingsManager = app.container.settingsManager
        val oldAktiv = settingsManager.fotoDokuAktiv
        val oldMessaufbau = settingsManager.fotoDokuMessaufbau
        try {
            settingsManager.fotoDokuAktiv = true
            settingsManager.fotoDokuMessaufbau = "AUS"

            oeffneFotodokumentationSektion()

            composeRule.onNodeWithTag("chip_foto_Messaufbau_OPTIONAL").performScrollTo().performClick()
            assertEquals("OPTIONAL", settingsManager.fotoDokuMessaufbau)

            composeRule.onNodeWithTag("chip_foto_Messaufbau_PFLICHT").performScrollTo().performClick()
            assertEquals("PFLICHT", settingsManager.fotoDokuMessaufbau)

            composeRule.onNodeWithTag("chip_foto_Messaufbau_AUS").performScrollTo().performClick()
            assertEquals("AUS", settingsManager.fotoDokuMessaufbau)
        } finally {
            settingsManager.fotoDokuMessaufbau = oldMessaufbau
            settingsManager.fotoDokuAktiv = oldAktiv
        }
    }

    @Test
    fun fotoKalibrierungFilterChipsAendernEinstellung() {
        val settingsManager = app.container.settingsManager
        val oldAktiv = settingsManager.fotoDokuAktiv
        val oldKalibrierung = settingsManager.fotoDokuKalibrierung
        try {
            settingsManager.fotoDokuAktiv = true
            settingsManager.fotoDokuKalibrierung = "AUS"

            oeffneFotodokumentationSektion()

            composeRule.onNodeWithTag("chip_foto_Kalibrierung_OPTIONAL").performScrollTo().performClick()
            assertEquals("OPTIONAL", settingsManager.fotoDokuKalibrierung)

            composeRule.onNodeWithTag("chip_foto_Kalibrierung_PFLICHT").performScrollTo().performClick()
            assertEquals("PFLICHT", settingsManager.fotoDokuKalibrierung)

            composeRule.onNodeWithTag("chip_foto_Kalibrierung_AUS").performScrollTo().performClick()
            assertEquals("AUS", settingsManager.fotoDokuKalibrierung)
        } finally {
            settingsManager.fotoDokuKalibrierung = oldKalibrierung
            settingsManager.fotoDokuAktiv = oldAktiv
        }
    }

    @Test
    fun fotoMaxProKategorieSliderPersistiertMinimumUndMaximum() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAktiv = settingsManager.fotoDokuAktiv
        val oldMax = settingsManager.fotoDokuMaxProKategorie
        try {
            settingsManager.isProMode = true
            settingsManager.fotoDokuAktiv = true
            settingsManager.fotoDokuMaxProKategorie = 5

            oeffneFotodokumentationSektion()

            val slider = composeRule.onNodeWithTag("slider_foto_max_pro_kategorie").performScrollTo().assertIsDisplayed()
            slider.performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            assertEquals(1, settingsManager.fotoDokuMaxProKategorie)

            slider.performTouchInput { swipeRight() }
            composeRule.waitForIdle()
            assertEquals(10, settingsManager.fotoDokuMaxProKategorie)
        } finally {
            settingsManager.fotoDokuMaxProKategorie = oldMax
            settingsManager.fotoDokuAktiv = oldAktiv
            settingsManager.isProMode = oldPro
        }
    }

    @Test
    fun fotoDriveUploadSchalterAendertEinstellung() {
        val settingsManager = app.container.settingsManager
        val oldPro = settingsManager.isProMode
        val oldAktiv = settingsManager.fotoDokuAktiv
        val oldDriveUpload = settingsManager.fotoDokuDriveUpload
        try {
            settingsManager.isProMode = true
            settingsManager.fotoDokuAktiv = true
            settingsManager.fotoDokuDriveUpload = false

            oeffneFotodokumentationSektion()

            composeRule.onNodeWithTag("switch_foto_drive_upload").performScrollTo().assertIsOff()
            composeRule.onNodeWithTag("switch_foto_drive_upload").performClick()

            assertTrue("Schalter muss fotoDokuDriveUpload tatsaechlich einschalten", settingsManager.fotoDokuDriveUpload)
            composeRule.onNodeWithTag("switch_foto_drive_upload").assertIsOn()
        } finally {
            settingsManager.fotoDokuDriveUpload = oldDriveUpload
            settingsManager.fotoDokuAktiv = oldAktiv
            settingsManager.isProMode = oldPro
        }
    }
}
