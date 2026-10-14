package com.example.lrmprotokoll.testhilfen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SONDE, kein Dauertest (Untersuchung AppNotIdle, PR #280). Sie beantwortet zwei Fragen, bevor
 * die Diagnose des Zeitwächters darauf aufbaut:
 *
 * 1. Kommt man unter Robolectric per Reflexion an die fünf "nicht idle"-Gründe von
 *    `ComposeIdlingResource` (`hadAwaitersOnMainClock`, `hadSnapshotChanges`, `hadRecomposerChanges`,
 *    `hadPendingSetContent`, `hadPendingMeasureLayout`)?
 * 2. Wie sieht jeder Grund in einem ABSICHTLICH erzeugten Hänger aus (Positivkontrolle)?
 *
 * Die Hänger enden nach [KURZES_LIMIT_S] statt 60 s, weil Espressos Richtlinie hier verkürzt wird.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdlingGruendeSondeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Espresso steht für Unit-Tests nicht zur Compile-Zeit bereit (nur androidTest), zur Laufzeit
     * aber schon - deshalb per Reflexion statt einer neuen Abhängigkeit für eine Sonde.
     */
    private fun setzeEspressoLimit(sekunden: Long) {
        Class.forName("androidx.test.espresso.IdlingPolicies")
            .getMethod("setMasterPolicyTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
            .invoke(null, sekunden, TimeUnit.SECONDS)
    }

    @Before
    fun kurzesLimit() = setzeEspressoLimit(KURZES_LIMIT_S)

    @After
    fun limitZuruecksetzen() = setzeEspressoLimit(60)

    /** Liest die fünf Felder; Namen und Pfad stammen aus compose-ui-test 1.6.7 (Quelltext gelesen). */
    private fun gruende(): Map<String, Boolean> {
        val umgebung = composeRule.javaClass.getDeclaredField("environment")
            .apply { isAccessible = true }.get(composeRule)
        val umgebungsKlasse = generateSequence<Class<*>>(umgebung.javaClass) { it.superclass }
            .first { k -> k.declaredFields.any { it.name == "composeIdlingResource" } }
        val ressource = umgebungsKlasse.getDeclaredField("composeIdlingResource")
            .apply { isAccessible = true }.get(umgebung)
        return FELDER.associateWith { name ->
            ressource.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(ressource)
        }
    }

    /** Startet einen Beobachter, der nach [verzoegerungMs] die Gründe einmal liest. */
    private fun beobachter(verzoegerungMs: Long): Pair<Thread, AtomicReference<Map<String, Boolean>?>> {
        val ergebnis = AtomicReference<Map<String, Boolean>?>(null)
        val thread = Thread {
            Thread.sleep(verzoegerungMs)
            ergebnis.set(runCatching { gruende() }.getOrNull())
        }.apply { isDaemon = true; start() }
        return thread to ergebnis
    }

    @Test
    fun normalerLeerlaufMeldetKeinenGrund() {
        composeRule.setContent { Text("Hallo") }
        composeRule.waitForIdle()
        val g = gruende()
        println("SONDE normal: $g")
        assertEquals("Im Leerlauf darf kein Grund gesetzt sein: $g", emptyList<String>(), g.filterValues { it }.keys.toList())
    }

    @Test
    fun handgeschriebeneFrameSchleifeHaeltDieAwaiterFest() {
        val (thread, ergebnis) = beobachter(3_000)
        val start = System.nanoTime()
        val ausnahme = runCatching {
            composeRule.setContent {
                LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { } }
                Text("Schleife")
            }
        }.exceptionOrNull()
        thread.join(2_000)
        println(
            "SONDE frameSchleife: nach ${(System.nanoTime() - start) / 1_000_000} ms " +
                "ausnahme=${ausnahme?.javaClass?.simpleName} meldung=${ausnahme?.message?.take(160)} gruende=${ergebnis.get()}",
        )
        assertTrue(
            "Erwartet AppNotIdleException, war: $ausnahme",
            ausnahme?.javaClass?.name == "androidx.test.espresso.AppNotIdleException",
        )
        // Gemessen (14.10.2026): eine withFrameNanos-Schleife erscheint als hadRecomposerChanges, nicht
        // als hadAwaitersOnMainClock - Recomposer.hasPendingWork schliesst eigene Frame-Wartende ein.
        assertEquals(true, ergebnis.get()?.get("hadRecomposerChanges"))
    }

    @Test
    fun fremderThreadSchreibtDauerhaftInSnapshotState() {
        val laeuft = AtomicBoolean(true)
        val zaehler = AtomicReference<androidx.compose.runtime.MutableIntState?>(null)
        val schreiber = Thread {
            while (laeuft.get()) {
                zaehler.get()?.let { it.intValue = it.intValue + 1 }
            }
        }.apply { isDaemon = true }
        val (thread, ergebnis) = beobachter(3_000)
        val start = System.nanoTime()
        val ausnahme = try {
            runCatching {
                composeRule.setContent {
                    val z = remember { mutableIntStateOf(0) }
                    zaehler.set(z)
                    schreiber.takeIf { !it.isAlive }?.start()
                    Column { Text("Wert ${z.intValue}") }
                }
                composeRule.waitForIdle()
            }.exceptionOrNull()
        } finally {
            laeuft.set(false)
        }
        thread.join(2_000)
        println(
            "SONDE fremdSchreiber: nach ${(System.nanoTime() - start) / 1_000_000} ms " +
                "ausnahme=${ausnahme?.javaClass?.simpleName} meldung=${ausnahme?.message?.take(160)} gruende=${ergebnis.get()}",
        )
    }

    private companion object {
        const val KURZES_LIMIT_S = 6L
        val FELDER = listOf(
            "hadAwaitersOnMainClock",
            "hadSnapshotChanges",
            "hadRecomposerChanges",
            "hadPendingSetContent",
            "hadPendingMeasureLayout",
        )
    }
}
