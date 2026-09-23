package com.example.lrmprotokoll.diagnose.export

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.ANR_WATCHDOG_DATEINAME
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticRedactor
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import com.example.lrmprotokoll.diagnose.HaengerBefund
import com.example.lrmprotokoll.diagnose.acra.SUPPORT_OUTBOX_DIR
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Obergrenze fuer ANR-Watchdog-Bundles (O-8) - schuetzt vor einer Haenger-Schleife. */
internal const val ANR_WATCHDOG_MAX_BUNDLES_JE_24H = 3

/** Obergrenze fuer den Mitschnitt, unkomprimiert (wie die Einzelobergrenzen in Konzept 4.5). */
internal const val ANR_WATCHDOG_MAX_BYTES = 256L * 1024

/**
 * O-8 (Konzept Abschnitt 8a, Owner-Entscheidung 23.09.2026): Reaktion auf einen vom
 * [com.example.lrmprotokoll.diagnose.AnrWatchdog] erkannten Haenger.
 *
 * - [haengerErkannt]: schreibt sofort und synchron den Stacktrace-Mitschnitt nach
 *   `process_exit_traces/anr_watchdog.txt`, dazu Breadcrumb und Report-Event. Synchron, weil
 *   Android den Prozess waehrend des Haengers jederzeit beenden kann.
 * - Das Bundle (Typ [BundleTyp.ANR]) entsteht erst danach: sobald der Main-Thread wieder
 *   reagiert ([erholt]) oder, falls Android den Prozess beendet hat, beim naechsten Start
 *   ([ausstehendesBundleNachholen]). Waehrend des Haengers wird bewusst nichts gebaut - das
 *   wuerde Speicher und CPU genau im schlechtesten Moment belasten.
 * - Ausstehend heisst: die Mitschnitt-Datei existiert. Nach dem Bundle (oder wenn die
 *   Obergrenze greift) wird sie geloescht - ausser ein neuer Haenger hat sie inzwischen
 *   ueberschrieben.
 * - Hoechstens [ANR_WATCHDOG_MAX_BUNDLES_JE_24H] Bundles je 24 h. Der Upload folgt demselben
 *   Schalter wie bei Abstuerzen ([SettingsManager.absturzAutoUploadAktiv]), inklusive
 *   6-h-Fallback ohne WLAN (Konzept 4.7 nennt Absturz- und ANR-Bundles gemeinsam).
 */
class AnrWatchdogCoordinator(
    private val context: Context,
    private val verzeichnis: File,
    private val reporter: DiagnosticsReporter,
    private val exporter: SupportBundleExporter,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
    private val jetztMs: () -> Long = { System.currentTimeMillis() },
    private val weitereThreads: () -> Map<Thread, Array<StackTraceElement>> = { Thread.getAllStackTraces() },
    private val uploadEinplanen: (Context) -> Unit = {
        SupportBundleUploadPlanung.planeSofort(it)
        SupportBundleUploadPlanung.planeFallbackOhneNetzbeschraenkung(it)
    },
) {

    private val bundleMutex = Mutex()

    private val mitschnitt: File get() = File(verzeichnis, ANR_WATCHDOG_DATEINAME)

    /** Laeuft im Watchdog-Thread, waehrend der Main-Thread haengt. */
    fun haengerErkannt(befund: HaengerBefund) {
        runCatching {
            verzeichnis.mkdirs()
            val text = DiagnosticRedactor.redactString(mitschnittText(befund)).orEmpty()
            val bytes = text.toByteArray(Charsets.UTF_8)
            mitschnitt.writeBytes(if (bytes.size > ANR_WATCHDOG_MAX_BYTES) bytes.copyOf(ANR_WATCHDOG_MAX_BYTES.toInt()) else bytes)
        }.onFailure { Log.w(TAG, "Konnte Watchdog-Mitschnitt nicht schreiben", it) }

        val oben = befund.mainThreadStack.firstOrNull()?.toString() ?: "unbekannt"
        reporter.breadcrumb(
            category = "AnrWatchdog",
            message = "Main-Thread reagiert seit ${befund.dauerMs} ms nicht: $oben",
            level = DiagnosticSeverity.WARN,
        )
        reporter.report(
            code = DiagnosticCode.APP_ANR_WATCHDOG,
            component = "AnrWatchdog",
            operation = "ueberwachen",
            severity = DiagnosticSeverity.WARN,
            message = "Main-Thread reagiert nicht (Watchdog)",
            details = mapOf("dauerMs" to befund.dauerMs, "oberstesFrame" to oben),
        )
    }

    /** Der Main-Thread reagiert wieder - das Bundle jetzt bauen, im Hintergrund. */
    fun erholt() {
        scope.launch { bundleErstellen(ausloeser = "Watchdog") }
    }

    /** Beim App-Start: ein Mitschnitt aus einem Prozess, den Android waehrend des Haengers beendet hat. */
    fun ausstehendesBundleNachholen() {
        if (!mitschnitt.exists()) return
        scope.launch { bundleErstellen(ausloeser = "Watchdog, nach Neustart") }
    }

    /** @return die Datei in der Outbox, oder `null`, wenn nichts ansteht oder die Obergrenze greift. */
    internal suspend fun bundleErstellen(ausloeser: String): File? = bundleMutex.withLock {
        if (!mitschnitt.exists()) return null
        // Ein neuer Haenger kann den Mitschnitt ueberschreiben, waehrend dieses Bundle entsteht -
        // dann gehoert er zum naechsten Bundle und darf unten nicht mit geloescht werden.
        val stand = mitschnitt.lastModified() to mitschnitt.length()

        val jetzt = jetztMs()
        val imFenster = settingsManager.anrWatchdogBundleZeitstempel
            .filter { jetzt - it < TimeUnit.HOURS.toMillis(24) }
        if (imFenster.size >= ANR_WATCHDOG_MAX_BUNDLES_JE_24H) {
            // Breadcrumb und Report-Event bleiben - nur kein weiteres Bundle.
            reporter.breadcrumb(
                category = "AnrWatchdog",
                message = "Kein Bundle: Obergrenze $ANR_WATCHDOG_MAX_BUNDLES_JE_24H je 24 h erreicht",
                level = DiagnosticSeverity.WARN,
            )
            loescheFallsUnveraendert(stand)
            settingsManager.anrWatchdogBundleZeitstempel = imFenster
            return null
        }

        val ziel = runCatching {
            val bundleDatei = exporter.createBundle(BundleKontext(typ = BundleTyp.ANR, ausloeser = ausloeser))
            val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR).apply { mkdirs() }
            File(outboxDir, bundleDatei.name).also {
                bundleDatei.copyTo(it, overwrite = true)
                bundleDatei.delete()
            }
        }.getOrElse {
            // Mitschnitt bleibt liegen - der naechste Start versucht es erneut.
            Log.w(TAG, "ANR-Bundle konnte nicht erstellt werden", it)
            return null
        }
        loescheFallsUnveraendert(stand)
        settingsManager.anrWatchdogBundleZeitstempel = imFenster + jetzt
        if (settingsManager.absturzAutoUploadAktiv) uploadEinplanen(context)
        ziel
    }

    private fun loescheFallsUnveraendert(stand: Pair<Long, Long>) {
        if (mitschnitt.lastModified() == stand.first && mitschnitt.length() == stand.second) mitschnitt.delete()
    }

    private fun mitschnittText(befund: HaengerBefund): String = buildString {
        appendLine("ANR-Watchdog (Laermprotokoll)")
        appendLine("erkanntUm: ${Instant.ofEpochMilli(jetztMs())}")
        appendLine("mainThreadOhneReaktionMs: ${befund.dauerMs}")
        appendLine("sdk: ${Build.VERSION.SDK_INT}")
        appendLine()
        appendLine("---- main ----")
        befund.mainThreadStack.forEach { appendLine("\tat $it") }
        appendLine()
        appendLine("---- weitere Threads ----")
        runCatching { weitereThreads() }.getOrDefault(emptyMap())
            .filterKeys { it.name != "main" }
            .forEach { (thread, stack) ->
                appendLine("\"${thread.name}\" ${thread.state}")
                stack.forEach { appendLine("\tat $it") }
                appendLine()
            }
    }

    private companion object {
        const val TAG = "AnrWatchdogCoordinator"
    }
}
