package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import java.time.LocalDate
import java.time.ZoneId
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
 * S-4: der Bericht-Reiter zeigt die Messtage und laesst einzelne abwaehlen.
 *
 * Vorher standen hier zwei Knoepfe und sonst nichts - welche Tage es gibt und welche berichtsfaehig
 * sind, erfuhr man erst nach dem Klick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BerichtScreenS4Test {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val heute = LocalDate.of(2026, 9, 29)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val app get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>()

    @Before
    @After
    fun datenbankZuruecksetzen() {
        runBlocking(Dispatchers.IO) { app.container.database.clearAllTables() }
    }

    private fun millis(
        tag: LocalDate,
        stunde: Int,
    ) = tag.atTime(stunde, 0).atZone(zone).toInstant().toEpochMilli()

    /** Ein Messtag mit einem Messwert; [verdichtet] macht ihn nicht berichtsfaehig. */
    private fun legeMesstagAn(
        tag: LocalDate,
        verdichtet: Boolean = false,
    ) = runBlocking(Dispatchers.IO) {
        val db = app.container.database
        val id = db.sessionDao().insert(
            SessionEntity(
                startedAt = millis(tag, 10),
                endedAt = millis(tag, 11),
                deviceAddress = "AA:BB",
                deviceName = "PCE-323",
                weighting = "A",
                timeWeighting = "FAST",
            ),
        )
        db.measurementDao().insertAll(
            listOf(
                MeasurementEntity(
                    sessionId = id,
                    timestamp = millis(tag, 10) + 60_000,
                    levelDb = 55.0,
                    weighting = "A",
                    timeWeighting = "FAST",
                    flags = 0,
                ),
            ),
        )
        if (verdichtet) {
            db.minuteAggregateDao().insertAll(
                listOf(
                    MinuteAggregateEntity(
                        sessionId = id,
                        minuteStart = millis(tag, 10),
                        leqDb = 55.0,
                        maxDb = 56.0,
                        minDb = 54.0,
                        sampleCount = 60,
                        weighting = "A",
                    ),
                ),
            )
        }
    }

    private fun zeigeBildschirm() {
        composeRule.setContent { BerichtScreen(onBack = {}, onOpenSettings = {}, heute = heute) }
    }

    private fun warteAuf(tag: LocalDate) {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("messtag_$tag").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun messtageStehenMitAuswahlUndDenDreiAusgabenAufDemBildschirm() {
        legeMesstagAn(heute)
        legeMesstagAn(heute.minusDays(4))

        zeigeBildschirm()
        warteAuf(heute)

        composeRule.onNodeWithTag("messtag_$heute").assertIsDisplayed()
        composeRule.onNodeWithTag("messtag_${heute.minusDays(4)}").assertIsDisplayed()
        composeRule.onNodeWithTag("haken_$heute").assertIsOn()

        // Die drei Ausgaben sind benannt und sichtbar - kein versteckter Schalter mehr.
        composeRule.onNodeWithTag("btn_bericht_uebersicht").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_bericht_gesamtbericht").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_bericht_erstellen_v2").assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_ausgabe_gesamt_titel))
            .assertIsDisplayed()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_auswahl_stand, 2, 2))
            .assertIsDisplayed()
    }

    @Test
    fun abwaehlenAendertDenAuswahlstand() {
        legeMesstagAn(heute)
        legeMesstagAn(heute.minusDays(4))

        zeigeBildschirm()
        warteAuf(heute)
        composeRule.onNodeWithTag("haken_$heute").performClick()

        composeRule.onNodeWithTag("haken_$heute").assertIsOff()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_auswahl_stand, 1, 2))
            .assertIsDisplayed()
    }

    /** Owner-Entscheidung 30.09.2026: verdichtete Tage sind Haken aus UND gesperrt. */
    @Test
    fun verdichteterTagIstAbgewaehltUndGesperrt() {
        legeMesstagAn(heute)
        legeMesstagAn(heute.minusDays(2), verdichtet = true)

        zeigeBildschirm()
        warteAuf(heute)

        val gesperrt = "haken_${heute.minusDays(2)}"
        composeRule.onNodeWithTag(gesperrt).assertIsOff()
        composeRule.onNodeWithTag(gesperrt).assertIsNotEnabled()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_hindernis_verdichtet))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.bericht_auswahl_stand, 1, 2))
            .assertIsDisplayed()
    }

    /**
     * Ohne Messung sind die beiden Kotlin-Ausgaben gesperrt - der High-End-Einstieg bleibt offen,
     * weil das Sheet einen eigenen Datumswaehler mitbringt.
     */
    @Test
    fun ohneMessungSindDieBeidenAusgabenGesperrtUndHighEndBleibtOffen() {
        zeigeBildschirm()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("bericht_keine_messtage").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("btn_bericht_uebersicht").assertIsNotEnabled()
        composeRule.onNodeWithTag("btn_bericht_gesamtbericht").assertIsNotEnabled()
        composeRule.onNodeWithTag("btn_bericht_erstellen_v2").assertIsDisplayed()
    }

    /** Die Schnellwahl setzt den Zeitraum; Tage ausserhalb verschwinden aus der Liste. */
    @Test
    fun schnellwahlSiebenTageEngtDieListeEin() {
        legeMesstagAn(heute)
        legeMesstagAn(heute.minusDays(20))

        zeigeBildschirm()
        warteAuf(heute.minusDays(20))

        composeRule.onNodeWithTag("btn_bericht_preset_7").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("messtag_${heute.minusDays(20)}").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("messtag_$heute").assertIsDisplayed()
    }
}
