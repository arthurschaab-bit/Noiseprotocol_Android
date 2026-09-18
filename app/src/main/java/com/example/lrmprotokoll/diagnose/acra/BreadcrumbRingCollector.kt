package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import com.example.lrmprotokoll.diagnose.BreadcrumbRingFile
import com.google.auto.service.AutoService
import org.acra.builder.ReportBuilder
import org.acra.collector.Collector
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.json.JSONArray
import org.json.JSONObject

/** Schluessel, unter dem die zusammengefuehrte Ringdatei im Report landet. */
const val BREADCRUMB_RING_REPORT_KEY = "BREADCRUMB_RING"

/**
 * Obergrenze fuer den serialisierten Inhalt im Report (Zeichen) - grosszuegig unter dem
 * 512-KB-Rohbudget der Ringdatei selbst (Konzept 4.3), da die JSON-Serialisierung hier etwas
 * aufblaeht.
 */
private const val MAX_ZEICHEN = 400_000

/**
 * M12 Schritt 2 (Konzept 4.3, 6): haengt die zusammengefuehrte [BreadcrumbRingFile] an jeden
 * ACRA-Report. Schreibt bewusst unter einem eigenen Report-Schluessel statt ueber ACRAs
 * eingebautes `ReportField.CUSTOM_DATA` (das ist ein von [org.acra.collector.CustomDataCollector]
 * verwalteter `Map<String, String>`-Merge-Punkt, dessen Ausfuehrungsreihenfolge relativ zu diesem
 * Collector nicht garantiert ist) - so ist das Ergebnis unabhaengig von der Collector-Reihenfolge
 * korrekt.
 *
 * Per ServiceLoader registriert (@AutoService, siehe app/build.gradle.kts fuer die
 * KSP-Portierung des Prozessors).
 */
@AutoService(Collector::class)
class BreadcrumbRingCollector : Collector {

    override fun collect(
        context: Context,
        config: CoreConfiguration,
        reportBuilder: ReportBuilder,
        crashReportData: CrashReportData,
    ) {
        val breadcrumbs = runCatching { BreadcrumbRingFile(context.filesDir).lesen() }
            .getOrElse { emptyList() }

        val array = JSONArray()
        for (bc in breadcrumbs) {
            val json = JSONObject()
            json.put("timestamp", bc.timestampMillis)
            json.put("category", bc.category)
            json.put("message", bc.message)
            json.put("level", bc.level)
            val data = JSONObject()
            bc.data.forEach { (k, v) -> data.put(k, v ?: JSONObject.NULL) }
            json.put("data", data)
            array.put(json)
        }

        var content = array.toString()
        if (content.length > MAX_ZEICHEN) {
            content = content.take(MAX_ZEICHEN) + "\n[gekuerzt - ${breadcrumbs.size} Eintraege insgesamt]"
        }
        crashReportData.put(BREADCRUMB_RING_REPORT_KEY, content)
    }
}
