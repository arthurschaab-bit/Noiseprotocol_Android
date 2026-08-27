package com.example.lrmprotokoll.report

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.NoiseRecord
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROMPT_M9A.md Aufgabe 2, End-zu-Ende gegen den echten Berichtstext (nicht nur die
 * ausgelagerte [pegelEinheit]-Funktion): der Tagesbericht darf "dBA" nur schreiben, wenn
 * [NoiseRecord.meterWeighting] fuer das jeweilige Ereignis tatsaechlich "A" ist.
 *
 * Die erwartete Zahl wird bewusst mit demselben String.format(Locale.getDefault(), ...) erzeugt
 * wie im Produktivcode, statt ein Dezimaltrennzeichen fest anzunehmen - das Trennzeichen haengt
 * von der Standard-Locale der ausfuehrenden JVM ab (Komma unter Deutsch, Punkt unter Englisch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportManagerEinheitTest {

    private val erwarteterPegel = String.format(Locale.getDefault(), "%.1f", 70.5)

    private fun manager() = ReportManager(ApplicationProvider.getApplicationContext())

    private fun ereignis(calibratedDbA: Double?, meterWeighting: String?) = NoiseRecord(
        timestamp = 1_700_000_000_000L,
        amplitude = 0.0,
        dbValue = 62.0,
        filePath = "/data/rec.wav",
        calibratedDbA = calibratedDbA,
        meterWeighting = meterWeighting,
    )

    @Test
    fun bestaetigteBewertungStehtAlsDbAImBericht() {
        val datei = manager().generateDailyReport(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = "A")))
        assertTrue(datei.readText().contains("$erwarteterPegel dBA (PCE-323)"))
    }

    @Test
    fun unbestaetigteBewertungStehtNurAlsDbImBerichtNichtAlsDbA() {
        val datei = manager().generateDailyReport(listOf(ereignis(calibratedDbA = 70.5, meterWeighting = null)))
        val text = datei.readText()
        assertTrue(text.contains("$erwarteterPegel dB (PCE-323)"))
        assertFalse("darf 'dBA' nicht behaupten, solange die Bewertung unbestaetigt ist", text.contains("dBA"))
    }
}
