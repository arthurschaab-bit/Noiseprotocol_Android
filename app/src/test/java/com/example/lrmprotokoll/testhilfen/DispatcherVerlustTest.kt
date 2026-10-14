package com.example.lrmprotokoll.testhilfen

import android.os.Looper
import android.view.Choreographer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPausedLooper
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Positivkontrolle fuer Hypothese C-H10 (Untersuchung AppNotIdle, PR #280): Robolectric ruft zu
 * Beginn jedes Tests `ShadowPausedLooper.resetLoopers()` (im Bytecode von 4.16.1 gelesen). Das
 * verwirft alle anstehenden Nachrichten - `AndroidUiDispatcher.Main` ist aber ein statischer
 * Singleton je Sandbox und merkt sich in `scheduledTrampolineDispatch` / `scheduledFrameDispatch`
 * weiter, eine Nachricht sei unterwegs. Er postet dann nie wieder.
 *
 * Diese Tests zeigen NUR, dass dieser Mechanismus die gemessene Haenger-Signatur erzeugt
 * (Flags wahr, eine wartende Fortsetzung, keine Nachricht im Looper). Sie zeigen NICHT, dass er in
 * der CI so ausgeloest wird - das ist die offene Frage.
 *
 * Die Sandbox ist mit anderen Testklassen geteilt. Ein liegengebliebener Dispatcher wuerde dort
 * Folgetests haengen lassen, genau das, was die Untersuchung sucht. Deshalb wird in `finally`
 * immer aufgeraeumt, und der Test ueberspringt sich, wenn der Dispatcher schon vorher nicht sauber ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DispatcherVerlustTest {
    @Test
    fun einVerworfenerDispatchHinterlaesstDieHaengerSignatur() {
        val dispatcher = dispatcherOderNull()
        assumeTrue("Dispatcher nicht saeuberlich vorgefunden - Test uebersprungen", dispatcher != null && istSauber())
        try {
            dispatche(dispatcher!!)
            ShadowPausedLooper.resetLoopers() // genau das, was Robolectric zu Beginn jedes Tests tut

            val z = IdlingGruende.leseDispatcher()

            assertEquals("Flag trampoline: $z", true, z?.trampolineGeplant)
            assertEquals("Flag frame: $z", true, z?.frameGeplant)
            assertEquals("wartende Fortsetzung: $z", 1, z?.wartend)
            assertEquals("Handler am Main-Looper: $z", true, z?.handlerAmMainLooper)
            assertEquals("Nachricht muss verworfen sein: $z", false, z?.dispatchNachrichtInQueue)
        } finally {
            raeumeAuf(dispatcher!!)
        }
        val danach = IdlingGruende.leseDispatcher()
        assertEquals("Aufraeumen muss den Dispatcher heilen: $danach", false, danach?.trampolineGeplant)
        assertEquals("Aufraeumen muss den Dispatcher heilen: $danach", false, danach?.frameGeplant)
    }

    @Test
    fun einWartenderDispatchWirdAlsNachrichtInDerQueueGelesen() {
        val dispatcher = dispatcherOderNull()
        assumeTrue("Dispatcher nicht saeuberlich vorgefunden - Test uebersprungen", dispatcher != null && istSauber())
        try {
            dispatche(dispatcher!!)

            val z = IdlingGruende.leseDispatcher()

            assertEquals("Nachricht muss in der Queue stehen: $z", true, z?.dispatchNachrichtInQueue)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            raeumeAuf(dispatcher!!)
        }
    }

    private fun dispatcherOderNull(): Any? =
        runCatching {
            val klasse = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher")
            val companion = klasse.getField("Companion").get(null)
            val kontext = companion.javaClass.getMethod("getMain").invoke(companion) as CoroutineContext
            kontext[ContinuationInterceptor]
        }.getOrNull()

    private fun istSauber(): Boolean {
        val z = IdlingGruende.leseDispatcher() ?: return false
        return z.initialisiert && z.trampolineGeplant == false && z.frameGeplant == false && z.wartend == 0
    }

    private fun dispatche(dispatcher: Any) {
        dispatcher.javaClass
            .getMethod("dispatch", CoroutineContext::class.java, Runnable::class.java)
            .invoke(dispatcher, EmptyCoroutineContext, Runnable { })
    }

    /** Fuehrt aus, was der Dispatcher selbst bei Ankunft der Nachricht und des Frames taete. */
    private fun raeumeAuf(dispatcher: Any) {
        val callback =
            runCatching {
                val feld = dispatcher.javaClass.getDeclaredField("dispatchCallback")
                feld.isAccessible = true
                feld.get(dispatcher)
            }.getOrNull()
        (callback as? Runnable)?.run()
        (callback as? Choreographer.FrameCallback)?.doFrame(0)
    }
}
