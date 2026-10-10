package com.example.lrmprotokoll.testhilfen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /**
     * Review-Befund 01.10.2026: ohne dies konnte eine Datei aus einem FRUEHEREN Lauf den
     * Dateinachweis unten falsch gruen machen - die Regel haengt an, sie ueberschreibt nicht, und
     * eine alte Datei enthaelt denselben Testnamen. Der Nachweis haette dann auch bestanden, wenn
     * der Schreibweg kaputt ist.
     */
    @Before
    fun alteDiagnoseEntfernen() {
        File(DIAGNOSEDATEI).delete()
    }

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
        val datei = File(DIAGNOSEDATEI)
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

    /**
     * Review zu PR #269: Die Stacks muessen in der Datei stehen, BEVOR die Stichproben fertig
     * sind - sonst ginge bei einem Abbruch in den Stichproben-Sekunden alles verloren.
     */
    @Test
    fun stacksStehenSchonWaehrendDerStichprobenInDerDatei() {
        var zwischenstand = ""
        fuehreAus(ZeitwaechterRegel(grenzeSekunden = 1)) {
            Thread.sleep(1_600)
            zwischenstand = File(DIAGNOSEDATEI).takeIf { it.isFile }?.readText().orEmpty()
            Thread.sleep(400)
        }

        assertTrue("Stacks fehlen waehrend der Stichproben: $zwischenstand", zwischenstand.contains("\tat "))
        assertFalse("Stichproben duerfen da noch nicht fertig sein", zwischenstand.contains("=== ZEITWAECHTER Ende ==="))
    }

    /**
     * Review zu PR #269: Endet der Test, waehrend die Stichproben laufen, muessen sie sofort
     * aufhoeren - sonst landen die Rahmen des naechsten Tests in dieser Zaehlung. Ohne Abbruch
     * dauerte dieser Lauf 1 s Grenze + 5 s Stichproben.
     */
    @Test
    fun stichprobenEndenMitDemTest() {
        val start = System.nanoTime()
        val ausgabe = fuehreAus(ZeitwaechterRegel(grenzeSekunden = 1)) { Thread.sleep(1_500) }
        val dauerMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("Stichproben liefen nach dem Testende weiter: $dauerMs ms", dauerMs < 3_000)
        assertTrue("Abschluss fehlt: $ausgabe", ausgabe.contains("=== ZEITWAECHTER Ende ==="))
    }

    /**
     * Review zu PR #269: Gesampelt wird der Testthread selbst (unter Robolectric der
     * "SDK <n> Main Thread"), nicht ein per Momentaufnahme `RUNNABLE` gesuchter Thread - der
     * hier schlafende Testthread waere dabei gar nicht gefunden worden.
     */
    @Test
    fun dieStichprobenTreffenDenTestthread() {
        val ausgabe = fuehreAus(ZeitwaechterRegel(grenzeSekunden = 1)) { schlafeErkennbar() }

        assertTrue("Kein Stichprobenblock: $ausgabe", ausgabe.contains("--- Stichproben \""))
        assertTrue("Rahmen des Testthreads nicht getroffen: $ausgabe", ausgabe.contains("schlafeErkennbar"))
    }

    @Test
    fun zaehleRahmenZaehltJeStackEinmalUndFiltert() {
        fun rahmen(
            klasse: String,
            methode: String,
        ) = StackTraceElement(klasse, methode, null, -1)

        val stack =
            arrayOf(
                rahmen("java.lang.Thread", "sleep"),
                rahmen("com.example.lrmprotokoll.ui.MeterScreenKt", "MeterScreen"),
                rahmen("com.example.lrmprotokoll.ui.MeterScreenKt", "MeterScreen"),
                rahmen("androidx.compose.foundation.lazy.LazyListKt", "LazyColumn"),
                rahmen("androidx.compose.ui.platform.AndroidComposeView", "measureAndLayout"),
                rahmen("com.example.lrmprotokoll.testhilfen.ZeitwaechterRegel", "gibDiagnoseAus"),
                rahmen("androidx.compose.ui.test.junit4.AndroidComposeTestRule", "waitForIdle"),
                rahmen("com.example.lrmprotokoll.ui.MeterScreenComposeTest", "einTest"),
            )

        val treffer = zaehleRahmen(listOf(stack, stack)).toMap()

        assertEquals(
            mapOf(
                "com.example.lrmprotokoll.ui.MeterScreenKt.MeterScreen" to 2,
                "androidx.compose.foundation.lazy.LazyListKt.LazyColumn" to 2,
                "androidx.compose.ui.platform.AndroidComposeView.measureAndLayout" to 2,
            ),
            treffer,
        )
    }

    @Test
    fun zaehleNachrichtenZaehltJeStichprobeEinmal() {
        val stichproben =
            listOf(
                listOf("Runnable A", "Runnable A", "Handler B what=1"),
                listOf("Runnable A"),
                emptyList(),
            )

        assertEquals(
            listOf("Runnable A" to 2, "Handler B what=1" to 1),
            zaehleNachrichten(stichproben),
        )
    }

    @Test
    fun lambdaAdresseWirdFuerDieZaehlungEntfernt() {
        assertEquals(
            "com.example.Foo$\$Lambda",
            ohneLambdaAdresse("com.example.Foo$\$Lambda/0x00007eff64eb0c00"),
        )
        assertEquals("com.example.Bar", ohneLambdaAdresse("com.example.Bar"))
        // Das Format des Zusatzes ist nicht festgelegt - geschnitten wird am "/" selbst.
        assertEquals("com.example.Foo$\$Lambda", ohneLambdaAdresse("com.example.Foo$\$Lambda/123456"))
    }

    /** Review zu PR #270: Gleichstand darf nicht von der HashMap-Iteration abhaengen. */
    @Test
    fun gleichstandWirdAlphabetischSortiert() {
        val stichproben = listOf(listOf("Runnable Z", "Runnable A", "Runnable M"))

        assertEquals(
            listOf("Runnable A" to 1, "Runnable M" to 1, "Runnable Z" to 1),
            zaehleNachrichten(stichproben),
        )
    }

    /** Ohne Robolectric gibt es keinen Main-Looper - die Diagnose meldet das, statt zu scheitern. */
    @Test
    fun warteschlangeIstAusserhalbVonRobolectricNichtLesbar() {
        assertEquals(null, leseHauptLooperWarteschlange())
    }

    private companion object {
        /** Derselbe Pfad, den [ZeitwaechterRegel] schreibt - relativ zum Modulverzeichnis. */
        const val DIAGNOSEDATEI = "build/zeitwaechter-diagnose.txt"
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

/**
 * Auf Dateiebene, nicht in der Testklasse: Rahmen aus Klassen, deren Name auf `Test` endet, filtert
 * die Regel als Testrumpf heraus. Hier soll der Rahmen gerade sichtbar sein.
 */
private fun schlafeErkennbar() = Thread.sleep(1_800)
