package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.standort.FakeStandortErmittlung
import com.example.lrmprotokoll.standort.Standort
import com.example.lrmprotokoll.wetter.FakeWetterProvider
import com.example.lrmprotokoll.wetter.Wetterlage
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkliste Button/Screen-Coverage Phase 4: [GesamtberichtStammdatenSheet] hatte bislang nur
 * zwei Tests (Standort-Berechtigungsfluss), von den ~19 Feldern/Buttons war der grosse Rest
 * ungetestet.
 *
 * `standortErmittlung`/`wetterProvider` hatten laut eigenem KDoc ("austauschbar gegen einen
 * Fake in Tests, ohne echtes Netzwerk", siehe `WetterProvider.kt`) immer schon einen Fake
 * vorgesehen, aber `AppContainer` hatte dafuer bislang keinen Override-Parameter (anders als
 * z.B. `meterTransportOverride`) - das wurde hier nachgezogen (siehe `AppContainer.kt`,
 * `FakeStandortErmittlung.kt`, `FakeWetterProvider.kt`), analog zum bestehenden Muster, kein
 * neues Architekturkonzept.
 */
@RunWith(AndroidJUnit4::class)
class GesamtberichtStammdatenSheetInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var fakeStandort: FakeStandortErmittlung
    private lateinit var fakeWetter: FakeWetterProvider

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fakeStandort = FakeStandortErmittlung()
        fakeWetter = FakeWetterProvider()
        app.setCustomContainer(
            AppContainer(app, standortErmittlungOverride = fakeStandort, wetterProviderOverride = fakeWetter)
        )
        app.container.database.clearAllTables()
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After
    fun tearDown() {
        app.container.database.clearAllTables()
        app.resetContainer()
    }

    private fun legeVerlaufseintragAn(messort: String, erstelltAm: Long): Long =
        runBlocking {
            app.container.database.stammdatenVerlaufDao().insert(
                StammdatenVerlaufEntity(
                    erstelltAm = erstelltAm,
                    geraetHersteller = "NTI Audio",
                    geraetTyp = "XL2",
                    geraetGenauigkeitsklasse = "Klasse 1",
                    geraetSeriennummer = "12345",
                    geraetKalibrierung = "94 dB(A) vor Messung",
                    messort = messort,
                    mikrofonposition = "Fensterbank",
                    mikrofonhoehe = "1,5 m",
                    entfernungZurQuelle = "3 m",
                    innenAussen = "Innen",
                    fensterzustand = "geschlossen",
                    wetter = "12 °C, bedeckt",
                    datenqualitaetHinweis = "",
                )
            )
        }

    @Test
    fun ohneVerlaufSindAlleFelderLeerUndSpeichernLegtNeuenEintragAn() {
        var fertig = false

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = { fertig = true })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("input_bericht_hersteller").performTextInput("NTI Audio")
        composeRule.onNodeWithTag("input_bericht_typ").performTextInput("XL2")
        composeRule.onNodeWithTag("input_bericht_messort").performTextInput("Garten")

        composeRule.onNodeWithText("Speichern").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { fertig }

        val eintraege = runBlocking { app.container.database.stammdatenVerlaufDao().letzte(10) }
        assertEquals(1, eintraege.size)
        assertEquals("NTI Audio", eintraege.first().geraetHersteller)
        assertEquals("XL2", eintraege.first().geraetTyp)
        assertEquals("Garten", eintraege.first().messort)
    }

    @Test
    fun mitLetztemVerlaufseintragSindDieFelderVorausgefuellt() {
        legeVerlaufseintragAn(messort = "Musterplatz 1", erstelltAm = 1_700_000_000_000L)

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("input_bericht_hersteller").assertTextContains("NTI Audio")
        composeRule.onNodeWithTag("input_bericht_messort").assertTextContains("Musterplatz 1")
    }

    @Test
    fun mitMehrerenVerlaufseintraegenErlaubtDieAuswahlEinesAndernEintrags() {
        legeVerlaufseintragAn(messort = "Alter Ort", erstelltAm = 1_700_000_000_000L)
        legeVerlaufseintragAn(messort = "Neuer Ort", erstelltAm = 1_700_000_100_000L)

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = {})
        }
        composeRule.waitForIdle()

        // Zuletzt gespeicherter Eintrag ("Neuer Ort") ist vorausgefuellt.
        composeRule.onNodeWithTag("input_bericht_messort").assertTextContains("Neuer Ort")

        composeRule.onNodeWithText("Andere gespeicherte Angaben wählen (2)").performClick()
        composeRule.onNodeWithText("Alter Ort", substring = true).performClick()

        composeRule.onNodeWithTag("input_bericht_messort").assertTextContains("Alter Ort")
    }

    @Test
    fun ueberspringenSchliesstOhneZuSpeichern() {
        var fertig = false

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = { fertig = true })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Überspringen").performClick()

        assertTrue("Überspringen muss onFertig aufrufen", fertig)
        val eintraege = runBlocking { app.container.database.stammdatenVerlaufDao().letzte(10) }
        assertTrue("Überspringen darf keinen Verlaufseintrag anlegen", eintraege.isEmpty())
    }

    @Test
    fun standortButtonUebernimmtDieAdresseAusDerStandortermittlung() {
        fakeStandort.standort = Standort(breitengrad = 52.5, laengengrad = 13.4)
        fakeStandort.adresse = "Teststraße 42, 10115 Berlin"

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("button_standort_ermitteln").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Teststraße 42, 10115 Berlin", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("input_bericht_messort").assertTextContains("Teststraße 42, 10115 Berlin")
    }

    @Test
    fun standortButtonOhneVerfuegbarenStandortZeigtHinweis() {
        fakeStandort.standort = null

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("button_standort_ermitteln").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Kein Standort verfügbar").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun wetterButtonUebernimmtDieWetterlageAlsKurztext() {
        fakeStandort.standort = Standort(breitengrad = 52.5, laengengrad = 13.4)
        fakeWetter.ergebnis = Result.success(
            Wetterlage(temperaturCelsius = 18.0, windgeschwindigkeitKmh = 5.0, beschreibung = "sonnig"),
        )

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("button_wetter_abrufen").performClick()
        val erwarteterText = Wetterlage(18.0, 5.0, "sonnig").alsKurztext()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(erwarteterText, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("input_bericht_wetter").assertTextContains(erwarteterText)
    }

    @Test
    fun wetterButtonBeiFehlschlagZeigtHinweisUndLaesstFeldUnveraendert() {
        fakeStandort.standort = Standort(breitengrad = 52.5, laengengrad = 13.4)
        fakeWetter.ergebnis = Result.failure(IOException("kein Netz"))

        composeRule.setContent {
            GesamtberichtStammdatenSheet(sessionId = 1L, onFertig = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("button_wetter_abrufen").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Wetterdienst nicht erreichbar").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
