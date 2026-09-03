package com.example.lrmprotokoll.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Der Tonmitschnitt fuer den Videobeweis (M11 Etappe B, B.2a Schritt 1).
 *
 * Der wichtigste Teil dieser Tests ist der Nachweis, dass diese Klasse die Aufnahmeschleife des
 * [com.example.lrmprotokoll.audio.AudioRecordingService] **nicht** stoert: Sie wird aus dem
 * heissesten Pfad der App aufgerufen, und die Pegelmessung ist die Kernaufgabe. Weder ein
 * inaktiver Mitschnitt noch ein Schreibfehler darf dort etwas werfen.
 */
class VideoTonMitschnittTest {

    @get:Rule
    val ordner = TemporaryFolder()

    private val mitschnitt = VideoTonMitschnitt()

    private fun block(groesse: Int = 64) = ByteArray(groesse) { (it % 251).toByte() }

    @Test
    fun ohneLaufendeAufnahmeIstSchreibenEinNoOp() {
        // Der Aufruf steht in der Aufnahmeschleife und laeuft dort tausendfach, ohne dass ein
        // Video laeuft. Er darf weder werfen noch etwas anlegen.
        assertFalse(mitschnitt.laeuft)
        mitschnitt.schreibe(block(), 64, 1_000)
        assertNull(mitschnitt.beende())
    }

    @Test
    fun einBlockLandetVollstaendigInDerDatei() {
        val ziel = ordner.newFile("ton.pcm")
        assertTrue(mitschnitt.starte(ziel, abtastrate = 44_100, kanaele = 1))

        mitschnitt.schreibe(block(64), 64, 5_000)
        val ergebnis = mitschnitt.beende()!!

        assertEquals(64L, ergebnis.bytes)
        assertEquals(64L, ziel.length())
        assertEquals(44_100, ergebnis.abtastrate)
        assertEquals(1, ergebnis.kanaele)
    }

    @Test
    fun nurDerTeilBisZurAngegebenenLaengeWirdGeschrieben() {
        // Die Aufnahmeschleife reicht einen wiederverwendeten Puffer weiter, der groesser ist
        // als die tatsaechlich gelesene Menge - der Rest ist Muell aus dem vorigen Durchlauf.
        val ziel = ordner.newFile("ton.pcm")
        mitschnitt.starte(ziel, 44_100, 1)

        mitschnitt.schreibe(block(1024), 100, 5_000)
        mitschnitt.beende()

        assertEquals(100L, ziel.length())
    }

    @Test
    fun derZeitstempelStammtVomErstenBlockNichtVomStart() {
        // Zwischen starte() und dem ersten Block koennen Millisekunden liegen. Massgeblich fuer
        // die Synchronisation ist, wann der erste Ton tatsaechlich aufgezeichnet wurde.
        val ziel = ordner.newFile("ton.pcm")
        mitschnitt.starte(ziel, 44_100, 1)

        mitschnitt.schreibe(block(), 64, 7_777)
        mitschnitt.schreibe(block(), 64, 9_999)
        val ergebnis = mitschnitt.beende()!!

        assertEquals(7_777L, ergebnis.ersterBlockAm)
    }

    @Test
    fun einMitschnittOhneEinenEinzigenBlockLiefertKeinErgebnis() {
        // Sonst wuerde ein Mux-Lauf mit einer leeren Tondatei geplant.
        val ziel = ordner.newFile("ton.pcm")
        mitschnitt.starte(ziel, 44_100, 1)

        assertNull(mitschnitt.beende())
    }

    @Test
    fun einZweiterStartWirdAbgelehntStattDenLaufendenZuVerdraengen() {
        val ersteDatei = ordner.newFile("ton1.pcm")
        val zweiteDatei = ordner.newFile("ton2.pcm")
        mitschnitt.starte(ersteDatei, 44_100, 1)

        assertFalse(mitschnitt.starte(zweiteDatei, 48_000, 2))

        mitschnitt.schreibe(block(), 64, 1_000)
        assertEquals(ersteDatei, mitschnitt.beende()!!.datei)
    }

    @Test
    fun einSchreibfehlerBeendetNurDenMitschnittUndVerlaesstDieSchleifeNicht() {
        // Ein voller Speicher darf das Beweisvideo kosten - niemals die Messreihe.
        val ziel = ordner.newFile("ton.pcm")
        mitschnitt.starte(ziel, 44_100, 1)
        mitschnitt.schreibe(block(), 64, 1_000)

        // Datei unter dem offenen Strom wegziehen und den Strom schliessen lassen, indem
        // ueber das Ende hinaus geschrieben wird - realistischer laesst sich ein IO-Fehler in
        // einem JVM-Test nicht ausloesen, deshalb pruefen wir den Vertrag ueber die
        // Laengenangabe: eine negative Laenge ist fuer OutputStream.write ungueltig.
        mitschnitt.schreibe(block(), -1, 2_000)

        assertFalse("Nach einem Fehler laeuft kein Mitschnitt mehr", mitschnitt.laeuft)
        assertNull("Und es gibt kein Ergebnis, das einen Mux-Lauf ausloesen wuerde", mitschnitt.beende())
    }

    @Test
    fun verwerfenRaeumtDieDateiWeg() {
        // Fuer den Fall, dass die Videoaufnahme gar nicht erst startet.
        val ziel = ordner.newFile("ton.pcm")
        mitschnitt.starte(ziel, 44_100, 1)
        mitschnitt.schreibe(block(), 64, 1_000)

        mitschnitt.verwerfe()

        assertFalse(mitschnitt.laeuft)
        assertFalse("Eine verwaiste PCM-Datei waere reiner Speicherverbrauch", ziel.exists())
    }

    @Test
    fun einNichtAnlegbaresZielFuehrtZuEinemSauberenNein() {
        // Der Aufrufer nimmt das Video dann stumm auf, statt abzustuerzen.
        val unmoeglich = File(ordner.newFolder("gesperrt"), "unter/pfad/ton.pcm")

        assertFalse(mitschnitt.starte(unmoeglich, 44_100, 1))
        assertFalse(mitschnitt.laeuft)
    }
}
