package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Systematische instrumentierte E2E- und UI-Tests für die Hauptnavigation
 * und das Startseiten-Cockpit in [MainActivity] / [AppNavigation].
 *
 * Prüft den ModalNavigationDrawer, die Bottom Navigation Bar, TopAppBar-Aktionen,
 * das globale Filter- und Such-Panel sowie die Dauermessungs-Zusammenfassung.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNavigationAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var fakeTransport: FakeMeterTransport

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        fakeTransport = FakeMeterTransport()
        app.setCustomContainer(AppContainer(app, fakeTransport))
        app.container.settingsManager.onboardingCompleted = true
    }

    @After
    fun tearDown() {
        app.resetContainer()
    }

    @Test
    fun drawerLaesstSichOeffnenUndNavigiertZuAllenZielen() {
        composeRule.setContent {
            LaermprotokollTheme {
                AppNavigation()
            }
        }
        composeRule.waitForIdle()

        // 1. Drawer über Hamburger-Icon öffnen
        composeRule.onNodeWithTag("btn_navigation_drawer").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 2. Drawer-Sheet und Menüeinträge überprüfen
        composeRule.onNodeWithTag("app_drawer_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_main").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_meter").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_protokoll").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_diagnose").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_ki-erklaerung").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_trash").assertIsDisplayed()

        // 3. Navigation zu "Systemzustand" (diagnose)
        composeRule.onNodeWithTag("drawer_item_diagnose").performClick()
        composeRule.waitForIdle()

        val diagnoseTitle = composeRule.activity.getString(R.string.nav_diagnose)
        composeRule.onNodeWithText(diagnoseTitle).assertIsDisplayed()

        // 4. Drawer aus Diagnose heraus erneut öffnen und zu "Einstellungen" navigieren
        composeRule.onNodeWithTag("btn_navigation_drawer").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer_item_settings").performClick()
        composeRule.waitForIdle()

        val settingsTitle = composeRule.activity.getString(R.string.nav_settings)
        composeRule.onNodeWithText(settingsTitle).assertIsDisplayed()

        // 5. Zurück zur Startseite über Drawer
        composeRule.onNodeWithTag("btn_navigation_drawer").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer_item_main").performClick()
        composeRule.waitForIdle()

        val appName = composeRule.activity.getString(R.string.app_name)
        composeRule.onNodeWithText(appName).assertIsDisplayed()
    }

    @Test
    fun bottomNavSchaltetZuverlaessigZwischenHauptscreens() {
        composeRule.setContent {
            LaermprotokollTheme {
                AppNavigation()
            }
        }
        composeRule.waitForIdle()

        // 1. Initial ist Start aktiv
        composeRule.onNodeWithTag("nav_item_main").assertIsDisplayed().assertIsSelected()

        // 2. Wechsel zu Protokoll
        composeRule.onNodeWithTag("nav_item_protokoll").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_item_protokoll").assertIsSelected()

        // 3. Wechsel zu Einstellungen
        composeRule.onNodeWithTag("nav_item_settings").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_item_settings").assertIsSelected()

        // 4. Stress-Test: Schnelles mehrfaches Antippen ohne Absturz
        repeat(3) {
            composeRule.onNodeWithTag("nav_item_main").performClick()
            composeRule.onNodeWithTag("nav_item_protokoll").performClick()
        }
        composeRule.waitForIdle()

        // 5. Wieder zurück zu Start
        composeRule.onNodeWithTag("nav_item_main").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_item_main").assertIsSelected()
    }

    @Test
    fun topAppBarBluetoothBadgeOeffnetPairingDialogUndBrichtAb() {
        composeRule.setContent {
            LaermprotokollTheme {
                AppNavigation()
            }
        }
        composeRule.waitForIdle()

        // 1. Klick auf Bluetooth-Badge
        composeRule.onNodeWithTag("badge_bluetooth_status").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 2. Pairing-Dialog erscheint
        composeRule.onNodeWithText("PCE-323 koppeln").assertIsDisplayed()

        // 3. Schließen klicken
        composeRule.onNodeWithText("Schließen").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 4. Dialog ist geschlossen
        composeRule.onNodeWithText("PCE-323 koppeln").assertDoesNotExist()
    }

    @Test
    fun topAppBarOverflowMenuZeigtOptionenUndNavigiert() {
        composeRule.setContent {
            LaermprotokollTheme {
                AppNavigation()
            }
        }
        composeRule.waitForIdle()

        // 1. Overflow-Menü öffnen
        composeRule.onNodeWithTag("btn_overflow_menu").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 2. Menü-Eintrag "Filter einblenden" klicken
        val filterTitle = composeRule.activity.getString(R.string.filter_title)
        composeRule.onNodeWithText(filterTitle).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 3. Filter-Panel ist nun sichtbar
        composeRule.onNodeWithTag("input_filter_search").assertIsDisplayed()
    }

    @Test
    fun filterPanelSucheUndFilterChipsFunktionieren() {
        composeRule.setContent {
            LaermprotokollTheme {
                AppNavigation()
            }
        }
        composeRule.waitForIdle()

        // 1. Filter-Panel per Header aufklappen
        composeRule.onNodeWithTag("panel_filter_header").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("input_filter_search").assertIsDisplayed()

        // 2. Suchtext eingeben
        composeRule.onNodeWithTag("input_filter_search").performTextInput("Bohren")
        composeRule.waitForIdle()

        // 3. Clear-Button klicken
        composeRule.onNodeWithTag("btn_clear_filter_search").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("input_filter_search").assertTextEquals("")

        // 4. Filter-Chips toggeln
        composeRule.onNodeWithTag("chip_filter_favorites").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chip_filter_quiet_hours").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 5. Reset-Chip im Header klicken
        composeRule.onNodeWithTag("chip_filter_reset").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun dauermessungsCardWirdBeiVorhandenerSessionAngezeigtUndNavigiert() {
        // Test-Session in Room DB anlegen
        runBlocking {
            app.container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = System.currentTimeMillis() - 60_000,
                    endedAt = null,
                    deviceAddress = "",
                    deviceName = "Smartphone-Mikrofon",
                    weighting = "A",
                    timeWeighting = "FAST"
                )
            )
        }

        composeRule.setContent {
            LaermprotokollTheme {
                AppNavigation()
            }
        }
        composeRule.waitForIdle()

        // 1. Dauermessungs-Card ist sichtbar
        composeRule.onNodeWithTag("card_continuous_session").assertIsDisplayed()

        // 2. Klick auf "Im Protokoll ansehen"
        composeRule.onNodeWithTag("btn_session_view_protocol").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 3. Protokoll-Tab ist aktiv
        composeRule.onNodeWithTag("nav_item_protokoll").assertIsSelected()
    }
}
