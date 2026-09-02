package com.example.lrmprotokoll.foto

import android.media.ExifInterface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Rechenschritte der Bildaufbereitung. Die Bitmap-Dekodierung selbst ist unter Robolectric
 * nicht aussagekraeftig (Shadow-Bitmaps haben keinen echten Inhalt) - geprueft wird deshalb die
 * Logik, die tatsaechlich falsch sein kann: Skalierungsfaktor, Speicherschonung und EXIF-Drehung.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BildverarbeitungTest {

    @Test
    fun grosseBilderWerdenAufDieMaximaleKanteBegrenzt() {
        val (b, h) = Bildverarbeitung.zielAbmessungen(4000, 3000, maxKante = 1600)
        assertEquals(1600, b)
        assertEquals(1200, h)
    }

    @Test
    fun hochformatWirdEbenfallsAnDerLaengerenKanteBegrenzt() {
        val (b, h) = Bildverarbeitung.zielAbmessungen(3000, 4000, maxKante = 1600)
        assertEquals(1200, b)
        assertEquals(1600, h)
    }

    @Test
    fun kleineBilderWerdenNichtVergroessert() {
        // Hochskalieren erfindet Bildinformation, die es nicht gibt.
        val (b, h) = Bildverarbeitung.zielAbmessungen(800, 600, maxKante = 1600)
        assertEquals(800, b)
        assertEquals(600, h)
    }

    @Test
    fun dasSeitenverhaeltnisBleibtErhalten() {
        val (b, h) = Bildverarbeitung.zielAbmessungen(4032, 3024, maxKante = 1600)
        assertEquals(4032.0 / 3024.0, b.toDouble() / h.toDouble(), 0.01)
    }

    @Test
    fun entarteteAbmessungenWerdenUnveraendertDurchgereicht() {
        assertEquals(0 to 0, Bildverarbeitung.zielAbmessungen(0, 0))
        assertEquals(-1 to 10, Bildverarbeitung.zielAbmessungen(-1, 10))
    }

    @Test
    fun inSampleSizeHalbiertNurSoWeitWieErlaubt() {
        // 4000 px: /2 = 2000 (noch >= 1600), /4 = 1000 (zu klein) -> 2
        assertEquals(2, Bildverarbeitung.inSampleSizeFuer(4000, 3000, maxKante = 1600))
        // 12000 px: /2=6000, /4=3000, /8=1500 (zu klein) -> 4
        assertEquals(4, Bildverarbeitung.inSampleSizeFuer(12000, 9000, maxKante = 1600))
    }

    @Test
    fun kleineBilderBrauchenKeineVorabVerkleinerung() {
        assertEquals(1, Bildverarbeitung.inSampleSizeFuer(1200, 900, maxKante = 1600))
        assertEquals(1, Bildverarbeitung.inSampleSizeFuer(0, 0))
    }

    @Test
    fun exifOrientierungWirdInEineDrehungUebersetzt() {
        // Der haeufigste sichtbare Fehler bei Belegfotos: Das Bild liegt quer, weil die Drehung
        // nur im EXIF stand und beim Neuschreiben verloren ging.
        assertEquals(90f, Bildverarbeitung.drehungFuerExif(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180f, Bildverarbeitung.drehungFuerExif(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270f, Bildverarbeitung.drehungFuerExif(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(0f, Bildverarbeitung.drehungFuerExif(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(0f, Bildverarbeitung.drehungFuerExif(ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test
    fun pruefsummeIstStabilUndUnterscheidetInhalte() {
        val a = File.createTempFile("foto_a", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val b = File.createTempFile("foto_b", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 4)); deleteOnExit() }

        val summeA = Bildverarbeitung.pruefsumme(a)
        assertNotNull(summeA)
        assertEquals("SHA-256 als Hex hat 64 Zeichen", 64, summeA!!.length)
        assertEquals("Dieselbe Datei muss dieselbe Summe liefern", summeA, Bildverarbeitung.pruefsumme(a))
        assertTrue("Ein geaenderter Inhalt muss eine andere Summe liefern", summeA != Bildverarbeitung.pruefsumme(b))
    }

    @Test
    fun pruefsummeEinerFehlendenDateiIstNullStattEinerAusnahme() {
        assertNull(Bildverarbeitung.pruefsumme(File("/gibt/es/nicht.jpg")))
    }

    @Test
    fun kaputteEingabeWirftNicht() {
        // Die Robustheitszusage: Eine misslungene Fotoaufbereitung darf die laufende Messung
        // nicht gefaehrden, also nie nach oben werfen.
        //
        // Geprueft wird hier NUR, dass nichts fliegt - nicht, dass `false` zurueckkommt.
        // Robolectrics ShadowBitmapFactory erfindet zu jeder Datei ein Bitmap mit plausiblen
        // Abmessungen, unabhaengig vom Inhalt; ein "ein kaputtes Bild liefert false" waere hier
        // also keine Aussage ueber den Produktivcode. Dass der Rueckgabewert stimmt, ist nur am
        // Geraet pruefbar - dieselbe Kategorie Luecke wie PdfDocument unter Robolectric.
        val quelle = File.createTempFile("kaputt", ".jpg").apply { writeBytes(byteArrayOf(0, 1, 2)); deleteOnExit() }
        val ziel = File.createTempFile("ziel", ".jpg").apply { deleteOnExit() }

        Bildverarbeitung.verkleinereUndSpeichere(quelle, ziel)
    }

    @Test
    fun eineFehlendeQuelldateiWirftNicht() {
        val ziel = File.createTempFile("ziel2", ".jpg").apply { deleteOnExit() }
        Bildverarbeitung.verkleinereUndSpeichere(File("/gibt/es/nicht.jpg"), ziel)
    }
}
