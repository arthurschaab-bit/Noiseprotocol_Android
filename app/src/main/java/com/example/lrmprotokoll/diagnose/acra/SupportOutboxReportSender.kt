package com.example.lrmprotokoll.diagnose.acra

import android.content.Context
import com.google.auto.service.AutoService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * Vorlaeufiger Sender (Konzept Abschnitt 6 Schritt 1): legt den ACRA-Report nur als JSON-Datei in
 * `support_outbox/` ab - kein Netzwerk, kein Drive, kein Worker. Laeuft im ACRA-Sender-Prozess
 * (`:acra`), nicht im sterbenden Hauptprozess (Konzept 4.1) - ein einfacher, nicht gestreamter
 * Schreibvorgang ist hier unproblematisch, anders als beim grossen Support-Bundle in Schritt 4.
 *
 * Schritt 5 ersetzt das Innenleben durch ein echtes Bundle (ueber
 * [com.example.lrmprotokoll.diagnose.export.SupportBundleExporter]) plus Upload-Worker; die
 * Schnittstelle (Factory + Sender) bleibt dabei unveraendert.
 */
class SupportOutboxReportSender : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        val outboxDir = File(context.filesDir, SUPPORT_OUTBOX_DIR).apply { mkdirs() }
        val zeitstempel = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val datei = File(outboxDir, "${zeitstempel}_acra_report.json")
        datei.writeText(errorContent.toJSON())
    }
}
