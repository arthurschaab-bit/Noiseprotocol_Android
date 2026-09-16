package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
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
 */
@RunWith(AndroidJUnit4::class)
class VideoAufnahmeScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.CAMERA)
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
}
