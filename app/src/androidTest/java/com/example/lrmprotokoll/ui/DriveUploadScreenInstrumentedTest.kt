package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DokumentationsFotoEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Geraetetest-Checkliste F6: [DriveUploadScreen] war bislang kompiliert und lint-sauber, aber
 * noch nie auf einem echten Bildschirm gesehen worden. [DriveUploadUebersicht] (die
 * Zusammenstellungslogik) ist bereits ohne Netz getestet - hier geht es nur um das Rendering
 * selbst: Leerzustand und Liste mit Eintraegen.
 *
 * Checkliste Button/Screen-Coverage Phase 1a: der urspruenglich fuer diese Phase vorgesehene
 * "Upload-Button mit Fake-Backend" existiert in [DriveUploadScreen] nicht - der Screen ist eine
 * reine Uebersicht ueber bereits (per Hintergrund-Sync) hochgeladene/laufende/fehlgeschlagene
 * Eintraege, ohne eigenen Auslöse-Button. Die einzige echte, bisher ungetestete Zustandslogik
 * ist die Kopfzeile ([driveSyncAusgeschaltetZeigtHinweisStattZaehler],
 * [driveSyncEingeschaltetZeigtZaehlerUndLetztenLauf]), die direkt von
 * `settingsManager.driveSyncEnabled`/`driveSyncLastMessage` abhaengt.
 */
@RunWith(AndroidJUnit4::class)
class DriveUploadScreenInstrumentedTest {

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
        // Defaults aus SettingsManager.kt (driveSyncEnabled default = false).
        app.container.settingsManager.driveSyncEnabled = false
        app.container.settingsManager.driveSyncLastMessage = null
    }

    @Test
    fun ohneEintraegeZeigtDerScreenDenLeerzustand() {
        var backed = false

        composeRule.setContent {
            DriveUploadScreen(onBack = { backed = true })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Drive-Uploads").assertIsDisplayed()
        composeRule.onNodeWithText("Noch nichts aufgezeichnet, was hochgeladen werden könnte.").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Zurück").assertIsDisplayed().performClick()
        assertTrue(backed)
    }

    @Test
    fun mitEinemFotoZeigtDerScreenDieListeMitDemEintrag() {
        runBlocking {
            val sessionId = app.container.database.sessionDao().insert(
                com.example.lrmprotokoll.data.SessionEntity(
                    startedAt = 1_700_000_000_000L, endedAt = 1_700_000_010_000L,
                    deviceAddress = "AA:BB", deviceName = "PCE-323", weighting = "A", timeWeighting = "SLOW",
                )
            )
            app.container.database.dokumentationsFotoDao().insert(
                DokumentationsFotoEntity(
                    sessionId = sessionId,
                    kategorie = "MESSAUFBAU",
                    dateiPfad = "/data/test/foto.jpg",
                    aufgenommenAm = 1_700_000_005_000L,
                )
            )
        }

        composeRule.setContent {
            DriveUploadScreen(onBack = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DRIVE_UPLOAD_LISTE_TAG).assertIsDisplayed()
    }

    @Test
    fun driveSyncAusgeschaltetZeigtHinweisStattZaehler() {
        app.container.settingsManager.driveSyncEnabled = false

        composeRule.setContent {
            DriveUploadScreen(onBack = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "Drive-Synchronisation ist ausgeschaltet – es wird nichts hochgeladen.",
        ).assertIsDisplayed()
    }

    @Test
    fun driveSyncEingeschaltetZeigtZaehlerUndLetztenLauf() {
        app.container.settingsManager.driveSyncEnabled = true
        app.container.settingsManager.driveSyncLastMessage = "Erfolgreich synchronisiert"

        composeRule.setContent {
            DriveUploadScreen(onBack = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "0 hochgeladen · 0 laufen · 0 offen · 0 fehlgeschlagen",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Letzter Lauf: Erfolgreich synchronisiert").assertIsDisplayed()
    }
}
