package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.example.lrmprotokoll.BuildConfig
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.DiagnosticBreadcrumb
import com.example.lrmprotokoll.diagnose.DiagnosticRedactor
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Erzeugt Support-Bundles (ZIP-Archive) fuer Diagnose und Support-Faelle (Konzept Abschnitt 7).
 *
 * Enthaelt:
 * - summary.json: Aggregierte Kennzahlen und Fehleruebersicht
 * - events.jsonl: Gespeicherte Diagnose-Events (DSGVO-bereinigt)
 * - breadcrumbs.jsonl: Zuletzt aufgelaufene Breadcrumbs
 * - device.json: Bereinigte Geraete- und Betriebssystem-Metadaten
 * - checksums.sha256: Pruefsummen zur Integritaetspruefung
 */
class SupportBundleExporter(
    private val context: Context,
    private val reporter: DiagnosticsReporter,
) {

    fun createBundle(diagnosticLogs: List<DiagnosticLogEntity>): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val bundleDir = File(baseDir, "support_bundles").apply { mkdirs() }
        // Alte Bundles aufraeumen
        bundleDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".zip")) it.delete() }

        val timestamp = System.currentTimeMillis()
        val zipFile = File(bundleDir, "support_bundle_$timestamp.zip")

        val breadcrumbs = reporter.recentBreadcrumbs()

        val summaryContent = buildSummaryJson(diagnosticLogs, breadcrumbs)
        val eventsContent = buildEventsJsonl(diagnosticLogs)
        val breadcrumbsContent = buildBreadcrumbsJsonl(breadcrumbs)
        val deviceContent = buildDeviceJson()

        val fileEntries = mutableMapOf<String, ByteArray>()
        fileEntries["summary.json"] = summaryContent.toByteArray(StandardCharsets.UTF_8)
        fileEntries["events.jsonl"] = eventsContent.toByteArray(StandardCharsets.UTF_8)
        fileEntries["breadcrumbs.jsonl"] = breadcrumbsContent.toByteArray(StandardCharsets.UTF_8)
        fileEntries["device.json"] = deviceContent.toByteArray(StandardCharsets.UTF_8)

        val checksumsContent = buildChecksums(fileEntries)
        fileEntries["checksums.sha256"] = checksumsContent.toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((entryName, data) in fileEntries) {
                val entry = ZipEntry(entryName)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }

        return zipFile
    }

    fun createShareIntent(zipFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, zipFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Lärmprotokoll Support-Bundle (${zipFile.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildSummaryJson(
        logs: List<DiagnosticLogEntity>,
        breadcrumbs: List<DiagnosticBreadcrumb>,
    ): String {
        val json = JSONObject()
        json.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        json.put("appVersion", BuildConfig.VERSION_NAME)
        json.put("buildType", BuildConfig.BUILD_TYPE)
        json.put("totalDiagnosticLogs", logs.size)
        json.put("totalBreadcrumbs", breadcrumbs.size)

        val codeCounts = JSONObject()
        logs.groupingBy { log -> log.message.takeWhile { char -> char != ':' && char != ' ' } }
            .eachCount()
            .forEach { (code, count) -> codeCounts.put(code, count) }
        json.put("eventCounts", codeCounts)

        return json.toString(2)
    }

    private fun buildEventsJsonl(logs: List<DiagnosticLogEntity>): String {
        val sb = StringBuilder()
        for (log in logs) {
            val json = JSONObject()
            json.put("id", log.id)
            json.put("timestamp", log.timestamp)
            json.put("isoTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(log.timestamp)))
            json.put("message", DiagnosticRedactor.redactString(log.message))
            sb.append(json.toString()).append("\n")
        }
        return sb.toString()
    }

    private fun buildBreadcrumbsJsonl(breadcrumbs: List<DiagnosticBreadcrumb>): String {
        val sb = StringBuilder()
        for (bc in breadcrumbs) {
            val json = JSONObject()
            json.put("timestamp", bc.timestamp.toEpochMilli())
            json.put("isoTime", DateTimeFormatter.ISO_INSTANT.format(bc.timestamp))
            json.put("category", bc.category)
            json.put("message", DiagnosticRedactor.redactString(bc.message))
            json.put("level", bc.level.name)
            val redactedData = DiagnosticRedactor.redactMap(bc.data)
            val dataObj = JSONObject()
            redactedData.forEach { (k, v) ->
                // KI-Umbau Etappe 1 (Nachtrag): ein echtes `null` (z.B. "AGC-Zustand unbekannt")
                // ist ein Diagnosewert an sich und muss von "Schluessel nie gesetzt"
                // unterscheidbar bleiben - JSONObject.put() mit einem Kotlin-`null` wuerfe eine
                // NullPointerException, darum explizit JSONObject.NULL statt den Eintrag zu
                // ueberspringen.
                dataObj.put(k, v ?: JSONObject.NULL)
            }
            json.put("data", dataObj)
            sb.append(json.toString()).append("\n")
        }
        return sb.toString()
    }

    private fun buildDeviceJson(): String {
        val json = JSONObject()
        json.put("sdkInt", Build.VERSION.SDK_INT)
        json.put("osRelease", Build.VERSION.RELEASE)
        json.put("manufacturer", DiagnosticRedactor.redactString(Build.MANUFACTURER))
        json.put("model", DiagnosticRedactor.redactString(Build.MODEL))
        json.put("appVersion", BuildConfig.VERSION_NAME)
        json.put("versionCode", BuildConfig.VERSION_CODE)
        json.put("buildType", BuildConfig.BUILD_TYPE)
        return json.toString(2)
    }

    private fun buildChecksums(entries: Map<String, ByteArray>): String {
        val sb = StringBuilder()
        for ((name, data) in entries) {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val hex = digest.joinToString("") { "%02x".format(it) }
            sb.append("$hex  $name\n")
        }
        return sb.toString()
    }
}
