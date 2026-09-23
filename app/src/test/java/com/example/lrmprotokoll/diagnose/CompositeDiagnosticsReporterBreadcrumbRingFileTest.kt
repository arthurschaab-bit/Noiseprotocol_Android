package com.example.lrmprotokoll.diagnose

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M12 Schritt 2 Test: ein Breadcrumb landet in der RAM-Historie UND in der Ringdatei. Eigene
 * Testklasse (statt Erweiterung von [CompositeDiagnosticsReporterTest]), weil [BreadcrumbRingFile]
 * ueber `org.json.JSONObject` serialisiert und deshalb Robolectric braucht - die uebrigen Tests
 * in [CompositeDiagnosticsReporterTest] sind bewusst plain JUnit und bleiben das.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositeDiagnosticsReporterBreadcrumbRingFileTest {

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

    @Test
    fun breadcrumbLandetInRamHistorieUndInDerRingdatei() {
        val ringFile = BreadcrumbRingFile(verzeichnis)
        val reporter = CompositeDiagnosticsReporter(sinks = emptyList(), ringFile = ringFile)

        reporter.breadcrumb("BLE", "Verbindung wiederhergestellt")
        ringFile.wartenBisFertig()

        val ausRam = reporter.recentBreadcrumbs()
        assertEquals(1, ausRam.size)
        assertEquals("Verbindung wiederhergestellt", ausRam.first().message)

        val ausRingdatei = ringFile.lesen()
        assertEquals(1, ausRingdatei.size)
        assertEquals("Verbindung wiederhergestellt", ausRingdatei.first().message)
        assertEquals("BLE", ausRingdatei.first().category)
    }

    @Test
    fun funktioniertWeiterhinOhneRingdatei() {
        // Bestehendes Verhalten (Default null) bleibt unveraendert - kein Absturz, keine Datei.
        val reporter = CompositeDiagnosticsReporter(sinks = emptyList())
        reporter.breadcrumb("BLE", "ohne Ringdatei")
        assertEquals(1, reporter.recentBreadcrumbs().size)
    }
}
