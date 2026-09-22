package com.example.lrmprotokoll.ui

import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.diagnose.ANR_TRACE_DATEINAME
import com.example.lrmprotokoll.diagnose.SystemProcessExitSource
import com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR
import java.io.File
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
        assertTrue("Diagnose-Prozess muss sichtbar werden", device.wait(Until.hasObject(By.pkg(app.packageName)), 20_000))
        val scroll = UiScrollable(UiSelector().scrollable(true))
        assertTrue("Debug-Ausloeser muss sichtbar sein: $buttonText", scroll.scrollTextIntoView(buttonText))
        val button = device.findObject(By.text(buttonText))
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
        ausloesen("Main-Thread blockieren (ANR)")
        // Erst ein weiteres Eingabeereignis macht den blockierten Main-Thread zum Input-ANR.
        device.pressBack()
        val close = device.wait(Until.findObject(By.res("android", "aerr_close")), 60_000)
        checkNotNull(close) { "Android hat keinen ANR-Dialog angezeigt" }
        close.click()
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
}
