package com.example.lrmprotokoll.audio

/**
 * Bewertung des Aufzeichnungszustands (Plan 5.4, Befund G aus docs/BEFUNDE_BUNDLES_2026-10-07.md).
 *
 * Reine Entscheidungslogik ohne Android- oder UI-Abhaengigkeiten. Wird sowohl beim Oeffnen der App
 * ([AufzeichnungsFortsetzer]) als auch vom periodischen Waechter-Job genutzt.
 */
enum class AufzeichnungsLage {
    /** Dienst laeuft bereits aktiv im aktuellen Prozess. */
    LAEUFT,

    /** Ueberwachung war explizit gestoppt oder noch nie gestartet. */
    AUS,

    /** Ueberwachung soll laufen, ist aber nicht aktiv und kann gestartet werden. */
    SOLL_LAEUFT_NICHT,

    /** Ueberwachung soll laufen, kann aber nicht in den Vordergrund (weder Mikrofon noch Messgeraet). */
    SOLL_ABER_NICHT_STARTBAR,
}

/**
 * Bewertet, ob die Aufzeichnung laufen soll und ob sie gestartet werden kann.
 */
fun bewerteAufzeichnungsLage(
    monitoringWasActive: Boolean,
    dienstLaeuft: Boolean,
    kannInDenVordergrund: Boolean,
): AufzeichnungsLage {
    if (dienstLaeuft) return AufzeichnungsLage.LAEUFT
    if (!monitoringWasActive) return AufzeichnungsLage.AUS
    return if (kannInDenVordergrund) {
        AufzeichnungsLage.SOLL_LAEUFT_NICHT
    } else {
        AufzeichnungsLage.SOLL_ABER_NICHT_STARTBAR
    }
}
