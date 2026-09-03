package com.example.lrmprotokoll.report

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BerichtDatei.mimeTypFuer] ist bewusst eine reine Funktion und braucht deshalb weder Context
 * noch Robolectric - dasselbe Muster wie [pegelEinheit].
 *
 * Der wichtigste Fall ist `zip`: Vor der Zusammenfuehrung der drei Teilen-Kopien hat
 * [ReportManager.shareFile] jede Datei ausser `.wav` als `text/plain` deklariert, also auch das
 * ZIP aus `createZipAndShare`. Dieser Test haelt die Korrektur fest.
 */
class BerichtDateiTest {

    @Test
    fun zipWirdNichtMehrAlsTextDeklariert() {
        assertEquals("application/zip", BerichtDatei.mimeTypFuer("Laermprotokoll_01.01.2026.zip"))
    }

    @Test
    fun bekannteBerichtsformateWerdenKorrektAbgeleitet() {
        assertEquals("application/pdf", BerichtDatei.mimeTypFuer("Session_01.01.2026_1200.pdf"))
        assertEquals("text/csv", BerichtDatei.mimeTypFuer("Session_01.01.2026_1200.csv"))
        assertEquals("text/plain", BerichtDatei.mimeTypFuer("Tagesbericht_01.01.2026.txt"))
        assertEquals("audio/wav", BerichtDatei.mimeTypFuer("noise_20260101_120000.wav"))
    }

    @Test
    fun endungWirdUnabhaengigVonGrossschreibungErkannt() {
        // Kamera-Apps liefern haeufig ".JPG" - eine Ableitung, die nur Kleinbuchstaben kennt,
        // wuerde solche Dateien still auf octet-stream zurueckfallen lassen.
        assertEquals("image/jpeg", BerichtDatei.mimeTypFuer("Foto.JPG"))
        assertEquals("application/pdf", BerichtDatei.mimeTypFuer("Bericht.PDF"))
    }

    @Test
    fun unbekannteUndFehlendeEndungFallenAufOctetStreamZurueck() {
        // Rueckfall statt Raten: ein falscher Typ laesst die Datei in der Zielauswahl
        // verschwinden oder wird von der Empfaengerapp fehlinterpretiert.
        assertEquals("application/octet-stream", BerichtDatei.mimeTypFuer("bericht.xyz"))
        assertEquals("application/octet-stream", BerichtDatei.mimeTypFuer("bericht"))
    }

    @Test
    fun punktImDateinamenVerwirrtDieAbleitungNicht() {
        // Die Berichtsnamen enthalten das Datum als "01.01.2026" - eine Ableitung, die auf den
        // ERSTEN Punkt schaut statt auf den letzten, wuerde hier "01" als Endung sehen.
        assertEquals("text/plain", BerichtDatei.mimeTypFuer("Tagesbericht_01.01.2026.txt"))
    }

    @Test
    fun beweisvideosBekommenDenVideoTyp() {
        // M11 Etappe B: Ohne diesen Eintrag fiele ein Video auf application/octet-stream zurueck
        // und verschwaende in der Zielauswahl des Systems.
        assertEquals("video/mp4", BerichtDatei.mimeTypFuer("video_20260903_08_00_00_ton.mp4"))
    }
}
