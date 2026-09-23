package com.example.lrmprotokoll.ui

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.diagnose.ANR_TRACE_DATEINAME
import com.example.lrmprotokoll.diagnose.ANR_WATCHDOG_DATEINAME
import com.example.lrmprotokoll.diagnose.SystemProcessExitSource
import com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jeder Fall loest den Fehler im echten Diagnose-Screen eines separaten Debug-Prozesses aus.
 * Der Runner ueberlebt und prueft im selben Test ausschliesslich NEUE Artefakte. Keine
 * Methodenreihenfolge, kein Abfangen des Fehlers durch Compose/Espresso im Testprozess.
 */
@RunWith(AndroidJUnit4::class)
class CrashDiagnoseInstrumentedTest {
    private val app: LaermprotokollApp = ApplicationProvider.getApplicationContext()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun ausloesen(buttonText: String) {
        app.startActivity(
            Intent().setClassName(app, "com.example.lrmprotokoll.ui.CrashProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        // CI-Fund (22.09.2026, PR #182): CrashProbeActivity zeigt seit der Extraktion der
        // Buttons in CrashTriggerButtons() (DiagnoseScreen.kt) nur noch diese drei Buttons in
        // einer einfachen, NICHT scrollbaren Column - kein Scrollen mehr noetig. Ein zuvor
        // hier verwendetes UiScrollable(UiSelector().scrollable(true)) warf sogar
        // UiObjectNotFoundException("SCROLLABLE=true"): es versucht intern zuerst, zum
        // (nicht mehr vorhandenen) Listenanfang zu scrollen, bevor es ueberhaupt sucht.
        val button = device.wait(Until.findObject(By.text(buttonText)), 20_000)
        checkNotNull(button) { "Debug-Ausloeser fehlt: $buttonText" }
        button.click()
    }

    private fun wartenBis(message: String, pruefung: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pruefung()) return
            SystemClock.sleep(250)
        }
        assertTrue(message, pruefung())
    }

    private fun pruefeAbsturz(button: String, exceptionType: String) {
        val outbox = File(app.filesDir, SUPPORT_OUTBOX_DIR)
        val vorher = outbox.listFiles()?.map { it.name }?.toSet().orEmpty()
        ausloesen(button)
        wartenBis("Neues ACRA-Bundle mit $exceptionType fehlt") {
            outbox.listFiles().orEmpty().any { file ->
                file.name !in vorher && file.name.endsWith("_absturz.zip") && runCatching {
                    ZipFile(file).use { zip ->
                        val entry = zip.getEntry("crash/acra_report.json") ?: return@use false
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }.contains(exceptionType)
                    }
                }.getOrDefault(false)
            }
        }
    }

    @Test
    fun runtimeExceptionHinterlaesstEinAbsturzBundle() {
        pruefeAbsturz("RuntimeException auslösen", "RuntimeException")
    }

    @Test
    fun outOfMemoryErrorHinterlaesstEinAbsturzBundle() {
        pruefeAbsturz("OutOfMemoryError provozieren", "OutOfMemoryError")
    }

    @Test
    fun anrHinterlaesstEinenThreadDump() {
        val start = System.currentTimeMillis()
        // CI-Fund (22.09.2026, PR #182, 2. Iteration): das CI-Zielimage ist "aosp_atd" (siehe
        // emulator-tests.yml) - Googles Automated-Test-Device-Images entfernen SystemUI
        // komplett (ersetzt durch ein minimales com.android.fakesystemapp, siehe
        // https://developer.android.com/studio/test/managed-devices und
        // https://blog.emulator.wtf/posts/2022-04-15-atd-images/). Der System-ANR-Dialog wird
        // von SystemUI gerendert, kann auf diesem Image also strukturell nie erscheinen (1.
        // Iteration: dieses Warten entfernt). Laut AOSP-Quelle (AppErrors.appNotResponding())
        // toetet das System den blockierten Prozess automatisch und vermerkt REASON_ANR NUR
        // dann sofort, wenn canShowErrorDialogs() false liefert - dieses Flag (mShowDialogs)
        // ist per Default true und haengt an Settings.Global.HIDE_ERROR_DIALOGS, das hier
        // niemand setzt. Ohne diese Einstellung wartet das System auf eine Dialog-Interaktion,
        // die auf diesem Bild nie kommt: der blockierte Prozess wird dann NICHT automatisch
        // getoetet, sondern laeuft nach dem 30s-Block einfach normal weiter - kein REASON_ANR.
        // Deshalb hier explizit erzwungen, statt laenger auf ein Ereignis zu warten, das ohne
        // dieses Flag nie eintritt.
        device.executeShellCommand("settings put global hide_error_dialogs 1")
        ausloesen("Main-Thread blockieren (ANR)")
        // Erst ein weiteres Eingabeereignis macht den blockierten Main-Thread zum Input-ANR.
        device.pressBack()
        wartenBis("Neuer REASON_ANR fuer den Diagnose-Prozess fehlt") {
            SystemProcessExitSource(app).historischeExits().any {
                it.timestamp >= start && it.processName.endsWith(":crashprobe") &&
                    it.reason == ApplicationExitInfo.REASON_ANR
            }
        }
        app.container.processExitCollector.auswerten()
        val trace = File(app.filesDir, "process_exit_traces/$ANR_TRACE_DATEINAME")
        assertTrue("Der neue ANR muss einen lesbaren Thread-Dump hinterlassen", trace.lastModified() >= start && trace.length() > 0)
    }

    /**
     * O-8 (Owner-Entscheidung 23.09.2026, Teststrategie nach AGENTS.md 8b freigegeben): der
     * ANR-Watchdog erkennt den blockierten Main-Thread selbst - der einzige ANR-Beleg auf
     * Android 10. Geprueft wird die ganze Kette im echten Prozess `:crashprobe`: Erkennung nach
     * 5 s, Mitschnitt, Erholung nach dem 30-s-Block, ANR-Bundle in der Outbox.
     *
     * Anders als in [anrHinterlaesstEinenThreadDump] soll das System den Prozess hier NICHT
     * beenden, sonst gaebe es keine Erholung: `hide_error_dialogs 0` und kein weiteres
     * Eingabeereignis. Auf `aosp_atd` (ohne SystemUI) laeuft der Prozess dann nach dem Block
     * normal weiter (siehe Kommentar dort).
     */
    @Test
    fun anrWatchdogErkenntHaengerUndBautAnrBundle() {
        val start = System.currentTimeMillis()
        device.executeShellCommand("settings put global hide_error_dialogs 0")
        // Ein noch laufender :crashprobe-Prozess (etwa aus einem frueheren Lauf auf demselben
        // Geraet) haette die Einstellungen - samt Bundle-Obergrenze - schon im Speicher.
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses.orEmpty().filter { it.processName.endsWith(":crashprobe") }
            .forEach { android.os.Process.killProcess(it.pid) }
        // Obergrenze (3 Bundles je 24 h) aus frueheren Laeufen zuruecksetzen - synchron, damit
        // der neue :crashprobe-Prozess den leeren Stand von der Platte liest.
        app.getSharedPreferences("noise_settings", Context.MODE_PRIVATE).edit()
            .remove("anr_watchdog_bundle_zeitstempel").commit()
        // Ein Mitschnitt aus einem frueheren Test (etwa dem ANR-Test, dessen Prozess das System
        // beendet) darf hier nicht als Beleg durchgehen.
        val mitschnitt = File(app.filesDir, "process_exit_traces/$ANR_WATCHDOG_DATEINAME")
        mitschnitt.delete()
        val outbox = File(app.filesDir, SUPPORT_OUTBOX_DIR)
        val vorher = outbox.listFiles()?.map { it.name }?.toSet().orEmpty()

        ausloesen("Main-Thread blockieren (ANR)")

        var gefunden: String? = null
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (gefunden == null && SystemClock.elapsedRealtime() < deadline) {
            gefunden = outbox.listFiles().orEmpty()
                .filter { it.name !in vorher && it.name.endsWith("_anr.zip") }
                .firstNotNullOfOrNull { datei ->
                    runCatching {
                        ZipFile(datei).use { zip ->
                            zip.getEntry("crash/anr_watchdog.txt")?.let { eintrag ->
                                zip.getInputStream(eintrag).bufferedReader().use { it.readText() }
                            }
                        }
                    }.getOrNull()?.takeIf { text ->
                        val erkanntUm = Regex("erkanntUm: (\\S+)").find(text)?.groupValues?.get(1)
                        erkanntUm != null && Instant.parse(erkanntUm).toEpochMilli() >= start
                    }
                }
            if (gefunden == null) SystemClock.sleep(500)
        }

        // Diagnose fuer den Fehlerfall: haengt es an der Erkennung (kein Mitschnitt) oder an
        // der Erholung (Mitschnitt da, aber kein Bundle - etwa weil das System den Prozess
        // doch beendet hat)?
        val exit = SystemProcessExitSource(app).historischeExits()
            .firstOrNull { it.timestamp >= start && it.processName.endsWith(":crashprobe") }
        assertTrue(
            "Neues ANR-Bundle mit Watchdog-Mitschnitt fehlt (Mitschnitt vorhanden: ${mitschnitt.exists()}, " +
                "Prozess-Exit von :crashprobe seit Teststart: ${exit?.reason})",
            gefunden != null,
        )
        val mainAbschnitt = gefunden!!.substringAfter("---- main ----").substringBefore("---- weitere Threads ----")
        assertTrue("Der Main-Thread-Stack muss den Block zeigen:\n$mainAbschnitt", mainAbschnitt.contains("Thread.sleep"))
    }
}
