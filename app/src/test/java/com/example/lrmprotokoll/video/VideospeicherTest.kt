package com.example.lrmprotokoll.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideospeicherTest {

    @Test
    fun knappUnterDerGrenzeWirdNichtAufgenommen() {
        // Ein mitten im Beweis abbrechendes Video ist schlimmer als eines, das nie begann.
        assertFalse(Videospeicher.reichtSpeicher(Videospeicher.MINDESTSPEICHER_BYTES - 1))
        assertTrue(Videospeicher.reichtSpeicher(Videospeicher.MINDESTSPEICHER_BYTES))
    }

    @Test
    fun leererSpeicherIstKeinSonderfall() {
        assertFalse(Videospeicher.reichtSpeicher(0))
    }

    @Test
    fun dieSchaetzungWaechstMitDauerUndAufloesung() {
        val hd = Videospeicher.geschaetzteGroesseBytes(180, "HD")
        val fhd = Videospeicher.geschaetzteGroesseBytes(180, "FHD")
        assertEquals(180L * 8_000_000 / 8, hd)
        assertEquals(2 * hd, fhd)
    }

    @Test
    fun eineNegativeDauerErgibtKeineNegativeGroesse() {
        assertEquals(0L, Videospeicher.geschaetzteGroesseBytes(-5, "HD"))
    }

    @Test
    fun dateinamenSindSortierbarUndUnterscheidenGemuxteFassungen() {
        val stumm = Videospeicher.dateiname(0L)
        val gemuxt = Videospeicher.dateiname(0L, gemuxt = true)
        assertTrue(stumm.startsWith("video_") && stumm.endsWith(".mp4"))
        assertTrue(gemuxt.endsWith("_ton.mp4"))
    }

    @Test
    fun tondateiTeiltDieBasisMitDemVideo() {
        val video = Videospeicher.dateiname(1_700_000_000_000)
        val ton = Videospeicher.tondateiname(1_700_000_000_000)
        assertEquals(video.removeSuffix(".mp4"), ton.removeSuffix(".pcm"))
    }

    @Test
    fun dauerWirdAlsMinutenUndSekundenAngezeigt() {
        assertEquals("0:00", Videospeicher.formatiereDauer(0))
        assertEquals("0:09", Videospeicher.formatiereDauer(9))
        assertEquals("2:59", Videospeicher.formatiereDauer(179))
        assertEquals("15:00", Videospeicher.formatiereDauer(900))
    }

    @Test
    fun eineNegativeRestzeitWirdNichtAngezeigt() {
        // Kann beim Stoppen im letzten Moment auftreten - "-0:01" waere ein Anzeigefehler.
        assertEquals("0:00", Videospeicher.formatiereDauer(-3))
    }
}
