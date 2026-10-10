package com.example.lrmprotokoll.testhilfen

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Gegenprobe zur Warteschlangen-Diagnose der [ZeitwaechterRegel] unter Robolectric: Eine in den
 * (pausierten) Main-Looper gestellte Nachricht muss in der Momentaufnahme stehen - mit der Klasse
 * ihres Callbacks bzw. Handler und `what`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZeitwaechterWarteschlangeTest {
    private class ErkennbarerAuftrag : Runnable {
        override fun run() = Unit
    }

    private class ErkennbarerHandler(
        looper: Looper,
    ) : Handler(looper)

    @Test
    fun gepostetesRunnableUndHandlerNachrichtStehenInDerMomentaufnahme() {
        val mainLooper = Looper.getMainLooper()
        Handler(mainLooper).post(ErkennbarerAuftrag())
        ErkennbarerHandler(mainLooper).sendEmptyMessage(42)

        val warteschlange = leseHauptLooperWarteschlange()

        assertTrue("Keine Momentaufnahme gelesen", warteschlange != null)
        assertTrue(
            "Runnable fehlt: $warteschlange",
            warteschlange!!.any { it.startsWith("Runnable ") && it.endsWith("ErkennbarerAuftrag") },
        )
        assertTrue(
            "Handler-Nachricht fehlt: $warteschlange",
            warteschlange.any { it.startsWith("Handler ") && it.contains("ErkennbarerHandler") && it.endsWith("what=42") },
        )
        shadowOf(mainLooper).idle()
    }
}
