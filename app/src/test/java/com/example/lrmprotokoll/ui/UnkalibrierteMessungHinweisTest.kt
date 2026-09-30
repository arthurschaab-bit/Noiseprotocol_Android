package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.FakeMeterTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F-37 (Owner-Meldung 30.09.2026): Wer die Messung startet, waehrend das gekoppelte Messgeraet
 * nicht verbunden ist, bekam **gar keinen Hinweis** - die unkalibrierte Mikrofonmessung sah im
 * Cockpit genauso aus wie eine kalibrierte.
 *
 * Der Grund lag in einer Zeile: eine so gestartete Session traegt eine leere `deviceAddress`,
 * gilt also als reiner Mikrofonlauf, und `istMeterFallback` ist fuer reine Mikrofonlaeufe
 * ausdruecklich aus (Bugfix 12.09.2026). Der Code unterschied nicht zwischen "kein Geraet
 * vorhanden" und "Geraet vorhanden, nur nicht verbunden".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnkalibrierteMessungHinweisTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    @Suppress("UNCHECKED_CAST")
    private fun <T> serviceFlow(name: String): MutableStateFlow<T> =
        AudioRecordingService::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(null) as MutableStateFlow<T>
        }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        runBlocking(Dispatchers.IO) { app.container.database.clearAllTables() }
        app.container.settingsManager.unkalibriertHinweisSessionId = -1L
        serviceFlow<Boolean>("_laeuft").value = true
        serviceFlow<Double?>("_currentMicDb").value = 55.0
    }

    @After
    fun tearDown() {
        serviceFlow<Boolean>("_laeuft").value = false
        serviceFlow<Double?>("_currentMicDb").value = null
        runBlocking(Dispatchers.IO) { app.container.database.clearAllTables() }
        app.resetContainer()
    }

    /** Eine laufende Mikrofon-Session - genau das, was ein Start ohne Verbindung erzeugt. */
    private fun oeffneMikrofonSession() =
        runBlocking(Dispatchers.IO) {
            app.container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = System.currentTimeMillis() - 60_000,
                    endedAt = null,
                    deviceAddress = "",
                    deviceName = "Mikrofon",
                    weighting = null,
                    timeWeighting = null,
                ),
            )
        }

    private fun zeigeCockpit() {
        composeRule.setContent {
            LiveCockpitCard(modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }

    @Test
    fun gekoppeltAberGetrenntKennzeichnetDenPegelUndWarntEinmal() {
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        app.container.settingsManager.meterDeviceName = "PCE-323"
        oeffneMikrofonSession()

        zeigeCockpit()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("dialog_unkalibriert").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("PCE-323", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("btn_unkalibriert_verstanden").performClick()

        // useUnmergedTree: der Hinweis liegt in einer Spalte, deren Semantik zusammengefasst wird.
        composeRule.onNodeWithTag("cockpit_unkalibriert_hinweis", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.cockpit_unkalibriert_hinweis))
            .assertIsDisplayed()
        composeRule.onNodeWithText("dB (Mikrofon-Fallback)", substring = true).assertIsDisplayed()
    }

    /** Ohne gekoppeltes Geraet ist die Mikrofonmessung der Normalfall - kein Pop-up. */
    @Test
    fun ohneGekoppeltesGeraetKeinPopUp() {
        app.container.settingsManager.meterDeviceAddress = null
        oeffneMikrofonSession()

        zeigeCockpit()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("dialog_unkalibriert").assertCountEquals(0)
        // useUnmergedTree ist hier NICHT schmueckend: ohne sie faende der Finder auch dann nichts,
        // wenn der Hinweis da waere - der Gegentest waere wirkungslos.
        composeRule
            .onAllNodesWithTag("cockpit_unkalibriert_hinweis", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /**
     * Owner-Entscheidung 30.09.2026: einmal je Messungsstart, nicht bei jeder Rueckkehr aufs
     * Cockpit. Der Merker liegt deshalb in den Einstellungen, nicht in `remember` - ein
     * Tabwechsel wuerde `remember` verwerfen.
     */
    @Test
    fun beimZweitenOeffnenDerselbenMessungKeinPopUpMehr() {
        app.container.settingsManager.meterDeviceAddress = "AA:BB:CC:DD:EE:FF"
        app.container.settingsManager.meterDeviceName = "PCE-323"
        val sessionId = oeffneMikrofonSession()

        zeigeCockpit()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            app.container.settingsManager.unkalibriertHinweisSessionId == sessionId
        }
        composeRule.onNodeWithTag("btn_unkalibriert_verstanden").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("dialog_unkalibriert").assertCountEquals(0)
        // Die Kennzeichnung bleibt - sie haengt am Zustand, nicht am weggeklickten Hinweis.
        composeRule.onNodeWithTag("cockpit_unkalibriert_hinweis", useUnmergedTree = true).assertExists()
    }
}
