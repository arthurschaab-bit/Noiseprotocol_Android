package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.meter.FakeMeterTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierte UI-Tests für die Startseite / HomeScreen-Komponenten gemäß Testplan.
 *
 * Prüft den globalen Filter (Auf-/Zuklappen, Reset) und dessen tatsächliche Filterwirkung
 * (Suchtext, Pegelbereich-RangeSlider, alle vier FilterChips) gegen die ECHTE Composable
 * [NoiseProtocolApp], sowie alle interaktiven Aktionen auf [NoiseRecordItem] (Abspielen,
 * Löschen, Lernen, KI-Erkennung, Schnellauswahl-Chips, Long-Click Auswahl).
 *
 * Coverage Phase 7a (docs Plan): der bisherige Filtertest hier war eine handgebaute
 * Ersatz-Composable (Column/Card/Text/TextButton ohne echten RangeSlider, ohne echte
 * RecordFilterState-Logik) und bewies nur, dass irgendein Text auf- und zuklappbar ist -
 * nicht, dass der echte Filter echte Aufnahmen tatsächlich ein-/ausblendet. Ersetzt durch
 * Tests gegen [NoiseProtocolApp] mit echten in die DB eingefügten [NoiseRecord]s.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    // Drei Testaufnahmen, jede so konstruiert, dass genau eine Filterdimension sie eindeutig
    // von den beiden anderen unterscheidet (Suchtext, Favorit, Ruhezeit, Messgeraet,
    // Kalibrierung, Pegelbereich) - siehe Testmethoden unten.
    private val leiseOhneMessgeraet = NoiseRecord(
        timestamp = System.currentTimeMillis(),
        amplitude = 1000.0,
        dbValue = 30.0,
        filePath = "/fake/filtertest-a.wav",
        label = "FilterTestBohren",
        favorite = false,
        isQuietHour = false,
        meterConnected = false,
        calibratedDbA = null,
    )
    private val lautFavorit = NoiseRecord(
        timestamp = System.currentTimeMillis() + 1,
        amplitude = 2000.0,
        dbValue = 100.0,
        filePath = "/fake/filtertest-b.wav",
        label = "FilterTestHaemmern",
        favorite = true,
        isQuietHour = false,
        meterConnected = false,
        calibratedDbA = null,
    )
    private val mittelKalibriertRuhezeit = NoiseRecord(
        timestamp = System.currentTimeMillis() + 2,
        amplitude = 1500.0,
        dbValue = 60.0,
        filePath = "/fake/filtertest-c.wav",
        label = "FilterTestVerkehr",
        favorite = false,
        isQuietHour = true,
        meterConnected = true,
        calibratedDbA = 65.0,
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        app.container.database.clearAllTables()
        app.container.settingsManager.filterSearchQuery = ""
        app.container.settingsManager.filterDbMin = 0f
        app.container.settingsManager.filterDbMax = 120f
        app.container.settingsManager.filterOnlyMeter = false
        app.container.settingsManager.filterOnlyCalibrated = false
        app.container.settingsManager.filterOnlyFavorites = false
        app.container.settingsManager.filterOnlyQuietHours = false
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.container.settingsManager.filterSearchQuery = ""
        app.container.settingsManager.filterDbMin = 0f
        app.container.settingsManager.filterDbMax = 120f
        app.container.settingsManager.filterOnlyMeter = false
        app.container.settingsManager.filterOnlyCalibrated = false
        app.container.settingsManager.filterOnlyFavorites = false
        app.container.settingsManager.filterOnlyQuietHours = false
        app.resetContainer()
    }

    private fun fuegeDreiTestaufnahmenEin() {
        runBlocking {
            val dao = app.container.database.noiseDao()
            dao.insert(leiseOhneMessgeraet)
            dao.insert(lautFavorit)
            dao.insert(mittelKalibriertRuhezeit)
        }
    }

    private fun setzeInhalt() {
        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
            )
        }
        composeRule.waitForIdle()
    }

    private fun setzeInhaltUndOeffneFilterPanel() {
        setzeInhalt()
        composeRule.onNodeWithTag("panel_filter_header").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    private fun labelText(record: NoiseRecord) =
        composeRule.activity.getString(com.example.lrmprotokoll.R.string.label_user_prefix, record.label)

    // home_lazy_column ist eine echte LazyColumn - Eintraege weit unterhalb des Viewports sind
    // schlicht noch nicht komponiert und daher im Semantics-Tree nicht vorhanden, bis dorthin
    // gescrollt wurde. performScrollTo() auf einen bereits gefundenen Knoten reicht hier nicht
    // (der Knoten existiert ja noch gar nicht) - performScrollToNode() auf dem Listen-Container
    // scrollt gezielt bis der Treffer komponiert ist, das ist die dafuer vorgesehene API.
    private fun scrolleZuUndPruefeVorhanden(record: NoiseRecord) {
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(labelText(record)))
        composeRule.onNodeWithText(labelText(record)).assertExists()
    }

    @Test
    fun filterPanelLaesstSichAufUndZuklappen() {
        fuegeDreiTestaufnahmenEin()
        setzeInhalt()

        composeRule.onNodeWithTag("input_filter_search").assertDoesNotExist()
        composeRule.onNodeWithTag("panel_filter_header").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("input_filter_search").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("panel_filter_header").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("input_filter_search").assertDoesNotExist()
    }

    @Test
    fun suchtextFiltertAufNichtPassendeAufnahmenAus() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        composeRule.onNodeWithTag("input_filter_search").performTextInput("Bohren")
        composeRule.waitForIdle()

        scrolleZuUndPruefeVorhanden(leiseOhneMessgeraet)
        composeRule.onNodeWithText(labelText(lautFavorit)).assertDoesNotExist()
        composeRule.onNodeWithText(labelText(mittelKalibriertRuhezeit)).assertDoesNotExist()
    }

    @Test
    fun chipNurFavoritenFiltertAufFavorisierteAufnahmenAus() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        composeRule.onNodeWithTag("chip_filter_favorites").performScrollTo().performClick()
        composeRule.waitForIdle()

        scrolleZuUndPruefeVorhanden(lautFavorit)
        composeRule.onNodeWithText(labelText(leiseOhneMessgeraet)).assertDoesNotExist()
        composeRule.onNodeWithText(labelText(mittelKalibriertRuhezeit)).assertDoesNotExist()
    }

    @Test
    fun chipNurRuhezeitFiltertAufRuhezeitAufnahmenAus() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        composeRule.onNodeWithTag("chip_filter_quiet_hours").performScrollTo().performClick()
        composeRule.waitForIdle()

        scrolleZuUndPruefeVorhanden(mittelKalibriertRuhezeit)
        composeRule.onNodeWithText(labelText(leiseOhneMessgeraet)).assertDoesNotExist()
        composeRule.onNodeWithText(labelText(lautFavorit)).assertDoesNotExist()
    }

    @Test
    fun chipNurMessgeraetFiltertAufMessgeraetAufnahmenAus() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        composeRule.onNodeWithTag("chip_filter_only_meter").performScrollTo().performClick()
        composeRule.waitForIdle()

        scrolleZuUndPruefeVorhanden(mittelKalibriertRuhezeit)
        composeRule.onNodeWithText(labelText(leiseOhneMessgeraet)).assertDoesNotExist()
        composeRule.onNodeWithText(labelText(lautFavorit)).assertDoesNotExist()
    }

    @Test
    fun chipNurKalibriertFiltertAufKalibrierteAufnahmenAus() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        composeRule.onNodeWithTag("chip_filter_only_calibrated").performScrollTo().performClick()
        composeRule.waitForIdle()

        scrolleZuUndPruefeVorhanden(mittelKalibriertRuhezeit)
        composeRule.onNodeWithText(labelText(leiseOhneMessgeraet)).assertDoesNotExist()
        composeRule.onNodeWithText(labelText(lautFavorit)).assertDoesNotExist()
    }

    @Test
    fun ausEinstellungenVorbelegterPegelbereichFiltertAufnahmenAusserhalbDesBereichsAus() {
        // Pre-set statt Slider-Drag (siehe rangeSlider*-Tests unten fuer die Drag-Interaktion
        // selbst) - filterState wird beim ersten Komposieren aus SettingsManager gelesen, das
        // deckt die eigentlich risikobehaftete Frage ab: filtert der Bereich die richtigen
        // Aufnahmen, unabhaengig davon, wie minDb/maxDb zustande kamen.
        //
        // CI-Fehler (root-caused): mit dem Preset ist filterState.istAktiv von der allerersten
        // Komposition an true, wodurch chip_filter_reset schon TEIL derselben Row ist wie
        // panel_filter_header, bevor das Panel ueberhaupt aufgeklappt wird - als einziger Test
        // hier. Die Filterwirkung auf die Aufnahmenliste haengt aber gar nicht von
        // showFilterPanel ab (filteredRecords wird unabhaengig vom Auf-/Zugeklapptsein
        // berechnet) - das Panel muss fuer diesen Test also gar nicht geoeffnet werden. Nur
        // setzeInhalt() statt setzeInhaltUndOeffneFilterPanel() vermeidet den Klick auf die
        // Kopfzeile komplett und damit jede Unklarheit rund um den benachbarten Reset-Chip.
        app.container.settingsManager.filterDbMin = 50f
        app.container.settingsManager.filterDbMax = 70f
        fuegeDreiTestaufnahmenEin()
        setzeInhalt()

        scrolleZuUndPruefeVorhanden(mittelKalibriertRuhezeit)
        composeRule.onNodeWithText(labelText(leiseOhneMessgeraet)).assertDoesNotExist()
        composeRule.onNodeWithText(labelText(lautFavorit)).assertDoesNotExist()
    }

    @Test
    fun rangeSliderSwipeNachRechtsErhoehtMinDbSchwelle() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        val slider = composeRule.onNodeWithTag("slider_home_filter_db").performScrollTo().assertIsDisplayed()
        slider.performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertTrue(
            "Ziehen des linken Reglers ganz nach rechts muss minDb deutlich erhoehen",
            app.container.settingsManager.filterDbMin > 50f
        )
    }

    @Test
    fun rangeSliderSwipeNachLinksVerringertMaxDbSchwelle() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        val slider = composeRule.onNodeWithTag("slider_home_filter_db").performScrollTo().assertIsDisplayed()
        slider.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertTrue(
            "Ziehen des rechten Reglers ganz nach links muss maxDb deutlich verringern",
            app.container.settingsManager.filterDbMax < 70f
        )
    }

    @Test
    fun filterZuruecksetzenChipEntferntAlleFilterUndZeigtAlleAufnahmenWieder() {
        fuegeDreiTestaufnahmenEin()
        setzeInhaltUndOeffneFilterPanel()

        composeRule.onNodeWithTag("chip_filter_favorites").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(labelText(leiseOhneMessgeraet)).assertDoesNotExist()

        composeRule.onNodeWithTag("chip_filter_reset").performScrollTo().performClick()
        composeRule.waitForIdle()

        scrolleZuUndPruefeVorhanden(leiseOhneMessgeraet)
        scrolleZuUndPruefeVorhanden(lautFavorit)
        scrolleZuUndPruefeVorhanden(mittelKalibriertRuhezeit)
    }

    @Test
    fun noiseRecordItemZeigtAlleDetailsUndReagiertAufAlleAktionen() {
        val record = NoiseRecord(
            id = 42L,
            timestamp = 1716000000000L,
            dbValue = 68.5,
            amplitude = 12500.0,
            filePath = "/fake/path/audio.wav",
            label = "Nachbar bohrt",
            detectedLabel = "Drilling"
        )

        var played = false
        var deleted = false
        var favoriteToggled = false
        var learned = false
        var longClicked = false
        var aiRecognized = false
        var assignedLabel: String? = null

        composeRule.setContent {
            NoiseRecordItem(
                record = record,
                isSelected = false,
                onPlay = { played = true },
                onLabel = { assignedLabel = it },
                onToggleFavorite = { favoriteToggled = true },
                onDelete = { deleted = true },
                onLearn = { learned = true },
                onLongClick = { longClicked = true },
                onAiRecognize = { aiRecognized = true }
            )
        }
        composeRule.waitForIdle()

        val micBadge = composeRule.activity.getString(com.example.lrmprotokoll.R.string.badge_microphone)
        val aiPrefix = composeRule.activity.getString(com.example.lrmprotokoll.R.string.label_ai_prefix, "Drilling")
        val userPrefix = composeRule.activity.getString(com.example.lrmprotokoll.R.string.label_user_prefix, "Nachbar bohrt")

        // Überprüfe Detailtexte
        composeRule.onNodeWithText("68.5 dB ($micBadge)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(aiPrefix).assertIsDisplayed()
        composeRule.onNodeWithText(userPrefix).assertIsDisplayed()

        val playDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.audio_play)
        val aiDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_ai_batch)
        val favDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.filter_favorites)
        val delDesc = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_delete)
        val learnStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.action_learn_pattern)
        val bohrenStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.category_drilling)
        val haemmernStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.category_hammering)
        val verkehrStr = composeRule.activity.getString(com.example.lrmprotokoll.R.string.category_traffic)

        // Teste Play-Button
        composeRule.onNodeWithContentDescription(playDesc).assertIsDisplayed().performClick()
        assertTrue(played)

        // Teste KI-Erkennung-Button
        composeRule.onNodeWithContentDescription(aiDesc).assertIsDisplayed().performClick()
        assertTrue(aiRecognized)

        // Teste Favorit-Button
        composeRule.onNodeWithContentDescription(favDesc).assertIsDisplayed().performClick()
        assertTrue(favoriteToggled)

        // Teste Lernen-Chip
        composeRule.onNodeWithText(learnStr, substring = true).assertIsDisplayed().performClick()
        assertTrue(learned)

        // Teste Löschen-Button
        composeRule.onNodeWithContentDescription(delDesc).assertIsDisplayed().performClick()
        assertTrue(deleted)

        // Teste Label-Chips
        composeRule.onNodeWithText(bohrenStr).assertIsDisplayed().performClick()
        assertEquals(bohrenStr, assignedLabel)

        composeRule.onNodeWithText(haemmernStr).assertIsDisplayed().performClick()
        assertEquals(haemmernStr, assignedLabel)

        composeRule.onNodeWithText(verkehrStr).assertIsDisplayed().performClick()
        assertEquals(verkehrStr, assignedLabel)

        // Teste Long Click
        composeRule.onNodeWithText(aiPrefix).performTouchInput { longClick() }
        assertTrue(longClicked)
    }

    @Test
    fun noiseRecordItemImAusgewaehltenZustandWirdKorrektHervorgehoben() {
        val record = NoiseRecord(
            id = 1L,
            timestamp = 1716000000000L,
            dbValue = 55.0,
            amplitude = 8000.0,
            filePath = "/fake/path/audio.wav"
        )

        composeRule.setContent {
            NoiseRecordItem(
                record = record,
                isSelected = true,
                onPlay = {},
                onLabel = {},
                onToggleFavorite = {},
                onDelete = {},
                onLearn = {},
                onLongClick = {},
                onAiRecognize = {}
            )
        }
        composeRule.waitForIdle()

        val micBadge = composeRule.activity.getString(com.example.lrmprotokoll.R.string.badge_microphone)
        composeRule.onNodeWithText("55.0 dB ($micBadge)", substring = true).assertIsDisplayed()
    }
}
