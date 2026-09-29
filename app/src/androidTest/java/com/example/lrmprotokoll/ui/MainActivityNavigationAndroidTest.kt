package com.example.lrmprotokoll.ui

import android.content.Context
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
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.report.messtagGrenzen
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
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationAndroidTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var fakeTransport: FakeMeterTransport
    private var previousStammdatenAbfrageAktiv = true

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fakeTransport = FakeMeterTransport()
        app.setCustomContainer(AppContainer(app, fakeTransport))
        app.container.settingsManager.onboardingCompleted = true
        app.container.settingsManager.fotoDokuAktiv = false
        // Die Navigationsfaelle legen offene Sessions an. Die standardmaessig aktive
        // Stammdaten-Abfrage darf dabei keinen Dialog ueber die Startliste legen.
        previousStammdatenAbfrageAktiv = app.container.settingsManager.stammdatenAbfrageAktiv
        app.container.settingsManager.stammdatenAbfrageAktiv = false
        app
            .getSharedPreferences("noise_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("stammdaten_abfrage_letzter_tag")
            .commit()
        app.container.database.clearAllTables()
    }

    @After
    fun tearDown() {
        app.container.settingsManager.fotoDokuAktiv = false
        app.container.settingsManager.stammdatenAbfrageAktiv = previousStammdatenAbfrageAktiv
        app
            .getSharedPreferences("noise_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("stammdaten_abfrage_letzter_tag")
            .commit()
        app.container.database.clearAllTables()
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
    fun fotoAbfrageBleibtNachNavigationErledigtUndSessionUnveraendert() {
        app.container.settingsManager.fotoDokuAktiv = true
        val sessionId = runBlocking {
            app.container.database.sessionDao().insertMitMessvorgang(
                SessionEntity(startedAt = System.currentTimeMillis(), endedAt = null,
                    deviceAddress = "", deviceName = "Mikrofon", weighting = null, timeWeighting = null)
            )
        }
        val navController = TestNavHostController(composeRule.activity)
        setNavigationContent(navController)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Ohne Foto fortfahren").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Ohne Foto fortfahren").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { app.container.database.sessionDao().byId(sessionId)?.photoPromptCompleted == true }
        }
        val startEntry = navController.currentBackStackEntry!!.id
        repeat(3) {
            composeRule.onNodeWithTag("nav_item_protokoll").performClick()
            composeRule.onNodeWithTag("nav_item_main").performClick()
            composeRule.waitForIdle()
            assertEquals(startEntry, navController.currentBackStackEntry!!.id)
            composeRule.onNodeWithText("Ohne Foto fortfahren").assertDoesNotExist()
            assertEquals(sessionId, runBlocking { app.container.database.sessionDao().offeneSession()!!.id })
        }
        runBlocking {
            val dao = app.container.database.sessionDao()
            dao.update(dao.byId(sessionId)!!.copy(endedAt = System.currentTimeMillis()))
            dao.insertMitMessvorgang(
                SessionEntity(
                    startedAt = System.currentTimeMillis() + 1,
                    endedAt = null,
                    deviceAddress = "PCE",
                    deviceName = "PCE-323",
                    weighting = null,
                    timeWeighting = null,
                ),
                sessionId,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ohne Foto fortfahren").assertDoesNotExist()
    }

    @Test
    fun stammdatenAbfrageBleibtNachQuellenwechselUndNeuemUiStateErledigt() {
        app.container.settingsManager.stammdatenAbfrageAktiv = true
        val sessionId = runBlocking {
            app.container.database.sessionDao().insertMitMessvorgang(
                SessionEntity(
                    startedAt = System.currentTimeMillis(),
                    endedAt = null,
                    deviceAddress = "",
                    deviceName = "Mikrofon",
                    weighting = null,
                    timeWeighting = null,
                ),
            )
        }
        setNavigationContent()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Überspringen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Überspringen").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { app.container.database.sessionDao().byId(sessionId)?.metadataPromptCompleted == true }
        }
        runBlocking {
            val dao = app.container.database.sessionDao()
            dao.update(dao.byId(sessionId)!!.copy(endedAt = System.currentTimeMillis()))
            dao.insertMitMessvorgang(
                SessionEntity(
                    startedAt = System.currentTimeMillis() + 1,
                    endedAt = null,
                    deviceAddress = "PCE",
                    deviceName = "PCE-323",
                    weighting = null,
                    timeWeighting = null,
                ),
                sessionId,
            )
        }
        composeRule.onNodeWithTag("nav_item_protokoll").performClick()
        composeRule.onNodeWithTag("nav_item_main").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Überspringen").assertDoesNotExist()
    }

    @Test
    fun mehrtaegigeMessungFragtAmDrittenTagErneutNachStammdaten() {
        app.container.settingsManager.stammdatenAbfrageAktiv = true
        val zone = ZoneId.systemDefault()
        val tagEins = LocalDate.now(zone).minusDays(2)
        val tagZwei = tagEins.plusDays(1)
        val tagDrei = tagZwei.plusDays(1)
        val (tagEinsVon, tagEinsBis) = messtagGrenzen(tagEins, zone)
        val (tagZweiVon, _) = messtagGrenzen(tagZwei, zone)
        val (tagDreiVon, _) = messtagGrenzen(tagDrei, zone)
        val messvorgangId =
            runBlocking {
                val id =
                    app.container.database.sessionDao().insertMitMessvorgang(
                        SessionEntity(
                            startedAt = tagEinsVon + (tagEinsBis - tagEinsVon) / 2,
                            endedAt = null,
                            deviceAddress = "",
                            deviceName = "Mikrofon",
                            weighting = null,
                            timeWeighting = null,
                            metadataPromptCompleted = true,
                        ),
                    )
                app.container.database.stammdatenVerlaufDao().insert(
                    StammdatenVerlaufEntity(
                        erstelltAm = tagEinsVon + (tagEinsBis - tagEinsVon) / 2,
                        geraetHersteller = "",
                        geraetTyp = "",
                        geraetGenauigkeitsklasse = "",
                        geraetSeriennummer = "",
                        geraetKalibrierung = "",
                        messort = "Tag eins",
                        mikrofonposition = "",
                        mikrofonhoehe = "",
                        entfernungZurQuelle = "",
                        innenAussen = "",
                        fensterzustand = "",
                        wetter = "",
                        datenqualitaetHinweis = "",
                        messvorgangId = id,
                    ),
                )
                id
            }
        app.container.settingsManager.stammdatenAbfrageFuerTagAbschliessen(messvorgangId, tagZweiVon)

        setNavigationContent()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Überspringen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Überspringen").performClick()
        composeRule.waitUntil(10_000) {
            app.container.settingsManager.stammdatenAbfrageFuerTagAbgeschlossen(messvorgangId, tagDreiVon)
        }
    }

    @Test
    fun ausgeschalteterDriveSyncErscheintImCockpitNeutral() {
        val vorher = app.container.settingsManager.driveSyncEnabled
        try {
            app.container.settingsManager.driveSyncEnabled = false
            setNavigationContent()
            val statusText = app.getString(R.string.drive_status_disabled)
            composeRule.warteUndScrolleZu(hasText(statusText))
            composeRule.onNodeWithText(statusText).assertIsDisplayed()
        } finally {
            app.container.settingsManager.driveSyncEnabled = vorher
        }
    }

    @Test
    fun vorhandeneFotosUnterdrueckenAbfrageAuchBeiNeuemUiState() {
        app.container.settingsManager.fotoDokuAktiv = true
        val sessionId = runBlocking {
            val id = app.container.database.sessionDao().insert(
                SessionEntity(startedAt = System.currentTimeMillis(), endedAt = null,
                    deviceAddress = "", deviceName = "Mikrofon", weighting = null, timeWeighting = null)
            )
            app.container.database.dokumentationsFotoDao().insert(
                com.example.lrmprotokoll.data.DokumentationsFotoEntity(sessionId = id,
                    kategorie = "MESSAUFBAU", dateiPfad = "/beweis.jpg", aufgenommenAm = 123L)
            )
            id
        }
        setNavigationContent()
        repeat(3) {
            composeRule.onNodeWithTag("nav_item_protokoll").performClick()
            composeRule.onNodeWithTag("nav_item_main").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Ohne Foto fortfahren").assertDoesNotExist()
        }
        assertEquals(1, runBlocking { app.container.database.dokumentationsFotoDao().fuerSession(sessionId).size })
        assertEquals(sessionId, runBlocking { app.container.database.sessionDao().offeneSession()!!.id })
    }

    @Test
    fun datenTabDreiPunktMenueNavigiertZuEinstellungen() {
        // Layout-Umbau (Owner-Vorgabe 12.09.2026): der Navigations-Drawer ist entfallen,
        // Einstellungen haengt jetzt am Drei-Punkt-Menue jedes Hauptreiters - hier am
        // Daten-Tab (ehemals Protokoll) im vollen Navigationsgraphen geprueft, nicht nur isoliert
        // an ProtokollScreen.
        setNavigationContent()
        composeRule.onNodeWithTag("nav_item_protokoll").performClick()
        composeRule.onNodeWithTag("btn_daten_menu").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("menu_item_daten_settings").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.nav_settings)).onFirst().assertIsDisplayed()
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
        composeRule.onNodeWithTag("nav_item_bericht").performClick()
        composeRule.onNodeWithTag("nav_item_bericht").assertIsSelected()
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
    fun bluetoothBadgeFuehrtZurGeraeteverwaltungUndZurueckZumCockpit() {
        val navController = TestNavHostController(composeRule.activity)
        setNavigationContent(navController)

        composeRule.onNodeWithTag("badge_bluetooth_status").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("btn_manage_meter").assertIsDisplayed().performClick()
        assertEquals("meter", navController.currentDestination?.route)

        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        assertEquals("main", navController.currentDestination?.route)
    }

    @Test
    fun topAppBarOverflowMenuZeigtOptionenUndNavigiert() {
        setNavigationContent()
        composeRule.onNodeWithTag("btn_overflow_menu").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("menu_item_filter").assertIsDisplayed().performClick()
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
        // Room emittiert asynchron; ein pauschaler Wisch wartet weder auf die Session
        // noch garantiert er bei unterschiedlichen Bildschirmhoehen eine sichtbare Karte.
        composeRule.warteUndScrolleZu(hasTestTag("btn_session_view_protocol"))
        composeRule.onNodeWithTag("card_continuous_session").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_session_view_protocol").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("nav_item_protokoll").assertIsSelected()
    }

    @Test
    fun stammdatenKorrekturImCockpitErzeugtGenauEinenNeuenTageseintrag() {
        val database = app.container.database
        val sessionDao = database.sessionDao()
        val stammdatenDao = database.stammdatenVerlaufDao()
        val sessionId =
            runBlocking {
                val id =
                    sessionDao.insertMitMessvorgang(
                        SessionEntity(
                            startedAt = System.currentTimeMillis(),
                            endedAt = null,
                            deviceAddress = "",
                            deviceName = "Smartphone-Mikrofon",
                            weighting = "A",
                            timeWeighting = "FAST",
                        ),
                    )
                stammdatenDao.insert(
                    StammdatenVerlaufEntity(
                        erstelltAm = System.currentTimeMillis(),
                        geraetHersteller = "NTI Audio",
                        geraetTyp = "XL2",
                        geraetGenauigkeitsklasse = "Klasse 1",
                        geraetSeriennummer = "12345",
                        geraetKalibrierung = "94 dB(A)",
                        messort = "Alter Messort",
                        mikrofonposition = "Fensterbank",
                        mikrofonhoehe = "1,5 m",
                        entfernungZurQuelle = "3 m",
                        innenAussen = "Innen",
                        fensterzustand = "geschlossen",
                        wetter = "bedeckt",
                        datenqualitaetHinweis = "",
                    ),
                )
                id
            }

        setNavigationContent()
        composeRule.warteUndScrolleZu(hasTestTag("btn_session_edit_stammdaten"))
        composeRule
            .onNodeWithTag("btn_session_edit_stammdaten")
            .performClick()
        composeRule
            .onNodeWithTag("input_bericht_messort")
            .performScrollTo()
            .assertTextContains("Alter Messort")
        composeRule
            .onNodeWithTag("input_bericht_messort")
            .performTextClearance()
        composeRule
            .onNodeWithTag("input_bericht_messort")
            .performTextInput("Korrigierter Messort")
        composeRule
            .onNodeWithText(app.getString(R.string.report_metadata_save))
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { stammdatenDao.letzte(10) }.size == 2
        }

        val eintraege = runBlocking { stammdatenDao.letzte(10) }
        assertEquals(2, eintraege.size)
        assertEquals("Korrigierter Messort", eintraege.first().messort)
        assertEquals("Alter Messort", eintraege.last().messort)
        assertEquals(sessionId, eintraege.first().messvorgangId)
        assertFalse(runBlocking { sessionDao.byId(sessionId)!!.metadataPromptCompleted })
    }

    /**
     * Review-Befund zu PR #220: derselbe Knopf erscheint auch fuer die zuletzt **beendete**
     * Session. Wird er am Folgetag benutzt, muss die Korrektur zum Messtag der Session gehoeren -
     * sonst erzeugt sie einen Eintrag fuer heute und der gestrige Bericht sieht sie nie.
     *
     * Der Test ist bewusst hier und nicht nur als Unit-Test auf
     * [com.example.lrmprotokoll.report.messtagFuerStammdatenKorrektur]: der Fehler lag nicht in
     * einer Berechnung, sondern darin, dass der Messtag gar nicht erst an das Sheet uebergeben
     * wurde. Eine reine Funktionspruefung haette ihn nicht gefunden.
     *
     * Die Zusicherungen unterscheiden die beiden Faelle eindeutig:
     * - mit Korrektur: der neue Eintrag steht in `fuerTag(gestern)` und **nicht** in `letzte()`,
     *   weil ein Nachtrag (`giltFuerTagStart` gesetzt) dort bewusst uebersprungen wird.
     * - ohne Korrektur (der alte Fehler): der Eintrag haette `giltFuerTagStart = null` und
     *   `erstelltAm = heute` - er stuende in `letzte()` und nicht in `fuerTag(gestern)`.
     */
    @Test
    fun stammdatenKorrekturEinerGestrigenSessionGehoertZumGestrigenMesstag() {
        val zone = ZoneId.systemDefault()
        val gestern = LocalDate.now(zone).minusDays(1)
        val (gesternVon, gesternBis) = messtagGrenzen(gestern, zone)
        val gesternMittag = gesternVon + (gesternBis - gesternVon) / 2

        val database = app.container.database
        val sessionDao = database.sessionDao()
        val stammdatenDao = database.stammdatenVerlaufDao()
        runBlocking {
            sessionDao.insert(
                SessionEntity(
                    startedAt = gesternMittag,
                    endedAt = gesternMittag + 60 * 60 * 1000,
                    deviceAddress = "",
                    deviceName = "Smartphone-Mikrofon",
                    weighting = "A",
                    timeWeighting = "FAST",
                ),
            )
            stammdatenDao.insert(
                StammdatenVerlaufEntity(
                    erstelltAm = gesternMittag,
                    geraetHersteller = "NTI Audio",
                    geraetTyp = "XL2",
                    geraetGenauigkeitsklasse = "Klasse 1",
                    geraetSeriennummer = "12345",
                    geraetKalibrierung = "94 dB(A)",
                    messort = "Alter Messort",
                    mikrofonposition = "Fensterbank",
                    mikrofonhoehe = "1,5 m",
                    entfernungZurQuelle = "3 m",
                    innenAussen = "Innen",
                    fensterzustand = "geschlossen",
                    wetter = "bedeckt",
                    datenqualitaetHinweis = "",
                ),
            )
        }

        setNavigationContent()
        composeRule.warteUndScrolleZu(hasTestTag("btn_session_edit_stammdaten"))
        composeRule.onNodeWithTag("btn_session_edit_stammdaten").performClick()
        composeRule
            .onNodeWithTag("input_bericht_messort")
            .performScrollTo()
            .performTextClearance()
        composeRule
            .onNodeWithTag("input_bericht_messort")
            .performTextInput("Korrektur von gestern")
        composeRule
            .onNodeWithText(app.getString(R.string.report_metadata_save))
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { stammdatenDao.fuerTag(gesternVon, gesternBis) }.size == 2
        }

        val fuerGestern = runBlocking { stammdatenDao.fuerTag(gesternVon, gesternBis) }
        assertEquals("Korrektur von gestern", fuerGestern.first().messort)
        assertEquals(gesternVon, fuerGestern.first().giltFuerTagStart)

        val regulaere = runBlocking { stammdatenDao.letzte(10) }
        assertEquals(
            "Ein Nachtrag darf nicht als Vorbelegung der naechsten Messung auftauchen, war $regulaere",
            1,
            regulaere.size,
        )
        assertEquals("Alter Messort", regulaere.first().messort)
    }
}
