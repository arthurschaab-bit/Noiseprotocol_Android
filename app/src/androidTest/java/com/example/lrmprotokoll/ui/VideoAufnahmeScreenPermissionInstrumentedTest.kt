package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import org.junit.After
import org.junit.Assert.assertTrue
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

    // Vorbedingung "CAMERA nicht gewaehrt" kommt von aussen per `adb shell pm revoke`, siehe
    // BerechtigungsTestHelfer-KDoc und .github/workflows/emulator-tests.yml (CI-Fund 10.09.2026/
    // PR #132). Ueber `./gradlew connectedDebugAndroidTest` allein schlaegt dieser Test fehl, weil
    // CAMERA dann bereits gewaehrt ist (AGP `pm install -g`).
    //
    // CI-Fund 10.09.2026 (2. Iteration): KEINE Pruefung auf den Hinweistext direkt nach
    // setContent()/waitForIdle() - die LaunchedEffect-Anfrage feuert so unmittelbar, dass der
    // echte Systemdialog (GrantPermissionsActivity) zu diesem Zeitpunkt bereits den Fokus
    // uebernommen haben kann; die Compose-Test-Abfrage schlug dann mit "No compose hierarchies
    // found in the app" fehl, weil die Activity in dem Moment nicht (mehr) im Vordergrund war.
    // Der eigentliche #129-Nachweis (Dialog erscheint wirklich UND der Hinweis verschwindet
    // danach) bleibt unveraendert bestehen.
    @Test
    fun ohneBerechtigungFragtDerScreenBeimBetretenNachUndSchaltetNachErlaubnisWeiter() {
        composeRule.setContent { VideoAufnahmeScreen(onBack = {}, onShowSnackbar = {}) }

        // Der eigentliche #129-Nachweis: Ohne dass der Screen tatsaechlich den echten
        // Systemdialog ausloest, kommt dieser Aufruf nie durch.
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
