package com.example.lrmprotokoll.testhilfen

import android.os.Looper
import android.view.Choreographer
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Repariert einen festgefahrenen `AndroidUiDispatcher.Main` zu Beginn eines Tests - und sagt es laut.
 *
 * **Warum.** Untersuchung AppNotIdle, PR #280. Gemessen in drei CI-Haengern und in einer Kaskade mit
 * 14 Haengern: Der Dispatcher der Robolectric-Sandbox haelt eine wartende Fortsetzung (den Konsumenten
 * des `GlobalSnapshotManager`) und glaubt, Handler-Nachricht und Choreographer-Callback seien
 * unterwegs. Beide sind verloren - im Bytecode von Robolectric 4.16.1 gelesen: `AndroidTestEnvironment`
 * ruft zu Beginn jedes Tests `ShadowPausedLooper.resetLoopers()`, der Dispatcher ist aber ein
 * statischer Singleton je Sandbox und behaelt seine Flags. Er postet dann nie wieder, der Konsument
 * laeuft nie, und jeder globale Schreibzugriff bleibt fuer immer unangewandt (`hadSnapshotChanges`).
 * Jeder spaetere Test, der einen angewandten Schreibzugriff braucht, wartet 60 s und scheitert.
 *
 * **Was sie tut.** Erkennt den Zustand (Flag `scheduledTrampolineDispatch` wahr, die `dispatchCallback`
 * aber nicht in der Main-Looper-Queue) und fuehrt das aus, was der Dispatcher selbst bei Ankunft von
 * Nachricht und Frame taete: `dispatchCallback.run()` und `doFrame(0)`. Das arbeitet die wartende
 * Fortsetzung ab (der Konsument setzt `sent` zurueck und wendet die Schreibzugriffe an) und setzt
 * beide Flags zurueck. Nur oeffentliche Methoden, keine Feldzugriffe schreibend.
 *
 * **Was sie NICHT tut.** Sie findet nicht den Ausloeser - der Eintritt liegt oft vor dem ersten Test
 * mit dieser Regel -, und sie schuetzt nur Klassen, die [ZeitwaechterRegel] tragen. Sie behandelt den
 * zweiten denkbaren Mechanismus (Konsument tot: `sent` wahr, aber kein Dispatch ausstehend) nicht,
 * sondern meldet ihn nur.
 *
 * **Nie still.** Jede Reparatur und jeder nicht reparierbare Befund wird mit Zustand vorher und nachher
 * und den zuletzt aufgezeichneten Fremd-Thread-Schreibzugriffen in die Diagnose geschrieben
 * ([ZeitwaechterRegel.DIAGNOSEDATEI]). Ein gruenes CI heisst deshalb nicht, dass nichts passiert ist.
 */
internal object DispatcherReparatur {
    internal data class Bericht(
        val befund: String,
        val vorher: IdlingGruende.Dispatcherzustand,
        val nachher: IdlingGruende.Dispatcherzustand?,
        val sentVorher: Boolean?,
        val sentNachher: Boolean?,
        val ausstehendVorher: Boolean?,
        val ausstehendNachher: Boolean?,
        val repariert: Boolean,
        val fehler: String?,
    ) {
        fun text(testname: String): String =
            buildString {
                appendLine()
                appendLine("=== DISPATCHER-REPARATUR: $testname ===")
                appendLine("Befund: $befund")
                appendLine("vorher:  sent=$sentVorher ausstehendeAenderungen=$ausstehendVorher $vorher")
                appendLine("nachher: sent=$sentNachher ausstehendeAenderungen=$ausstehendNachher $nachher")
                appendLine("Ergebnis: ${if (repariert) "repariert" else "NICHT repariert"}${fehler?.let { " ($it)" } ?: ""}")
                append(GlobaleSchreiber.text())
                appendLine("=== DISPATCHER-REPARATUR Ende ===")
            }
    }

    /**
     * `null`: nichts zu tun (Dispatcher gesund, nicht angelegt, nicht lesbar oder nicht auf dem
     * Main-Thread). Sonst der Bericht. Wirft nie - eine Diagnose darf keinen Test rot machen.
     */
    fun pruefeUndHeile(): Bericht? =
        runCatching {
            if (Looper.getMainLooper().thread !== Thread.currentThread()) return@runCatching null
            val dispatcher = IdlingGruende.dispatcherFallsVorhanden() ?: return@runCatching null
            val vorher = IdlingGruende.leseDispatcher() ?: return@runCatching null
            val sentVorher = IdlingGruende.leseGlobalSnapshotManager().second
            val ausstehendVorher = ausstehendeAenderungen()

            val verloren = vorher.trampolineGeplant == true && vorher.dispatchNachrichtInQueue == false
            val konsumentTot = sentVorher == true && vorher.trampolineGeplant == false
            when {
                verloren -> heile(dispatcher, vorher, sentVorher, ausstehendVorher)
                konsumentTot ->
                    Bericht(
                        befund = "sent wahr, aber kein Dispatch ausstehend (Konsument tot?) - hier nicht reparierbar",
                        vorher = vorher,
                        nachher = vorher,
                        sentVorher = sentVorher,
                        sentNachher = sentVorher,
                        ausstehendVorher = ausstehendVorher,
                        ausstehendNachher = ausstehendVorher,
                        repariert = false,
                        fehler = null,
                    )
                else -> null
            }
        }.getOrNull()

    private fun heile(
        dispatcher: Any,
        vorher: IdlingGruende.Dispatcherzustand,
        sentVorher: Boolean?,
        ausstehendVorher: Boolean?,
    ): Bericht {
        val fehler = mutableListOf<String>()
        val callback = IdlingGruende.leseFeld(dispatcher, "dispatchCallback")
        // Getrennt abgesichert: wirft run() (z. B. ein Apply-Beobachter), muss doFrame(0) trotzdem das
        // zweite Flag zuruecksetzen (Review Gemini, PR #280).
        runCatching { (callback as? Runnable)?.run() }
            .onFailure { fehler += "run(): ${it.javaClass.simpleName}: ${it.message}" }
        runCatching { (callback as? Choreographer.FrameCallback)?.doFrame(0) }
            .onFailure { fehler += "doFrame(): ${it.javaClass.simpleName}: ${it.message}" }
        val nachher = IdlingGruende.leseDispatcher()
        val sentNachher = IdlingGruende.leseGlobalSnapshotManager().second
        val ausstehendNachher = ausstehendeAenderungen()
        val gesund = nachher != null && nachher.trampolineGeplant == false && nachher.frameGeplant == false && nachher.wartend == 0
        return Bericht(
            befund = "verlorene Dispatch-Nachricht (scheduledTrampolineDispatch wahr, dispatchCallback nicht in der Main-Looper-Queue)",
            vorher = vorher,
            nachher = nachher,
            sentVorher = sentVorher,
            sentNachher = sentNachher,
            ausstehendVorher = ausstehendVorher,
            ausstehendNachher = ausstehendNachher,
            repariert = gesund && fehler.isEmpty(),
            fehler = fehler.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    private fun ausstehendeAenderungen(): Boolean? = runCatching { Snapshot.current.hasPendingChanges() }.getOrNull()
}
