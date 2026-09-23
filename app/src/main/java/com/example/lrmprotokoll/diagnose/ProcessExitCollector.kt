package com.example.lrmprotokoll.diagnose

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val TAG = "ProcessExitCollector"

/** Dateiname fuer den ANR-Thread-Dump (Konzept 4.4: `crash/anr_trace.txt`). */
const val ANR_TRACE_DATEINAME = "anr_trace.txt"

/** Dateiname fuer das native Tombstone-Protobuf (Konzept 4.4: `crash/native_tombstone.pb`). */
const val NATIVE_TOMBSTONE_DATEINAME = "native_tombstone.pb"

/** Obergrenzen aus Konzept 4.5 (Absturz-Bundle-Budget) - gelten schon hier beim Rohsichern. */
private const val ANR_TRACE_MAX_BYTES = 4L * 1024 * 1024
private const val NATIVE_TOMBSTONE_MAX_BYTES = 8L * 1024 * 1024

/** Traces, die laenger als das hier liegenbleiben, gelten als verwaist und werden verworfen. */
private const val TRACE_MAX_ALTER_TAGE = 7L

/**
 * Eigene, plattformunabhaengige Abbildung eines [ApplicationExitInfo]-Eintrags (Konzept 4).
 * [ApplicationExitInfo] selbst hat keinen oeffentlichen Konstruktor und laesst sich deshalb nicht
 * direkt in einem handgeschriebenen Fake verwenden (AGENTS.md Abschnitt 3: keine Mocking-
 * Bibliothek) - diese Klasse ist die Testbarkeitsgrenze.
 */
data class ProcessExitInfo(
    val reason: Int,
    val status: Int,
    val timestamp: Long,
    val importance: Int,
    val pss: Long,
    val rss: Long,
    val description: String?,
    val processName: String,
    val definingUid: Int,
    /** `null`, wenn kein Trace verfuegbar ist - der globale Ringpuffer kann von anderen Apps
     * ueberschrieben worden sein (Normalfall, kein Fehler, siehe Konzept Schritt 3 Aufgabe 1). */
    val traceInputStreamProvider: () -> InputStream? = { null },
)

/** Quelle fuer vergangene Prozessbeendigungen - Testbarkeitsgrenze zu [ActivityManager]. */
interface ProcessExitSource {
    fun historischeExits(): List<ProcessExitInfo>
}

/** Echte, auf [ActivityManager.getHistoricalProcessExitReasons] basierende Implementierung. */
class SystemProcessExitSource(private val context: Context) : ProcessExitSource {
    override fun historischeExits(): List<ProcessExitInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        // maxNum=0: ALLE verfuegbaren Eintraege (bis zu 16), nicht nur den letzten (Konzept
        // Aufgabe 1 - vorher getHistoricalProcessExitReasons(packageName, 0, 1)).
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 0).map { exit ->
            ProcessExitInfo(
                reason = exit.reason,
                status = exit.status,
                timestamp = exit.timestamp,
                importance = exit.importance,
                pss = exit.pss,
                rss = exit.rss,
                description = exit.description,
                processName = exit.processName,
                definingUid = exit.definingUid,
                traceInputStreamProvider = { runCatching { exit.traceInputStream }.getOrNull() },
            )
        }
    }
}

/**
 * M12 Schritt 3 (Konzept Abschnitt 6, behebt Luecke L5): wertet vergangene Prozessbeendigungen
 * vollstaendig aus - vorher wurde nur ein einzelner Eintrag gelesen und `getTraceInputStream()`
 * nie aufgerufen, obwohl dort bei `REASON_ANR` der vollstaendige Thread-Dump und ab API 31 bei
 * `REASON_CRASH_NATIVE` das native Tombstone liegen.
 *
 * Geloest aus [com.example.lrmprotokoll.LaermprotokollApp.checkPreviousProcessExit] (vorher dort
 * inline) - dieselbe Ausloese-Logik (Breadcrumb + Report bei CRASH/ANR), jetzt fuer ALLE seit dem
 * letzten Start neuen Eintraege statt nur den einen zuletzt gesehenen.
 */
class ProcessExitCollector(
    private val source: ProcessExitSource,
    private val diagnosticsReporter: DiagnosticsReporter,
    private val verzeichnis: File,
    private val zuletztVerarbeitet: () -> Long,
    private val setzeZuletztVerarbeitet: (Long) -> Unit,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    /** Wertet neue Exits seit dem letzten Aufruf aus. Auf SDK < 30 (minSdk ist 29) ein No-Op. */
    fun auswerten() {
        if (sdkInt < Build.VERSION_CODES.R) return

        runCatching { raeumeAlteTracesAuf() }.onFailure { Log.w(TAG, "Aufraeumen alter Traces fehlgeschlagen", it) }

        val exits = runCatching { source.historischeExits() }.getOrElse { emptyList() }
        if (exits.isEmpty()) return

        val letzterVerarbeiteterZeitstempel = zuletztVerarbeitet()
        // Entprellung (Aufgabe 2): ohne das wuerde derselbe Exit bei jedem Start erneut
        // gemeldet und spaeter erneut hochgeladen.
        val neue = exits.filter { it.timestamp > letzterVerarbeiteterZeitstempel }.sortedBy { it.timestamp }
        if (neue.isEmpty()) return

        for (exit in neue) {
            runCatching { verarbeiteEinzelnenExit(exit) }
                .onFailure { Log.w(TAG, "Konnte Prozess-Exit nicht verarbeiten", it) }
        }
        setzeZuletztVerarbeitet(neue.last().timestamp)
    }

    private fun verarbeiteEinzelnenExit(exit: ProcessExitInfo) {
        val reasonDesc = reasonBeschreibung(exit.reason)
        val unnormal = exit.reason == ApplicationExitInfo.REASON_CRASH || exit.reason == ApplicationExitInfo.REASON_ANR

        diagnosticsReporter.breadcrumb(
            category = "Process",
            message = "Vorheriger Prozess-Exit: $reasonDesc (Status: ${exit.status})",
            level = if (unnormal) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
        )

        if (unnormal) {
            diagnosticsReporter.report(
                code = DiagnosticCode.APP_PREVIOUS_EXIT,
                component = "Process",
                operation = "auswerten",
                severity = DiagnosticSeverity.WARN,
                message = "Vorherige Prozessbeendigung war unnormal: $reasonDesc",
                details = mapOf(
                    "exitReason" to reasonDesc,
                    "exitStatus" to exit.status,
                    "exitTimestamp" to exit.timestamp,
                    "importance" to exit.importance,
                    "pss" to exit.pss,
                    "rss" to exit.rss,
                    "processName" to exit.processName,
                ),
            )
        }

        if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            sichereTrace(exit, ANR_TRACE_DATEINAME, ANR_TRACE_MAX_BYTES)
        } else if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && sdkInt >= Build.VERSION_CODES.S) {
            // Ab API 31 liefert getTraceInputStream() hier ein Protobuf mit Binaerdaten -
            // byteweise kopieren (Aufgabe 1), niemals durch einen Reader/String schicken, das
            // wuerde den Inhalt zerstoeren. sichereTrace() kopiert ausschliesslich byteweise.
            sichereTrace(exit, NATIVE_TOMBSTONE_DATEINAME, NATIVE_TOMBSTONE_MAX_BYTES)
        }
    }

    private fun sichereTrace(exit: ProcessExitInfo, dateiname: String, obergrenzeBytes: Long) {
        // null ist der Normalfall (globaler Ringpuffer, andere Apps koennen ihn ueberschrieben
        // haben) - kein Fehler, kein Log-Rauschen.
        val quelle = runCatching { exit.traceInputStreamProvider() }.getOrNull() ?: return
        runCatching {
            verzeichnis.mkdirs()
            quelle.use { input ->
                FileOutputStream(File(verzeichnis, dateiname)).use { output ->
                    kopiereMitObergrenze(input, output, obergrenzeBytes)
                }
            }
        }.onFailure { Log.w(TAG, "Konnte Trace nicht sichern: $dateiname", it) }
    }

    private fun kopiereMitObergrenze(input: InputStream, output: FileOutputStream, obergrenzeBytes: Long) {
        val puffer = ByteArray(8192)
        var gesamt = 0L
        while (gesamt < obergrenzeBytes) {
            val gelesen = input.read(puffer, 0, minOf(puffer.size.toLong(), obergrenzeBytes - gesamt).toInt())
            if (gelesen == -1) break
            output.write(puffer, 0, gelesen)
            gesamt += gelesen
        }
    }

    private fun raeumeAlteTracesAuf() {
        val grenze = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRACE_MAX_ALTER_TAGE)
        for (name in listOf(ANR_TRACE_DATEINAME, NATIVE_TOMBSTONE_DATEINAME)) {
            val datei = File(verzeichnis, name)
            if (datei.exists() && datei.lastModified() < grenze) {
                datei.delete()
            }
        }
    }

    private fun reasonBeschreibung(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        else -> "CODE_$reason"
    }
}
