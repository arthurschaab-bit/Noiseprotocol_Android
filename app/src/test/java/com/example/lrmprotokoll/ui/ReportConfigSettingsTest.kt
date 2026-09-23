package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.ReportConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bericht-Umbau Schritt 3 (Owner-Klarstellung 13.09.2026): die § 287 ZPO-Schaetz- und
 * Tier-Parameter aus [com.example.lrmprotokoll.data.ReportConfigEntity] muessen ueber die neue
 * Sektion "Berichtsparameter" im Bericht-Tab der Einstellungen editierbar und persistent sein -
 * anders als die Berichtsangaben (Geraet/Messaufbau) bewusst hier statt im Stammdaten-Dialog pro
 * Messung, weil sie sich praktisch nie zwischen Messungen aendern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportConfigSettingsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // CI-Fund (22.09.2026, PR #182): AppDatabase.getDatabase() ist ein @Volatile
    // Klassen-Singleton auf einer benannten Datei ("noise_database"), kein In-Memory-/Pro-Test-
    // Handle. Gradle fasst mehrere Testklassen im selben JVM-Fork zusammen - das Singleton
    // ueberlebt also den Wechsel zwischen Testmethoden UND -klassen, unabhaengig von Robolectrics
    // sonst frischer Application pro Test. Ohne expliziten Reset lecken reportConfigDao()-Werte
    // aus vorherigen Tests (auch aus anderen Klassen, z.B. BerichtErstellenSheetTest) hier hinein.
    @Before
    @After
    fun reportConfigZuruecksetzen() {
        // clearAllTables() ist im Gegensatz zu den suspend-DAO-Methoden NICHT automatisch
        // thread-verlagert und prueft explizit, nicht auf dem Hauptthread zu laufen -
        // Dispatchers.IO hier ist deshalb noetig, nicht nur Stil.
        runBlocking(Dispatchers.IO) {
            ApplicationProvider.getApplicationContext<LaermprotokollApp>()
                .container.database.clearAllTables()
        }
    }

    @Test
    fun gebietseinstufungWirdAusgewaehltUndUeberDasDaoGespeichert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("input_report_gebietseinstufung")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("report_area_WA").performClick()
        composeRule.waitForIdle()

        // CI-Fund (22.09.2026, PR #182): waitForIdle() wartet nur auf Komposition/Layout, nicht
        // auf die durch den State-Wechsel ausgeloeste asynchrone DB-Speicherung (eigene
        // Coroutine, kein Teil des Compose-Idle-Begriffs) - direkt danach lesen kann deshalb
        // noch den alten Wert liefern. Explizit auf den geschriebenen Wert pollen statt zu raten.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking(Dispatchers.IO) { app.container.database.reportConfigDao().get() }?.gebietseinstufung == "WA"
        }

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertEquals("WA", gespeichert?.gebietseinstufung)
        }
    }

    @Test
    fun unbekannterAlttextBleibtBisZurBewusstenNeuauswahlErhalten() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        runBlocking {
            app.container.database.reportConfigDao().speichere(ReportConfigEntity(gebietseinstufung = "WA oder MI"))
        }
        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("input_report_gebietseinstufung").performScrollTo()
            .assertTextContains("WA oder MI")
        runBlocking {
            assertEquals("WA oder MI", app.container.database.reportConfigDao().get()?.gebietseinstufung)
        }
    }

    @Test
    fun ungepruefteGebieteKoennenNichtAusgewaehltWerden() {
        composeRule.setContent { ReportAreaSelection(value = "WA", enabled = true, onSelect = { error("Keine Auswahl erwartet") }) }
        composeRule.onNodeWithTag("input_report_gebietseinstufung").performClick()

        // In Robolectric erzeugt ExposedDropdownMenu (Popup) eine permanente Recomposition/Layout-
        // Schleife, solange autoAdvance = true ist (RobolectricIdlingStrategy wartet 60 s vergebens).
        // Mit autoAdvance = false wird der Semantics-Zustand des geoeffneten Menues sofort geprueft.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.mainClock.advanceTimeBy(100)
            composeRule.onNodeWithTag("report_area_WB").assertIsNotEnabled()
            composeRule.onNodeWithTag("report_area_MU").assertIsNotEnabled()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun tierSchwelleVollmessungWirdPerSliderVeraendertUndGespeichert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("slider_report_tier_vollmessung")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        composeRule.waitForIdle()

        // Siehe CI-Fund in gebietseinstufungWirdAusgewaehltUndUeberDasDaoGespeichert oben.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking(Dispatchers.IO) { app.container.database.reportConfigDao().get() }
                ?.let { kotlin.math.abs(it.tierSchwelleVollmessungProzent - 100.0) < 0.0001 } == true
        }

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertEquals(100.0, gespeichert?.tierSchwelleVollmessungProzent ?: 0.0, 0.0001)
        }
    }

    @Test
    fun konservativesFensterWirdGespeichertUndBeiWiderspruchGeklemmt() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("slider_report_konservativ_start")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(23f) }
        composeRule.onNodeWithTag("slider_report_konservativ_ende")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(17f) }
        composeRule.waitForIdle()

        // Siehe CI-Fund in gebietseinstufungWirdAusgewaehltUndUeberDasDaoGespeichert oben.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking(Dispatchers.IO) { app.container.database.reportConfigDao().get() }
                ?.let { it.konservativFensterStartStunde == 19 && it.konservativFensterEndeStunde == 19 } == true
        }

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertEquals(19, gespeichert?.konservativFensterStartStunde)
            assertEquals(19, gespeichert?.konservativFensterEndeStunde)
        }
    }

    @Test
    fun overrideSchalterWirdGespeichert() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

        composeRule.setContent { SettingsScreen(onBack = {}, initialTab = SettingsTab.BERICHT) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Berichtsparameter", substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("switch_report_erzwinge_override")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        // Siehe CI-Fund in gebietseinstufungWirdAusgewaehltUndUeberDasDaoGespeichert oben.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking(Dispatchers.IO) {
                app.container.database.reportConfigDao().get()
            }?.erzwingeBerichtOhneBestaetigteBewertung == true
        }

        runBlocking {
            val gespeichert = app.container.database.reportConfigDao().get()
            assertTrue(gespeichert?.erzwingeBerichtOhneBestaetigteBewertung ?: false)
        }
    }
}
