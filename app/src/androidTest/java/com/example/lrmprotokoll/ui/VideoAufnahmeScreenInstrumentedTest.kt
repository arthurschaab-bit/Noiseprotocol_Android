package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Geraetetest-Checkliste F6: [VideoAufnahmeScreen] war bislang kompiliert und lint-sauber, aber
 * noch nie auf einem echten Bildschirm gesehen worden - [VideoAufnahmeScreenPermissionInstrumentedTest]
 * deckt nur die CAMERA-Berechtigungsanfrage ab, nicht das eigentliche Rendering.
 *
 * Der CI-Emulator laeuft mit `-camera-back none` (siehe emulator-tests.yml) - `bindToLifecycle()`
 * scheitert dort also fuer die Rueckkamera und wird von `runCatching` abgefangen (siehe
 * VideoAufnahmeScreen.kt). Genau das ist hier die eigentliche Pruefung: Titel und Zurueck-Button
 * muessen unabhaengig davon sichtbar bleiben und ohne laufende Aufnahme sofort funktionieren -
 * kein Absturz, keine haengende Kamera-Initialisierung.
 *
 * Checkliste Button/Screen-Coverage Phase 1a: eine echte Start/Stop-Interaktion der eigentlichen
 * Aufnahme (wie urspruenglich fuer diese Phase vorgesehen) ist auf dem CI-Emulator NICHT
 * pruefbar - `videoCapture` bleibt dort wegen `-camera-back none` immer `null`, der
 * Start-`LaunchedEffect` (Zeile 206 in VideoAufnahmeScreen.kt) kehrt deshalb immer sofort zurueck
 * und `laeuft` (und damit der Stop-Button) wird nie `true`. Stattdessen deckt
 * [mikrofonWarnungErscheintNurOhneLaufendesMikrofonFormat] die einzige andere echte,
 * kamera-unabhaengige Zustandslogik dieses Screens ab: die "ohne Ton"-Warnung haengt direkt an
 * [AudioRecordingService.laufendesFormat], nicht an der Kamera.
 */
@RunWith(AndroidJUnit4::class)
class VideoAufnahmeScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.CAMERA)
    }

    @After
    fun tearDown() {
        // Companion-StateFlow, geteilt mit allen anderen Tests dieses Prozesses - muss
        // zurueckgesetzt werden, sonst wirkt sich dieser Test auf spaeter laufende Tests aus
        // (z.B. MicrophoneStatusBadgeInstrumentedTest), die denselben Zustand lesen.
        AudioRecordingService.testSetzeLaufendesFormat(null)
    }

    @Test
    fun videoAufnahmeScreenZeigtTitelUndErlaubtZurueckOhneLaufendeAufnahme() {
        var backed = false

        composeRule.setContent {
            VideoAufnahmeScreen(onBack = { backed = true }, onShowSnackbar = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Videobeweis").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Zurück").assertIsDisplayed().performClick()

        assertTrue(
            "Ohne laufende Aufnahme muss onBack sofort ausgeloest werden",
            backed,
        )
    }

    @Test
    fun mikrofonWarnungErscheintNurOhneLaufendesMikrofonFormat() {
        AudioRecordingService.testSetzeLaufendesFormat(null)

        composeRule.setContent {
            VideoAufnahmeScreen(onBack = {}, onShowSnackbar = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "Das Mikrofon läuft nicht – dieses Video wird ohne Ton aufgezeichnet.",
        ).assertIsDisplayed()

        AudioRecordingService.testSetzeLaufendesFormat(AudioRecordingService.Aufnahmeformat(abtastrate = 44_100, kanaele = 1))
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(
            "Das Mikrofon läuft nicht – dieses Video wird ohne Ton aufgezeichnet.",
        ).assertCountEquals(0)
    }
}
