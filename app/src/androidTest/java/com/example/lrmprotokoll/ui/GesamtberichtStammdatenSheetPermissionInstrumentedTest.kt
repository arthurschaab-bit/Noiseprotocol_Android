package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

        // CI-Fund 10.09.2026 (4. Iteration, PR #132): `adb shell dumpsys package` bestaetigte
        // granted=false unmittelbar VOR diesem Testlauf, aber der echte Systemdialog erschien
        // im Logcat trotzdem nie - deutet auf einen Widerspruch zwischen der Aussensicht (dumpsys)
        // und dem, was der App-Prozess selbst per checkSelfPermission() sieht. Diese Zeile
        // schreibt die In-Prozess-Sicht explizit ins Logcat, um den naechsten CI-Fehlschlag
        // definitiv zu klaeren statt weiter von aussen zu vermuten.
        val gewaehrtLautProzess = ContextCompat.checkSelfPermission(
            InstrumentationRegistry.getInstrumentation().targetContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        Log.i("StandortPermissionDiag", "checkSelfPermission(ACCESS_COARSE_LOCATION) vor Klick: gewaehrt=$gewaehrtLautProzess")

        // CI-Fund 10.09.2026 (5. Iteration, PR #132): performClick() simuliert einen ECHTEN
        // Touch an der Bildschirmposition des Knotens - liegt diese ausserhalb des sichtbaren
        // Scroll-Ausschnitts (Column mit .verticalScroll(...).heightIn(max = 560.dp), der Button
        // steht nach 5 Textfeldern weit unten), trifft der Klick ins Leere: kein Fehler, aber
        // standortErmitteln() wird nie aufgerufen (bestaetigt per Debug-Logging in
        // GesamtberichtStammdatenSheet.kt - keine der dortigen Log-Zeilen erschien je). Foto-/
        // Video-Sheet sind kurz genug, dass ihre Buttons ohne Scrollen sichtbar sind - deshalb
        // fiel das dort nie auf. performScrollTo() bringt den Knoten zuerst in den sichtbaren
        // Bereich.
        composeRule.onNodeWithTag("button_standort_ermitteln").performScrollTo().performClick()
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

        composeRule.onNodeWithTag("button_standort_ermitteln").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Ermittle …").fetchSemanticsNodes().isEmpty()
        }
    }
}
