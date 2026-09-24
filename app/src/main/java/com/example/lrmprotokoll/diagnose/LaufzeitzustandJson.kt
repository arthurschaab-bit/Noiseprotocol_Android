package com.example.lrmprotokoll.diagnose

import android.app.ActivityManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bugfix docs/PROMPT_FIX_LAUFZEITZUSTAND_ABSTURZ.md, Schritt 1: die "billigen" Laufzeitfelder,
 * die sowohl [com.example.lrmprotokoll.diagnose.export.SupportBundleExporter] (in
 * `state/runtime.json`, gebaut NACH einem moeglichen Absturz) als auch
 * [com.example.lrmprotokoll.diagnose.acra.LaufzeitzustandCollector] (in
 * `crash/laufzeitzustand_beim_absturz.json`, erfasst IM Absturzmoment selbst) brauchen:
 * Heap-Kennzahlen, [ActivityManager.MemoryInfo], laufende Dienste. Gemeinsam an einer Stelle
 * gehalten statt kopiert.
 *
 * Bewusst NICHT enthalten: Berechtigungsliste, Einstellungen, Akkustand, Doze-Status - die
 * bleiben im Exporter (dort nicht zeitkritisch; im Absturzpfad laut Auftrag "zu teuer, und im
 * Absturzmoment nicht noetig").
 *
 * Reine Lesezugriffe auf bereits vom System bzw. von der JVM gehaltene Daten, keine eigene
 * Initialisierung - unbedenklich auch im abstuerzenden Prozess. Jede Gruppe einzeln in
 * `runCatching` (faengt implizit auch [Throwable], nicht nur [Exception] - wichtig bei einem
 * zweiten [OutOfMemoryError]): schlaegt eine Gruppe fehl, fehlen nur ihre Felder, der Rest des
 * uebergebenen [JSONObject] bleibt unangetastet.
 */
object LaufzeitzustandJson {
    fun schreibeGemeinsameFelder(
        context: Context,
        json: JSONObject,
    ) {
        runCatching {
            val runtime = Runtime.getRuntime()
            json.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            json.put("heapFreeBytes", runtime.freeMemory())
            json.put("heapMaxBytes", runtime.maxMemory())
        }

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                json.put("systemAvailMemBytes", memInfo.availMem)
                json.put("systemTotalMemBytes", memInfo.totalMem)
                json.put("systemLowMemory", memInfo.lowMemory)
                json.put("systemMemoryThresholdBytes", memInfo.threshold)
            }
        }

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val dienste = JSONArray()
            @Suppress("DEPRECATION")
            am?.getRunningServices(Integer.MAX_VALUE)?.forEach { dienste.put(it.service.className) }
            json.put("laufendeDienste", dienste)
        }
    }
}
