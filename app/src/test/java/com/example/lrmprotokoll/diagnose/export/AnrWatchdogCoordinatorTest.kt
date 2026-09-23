package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.ANR_WATCHDOG_DATEINAME
import com.example.lrmprotokoll.diagnose.BreadcrumbRingFile
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import com.example.lrmprotokoll.diagnose.HaengerBefund
import com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * O-8: [AnrWatchdogCoordinator] - Mitschnitt, Report-Event, ANR-Bundle in der Outbox, keine
 * Obergrenze und Upload unabhaengig vom Absturz-Schalter (Owner-Entscheidung 23.09.2026). Echter [SupportBundleExporter] (Bundle-Inhalt wird
 * geprueft), Datenbank/Einstellungen aus dem Robolectric-Container wie in
 * [SupportBundleExporterTest]; Log-DAO und Upload-Planung als handgeschriebene Fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnrWatchdogCoordinatorTest {
    private object LeeresDiagnosticLogDao : DiagnosticLogDao {
        override suspend fun insert(eintrag: DiagnosticLogEntity) {}

        override fun neueste(grenze: Int): Flow<List<DiagnosticLogEntity>> = flowOf(emptyList())

        override suspend fun loescheAelterAls(grenze: Long) {}

        override suspend fun seite(
            nachId: Long,
            seitengroesse: Int,
        ): List<DiagnosticLogEntity> = emptyList()

        override suspend fun anzahlSeit(von: Long): Long = 0L
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container

    private lateinit var verzeichnis: File
    private val reporter = CompositeDiagnosticsReporter(sinks = emptyList())
    private var jetzt = 1_000_000_000_000L
    private var uploadsEingeplant = 0

    private val befund =
        HaengerBefund(
            dauerMs = 5_123L,
            mainThreadStack =
                arrayOf(
                    StackTraceElement("java.lang.Thread", "sleep", "Thread.java", 450),
                    StackTraceElement("com.example.lrmprotokoll.ui.Blockierer", "klick", "Blockierer.kt", 7),
                ),
        )

    @Before
    fun aufbauen() {
        verzeichnis = File(context.filesDir, "process_exit_traces_test_${System.nanoTime()}")
    }

    @After
    fun aufraeumen() {
        verzeichnis.deleteRecursively()
        File(context.filesDir, SUPPORT_OUTBOX_DIR).deleteRecursively()
    }

    private fun coordinator(
        scope: CoroutineScope = TestScope(),
        exporterReporter: DiagnosticsReporter = reporter,
    ) = AnrWatchdogCoordinator(
        context = context,
        verzeichnis = verzeichnis,
        reporter = reporter,
        exporter =
            SupportBundleExporter(
                context = context,
                reporter = exporterReporter,
                diagnosticLogDao = LeeresDiagnosticLogDao,
                breadcrumbRingFile = BreadcrumbRingFile(File(context.cacheDir, "ring_${System.nanoTime()}").apply { mkdirs() }),
                settingsManager = container.settingsManager,
                database = container.database,
                traceVerzeichnis = verzeichnis,
            ),
        scope = scope,
        jetztMs = { jetzt },
        weitereThreads = { mapOf(Thread("DefaultDispatcher-worker-1") to arrayOf(StackTraceElement("Worker", "arbeite", "Worker.kt", 1))) },
        uploadEinplanen = { uploadsEingeplant++ },
    )

    private val mitschnitt: File get() = File(verzeichnis, ANR_WATCHDOG_DATEINAME)

    @Test
    fun haengerSchreibtMitschnittUndMeldetEvent() {
        coordinator().haengerErkannt(befund)

        val text = mitschnitt.readText()
        assertTrue(text.contains("mainThreadOhneReaktionMs: 5123"))
        assertTrue(text.contains("at java.lang.Thread.sleep(Thread.java:450)"))
        assertTrue(text.contains("\"DefaultDispatcher-worker-1\""))
        assertEquals(DiagnosticCode.APP_ANR_WATCHDOG, reporter.recentEvents().single().code)
    }

    @Test
    fun bundleEnthaeltMitschnittLandetInDerOutboxUndPlantUpload() =
        runTest {
            val coordinator = coordinator()
            coordinator.haengerErkannt(befund)

            val bundle = coordinator.bundleErstellen(ausloeser = "Watchdog")

            assertNotNull(bundle)
            assertTrue(bundle!!.name.endsWith("_anr.zip"))
            assertEquals(File(context.filesDir, SUPPORT_OUTBOX_DIR), bundle.parentFile)
            ZipFile(bundle).use { zip ->
                val eintrag = zip.getEntry("crash/anr_watchdog.txt")
                assertNotNull(eintrag)
                assertTrue(
                    zip
                        .getInputStream(eintrag)
                        .bufferedReader()
                        .readText()
                        .contains("Blockierer.klick"),
                )
            }
            assertFalse("Der Mitschnitt ist abgearbeitet", mitschnitt.exists())
            assertEquals(1, uploadsEingeplant)
        }

    @Test
    fun ohneMitschnittEntstehtKeinBundle() =
        runTest {
            assertNull(coordinator().bundleErstellen(ausloeser = "Watchdog"))
            assertEquals(0, uploadsEingeplant)
        }

    @Test
    fun keineObergrenzeJederHaengerErgibtEinBundle() =
        runTest {
            val coordinator = coordinator()
            repeat(5) {
                coordinator.haengerErkannt(befund)
                assertNotNull("Haenger ${it + 1} muss ein Bundle bekommen", coordinator.bundleErstellen(ausloeser = "Watchdog"))
            }
            assertEquals(5, uploadsEingeplant)
        }

    @Test
    fun uploadWirdAuchBeiAusgeschaltetemAbsturzSchalterEingeplant() =
        runTest {
            container.settingsManager.absturzAutoUploadAktiv = false
            val coordinator = coordinator()
            coordinator.haengerErkannt(befund)

            assertNotNull(coordinator.bundleErstellen(ausloeser = "Watchdog"))
            assertEquals(1, uploadsEingeplant)
        }

    @Test
    fun ausstehenderMitschnittWirdBeimStartNachgeholt() =
        runTest {
            // Simuliert einen Prozess, den Android waehrend des Haengers beendet hat: der Mitschnitt
            // liegt noch da, ein Bundle gibt es nicht.
            coordinator().haengerErkannt(befund)
            val scope = TestScope(testScheduler)

            coordinator(scope).ausstehendesBundleNachholen()
            scope.advanceUntilIdle()

            assertFalse(mitschnitt.exists())
            val outbox = File(context.filesDir, SUPPORT_OUTBOX_DIR).listFiles().orEmpty()
            assertEquals(1, outbox.count { it.name.endsWith("_anr.zip") })
        }

    @Test
    fun einNeuerHaengerWaehrendDesBundlesBleibtFuerDasNaechsteBundleErhalten() =
        runTest {
            // Der Exporter meldet zu Beginn einen Breadcrumb "SupportBundle" - genau dann "haengt"
            // der Main-Thread erneut und ueberschreibt den Mitschnitt.
            lateinit var zweiter: AnrWatchdogCoordinator
            val zweiterBefund = HaengerBefund(dauerMs = 77_777L, mainThreadStack = befund.mainThreadStack)
            val exporterReporter =
                object : DiagnosticsReporter by reporter {
                    override fun breadcrumb(
                        category: String,
                        message: String,
                        data: Map<String, Any?>,
                        level: DiagnosticSeverity,
                    ) {
                        if (category == "SupportBundle") zweiter.haengerErkannt(zweiterBefund)
                    }
                }
            zweiter = coordinator(exporterReporter = exporterReporter)
            zweiter.haengerErkannt(befund)

            assertNotNull(zweiter.bundleErstellen(ausloeser = "Watchdog"))

            assertTrue("Der Mitschnitt des zweiten Haengers muss liegen bleiben", mitschnitt.exists())
            assertTrue(mitschnitt.readText().contains("mainThreadOhneReaktionMs: 77777"))
        }
}
