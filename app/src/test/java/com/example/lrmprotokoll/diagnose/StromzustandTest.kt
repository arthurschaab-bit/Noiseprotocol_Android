package com.example.lrmprotokoll.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer [Stromzustand] und [naechsterEintrag] gemaess Test 1 aus
 * docs/PROMPT_FIX_LADEZUSTAND_PROTOKOLLIEREN.md.
 */
class StromzustandTest {

    @Test
    fun testTabelleGemaessAuftrag() {
        // 1. Erster Aufruf (vorher = null) -> Eintrag
        val startZustand = Stromzustand(
            quelle = Stromzustand.Quelle.USB,
            status = Stromzustand.Status.LAEDT,
            prozent = 92,
        )
        val ersterEintrag = naechsterEintrag(null, startZustand)
        assertNotNull("Erster Aufruf muss einen Eintrag erzeugen", ersterEintrag)
        assertTrue(
            "Muss mit Praefix 'Stromversorgung' beginnen",
            ersterEintrag!!.startsWith("Stromversorgung"),
        )
        assertEquals("Stromversorgung beim Start: USB, Akku 92 %, lädt", ersterEintrag)

        // 2. USB -> keine -> Eintrag
        val abgesteckt = Stromzustand(
            quelle = Stromzustand.Quelle.KEINE,
            status = Stromzustand.Status.ENTLAEDT,
            prozent = 100,
        )
        val wechselEintrag = naechsterEintrag(startZustand, abgesteckt)
        assertEquals(
            "Stromversorgung: USB → keine, Akku 100 %, entlädt",
            wechselEintrag,
        )

        // 3. Gleicher Zustand -> null
        val unverändert = Stromzustand(
            quelle = Stromzustand.Quelle.KEINE,
            status = Stromzustand.Status.ENTLAEDT,
            prozent = 100,
        )
        assertNull("Gleicher Zustand darf keinen Eintrag erzeugen", naechsterEintrag(abgesteckt, unverändert))

        // 4. Entladen 95 -> 91 % -> null (gleicher Zehnerschritt)
        val entladen95 = Stromzustand(
            quelle = Stromzustand.Quelle.KEINE,
            status = Stromzustand.Status.ENTLAEDT,
            prozent = 95,
        )
        val entladen91 = Stromzustand(
            quelle = Stromzustand.Quelle.KEINE,
            status = Stromzustand.Status.ENTLAEDT,
            prozent = 91,
        )
        assertNull("Entladen von 95% auf 91% darf keinen Eintrag erzeugen", naechsterEintrag(entladen95, entladen91))

        // 5. 91 -> 89 % -> Eintrag (Schritt ueber die 90%-Zehnergrenze nach unten)
        val entladen89 = Stromzustand(
            quelle = Stromzustand.Quelle.KEINE,
            status = Stromzustand.Status.ENTLAEDT,
            prozent = 89,
        )
        val zehnerSprungEintrag = naechsterEintrag(entladen91, entladen89)
        assertNotNull("Schritt ueber die 90%-Grenze nach unten muss Eintrag erzeugen", zehnerSprungEintrag)
        assertEquals("Stromversorgung: keine, Akku 89 %, entlädt", zehnerSprungEintrag)

        // 6. Laden 89 -> 95 % -> null
        val laden89 = Stromzustand(
            quelle = Stromzustand.Quelle.NETZTEIL,
            status = Stromzustand.Status.LAEDT,
            prozent = 89,
        )
        val laden95 = Stromzustand(
            quelle = Stromzustand.Quelle.NETZTEIL,
            status = Stromzustand.Status.LAEDT,
            prozent = 95,
        )
        assertNull("Laden von 89% auf 95% darf keinen Eintrag erzeugen", naechsterEintrag(laden89, laden95))
    }
}
