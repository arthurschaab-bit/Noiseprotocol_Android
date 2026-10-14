package com.example.lrmprotokoll.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.example.lrmprotokoll.testhilfen.ZeitwaechterRegel

/**
 * Regressionstest fuer den schwarzen Bildschirm nach einem Videobeweis (Bundles vom 14.10.2026,
 * `VIDEO_MUX_FAILED`: "Method setCurrentState must be called on the main thread").
 *
 * [beendeAufnahme] laeuft auf `container.videobeweisAbschlussScope` (Dispatchers.IO), ruft am Ende
 * aber `onFertig` - im echten Screen `navController.popBackStack()` - und `onShowSnackbar` auf.
 * Beide muessen auf dem Main-Thread landen: Navigation setzt dort den Lifecycle-Zustand der
 * Back-Stack-Eintraege und wirft sonst. Die Rueckkehr ins Cockpit scheitert, der Screen bleibt mit
 * toter Kamera-Vorschau stehen.
 *
 * Der Test startet den Abschluss wie `VideoAufnahmeScreen` auf dem App-Scope und haelt fest, auf
 * welchem Thread die UI-Callbacks ankommen. Er nutzt die Pfade ohne WorkManager (Fehlerfall und
 * "kein Ton mitgeschnitten"); der Mux-Pfad endet in denselben beiden Callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoAbschlussMainThreadTest {

    @get:Rule
    val zeitwaechter = ZeitwaechterRegel()

    private class Aufruf(val meldung: AtomicReference<String?> = AtomicReference(null)) {
        val fertigAufMain = AtomicReference<Boolean?>(null)
        val snackbarAufMain = AtomicReference<Boolean?>(null)
        val fertig = CountDownLatch(1)
    }

    private fun fuehreAbschlussAus(fehlerhaft: Boolean): Aufruf {
        val container = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container
        val aufruf = Aufruf()
        val main = Looper.getMainLooper()
        val ordner = File(System.getProperty("java.io.tmpdir"), "video-abschluss-test").apply { mkdirs() }

        container.videobeweisAbschlussScope.launch {
            beendeAufnahme(
                context = ApplicationProvider.getApplicationContext(),
                container = container,
                videoId = 1L,
                videoDatei = File(ordner, "v.mp4"),
                pcmDatei = File(ordner, "v.pcm"),
                gestartetAm = System.currentTimeMillis() - 1_000,
                fehlerhaft = fehlerhaft,
                onShowSnackbar = { msg ->
                    aufruf.meldung.set(msg)
                    aufruf.snackbarAufMain.set(main.isCurrentThread)
                },
                onFertig = {
                    aufruf.fertigAufMain.set(main.isCurrentThread)
                    aufruf.fertig.countDown()
                },
            )
        }

        // Der Test-Thread ist der Main-Thread: Looper abarbeiten, bis onFertig kam oder die Frist ablaeuft.
        val frist = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (aufruf.fertig.count > 0 && System.nanoTime() < frist) {
            shadowOf(main).idle()
            aufruf.fertig.await(20, TimeUnit.MILLISECONDS)
        }
        return aufruf
    }

    @Test
    fun fehlgeschlageneAufnahmeMeldetUndNavigiertAufDemMainThread() {
        val aufruf = fuehreAbschlussAus(fehlerhaft = true)

        assertEquals("onFertig wurde nicht aufgerufen", 0L, aufruf.fertig.count)
        assertNotNull(aufruf.meldung.get())
        assertTrue("onShowSnackbar ausserhalb des Main-Threads", aufruf.snackbarAufMain.get() == true)
        assertTrue("onFertig (popBackStack) ausserhalb des Main-Threads", aufruf.fertigAufMain.get() == true)
    }

    @Test
    fun aufnahmeOhneTonMeldetUndNavigiertAufDemMainThread() {
        val aufruf = fuehreAbschlussAus(fehlerhaft = false)

        assertEquals("onFertig wurde nicht aufgerufen", 0L, aufruf.fertig.count)
        assertNotNull(aufruf.meldung.get())
        assertTrue("onShowSnackbar ausserhalb des Main-Threads", aufruf.snackbarAufMain.get() == true)
        assertTrue("onFertig (popBackStack) ausserhalb des Main-Threads", aufruf.fertigAufMain.get() == true)
    }
}
