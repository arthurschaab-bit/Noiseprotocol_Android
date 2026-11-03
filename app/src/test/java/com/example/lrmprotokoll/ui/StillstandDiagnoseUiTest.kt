package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.diagnose.AusfallAntwort
import com.example.lrmprotokoll.diagnose.AusfallKlasse
import com.example.lrmprotokoll.diagnose.AusfallNachfrageEintrag
import com.example.lrmprotokoll.diagnose.HintergrundbeschraenkungBefund
import com.example.lrmprotokoll.diagnose.HintergrundbeschraenkungGrund
import com.example.lrmprotokoll.diagnose.TotmannschaltungZustand
import com.example.lrmprotokoll.diagnose.WaechterLief
import com.example.lrmprotokoll.ui.components.AUSFALL_NACHFRAGE_CARD_TAG
import com.example.lrmprotokoll.ui.components.AUSFALL_NACHFRAGE_DISMISS_TAG
import com.example.lrmprotokoll.ui.components.AusfallNachfrageCard
import com.example.lrmprotokoll.ui.components.HINTERGRUNDBESCHRAENKUNG_BUTTON_TAG
import com.example.lrmprotokoll.ui.components.HINTERGRUNDBESCHRAENKUNG_CARD_TAG
import com.example.lrmprotokoll.ui.components.HintergrundbeschraenkungHinweisCard
import com.example.lrmprotokoll.ui.components.TOTMANNSCHALTUNG_EINRICHTEN_BUTTON_TAG
import com.example.lrmprotokoll.ui.components.TOTMANNSCHALTUNG_WARNUNG_CARD_TAG
import com.example.lrmprotokoll.ui.components.TotmannschaltungWarnungZeile
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-Tests für die Oberflächen-Karten aus Lauf 3 (`docs/PROMPT_DIAGNOSE_STILLSTAND.md`,
 * Schritte 6, 7 und 8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StillstandDiagnoseUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ausfallNachfrageCardZeigtZeitraumHinweisUndGenauFuenfAntworten() {
        var gewaehlteAntwort: AusfallAntwort? = null
        var weggewischt = false
        val letzteDatenAt = 1_761_835_620_000L
        val eintrag =
            AusfallNachfrageEintrag(
                timestampMillis = 1_762_000_000_000L,
                letzteDatenAt = letzteDatenAt,
                entdecktAt = letzteDatenAt + (45L * 60L + 49L) * 60_000L,
                lueckeMinuten = 45L * 60L + 49L,
                klasse = AusfallKlasse.STILLSTAND_GERAET_LIEF,
                waechterLief = WaechterLief.NEIN,
                nutzerAngabe = null,
                belege = emptyMap(),
            )

        composeRule.setContent {
            LaermprotokollTheme {
                AusfallNachfrageCard(
                    eintrag = eintrag,
                    onAntwort = { gewaehlteAntwort = it },
                    onWegwischen = { weggewischt = true },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("${AUSFALL_NACHFRAGE_CARD_TAG}_$letzteDatenAt").assertIsDisplayed()
        composeRule.onNodeWithText("45 h 49 min", substring = true).assertIsDisplayed()

        // Alle fünf festen Antwortknöpfe sind sichtbar
        AusfallAntwort.entries.forEach { antwort ->
            composeRule.onNodeWithTag("btn_ausfall_antwort_${antwort.code}_$letzteDatenAt").assertIsDisplayed()
        }

        composeRule
            .onNodeWithTag("btn_ausfall_antwort_${AusfallAntwort.EINGEFROREN.code}_$letzteDatenAt")
            .performClick()
        assertEquals(AusfallAntwort.EINGEFROREN, gewaehlteAntwort)

        composeRule.onNodeWithTag(AUSFALL_NACHFRAGE_DISMISS_TAG).performClick()
        assertTrue(weggewischt)
    }

    @Test
    fun hintergrundbeschraenkungCardZeigtHuaweiWortlautBeiWaechterSpur() {
        var geoeffnet = false
        val befund =
            HintergrundbeschraenkungBefund(
                aktiv = true,
                gruende = listOf(HintergrundbeschraenkungGrund.WAECHTER_BLOCKIERT_BEI_LAUFENDEM_GERAET),
                standbyBucket = 40,
            )

        composeRule.setContent {
            LaermprotokollTheme {
                HintergrundbeschraenkungHinweisCard(
                    befund = befund,
                    onOeffneEinstellungen = { geoeffnet = true },
                )
            }
        }
        composeRule.waitForIdle()

        val erwarteterHuaweiText =
            composeRule.activity.getString(R.string.hintergrundbeschraenkung_waechter_text)
        composeRule.onNodeWithTag(HINTERGRUNDBESCHRAENKUNG_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(erwarteterHuaweiText).assertIsDisplayed()
        composeRule.onNodeWithTag(HINTERGRUNDBESCHRAENKUNG_BUTTON_TAG).performClick()
        assertTrue(geoeffnet)
    }

    @Test
    fun totmannschaltungWarnungZeileZeigtNichtEingerichtetMitEinrichtenKnopf() {
        var einrichtenGeklickt = false

        composeRule.setContent {
            LaermprotokollTheme {
                TotmannschaltungWarnungZeile(
                    zustand = TotmannschaltungZustand.NICHT_EINGERICHTET,
                    onEinrichtenClick = { einrichtenGeklickt = true },
                )
            }
        }
        composeRule.waitForIdle()

        val erwarteterText =
            composeRule.activity.getString(R.string.totmann_warnung_nicht_eingerichtet)
        composeRule.onNodeWithTag(TOTMANNSCHALTUNG_WARNUNG_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(erwarteterText).assertIsDisplayed()
        composeRule.onNodeWithTag(TOTMANNSCHALTUNG_EINRICHTEN_BUTTON_TAG).performClick()
        assertTrue(einrichtenGeklickt)
    }
}
