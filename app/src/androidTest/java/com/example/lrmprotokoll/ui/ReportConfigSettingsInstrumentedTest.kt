package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echtes Geraete-Pendant zu [ReportConfigSettingsTest] (Robolectric, app/src/test) - Teil der
 * Bestandsaufnahme nach dem Datumsbereich-Dialog-Bug (Owner-Auftrag 15.09.2026). Weder
 * BerichtScreenAndroidTest noch SettingsScreen*InstrumentedTest testeten bisher
 * initialTab=SettingsTab.BERICHT bzw. die Berichtsparameter-Sektion (Bericht-Umbau Schritt 3,
 * Owner-Klarstellung 13.09.2026).
 */
@RunWith(AndroidJUnit4::class)
class ReportConfigSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.database.clearAllTables()
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
    }

    @Test
    fun gebietseinstufungWirdEingegebenUndUeberDasDaoGespeichert() {
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
