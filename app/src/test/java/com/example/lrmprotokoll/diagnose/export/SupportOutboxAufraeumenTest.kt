package com.example.lrmprotokoll.diagnose.export

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M12 Schritt 5 Aufgabe 4: `support_outbox/` wird auf eine Obergrenze (Anzahl und Gesamtgroesse)
 * beschnitten - periodische/manuelle Bundles zuerst, Absturz-/ANR-Bundles zuletzt.
 */
class SupportOutboxAufraeumenTest {

    private lateinit var outboxDir: File

    @Before
    fun aufbauen() {
        outboxDir = File.createTempFile("outbox", "dir").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun aufraeumen() {
        outboxDir.deleteRecursively()
    }

    private fun datei(name: String, groesseBytes: Int, alterMs: Long): File {
        val f = File(outboxDir, name)
        f.writeBytes(ByteArray(groesseBytes))
        f.setLastModified(System.currentTimeMillis() - alterMs)
        return f
    }

    @Test
    fun bleibtUnangetastetUnterhalbBeiderGrenzen() {
        datei("2026-09-17_100000_periodisch.zip", 1000, 1000)
        datei("2026-09-17_110000_absturz.zip", 1000, 500)

        raeumeSupportOutboxAuf(outboxDir)

        assertEquals(2, outboxDir.listFiles()?.size)
    }

    @Test
    fun periodischeBundlesWerdenZuerstVerworfenAbsturzBundlesUeberlebenLaenger() {
        // 25 Dateien > OUTBOX_MAX_DATEIEN (20) - erzwingt Aufraeumen ueber die Anzahlgrenze.
        // 5 muessen weichen, damit 20 uebrig bleiben.
        val periodische = (1..20).map { i -> datei("2026-09-0${(i % 9) + 1}_${i}0000_periodisch.zip", 10, (30 - i) * 1000L) }
        val absturz = (1..5).map { i -> datei("2026-09-0${i}_${i}0000_absturz.zip", 10, (30 - i) * 1000L) }

        raeumeSupportOutboxAuf(outboxDir)

        val uebrig = outboxDir.listFiles()?.map { it.name }.orEmpty()
        assertEquals(20, uebrig.size)
        assertTrue(
            "Alle 5 Absturz-Bundles muessen ueberleben",
            absturz.all { it.name in uebrig },
        )
        assertEquals(
            "Genau 15 der 20 periodischen Bundles duerfen uebrig bleiben (5 wurden verworfen)",
            15,
            uebrig.count { it.contains("_periodisch") },
        )
    }

    @Test
    fun aeltesteDateienDerselbenPrioritaetWerdenZuerstVerworfen() {
        val alt = datei("2026-09-01_100000_periodisch.zip", 10, 10_000)
        val neu = datei("2026-09-17_100000_periodisch.zip", 10, 100)
        // 19 weitere fuellen die Grenze auf, damit das Aufraeumen tatsaechlich greift.
        (1..19).forEach { i -> datei("2026-09-0${(i % 9) + 1}_${i}0000_manuell.zip", 10, (5000 - i).toLong()) }

        raeumeSupportOutboxAuf(outboxDir)

        assertTrue("Die neuere Datei muss ueberleben", neu.exists())
        assertTrue("Die aeltere Datei darf nicht mehr da sein", !alt.exists())
    }

    @Test
    fun gesamtgroesseWirdAlsZweiteGrenzeDurchgesetzt() {
        // Wenige, aber grosse Dateien - unter der Anzahlgrenze, ueber der Groessengrenze.
        datei("2026-09-17_100000_periodisch.zip", 60 * 1024 * 1024, 2000)
        datei("2026-09-17_110000_periodisch.zip", 60 * 1024 * 1024, 1000)

        raeumeSupportOutboxAuf(outboxDir)

        val gesamtgroesse = outboxDir.listFiles()?.sumOf { it.length() } ?: 0L
        assertTrue("Gesamtgroesse muss nach dem Aufraeumen unter dem 100-MB-Budget liegen", gesamtgroesse <= 100L * 1024 * 1024)
    }
}
