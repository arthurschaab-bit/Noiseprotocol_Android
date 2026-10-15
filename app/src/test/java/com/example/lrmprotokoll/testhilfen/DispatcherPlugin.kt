package com.example.lrmprotokoll.testhilfen

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Schuetzt JEDEN Robolectric-Test vor einem festgefahrenen `AndroidUiDispatcher.Main` (Untersuchung AppNotIdle,
 * PR #280) - auch Klassen ohne [ZeitwaechterRegel]. Hunt 5 zeigte die Luecke: 14 Tests in 4 ungeschuetzten Klassen
 * hingen je 60 s, bis die erste Klasse mit Regel den Dispatcher heilte.
 *
 * **Wann.** `AndroidTestEnvironment.setUpApplicationState` ruft die Plugins VOR `ShadowPausedLooper.resetLoopers()`
 * (Bytecode Robolectric 4.16.1 gelesen). Dieses Plugin arbeitet deshalb einen anstehenden Dispatch noch ab, bevor
 * der Reset die Nachricht verwirft, und heilt eine schon verlorene.
 *
 * **Wie es geladen wird.** `META-INF/services/org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin` im
 * Test-Klassenpfad. In einer Sonde nachgewiesen: Aufruf je Test, auf dem Main-Thread, in derselben
 * `SdkSandboxClassLoader` wie die Compose-Singletons.
 *
 * Jeder auffaellige Befund steht in der Diagnosedatei; die Zaehler am JVM-Ende zeigen, wie oft das Plugin
 * eingreifen musste.
 */
class DispatcherPlugin : TestEnvironmentLifecyclePlugin {
    init {
        Diagnoseausgabe.beiJvmEnde { DispatcherReparatur.zusammenfassung() }
    }

    override fun onSetupApplicationState() {
        runCatching {
            DispatcherReparatur
                .pruefeUndHeile(DispatcherReparatur.Anlass.VOR_RESET)
                ?.let { Diagnoseausgabe.schreibe(it.text("vor dem Reset des naechsten Tests (Plugin)")) }
        }
    }
}
