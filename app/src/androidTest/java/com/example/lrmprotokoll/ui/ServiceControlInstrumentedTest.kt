package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für das [ServiceControl] Dashboard gemäß Testplan.
 *
 * Prüft die Anzeige des Inaktiv/Aktiv-Status sowie die Buttons "Aufnahme starten"
 * und "Aufnahme beenden" mit korrekter Aktivierungs- und Deaktivierungslogik.
 */
@RunWith(AndroidJUnit4::class)
class ServiceControlInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
    }

    @Test
    fun serviceControlZeigtInaktivenZustandUndStartButtonAktiv() {
        composeRule.setContent {
            ServiceControl(context = app, hasPermissions = true)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Überwachung:").assertIsDisplayed()
        composeRule.onNodeWithText("Inaktiv").assertIsDisplayed()

        // Starten-Button ist aktiviert, Beenden-Button ist deaktiviert
        composeRule.onNodeWithText("Aufnahme starten").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Aufnahme beenden").assertIsDisplayed().assertIsNotEnabled()
    }
}
