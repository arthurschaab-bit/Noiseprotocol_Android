package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.meter.FakeMeterTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 7b (docs Plan sorted-orbiting-crown.md), naechste
 * Home-Screen-Luecke nach Phase 7a (globaler Filter, PR #168). Das Tages-Header-Einklappen
 * (collapsedDays) war laut Audit zu 0% abgedeckt - kein Test verwendet je die day_header_*-Zeile
 * (neuer testTag, dynamisch pro Datum wie bei den Filter-Chips) oder pruefte, dass sich nur die
 * eigene Tagesgruppe ein-/ausklappt statt versehentlich eine andere.
 *
 * Zwei Aufnahmen an einem festen Tag A und eine an einem Tag B, mehr als 24h auseinander (damit
 * der Kalendertag unabhaengig von der Zeitzone garantiert unterschiedlich ist - jedes Zeitfenster
 * > 24h enthaelt in jeder Zeitzone mindestens eine lokale Mitternacht).
 */
@RunWith(AndroidJUnit4::class)
class HomeTagHeaderCollapseInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp

    private val tagAZeit = 1_700_000_000_000L
    private val tagBZeit = 1_700_100_000_000L // ~27,8h nach Tag A - garantiert anderer Kalendertag

    private val aufnahmeTagA1 = NoiseRecord(
        timestamp = tagAZeit,
        amplitude = 1000.0,
        dbValue = 40.0,
        filePath = "/fake/tagheader-a1.wav",
        label = "TagHeaderA1",
    )
    private val aufnahmeTagA2 = NoiseRecord(
        timestamp = tagAZeit + 1_000L,
        amplitude = 1200.0,
        dbValue = 45.0,
        filePath = "/fake/tagheader-a2.wav",
        label = "TagHeaderA2",
    )
    private val aufnahmeTagB1 = NoiseRecord(
        timestamp = tagBZeit,
        amplitude = 1400.0,
        dbValue = 50.0,
        filePath = "/fake/tagheader-b1.wav",
        label = "TagHeaderB1",
    )

    private fun datumsTag(timestamp: Long) =
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))

    private val tagA get() = datumsTag(tagAZeit)
    private val tagB get() = datumsTag(tagBZeit)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
        app.container.database.clearAllTables()
        runBlocking {
            val dao = app.container.database.noiseDao()
            dao.insert(aufnahmeTagA1)
            dao.insert(aufnahmeTagA2)
            dao.insert(aufnahmeTagB1)
        }
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.resetContainer()
    }

    private fun labelText(record: NoiseRecord) =
        composeRule.activity.getString(R.string.label_user_prefix, record.label)

    private fun aufnahmenCountText(count: Int) =
        composeRule.activity.getString(R.string.protocol_records_count, count)

    @Test
    fun tagHeaderKlapptNurDieEigeneGruppeEinUndAus() {
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

        // Tag A hat zwei Aufnahmen - der Header muss das auch anzeigen, bevor irgendetwas
        // eingeklappt wird.
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag("day_header_$tagA"))
        composeRule.onNodeWithTag("day_header_$tagA").assertIsDisplayed()
        composeRule.onNodeWithText(aufnahmenCountText(2)).assertExists()
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(labelText(aufnahmeTagA1)))
        composeRule.onNodeWithText(labelText(aufnahmeTagA1)).assertExists()
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(labelText(aufnahmeTagA2)))
        composeRule.onNodeWithText(labelText(aufnahmeTagA2)).assertExists()

        // Tag A einklappen. Nach den beiden vorherigen performScrollToNode()-Aufrufen (zu den
        // Aufnahmen-Labels, weiter unten in der Liste) kann der Header laengst wieder aus der
        // Komposition entfernt worden sein - LazyColumn disponiert Eintraege, die aus dem
        // Sichtbereich hinausscrollen, in BEIDE Richtungen, nicht nur "noch nicht erreicht".
        // Deshalb erst zurueckscrollen, dann klicken (wie beim zweiten Klick weiter unten).
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag("day_header_$tagA"))
        composeRule.onNodeWithTag("day_header_$tagA").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(labelText(aufnahmeTagA1)).assertDoesNotExist()
        composeRule.onNodeWithText(labelText(aufnahmeTagA2)).assertDoesNotExist()

        // Tag B (fremde Gruppe) darf davon unberuehrt bleiben.
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(labelText(aufnahmeTagB1)))
        composeRule.onNodeWithText(labelText(aufnahmeTagB1)).assertExists()

        // Tag A wieder aufklappen.
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag("day_header_$tagA"))
        composeRule.onNodeWithTag("day_header_$tagA").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(labelText(aufnahmeTagA1)))
        composeRule.onNodeWithText(labelText(aufnahmeTagA1)).assertExists()
        composeRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasText(labelText(aufnahmeTagA2)))
        composeRule.onNodeWithText(labelText(aufnahmeTagA2)).assertExists()
    }
}
