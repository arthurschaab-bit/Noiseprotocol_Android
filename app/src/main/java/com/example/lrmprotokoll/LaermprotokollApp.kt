package com.example.lrmprotokoll

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticRedactor
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.acra.AcraConfig
import io.sentry.android.core.SentryAndroid
import org.acra.ACRA

class LaermprotokollApp : Application() {

    lateinit var container: AppContainer
        internal set

    /**
     * Testseam (M12 Schritt 1): [ACRA.isACRASenderServiceProcess] liest den echten
     * Betriebssystem-Prozessnamen - in einem Robolectric-Test laeuft alles im selben Prozess,
     * das laesst sich nicht faken. `null` (Standard) verwendet die echte ACRA-Abfrage.
     */
    internal var acraSenderProcessOverride: Boolean? = null

    fun setCustomContainer(customContainer: AppContainer) {
        container = customContainer
    }

    fun resetContainer() {
        container = AppContainer(this)
    }

    /**
     * `instance::container.isInitialized` liesse sich von ausserhalb dieser Klasse nicht
     * aufrufen (Kotlin verlangt fuer die isInitialized-Pruefung eines lateinit-Felds direkten
     * Backing-Field-Zugriff, der nur innerhalb der deklarierenden Klasse besteht) - deshalb hier
     * als schmaler Zugriffspunkt fuer Tests (Schritt 1 Akzeptanzkriterium "Der zweite Prozess
     * baut nachweislich keinen AppContainer auf").
     */
    internal fun isContainerInitialized(): Boolean = ::container.isInitialized

    /**
     * ACRA verlangt Initialisierung hier, nicht in [onCreate] - harte Anforderung der
     * Bibliothek (Konzept 4.1), keine Stilfrage. Sichtbarkeit auf `public` erweitert (Kotlin
     * erlaubt das Weiten einer Override-Sichtbarkeit), damit Tests die Reihenfolge
     * attachBaseContext() -> onCreate() an einer eigenen Instanz nachstellen koennen, ohne auf
     * die von Robolectric automatisch gebaute Singleton-Applikation angewiesen zu sein.
     */
    public override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initAcra()
    }

    override fun onCreate() {
        super.onCreate()
        // Prozess-Weiche (Konzept 4.1): ACRA startet den Sender in einem eigenen Prozess
        // (":acra"). Application.onCreate() laeuft dort erneut - ohne diese Weiche wuerde
        // AppContainer dort ein zweites Mal aufgebaut: zweite Room-Instanz, zweiter
        // BLE-Transport, zweiter OkHttp-Pool, auf einem Geraet, das eventuell gerade wegen
        // Speichermangels abgestuerzt ist.
        if (isAcraSenderProcess()) return
        initSentry()
        container = AppContainer(this)
        // Aufgabe 5 (Konzept 4.3): die Ringdatei wird genau einmal beim Start beschnitten,
        // falls sie durch einen frueheren Fehler die Obergrenze ueberschreitet.
        container.breadcrumbRingFile.beimStartBeschneiden()
        checkPreviousProcessExit()
    }

    internal fun isAcraSenderProcess(): Boolean =
        acraSenderProcessOverride ?: runCatching { ACRA.isACRASenderServiceProcess() }.getOrDefault(false)

    private fun initAcra() {
        runCatching {
            ACRA.init(this, AcraConfig.build())
        }.onFailure {
            Log.w("LaermprotokollApp", "ACRA konnte nicht initialisiert werden", it)
        }
    }

    private fun initSentry() {
        if (!BuildConfig.DIAGNOSTICS_REMOTE_ENABLED) return
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return

        runCatching {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.environment = BuildConfig.BUILD_TYPE
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.isSendDefaultPii = false
                options.isEnableUserInteractionTracing = false
                options.isEnableAutoSessionTracking = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false

                options.setBeforeSend { event, _ ->
                    event.message?.formatted?.let {
                        event.message?.formatted = DiagnosticRedactor.redactString(it)
                    }
                    event
                }
            }
        }.onFailure {
            Log.w("LaermprotokollApp", "Sentry konnte nicht initialisiert werden", it)
        }
    }

    private fun checkPreviousProcessExit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
                val exitInfos = am.getHistoricalProcessExitReasons(packageName, 0, 1)
                if (exitInfos.isNotEmpty()) {
                    val exit = exitInfos.first()
                    val reasonDesc = when (exit.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "CRASH"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                        else -> "CODE_${exit.reason}"
                    }

                    container.diagnosticsReporter.breadcrumb(
                        category = "Process",
                        message = "Vorheriger Prozess-Exit: $reasonDesc (Status: ${exit.status})",
                        level = if (exit.reason == ApplicationExitInfo.REASON_CRASH || exit.reason == ApplicationExitInfo.REASON_ANR) {
                            DiagnosticSeverity.WARN
                        } else {
                            DiagnosticSeverity.INFO
                        }
                    )

                    if (exit.reason == ApplicationExitInfo.REASON_CRASH || exit.reason == ApplicationExitInfo.REASON_ANR) {
                        container.diagnosticsReporter.report(
                            code = DiagnosticCode.APP_PREVIOUS_EXIT,
                            component = "Process",
                            operation = "checkPreviousProcessExit",
                            severity = DiagnosticSeverity.WARN,
                            message = "Vorherige Prozessbeendigung war unnormal: $reasonDesc",
                            details = mapOf(
                                "exitReason" to reasonDesc,
                                "exitStatus" to exit.status,
                                "exitTimestamp" to exit.timestamp,
                                "importance" to exit.importance
                            )
                        )
                    }
                }
            }.onFailure {
                Log.w("LaermprotokollApp", "Konnte vorherige Prozessbeendigung nicht auslesen", it)
            }
        }
    }
}
