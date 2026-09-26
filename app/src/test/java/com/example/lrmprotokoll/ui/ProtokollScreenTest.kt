package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.SessionEntity
import kotlinx.coroutines.Dispatchers
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
 * Bugfix (Owner-Feedback 12.09.2026): "+ Neue Messung" im Protokollreiter fuehrte waehrend einer
 * bereits laufenden Messung nur zurueck ins Cockpit - eine zweite, parallele Messung gibt es
 * nicht. Der Button darf deshalb nicht angezeigt werden, solange [AudioRecordingService.laeuft]
 * aktiv ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtokollScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * `AppDatabase.getDatabase()` ist ein prozessweites Singleton; ohne Ruecksetzen sieht diese
     * Klasse die Sitzungen, die andere Testklassen im selben Gradle-Fork hinterlassen haben. Die
     * Protokollliste wird dadurch laenger, und hoehenabhaengige Zusicherungen schlagen je nach
     * Ausfuehrungsreihenfolge fehl - genau das ist hier passiert, nachdem F-12 jeden Eintrag um
     * die Integritaetszeile verlaengert hat. Isoliert war die Klasse gruen, im Gesamtlauf rot.
     *
     * Dieselbe Regel steht als Pruefpunkt 3 in docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md und wird
     * von BerichtErstellenSheetTest bereits so umgesetzt.
     */
    @Before
    @After
    fun aufraeumen() {
        AudioRecordingService.testSetzeLaeuft(false)
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        runBlocking(Dispatchers.IO) {
            app.container.database.clearAllTables()
        }
    }

    @Test
    fun neueMessungButtonIstSichtbarOhneLaufendeMessung() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeLaeuft(false)

        composeRule.setContent {
            ProtokollScreen(
                onBack = {},
                onOpenSession = {},
                onStartNewMeasurement = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("fab_new_measurement").assertIsDisplayed()
    }

    @Test
    fun neueMessungButtonIstBeiLaufenderMessungAusgeblendet() {
        ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeLaeuft(true)

        composeRule.setContent {
            ProtokollScreen(
                onBack = {},
                onOpenSession = {},
                onStartNewMeasurement = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("fab_new_measurement").assertDoesNotExist()
    }

    /**
     * Owner-Feature-Auftrag 12.09.2026: Sessions an unterschiedlichen Kalendertagen muessen
     * unter getrennten Tages-Kopfzeilen erscheinen, nicht in einer einzigen flachen Liste.
     */
    @Test
    fun sessionsAnUnterschiedlichenTagenBekommenGetrennteTagesueberschriften() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        AudioRecordingService.testSetzeLaeuft(false)
        val heute = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 10)
        }.timeInMillis
        val gestern = heute - 24L * 60 * 60 * 1000

        runBlocking {
            app.container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = heute, endedAt = heute + 10_000,
                    deviceAddress = "AA:BB", deviceName = "PCE-323", weighting = "A", timeWeighting = "F",
                )
            )
            app.container.database.sessionDao().insert(
                SessionEntity(
                    startedAt = gestern, endedAt = gestern + 10_000,
                    deviceAddress = "AA:BB", deviceName = "PCE-323", weighting = "A", timeWeighting = "F",
                )
            )
        }

        composeRule.setContent {
            ProtokollScreen(onBack = {}, onOpenSession = {}, onStartNewMeasurement = {})
        }
        val formatter = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        val tagHeute = "protokoll_tagesheader_${formatter.format(java.util.Date(heute))}"
        val tagGestern = "protokoll_tagesheader_${formatter.format(java.util.Date(gestern))}"

        // Nur auf die erste Ueberschrift warten: In einer LazyColumn wird nicht komponiert, was
        // ausserhalb des Sichtbereichs liegt. Auf BEIDE Marken gleichzeitig zu warten haengt
        // davon ab, dass beide zufaellig hineinpassen - und genau das gilt seit F-12 nicht mehr
        // zuverlaessig, weil jeder Eintrag um die Integritaetszeile gewachsen ist.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tagHeute).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(tagHeute).assertIsDisplayed()
        // Zur zweiten Ueberschrift scrollen statt die Zusicherung auf assertExists abzuschwaechen:
        // Geprueft werden soll, dass die Tagestrennung SICHTBAR ist, nicht nur im Semantikbaum
        // steht. Dasselbe Vorgehen wie warteUndScrolleZu() fuer die Startbildschirm-Liste.
        composeRule.onNodeWithTag("protokoll_liste").performScrollToNode(hasTestTag(tagGestern))
        composeRule.onNodeWithTag(tagGestern).assertIsDisplayed()
    }
}
