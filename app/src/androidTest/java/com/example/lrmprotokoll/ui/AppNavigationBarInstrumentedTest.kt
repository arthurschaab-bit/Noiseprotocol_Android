package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für die Hauptnavigation und die [AppNavigationBar] gemäß Testplan.
 *
 * Prüft alle 3 Navigationsleisten-Ziele ("Start", "Daten", "Bericht") des Layout-Umbaus
 * (Owner-Vorgabe 12.09.2026: Einstellungen hängt seit dem Umbau nicht mehr an der Bottom-Nav,
 * sondern am Drei-Punkt-Menü jedes Hauptreiters), schnelles Umschalten, sowie den
 * Regressionsfall, dass die Leiste auf allen Hauptseiten sichtbar und erreichbar bleibt.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationBarInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun alleDreiNavigationsleistenZieleSindSichtbarUndAntippbar() {
        var currentRoute by androidx.compose.runtime.mutableStateOf("main")
        composeRule.setContent {
            AppNavigationBar(
                currentRoute = currentRoute,
                onNavigateToStart = { currentRoute = "main" },
                onNavigateToProtokoll = { currentRoute = "protokoll" },
                onNavigateToBericht = { currentRoute = "bericht" },
            )
        }
        composeRule.waitForIdle()

        val startLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_start)
        val dataLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_data)
        val reportLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_report)

        // 1. Start ist initial aktiv
        composeRule.onNodeWithText(startLabel).assertIsDisplayed().assertIsSelected()

        // 2. Zu "Daten" navigieren
        composeRule.onNodeWithText(dataLabel).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(dataLabel).assertIsSelected()

        // 3. Zu "Bericht" navigieren
        composeRule.onNodeWithText(reportLabel).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(reportLabel).assertIsSelected()

        // 4. Zurück zu "Start"
        composeRule.onNodeWithText(startLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(startLabel).assertIsSelected()
    }

    @Test
    fun mehrfachesSchnellesAntippenDesselbenZielsErzeugtKeineFehler() {
        var currentRoute by androidx.compose.runtime.mutableStateOf("main")
        composeRule.setContent {
            AppNavigationBar(
                currentRoute = currentRoute,
                onNavigateToStart = { currentRoute = "main" },
                onNavigateToProtokoll = { currentRoute = "protokoll" },
                onNavigateToBericht = { currentRoute = "bericht" },
            )
        }
        composeRule.waitForIdle()

        val startLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_start)
        val dataLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_data)

        // Mehrfach hintereinander schnell "Daten" und "Start" antippen
        repeat(3) {
            composeRule.onNodeWithText(dataLabel).performClick()
            composeRule.onNodeWithText(startLabel).performClick()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(startLabel).assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun navigationsleisteBleibtAufJederSeiteSichtbar() {
        val routes = listOf("main", "protokoll", "bericht")
        val startLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_start)
        val dataLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_data)
        val reportLabel = composeRule.activity.getString(com.example.lrmprotokoll.R.string.nav_report)
        val labels = listOf(startLabel, dataLabel, reportLabel)

        var currentRoute by mutableStateOf("main")
        composeRule.setContent {
            AppNavigationBar(
                currentRoute = currentRoute,
                onNavigateToStart = { currentRoute = "main" },
                onNavigateToProtokoll = { currentRoute = "protokoll" },
                onNavigateToBericht = { currentRoute = "bericht" },
            )
        }
        composeRule.waitForIdle()

        routes.forEach { activeRoute ->
            currentRoute = activeRoute
            composeRule.waitForIdle()

            // Auf jedem der Screens müssen alle Nav-Labels sichtbar bleiben
            labels.forEach { label ->
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }
        }
    }

    // bottomNavHilfsfunktionErkenntPrefixZieleKorrekt entfernt (Testluecken-Auftrag Stufe 6):
    // reine Funktion ohne Android-Abhaengigkeit, bereits vollstaendig in
    // HomeNavigationComposeTest.istBottomNavZielAktivErkenntGenauesUndParametrisiertesZiel
    // (Robolectric, test/) abgedeckt - ein Geraetetest dafuer war reine Dopplung.
}
