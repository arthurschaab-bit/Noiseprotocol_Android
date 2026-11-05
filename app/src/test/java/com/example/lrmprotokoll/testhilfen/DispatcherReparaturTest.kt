package com.example.lrmprotokoll.testhilfen

import android.os.Handler
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.util.concurrent.TimeUnit

/**
 * Nachweis fuer [DispatcherReparatur] (Untersuchung AppNotIdle, PR #280) - Ende zu Ende gegen den
 * echten Mechanismus, nicht gegen einen Nachbau.
 *
 * Der verlorene Dispatch wird so erzeugt: ein echter globaler Schreibzugriff setzt `sent` und stellt
 * die Fortsetzung des `GlobalSnapshotManager`-Konsumenten beim Dispatcher ein (Flag wahr, Nachricht
 * und Frame-Callback gepostet); dann werden genau diese beiden entfernt - das Ergebnis von
 * `ShadowPausedLooper.resetLoopers()` zu Beginn des naechsten Tests. Den Reset selbst mitten im Test
 * aufzurufen wuerde auch die Activity der Compose-Regel zerlegen.
 *
 * Die Sandbox ist mit anderen Testklassen geteilt; ein liegengebliebener Dispatcher haengte dort
 * Folgetests. Beide Tests heilen deshalb in `finally` und ueberspringen sich, wenn der Dispatcher schon
 * vorher nicht sauber ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DispatcherReparaturTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun einGesunderDispatcherWirdNichtAngefasst() {
        composeRule.setContent { Text("Hallo") }
        composeRule.waitForIdle()

        assertNull("Gesunder Dispatcher: keine Reparatur", DispatcherReparatur.pruefeUndHeile())
    }

    @Test
    fun ohneReparaturHaengtDerAngewandteSchreibzugriffWirklich() {
        composeRule.setContent { Text("Hallo") }
        composeRule.waitForIdle()
        val dispatcher = IdlingGruende.dispatcherFallsVorhanden()
        assumeTrue("Dispatcher nicht saeuberlich vorgefunden - Test uebersprungen", dispatcher != null && istSauber())
        val vorher = idleTimeoutSekunden()
        try {
            setzeIdleTimeoutSekunden(KURZER_TIMEOUT_S)
            erzeugeVerlorenenDispatch(dispatcher!!)

            val ausnahme = runCatching { composeRule.waitForIdle() }.exceptionOrNull()

            assertEquals(
                "Ohne Reparatur muss waitForIdle in AppNotIdleException laufen: $ausnahme",
                "AppNotIdleException",
                ausnahme?.javaClass?.simpleName,
            )
            val gruende = IdlingGruende.lies(this).gruende
            val wahr = gruende.filterValues { it }.keys.toList()
            assertEquals("Nur hadSnapshotChanges darf wahr sein: $gruende", listOf("hadSnapshotChanges"), wahr)
        } finally {
            setzeIdleTimeoutSekunden(vorher)
            DispatcherReparatur.pruefeUndHeile()
        }
    }

    @Test
    fun eineReparaturLoestDenHaengerDenEinVerlorenerDispatchErzeugt() {
        composeRule.setContent { Text("Hallo") }
        composeRule.waitForIdle()
        val dispatcher = IdlingGruende.dispatcherFallsVorhanden()
        assumeTrue("Dispatcher nicht saeuberlich vorgefunden - Test uebersprungen", dispatcher != null && istSauber())
        try {
            erzeugeVerlorenenDispatch(dispatcher!!)
            val verloren = IdlingGruende.leseDispatcher()
            assertEquals("Vorbedingung Flag: $verloren", true, verloren?.trampolineGeplant)
            assertEquals("Vorbedingung Nachricht verloren: $verloren", false, verloren?.dispatchNachrichtInQueue)
            assertEquals("Vorbedingung sent", true, IdlingGruende.leseGlobalSnapshotManager().second)
            assertEquals("Vorbedingung ausstehende Aenderung", true, Snapshot.current.hasPendingChanges())

            val bericht = DispatcherReparatur.pruefeUndHeile()

            assertNotNull("Reparatur muss anspringen", bericht)
            assertTrue("Reparatur muss gelingen: ${bericht?.text("test")}", bericht!!.repariert)
            composeRule.waitForIdle() // haengt ohne Reparatur (siehe der Test darueber)
            assertEquals("Danach darf nichts mehr ausstehen", false, Snapshot.current.hasPendingChanges())
            assertEquals("Konsument muss sent zurueckgesetzt haben", false, IdlingGruende.leseGlobalSnapshotManager().second)
            assertTrue("Dispatcher muss sauber sein: ${IdlingGruende.leseDispatcher()}", istSauber())
            assertTrue("Der Bericht nennt den Testnamen", bericht.text("mein-test").contains("DISPATCHER-REPARATUR: mein-test"))
        } finally {
            DispatcherReparatur.pruefeUndHeile()
        }
    }

    /** Wird nur gebraucht, damit [FrameworkMethod] eine echte Methode bekommt. */
    fun platzhalter() = Unit

    @Test
    fun dieWaechterRegelRepariertZuBeginnEinesTestsUndSchreibtDenBericht() {
        composeRule.setContent { Text("Hallo") }
        composeRule.waitForIdle()
        val dispatcher = IdlingGruende.dispatcherFallsVorhanden()
        assumeTrue("Dispatcher nicht saeuberlich vorgefunden - Test uebersprungen", dispatcher != null && istSauber())
        File(ZeitwaechterRegel.DIAGNOSEDATEI).delete()
        try {
            erzeugeVerlorenenDispatch(dispatcher!!)
            val methode = FrameworkMethod(DispatcherReparaturTest::class.java.getMethod("platzhalter"))
            val rumpf =
                object : Statement() {
                    override fun evaluate() {
                        composeRule.waitForIdle() // haengt 60 s, wenn die Regel nicht vorher repariert
                    }
                }
            val statement = ZeitwaechterRegel(grenzeSekunden = 30).apply(rumpf, methode, this)

            val ausgabe = fangeFehlerausgabe { statement.evaluate() }

            assertTrue("Berichtskopf fehlt: $ausgabe", ausgabe.contains("=== DISPATCHER-REPARATUR: "))
            assertTrue("Ergebnis fehlt: $ausgabe", ausgabe.contains("Ergebnis: repariert"))
            assertTrue("Diagnosedatei fehlt", File(ZeitwaechterRegel.DIAGNOSEDATEI).readText().contains("DISPATCHER-REPARATUR"))
        } finally {
            DispatcherReparatur.pruefeUndHeile()
        }
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

    private fun istSauber(): Boolean {
        val z = IdlingGruende.leseDispatcher() ?: return false
        val sent = IdlingGruende.leseGlobalSnapshotManager().second
        return z.initialisiert && z.trampolineGeplant == false && z.frameGeplant == false && z.wartend == 0 && sent == false
    }

    /**
     * Echter globaler Schreibzugriff, dann Nachricht und Frame-Callback des Dispatchers entfernen.
     * `notifyObjectsInitialized` zuerst: ein im selben Snapshot frisch angelegter State meldet den
     * Schreibzugriff zwar (`sent` wird wahr), hinterlaesst aber keine ausstehende Aenderung (gemessen).
     * Der echte Haenger schreibt in lange bestehenden State.
     */
    private fun erzeugeVerlorenenDispatch(dispatcher: Any) {
        val zustand = mutableIntStateOf(0)
        Snapshot.notifyObjectsInitialized()
        zustand.intValue = 1
        val callback = IdlingGruende.leseFeld(dispatcher, "dispatchCallback")
        val handler = IdlingGruende.leseFeld(dispatcher, "handler") as Handler
        val choreographer = IdlingGruende.leseFeld(dispatcher, "choreographer") as Choreographer
        handler.removeCallbacks(callback as Runnable)
        choreographer.removeFrameCallback(callback as Choreographer.FrameCallback)
    }

    /** Espresso ist beim Uebersetzen der Unit-Tests nicht auf dem Klassenpfad, zur Laufzeit schon. */
    private fun idleTimeoutSekunden(): Long =
        runCatching {
            val klasse = Class.forName("androidx.test.espresso.IdlingPolicies")
            val policy = klasse.getMethod("getMasterIdlingPolicy").invoke(null)
            val wert = policy.javaClass.getMethod("getIdleTimeout").invoke(policy) as Long
            val einheit = policy.javaClass.getMethod("getIdleTimeUnit").invoke(policy) as TimeUnit
            einheit.toSeconds(wert)
        }.getOrDefault(STANDARD_TIMEOUT_S)

    private fun setzeIdleTimeoutSekunden(sekunden: Long) {
        val klasse = Class.forName("androidx.test.espresso.IdlingPolicies")
        val methode = klasse.getMethod("setMasterPolicyTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
        methode.invoke(null, sekunden, TimeUnit.SECONDS)
    }

    private companion object {
        const val KURZER_TIMEOUT_S = 5L
        const val STANDARD_TIMEOUT_S = 60L
    }
}
