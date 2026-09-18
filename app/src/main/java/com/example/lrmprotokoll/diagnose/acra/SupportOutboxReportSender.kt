package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.BreadcrumbRingFile
import com.example.lrmprotokoll.diagnose.CompositeDiagnosticsReporter
import com.example.lrmprotokoll.diagnose.export.BundleKontext
import com.example.lrmprotokoll.diagnose.export.BundleTyp
import com.example.lrmprotokoll.diagnose.export.SupportBundleExporter
import com.example.lrmprotokoll.diagnose.export.SupportBundleUploadPlanung
import com.google.auto.service.AutoService
import java.io.File
import kotlinx.coroutines.runBlocking
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory

/** Verzeichnis unter `filesDir`, in dem Absturzberichte auf ihren Upload warten (Konzept 4.2). */
const val SUPPORT_OUTBOX_DIR = "support_outbox"

/**
 * M12 Schritt 1: registriert [SupportOutboxReportSender] bei ACRA per ServiceLoader
 * (`@AutoService`, siehe app/build.gradle.kts fuer die KSP-Portierung des Prozessors - der
 * offizielle `com.google.auto.service:auto-service`-Prozessor ist reines APT und laeuft mit KSP
 * nicht).
 */
@AutoService(ReportSenderFactory::class)
class SupportOutboxReportSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        SupportOutboxReportSender()
}

/**
 * M12 Schritt 5 (Konzept Abschnitt 6 Schritt 5): baut aus dem ACRA-Report ein vollstaendiges
 * Support-Bundle (ueber [SupportBundleExporter]), legt es in `support_outbox/` ab und reiht den
 * Upload-Worker ein - kein Netzwerkzugriff in diesem Prozess (Konzept 4.2).
 *
 * Laeuft im ACRA-Sender-Prozess (`:acra`), nicht im sterbenden Hauptprozess (Konzept 4.1). Baut
 * deshalb bewusst NICHT den vollen [com.example.lrmprotokoll.AppContainer] auf (der wuerde eine
 * zweite Room-Instanz, einen zweiten BLE-Transport und einen zweiten OkHttp-Pool anlegen - genau
 * das, was die Prozess-Weiche in [com.example.lrmprotokoll.LaermprotokollApp] verhindern soll),
 * sondern nur die schmale Teilmenge, die [SupportBundleExporter] tatsaechlich braucht (Datenbank,
 * Einstellungen, Ringdatei). Das ist eine bewusst leichtgewichtige Ausnahme von der Prozess-Weiche,
 * keine Verletzung davon: Room oeffnet pro Prozess ohnehin eine eigene Verbindung
 * ([AppDatabase.getDatabase] ist selbst schon prozesslokal ueber ein `@Volatile`-Feld
 * gecacht) - was die Weiche verhindert, ist der schwere Rest von `AppContainer`.
 */
class SupportOutboxReportSender : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        val database = AppDatabase.getDatabase(context)
        val settingsManager = SettingsManager(context)
        val ringFile = BreadcrumbRingFile(context.filesDir)
        val reporter = CompositeDiagnosticsReporter(sinks = emptyList(), ringFile = ringFile)
        val exporter = SupportBundleExporter(
            context = context,
            reporter = reporter,
            diagnosticLogDao = database.diagnosticLogDao(),
            breadcrumbRingFile = ringFile,
            settingsManager = settingsManager,
            database = database,
            traceVerzeichnis = File(context.filesDir, "process_exit_traces"),
        )

        val bundleDatei = runBlocking {
            exporter.createBundle(
                BundleKontext(
                    typ = BundleTyp.ABSTURZ,
                    ausloeser = "ACRA",
                    acraReportJson = runCatching { errorContent.toJSON() }.getOrNull(),
                    threadDetails = errorContent.getString(ReportField.THREAD_DETAILS),
                    logcatText = errorContent.getString(ReportField.LOGCAT),
                )
            )
        }

        val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR).apply { mkdirs() }
        val ziel = File(outboxDir, bundleDatei.name)
        bundleDatei.copyTo(ziel, overwrite = true)
        bundleDatei.delete()

        SupportBundleUploadPlanung.planeSofort(context)
        // Fallback fuer ein Geraet ohne WLAN (Konzept 4.7) - nur fuer Absturz/ANR, nicht fuer
        // periodische Bundles (die reiht nur planeSofort() ein, siehe Schritt 6).
        SupportBundleUploadPlanung.planeFallbackOhneNetzbeschraenkung(context)
    }
}
