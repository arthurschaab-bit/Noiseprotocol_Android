package com.example.lrmprotokoll.report

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * Bugfix 24.09.2026 (docs/PROMPT_FIX_BERICHT_HIGHEND.md): ein gescheiterter High-End-Bericht
 * hinterliess bisher keine Spur im Diagnoseprotokoll (docs/BEFUNDE_P30_2026-09-23.md, Abschnitt 3).
 * Reporter ist bewusst der echte [CompositeDiagnosticsReporter] ohne Sinks - genau das Muster aus
 * DriveSyncCoordinatorTest.fehlgeschlagenerWavUploadWirdImDiagnoseprotokollGemeldet - statt eines
 * handgeschriebenen Fakes, weil das Interface hier keine zusaetzliche Test-Logik braucht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HighEndReportExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val reporter =
        CompositeDiagnosticsReporter(
            initialContext = DiagnosticContext(appVersion = "1.0", buildType = "debug"),
        )
    private val zone: ZoneId = ZoneId.systemDefault()
    private val datum: LocalDate = LocalDate.of(2026, 9, 20)
    private val config = ReportConfigEntity(gebietseinstufung = "WA")

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Legt eine Session mit [anzahl] Messwerten (Sekundenabstand ab [start]) sowie vollständige
     * Stammdaten für [datum] an und liefert den zugehörigen, bereits vorgeprüften [BerichtTag].
     */
    private fun tagMit(
        anzahl: Int,
        start: Long = datum.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L,
    ): BerichtTag =
        runBlocking {
            val sessionId =
                db.sessionDao().insert(
                    SessionEntity(
                        startedAt = start,
                        endedAt = start + anzahl * 1_000L,
                        deviceAddress = "AA:BB",
                        deviceName = "PCE-323",
                        weighting = "A",
                        timeWeighting = "FAST",
                    ),
                )
            db.measurementDao().insertAll(
                (0 until anzahl).map {
                    MeasurementEntity(
                        sessionId = sessionId,
                        timestamp = start + it * 1_000L,
                        levelDb = 55.0,
                        weighting = "A",
                        timeWeighting = "FAST",
                        flags = 0,
                    )
                },
            )
            db.stammdatenVerlaufDao().insert(
                StammdatenVerlaufEntity(
                    erstelltAm = start,
                    geraetHersteller = "PCE",
                    geraetTyp = "323",
                    geraetGenauigkeitsklasse = "2",
                    geraetSeriennummer = "SN1",
                    geraetKalibrierung = "kalibriert",
                    messort = "Musterort",
                    mikrofonposition = "Fenster",
                    mikrofonhoehe = "1m",
                    entfernungZurQuelle = "5m",
                    innenAussen = "Außen",
                    fensterzustand = "",
                    wetter = "trocken",
                    datenqualitaetHinweis = "",
                ),
            )
            ladeBerichtstage(db, BerichtZeitraum(datum, datum), zone).single()
        }

    /**
     * Für Test 4/5: ein Tag mit Messwerten in vier verschiedenen Stunden (mehr als
     * [HIGH_END_REPORT_ABSCHNITT_MILLIS] auseinander) und einem Sitzungswechsel nach der ersten
     * Stunde - genug für ≥ 3 Abschnitte mit Inhalt.
     */
    private fun tagMitMehrerenAbschnitten(zielDb: AppDatabase = db): BerichtTag =
        runBlocking {
            val tagStart = datum.atStartOfDay(zone).toInstant().toEpochMilli()
            val sessionA =
                zielDb.sessionDao().insert(
                    SessionEntity(
                        startedAt = tagStart,
                        endedAt = tagStart + 9 * 3_600_000L,
                        deviceAddress = "AA:BB",
                        deviceName = "PCE-323",
                        weighting = "A",
                        timeWeighting = "FAST",
                    ),
                )
            val sessionB =
                zielDb.sessionDao().insert(
                    SessionEntity(
                        startedAt = tagStart + 9 * 3_600_000L,
                        endedAt = tagStart + 24 * 3_600_000L,
                        deviceAddress = "AA:BB",
                        deviceName = "PCE-323",
                        weighting = "A",
                        timeWeighting = "FAST",
                    ),
                )
            val rows = mutableListOf<MeasurementEntity>()
            for (stunde in listOf(0L, 8L, 16L, 23L)) {
                val sessionId = if (stunde < 9L) sessionA else sessionB
                val basis = tagStart + stunde * 3_600_000L + 5 * 60_000L
                repeat(3) { i ->
                    rows +=
                        MeasurementEntity(
                            sessionId = sessionId,
                            timestamp = basis + i * 1_000L,
                            levelDb = 50.0 + i,
                            weighting = "A",
                            timeWeighting = "FAST",
                            flags = 0,
                        )
                }
            }
            zielDb.measurementDao().insertAll(rows)
            zielDb.stammdatenVerlaufDao().insert(
                StammdatenVerlaufEntity(
                    erstelltAm = tagStart,
                    geraetHersteller = "PCE",
                    geraetTyp = "323",
                    geraetGenauigkeitsklasse = "2",
                    geraetSeriennummer = "SN1",
                    geraetKalibrierung = "kalibriert",
                    messort = "Musterort",
                    mikrofonposition = "Fenster",
                    mikrofonhoehe = "1m",
                    entfernungZurQuelle = "5m",
                    innenAussen = "Außen",
                    fensterzustand = "",
                    wetter = "trocken",
                    datenqualitaetHinweis = "",
                ),
            )
            ladeBerichtstage(zielDb, BerichtZeitraum(datum, datum), zone).single()
        }

    /** Test 1 (PROMPT_FIX_BERICHT_HIGHEND.md Abschnitt 3): muss ohne die Änderung rot sein. */
    @Test
    fun runnerFehlerWirdAlsReportCreateFailedMitPhasePythonGemeldet() =
        runBlocking {
            val tag = tagMit(3)
            val export = HighEndReportExport(context, db, reporter)

            val ergebnis =
                export.generate(listOf(tag), config, emptyMap()) {
                    ChaquopyReportRunner.Ergebnis.Fehler("Simulierter Python-Fehler", RuntimeException("kaputt"))
                }

            assertTrue("$ergebnis", ergebnis is ChaquopyReportRunner.Ergebnis.Fehler)
            assertEquals(
                "Der Rückgabewert bleibt unverändert der Fehler des Runners",
                "Simulierter Python-Fehler",
                (ergebnis as ChaquopyReportRunner.Ergebnis.Fehler).nachricht,
            )
            val gemeldet = reporter.recentEvents().filter { it.code == DiagnosticCode.REPORT_CREATE_FAILED }
            assertEquals("Genau ein REPORT_CREATE_FAILED", 1, gemeldet.size)
            assertEquals("python", gemeldet.single().details["phase"])
        }

    /** Test 2 (PROMPT_FIX_BERICHT_HIGHEND.md Abschnitt 3): muss ohne die Änderung rot sein. */
    @Test
    fun exportfehlerWirdAlsReportCreateFailedMitPhaseExportGemeldet() =
        runBlocking {
            val tag = tagMit(3)
            // Blockiert das Anlegen des Handoff-Ordners: eine DATEI liegt dort, wo generate() per
            // mkdirs() ein Verzeichnis anlegen will (schreibgeschütztes Temp-Verzeichnis als
            // deterministisches, plattformunabhängiges Analogon).
            File(context.cacheDir, "report_handoff").writeText("blockiert")
            val export = HighEndReportExport(context, db, reporter)

            val ergebnis =
                export.generate(listOf(tag), config, emptyMap()) {
                    ChaquopyReportRunner.Ergebnis.Erfolg("sollte nicht erreicht werden")
                }

            assertTrue("$ergebnis", ergebnis is ChaquopyReportRunner.Ergebnis.Fehler)
            val gemeldet = reporter.recentEvents().filter { it.code == DiagnosticCode.REPORT_CREATE_FAILED }
            assertEquals("Genau ein REPORT_CREATE_FAILED", 1, gemeldet.size)
            assertEquals("export", gemeldet.single().details["phase"])
        }

    /**
     * Test 4 (PROMPT_FIX_BERICHT_HIGHEND.md Abschnitt 3): die abschnittsweise geschriebene CSV
     * muss byteweise identisch zur bisherigen sein. Referenz ist der alte Algorithmus, mit
     * derselben DAO-Methode einmal für den ganzen Tag statt mehrfach je Abschnitt nachgebildet.
     */
    @Test
    fun csvBleibtByteweiseGleichDerBisherigenAusgabe() =
        runBlocking {
            val tag = tagMitMehrerenAbschnitten()
            val alteZeilen = db.measurementDao().zwischen(tag.von, tag.bis)
            assertEquals(12, alteZeilen.size)
            val erwartet =
                buildString {
                    appendLine("timestampMillis,levelDb,flags,sessionId,weighting,timeWeighting")
                    for (row in alteZeilen) {
                        fun csv(value: String?) = "\"${value.orEmpty().replace("\"", "\"\"")}\""
                        appendLine(
                            "${row.timestamp},${row.levelDb},${row.flags},${row.sessionId}," +
                                "${csv(row.weighting)},${csv(row.timeWeighting)}",
                        )
                    }
                }

            var tatsaechlicheBytes: ByteArray? = null
            val export = HighEndReportExport(context, db, reporter)
            val ergebnis =
                export.generate(listOf(tag), config, emptyMap()) { json ->
                    val samplesPath =
                        JSONObject(json).getJSONArray("days").getJSONObject(0).getString("samplesPath")
                    tatsaechlicheBytes = File(samplesPath).readBytes()
                    ChaquopyReportRunner.Ergebnis.Fehler("Test bricht bewusst vor dem Python-Aufruf ab")
                }

            assertTrue("$ergebnis", ergebnis is ChaquopyReportRunner.Ergebnis.Fehler)
            assertArrayEquals(erwartet.toByteArray(Charsets.UTF_8), tatsaechlicheBytes)
        }

    /**
     * Test 5 (PROMPT_FIX_BERICHT_HIGHEND.md Abschnitt 3): muss ohne die Änderung rot sein.
     * Speichergrenze als Proxy - über Rooms eigenen `setQueryCallback` (kein Fake nötig): kein
     * Aufruf der Rohwerte-Abfrage darf mehr als einen Abschnitt umfassen.
     */
    @Test
    fun keinMesswertAbfrageaufrufUmfasstMehrAlsEinenAbschnitt() =
        runBlocking {
            val bindArgs = mutableListOf<List<Any?>>()
            val queryCallback =
                RoomDatabase.QueryCallback { sqlQuery, args ->
                    if (sqlQuery.startsWith("SELECT * FROM measurements WHERE timestamp")) {
                        bindArgs += args
                    }
                }
            val callbackDb =
                Room
                    .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryCallback(queryCallback, Executor { it.run() })
                    .build()
            try {
                val tag = tagMitMehrerenAbschnitten(callbackDb)
                val export = HighEndReportExport(context, callbackDb, reporter)

                export.generate(listOf(tag), config, emptyMap()) {
                    ChaquopyReportRunner.Ergebnis.Fehler("Test bricht bewusst vor dem Python-Aufruf ab")
                }

                assertTrue(
                    "Mindestens 3 Abschnitts-Abfragen erwartet, waren ${bindArgs.size}",
                    bindArgs.size >= 3,
                )
                for (args in bindArgs) {
                    val von = args[0] as Long
                    val bis = args[1] as Long
                    assertTrue(
                        "Abfrage $von..$bis umfasst mehr als einen Abschnitt ($HIGH_END_REPORT_ABSCHNITT_MILLIS ms)",
                        bis - von <= HIGH_END_REPORT_ABSCHNITT_MILLIS,
                    )
                }
            } finally {
                callbackDb.close()
            }
        }

    /** Test 7 (PROMPT_FIX_BERICHT_HIGHEND.md Abschnitt 3). */
    @Test
    fun erfolgHinterlaesstBreadcrumbMitTagenRohwertenUndDauer() =
        runBlocking {
            val tag = tagMit(5)
            val export = HighEndReportExport(context, db, reporter)

            val ergebnis =
                export.generate(listOf(tag), config, emptyMap()) { json ->
                    val outputPath = JSONObject(json).getString("outputPath")
                    File(outputPath).apply {
                        parentFile!!.mkdirs()
                        writeBytes(ByteArray(10))
                    }
                    ChaquopyReportRunner.Ergebnis.Erfolg(outputPath)
                }

            assertTrue("$ergebnis", ergebnis is ChaquopyReportRunner.Ergebnis.Erfolg)
            val erfolgsBreadcrumb =
                reporter.recentBreadcrumbs().last { it.message.startsWith("High-End-Bericht erzeugt:") }
            assertTrue(erfolgsBreadcrumb.message, erfolgsBreadcrumb.message.contains("1 Tage"))
            assertTrue(erfolgsBreadcrumb.message, erfolgsBreadcrumb.message.contains("5 Rohwerte"))
            assertTrue(erfolgsBreadcrumb.message, erfolgsBreadcrumb.message.contains(" ms, PDF "))
            assertTrue(erfolgsBreadcrumb.message, erfolgsBreadcrumb.message.endsWith(" KB"))
        }
}
