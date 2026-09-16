package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.NoiseRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage (Phase 1b): [TrashScreen] hatte bislang ueberhaupt keinen
 * Test, weder JVM noch instrumentiert - weder der Leerzustand noch Wiederherstellen/Endgueltig-
 * loeschen waren je geprueft.
 */
@RunWith(AndroidJUnit4::class)
class TrashScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
    }

    private fun legeGeloeschtenEintragAn(dateiPfad: String = "/tmp/nicht-vorhanden.wav"): Long =
        runBlocking {
            val id = app.container.database.noiseDao().insert(
                NoiseRecord(
                    timestamp = 1_716_000_000_000L,
                    amplitude = 0.5,
                    dbValue = 61.8,
                    filePath = dateiPfad,
                ),
            )
            app.container.database.noiseDao().softDelete(id, deletedAt = System.currentTimeMillis())
            id
        }

    @Test
    fun ohneEintraegeZeigtDerScreenDenLeerzustandUndErlaubtZurueck() {
        var backed = false
        composeRule.setContent {
            TrashScreen(onNavigateBack = { backed = true }, onShowSnackbar = {})
        }
        composeRule.waitForIdle()

        // waitUntil statt direktem assertIsDisplayed(): auf dem CI-Emulator kann der vorherige
        // Test noch mitten in einem Activity-Wechsel stecken, wenn dieser Test startet - der
        // Titel existiert dann kurzzeitig im Semantics-Baum, ist aber noch nicht ausgemessen.
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Papierkorb").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Papierkorb").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Zurück").assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun mitEintragErmoeglichtWiederherstellenUndZeigtEsSofortAn() {
        val id = legeGeloeschtenEintragAn()
        // Diagnose (PR #153): isoliert, ob der Eintrag tatsaechlich in der DB als geloescht
        // ankommt, bevor ueberhaupt die UI beobachtet wird - trennt einen DAO-Fehler von einem
        // reinen Flow-Beobachtungsfehler in der Compose-UI.
        val direktGeprueft = runBlocking { app.container.database.noiseDao().getTrashAelterAls(Long.MAX_VALUE) }
        assertTrue(
            "legeGeloeschtenEintragAn() muss den Eintrag direkt per DAO auffindbar machen: $direktGeprueft",
            direktGeprueft.any { it.id == id },
        )
        var snackbar: String? = null

        composeRule.setContent {
            TrashScreen(onNavigateBack = {}, onShowSnackbar = { snackbar = it })
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithContentDescription("Wiederherstellen").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Wiederherstellen").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            snackbar != null
        }
        val trashNachRestore = runBlocking {
            app.container.database.noiseDao().getAlleAktiven()
        }
        assertTrue(
            "Nach Wiederherstellen muss der Eintrag wieder unter den aktiven Aufnahmen sein",
            trashNachRestore.isNotEmpty(),
        )
    }

    @Test
    fun endgueltigLoeschenVerlangtBestaetigungUndBrichtBeiAbbrechenNichtsAb() {
        val id = legeGeloeschtenEintragAn()

        composeRule.setContent {
            TrashScreen(onNavigateBack = {}, onShowSnackbar = {})
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithContentDescription("Löschen").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Löschen").performClick()
        composeRule.onNodeWithText("Abbrechen").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val nochImPapierkorb = runBlocking { app.container.database.noiseDao().getTrashAelterAls(Long.MAX_VALUE) }
        assertTrue(
            "Abbrechen darf den Eintrag nicht endgueltig loeschen",
            nochImPapierkorb.any { it.id == id },
        )
    }

    @Test
    fun endgueltigLoeschenEntferntDenEintragNachBestaetigung() {
        val id = legeGeloeschtenEintragAn()
        var snackbar: String? = null

        composeRule.setContent {
            TrashScreen(onNavigateBack = {}, onShowSnackbar = { snackbar = it })
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithContentDescription("Löschen").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Löschen").performClick()
        composeRule.onNodeWithText("Löschen").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            snackbar != null
        }
        val nochVorhanden = runBlocking { app.container.database.noiseDao().getTrashAelterAls(Long.MAX_VALUE) }
        assertTrue(
            "Nach bestaetigtem Loeschen darf der Eintrag nicht mehr existieren",
            nochVorhanden.none { it.id == id },
        )
    }
}
