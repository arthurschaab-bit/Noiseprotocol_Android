package com.example.lrmprotokoll.video

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert die zentrale Zusage von M11 Etappe B (Owner-Entscheidung E9/V4) im Quelltext ab:
 * **Die Kamera fasst das Mikrofon nicht an.**
 *
 * In CameraX ist Audio opt-in - ein einziger `withAudioEnabled()`-Aufruf wuerde genuegen, damit
 * die Videoaufnahme das Mikrofon oeffnet. Dann bekaeme die Pegelmessung waehrend jeder
 * Videoaufnahme ein Loch, und zwar unbemerkt: Die App liefe weiter, nur fehlten in der Messreihe
 * ausgerechnet die Minuten, in denen der Nutzer den Laerm gefilmt hat.
 *
 * Das ist mit einem gewoehnlichen Test nicht pruefbar - CameraX existiert auf der JVM nicht.
 * Deshalb dieser ungewoehnliche Weg ueber den Quelltext: Er kostet nichts und faengt genau den
 * Fall ab, in dem jemand den fehlenden Aufruf spaeter fuer ein Versehen haelt und ihn
 * "repariert".
 */
class KeinKameraTonTest {

    private val screen = File("src/main/java/com/example/lrmprotokoll/ui/VideoAufnahmeScreen.kt")

    @Test
    fun dieQuelldateiIstAuffindbar() {
        // Ein stiller Fehlschlag waere hier schlimmer als ein lauter: Ein umbenannter Screen
        // wuerde die Pruefung sonst wirkungslos machen, ohne dass es jemand merkt.
        assertTrue("VideoAufnahmeScreen.kt nicht gefunden unter ${screen.absolutePath}", screen.exists())
    }

    @Test
    fun dieVideoaufnahmeSchaltetNiemalsDenTonEin() {
        // Kommentarzeilen werden bewusst ausgenommen: Sowohl der KDoc des Screens als auch die
        // Begruendung an der Aufnahmestelle sprechen ueber withAudioEnabled - sie ERWAEHNEN es,
        // sie rufen es nicht auf. Genau dieser Unterschied ist hier der Punkt.
        val verstoesse = screen.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.contains("withAudioEnabled") }

        assertTrue(
            "withAudioEnabled() wuerde die Kamera das Mikrofon oeffnen lassen - siehe " +
                "docs/PROMPT_M11_FOTO_VIDEO.md, Entscheidung E9. Gefunden: $verstoesse",
            verstoesse.isEmpty(),
        )
    }

    @Test
    fun derVerzichtIstImQuelltextBegruendet() {
        // Ohne Begruendung an Ort und Stelle sieht der fehlende Aufruf wie ein Versehen aus.
        val quelle = screen.readText()
        assertTrue(
            "An der Aufnahmestelle muss stehen, warum hier kein Ton eingeschaltet wird",
            quelle.contains("KEIN withAudioEnabled()"),
        )
    }
}
