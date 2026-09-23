package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.BreadcrumbRingFile
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticContext
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.ANR_TRACE_DATEINAME
import com.example.lrmprotokoll.diagnose.NATIVE_TOMBSTONE_DATEINAME
import com.example.lrmprotokoll.diagnose.ProcessExitInfo
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M12 Schritt 4: [SupportBundleExporter] streamend, mit erweiterten Inhalten und
 * Groessenbudget (Konzept 4.4/4.5). Nutzt fuer `database`/`settingsManager`/`breadcrumbRingFile`
 * den echten, von Robolectric gebauten [LaermprotokollApp]-Container (wie
 * [com.example.lrmprotokoll.data.MeasurementDaoTest]), fuer [DiagnosticLogDao] aber eine
 * handgeschriebene Fake-Implementierung (AGENTS.md Abschnitt 3: keine Mocking-Bibliothek) -
 * die geteilte, nicht pro Testmethode isolierte Room-Datenbank waere fuer die hier geprueften
 * exakten Zeilenzahlen (Paginierung, 50000-Eintraege-Test) ungeeignet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportBundleExporterTest {

    private class FakeDiagnosticLogDao(private val eintraege: List<DiagnosticLogEntity>) : DiagnosticLogDao {
        var abgefragteSeiten = 0
        var schlaegtFehl = false

        override suspend fun insert(eintrag: DiagnosticLogEntity) {}
        override fun neueste(grenze: Int): Flow<List<DiagnosticLogEntity>> = flowOf(eintraege.take(grenze))
        override suspend fun loescheAelterAls(grenze: Long) {}
        override suspend fun seite(nachId: Long, seitengroesse: Int): List<DiagnosticLogEntity> {
            if (schlaegtFehl) error("Simulierter DB-Fehler fuer den Fehlertoleranz-Test")
            abgefragteSeiten++
            return eintraege.filter { it.id > nachId }.sortedBy { it.id }.take(seitengroesse)
        }
        override suspend fun anzahlSeit(von: Long): Long = eintraege.count { it.timestamp >= von }.toLong()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = ApplicationProvider.getApplicationContext<LaermprotokollApp>().container

    private fun exporter(
        dao: DiagnosticLogDao,
        reporter: com.example.lrmprotokoll.diagnose.DiagnosticsReporter = CompositeDiagnosticsReporter(
            sinks = emptyList(),
            initialContext = DiagnosticContext(appVersion = "1.0", buildType = "debug"),
        ),
        ringFile: BreadcrumbRingFile = BreadcrumbRingFile(File(context.cacheDir, "ring_${System.nanoTime()}").apply { mkdirs() }),
        traceVerzeichnis: File = File(context.filesDir, "process_exit_traces_test_${System.nanoTime()}"),
    ) = SupportBundleExporter(
        context = context,
        reporter = reporter,
        diagnosticLogDao = dao,
        breadcrumbRingFile = ringFile,
        settingsManager = container.settingsManager,
        database = container.database,
        traceVerzeichnis = traceVerzeichnis,
    )

    private fun logEintrag(id: Long, nachricht: String) =
        DiagnosticLogEntity(id = id, timestamp = System.currentTimeMillis(), message = nachricht)

    @Test
    fun createBundleCreatesZipWithAllRequiredFiles() = runTest {
        val reporter = CompositeDiagnosticsReporter(
            sinks = emptyList(),
            initialContext = DiagnosticContext(appVersion = "1.0", buildType = "debug"),
        )
        reporter.breadcrumb("BLE", "Scan started for device")
        reporter.report(
            code = DiagnosticCode.BLE_CONNECT_FAILED,
            component = "BleMeterTransport",
            operation = "connect",
            severity = DiagnosticSeverity.ERROR,
            message = "Connect timed out",
        )
        val dao = FakeDiagnosticLogDao(listOf(logEintrag(1, "CONNECT_FAILED: Timeout nach 10s")))

        val zipFile = exporter(dao, reporter).createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))
        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.contains("manifest.json"))
            assertTrue(entries.contains("log/events.jsonl"))
            assertTrue(entries.contains("log/breadcrumbs.jsonl"))
            assertTrue(entries.contains("log/logcat.txt"))
            assertTrue(entries.contains("state/runtime.json"))
            assertTrue(entries.contains("state/settings.json"))
            assertTrue(entries.contains("state/db_stats.json"))
            assertTrue(entries.contains("checksums.sha256"))

            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            assertTrue(manifest.contains("\"typ\": \"manuell\""))

            val events = zip.getInputStream(zip.getEntry("log/events.jsonl")).bufferedReader().readText()
            assertTrue(events.contains("CONNECT_FAILED"))
        }
    }

    @Test
    fun crashBundleEnthaeltCrashOrdnerWennKontextDatenLiefert() = runTest {
        val dao = FakeDiagnosticLogDao(emptyList())
        val kontext = BundleKontext(
            typ = BundleTyp.ABSTURZ,
            ausloeser = "ACRA",
            acraReportJson = "{\"STACK_TRACE\":\"java.lang.RuntimeException\"}",
            threadDetails = "main: RUNNABLE\n  at com.example.Foo.bar",
            exitInfos = listOf(
                ProcessExitInfo(
                    reason = 6, status = 0, timestamp = 123L, importance = 100,
                    pss = 1000L, rss = 2000L, description = "crash", processName = "com.example.lrmprotokoll",
                    definingUid = 10123,
                ),
            ),
        )

        val zipFile = exporter(dao).createBundle(kontext)

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.contains("crash/acra_report.json"))
            assertTrue(entries.contains("crash/threads.txt"))
            assertTrue(entries.contains("crash/exit_info.json"))
        }
    }

    @Test
    fun crashBundleRedigiertPiiImAcraReportJson() = runTest {
        // Review-Fund (Copilot, PR #182): anders als crash/threads.txt lief der ACRA-JSON-Report
        // bislang unredigiert ins Bundle - er enthaelt u.a. LOGCAT/THREAD_DETAILS/CUSTOM_DATA
        // (AcraConfig.reportContent), also denselben Inhalt, der anderswo bereits redigiert wird.
        val dao = FakeDiagnosticLogDao(emptyList())
        val kontext = BundleKontext(
            typ = BundleTyp.ABSTURZ,
            ausloeser = "ACRA",
            acraReportJson = "{\"LOGCAT\":\"Device AA:BB:CC:DD:EE:FF failed for user@example.com\"," +
                "\"CUSTOM_DATA\":\"Authorization: Bearer supersecrettoken\"}",
        )

        val zipFile = exporter(dao).createBundle(kontext)

        ZipFile(zipFile).use { zip ->
            val acraReport = zip.getInputStream(zip.getEntry("crash/acra_report.json")).bufferedReader().readText()
            assertTrue(acraReport.contains("AA:BB:CC:XX:XX:XX"))
            assertTrue(acraReport.contains("[REDACTED_EMAIL]"))
            assertTrue(!acraReport.contains("user@example.com"))
            assertTrue(!acraReport.contains("supersecrettoken"))
        }
    }

    @Test
    fun createBundleSanitizesPiiInEventsAndBreadcrumbs() = runTest {
        val ringFile = BreadcrumbRingFile(File(context.cacheDir, "ring_pii_${System.nanoTime()}").apply { mkdirs() })
        val reporter = CompositeDiagnosticsReporter(
            sinks = emptyList(),
            initialContext = DiagnosticContext(appVersion = "1.0", buildType = "debug"),
            ringFile = ringFile,
        )
        reporter.breadcrumb(
            category = "Auth",
            message = "User user@example.com logged in with token=secret12345",
            data = mapOf("token" to "supersecret", "email" to "user@example.com"),
        )
        ringFile.wartenBisFertig()
        val dao = FakeDiagnosticLogDao(
            listOf(logEintrag(1, "Device AA:BB:CC:DD:EE:FF at C:\\Users\\secret\\test.txt failed for user@example.com"))
        )

        val zipFile = exporter(dao, reporter, ringFile).createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val events = zip.getInputStream(zip.getEntry("log/events.jsonl")).bufferedReader().readText()
            assertTrue(events.contains("AA:BB:CC:XX:XX:XX"))
            assertTrue(events.contains("[REDACTED_EMAIL]"))
            assertTrue(!events.contains("user@example.com"))
            assertTrue(!events.contains("C:\\Users\\secret\\test.txt"))

            val breadcrumbs = zip.getInputStream(zip.getEntry("log/breadcrumbs.jsonl")).bufferedReader().readText()
            assertTrue(breadcrumbs.contains("[REDACTED_EMAIL]"))
            assertTrue(!breadcrumbs.contains("user@example.com"))
            assertTrue(!breadcrumbs.contains("supersecret"))
        }
    }

    @Test
    fun createBundleVerwendetLesbarenDateinamenMitZeitstempelUndTyp() = runTest {
        val zipFile = exporter(FakeDiagnosticLogDao(emptyList()))
            .createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        assertTrue(zipFile.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}_\\d{6}_manuell\\.zip")))
        assertTrue(!zipFile.name.contains(" "))
    }

    @Test
    fun checksummenStimmenMitDemInhaltUeberein() = runTest {
        val zipFile = exporter(FakeDiagnosticLogDao(listOf(logEintrag(1, "Testeintrag"))))
            .createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val checksums = zip.getInputStream(zip.getEntry("checksums.sha256")).bufferedReader().readText()
                .lineSequence().filter { it.isNotBlank() }
                .associate { zeile -> val (hash, name) = zeile.split("  ", limit = 2); name to hash }

            assertTrue(checksums.containsKey("manifest.json"))
            assertTrue(checksums.containsKey("log/events.jsonl"))

            val inhalt = zip.getInputStream(zip.getEntry("log/events.jsonl")).readBytes()
            val erwarteterHash = MessageDigest.getInstance("SHA-256").digest(inhalt).joinToString("") { "%02x".format(it) }
            assertEquals(erwarteterHash, checksums.getValue("log/events.jsonl"))
        }
    }

    @Test
    fun paginierungLiefertAlleZeilenGenauEinmal() = runTest {
        // Deutlich mehr Eintraege als eine Seite (500) - erzwingt mindestens drei Seiten.
        val eintraege = (1..1234L).map { logEintrag(it, "Eintrag Nummer $it") }
        val dao = FakeDiagnosticLogDao(eintraege)

        val zipFile = exporter(dao).createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val zeilen = zip.getInputStream(zip.getEntry("log/events.jsonl")).bufferedReader().readLines()
                .filter { it.isNotBlank() }
            assertEquals(1234, zeilen.size)
            val ids = zeilen.map { org.json.JSONObject(it).getLong("id") }.toSet()
            assertEquals(1234, ids.size)
        }
        assertTrue("Mehr als eine Seite muss abgefragt worden sein", dao.abgefragteSeiten >= 3)
    }

    @Test
    fun bundleEntstehtBei50000EintraegenOhneOOM() = runTest {
        val eintraege = (1..50_000L).map { logEintrag(it, "Log-Eintrag $it mit etwas zusaetzlichem Text fuer Realismus") }
        val dao = FakeDiagnosticLogDao(eintraege)

        val zipFile = exporter(dao).createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)
    }

    @Test
    fun fehlertoleranzErzeugtBundleTrotzScheiterndemSammelschritt() = runTest {
        val dao = FakeDiagnosticLogDao(listOf(logEintrag(1, "wird nie gelesen")))
        dao.schlaegtFehl = true

        val zipFile = exporter(dao).createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        assertTrue("Ein scheiternder Sammelschritt darf das Bundle nicht verhindern", zipFile.exists())
        ZipFile(zipFile).use { zip ->
            assertNotNull("manifest.json muss trotzdem entstehen", zip.getEntry("manifest.json"))
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            // org.json escaped "/" als "\/" beim Serialisieren - deshalb ohne den "log/"-Praefix
            // suchen statt den woertlichen Pfad.
            assertTrue("Der Fehler muss im Manifest vermerkt sein", manifest.contains("events.jsonl"))
        }
    }

    @Test
    fun groessenbudgetWirdEingehaltenUndKuerztZuerstEventsDannLogcat() = runTest {
        // Absichtlich schlecht komprimierbarer Inhalt (jede Zeile pseudo-eindeutig), um trotz der
        // Einzelobergrenzen das 2-MB-Budget eines periodischen Bundles zu reissen.
        val eintraege = (1..20_000L).map { i ->
            logEintrag(i, (1..40).joinToString("") { ('a' + ((it + i) % 26).toInt()).toString() })
        }
        val dao = FakeDiagnosticLogDao(eintraege)

        val zipFile = exporter(dao).createBundle(BundleKontext(typ = BundleTyp.PERIODISCH, ausloeser = "Test"))

        assertTrue(zipFile.length() <= 2L * 1024 * 1024)
        ZipFile(zipFile).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            assertTrue(
                "manifest.json muss die Kuerzung vermerken, wenn das Budget nur durch Kuerzen eingehalten wurde",
                manifest.contains("\"kuerzungsstufe\": 0") || manifest.contains("Budget ueberschritten"),
            )
        }
    }

    @Test
    fun crashOrdnerWirdNieGekuerztAuchWennBudgetUeberschritten() = runTest {
        // Owner-Entscheidung O-7 (Konzept 4.5): crash/ bleibt vollstaendig, das 10-MB-Budget ist
        // fuer Absturz-Bundles ein Richtwert. Zufallsbytes komprimieren praktisch nicht -
        // ANR-Trace und Tombstone an ihren Einzelobergrenzen (4 MB + 8 MB) reissen das Budget
        // allein, auch nachdem events.jsonl und logcat.txt weggekuerzt sind.
        val zufall = java.util.Random(42)
        val anrTrace = ByteArray(4 * 1024 * 1024).also { zufall.nextBytes(it) }
        val tombstone = ByteArray(8 * 1024 * 1024).also { zufall.nextBytes(it) }
        val traces = File(context.filesDir, "process_exit_traces_test_${System.nanoTime()}").apply { mkdirs() }
        File(traces, ANR_TRACE_DATEINAME).writeBytes(anrTrace)
        File(traces, NATIVE_TOMBSTONE_DATEINAME).writeBytes(tombstone)

        val zipFile = exporter(FakeDiagnosticLogDao(emptyList()), traceVerzeichnis = traces)
            .createBundle(BundleKontext(typ = BundleTyp.ABSTURZ, ausloeser = "Test"))

        assertTrue("Budget darf hier ueberschritten sein", zipFile.length() > 10L * 1024 * 1024)
        ZipFile(zipFile).use { zip ->
            assertArrayEquals(anrTrace, zip.getInputStream(zip.getEntry("crash/anr_trace.txt")).readBytes())
            assertArrayEquals(tombstone, zip.getInputStream(zip.getEntry("crash/native_tombstone.pb")).readBytes())
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            assertTrue(manifest.contains("\"kuerzungsstufe\": 2"))
        }
        traces.deleteRecursively()
    }
}
