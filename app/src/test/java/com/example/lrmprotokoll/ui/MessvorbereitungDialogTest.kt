package com.example.lrmprotokoll.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.messreihe.Messphase
import com.example.lrmprotokoll.testhilfen.ZeitwaechterRegel
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose-Test der Kalibrier- und Aufbauphase (Owner-Auftrag 12.11.2026). Prueft nur die
 * Bedienlogik - ob die Erkennung am echten Kalibrator stimmt, zeigt erst ein Geraetetest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessvorbereitungDialogTest {
    @get:Rule(order = 0)
    val zeitwaechter = ZeitwaechterRegel()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val app: LaermprotokollApp = ApplicationProvider.getApplicationContext()

    private fun zeige() {
        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                MessvorbereitungHost(aufbauBearbeiten = false, onAufbauBearbeitenEnde = { })
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun ohneLaufendePhaseIstNichtsSichtbar() {
        zeige()
        composeRule.onNodeWithTag("dialog_kalibrierung").assertDoesNotExistCompat()
        composeRule.onNodeWithTag("dialog_aufbau").assertDoesNotExistCompat()
        assertEquals(Messphase.KEINE, app.container.messphasen.phase.value)
    }

    @Test
    fun manuellerStartMitMessgeraetZeigtDieKalibrierungUndUeberspringenFuehrtInDenAufbau() {
        zeige()
        app.container.messphasen.startManuell(messgeraetErwartet = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dialog_kalibrierung").assertIsDisplayed()
        composeRule.onNodeWithTag("button_kalibrierung_ueberspringen").performClick()
        composeRule.waitForIdle()

        assertEquals(Messphase.AUFBAU, app.container.messphasen.phase.value)
        composeRule.onNodeWithTag("dialog_aufbau").assertIsDisplayed()
    }

    @Test
    fun messungStartenImAufbauBeendetDiePhaseUndGibtDieErfassungFrei() {
        zeige()
        app.container.messphasen.startManuell(messgeraetErwartet = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_aufbau").assertIsDisplayed()
        assertEquals(true, app.container.messphasen.erfassungPausiert.value)

        composeRule.onNodeWithTag("button_aufbau_abschliessen").performClick()
        // Der Abschluss schreibt vorher im Hintergrund die beantworteten Abfragen weg - nicht
        // auf waitForIdle verlassen (in der CI war der Test sonst zu frueh fertig).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            app.container.messphasen.phase.value == Messphase.KEINE
        }

        assertEquals(Messphase.KEINE, app.container.messphasen.phase.value)
        assertEquals(false, app.container.messphasen.erfassungPausiert.value)
    }

    @Test
    fun vomNutzerEingetrageneAbweichungWirdAlsKorrigiertGespeichert() {
        zeige()
        app.container.messphasen.startManuell(messgeraetErwartet = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("input_kalibrierung_vorher").performTextInput("-0,4")
        composeRule.onNodeWithTag("input_kalibrierung_nachher").performTextInput("0.1")
        composeRule.onNodeWithTag("button_kalibrierung_beendet").performClick()
        composeRule.waitForIdle()
        assertEquals(Messphase.AUFBAU, app.container.messphasen.phase.value)

        // Das Speichern laeuft asynchron im Hintergrund.
        val dao = app.container.database.kalibrierphaseDao()
        var phase = runBlocking { dao.zwischen(0, Long.MAX_VALUE).lastOrNull() }
        repeat(50) {
            if (phase?.endeGrund != null) return@repeat
            Thread.sleep(100)
            phase = runBlocking { dao.zwischen(0, Long.MAX_VALUE).lastOrNull() }
        }
        assertEquals(-0.4, phase!!.abweichungVorherDb!!, 0.0001)
        assertEquals(0.1, phase!!.abweichungNachherDb!!, 0.0001)
        assertTrue("Ohne erkannte Werte ist jeder Eintrag eine Korrektur", phase!!.abweichungKorrigiert)
        assertEquals(com.example.lrmprotokoll.data.KalibrierEnde.MANUELL, phase!!.endeGrund)
    }

    @Test
    fun einOffenesSheetBlendetDenKalibrierdialogAusUndDieEingabenBleibenErhalten() {
        // Meldung vom 13.11.2026: Der Kalibrierdialog (eigenes Fenster) lag ueber dem Foto-Popup,
        // Fotos liessen sich nicht aufnehmen.
        var sheetOffen by mutableStateOf(false)
        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                MessvorbereitungHost(
                    aufbauBearbeiten = false,
                    onAufbauBearbeitenEnde = { },
                    fremdesSheetOffen = sheetOffen,
                )
            }
        }
        app.container.messphasen.startManuell(messgeraetErwartet = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("input_kalibrierung_vorher").performTextInput("-0,4")

        sheetOffen = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_kalibrierung").assertDoesNotExistCompat()

        sheetOffen = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_kalibrierung").assertIsDisplayed()
        composeRule.onNodeWithTag("input_kalibrierung_vorher").assertTextContains("-0,4")
    }

    @Test
    fun einOffenesSheetBlendetAuchDenAufbaudialogAus() {
        var sheetOffen by mutableStateOf(false)
        composeRule.setContent {
            LaermprotokollTheme(darkTheme = true) {
                MessvorbereitungHost(
                    aufbauBearbeiten = false,
                    onAufbauBearbeitenEnde = { },
                    fremdesSheetOffen = sheetOffen,
                )
            }
        }
        app.container.messphasen.startManuell(messgeraetErwartet = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_aufbau").assertIsDisplayed()

        sheetOffen = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_aufbau").assertDoesNotExistCompat()

        sheetOffen = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_aufbau").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() {
        assertNull(runCatching { fetchSemanticsNode() }.getOrNull())
    }
}
