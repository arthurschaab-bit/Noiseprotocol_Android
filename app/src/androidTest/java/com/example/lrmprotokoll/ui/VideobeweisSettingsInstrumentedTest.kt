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
 * Checkliste Button/Screen-Coverage Phase 6.2 ("Risiko zuerst"), naechste Stufe nach PR #166.
 * Die Sektion "Videobeweis" (BERICHT-Tab) war laut Phase-6.1-Audit zu 0% abgedeckt - die letzte
 * verbliebene 0%-Sektion. Anders als Fotodokumentation gibt es hier keine Pro-Modus-Gate, alle
 * Elemente sind sofort nach dem Aufklappen sichtbar.
 */
@RunWith(AndroidJUnit4::class)
class VideobeweisSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun oeffneVideobeweisSektion() {
        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Videobeweis", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun videoMaxDauerSliderPersistiertMinimumUndMaximum() {
        val settingsManager = app.container.settingsManager
        val oldDauer = settingsManager.videoMaxDauerSekunden
        try {
            settingsManager.videoMaxDauerSekunden = 300

            oeffneVideobeweisSektion()

            val slider = composeRule.onNodeWithTag("slider_video_max_dauer").performScrollTo().assertIsDisplayed()
            slider.performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            assertEquals(30, settingsManager.videoMaxDauerSekunden)

            slider.performTouchInput { swipeRight() }
            composeRule.waitForIdle()
            assertEquals(900, settingsManager.videoMaxDauerSekunden)
        } finally {
            settingsManager.videoMaxDauerSekunden = oldDauer
        }
    }

    @Test
    fun videoAufloesungFilterChipsAendernEinstellung() {
        val settingsManager = app.container.settingsManager
        val oldAufloesung = settingsManager.videoAufloesung
        try {
            settingsManager.videoAufloesung = "HD"

            oeffneVideobeweisSektion()

            composeRule.onNodeWithTag("chip_video_aufloesung_FHD").performScrollTo().performClick()
            assertEquals("FHD", settingsManager.videoAufloesung)

            composeRule.onNodeWithTag("chip_video_aufloesung_HD").performScrollTo().performClick()
            assertEquals("HD", settingsManager.videoAufloesung)
        } finally {
            settingsManager.videoAufloesung = oldAufloesung
        }
    }

    @Test
    fun videoDriveUploadSchalterAendertEinstellung() {
        val settingsManager = app.container.settingsManager
        val oldDriveUpload = settingsManager.videoDriveUpload
        try {
            settingsManager.videoDriveUpload = false

            oeffneVideobeweisSektion()

            composeRule.onNodeWithTag("switch_video_drive_upload").performScrollTo().assertIsOff()
            composeRule.onNodeWithTag("switch_video_drive_upload").performClick()

            assertTrue("Schalter muss videoDriveUpload tatsaechlich einschalten", settingsManager.videoDriveUpload)
            composeRule.onNodeWithTag("switch_video_drive_upload").assertIsOn()

            composeRule.onNodeWithTag("switch_video_drive_upload").performClick()
            assertFalse("Schalter muss videoDriveUpload tatsaechlich ausschalten", settingsManager.videoDriveUpload)
            composeRule.onNodeWithTag("switch_video_drive_upload").assertIsOff()
        } finally {
            settingsManager.videoDriveUpload = oldDriveUpload
        }
    }
}
