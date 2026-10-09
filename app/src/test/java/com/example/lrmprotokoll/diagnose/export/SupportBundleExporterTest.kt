package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.BreadcrumbRingFile
import com.example.lrmprotokoll.diagnose.LebenszyklusRingFile
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
import org.junit.Assert.assertFalse
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
        override suspend fun seiteRueckwaerts(vorId: Long, seitengroesse: Int): List<DiagnosticLogEntity> {
            if (schlaegtFehl) error("Simulierter DB-Fehler fuer den Fehlertoleranz-Test")
            abgefragteSeiten++
            return eintraege.filter { it.id < vorId }.sortedByDescending { it.id }.take(seitengroesse)
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
        lebenszyklusRingFile: LebenszyklusRingFile = container.lebenszyklusRingFile,
        traceVerzeichnis: File = File(context.filesDir, "process_exit_traces_test_${System.nanoTime()}"),
        // Default identisch zum Produktionsstandard in SupportBundleExporter selbst - bestehende
        // Aufrufer dieser Hilfsfunktion bleiben dadurch unveraendert (echter, Robolectric-
        // gestuetzter PackageManager-Weg); nur Tests, die den Parameter explizit setzen, weichen
        // davon ab (PROMPT_FIX_BUNDLE_INHALT.md Teil 2).
        berechtigungExistiertProvider: (String) -> Boolean = { name ->
            runCatching { context.packageManager.getPermissionInfo(name, 0) }.isSuccess
        },
    ) = SupportBundleExporter(
        context = context,
        reporter = reporter,
        diagnosticLogDao = dao,
        breadcrumbRingFile = ringFile,
        lebenszyklusRingFile = lebenszyklusRingFile,
        settingsManager = container.settingsManager,
        database = container.database,
        traceVerzeichnis = traceVerzeichnis,
        berechtigungExistiertProvider = berechtigungExistiertProvider,
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
            assertTrue(entries.contains("log/lebenszyklus.jsonl"))
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

    /**
     * Test 3 aus docs/PROMPT_FIX_LAUFZEITZUSTAND_ABSTURZ.md Abschnitt 3: enthaelt der
     * ACRA-Report (hier bereits als [BundleKontext.laufzeitzustandJson] durchgereicht, siehe
     * [com.example.lrmprotokoll.diagnose.acra.SupportOutboxReportSender]) den Schluessel, landet
     * er als eigene Datei im Bundle.
     */
    @Test
    fun crashBundleEnthaeltLaufzeitzustandWennImKontextVorhanden() =
        runTest {
            val dao = FakeDiagnosticLogDao(emptyList())
            val kontext =
                BundleKontext(
                    typ = BundleTyp.ABSTURZ,
                    ausloeser = "ACRA",
                    laufzeitzustandJson = "{\"aufnahmeAktiv\":true,\"heapMaxBytes\":123456,\"bleVerbindungszustand\":\"STREAMING\"}",
                )

            val zipFile = exporter(dao).createBundle(kontext)

            ZipFile(zipFile).use { zip ->
                assertNotNull(zip.getEntry("crash/laufzeitzustand_beim_absturz.json"))
                val inhalt = zip.getInputStream(zip.getEntry("crash/laufzeitzustand_beim_absturz.json")).bufferedReader().readText()
                assertTrue(inhalt.contains("aufnahmeAktiv"))
                assertTrue(inhalt.contains("STREAMING"))
            }
        }

    /** Gegenprobe zu Test 3: ohne den Schluessel entsteht die Datei nicht. */
    @Test
    fun crashBundleOhneLaufzeitzustandHatKeineDieserDatei() =
        runTest {
            val dao = FakeDiagnosticLogDao(emptyList())
            val kontext = BundleKontext(typ = BundleTyp.ABSTURZ, ausloeser = "ACRA")

            val zipFile = exporter(dao).createBundle(kontext)

            ZipFile(zipFile).use { zip ->
                assertTrue(zip.getEntry("crash/laufzeitzustand_beim_absturz.json") == null)
            }
        }

    /**
     * Schritt 2 des Auftrags: "Er laeuft durch den DiagnosticRedactor, wie crash/acra_report.json."
     * Vorbild ist [crashBundleRedigiertPiiImAcraReportJson].
     */
    @Test
    fun crashBundleRedigiertPiiImLaufzeitzustand() =
        runTest {
            val dao = FakeDiagnosticLogDao(emptyList())
            val kontext =
                BundleKontext(
                    typ = BundleTyp.ABSTURZ,
                    ausloeser = "ACRA",
                    laufzeitzustandJson = "{\"hinweis\":\"Geraet AA:BB:CC:DD:EE:FF fuer user@example.com\"}",
                )

            val zipFile = exporter(dao).createBundle(kontext)

            ZipFile(zipFile).use { zip ->
                val inhalt = zip.getInputStream(zip.getEntry("crash/laufzeitzustand_beim_absturz.json")).bufferedReader().readText()
                assertTrue(inhalt.contains("AA:BB:CC:XX:XX:XX"))
                assertTrue(inhalt.contains("[REDACTED_EMAIL]"))
                assertTrue(!inhalt.contains("user@example.com"))
            }
        }

    /**
     * Test 4 aus docs/PROMPT_FIX_LAUFZEITZUSTAND_ABSTURZ.md Abschnitt 3: state/runtime.json muss
     * als "danach" gebaut erkennbar sein, damit niemand es mit dem Absturzmoment verwechselt
     * (Befund C, docs/BEFUNDE_P30_2026-09-23.md Abschnitt 3a).
     */
    @Test
    fun runtimeJsonMarkiertZeitpunktAlsBeiBundleErstellung() =
        runTest {
            val zipFile =
                exporter(FakeDiagnosticLogDao(emptyList()))
                    .createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

            ZipFile(zipFile).use { zip ->
                val runtimeJson = zip.getInputStream(zip.getEntry("state/runtime.json")).bufferedReader().readText()
                assertTrue(runtimeJson.contains("\"erfasst\": \"bei Bundle-Erstellung\""))
            }
        }

    /**
     * Geraetefund (BEFUNDE_P30_2026-09-23.md Abschnitt 4, PROMPT_FIX_BUNDLE_INHALT.md Teil 2):
     * Berechtigungen, die es auf dem Geraet gar nicht gibt (z. B. POST_NOTIFICATIONS auf API 29
     * - erst API 33+), standen bislang als "false" ("verweigert") unter "berechtigungen" - beim
     * Gerätetest auf dem Huawei P30 irrefuehrend. Fake-Pruefung statt echtem PackageManager
     * (siehe [berechtigungExistiertProviderEchterWegUnterRobolectricKenntFrameworkBerechtigungenNicht]
     * fuer den Grund).
     */
    @Test
    fun runtimeJsonTrenntNichtVorhandeneBerechtigungenVonVerweigerten() = runTest {
        val zipFile = exporter(
            FakeDiagnosticLogDao(emptyList()),
            // RECORD_AUDIO "existiert" (Fake), POST_NOTIFICATIONS nicht - unabhaengig davon, ob
            // es tatsaechlich gewaehrt ist.
            berechtigungExistiertProvider = { name -> name != "android.permission.POST_NOTIFICATIONS" },
        ).createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val runtimeJson = org.json.JSONObject(zip.getInputStream(zip.getEntry("state/runtime.json")).bufferedReader().readText())
            val berechtigungen = runtimeJson.getJSONObject("berechtigungen")
            val nichtVorhanden = (0 until runtimeJson.getJSONArray("berechtigungenNichtVorhanden").length())
                .map { runtimeJson.getJSONArray("berechtigungenNichtVorhanden").getString(it) }

            assertTrue(
                "Eine vorhandene Berechtigung muss weiterhin true/false unter berechtigungen stehen",
                berechtigungen.has("android.permission.RECORD_AUDIO"),
            )
            assertTrue(
                "Eine nicht vorhandene Berechtigung darf NICHT unter berechtigungen stehen (dort laese sie sich als 'verweigert')",
                !berechtigungen.has("android.permission.POST_NOTIFICATIONS"),
            )
            assertTrue(
                "Eine nicht vorhandene Berechtigung gehoert nach berechtigungenNichtVorhanden",
                nichtVorhanden.contains("android.permission.POST_NOTIFICATIONS"),
            )
            assertTrue(
                "Eine vorhandene Berechtigung darf nicht in berechtigungenNichtVorhanden auftauchen",
                !nichtVorhanden.contains("android.permission.RECORD_AUDIO"),
            )
        }
    }

    /**
     * Zweite Haelfte von Teil 2 ("wenn moeglich zusaetzlich @Config(sdk = [29]) gegen den echten
     * Weg"): dieser Test laeuft gegen den echten `PackageManager`-Aufruf (kein Fake), aber er
     * beweist NICHT, dass POST_NOTIFICATIONS auf einem echten API-29-Geraet fehlt und RECORD_AUDIO
     * vorhanden ist - er haelt stattdessen fest, WARUM die Pruefung ueberhaupt hinter einer
     * injizierbaren Funktion versteckt ist: Robolectrics PackageManager kennt unter
     * `@Config(sdk = [29])` KEINE vom Android-Framework definierte Berechtigung, auch nicht
     * RECORD_AUDIO/CAMERA, die auf einem echten Geraet seit jeher existieren
     * (`context.packageManager.getPermissionInfo(name, 0)` wirft fuer jede hier getestete
     * Framework-Berechtigung `NameNotFoundException`, empirisch geprueft: siehe PR-Beschreibung).
     * Erkannt wird nur die eine App-eigene Berechtigung, die im gemergten Manifest selbst per
     * `<permission>` DEFINIERT ist (`com.example.lrmprotokoll.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
     * von AGP eingefuegt) - deshalb hier keine pauschale "berechtigungen ist leer"-Behauptung,
     * sondern gezielt gegen echte Framework-Berechtigungen geprueft. Ohne den Fake in
     * [runtimeJsonTrenntNichtVorhandeneBerechtigungenVonVerweigerten] waere also jede echte
     * Framework-Berechtigung faelschlich als "gibt es auf diesem Geraet nicht" markiert - genau
     * der Fall, vor dem der Auftrag warnt ("Robolectric kennt Plattform-Berechtigungen womoeglich
     * nicht"). Sollte ein spaeteres Robolectric-Update das aendern, faellt dieser Test auf und
     * macht die veraltete Annahme sichtbar.
     */
    @Test
    @Config(sdk = [29])
    fun berechtigungExistiertProviderEchterWegUnterRobolectricKenntFrameworkBerechtigungenNicht() = runTest {
        val zipFile = exporter(FakeDiagnosticLogDao(emptyList()))
            .createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val runtimeJson = org.json.JSONObject(zip.getInputStream(zip.getEntry("state/runtime.json")).bufferedReader().readText())
            val berechtigungen = runtimeJson.getJSONObject("berechtigungen")
            val nichtVorhanden = (0 until runtimeJson.getJSONArray("berechtigungenNichtVorhanden").length())
                .map { runtimeJson.getJSONArray("berechtigungenNichtVorhanden").getString(it) }

            // Keine vom Android-Framework definierte Berechtigung wird unter Robolectric/sdk=29
            // erkannt - sie landen ausnahmslos in berechtigungenNichtVorhanden statt faelschlich
            // unter berechtigungen mit "false".
            assertTrue(nichtVorhanden.contains("android.permission.RECORD_AUDIO"))
            assertTrue(nichtVorhanden.contains("android.permission.POST_NOTIFICATIONS"))
            assertTrue(nichtVorhanden.contains("android.permission.CAMERA"))
            assertTrue(!berechtigungen.has("android.permission.RECORD_AUDIO"))
            assertTrue(!berechtigungen.has("android.permission.POST_NOTIFICATIONS"))
            assertTrue(!berechtigungen.has("android.permission.CAMERA"))
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

    /**
     * Geraetefund (BEFUNDE_P30_2026-09-23.md Abschnitt 4, PROMPT_FIX_BUNDLE_INHALT.md Teil 1):
     * "google_account_name" landete im Klartext in state/settings.json, obwohl die gepaarte
     * "google_account_email" bereits geschwaerzt wurde. Prueft den vollen Weg
     * SettingsManager -> unverschluesselteEinstellungenSnapshot() -> DiagnosticRedactor ->
     * state/settings.json im fertigen Bundle - nicht nur den Redactor isoliert.
     */
    @Test
    fun settingsJsonSchwaertGoogleKontoAnzeigenamenAberNichtUnverdaechtigeSchluessel() = runTest {
        container.settingsManager.googleAccountName = "Max Mustermann"
        container.settingsManager.googleAccountEmail = "max.mustermann@example.com"
        container.settingsManager.driveFolderName = "Laermprotokolle 2026"

        val zipFile = exporter(FakeDiagnosticLogDao(emptyList()))
            .createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val settingsJson = zip.getInputStream(zip.getEntry("state/settings.json")).bufferedReader().readText()
            assertTrue(!settingsJson.contains("Max Mustermann"))
            assertTrue(settingsJson.contains("\"google_account_name\": \"[REDACTED]\""))
            assertTrue(!settingsJson.contains("max.mustermann@example.com"))
            assertTrue(settingsJson.contains("\"google_account_email\": \"[REDACTED_EMAIL]\""))
            // Unverdaechtiger Schluessel bleibt unveraendert - der neue SENSITIVE_KEYS-Eintrag
            // ("account_name") darf nicht breiter matchen als noetig.
            assertTrue(settingsJson.contains("\"drive_folder_name\": \"Laermprotokolle 2026\""))
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
            val idList = zeilen.map { org.json.JSONObject(it).getLong("id") }
            assertEquals(1234, idList.toSet().size)
            // Neueste zuerst (Befund 5 aus docs/BEFUNDE_SUPPORT_BUNDLES_2026-10-02.md)
            assertEquals(1234L, idList.first())
            assertEquals(1L, idList.last())
        }
        assertTrue("Mehr als eine Seite muss abgefragt worden sein", dao.abgefragteSeiten >= 3)
    }

    @Test
    fun neuesteEintraegeWerdenBeiBudgetUeberschreitungExportiert() = runTest {
        // 20.000 Eintraege - sprengt das 1-MB-Budget eines periodischen Bundles bei Weitem (~2.4 MB vs 1 MB).
        val eintraege = (1..20_000L).map { logEintrag(it, "Diagnosemeldung $it mit langem Detailtext fuer realistisches Bytevolumen") }
        val dao = FakeDiagnosticLogDao(eintraege)

        val zipFile = exporter(dao).createBundle(BundleKontext(typ = BundleTyp.PERIODISCH, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val zeilen = zip.getInputStream(zip.getEntry("log/events.jsonl")).bufferedReader().readLines()
                .filter { it.isNotBlank() }
            assertTrue("Es duerfen nicht alle 20.000 Eintraege im Budget Platz finden", zeilen.size < 20_000)
            assertTrue("Mindestens eine Seite muss exportiert worden sein", zeilen.size >= 500)
            val ersteId = org.json.JSONObject(zeilen.first()).getLong("id")
            val letzteId = org.json.JSONObject(zeilen.last()).getLong("id")
            assertEquals("Der allererste exportierte Eintrag muss der neueste (20.000) sein", 20_000L, ersteId)
            assertTrue("Die aelteste ID in der gekuerzten Liste muss deutlich groesser als 1 sein", letzteId > 1L)
        }
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
            assertNotNull("log/lebenszyklus.jsonl muss auch bei Kuerzungsstufe 2 enthalten sein", zip.getEntry("log/lebenszyklus.jsonl"))
        }
        traces.deleteRecursively()
    }

    /**
     * Test 3 aus docs/PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md Abschnitt 3:
     * Das Bundle enthaelt log/lebenszyklus.jsonl auch bei Kuerzungsstufe 2.
     */
    @Test
    fun bundleEnthaeltLebenszyklusJsonlAuchBeiKuerzungsstufe2() = runTest {
        val zufall = java.util.Random(42)
        val anrTrace = ByteArray(4 * 1024 * 1024).also { zufall.nextBytes(it) }
        val tombstone = ByteArray(8 * 1024 * 1024).also { zufall.nextBytes(it) }
        val traces = File(context.filesDir, "process_exit_traces_test_${System.nanoTime()}").apply { mkdirs() }
        File(traces, ANR_TRACE_DATEINAME).writeBytes(anrTrace)
        File(traces, NATIVE_TOMBSTONE_DATEINAME).writeBytes(tombstone)

        val ringVerzeichnis = File(context.cacheDir, "lebens_test_${System.nanoTime()}").apply { mkdirs() }
        val lebenszyklusRing = LebenszyklusRingFile(ringVerzeichnis)
        lebenszyklusRing.protokolliere("Prozessstart", mapOf("pid" to 1234))
        lebenszyklusRing.wartenBisFertig()

        val exporter = SupportBundleExporter(
            context = context,
            reporter = CompositeDiagnosticsReporter(sinks = emptyList()),
            diagnosticLogDao = FakeDiagnosticLogDao(emptyList()),
            breadcrumbRingFile = BreadcrumbRingFile(File(context.cacheDir, "ring_${System.nanoTime()}").apply { mkdirs() }),
            lebenszyklusRingFile = lebenszyklusRing,
            settingsManager = container.settingsManager,
            database = container.database,
            traceVerzeichnis = traces,
        )

        val zipFile = exporter.createBundle(BundleKontext(typ = BundleTyp.ABSTURZ, ausloeser = "Test"))

        ZipFile(zipFile).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            assertTrue("Kuerzungsstufe 2 muss erreicht sein", manifest.contains("\"kuerzungsstufe\": 2"))
            val eintrag = zip.getEntry("log/lebenszyklus.jsonl")
            assertNotNull("log/lebenszyklus.jsonl muss bei Kuerzungsstufe 2 enthalten sein", eintrag)
            val inhalt = zip.getInputStream(eintrag).bufferedReader().readText()
            assertTrue(inhalt.contains("Prozessstart"))
        }
        traces.deleteRecursively()
        ringVerzeichnis.deleteRecursively()
    }

    /**
     * PROMPT_UNTERSUCHUNG_HINTERGRUNDJOBS_DRIVE.md Teil 1 Anforderung 1:
     * runtime.json enthaelt fuer jede geplante eindeutige Arbeit Name und Zustand.
     */
    @Test
    fun runtimeJsonEnthaeltWorkManagerArbeitenMitZustand() = runTest {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        try {
            val anfrage = androidx.work.OneTimeWorkRequestBuilder<androidx.work.Worker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                        .build(),
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "drive_sync_immediate",
                androidx.work.ExistingWorkPolicy.KEEP,
                anfrage,
            )

            val zipFile = exporter(FakeDiagnosticLogDao(emptyList())).createBundle(
                BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"),
            )

            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("state/runtime.json")
                assertNotNull("state/runtime.json muss existieren", entry)
                val json = org.json.JSONObject(zip.getInputStream(entry).bufferedReader().readText())
                assertTrue("runtime.json muss hintergrundJobs enthalten", json.has("hintergrundJobs"))
                val jobs = json.getJSONArray("hintergrundJobs")
                assertTrue("Mindestens ein Job muss enthalten sein", jobs.length() > 0)
                var gefunden = false
                for (i in 0 until jobs.length()) {
                    val job = jobs.getJSONObject(i)
                    if (job.getString("name") == "drive_sync_immediate") {
                        gefunden = true
                        assertTrue("state muss vorhanden sein", job.has("state"))
                        assertTrue("runAttemptCount muss vorhanden sein", job.has("runAttemptCount"))
                        // Stoppgruende gibt es erst ab Android 12 - darunter bleibt das Feld weg.
                        assertEquals(
                            "stopReason nur ab Android 12",
                            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                            job.has("stopReason"),
                        )
                        assertEquals("UNMETERED", job.getString("netztyp"))
                    }
                }
                assertTrue("drive_sync_immediate muss in hintergrundJobs gefunden werden", gefunden)
            }
        } finally {
            androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    /**
     * Review-Befund zu PR #264: Dynamisch benannte Arbeiten (drive_sync_retry_*, video-mux-*)
     * standen in keiner festen Namensliste und fehlten deshalb im Bundle.
     */
    @Test
    fun runtimeJsonEnthaeltAuchDynamischBenannteArbeitenGenauEinmal() =
        runTest {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            try {
                val workManager = androidx.work.WorkManager.getInstance(context)

                // Unerfuellte Netzbedingung haelt beide Arbeiten auf ENQUEUED - ohne sie liefe der
                // Test-WorkManager sie sofort (und scheiterte, Worker ist abstrakt).
                val nurWlan = androidx.work.Constraints(requiredNetworkType = androidx.work.NetworkType.UNMETERED)
                val keep = androidx.work.ExistingWorkPolicy.KEEP
                for (name in listOf("drive_sync_retry_WAV_42", "drive_sync_immediate")) {
                    val anfrage =
                        androidx.work
                            .OneTimeWorkRequestBuilder<androidx.work.Worker>()
                            .setConstraints(nurWlan)
                            .build()
                    workManager.enqueueUniqueWork(name, keep, anfrage)
                }

                val bundleExporter = exporter(FakeDiagnosticLogDao(emptyList()))
                val zipFile = bundleExporter.createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))

                ZipFile(zipFile).use { zip ->
                    val text = zip.getInputStream(zip.getEntry("state/runtime.json")).bufferedReader().readText()
                    val json = org.json.JSONObject(text)
                    val jobs = json.getJSONArray("hintergrundJobs")
                    val namen = List(jobs.length()) { jobs.getJSONObject(it).getString("name") }
                    assertEquals(
                        "Bekannte Arbeit unter ihrem Namen, dynamische unter der Worker-Klasse, keine doppelt",
                        listOf("Worker", "drive_sync_immediate"),
                        namen.sorted(),
                    )
                    assertFalse("Doppelter Schluessel 'arbeiten' entfaellt", json.has("arbeiten"))
                }
            } finally {
                WorkManagerTestInitHelper.closeWorkDatabase()
            }
        }
}
