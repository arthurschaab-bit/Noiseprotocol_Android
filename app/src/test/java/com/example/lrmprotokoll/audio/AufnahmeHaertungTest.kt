package com.example.lrmprotokoll.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class AufnahmeHaertungTest {

    @Test
    fun waehltUnprocessedWennUnterstuetzt() {
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, waehleAufnahmequelle(unterstuetztUnprocessed = true))
    }

    @Test
    fun faelltAufMicZurueckWennUnprocessedNichtUnterstuetzt() {
        assertEquals(MediaRecorder.AudioSource.MIC, waehleAufnahmequelle(unterstuetztUnprocessed = false))
    }

    @Test
    fun behaeltGewuenschteRateWennUnterstuetzt() {
        val rate = waehleAufnahmerate(16000) { it == 16000 }
        assertEquals(16000, rate)
    }

    @Test
    fun waehltNaechsthoehereUnterstuetzteRateWennGewuenschteNichtGeht() {
        val unterstuetzt = setOf(44100, 48000)
        val rate = waehleAufnahmerate(16000) { it in unterstuetzt }
        assertEquals(
            "22050/24000/32000 sind ebenfalls nicht unterstuetzt in diesem Testfall - " +
                "die naechste unterstuetzte Rate ist 44100",
            44100, rate,
        )
    }

    @Test
    fun waehltNiemalsEineNiedrigereRateAlsGewuenscht() {
        // 11025 ist zwar in der Kandidatenleiter enthalten, aber NIEDRIGER als die gewuenschten
        // 16000 - darf trotz Unterstuetzung nicht gewaehlt werden.
        val unterstuetzt = setOf(11025)
        val rate = waehleAufnahmerate(16000) { it in unterstuetzt }
        assertEquals(
            "Ohne unterstuetzte hoehere Rate bleibt die gewuenschte Rate bestehen, " +
                "niemals ein Rueckfall auf eine niedrigere",
            16000, rate,
        )
    }

    @Test
    fun ohneJedeUnterstuetzteRateBleibtDieGewuenschteRateBestehen() {
        val rate = waehleAufnahmerate(16000) { false }
        assertEquals(16000, rate)
    }
}
