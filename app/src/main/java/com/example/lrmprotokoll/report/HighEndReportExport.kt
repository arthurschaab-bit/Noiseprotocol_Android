package com.example.lrmprotokoll.report

import android.content.Context
import androidx.room.withTransaction
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.ReportConfigEntity
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Privater Datei-Handoff. Nur Metadaten passieren die JNI-Grenze; CSVs werden immer entfernt. */
class HighEndReportExport(private val context: Context, private val db: AppDatabase) {
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
        try {
            check(temporary.mkdirs()) { "Temporärer Berichtsordner konnte nicht angelegt werden." }
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) {
                "Berichtsordner konnte nicht angelegt werden."
            }
            // Ein konsistenter Snapshot verhindert Retention zwischen Vorprüfung und Export.
            // Rohwerte werden nur für einen Tag gleichzeitig im Speicher gehalten.
            val parameter = db.withTransaction {
                require(days.isNotEmpty()) { "Es wurde kein Berichtszeitraum gewählt." }
                val fresh = ladeBerichtstage(db, BerichtZeitraum(days.first().datum, days.last().datum))
                val error = retentionFehler(fresh) ?: bewertungsFehler(fresh, config) ?:
                    auswahlFehler(fresh, selectedIds) ?: areaSelectionError(config.gebietseinstufung)
                require(error == null) { error.orEmpty() }
                require(fresh.any { it.rohwerte > 0 }) { "Im gewählten Zeitraum liegen keine Rohdaten vor." }
                val json = JSONObject(vorlaeufigeBerichtsparameter(fresh, config, selectedIds, output.absolutePath))
                    .put("contractVersion", 2)
                    .put("timeZone", ZoneId.systemDefault().id)
                val jsonDays = json.getJSONArray("days")
                for ((index, day) in fresh.withIndex()) {
                    val file = File(temporary, "${day.datum}.csv")
                    val rows = db.measurementDao().zwischen(day.von, day.bis)
                    file.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.appendLine("timestampMillis,levelDb,flags,sessionId,weighting,timeWeighting")
                        for (row in rows) {
                            fun csv(value: String?) = "\"${value.orEmpty().replace("\"", "\"\"")}\""
                            writer.appendLine("${row.timestamp},${row.levelDb},${row.flags},${row.sessionId},${csv(row.weighting)},${csv(row.timeWeighting)}")
                        }
                    }
                    val sessions = JSONArray()
                    val photos = JSONArray()
                    // Einschließlich Session-IDs aus Rohwerten, auch bei unvollständigen Altdaten.
                    for (id in (day.sessionIds + rows.map { it.sessionId }).distinct()) {
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
                        .put("rawSampleCount", rows.size).put("sessions", sessions).put("photos", photos)
                }
                json.toString()
            }
            val result = runner(parameter)
            if (result is ChaquopyReportRunner.Ergebnis.Erfolg) {
                check(File(result.pdfPfad).canonicalFile == output.canonicalFile && output.length() > 0) {
                    "Die Berichtserzeugung hat keine gültige PDF-Datei zurückgegeben."
                }
                succeeded = true
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ChaquopyReportRunner.Ergebnis.Fehler(
                "Bericht konnte nicht erzeugt werden: ${error.message ?: "Dateifehler"}", error,
            )
        } finally {
            temporary.deleteRecursively()
            if (!succeeded) output.delete()
        }
    }
}
