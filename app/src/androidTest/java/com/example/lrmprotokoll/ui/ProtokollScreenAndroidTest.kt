package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Systematische instrumentierte UI- und E2E-Tests für den [ProtokollScreen].
 *
 * Verifiziert auf dem Android-Emulator (API 34 ATD):
 * 1. Leerzustand bei leerer Datenbank (Hinweistext & Titel).
 * 2. Navigation: Back-Button vs. Drawer-Button.
 * 3. Session-Liste: Anzeige aktiver und abgeschlossener Sessions mit Badges und Gerätenamen.
 * 4. Klick-Interaktion: Öffnen einer Session ruft Callback mit passender ID auf.
 * 5. Suchleiste: Filterung nach Gerätename, Anzeige bei keinen Treffern & Leeren per Clear-Button.
 * 6. Floating Action Button: Start einer neuen Messung.
 * 7. TopAppBar-Aktionen: Filter-Icon Toggle & Zeitraum-Dialog (Öffnen, Presets, Abbrechen).
 */
@RunWith(AndroidJUnit4::class)
class ProtokollScreenAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        db = app.container.database
        db.clearAllTables()
    }

    @After
    fun tearDown() {
        db.clearAllTables()
    }

    @Test
    fun protokollScreen_leererZustand_zeigtHinweisUndKeineSessions() {
        var backed = false

        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = { backed = true },
                    onOpenSession = {}
                )
            }
        }
        composeRule.waitForIdle()

        // 1. Titel "Protokoll" in TopAppBar sichtbar
        val protocolTitle = composeRule.activity.getString(R.string.nav_protocol)
        composeRule.onNodeWithText(protocolTitle).assertIsDisplayed()

        // 2. Leerzustandstext sichtbar
        val emptyText = composeRule.activity.getString(R.string.empty_protocol_desc)
        composeRule.onNodeWithText(emptyText, substring = true).assertIsDisplayed()

        // 3. Zurück-Button klickbar
        composeRule.onNodeWithTag("btn_protokoll_back").assertIsDisplayed().performClick()
        assertTrue("onBack muss nach Klick auf btn_protokoll_back aufgerufen werden", backed)
    }

    @Test
    fun protokollScreen_navigation_drawerButtonWirdVerwendetWennVorhanden() {
        var drawerOpened = false

        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = {},
                    onOpenDrawer = { drawerOpened = true },
                    onOpenSession = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Drawer-Button muss sichtbar sein, Back-Button nicht
        composeRule.onNodeWithTag("btn_navigation_drawer").assertIsDisplayed().performClick()
        assertTrue("onOpenDrawer muss aufgerufen werden", drawerOpened)
        composeRule.onNodeWithTag("btn_protokoll_back").assertDoesNotExist()
    }

    @Test
    fun protokollScreen_mitSessions_zeigtKartenMitBadgesUndOeffnetSession() {
        val now = System.currentTimeMillis()
        val s1 = SessionEntity(
            id = 101L,
            startedAt = now - 3_600_000L,
            endedAt = now - 1_800_000L,
            deviceAddress = "00:11:22:33:44:55",
            deviceName = "PCE-323-LE",
            weighting = "A",
            timeWeighting = "FAST"
        )
        val s2 = SessionEntity(
            id = 102L,
            startedAt = now - 600_000L,
            endedAt = null,
            deviceAddress = "",
            deviceName = "Smartphone-Mikrofon",
            weighting = null,
            timeWeighting = null
        )

        runBlocking {
            db.sessionDao().insert(s1)
            db.sessionDao().insert(s2)
        }

        var openedSessionId: Long? = null

        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = {},
                    onOpenSession = { openedSessionId = it }
                )
            }
        }
        composeRule.waitForIdle()

        // Prüfe Existenz der beiden Karten
        composeRule.onNodeWithTag("card_session_101").assertIsDisplayed()
        composeRule.onNodeWithTag("card_session_102").assertIsDisplayed()

        // Prüfe Status-Badges
        val completeBadge = composeRule.activity.getString(R.string.protocol_badge_complete)
        val activeBadge = composeRule.activity.getString(R.string.protocol_badge_active)
        composeRule.onNodeWithText(completeBadge).assertIsDisplayed()
        composeRule.onNodeWithText(activeBadge).assertIsDisplayed()

        // Prüfe Gerätenamen
        composeRule.onNodeWithText("PCE-323-LE").assertIsDisplayed()
        composeRule.onNodeWithText("Smartphone-Mikrofon").assertIsDisplayed()

        // Klick auf die erste Session
        composeRule.onNodeWithTag("card_session_101").performClick()
        assertEquals(101L, openedSessionId)
    }

    @Test
    fun protokollScreen_suchleiste_filtertKartenUndSetztZurueck() {
        val now = System.currentTimeMillis()
        val s1 = SessionEntity(
            id = 201L,
            startedAt = now - 3_600_000L,
            endedAt = now - 1_800_000L,
            deviceAddress = "00:11:22:33:44:55",
            deviceName = "PCE-323-Nordseite",
            weighting = "A",
            timeWeighting = "FAST"
        )
        val s2 = SessionEntity(
            id = 202L,
            startedAt = now - 600_000L,
            endedAt = null,
            deviceAddress = "",
            deviceName = "Smartphone-Suedseite",
            weighting = null,
            timeWeighting = null
        )

        runBlocking {
            db.sessionDao().insert(s1)
            db.sessionDao().insert(s2)
        }

        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = {},
                    onOpenSession = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Beide Sessions initial sichtbar
        composeRule.onNodeWithTag("card_session_201").assertIsDisplayed()
        composeRule.onNodeWithTag("card_session_202").assertIsDisplayed()

        // Suche nach "Nordseite"
        composeRule.onNodeWithTag(PROTOKOLL_SEARCH_BAR_TAG).performTextInput("Nordseite")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("card_session_201").assertIsDisplayed()
        composeRule.onNodeWithTag("card_session_202").assertDoesNotExist()

        // Clear-Button klicken
        composeRule.onNodeWithTag("btn_search_clear").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Wieder beide Sessions sichtbar
        composeRule.onNodeWithTag("card_session_201").assertIsDisplayed()
        composeRule.onNodeWithTag("card_session_202").assertIsDisplayed()

        // Suche nach Fantasiebegriff -> Leerzustand "Keine Messungen gefunden"
        composeRule.onNodeWithTag(PROTOKOLL_SEARCH_BAR_TAG).performTextInput("KeinTrefferXYZ")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("card_session_201").assertDoesNotExist()
        composeRule.onNodeWithTag("card_session_202").assertDoesNotExist()
        val noResultsText = composeRule.activity.getString(R.string.protocol_search_no_results)
        composeRule.onNodeWithText(noResultsText).assertIsDisplayed()
    }

    @Test
    fun protokollScreen_floatingActionButton_startetNeueMessung() {
        var newMeasurementStarted = false

        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = {},
                    onOpenSession = {},
                    onStartNewMeasurement = { newMeasurementStarted = true }
                )
            }
        }
        composeRule.waitForIdle()

        // FAB muss sichtbar sein
        composeRule.onNodeWithTag("fab_new_measurement").assertIsDisplayed().performClick()
        assertTrue("onStartNewMeasurement muss aufgerufen werden", newMeasurementStarted)
    }

    @Test
    fun protokollScreen_floatingActionButton_nichtVorhandenWennCallbackNull() {
        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = {},
                    onOpenSession = {},
                    onStartNewMeasurement = null
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("fab_new_measurement").assertDoesNotExist()
    }

    @Test
    fun protokollScreen_filterButton_klickbar() {
        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = {},
                    onOpenSession = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Filter-Button ist in TopAppBar vorhanden und klickbar
        composeRule.onNodeWithTag("btn_filter_events").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        // Zweiter Klick toggelt zurück
        composeRule.onNodeWithTag("btn_filter_events").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun protokollScreen_zeitraumberichtDialog_oeffnetUndSchliesstPerAbbrechen() {
        composeRule.setContent {
            LaermprotokollTheme {
                ProtokollScreen(
                    onBack = {},
                    onOpenSession = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Klick auf Zeitraum-Aktion
        composeRule.onNodeWithTag("btn_period_report").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Dialog-Titel & Presets müssen sichtbar sein
        val dialogTitle = composeRule.activity.getString(R.string.period_report_dialog_title)
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()

        composeRule.onNodeWithTag("btn_period_preset_7d").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_period_preset_30d").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_period_preset_month").assertIsDisplayed()

        // Klick auf "Abbrechen"
        composeRule.onNodeWithTag("btn_period_dialog_cancel").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Dialog muss geschlossen sein
        composeRule.onNodeWithTag("btn_period_dialog_cancel").assertDoesNotExist()
        composeRule.onNodeWithText(dialogTitle).assertDoesNotExist()
    }
}
