package com.example.lrmprotokoll.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * M12 Schritt 8, O-5 (Owner-Entscheidung 17.09.2026: "breitere Testabdeckung" statt nur des im
 * Konzept vorgeschlagenen Minimaltests) - deckt die drei Testabsturz-Ausloeser aus dem
 * DiagnoseScreen (Schritt 1, `BuildConfig.DEBUG`) instrumentiert ab: RuntimeException,
 * OutOfMemoryError, ANR.
 *
 * **Ungewoehnliches Testdesign, absichtlich:** jede der drei Ausloese-Methoden ([a1_], [b1_],
 * [c1_]) provoziert einen ECHTEN Absturz und beendet damit ihren eigenen Instrumentierungsprozess.
 * Vorbedingung dafuer ist der Test Orchestrator (`app/build.gradle.kts`,
 * `testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"`, hier fuer M12 Schritt 8 eingefuehrt) -
 * ohne ihn liefen alle instrumentierten Tests dieses Moduls im selben Prozess wie die App unter
 * Test, und ein hier ausgeloester Absturz haette den GESAMTEN restlichen Testlauf mitgerissen.
 * Eine Testmethode kann deshalb nicht "ausloesen und im selben Aufruf pruefen" - der Prozess ist
 * zum Zeitpunkt der Pruefung bereits tot. Stattdessen je Absturzart zwei Methoden
 * ([a1_]/[a2_] usw.), `@FixMethodOrder(NAME_ASCENDING)` erzwingt die Reihenfolge. App-Daten
 * (`support_outbox/`, `process_exit_traces/`) ueberleben den Prozesswechsel (bewusst kein
 * `clearPackageData`, siehe `build.gradle.kts`) - die jeweils zweite Methode laeuft in einem
 * frischen Prozess (der wie jeder App-Start `LaermprotokollApp.onCreate()` durchlaeuft, inklusive
 * `ProcessExitCollector.auswerten()`) und prueft, was die erste (im sterbenden Prozess)
 * hinterlassen hat.
 *
 * **Fund waehrend der Umsetzung, ausserhalb des Schritt-8-Auftrags:** anders als bei RuntimeException/
 * OutOfMemoryError (ACRA faengt beide live ab, baut sofort ein `_absturz.zip`-Bundle und reiht den
 * Upload ein) gibt es fuer einen ANR AKTUELL keinen automatischen Bundle-/Upload-Pfad.
 * [com.example.lrmprotokoll.diagnose.ProcessExitCollector] (Schritt 3) sichert nur den rohen
 * `anr_trace.txt` in `process_exit_traces/` - ein vollstaendiges Bundle entsteht dafuer erst, wenn
 * jemand danach manuell exportiert (der Trace liegt dann bereits im `crash/`-Teil). Deshalb prueft
 * [c2_anrHinterlaesstEinenThreadDump] auf die rohe Trace-Datei, nicht auf ein Bundle in
 * `support_outbox/`. Das ist keine Testluecke, sondern der tatsaechliche Stand des Codes -
 * automatisches Bundeln/Hochladen von ANR-Traces waere ein eigener, hier nicht beauftragter
 * Schritt.
 *
 * **NICHT AUF ECHTER HARDWARE/EMULATOR VERIFIZIERT** (kein Geraet/Emulator in dieser
 * Entwicklungsumgebung) - insbesondere der ANR-Fall ist unsicher: ob der jeweilige Emulator/
 * CI-Runner den blockierten Main-Thread zuverlaessig und innerhalb des hier gewaehlten
 * Zeitbudgets als `REASON_ANR` in `ApplicationExitInfo` festhaelt, haengt vom System/API-Level ab.
 * Vor Verlass auf dieses Ergebnis manuell auf einem Geraet gegenpruefen
 * (`CHECKLISTE_GERAETETEST.md` Teil F).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CrashDiagnoseInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private fun supportOutboxDir(): File =
        File(appContext().filesDir, com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR)

    private fun processExitTracesDir(): File =
        File(appContext().filesDir, "process_exit_traces")

    private fun oeffneDebugAbschnittUndKlicke(buttonText: String) {
        composeRule.setContent { DiagnoseScreen(onBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIAGNOSE_LAZY_COLUMN_TAG).performScrollToIndex(1)
        composeRule.onNodeWithText(buttonText).performClick()
    }

    private fun wartenBis(timeoutMillis: Long, pruefung: () -> Boolean): Boolean {
        val ablauf = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < ablauf) {
            if (pruefung()) return true
            Thread.sleep(500)
        }
        return pruefung()
    }

    // ---------------------------------------------------------------- RuntimeException

    @Test
    fun a1_runtimeExceptionAusloesen() {
        oeffneDebugAbschnittUndKlicke("RuntimeException auslösen")
        // Der Prozess stirbt an dieser Stelle (ACRAs UncaughtExceptionHandler + restartAfterCrash
        // = false) - nichts danach in dieser Methode wird noch erreicht.
    }

    @Test
    fun a2_runtimeExceptionHinterlaesstEinAbsturzBundle() {
        val gefunden = wartenBis(20_000) {
            supportOutboxDir().listFiles { f -> f.name.endsWith("_absturz.zip") }?.isNotEmpty() == true
        }
        assertTrue(
            "Nach der RuntimeException muss ACRA ein Absturz-Bundle in support_outbox/ abgelegt haben",
            gefunden,
        )
    }

    // ---------------------------------------------------------------- OutOfMemoryError

    @Test
    fun b1_outOfMemoryErrorAusloesen() {
        oeffneDebugAbschnittUndKlicke("OutOfMemoryError provozieren")
    }

    @Test
    fun b2_outOfMemoryErrorHinterlaesstEinAbsturzBundle() {
        val gefunden = wartenBis(20_000) {
            supportOutboxDir().listFiles { f -> f.name.endsWith("_absturz.zip") }?.isNotEmpty() == true
        }
        assertTrue(
            "Nach dem OutOfMemoryError muss ACRA ein Absturz-Bundle in support_outbox/ abgelegt haben",
            gefunden,
        )
    }

    // ---------------------------------------------------------------- ANR

    @Test
    fun c1_anrAusloesen() {
        oeffneDebugAbschnittUndKlicke("Main-Thread blockieren (ANR)")
        // Blockiert den Main-Thread fuer 30s (Produktionscode, siehe DiagnoseScreen.kt) - das
        // System soll das als ANR erkennen und den Prozess irgendwann beenden. Ob und wann das
        // auf diesem Runner passiert, ist unverifiziert (siehe Klassen-KDoc).
    }

    @Test
    fun c2_anrHinterlaesstEinenThreadDump() {
        // Kein erneuter Ausloeser noetig: LaermprotokollApp.onCreate() ruft
        // ProcessExitCollector.auswerten() bereits beim (durch den Orchestrator ohnehin
        // erzwungenen) Neustart dieses Prozesses auf.
        val gefunden = wartenBis(30_000) {
            File(processExitTracesDir(), com.example.lrmprotokoll.diagnose.ANR_TRACE_DATEINAME).exists()
        }
        assertTrue(
            "Nach dem ANR sollte beim naechsten Start ein Thread-Dump (anr_trace.txt) vorliegen - " +
                "siehe Klassen-KDoc: auf diesem System nicht verifiziert, ob/wann der ANR als " +
                "REASON_ANR erkannt wird, und dass hierfuer (anders als bei RuntimeException/OOM) " +
                "kein automatisches Bundle/Upload existiert, nur die rohe Trace-Datei.",
            gefunden,
        )
    }
}
