package com.example.lrmprotokoll.diagnose.acra

import android.app.job.JobInfo
import com.example.lrmprotokoll.BuildConfig
import java.util.concurrent.TimeUnit
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.config.coreConfiguration
import org.acra.config.limiter
import org.acra.config.scheduler
import org.acra.data.StringFormat

/**
 * Baut die ACRA-Konfiguration (M12 Schritt 1, Konzept Abschnitt 6 Schritt 1). Reine Funktion -
 * erzeugt nur ACRA-eigene Datenklassen und fasst keinen Android-Systemdienst an, deshalb ohne
 * Android-Laufzeit pruefbar (siehe AcraConfigTest).
 *
 * Wichtig fuer eine spaetere Sentry-Aktivierung (offener Punkt O-1, Konzept Abschnitt 5): ACRA
 * faengt den Absturz ab, bevor Sentry ihn sehen koennte. Beide Bibliotheken verketten den
 * vorherigen UncaughtExceptionHandler grundsaetzlich korrekt, aber die Initialisierungsreihenfolge
 * entscheidet, ob Sentry den Absturz noch sieht - das ist bei Aktivierung von Sentry explizit zu
 * pruefen und zu testen (LaermprotokollApp initialisiert ACRA in attachBaseContext(), Sentry erst
 * danach in onCreate()).
 */
object AcraConfig {

    fun build(): CoreConfiguration = coreConfiguration {
        buildConfigClass = BuildConfig::class.java
        reportFormat = StringFormat.JSON
        reportContent = listOf(
            ReportField.REPORT_ID,
            ReportField.APP_VERSION_CODE,
            ReportField.APP_VERSION_NAME,
            ReportField.PACKAGE_NAME,
            ReportField.PHONE_MODEL,
            ReportField.BRAND,
            ReportField.PRODUCT,
            ReportField.ANDROID_VERSION,
            ReportField.BUILD,
            ReportField.TOTAL_MEM_SIZE,
            ReportField.AVAILABLE_MEM_SIZE,
            ReportField.STACK_TRACE,
            ReportField.THREAD_DETAILS,
            ReportField.LOGCAT,
            ReportField.INITIAL_CONFIGURATION,
            ReportField.CRASH_CONFIGURATION,
            ReportField.USER_APP_START_DATE,
            ReportField.USER_CRASH_DATE,
            ReportField.IS_SILENT,
            ReportField.CUSTOM_DATA,
        )

        // Owner-Entscheidung O-2 (Konzept Abschnitt 8a, 17.09.2026): Logcat kommt vollstaendig
        // ins Bundle, einzige Filterung ist spaeter DiagnosticRedactor (Schritt 4). "-t 5000"
        // liest den Puffer so weit aus, wie er hergibt; "threadtime" statt "time" macht
        // Thread-IDs sichtbar - bei einem Absturz im Aufzeichnungsbetrieb (mehrere
        // Coroutine-Dispatcher, Foreground Service, BLE-Callbacks) der Unterschied zwischen
        // lesbar und Raetselraten.
        logcatArguments = listOf("-t", "5000", "-v", "threadtime")

        limiter {
            enabled = true
            // Nicht die Bundle-Groesse ist der Kostentreiber, sondern die Anzahl (Konzept 8a):
            // ohne Limiter flutet eine Absturzschleife Drive mit hunderten identischen Bundles.
            // Der Limiter darf deshalb unter keinen Umstaenden entfallen.
            failedReportLimit = 3
            overallLimit = 20
            period = 1
            periodUnit = TimeUnit.HOURS
        }

        scheduler {
            // Schritt 1 sendet noch nicht ueber Netzwerk (der Sender schreibt nur eine Datei,
            // siehe SupportOutboxReportSender) - relevant wird das erst mit dem echten
            // Upload-Worker in Schritt 5. NETWORK_TYPE_ANY statt NONE, damit der spaetere
            // netzwerkbehaftete Sender ohne erneute Konfigurationsaenderung funktioniert.
            requiresNetworkType = JobInfo.NETWORK_TYPE_ANY
            // Die App startet nicht von selbst neu - bei einer Dauerueberwachung waere ein
            // stiller Neustart ohne laufenden Foreground Service irrefuehrend.
            restartAfterCrash = false
        }
    }
}
