package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S-5 (F-18 + F-32): Der Protokollreiter hatte zwei Filter-Einstiege nebeneinander in der
 * TopAppBar, und nur einer der beiden Filterzustaende der App war persistent.
 *
 * Diese Klasse sichert beide Haelften der Zusammenfuehrung:
 * - **F-32:** Es gibt nur noch einen Knopf. Der alte `btn_filter_events` existiert nicht mehr;
 *   sein Kriterium ist ein Chip im Panel.
 * - **F-18:** Was im Panel eingestellt wird, steht danach in den Einstellungen, und was dort
 *   steht, ist beim Betreten des Reiters wieder aktiv.
 *
 * Der Aufraeumblock folgt Pruefpunkt 3 aus `docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md`: Datenbank
 * *und* Einstellungen sind prozessweit, ein hinterlassener Filter wuerde andere Testklassen im
 * selben Gradle-Fork eine gefilterte Liste sehen lassen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtokollFilterEinstiegTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun einstellungen() = SettingsManager(ApplicationProvider.getApplicationContext())

    @Before
    @After
    fun aufraeumen() {
        AudioRecordingService.testSetzeLaeuft(false)
        einstellungen().apply {
            sessionFilterDbMin = 0.0f
            sessionFilterDbMax = 120.0f
            sessionFilterLabelQuery = ""
            sessionFilterOnlyMeter = false
            sessionFilterOnlyCalibrated = false
            sessionFilterOnlyFavorites = false
            sessionFilterOnlyQuietHours = false
            sessionFilterOnlyWithEvents = false
        }
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        runBlocking(Dispatchers.IO) {
            app.container.database.clearAllTables()
        }
    }

    private fun zeigeProtokoll() {
        composeRule.setContent {
            ProtokollScreen(onBack = {}, onOpenSession = {})
        }
        composeRule.waitForIdle()
    }

    /**
     * F-32. Der entfernte Knopf hiess `btn_filter_events`; der Quelltext nannte als Grund fuer
     * den zweiten Knopf ausdruecklich, dass ein Instrumented-Test genau den ersten klickt.
     */
    @Test
    fun esGibtNurNochEinenFilterEinstieg() {
        zeigeProtokoll()

        composeRule.onNodeWithTag("btn_filter_events").assertDoesNotExist()
        composeRule.onNodeWithTag("btn_session_filter_panel").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("panel_session_filter").assertIsDisplayed()
        composeRule.onNodeWithTag("chip_session_filter_only_with_events").assertIsDisplayed().assertIsNotSelected()
    }

    /** F-18, Schreibrichtung: was das Panel aendert, landet in den Einstellungen. */
    @Test
    fun einKlickImPanelWirdGespeichert() {
        assertFalse(einstellungen().sessionFilterOnlyWithEvents)
        zeigeProtokoll()

        composeRule.onNodeWithTag("btn_session_filter_panel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chip_session_filter_only_with_events").performClick()
        composeRule.waitForIdle()

        assertTrue(
            "Der Protokollfilter muss wie der Startseitenfilter persistiert werden (F-18)",
            einstellungen().sessionFilterOnlyWithEvents,
        )
    }

    /** F-18, Leserichtung: was gespeichert ist, ist beim Betreten des Reiters wieder aktiv. */
    @Test
    fun einGespeicherterFilterIstBeimBetretenWiederAktiv() {
        einstellungen().sessionFilterOnlyWithEvents = true

        zeigeProtokoll()

        composeRule.onNodeWithTag("btn_session_filter_panel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chip_session_filter_only_with_events").assertIsSelected()
    }
}
