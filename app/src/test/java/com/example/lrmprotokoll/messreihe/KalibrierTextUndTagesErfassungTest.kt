package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.KalibrierEnde
import com.example.lrmprotokoll.data.KalibrierphaseEntity
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KalibrierTextUndTagesErfassungTest {
    private fun phase(grund: String?, vorher: Double?, nachher: Double?, korrigiert: Boolean = false) =
        KalibrierphaseEntity(
            id = 1, startedAt = 0, endedAt = 1_000, abweichungVorherDb = vorher,
            abweichungNachherDb = nachher, abweichungKorrigiert = korrigiert, endeGrund = grund,
        )

    @Test
    fun berichtstextNenntKalibratorSollwertUndBeideAbweichungen() {
        val standard = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val text = kalibrierBerichtText(phase(KalibrierEnde.BESTAETIGT, 0.3, 0.0), Locale.GERMAN)
            assertEquals(
                "Kalibrator PCE-SC 43, 94,0 dB / 1 kHz, 01.01.1970 00:00 Uhr: " +
                    "Abweichung vor Justierung +0,3 dB, danach +0,0 dB",
                text,
            )
        } finally {
            TimeZone.setDefault(standard)
        }
    }

    @Test
    fun korrigierteWerteWerdenImTextKenntlichGemacht() {
        val text = kalibrierBerichtText(phase(KalibrierEnde.MANUELL, -0.4, null, korrigiert = true))!!
        assertTrue(text.contains("vom Nutzer korrigiert"))
        assertTrue(text.contains("danach k. A."))
    }

    @Test
    fun ohneKalibrierungKeinBerichtstext() {
        assertNull(kalibrierBerichtText(phase(KalibrierEnde.UEBERSPRUNGEN, null, null)))
        assertNull(kalibrierBerichtText(phase(KalibrierEnde.ZEITUEBERSCHREITUNG, null, null)))
        assertNull(kalibrierBerichtText(phase(KalibrierEnde.MANUELL, null, null)))
    }

    @Test
    fun tagesErfassungIstVollstaendigNurWennAlleBenoetigtenBereicheVorliegen() {
        assertTrue(TagesErfassung(true, 2).vollstaendig(konfigurationNoetig = true, fotosNoetig = true))
        assertFalse(TagesErfassung(true, 0).vollstaendig(true, true))
        assertFalse(TagesErfassung(false, 2).vollstaendig(true, true))
        assertTrue("Ausgeschaltete Bereiche werden nicht verlangt", TagesErfassung(true, 0).vollstaendig(true, false))
        assertTrue(TagesErfassung(false, 0).vollstaendig(false, false))
    }
}
