package com.example.lrmprotokoll.meter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-03, Audit-Punkt 2 (Owner-Entscheidung 29.09.2026): Der Tipp auf den Bluetooth-Badge holt im
 * Zustand "Fehlgeschlagen" den naechsten Anlauf sofort, statt den Kopplungsdialog mit
 * 10-Sekunden-Scan zu oeffnen.
 *
 * Der Test prueft vor allem die **Abgrenzung**, nicht den Hauptfall: Ein zu weit gefasstes
 * `erneutVersuchen()` waere schlimmer als gar keins - wer bei laufender Verbindung auf das Badge
 * tippt, will das Geraet wechseln, und ohne gepinntes Geraet gibt es nichts, wohin ein Anlauf
 * ueberhaupt gehen koennte.
 */
class BadgeTippAktionTest {
    @Test
    fun nurFehlgeschlagenMitGepinntemGeraetLoestEinenNeuenAnlaufAus() {
        assertEquals(
            BadgeTippAktion.ERNEUT_VERBINDEN,
            badgeTippAktion(ConnectionState.FAILED, geraetGepinnt = true),
        )
    }

    @Test
    fun ohneGepinntesGeraetBleibtEsBeimKopplungsdialog() {
        assertEquals(
            "Ohne gepinntes Geraet gibt es kein Ziel fuer einen Anlauf - der Scan ist der richtige Weg",
            BadgeTippAktion.KOPPLUNGSDIALOG,
            badgeTippAktion(ConnectionState.FAILED, geraetGepinnt = false),
        )
    }

    @Test
    fun jederAndereZustandOeffnetDenKopplungsdialog() {
        val andere = ConnectionState.entries.filter { it != ConnectionState.FAILED }
        // Absicherung gegen ein spaeter ergaenztes Enum: die Liste darf nicht leer laufen.
        assertEquals(ConnectionState.entries.size - 1, andere.size)
        for (zustand in andere) {
            assertEquals(
                "$zustand darf keinen neuen Anlauf ausloesen",
                BadgeTippAktion.KOPPLUNGSDIALOG,
                badgeTippAktion(zustand, geraetGepinnt = true),
            )
        }
    }
}
