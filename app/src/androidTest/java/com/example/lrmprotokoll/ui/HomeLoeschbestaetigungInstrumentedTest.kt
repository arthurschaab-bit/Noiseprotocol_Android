package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.ReferenceSound
import com.example.lrmprotokoll.meter.FakeMeterTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 7c (docs Plan sorted-orbiting-crown.md). Die
 * Löschbestätigung fürs gelernte Referenzgeräusch (referenceToDelete-AlertDialog in
 * MainActivity.kt) war laut Audit zu 0% abgedeckt - kein Test öffnete je den Dialog, prüfte den
 * Abbrechen-Pfad (darf NICHT löschen) oder dass ein Bestätigen nur das ausgewählte Muster löscht,
 * nicht alle.
 */
@RunWith(AndroidJUnit4::class)
class HomeLoeschbestaetigungInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    private val muster1 = ReferenceSound(name = "LoeschTestBohren", pattern = "Bohren")
    private val muster2 = ReferenceSound(name = "LoeschTestHaemmern", pattern = "Haemmern")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        app.container.database.clearAllTables()
        runBlocking {
            val dao = app.container.database.noiseDao()
            dao.insertReference(muster1)
            dao.insertReference(muster2)
        }
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.resetContainer()
    }

    private fun setzeInhalt() {
        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
            )
        }
        composeRule.waitForIdle()
    }

    private fun oeffneLoeschDialogFuer(name: String) {
        composeRule.warteUndScrolleZu(hasTestTag("btn_delete_reference_$name"))
        composeRule.onNodeWithTag("btn_delete_reference_$name").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun abbrechenSchliesstDenDialogOhneZuLoeschen() {
        setzeInhalt()

        oeffneLoeschDialogFuer(muster1.name)

        val titel = composeRule.activity.getString(R.string.delete_pattern_title)
        val beschreibung = composeRule.activity.getString(R.string.delete_pattern_desc, muster1.name)
        composeRule.onNodeWithText(titel).assertIsDisplayed()
        composeRule.onNodeWithText(beschreibung).assertIsDisplayed()

        composeRule.onNodeWithTag("btn_cancel_delete_reference").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(titel).assertDoesNotExist()
        // Beide Muster muessen unveraendert weiter existieren - die Chip-Liste ist reaktiv aus
        // derselben Flow wie die DB gespeist, ihr Fortbestehen beweist also, dass nichts geloescht
        // wurde.
        val zweiMuster = composeRule.activity.getString(R.string.learned_patterns_count, 2)
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(zweiMuster))
        composeRule.onNodeWithText(zweiMuster).assertExists()
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag("btn_delete_reference_${muster1.name}"))
        composeRule.onNodeWithTag("btn_delete_reference_${muster1.name}").assertIsDisplayed()
    }

    @Test
    fun bestaetigenLoeschtNurDasAusgewaehlteMuster() {
        setzeInhalt()

        val zweiMuster = composeRule.activity.getString(R.string.learned_patterns_count, 2)
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(zweiMuster))
        composeRule.onNodeWithText(zweiMuster).assertExists()

        oeffneLoeschDialogFuer(muster1.name)
        composeRule.onNodeWithTag("btn_confirm_delete_reference").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.delete_pattern_title)).assertDoesNotExist()
        composeRule.onNodeWithTag("btn_delete_reference_${muster1.name}").assertDoesNotExist()
        val einMuster = composeRule.activity.getString(R.string.learned_patterns_count, 1)
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(einMuster))
        composeRule.onNodeWithText(einMuster).assertExists()

        // Das zweite Muster darf davon unberuehrt bleiben.
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag("btn_delete_reference_${muster2.name}"))
        composeRule.onNodeWithTag("btn_delete_reference_${muster2.name}").assertIsDisplayed()
    }
}
