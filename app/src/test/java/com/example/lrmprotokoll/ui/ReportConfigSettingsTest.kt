package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bericht-Umbau Schritt 3 (Owner-Klarstellung 13.09.2026): die § 287 ZPO-Schaetz- und
 * Tier-Parameter aus [com.example.lrmprotokoll.data.ReportConfigEntity] muessen ueber die neue
 * Sektion "Berichtsparameter" im Bericht-Tab der Einstellungen editierbar und persistent sein -
 * anders als die Berichtsangaben (Geraet/Messaufbau) bewusst hier statt im Stammdaten-Dialog pro
 * Messung, weil sie sich praktisch nie zwischen Messungen aendern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportConfigSettingsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun gebietseinstufungWirdEingegebenUndUeberDasDaoGespeichert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("input_report_gebietseinstufung")
            .performScrollTo()
            .performTextReplacement("WA")
        composeRule.waitForIdle()

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertEquals("WA", gespeichert?.gebietseinstufung)
        }
    }

    @Test
    fun tierSchwelleVollmessungWirdPerSliderVeraendertUndGespeichert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("slider_report_tier_vollmessung")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        composeRule.waitForIdle()

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertEquals(100.0, gespeichert?.tierSchwelleVollmessungProzent ?: 0.0, 0.0001)
        }
    }

    @Test
    fun konservativesFensterWirdGespeichertUndBeiWiderspruchGeklemmt() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("slider_report_konservativ_start")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(23f) }
        composeRule.onNodeWithTag("slider_report_konservativ_ende")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(17f) }
        composeRule.waitForIdle()

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertEquals(19, gespeichert?.konservativFensterStartStunde)
            assertEquals(19, gespeichert?.konservativFensterEndeStunde)
        }
    }

    @Test
    fun overrideSchalterWirdGespeichert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("switch_report_erzwinge_override")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertTrue(gespeichert?.erzwingeBerichtOhneBestaetigteBewertung ?: false)
        }
    }
}
