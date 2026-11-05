package com.example.lrmprotokoll.testhilfen

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Nachweis fuer [DispatcherPlugin] ueber die Testgrenze (Untersuchung AppNotIdle, PR #280) - gegen den echten
 * Reset von Robolectric, nicht gegen einen Nachbau.
 *
 * `a_` hinterlaesst einen anstehenden Dispatch (echter globaler Schreibzugriff, der Looper wird nicht geleert).
 * Robolectric setzt zu Beginn des naechsten Tests die Looper zurueck und verwirft die Nachricht; ohne Plugin bliebe
 * der Dispatcher dann fuer immer stumm. `b_` prueft, dass er sauber ankommt. Die feste Reihenfolge ist noetig,
 * weil beide Tests dieselbe Sandbox teilen.
 *
 * Die Sandbox ist mit anderen Testklassen geteilt: `b_` heilt in `finally` in jedem Fall, und beide ueberspringen
 * sich, wenn der Dispatcher schon vorher nicht sauber ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DispatcherPluginTest {
    @Test
    fun a_hinterlaesstEinenAnstehendenDispatch() {
        val vorher = IdlingGruende.leseDispatcher()
        assumeTrue(
            "Dispatcher nicht saeuberlich vorgefunden - Test uebersprungen",
            vorher == null || !vorher.initialisiert || vorher.sauber(),
        )
        assumeTrue("sent schon wahr - Test uebersprungen", IdlingGruende.leseGlobalSnapshotManager().second != true)
        starteGlobalSnapshotManager()
        val zustand = mutableIntStateOf(0)
        Snapshot.notifyObjectsInitialized()
        zustand.intValue = 1

        val z = IdlingGruende.leseDispatcher()
        assertEquals("Vorbedingung: Dispatch steht an: $z", true, z?.trampolineGeplant)
        assertEquals("Vorbedingung: Nachricht liegt in der Queue: $z", true, z?.dispatchNachrichtInQueue)
        angelegt = true
        // Absichtlich KEIN idle(): das Plugin soll es vor dem Reset des naechsten Tests abarbeiten.
    }

    @Test
    fun b_derNaechsteTestBeginntMitSauberemDispatcher() {
        assumeTrue("a_ lief nicht in dieser Sandbox - Test uebersprungen", angelegt)
        try {
            val z = IdlingGruende.leseDispatcher()
            assertTrue("Dispatcher muss nach dem Reset sauber sein: $z", z != null && z.sauber())
            assertEquals("sent muss zurueckgesetzt sein", false, IdlingGruende.leseGlobalSnapshotManager().second)
            assertEquals("Nichts darf unangewandt ausstehen", false, Snapshot.current.hasPendingChanges())
        } finally {
            angelegt = false
            DispatcherReparatur.pruefeUndHeile()
        }
    }

    @Test
    fun c_dasPluginWurdeGeladenUndLaeuftInDerSandboxDesTests() {
        // Der Zaehler liegt in derselben Klasse, die das Plugin erhoeht: steht er auf 0, wurde das Plugin nicht
        // geladen oder lebt in einer anderen Classloader.
        assertTrue("Plugin nicht aufgerufen: ${DispatcherReparatur.zusammenfassung()}", DispatcherReparatur.aufrufe.get() > 0)
    }

    private fun IdlingGruende.Dispatcherzustand.sauber(): Boolean =
        initialisiert && trampolineGeplant == false && frameGeplant == false && wartend == 0

    /** Wie ein Compose-View es beim ersten Anlegen tut. */
    private fun starteGlobalSnapshotManager() {
        val klasse = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
        val instanz = klasse.getField("INSTANCE").get(null)
        klasse.getMethod("ensureStarted").invoke(instanz)
    }

    private companion object {
        @Volatile
        var angelegt = false
    }
}
