package com.example.lrmprotokoll.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.data.SessionEntity
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Echter nativer CPython-/Matplotlib-Pfad, einschließlich Room-Handoff und Android-PDF-Leser. */
@RunWith(AndroidJUnit4::class)
class ChaquopyReportRunnerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun roomExportErzeugtLesbareTeilbarePdfUndEntferntTemporaereDateien() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var output: File? = null
        var unexpectedOutput: File? = null
        try {
            val date = LocalDate.of(2026, 9, 12)
            val start = date.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val id = db.sessionDao().insert(SessionEntity(
                startedAt = start, endedAt = start + 120_000, deviceAddress = "TEST",
                deviceName = "PCE-Test", weighting = "A", timeWeighting = "FAST",
                rohdatenPruefsumme = "a".repeat(64),
            ))
            db.measurementDao().insertAll((0 until 240).map {
                MeasurementEntity(sessionId = id, timestamp = start + it * 500, levelDb = 60.0,
                    weighting = "A", timeWeighting = "FAST", flags = 0)
            })
            val days = ladeBerichtstage(db, BerichtZeitraum(date, date))
            val config = ReportConfigEntity(gebietseinstufung = "MI")
            val runner = ChaquopyReportRunner(context)
            var temporary: File? = null
            val result = HighEndReportExport(context, db).generate(days, config, emptyMap()) { json ->
                val parameter = JSONObject(json)
                val day = parameter.getJSONArray("days").getJSONObject(0)
                assertEquals(240, day.getInt("rawSampleCount"))
                assertFalse(day.has("samples"))
                val csv = File(day.getString("samplesPath"))
                temporary = csv.parentFile
                assertEquals(241, csv.readLines().size)
                assertEquals("a".repeat(64), day.getJSONArray("sessions").getJSONObject(0).getString("sha256"))
                runner.erzeugeBericht(json)
            }
            assertTrue("Tatsächliches Chaquopy-Ergebnis: $result", result is ChaquopyReportRunner.Ergebnis.Erfolg)
            output = File((result as ChaquopyReportRunner.Ergebnis.Erfolg).pdfPfad)
            assertTrue(output!!.length() > 1000)
            assertFalse(temporary!!.exists())
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertTrue(pdf.pageCount >= 10)
                pdf.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(700, 525, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.recycle()
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output!!)
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val signature = ByteArray(5)
                assertEquals(5, stream.read(signature))
                assertEquals("%PDF-", String(signature, Charsets.US_ASCII))
            }
            // Fehler nach erfolgreichem Handoff: Cleanup ist auch dann zwingend.
            val failed = HighEndReportExport(context, db).generate(days, config, emptyMap()) { json ->
                temporary = File(JSONObject(json).getJSONArray("days").getJSONObject(0).getString("samplesPath")).parentFile
                throw IllegalStateException("Absichtlicher Testfehler")
            }
            assertTrue(failed is ChaquopyReportRunner.Ergebnis.Fehler)
            assertFalse(temporary!!.exists())

            // Nur die für diesen Lauf erzeugte Zieldatei darf als Erfolg gelten.
            val unexpected = HighEndReportExport(context, db).generate(days, config, emptyMap()) { json ->
                temporary = File(JSONObject(json).getJSONArray("days").getJSONObject(0).getString("samplesPath")).parentFile
                unexpectedOutput = File(context.filesDir, "reports/unexpected-output.pdf").apply {
                    parentFile!!.mkdirs()
                    writeBytes("%PDF-unexpected".toByteArray())
                }
                ChaquopyReportRunner.Ergebnis.Erfolg(unexpectedOutput!!.absolutePath)
            }
            assertTrue(unexpected is ChaquopyReportRunner.Ergebnis.Fehler)
            assertFalse(temporary!!.exists())
        } finally {
            output?.delete()
            unexpectedOutput?.delete()
            db.close()
        }
    }

    @Test
    fun kaputtesJsonUnbekanntesGebietUndFehlendeDateiSindVerstaendlich() = runBlocking {
        val runner = ChaquopyReportRunner(context)
        fun checkError(result: ChaquopyReportRunner.Ergebnis, expected: String) {
            assertTrue("$result", result is ChaquopyReportRunner.Ergebnis.Fehler)
            val message = (result as ChaquopyReportRunner.Ergebnis.Fehler).nachricht
            assertTrue(message, message.contains(expected))
            assertFalse(message, message.contains("Traceback"))
            assertFalse(message, message.contains("KeyError"))
        }
        checkError(runner.erzeugeBericht("{defekt"), "JSON")
        val output = File(context.filesDir, "reports/error-test.pdf")
        output.parentFile!!.mkdirs()
        val parameter = JSONObject()
            .put("contractVersion", 2).put("timeZone", "Europe/Berlin")
            .put("outputPath", output.absolutePath).put("unconfirmedWeightingOverride", false)
            .put("reportConfig", JSONObject()
                .put("gebietseinstufung", "UNBEKANNT")
                .put("tierSchwelleVollmessungProzent", 90).put("tierSchwelleTeilerfassungProzent", 70)
                .put("schaetzpegelTeilerfassungDb", 50).put("geraeteUnsicherheitDb", 1.4)
                .put("konservativFensterStartStunde", 15).put("konservativFensterEndeStunde", 19)
                .put("erzwingeBerichtOhneBestaetigteBewertung", false))
            .put("days", JSONArray().put(JSONObject().put("date", "2026-09-12")
                .put("rawSampleCount", 1).put("samplesPath", File(
                    context.cacheDir,
                    "report_handoff/error-test/missing-raw.csv",
                ).apply { parentFile!!.mkdirs() }.absolutePath)))
        checkError(runner.erzeugeBericht(parameter.toString()), "Unbekannte Gebietseinstufung")
        parameter.getJSONObject("reportConfig").put("gebietseinstufung", "WA")
        checkError(runner.erzeugeBericht(parameter.toString()), "Rohdaten-Datei")
        assertFalse(output.exists())
    }
}
