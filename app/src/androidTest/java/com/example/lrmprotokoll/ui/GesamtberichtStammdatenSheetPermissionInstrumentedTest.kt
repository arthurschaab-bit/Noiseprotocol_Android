package com.example.lrmprotokoll.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.BerechtigungsTestHelfer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressionstest fuer dieselbe Fehlerklasse wie #129 (siehe
 * [FotoDokumentationSheetPermissionInstrumentedTest]), hier fuer "Standort ermitteln" in
 * [GesamtberichtStammdatenSheet]: ohne `ACCESS_COARSE_LOCATION` darf der Button nicht einfach
 * nichts tun, sondern muss danach fragen.
 *
 * **Testtiefe:** geprueft wird nur, dass die Berechtigung angefragt wird und der Ladezustand
 * danach sauber aufloest - NICHT, dass eine echte Adresse zurueckkommt. Ein CI-Emulator hat i.d.R.
 * keine GPS-Fixierung; [com.example.lrmprotokoll.standort.GeraeteStandortErmittlung] liefert dann
 * `null`, und die Oberflaeche zeigt einen Hinweis statt zu haengen - genau das wird hier geprueft.
 */
@RunWith(AndroidJUnit4::class)
class GesamtberichtStammdatenSheetPermissionInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sessionId = System.currentTimeMillis()

    @After
    fun tearDown() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // Vorbedingung "ACCESS_COARSE_LOCATION nicht gewaehrt" kommt von aussen per
    // `adb shell pm revoke`, siehe BerechtigungsTestHelfer-KDoc und
    // .github/workflows/emulator-tests.yml (CI-Fund 10.09.2026/PR #132). Ueber
    // `./gradlew connectedDebugAndroidTest` allein schlaegt dieser Test fehl, weil die
    // Berechtigung dann bereits gewaehrt ist (AGP `pm install -g`).
    @Test
    fun ohneBerechtigungFragtStandortErmittelnErstNachUndHaengtNichtEndlosImLadezustand() {
        composeRule.setContent { GesamtberichtStammdatenSheet(sessionId = sessionId, onFertig = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("button_standort_ermitteln").performClick()
        // Der eigentliche #129-Nachweis: ohne dass der Button tatsaechlich den echten
        // Systemdialog ausloest, kommt dieser Aufruf nie durch.
        BerechtigungsTestHelfer.erlaubeSystemdialog()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Ermittle …").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun mitBerechtigungLoestDerLadezustandOhneDialogAuf() {
        BerechtigungsTestHelfer.gewaehre(Manifest.permission.ACCESS_COARSE_LOCATION)
        composeRule.setContent { GesamtberichtStammdatenSheet(sessionId = sessionId, onFertig = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("button_standort_ermitteln").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Ermittle …").fetchSemanticsNodes().isEmpty()
        }
    }
}
