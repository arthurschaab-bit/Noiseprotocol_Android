package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressionstest fuer dieselbe Fehlerklasse wie #129 (siehe
 * [FotoDokumentationSheetPermissionInstrumentedTest]), hier fuer [VideoAufnahmeScreen]: die
 * CAMERA-Berechtigung wird beim Betreten des Screens per `LaunchedEffect` automatisch angefragt
 * (kein Button-Tipp noetig, anders als bei [FotoDokumentationSheet]).
 *
 * **Testtiefe:** geprueft wird nur, dass `kameraErlaubt` korrekt auf die echte
 * Berechtigungsantwort reagiert (Wechsel weg vom "Kamera-Berechtigung erforderlich"-Hinweis) -
 * NICHT, dass CameraX auf dem jeweiligen Emulator-Image tatsaechlich eine Vorschau liefert. Der
 * CI-Emulator laeuft mit `-camera-back none` (siehe `emulator-tests.yml`), eine echte
 * Kamera-Bindung ist dort ohnehin nicht sinnvoll pruefbar - das bleibt Sache der
 * Geraeteverifikation (docs/CHECKLISTE_GERAETETEST.md).
 */
@RunWith(AndroidJUnit4::class)
class VideoAufnahmeScreenPermissionInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.CAMERA)
    }

    // CI-Fund 10.09.2026 (PR #132): siehe BerechtigungsTestHelfer-KDoc - entziehe() toetet den
    // laufenden instrumentierten Prozess und reisst den gesamten Testlauf ab.
    @Ignore("entziehe() toetet den instrumentierten Prozess und reisst den ganzen Testlauf ab - siehe BerechtigungsTestHelfer-KDoc")
    @Test
    fun ohneBerechtigungFragtDerScreenBeimBetretenNachUndSchaltetNachErlaubnisWeiter() {
        BerechtigungsTestHelfer.entziehe(Manifest.permission.CAMERA)
        composeRule.setContent { VideoAufnahmeScreen(onBack = {}, onShowSnackbar = {}) }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Kamera-Berechtigung erforderlich").fetchSemanticsNodes().let {
            assertTrue("Ohne Berechtigung muss der Hinweis zunaechst sichtbar sein", it.isNotEmpty())
        }

        // Wie beim Foto-Sheet der eigentliche #129-Nachweis: Ohne dass der Screen tatsaechlich
        // den echten Systemdialog ausloest, kommt dieser Aufruf nie durch.
        BerechtigungsTestHelfer.erlaubeSystemdialog()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Kamera-Berechtigung erforderlich").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun mitBerechtigungZeigtDerScreenSofortKeinenBerechtigungshinweis() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.CAMERA)
        composeRule.setContent { VideoAufnahmeScreen(onBack = {}, onShowSnackbar = {}) }
        composeRule.waitForIdle()

        assertTrue(
            "Bei bereits erteilter Berechtigung darf der Hinweis nie erscheinen",
            composeRule.onAllNodesWithText("Kamera-Berechtigung erforderlich").fetchSemanticsNodes().isEmpty(),
        )
    }
}
