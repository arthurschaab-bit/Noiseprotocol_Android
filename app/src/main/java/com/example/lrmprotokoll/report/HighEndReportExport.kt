package com.example.lrmprotokoll.report

import android.content.Context
import androidx.room.withTransaction
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Größe eines Lese-/Schreibabschnitts beim CSV-Export eines Berichtstages (Bugfix 24.09.2026,
 * docs/PROMPT_FIX_BERICHT_HIGHEND.md Schritt 2): [HighEndReportExport] hält damit höchstens die
 * Messwerte einer Stunde gleichzeitig im Speicher statt aller Messwerte eines ganzen Tages.
 * `measurements` hatte auf dem Owner-Gerät 575.403 Zeilen; ein `OutOfMemoryError` beim Laden
 * eines ganzen Tages als `List<MeasurementEntity>` war belegt (docs/BEFUNDE_P30_2026-09-23.md,
 * Abschnitt 3).
 */
internal const val HIGH_END_REPORT_ABSCHNITT_MILLIS = 60 * 60 * 1000L

/** Privater Datei-Handoff. Nur Metadaten passieren die JNI-Grenze; CSVs werden immer entfernt. */
class HighEndReportExport(
    private val context: Context,
    private val db: AppDatabase,
    private val diagnosticsReporter: DiagnosticsReporter,
) {
    suspend fun generate(
        days: List<BerichtTag>,
        config: ReportConfigEntity,
        selectedIds: Map<LocalDate, Long>,
        runner: suspend (String) -> ChaquopyReportRunner.Ergebnis,
    ): ChaquopyReportRunner.Ergebnis = withContext(Dispatchers.IO) {
        val runId = UUID.randomUUID().toString()
        val temporary = File(context.cacheDir, "report_handoff/$runId")
        val output = File(context.filesDir, "reports/Schallbericht_$runId.pdf")
        var succeeded = false
        val start = System.currentTimeMillis()
        // Phase, in der ein Fehlschlag entsteht (Bugfix 24.09.2026, siehe REPORT_CREATE_FAILED
        // unten) - "export" ist der Ausgangswert, da schon das Anlegen der Ordner dazu gehoert.
        var phase = "export"
        var rawSampleCount = 0

        // Gemeinsame Details fuer beide REPORT_CREATE_FAILED-Aufrufe unten (Fehler vom Runner
        // bzw. eine Ausnahme) - eine Stelle statt zweimal derselben Map.
        fun reportDetails() =
            mapOf(
                "phase" to phase,
                "tage" to days.size,
                "rawSampleCount" to rawSampleCount,
                "dauerMs" to (System.currentTimeMillis() - start),
            )
        diagnosticsReporter.breadcrumb("Bericht", "High-End-Bericht gestartet: ${days.size} Tage")
        try {
            check(temporary.mkdirs()) { "Temporärer Berichtsordner konnte nicht angelegt werden." }
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) {
                "Berichtsordner konnte nicht angelegt werden."
            }
            // Ein konsistenter Snapshot verhindert Retention zwischen Vorprüfung und Export.
            // Rohwerte werden nur abschnittsweise (HIGH_END_REPORT_ABSCHNITT_MILLIS), nicht als
            // ganzer Tag gleichzeitig im Speicher gehalten (Bugfix 24.09.2026).
            val transaktionStart = System.currentTimeMillis()
            val parameter = db.withTransaction {
                phase = "vorpruefung"
                require(days.isNotEmpty()) { "Es wurde kein Berichtszeitraum gewählt." }
                val fresh = ladeBerichtstage(db, BerichtZeitraum(days.first().datum, days.last().datum))
                val error = retentionFehler(fresh) ?: bewertungsFehler(fresh, config) ?:
                    auswahlFehler(fresh, selectedIds) ?: areaSelectionError(config.gebietseinstufung)
                require(error == null) { error.orEmpty() }
                require(fresh.any { it.rohwerte > 0 }) { "Im gewählten Zeitraum liegen keine Rohdaten vor." }
                phase = "export"
                val json = JSONObject(vorlaeufigeBerichtsparameter(fresh, config, selectedIds, output.absolutePath))
                    .put("contractVersion", 2)
                    .put("timeZone", ZoneId.systemDefault().id)
                val jsonDays = json.getJSONArray("days")
                for ((index, day) in fresh.withIndex()) {
                    val file = File(temporary, "${day.datum}.csv")
                    val (tagesRohwerte, sessionIdsAusRohwerten) = schreibeTagesRohwerte(day, file)
                    rawSampleCount += tagesRohwerte
                    val sessions = JSONArray()
                    val photos = JSONArray()
                    // Einschließlich Session-IDs aus Rohwerten, auch bei unvollständigen Altdaten.
                    for (id in (day.sessionIds + sessionIdsAusRohwerten).distinct()) {
                        val session = db.sessionDao().byId(id)
                        sessions.put(JSONObject().put("id", id)
                            .put("sha256", session?.rohdatenPruefsumme ?: JSONObject.NULL)
                            .put("startedAt", session?.startedAt ?: JSONObject.NULL)
                            .put("endedAt", session?.endedAt ?: JSONObject.NULL))
                        for (photo in db.dokumentationsFotoDao().fuerSession(id)) {
                            val source = File(photo.dateiPfad).canonicalFile
                            val privateRoots = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null)).map { it.canonicalFile }
                            require(privateRoots.any { source.toPath().startsWith(it.toPath()) }) {
                                "Ein Dokumentationsfoto liegt außerhalb des App-Speichers."
                            }
                            val privatePhoto = File(temporary, "photo_${photo.id}.${source.extension.ifBlank { "jpg" }}")
                            if (source.isFile && !privatePhoto.exists()) source.copyTo(privatePhoto)
                            photos.put(JSONObject().put("id", photo.id).put("sessionId", id)
                                .put("path", privatePhoto.absolutePath).put("category", photo.kategorie)
                                .put("sha256", photo.pruefsumme ?: JSONObject.NULL)
                                .put("capturedAt", photo.aufgenommenAm)
                                .put("imported", photo.nachtraeglichHinzugefuegt)
                                .put("geometry", photo.geometrieTag ?: JSONObject.NULL)
                                .put("note", photo.notiz ?: JSONObject.NULL))
                        }
                    }
                    jsonDays.getJSONObject(index).put("samplesPath", file.absolutePath)
                        .put("rawSampleCount", tagesRohwerte).put("sessions", sessions).put("photos", photos)
                }
                json.toString()
            }
            val transaktionDauerMs = System.currentTimeMillis() - transaktionStart
            phase = "python"
            val result = runner(parameter)
            val endResult =
                when (result) {
                    is ChaquopyReportRunner.Ergebnis.Erfolg -> {
                        phase = "pdfpruefung"
                        check(File(result.pdfPfad).canonicalFile == output.canonicalFile && output.length() > 0) {
                            "Die Berichtserzeugung hat keine gültige PDF-Datei zurückgegeben."
                        }
                        succeeded = true
                        result
                    }
                    is ChaquopyReportRunner.Ergebnis.Fehler -> {
                        diagnosticsReporter.report(
                            code = DiagnosticCode.REPORT_CREATE_FAILED,
                            component = "HighEndReportExport",
                            operation = "generate",
                            severity = DiagnosticSeverity.WARN,
                            cause = result.ursache,
                            message = result.nachricht,
                            details = reportDetails(),
                        )
                        result
                    }
                }
            if (succeeded) {
                diagnosticsReporter.breadcrumb(
                    "Bericht",
                    "High-End-Bericht erzeugt: ${days.size} Tage, $rawSampleCount Rohwerte, " +
                        "$transaktionDauerMs ms, PDF ${output.length() / 1024} KB",
                )
            }
            endResult
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            // Owner-Vorgabe (PROMPT_FIX_BERICHT_HIGHEND.md Schritt 3, bestätigt 24.09.2026): NUR
            // hier gezielt gefangen, kein allgemeines catch (Throwable). Die großen Listen des
            // Exports sind an dieser Stelle nicht mehr erreichbar; ein vom Nutzer ausgelöster
            // Bericht soll die laufende Messung nicht mit in den Absturz reißen.
            val ergebnis =
                ChaquopyReportRunner.Ergebnis.Fehler(
                    "Bericht konnte nicht erzeugt werden: zu wenig Arbeitsspeicher. Bitte einen kürzeren Zeitraum wählen.",
                )
            diagnosticsReporter.report(
                code = DiagnosticCode.REPORT_CREATE_FAILED,
                component = "HighEndReportExport",
                operation = "generate",
                severity = DiagnosticSeverity.WARN,
                cause = oom,
                message = ergebnis.nachricht,
                details = reportDetails(),
            )
            ergebnis
        } catch (error: Exception) {
            val ergebnis =
                ChaquopyReportRunner.Ergebnis.Fehler(
                    "Bericht konnte nicht erzeugt werden: ${error.message ?: "Dateifehler"}", error,
                )
            diagnosticsReporter.report(
                code = DiagnosticCode.REPORT_CREATE_FAILED,
                component = "HighEndReportExport",
                operation = "generate",
                severity = DiagnosticSeverity.WARN,
                cause = error,
                message = ergebnis.nachricht,
                details = reportDetails(),
            )
            ergebnis
        } finally {
            temporary.deleteRecursively()
            if (!succeeded) output.delete()
        }
    }

    /**
     * Schreibt die Messwerte eines Tages abschnittsweise (siehe [HIGH_END_REPORT_ABSCHNITT_MILLIS])
     * direkt in [datei], statt sie als kompletten Tag in einer Liste zu halten (Bugfix 24.09.2026,
     * docs/PROMPT_FIX_BERICHT_HIGHEND.md Schritt 2). Liefert die Anzahl geschriebener Rohwerte und
     * die dabei gesehenen Session-IDs zurück; kein einzelner Aufruf von
     * [com.example.lrmprotokoll.data.MeasurementDao.zwischen] umfasst dabei mehr als einen
     * Abschnitt.
     */
    private suspend fun schreibeTagesRohwerte(
        day: BerichtTag,
        datei: File,
    ): Pair<Int, Set<Long>> {
        val sessionIds = mutableSetOf<Long>()
        var rohwerte = 0
        datei.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("timestampMillis,levelDb,flags,sessionId,weighting,timeWeighting")
            var abschnittsVon = day.von
            while (abschnittsVon < day.bis) {
                val abschnittsBis = minOf(abschnittsVon + HIGH_END_REPORT_ABSCHNITT_MILLIS, day.bis)
                val rows = db.measurementDao().zwischen(abschnittsVon, abschnittsBis)
                for (row in rows) {
                    writer.appendLine(csvZeile(row))
                    sessionIds += row.sessionId
                }
                rohwerte += rows.size
                abschnittsVon = abschnittsBis
            }
        }
        return rohwerte to sessionIds
    }
}

/** Eine Rohwert-Zeile im CSV-Format des Python-Vertrags (Spaltenreihenfolge siehe Header-Zeile). */
private fun csvZeile(row: MeasurementEntity): String {
    fun csv(value: String?) = "\"${value.orEmpty().replace("\"", "\"\"")}\""
    return "${row.timestamp},${row.levelDb},${row.flags},${row.sessionId},${csv(row.weighting)},${csv(row.timeWeighting)}"
}
