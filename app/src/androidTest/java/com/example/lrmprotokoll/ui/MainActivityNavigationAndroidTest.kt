package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var fakeTransport: FakeMeterTransport

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fakeTransport = FakeMeterTransport()
        app.setCustomContainer(AppContainer(app, fakeTransport))
        app.container.settingsManager.onboardingCompleted = true
    }

    @After
    fun tearDown() {
        app.resetContainer()
    }

    private fun setNavigationContent(navController: TestNavHostController? = null) {
        composeRule.setContent {
            LaermprotokollTheme {
                if (navController == null) {
                    AppNavigation()
                } else {
                    navController.navigatorProvider.addNavigator(ComposeNavigator())
                    AppNavigation(navController)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun drawerLaesstSichOeffnenUndNavigiertZuAllenZielen() {
        setNavigationContent()
        composeRule.onNodeWithTag("btn_navigation_drawer").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("app_drawer_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_diagnose").performClick()
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.nav_diagnose)).onFirst().assertIsDisplayed()
        composeRule.onNodeWithTag("btn_navigation_drawer").performClick()
        composeRule.onNodeWithTag("drawer_item_settings").performClick()
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.nav_settings)).onFirst().assertIsDisplayed()
        composeRule.onNodeWithTag("btn_navigation_drawer").performClick()
        composeRule.onNodeWithTag("drawer_item_main").performClick()
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.app_name)).onFirst().assertIsDisplayed()
    }

    @Test
    fun wiederholtesTippenAufDenselbenTabErzeugtKeinenDoppeltenBackstackEintrag() {
        val navController = TestNavHostController(composeRule.activity)
        setNavigationContent(navController)

        repeat(5) {
            composeRule.onNodeWithTag("nav_item_protokoll").performClick()
            composeRule.waitForIdle()
        }
        assertEquals("protokoll", navController.currentDestination?.route)

        val firstPop = composeRule.runOnUiThread<Boolean> { navController.popBackStack() }
        composeRule.waitForIdle()
        assertTrue(firstPop)
        assertEquals("main", navController.currentDestination?.route)

        val secondPop = composeRule.runOnUiThread<Boolean> { navController.popBackStack() }
        assertFalse("Ein zweiter Back darf keinen duplizierten Protokoll-Tab freilegen", secondPop)
    }

    @Test
    fun bottomNavSchaltetZuverlaessigZwischenHauptscreens() {
        setNavigationContent()
        composeRule.onNodeWithTag("nav_item_main").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("nav_item_protokoll").performClick()
        composeRule.onNodeWithTag("nav_item_protokoll").assertIsSelected()
        composeRule.onNodeWithTag("nav_item_settings").performClick()
        composeRule.onNodeWithTag("nav_item_settings").assertIsSelected()
        repeat(3) {
            composeRule.onNodeWithTag("nav_item_main").performClick()
            composeRule.onNodeWithTag("nav_item_protokoll").performClick()
        }
        composeRule.onNodeWithTag("nav_item_main").performClick()
        composeRule.onNodeWithTag("nav_item_main").assertIsSelected()
    }

    @Test
    fun topAppBarBluetoothBadgeOeffnetPairingDialogUndBrichtAb() {
        setNavigationContent()
        composeRule.onNodeWithTag("badge_bluetooth_status").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("PCE-323 koppeln").assertIsDisplayed()
        composeRule.onNodeWithText("Schließen").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("PCE-323 koppeln").assertDoesNotExist()
    }

    @Test
    fun topAppBarOverflowMenuZeigtOptionenUndNavigiert() {
        setNavigationContent()
        composeRule.onNodeWithTag("btn_overflow_menu").assertIsDisplayed().performClick()
        val filterTitle = composeRule.activity.getString(R.string.filter_title)
        composeRule.onNodeWithText(filterTitle).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("home_lazy_column").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("input_filter_search").assertIsDisplayed()
    }

    @Test
    fun filterPanelSucheUndFilterChipsFunktionieren() {
        setNavigationContent()
        composeRule.onNodeWithTag("home_lazy_column").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("panel_filter_header").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("input_filter_search").performTextInput("Bohren")
        composeRule.onNodeWithTag("btn_clear_filter_search").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("btn_clear_filter_search").assertDoesNotExist()
        composeRule.onNodeWithTag("input_filter_search").assert(!hasText("Bohren"))
        composeRule.onNodeWithTag("chip_filter_favorites").performClick()
        composeRule.onNodeWithTag("chip_filter_quiet_hours").performClick()
        composeRule.onNodeWithTag("chip_filter_reset").performClick()
    }

    @Test
    fun dauermessungsCardWirdBeiVorhandenerSessionAngezeigtUndNavigiert() {
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
        setNavigationContent()
        composeRule.onNodeWithTag("home_lazy_column").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("card_continuous_session").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_session_view_protocol").performClick()
        composeRule.onNodeWithTag("nav_item_protokoll").assertIsSelected()
    }
}
