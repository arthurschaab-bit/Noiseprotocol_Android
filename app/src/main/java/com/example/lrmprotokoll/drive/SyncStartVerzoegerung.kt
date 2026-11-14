package com.example.lrmprotokoll.drive

/**
 * Schonfrist fuer den Drive-Sync nach dem Start des Messdienstes (Owner-Vorgabe 14.11.2026).
 *
 * Im Support-Bundle vom 13.11.2026 begann direkt mit dem Dienststart ein Drive-Sync-Zyklus mit
 * rund 24.000 Zeilen, und die Mikrofon-Session entstand erst knapp 20 s spaeter - vermutlich
 * konkurrierten beide um die Datenbank (nicht belegt). Solange keine Session existiert, bleiben
 * Konfiguration und Fotos im Aufbau gesperrt und die Popups verspaeten sich. Deshalb wartet der
 * Sync die ersten [SCHONFRIST_MS] nach dem Dienststart.
 */
object SyncStartVerzoegerung {
    const val SCHONFRIST_MS: Long = 2 * 60 * 1000L

    /**
     * Wie lange der Sync noch warten soll, in Millisekunden; `0`, wenn er sofort laufen darf.
     * Ein [dienstGestartetAmMs] von `0` heisst "kein Start gemerkt" (Dienst laeuft nicht oder
     * Testbetrieb) und verzoegert nie.
     */
    fun restMs(
        dienstLaeuft: Boolean,
        dienstGestartetAmMs: Long,
        jetztMs: Long,
    ): Long {
        if (!dienstLaeuft || dienstGestartetAmMs <= 0L) return 0L
        val vergangen = jetztMs - dienstGestartetAmMs
        if (vergangen < 0L) return 0L // Uhr zurueckgestellt: lieber syncen als haengen
        return (SCHONFRIST_MS - vergangen).coerceIn(0L, SCHONFRIST_MS)
    }
}
