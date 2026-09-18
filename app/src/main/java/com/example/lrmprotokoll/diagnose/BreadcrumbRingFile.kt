package com.example.lrmprotokoll.diagnose

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

private const val TAG = "BreadcrumbRingFile"
private const val DATEINAME_A = "breadcrumbs_a.jsonl"
private const val DATEINAME_B = "breadcrumbs_b.jsonl"

/**
 * Absturzfeste, groessenbegrenzte Ablage fuer Breadcrumbs (Konzept 4.3, M12 Schritt 2 - behebt
 * Luecke L3: Breadcrumbs liegen bis zum Absturz nur im RAM, siehe [CompositeDiagnosticsReporter]).
 *
 * Zwei Dateien a [DATEI_OBERGRENZE_BYTES] im Wechsel: ist die aktive Datei voll, wird auf die
 * jeweils andere umgeschaltet und diese dabei geleert (Rotation statt Wachstum). Damit liegen
 * immer zwischen [DATEI_OBERGRENZE_BYTES] und 2x [DATEI_OBERGRENZE_BYTES] Historie vor.
 *
 * Schreiben laeuft auf einem eigenen Single-Thread-Executor - der Aufrufer blockiert nie, und
 * die Allokation pro Schreibvorgang ist auf die eine geschriebene Zeile begrenzt (OOM ist der
 * Hauptverdaechtige fuer den akuten Absturz, Konzept Abschnitt 2). Der Executor wird erst beim
 * ersten Schreibvorgang angelegt - ein reiner Lesezugriff (z.B. aus [ACRA-Collector in
 * diagnose.acra]) erzeugt keinen ungenutzten Hintergrund-Thread.
 *
 * Liegt in `filesDir` (App-interner Speicher), nicht im externen Speicher.
 */
class BreadcrumbRingFile(
    verzeichnis: File,
    executorOverride: ExecutorService? = null,
) {
    private val dateiA = File(verzeichnis, DATEINAME_A)
    private val dateiB = File(verzeichnis, DATEINAME_B)

    private val executor: ExecutorService by lazy { executorOverride ?: Executors.newSingleThreadExecutor() }

    // Schreibvorgaenge passieren ausschliesslich an der aktiven Datei - ihr Zeitstempel ist
    // damit nach einem Prozessneustart immer der juengere und identifiziert sie zuverlaessig.
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

    /** Haengt [breadcrumb] asynchron an - blockiert den Aufrufer nicht. */
    fun anhaengen(breadcrumb: DiagnosticBreadcrumb) {
        executor.execute {
            runCatching { schreibeZeile(serialisiere(breadcrumb)) }
                .onFailure { Log.w(TAG, "Konnte Breadcrumb nicht in Ringdatei schreiben", it) }
        }
    }

    private fun serialisiere(breadcrumb: DiagnosticBreadcrumb): String {
        val json = JSONObject()
        json.put("timestamp", breadcrumb.timestamp.toEpochMilli())
        json.put("category", breadcrumb.category)
        json.put("message", breadcrumb.message)
        json.put("level", breadcrumb.level.name)
        val data = JSONObject()
        breadcrumb.data.forEach { (k, v) -> data.put(k, v ?: JSONObject.NULL) }
        json.put("data", data)
        return json.toString()
    }

    private fun schreibeZeile(zeile: String) {
        val bytes = (zeile + "\n").toByteArray(StandardCharsets.UTF_8)
        if (aktiveDatei.length() + bytes.size > DATEI_OBERGRENZE_BYTES) {
            aktiveDatei = if (aktiveDatei === dateiA) dateiB else dateiA
            // Rotation: die neu aktive Datei wird geleert, bevor die erste Zeile hineingeht -
            // sie enthielt die naechst-aeltere Historie, deren Platz jetzt gebraucht wird.
            FileOutputStream(aktiveDatei, false).use { }
        }
        FileOutputStream(aktiveDatei, true).use { it.write(bytes) }
    }

    /**
     * Liest beide Dateien und fuehrt sie in zeitlicher Reihenfolge zusammen. Synchron (kein
     * Executor-Umweg) - Leser wie der ACRA-Collector laufen im Absturzmoment selbst und duerfen
     * nicht auf eine Hintergrund-Warteschlange warten.
     *
     * Zeilen, die sich nicht als gueltiges JSON-Objekt mit den erwarteten Feldern lesen lassen
     * (z.B. eine durch Rotation angebrochene erste Zeile), werden uebersprungen statt den
     * gesamten Lesevorgang scheitern zu lassen.
     */
    fun lesen(): List<RingBreadcrumb> {
        val alle = mutableListOf<RingBreadcrumb>()
        for (datei in listOf(dateiA, dateiB)) {
            if (!datei.exists()) continue
            runCatching { datei.readLines(StandardCharsets.UTF_8) }.getOrNull()?.forEach { zeile ->
                parse(zeile)?.let { alle.add(it) }
            }
        }
        return alle.sortedBy { it.timestampMillis }
    }

    private fun parse(zeile: String): RingBreadcrumb? {
        if (zeile.isBlank()) return null
        return runCatching {
            val json = JSONObject(zeile)
            val data = json.getJSONObject("data")
            val dataMap = mutableMapOf<String, Any?>()
            data.keys().forEach { key -> dataMap[key] = if (data.isNull(key)) null else data.get(key) }
            RingBreadcrumb(
                timestampMillis = json.getLong("timestamp"),
                category = json.getString("category"),
                message = json.getString("message"),
                level = json.getString("level"),
                data = dataMap,
            )
        }.getOrNull()
    }

    /**
     * Beschneidet beide Dateien auf die Obergrenze, falls eine von ihnen sie durch einen
     * frueheren Fehler ueberschreitet (Aufgabe 5). Von [com.example.lrmprotokoll.LaermprotokollApp]
     * genau einmal beim Start aufgerufen.
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
                }.onFailure { Log.w(TAG, "Konnte Ringdatei beim Start nicht beschneiden", it) }
            }
        }
    }

    /** Nur fuer Tests: wartet, bis alle bereits eingereihten Schreibvorgaenge abgeschlossen sind. */
    internal fun wartenBisFertig(timeoutSekunden: Long = 5) {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(timeoutSekunden, TimeUnit.SECONDS)
    }

    companion object {
        const val DATEI_OBERGRENZE_BYTES = 256 * 1024L
    }
}

/** Eine aus der Ringdatei gelesene Breadcrumb-Zeile (Konzept 4.3). */
data class RingBreadcrumb(
    val timestampMillis: Long,
    val category: String,
    val message: String,
    val level: String,
    val data: Map<String, Any?>,
)
