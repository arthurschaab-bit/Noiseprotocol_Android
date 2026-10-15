package com.example.lrmprotokoll.testhilfen

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Nachweis fuer [IdlingGruende] und die Erweiterung der [ZeitwaechterRegel] (Untersuchung
 * AppNotIdle, PR #280): Ohne Gegenprobe waere die Diagnose nur eine Behauptung.
 *
 * Die Positivkontrolle laeuft mit `mainClock.autoAdvance = false`: `isIdleNow` setzt die Felder dann
 * trotzdem, pumpt aber nicht und kehrt sofort zurueck. So steht der Grund einer
 * `withFrameNanos`-Schleife in unter einer Sekunde im Feld, statt dass der Test in den
 * 60-Sekunden-Haenger laeuft (der in der Sonde gemessene Weg dauerte 54 s).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdlingGruendeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Wird nur gebraucht, damit [FrameworkMethod] eine echte Methode bekommt. */
    fun platzhalter() = Unit

    @Test
    fun imLeerlaufIstKeinGrundGesetzt() {
        composeRule.setContent { Text("Hallo") }
        composeRule.waitForIdle()

        val aufnahme = IdlingGruende.lies(this)

        assertEquals(emptyList<String>(), aufnahme.fehler)
        assertEquals(IdlingGruende.FELDER.toSet(), aufnahme.gruende.keys)
        assertEquals(
            "Im Leerlauf darf kein Grund gesetzt sein: ${aufnahme.gruende}",
            emptyList<String>(),
            aufnahme.gruende
                .filterValues { it }
                .keys
                .toList(),
        )
        assertNotNull("Testuhr muss lesbar sein", aufnahme.testzeitMs)
        assertNotNull("Roots muessen lesbar sein", aufnahme.roots)
        assertEquals("GlobalSnapshotManager muss gestartet sein", true, aufnahme.gsmGestartet)
        assertNotNull("sent muss lesbar sein", aufnahme.gsmSent)
        val dispatcher = aufnahme.dispatcher
        assertNotNull("AndroidUiDispatcher muss lesbar sein", dispatcher)
        assertEquals("Der Singleton ist nach dem Start des GlobalSnapshotManager angelegt", true, dispatcher!!.initialisiert)
        assertNotNull("scheduledTrampolineDispatch muss lesbar sein", dispatcher.trampolineGeplant)
        assertNotNull("scheduledFrameDispatch muss lesbar sein", dispatcher.frameGeplant)
        assertNotNull("toRunTrampolined muss lesbar sein", dispatcher.wartend)
        assertEquals("Handler muss am Main-Looper haengen", true, dispatcher.handlerAmMainLooper)
        assertNotNull("Queue-Pruefung muss lesbar sein", dispatcher.dispatchNachrichtInQueue)
    }

    /**
     * Positivkontrolle fuer das LESEN: ein bekannter Wert (Flag kuenstlich gesetzt) muss ankommen.
     * Die Sandbox ist mit anderen Testklassen geteilt - das Flag wird deshalb in jedem Fall zurueckgesetzt,
     * sonst bliebe der Dispatcher fuer Folgetests tot (genau der Mechanismus, den die Messung sucht).
     */
    @Test
    fun einGesetztesDispatchFlagWirdAlsWahrGelesen() {
        composeRule.setContent { Text("Hallo") }
        composeRule.waitForIdle()
        val dispatcher = dispatcherInstanz()
        val feld = dispatcher.javaClass.getDeclaredField("scheduledTrampolineDispatch").apply { isAccessible = true }
        val vorher = feld.getBoolean(dispatcher)
        try {
            feld.setBoolean(dispatcher, true)

            val gelesen = IdlingGruende.leseDispatcher()

            assertEquals("Gesetztes Flag muss als wahr ankommen: $gelesen", true, gelesen?.trampolineGeplant)
        } finally {
            feld.setBoolean(dispatcher, vorher)
        }
    }

    @Test
    fun dieAuswertungZaehltDieDispatcherFelderProStichprobe() {
        val stichproben = IdlingStichproben()
        listOf(true, true, false).forEachIndexed { i, geplant ->
            stichproben.fuegeHinzu(
                wanduhrMs = i * 50L,
                aufnahme =
                    IdlingGruende.Momentaufnahme(
                        gruende = emptyMap(),
                        testzeitMs = null,
                        roots = null,
                        fehler = emptyList(),
                        dispatcher =
                            IdlingGruende.Dispatcherzustand(
                                initialisiert = true,
                                trampolineGeplant = geplant,
                                frameGeplant = false,
                                wartend = if (geplant) 2 else 0,
                            ),
                    ),
            )
        }

        val text = stichproben.text()

        assertTrue("Abschnitt fehlt: $text", text.contains("--- AndroidUiDispatcher.Main"))
        assertTrue("trampoline 2 / 3 fehlt: $text", Regex("""scheduledTrampolineDispatch\s+2 / 3""").containsMatchIn(text))
        assertTrue("frame 0 / 3 fehlt: $text", Regex("""scheduledFrameDispatch\s+0 / 3""").containsMatchIn(text))
        assertTrue(
            "wartend 2 / 3 fehlt: $text",
            Regex("""nicht leer\s+2 / 3 \(hoechstens 2 Eintraege\)""").containsMatchIn(text),
        )
    }

    private fun dispatcherInstanz(): Any {
        val klasse = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher")
        val verzoegert = klasse.getDeclaredField("Main\$delegate").apply { isAccessible = true }.get(null) as Lazy<*>
        return (verzoegert.value as CoroutineContext)[ContinuationInterceptor]!!
    }

    @Test
    fun eineFrameSchleifeErscheintAlsRecomposerGrund() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LaunchedEffect(Unit) { while (true) withFrameNanos { } }
            Text("Schleife")
        }
        composeRule.waitForIdle()

        val aufnahme = IdlingGruende.lies(this)

        // Gemessen (14.10.2026, compose-ui-test 1.6.7): Recomposer.hasPendingWork schliesst eigene
        // Frame-Wartende ein - die Schleife erscheint hier, nicht unter hadAwaitersOnMainClock.
        assertEquals("Grund fehlt: ${aufnahme.gruende}", true, aufnahme.gruende["hadRecomposerChanges"])
        assertEquals(false, aufnahme.gruende["hadPendingMeasureLayout"])
    }

    @Test
    fun ohneComposeRegelIstDieDiagnoseNichtVerfuegbarAberKeinFehler() {
        val aufnahme = IdlingGruende.lies(Object())

        assertTrue(aufnahme.gruende.isEmpty())
        assertNull(aufnahme.testzeitMs)
        assertTrue("Der Grund muss genannt sein: ${aufnahme.fehler}", aufnahme.fehler.isNotEmpty())
    }

    @Test
    fun dieWaechterRegelSchreibtDieIdlingGruendeInIhreDiagnose() {
        File(ZeitwaechterRegel.DIAGNOSEDATEI).delete()
        composeRule.mainClock.autoAdvance = false
        val regel = ZeitwaechterRegel(grenzeSekunden = 1)
        val methode = FrameworkMethod(IdlingGruendeTest::class.java.getMethod("platzhalter"))
        val statement =
            regel.apply(
                object : Statement() {
                    override fun evaluate() {
                        composeRule.setContent {
                            LaunchedEffect(Unit) { while (true) withFrameNanos { } }
                            Text("Schleife")
                        }
                        composeRule.waitForIdle()
                        Thread.sleep(2_500)
                    }
                },
                methode,
                this,
            )

        val ausgabe = fangeFehlerausgabe { statement.evaluate() }

        assertTrue("Kopfzeile fehlt: $ausgabe", ausgabe.contains("=== ZEITWAECHTER:"))
        assertTrue("Abschnitt fehlt: $ausgabe", ausgabe.contains("--- Idling-Gruende"))
        val treffer = Regex("""hadRecomposerChanges\s+(\d+) / (\d+)""").find(ausgabe)
        assertNotNull("Zeile hadRecomposerChanges fehlt: $ausgabe", treffer)
        val wahr = treffer!!.groupValues[1].toInt()
        assertTrue("hadRecomposerChanges muss in mindestens einer Stichprobe wahr sein: ${treffer.value}", wahr > 0)
        assertTrue("Testuhr-Abschnitt fehlt: $ausgabe", ausgabe.contains("--- Testuhr"))
        assertTrue("Roots-Abschnitt fehlt: $ausgabe", ausgabe.contains("--- Compose-Roots"))
        assertTrue("Zweig-Abschnitt fehlt: $ausgabe", ausgabe.contains("--- Zweig der Idle-Schleife"))
        assertTrue("JVM-Abschnitt fehlt: $ausgabe", ausgabe.contains("--- Zustand der Test-JVM"))
        assertTrue("GSM-Abschnitt fehlt: $ausgabe", ausgabe.contains("--- GlobalSnapshotManager"))
        assertTrue("Dispatcher-Abschnitt fehlt: $ausgabe", ausgabe.contains("--- AndroidUiDispatcher.Main"))
        assertTrue("Schreiber-Abschnitt fehlt: $ausgabe", ausgabe.contains("--- Globale Schreibzugriffe"))
        assertTrue("Diagnosedatei fehlt", File(ZeitwaechterRegel.DIAGNOSEDATEI).readText().contains("Idling-Gruende"))
    }

    private fun fangeFehlerausgabe(block: () -> Unit): String {
        val original = System.err
        val puffer = ByteArrayOutputStream()
        System.setErr(PrintStream(puffer, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return puffer.toString("UTF-8")
    }
}
