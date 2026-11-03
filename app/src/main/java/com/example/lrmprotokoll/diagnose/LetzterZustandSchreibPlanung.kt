package com.example.lrmprotokoll.diagnose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fasst haeufige Strom- und BLE-Signale zusammen; Dienststart und -ende bleiben sofort dauerhaft. */
class LetzterZustandSchreibPlanung(
    private val scope: CoroutineScope,
    private val schreiben: (Boolean?) -> Unit,
    private val warteMs: Long = 1_000L,
) {
    private var vorgemerkt: Job? = null

    @Synchronized
    fun aktualisieren(sauberBeendet: Boolean? = null) {
        vorgemerkt?.cancel()
        vorgemerkt = null
        if (sauberBeendet == null) {
            vorgemerkt =
                scope.launch {
                    delay(warteMs)
                    schreiben(null)
                }
        } else {
            schreiben(sauberBeendet)
        }
    }
}
