package com.example.lrmprotokoll.diagnose

import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M12 Schritt 2: Ringdatei-Verhalten (Konzept 4.3) - Schreiben, Rotation, Zusammenfuehren,
 * Obergrenze, angebrochene erste Zeile, Nebenlaeufigkeit.
 *
 * Robolectric statt plain JUnit: [BreadcrumbRingFile] serialisiert ueber `org.json.JSONObject`,
 * das ohne Robolectric-Shadow nur als Stub vorliegt (wie in [DiagnosticRedactorTest]s
 * Nachbar-Test [com.example.lrmprotokoll.diagnose.export.SupportBundleExporterTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BreadcrumbRingFileTest {

    private lateinit var verzeichnis: File

    @Before
    fun aufbauen() {
        verzeichnis = File.createTempFile("ringfile", "dir").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun aufraeumen() {
        verzeichnis.deleteRecursively()
    }

    private fun breadcrumb(nachricht: String, kategorie: String = "Test") = DiagnosticBreadcrumb(
        timestamp = Instant.now(),
        category = kategorie,
        message = nachricht,
        level = DiagnosticSeverity.INFO,
        data = mapOf("k" to "v"),
    )

    @Test
    fun schreibtUndListEineBreadcrumb() {
        val ring = BreadcrumbRingFile(verzeichnis)
        ring.anhaengen(breadcrumb("erste Nachricht"))
        ring.wartenBisFertig()

        val gelesen = ring.lesen()
        assertEquals(1, gelesen.size)
        assertEquals("erste Nachricht", gelesen.first().message)
        assertEquals("Test", gelesen.first().category)
        assertEquals("v", gelesen.first().data["k"])
    }

    @Test
    fun rotiertBeiUeberschreitenDerObergrenzeUndUeberschreitetDasGesamtbudgetNicht() {
        val ring = BreadcrumbRingFile(verzeichnis)
        // ~120 Byte je Zeile * 6000 > 700 KB, reicht sicher fuer mindestens eine volle Rotation
        // (256 KB) und liegt nahe an einer zweiten.
        repeat(6000) { i -> ring.anhaengen(breadcrumb("Nachricht Nummer $i mit etwas Fuelltext")) }
        ring.wartenBisFertig()

        val dateiA = File(verzeichnis, "breadcrumbs_a.jsonl")
        val dateiB = File(verzeichnis, "breadcrumbs_b.jsonl")
        assertTrue("Datei A sollte existieren", dateiA.exists())
        assertTrue("Bei so vielen Eintraegen muss rotiert worden sein", dateiB.exists() && dateiB.length() > 0)

        val gesamtgroesse = dateiA.length() + dateiB.length()
        assertTrue(
            "Gesamtgroesse ($gesamtgroesse) darf 2x Obergrenze nie ueberschreiten",
            gesamtgroesse <= 2 * BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES,
        )
        assertTrue(
            "Jede einzelne Datei darf die Obergrenze nicht ueberschreiten",
            dateiA.length() <= BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES &&
                dateiB.length() <= BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES,
        )
    }

    @Test
    fun ueberschreibtDieAeltesteDateiBeimZweitenWechsel() {
        val ring = BreadcrumbRingFile(verzeichnis)
        // Genug Eintraege fuer zwei volle Rotationen (A -> B -> A erneut, dabei A geleert).
        repeat(15000) { i -> ring.anhaengen(breadcrumb("Fuelltext Nummer $i mit etwas mehr Laenge drin")) }
        ring.wartenBisFertig(timeoutSekunden = 15)

        val gelesen = ring.lesen()
        assertTrue("Die aeltesten Eintraege (Index 0) duerfen nach zwei Rotationen nicht mehr vorhanden sein", gelesen.none { it.message.contains("Nummer 0 ") })
        assertTrue("Die juengsten Eintraege muessen noch vorhanden sein", gelesen.any { it.message.contains("Nummer 14999 ") })
    }

    @Test
    fun fuehrtBeideDateienInZeitlicherReihenfolgeZusammen() {
        val ring = BreadcrumbRingFile(verzeichnis)
        ring.anhaengen(breadcrumb("zuerst"))
        ring.wartenBisFertig()
        // Erzwingt eine Rotation, damit "zuerst" in Datei A und "danach" in Datei B landet.
        repeat(3000) { i -> ring.anhaengen(breadcrumb("Fuelltext $i, damit die erste Datei vollaeuft und rotiert wird")) }
        ring.wartenBisFertig()
        ring.anhaengen(breadcrumb("danach"))
        ring.wartenBisFertig()

        val gelesen = ring.lesen()
        val indexZuerst = gelesen.indexOfFirst { it.message == "zuerst" }
        val indexDanach = gelesen.indexOfFirst { it.message == "danach" }
        assertTrue("'danach' muss nach 'zuerst' in der zusammengefuehrten Liste stehen", indexDanach > indexZuerst || indexZuerst == -1)
        assertTrue("'danach' muss ganz am Ende stehen (juengster Eintrag)", indexDanach == gelesen.lastIndex)
    }

    @Test
    fun ueberspringtEineAngebrocheneErsteZeileStattZuScheitern() {
        val ring = BreadcrumbRingFile(verzeichnis)
        ring.anhaengen(breadcrumb("gueltiger Eintrag eins"))
        ring.anhaengen(breadcrumb("gueltiger Eintrag zwei"))
        ring.wartenBisFertig()

        // Simuliert eine durch Rotation angebrochene erste Zeile: unvollstaendiges JSON vor den
        // bisherigen, gueltigen Zeilen.
        val dateiA = File(verzeichnis, "breadcrumbs_a.jsonl")
        val bisherigerInhalt = dateiA.readText(StandardCharsets.UTF_8)
        dateiA.writeText("{\"timestamp\":123,\"category\":\"Kap\n$bisherigerInhalt", StandardCharsets.UTF_8)

        val gelesen = ring.lesen()
        assertEquals(2, gelesen.size)
        assertEquals("gueltiger Eintrag eins", gelesen[0].message)
        assertEquals("gueltiger Eintrag zwei", gelesen[1].message)
    }

    @Test
    fun nebenlaeufigeSchreibvorgaengeVerlierenKeineZeilenUndUeberschreitenDieGrenzeNicht() {
        val ring = BreadcrumbRingFile(verzeichnis)
        val threadPool = Executors.newFixedThreadPool(8)
        val anzahlProThread = 200
        val latch = CountDownLatch(8)
        repeat(8) { threadIndex ->
            threadPool.execute {
                repeat(anzahlProThread) { i ->
                    ring.anhaengen(breadcrumb("Thread $threadIndex Eintrag $i"))
                }
                latch.countDown()
            }
        }
        latch.await()
        threadPool.shutdown()
        ring.wartenBisFertig()

        val dateiA = File(verzeichnis, "breadcrumbs_a.jsonl")
        val dateiB = File(verzeichnis, "breadcrumbs_b.jsonl")
        val gesamtgroesse = dateiA.length() + dateiB.length()
        assertTrue(
            "Gesamtgroesse darf 2x Obergrenze auch bei Nebenlaeufigkeit nie ueberschreiten",
            gesamtgroesse <= 2 * BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES,
        )

        // Da 8*200=1600 kleine Eintraege deutlich unter dem 256-KB-Budget einer Datei liegen,
        // duerfen dabei keine Zeilen verloren gehen (anders als beim Rotationstest oben, der
        // bewusst ueber die Kapazitaet hinaus schreibt).
        val gelesen = ring.lesen()
        assertEquals(8 * anzahlProThread, gelesen.size)
    }

    @Test
    fun beimStartBeschneidenKuerztEineZuGrosseDateiAufDieObergrenze() {
        val dateiA = File(verzeichnis, "breadcrumbs_a.jsonl")
        // Direkt eine zu grosse Datei simulieren (frueherer Fehler, siehe Konzept Aufgabe 5) -
        // eine lange Zeile pro "Eintrag", damit die Groesse leicht kontrollierbar ist.
        val zeile = "{\"timestamp\":1,\"category\":\"c\",\"message\":\"" + "x".repeat(500) + "\",\"level\":\"INFO\",\"data\":{}}\n"
        val wiederholungen = (BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES.toInt() / zeile.toByteArray(StandardCharsets.UTF_8).size) + 50
        dateiA.writeText(zeile.repeat(wiederholungen), StandardCharsets.UTF_8)
        assertTrue(dateiA.length() > BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES)

        val ring = BreadcrumbRingFile(verzeichnis)
        ring.beimStartBeschneiden()
        ring.wartenBisFertig()

        assertTrue(
            "Nach dem Beschneiden muss die Datei innerhalb der Obergrenze liegen",
            dateiA.length() <= BreadcrumbRingFile.DATEI_OBERGRENZE_BYTES,
        )
    }
}
