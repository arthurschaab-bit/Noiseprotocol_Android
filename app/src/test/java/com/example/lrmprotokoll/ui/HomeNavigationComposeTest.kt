package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für M7c Aufgabe 3 UND für die Gerätetest-Rückmeldung "Die Navileiste ist in
 * jeder Seite außer Start nicht vorhanden": Die `NavigationBar` lag ursprünglich (M7c) und dann
 * erneut nach einem Regressionsfehler NUR innerhalb von [NoiseProtocolApp] (der "main"-Route),
 * statt den kompletten [androidx.navigation.compose.NavHost] zu umschließen - jede andere Route
 * hatte dadurch keine Navigationsleiste. Läuft deshalb bewusst gegen [AppNavigation] (die echte,
 * produktive Wurzel-Komposable inkl. Navigation), nicht nur gegen [NoiseProtocolApp] allein -
 * sonst hätte genau dieser Regressionsfehler den Test nicht auffangen können.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeNavigationComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun diagnoseIstUeberEinenBeschrifteteNavigationseintragErreichbar() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { AppNavigation() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Diagnose").assertIsDisplayed()
        composeRule.onNodeWithText("Diagnose").performClick()
        composeRule.waitForIdle()

        // "Diagnose-Log (" statt nur "Diagnose-Log": der Leerzustandstext auf demselben Screen
        // ("...oder das Diagnose-Log ist...") enthaelt "Diagnose-Log" ebenfalls als Substring -
        // erst die Klammer macht die Kopfzeile eindeutig treffbar.
        composeRule.onNodeWithText("Diagnose-Log (", substring = true).assertIsDisplayed()
    }

    @Test
    fun alleFuenfNavigationszieleSindBeschriftetAufDemStartbildschirm() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { AppNavigation() }
        composeRule.waitForIdle()

        listOf("Start", "Messgerät", "Protokoll", "Diagnose", "Einstellungen").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun navigationsleisteBleibtAufDemDiagnoseScreenSichtbar() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { AppNavigation() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Diagnose").performClick()
        composeRule.waitForIdle()

        // Der eigentliche Regressionsfall: auf JEDER Seite, nicht nur auf "Start", muessen
        // weiterhin alle fuenf Ziele erreichbar sein. onFirst() statt onNodeWithText(), weil
        // "Diagnose" jetzt zusaetzlich als TopAppBar-Titel des Zielscreens selbst vorkommt -
        // zwei Treffer sind hier korrekt, nicht das zu pruefende Verhalten.
        listOf("Start", "Messgerät", "Protokoll", "Diagnose", "Einstellungen").forEach { label ->
            composeRule.onAllNodesWithText(label).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun istBottomNavZielAktivErkenntGenauesUndParametrisiertesZiel() {
        assertTrue(istBottomNavZielAktiv("main", "main"))
        assertTrue(istBottomNavZielAktiv("protokoll/{sessionId}", "protokoll"))
        assertTrue(istBottomNavZielAktiv("protokoll", "protokoll"))
        assertFalse(istBottomNavZielAktiv("player?path={path}", "main"))
        assertFalse(istBottomNavZielAktiv(null, "main"))
        assertFalse(istBottomNavZielAktiv("protokollverlauf", "protokoll"))
    }
}
