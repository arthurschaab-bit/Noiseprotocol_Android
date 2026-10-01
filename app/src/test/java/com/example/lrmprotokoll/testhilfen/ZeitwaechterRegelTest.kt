package com.example.lrmprotokoll.testhilfen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Gegenprobe zu [ZeitwaechterRegel]: ohne Nachweis ist eine Diagnoseregel nur eine Behauptung.
 * Die Regel schreibt bewusst nach `System.err`, damit die Ausgabe auch dann im CI-Joblog landet,
 * wenn kein XML-Bericht mehr entsteht - also wird hier `System.err` umgeleitet.
 */
class ZeitwaechterRegelTest {
    private val beschreibung: Description =
        Description.createTestDescription("EineKlasse", "einTest")

    @Test
    fun einSchnellerTestLoestKeineDiagnoseAus() {
        val ausgabe = fuehreAus(ZeitwaechterRegel(grenzeSekunden = 30)) { /* sofort fertig */ }

        assertFalse(
            "Ein Test unter der Grenze darf das Protokoll nicht zumuellen, Ausgabe war: $ausgabe",
            ausgabe.contains("ZEITWAECHTER"),
        )
    }

    @Test
    fun einZuLangerTestBekommtDiagnoseMitThreadUndTestnamen() {
        // Grenze 1 s, Testrumpf 3 s - der Beobachter muss waehrend des Laufs zuschlagen.
        val ausgabe = fuehreAus(ZeitwaechterRegel(grenzeSekunden = 1)) { Thread.sleep(3_000) }

        assertTrue("Kopfzeile fehlt: $ausgabe", ausgabe.contains("=== ZEITWAECHTER:"))
        assertTrue("Testname fehlt: $ausgabe", ausgabe.contains("EineKlasse.einTest"))
        assertTrue("Stack des Testthreads fehlt: $ausgabe", ausgabe.contains("\tat "))
        assertTrue("Abschluss fehlt: $ausgabe", ausgabe.contains("=== ZEITWAECHTER Ende ==="))

        // Der zweite Ausgabeweg ist der wichtigere: er ueberlebt eine von Test.timeout
        // abgeschnittene Aufgabe, in der kein XML-Bericht mehr entsteht.
        val datei = File("build/zeitwaechter-diagnose.txt")
        assertTrue("Diagnosedatei fehlt unter ${datei.absolutePath}", datei.isFile)
        assertTrue("Diagnosedatei ohne Testnamen", datei.readText().contains("EineKlasse.einTest"))
    }

    @Test
    fun einFehlschlagImTestrumpfBleibtEinFehlschlag() {
        val regel = ZeitwaechterRegel(grenzeSekunden = 30)
        val statement =
            regel.apply(
                object : Statement() {
                    override fun evaluate() = throw IllegalStateException("der eigentliche Fehler")
                },
                beschreibung,
            )

        val geworfen = runCatching { statement.evaluate() }.exceptionOrNull()

        assertTrue("Die Regel darf den Fehler nicht verschlucken", geworfen is IllegalStateException)
        assertTrue(geworfen!!.message!!.contains("der eigentliche Fehler"))
    }

    private fun fuehreAus(
        regel: ZeitwaechterRegel,
        rumpf: () -> Unit,
    ): String {
        val puffer = ByteArrayOutputStream()
        val vorher = System.err
        System.setErr(PrintStream(puffer, true))
        try {
            regel
                .apply(
                    object : Statement() {
                        override fun evaluate() = rumpf()
                    },
                    beschreibung,
                ).evaluate()
        } finally {
            System.setErr(vorher)
        }
        return puffer.toString()
    }
}
