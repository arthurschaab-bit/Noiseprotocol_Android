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
import io.sentry.android.core.SentryAndroid

class LaermprotokollApp : Application() {

    lateinit var container: AppContainer
        internal set

    fun setCustomContainer(customContainer: AppContainer) {
        container = customContainer
    }

    fun resetContainer() {
        container = AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        initSentry()
        container = AppContainer(this)
        checkPreviousProcessExit()
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
