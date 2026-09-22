package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.report.BerichtZeitraum
import com.example.lrmprotokoll.report.ChaquopyReportRunner
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Der UI-Pfad erreicht den Runner und zeigt dessen erwarteten Modulfehler ohne Absturz. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BerichtErstellenSheetTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun neuerBerichtButtonOeffnetAblaufUndModulfehlerIstVerstaendlich() {
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val jetzt = System.currentTimeMillis()
        val datum = LocalDate.now()
        runBlocking {
            val db = app.container.database
            val sessionId = db.sessionDao().insert(
                SessionEntity(startedAt = jetzt - 1_000, endedAt = jetzt + 1_000,
                    deviceAddress = "AA:BB", deviceName = "PCE-323", weighting = "A",
                    timeWeighting = "FAST")
            )
            db.measurementDao().insertAll(listOf(
                MeasurementEntity(sessionId = sessionId, timestamp = jetzt, levelDb = 55.0,
                    weighting = "A", timeWeighting = "FAST", flags = 0)
            ))
            db.stammdatenVerlaufDao().insert(StammdatenVerlaufEntity(
                erstelltAm = jetzt, geraetHersteller = "PCE", geraetTyp = "323",
                geraetGenauigkeitsklasse = "2", geraetSeriennummer = "SN1",
                geraetKalibrierung = "kalibriert", messort = "Musterort",
                mikrofonposition = "Fenster", mikrofonhoehe = "1m",
                entfernungZurQuelle = "5m", innenAussen = "Außen",
                fensterzustand = "", wetter = "trocken", datenqualitaetHinweis = "",
            ))
            db.reportConfigDao().speichere(ReportConfigEntity(gebietseinstufung = "WA"))
        }

        var runnerAufgerufen = false
        composeRule.setContent {
            BerichtScreen(
                onBack = {}, onOpenSettings = {}, initialHighEndRange = BerichtZeitraum(datum, datum),
                highEndRunner = {
                    runnerAufgerufen = true
                    ChaquopyReportRunner.Ergebnis.Fehler("No module named 'report_bridge'")
                },
            )
        }
        composeRule.onNodeWithTag("btn_bericht_erstellen_v2").performClick()
        composeRule.waitForIdle()
        // CI-Fund (22.09.2026, PR #182): performScrollTo() und performClick() direkt verkettet
        // liessen den Klick sporadisch ins Leere gehen (runnerAufgerufen blieb false) - der Klick
        // traf offenbar manchmal, bevor das durch den Scroll ausgeloeste Layout unter Robolectrics
        // GraphicsMode.NATIVE fertig war. Reproduziert unabhaengig von jeder sonstigen Aenderung
        // dieses PRs (2 von 3 isolierten Laeufen auf dem unveraenderten main-Stand). waitForIdle()
        // zwischen Scroll und Klick statt beides zu verketten.
        composeRule.onNodeWithTag("btn_bericht_erstellen_start").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("btn_bericht_erstellen_start").performClick()
        composeRule.waitForIdle()

        assertTrue(runnerAufgerufen)
        composeRule.onNodeWithTag("bericht_erstellen_fehler")
            .assertTextEquals("Der Berichtskern ist noch nicht installiert. Die Auswahl wurde geprüft; der Python-Bericht folgt im nächsten Schritt.")
    }
}
