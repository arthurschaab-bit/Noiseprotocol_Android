package com.example.lrmprotokoll.diagnose.acra

import android.app.job.JobInfo
import org.acra.ReportField
import org.acra.data.StringFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schritt 1 Test 3: [AcraConfig.build] ist eine reine Funktion (keine Android-Laufzeit
 * angefasst) - deshalb bewusst ein plain-JUnit-Test ohne RobolectricTestRunner, analog zu
 * [com.example.lrmprotokoll.diagnose.DiagnosticFingerprintTest].
 */
class AcraConfigTest {

    @Test
    fun enthaeltDieGefordertenReportFelder() {
        val config = AcraConfig.build()

        val erwartet = listOf(
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
        assertTrue(config.reportContent.containsAll(erwartet))
    }

    @Test
    fun verwendetJsonFormat() {
        assertEquals(StringFormat.JSON, AcraConfig.build().reportFormat)
    }

    /**
     * Owner-Entscheidung O-2 (Konzept 8a): Logcat vollstaendig, threadtime statt time.
     */
    @Test
    fun liestLogcatVollstaendigMitThreadtime() {
        val config = AcraConfig.build()
        assertEquals(listOf("-t", "5000", "-v", "threadtime"), config.logcatArguments)
    }

    /**
     * Der Limiter darf unter keinen Umstaenden entfallen (Konzept 8a) - er ist der eigentliche
     * Schutz gegen eine Absturzschleife, nicht das Groessenbudget.
     */
    @Test
    fun limiterIstAktiv() {
        val config = AcraConfig.build()
        val limiterConfig = config.pluginConfigurations
            .filterIsInstance<org.acra.config.LimiterConfiguration>()
            .singleOrNull()
        assertTrue("Es muss genau eine LimiterConfiguration registriert sein", limiterConfig != null)
        assertTrue(limiterConfig!!.enabled)
        assertTrue(limiterConfig.failedReportLimit > 0)
    }

    @Test
    fun schedulerStartetDieAppNachAbsturzNichtNeu() {
        val config = AcraConfig.build()
        val schedulerConfig = config.pluginConfigurations
            .filterIsInstance<org.acra.config.SchedulerConfiguration>()
            .singleOrNull()
        assertTrue("Es muss genau eine SchedulerConfiguration registriert sein", schedulerConfig != null)
        assertTrue(!schedulerConfig!!.restartAfterCrash)
    }

    /**
     * CI-Fund M12 Schritt 8 (instrumentierter Testlauf 18.09.2026): mit NETWORK_TYPE_ANY blieb
     * der Scheduler-Job (und damit SupportOutboxReportSender) auf dem CI-Emulator nach einem
     * echten Absturz aus, obwohl der Sender selbst nur lokal in support_outbox/ schreibt - kein
     * Netzwerkzugriff. Regressionsschutz gegen ein erneutes Zurueckwechseln auf ANY/NONE-Aequivalente.
     */
    @Test
    fun schedulerVerlangtKeinNetzwerk() {
        val config = AcraConfig.build()
        val schedulerConfig = config.pluginConfigurations
            .filterIsInstance<org.acra.config.SchedulerConfiguration>()
            .singleOrNull()
        assertTrue("Es muss genau eine SchedulerConfiguration registriert sein", schedulerConfig != null)
        assertEquals(JobInfo.NETWORK_TYPE_NONE, schedulerConfig!!.requiresNetworkType)
    }
}
