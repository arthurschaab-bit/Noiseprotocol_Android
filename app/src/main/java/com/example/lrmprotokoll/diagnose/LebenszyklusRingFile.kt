package com.example.lrmprotokoll.diagnose

import android.util.Log
import com.example.lrmprotokoll.data.SettingsManager
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

private const val TAG = "LebenszyklusRingFile"
private const val DATEINAME_A = "lebenszyklus_a.jsonl"
private const val DATEINAME_B = "lebenszyklus_b.jsonl"

/**
 * Absturzfeste, groessenbegrenzte Ablage fuer Lebenszyklus-Ereignisse (Prozessstart,
 * Geraeteneustart, Dienststart/-ende, Stromversorgungswechsel, Waechter-Ereignisse,
 * Lebenszeichen).
 *
 * Entspricht Befund I und Auftrag 9 (Welle 3, docs/PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md):
 * Zwei Dateien a [DATEI_OBERGRENZE_BYTES] (64 KB) im Wechsel. Da Lebenszyklus-Ereignisse selten
 * sind, reicht dieser Speicher ueber viele Wochen zurueck.
 */
class LebenszyklusRingFile(
    verzeichnis: File,
    executorOverride: ExecutorService? = null,
) {
    private val dateiA = File(verzeichnis, DATEINAME_A)
    private val dateiB = File(verzeichnis, DATEINAME_B)

    private val executor: ExecutorService by lazy { executorOverride ?: Executors.newSingleThreadExecutor() }

    @Volatile
    private var aktiveDatei: File = ermittleAktiveDatei()

    private fun ermittleAktiveDatei(): File {
        val existiertA = dateiA.exists()
        val existiertB = dateiB.exists()
        if (!existiertA && !existiertB) return dateiA
        if (existiertA && !existiertB) return dateiA
        if (!existiertA && existiertB) return dateiB
        return if (dateiB.lastModified() >= dateiA.lastModified()) dateiB else dateiA
    }

    /**
     * Haengt [eintrag] nach Schwaerzung sensibler Daten asynchron an die aktive Ringdatei an.
     */
    fun anhaengen(eintrag: LebenszyklusEintrag) {
        val redigierterEintrag = LebenszyklusEintrag(
            timestampMillis = eintrag.timestampMillis,
            ereignis = DiagnosticRedactor.redactString(eintrag.ereignis) ?: "",
            details = DiagnosticRedactor.redactMap(eintrag.details),
        )
        executor.execute {
            runCatching { schreibeZeile(serialisiere(redigierterEintrag)) }
                .onFailure { Log.w(TAG, "Konnte Eintrag nicht in Lebenszyklus-Ringdatei schreiben", it) }
        }
    }

    /**
     * Bequemer Einstiegspunkt zum Protokollieren eines Ereignisses mit Details.
     */
    fun protokolliere(
        ereignis: String,
        details: Map<String, Any?> = emptyMap(),
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        anhaengen(
            LebenszyklusEintrag(
                timestampMillis = timestampMillis,
                ereignis = ereignis,
                details = details,
            ),
        )
    }

    private fun serialisiere(eintrag: LebenszyklusEintrag): String {
        val json = JSONObject()
        json.put("timestamp", eintrag.timestampMillis)
        json.put("ereignis", eintrag.ereignis)
        val detailsObj = JSONObject()
        eintrag.details.forEach { (k, v) -> detailsObj.put(k, v ?: JSONObject.NULL) }
        json.put("details", detailsObj)
        return json.toString()
    }

    private fun schreibeZeile(zeile: String) {
        val bytes = (zeile + "\n").toByteArray(StandardCharsets.UTF_8)
        if (aktiveDatei.length() + bytes.size > DATEI_OBERGRENZE_BYTES) {
            aktiveDatei = if (aktiveDatei === dateiA) dateiB else dateiA
            FileOutputStream(aktiveDatei, false).use { }
        }
        FileOutputStream(aktiveDatei, true).use { it.write(bytes) }
    }

    /**
     * Liest beide Ringdateien synchron und liefert die Eintraege zeitlich sortiert zurueck.
     */
    fun lesen(): List<LebenszyklusEintrag> {
        val alle = mutableListOf<LebenszyklusEintrag>()
        for (datei in listOf(dateiA, dateiB)) {
            if (!datei.exists()) continue
            runCatching { datei.readLines(StandardCharsets.UTF_8) }.getOrNull()?.forEach { zeile ->
                parse(zeile)?.let { alle.add(it) }
            }
        }
        return alle.sortedBy { it.timestampMillis }
    }

    private fun parse(zeile: String): LebenszyklusEintrag? {
        if (zeile.isBlank()) return null
        return runCatching {
            val json = JSONObject(zeile)
            val detailsObj = json.optJSONObject("details")
            val detailsMap = mutableMapOf<String, Any?>()
            detailsObj?.keys()?.forEach { key ->
                detailsMap[key] = if (detailsObj.isNull(key)) null else detailsObj.get(key)
            }
            LebenszyklusEintrag(
                timestampMillis = json.getLong("timestamp"),
                ereignis = json.getString("ereignis"),
                details = detailsMap,
            )
        }.getOrNull()
    }

    /**
     * Beschneidet beide Ringdateien auf die Obergrenze, falls eine von ihnen diese durch
     * fruehere Vorfaelle ueberschritten hat.
     */
    fun beimStartBeschneiden() {
        executor.execute {
            for (datei in listOf(dateiA, dateiB)) {
                runCatching {
                    if (!datei.exists() || datei.length() <= DATEI_OBERGRENZE_BYTES) return@runCatching
                    val behalten = mutableListOf<String>()
                    var groesse = 0L
                    for (zeile in datei.readLines(StandardCharsets.UTF_8).asReversed()) {
                        val zeilenGroesse = (zeile.toByteArray(StandardCharsets.UTF_8).size + 1).toLong()
                        if (groesse + zeilenGroesse > DATEI_OBERGRENZE_BYTES) break
                        behalten.add(0, zeile)
                        groesse += zeilenGroesse
                    }
                    FileOutputStream(datei, false).use { out ->
                        behalten.forEach { out.write("$it\n".toByteArray(StandardCharsets.UTF_8)) }
                    }
                }.onFailure { Log.w(TAG, "Konnte Lebenszyklus-Ringdatei beim Start nicht beschneiden", it) }
            }
        }
    }

    /**
     * Wartet, bis alle asynchron eingereihten Schreibvorgaenge abgeschlossen sind (fuer Tests).
     */
    internal fun wartenBisFertig(timeoutSekunden: Long = 5) {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(timeoutSekunden, TimeUnit.SECONDS)
    }

    companion object {
        const val DATEI_OBERGRENZE_BYTES = 64 * 1024L
    }
}

/**
 * Ein einzelner Eintrag im Lebenszyklus-Protokoll.
 */
data class LebenszyklusEintrag(
    val timestampMillis: Long,
    val ereignis: String,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * Bewertet den Start des aktuellen App-Prozesses und erkennt anhand der Geraete-Uptime
 * (`elapsedRealtimeMs`), ob das Geraet seit dem letzten Lauf neu gestartet wurde.
 */
object LebenszyklusProzessUeberwachung {
    fun auswerten(
        settingsManager: SettingsManager,
        lebenszyklusRingFile: LebenszyklusRingFile,
        pid: Int = android.os.Process.myPid(),
        elapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime(),
        versionCode: Long = com.example.lrmprotokoll.BuildConfig.VERSION_CODE.toLong(),
        jetztMs: Long = System.currentTimeMillis(),
    ) {
        val vorherigerStartAt = settingsManager.letzterProzessStartAt
        val vorherigerElapsedRealtime = settingsManager.letzterProzessStartElapsedRealtime

        if (vorherigerStartAt > 0L) {
            val abstand = jetztMs - vorherigerStartAt
            // Wenn elapsedRealtime kleiner ist als der Zeitabstand seit dem letzten Start
            // (oder kleiner als der vorherige elapsedRealtime-Stand), erfolgte zwischenzeitlich ein Reboot.
            if (abstand > 0L && (elapsedRealtimeMs < abstand || elapsedRealtimeMs < vorherigerElapsedRealtime)) {
                lebenszyklusRingFile.protokolliere(
                    ereignis = "Gerät wurde neu gestartet",
                    details = mapOf(
                        "elapsedRealtimeMs" to elapsedRealtimeMs,
                        "abstandLetzterStartMs" to abstand,
                        "vorherigerStartAt" to vorherigerStartAt,
                    ),
                    timestampMillis = jetztMs,
                )
            }
        }

        lebenszyklusRingFile.protokolliere(
            ereignis = "Prozessstart",
            details = mapOf(
                "pid" to pid,
                "elapsedRealtimeMs" to elapsedRealtimeMs,
                "versionCode" to versionCode,
            ),
            timestampMillis = jetztMs,
        )

        settingsManager.letzterProzessStartAt = jetztMs
        settingsManager.letzterProzessStartElapsedRealtime = elapsedRealtimeMs
    }
}
