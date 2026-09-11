package com.example.lrmprotokoll.audio

import kotlin.math.exp
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Praefprotokoll C-1 (Owner-Entscheidung vom 11.09.2026: "Auf Fast-zeitgewichtetes RMS
 * umstellen"). Der entscheidende Nachweis ist Test 4: die Zeitgewichtung mittelt im
 * LEISTUNGS-Bereich, nicht im dB-Bereich - beides ergibt fuer denselben Fall unterschiedliche
 * Zahlen, und nur die Leistungsmittelung ist akustisch das, was ein "Fast"-Schallpegelmesser tut.
 */
class FastPegelSchaetzerTest {

    private fun block(amplitude: Int, groesse: Int = 1000): ShortArray =
        ShortArray(groesse) { amplitude.toShort() }

    @Test
    fun volleAussteuerungLiefert100Db() {
        val schaetzer = FastPegelSchaetzer()
        val db = schaetzer.naechsterBlock(block(32767), 1000, abtastrate = 8000)
        assertEquals(100.0, db, 0.001)
    }

    @Test
    fun stilleLiefertNullDb() {
        val schaetzer = FastPegelSchaetzer()
        val db = schaetzer.naechsterBlock(block(0), 1000, abtastrate = 8000)
        assertEquals(0.0, db, 0.001)
    }

    @Test
    fun ersterBlockWirdSofortUebernommenOhneEinschwingen() {
        // Ohne die initialisiert-Sonderbehandlung wuerde ein frisch gestartetes Monitoring
        // sich erst ueber mehrere Zeitkonstanten an den tatsaechlichen Pegel herantasten -
        // als haette vor der ersten Messung Stille geherrscht, die es nie gab.
        val schaetzer = FastPegelSchaetzer()
        val db = schaetzer.naechsterBlock(block(32767), 1000, abtastrate = 8000)
        assertEquals(100.0, db, 0.001)
    }

    @Test
    fun zweiterBlockWirdImLeistungsBereichGemitteltNichtImDbBereich() {
        // Blockdauer = readSize/abtastrate = 1000/8000 = 0.125s = genau die Fast-Zeitkonstante,
        // das ergibt ein rechnerisch sauberes alpha = 1 - e^-1.
        val schaetzer = FastPegelSchaetzer()
        schaetzer.naechsterBlock(block(32767), 1000, abtastrate = 8000) // Leistung 1.0, db=100
        val db = schaetzer.naechsterBlock(block(0), 1000, abtastrate = 8000) // Leistung 0.0

        val alpha = 1.0 - exp(-1.0)
        val erwarteteLeistung = alpha * 0.0 + (1.0 - alpha) * 1.0
        val erwarteteDb = 10 * log10(erwarteteLeistung) + 100.0
        assertEquals(erwarteteDb, db, 0.01)

        // Eine (falsche) Mittelung im dB-Bereich haette (100+0)/2=50 ergeben - klar erkennbar
        // etwas anderes als das tatsaechliche, leistungsbasierte Ergebnis.
        assertNotEquals(50.0, db, 5.0)
    }

    @Test
    fun resetSetztDenZustandZurueck() {
        val schaetzer = FastPegelSchaetzer()
        schaetzer.naechsterBlock(block(32767), 1000, abtastrate = 8000)

        schaetzer.reset()
        val db = schaetzer.naechsterBlock(block(0), 1000, abtastrate = 8000)

        assertEquals("Nach reset() darf der alte Pegel nicht mehr nachwirken", 0.0, db, 0.001)
    }

    @Test
    fun leererBlockLiefertDenLetztenWertUndAendertDenZustandNicht() {
        val schaetzer = FastPegelSchaetzer()
        schaetzer.naechsterBlock(block(32767), 1000, abtastrate = 8000)

        val dbBeiLeeremBlock = schaetzer.naechsterBlock(ShortArray(0), 0, abtastrate = 8000)
        assertEquals(100.0, dbBeiLeeremBlock, 0.001)

        // Der naechste echte Block mittelt weiterhin gegen die Leistung von VOR dem leeren
        // Aufruf, nicht gegen einen durch ihn faelschlich zurueckgesetzten Zustand.
        val dbDanach = schaetzer.naechsterBlock(block(0), 1000, abtastrate = 8000)
        val alpha = 1.0 - exp(-1.0)
        val erwarteteDb = 10 * log10((1.0 - alpha) * 1.0) + 100.0
        assertEquals(erwarteteDb, dbDanach, 0.01)
    }

    @Test
    fun sehrLeiserPegelNachLauterStilleWirdAufNullGeklemmt() {
        val schaetzer = FastPegelSchaetzer()
        schaetzer.naechsterBlock(block(32767), 1000, abtastrate = 8000) // startet bei 100 dB

        var letzterWert = 100.0
        repeat(30) {
            letzterWert = schaetzer.naechsterBlock(block(0), 1000, abtastrate = 8000)
        }

        assertEquals(0.0, letzterWert, 0.001)
        assertTrue("Der Pegel muss ueber die Stille-Bloecke hinweg tatsaechlich gefallen sein", letzterWert < 100.0)
    }
}
