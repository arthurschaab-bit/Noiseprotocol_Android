package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressionstest für den Testplan-Befund (docs/TESTPLAN_INSTRUMENTIERT.md): eine nicht
 * (mehr) vorhandene Audiodatei ließ `AudioPlayerScreen` mit einer unbehandelten Exception aus
 * `mediaPlayer.setDataSource()`/`.prepare()` abstürzen. Läuft gegen die echte, produktive
 * `AudioPlayerScreen()`-Funktion mit einem bewusst nicht existierenden Pfad.
 *
 * Robolectrics `MediaPlayer`-Shadow wirft dafür KEINE `IOException` wie ein echtes Gerät,
 * sondern (ohne explizit per `ShadowMediaPlayer.addException()` konfigurierte Datenquelle) eine
 * `IllegalArgumentException` mit einer Shadow-internen Meldung ("Don't know what to do with
 * dataSource...") - selbst geprüft. Der `catch (e: Exception)` in `AudioPlayerScreen.kt` fängt
 * das trotzdem ab (Exception, nicht nur IOException), landet aber im generischen
 * `else`-Zweig von `wiedergabeFehlermeldung()`, nicht im `IOException`-spezifischen. Dieser Test
 * prüft deshalb bewusst nur den Oberbegriff "Wiedergabe fehlgeschlagen" (belegt: kein Absturz,
 * Fehler wird angezeigt) - den genaueren "gelöscht oder beschädigt"-Text für den echten
 * `IOException`-Fall deckt nur ein Emulator-Test mit einer tatsächlich fehlenden Datei ab (siehe
 * docs/TESTPLAN_INSTRUMENTIERT.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioPlayerScreenComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fehlendeDateiZeigtFehlertextStattAbzustuerzen() {
        composeRule.setContent {
            AudioPlayerScreen(filePath = "/nicht-vorhandener-pfad/verschwunden.wav", onBack = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Wiedergabe fehlgeschlagen", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Abspielen").assertIsNotEnabled()
    }
}
