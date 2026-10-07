package com.example.lrmprotokoll.audio

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.diagnose.DiagnosticsReporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Koordiniert die Erkennung und Wiederaufnahme unerwartet beendeter Aufzeichnungen (Befund G / E2).
 *
 * Verhindert Doppelstarts bei schnellen aufeinanderfolgenden [onResume]-Aufrufen und dokumentiert
 * jede Luecke mit Beginn, Ende und Dauer im Diagnoseprotokoll sowie als Hinweiskarte (E1).
 */
class AufzeichnungsFortsetzer(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val levelSampleDao: LevelSampleDao,
    private val diagnosticsReporter: DiagnosticsReporter,
    private val dienstLaeuftProvider: () -> Boolean = { AudioRecordingService.laeuft.value },
    private val kannInVordergrundProvider: () -> Boolean = { kannDienstInDenVordergrund(context, settingsManager) },
    private val serviceStarter: (Context, Intent) -> Unit = { ctx, intent -> ContextCompat.startForegroundService(ctx, intent) },
    private val zeitProvider: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hinweisZeiger: (String) -> Unit = { text -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() },
) {
    private val startInFlight = AtomicBoolean(false)

    /**
     * Suspendierende Kernlogik fuer Bewertung und Fortsetzung der Aufzeichnung.
     */
    suspend fun pruefeUndSetzeFortSuspend(
        quelle: String = "app_geoeffnet",
    ) {
        val dienstLaeuft = dienstLaeuftProvider()
        if (dienstLaeuft) {
            startInFlight.set(false)
            return
        }

        val kannInDenVordergrund = kannInVordergrundProvider()
        val lage = bewerteAufzeichnungsLage(
            monitoringWasActive = settingsManager.monitoringWasActive,
            dienstLaeuft = false,
            kannInDenVordergrund = kannInDenVordergrund,
        )

        when (lage) {
            AufzeichnungsLage.LAEUFT -> {
                startInFlight.set(false)
            }
            AufzeichnungsLage.AUS -> {
                startInFlight.set(false)
            }
            AufzeichnungsLage.SOLL_ABER_NICHT_STARTBAR -> {
                startInFlight.set(false)
                diagnosticsReporter.report(
                    code = DiagnosticCode.PERMISSION_REVOKED_DURING_OPERATION,
                    component = "Aufzeichnung",
                    operation = "pruefeUndSetzeFort",
                    severity = DiagnosticSeverity.WARN,
                    message = "Aufzeichnung soll laufen, kann aber nicht gestartet werden: weder Mikrofonberechtigung noch gekoppeltes Messgeraet",
                    details = mapOf(
                        "quelle" to quelle,
                        "monitoringWasActive" to true,
                    ),
                )
                hinweisZeiger(context.getString(R.string.dienst_start_ohne_quelle))
            }
            AufzeichnungsLage.SOLL_LAEUFT_NICHT -> {
                if (!startInFlight.compareAndSet(false, true)) {
                    // Start ist bereits in flight, Doppelstart verhindern
                    return
                }

                try {
                    val letzteDatenAt = withContext(ioDispatcher) {
                        runCatching { levelSampleDao.maxAt() }.getOrNull()
                    }
                    val entdecktAt = zeitProvider()
                    val lueckeMinuten = if (letzteDatenAt != null && letzteDatenAt < entdecktAt) {
                        (entdecktAt - letzteDatenAt) / (60 * 1000L)
                    } else {
                        0L
                    }

                    diagnosticsReporter.report(
                        code = DiagnosticCode.RECORDING_ENDED_UNEXPECTEDLY,
                        component = "Aufzeichnung",
                        operation = "fortsetzen",
                        severity = DiagnosticSeverity.WARN,
                        message = "Aufzeichnung unerwartet beendet und fortgesetzt",
                        details = mapOf(
                            "letzteDatenAt" to letzteDatenAt,
                            "entdecktAt" to entdecktAt,
                            "lueckeMinuten" to lueckeMinuten,
                            "audioWarAktiv" to settingsManager.audioMonitoringWasActive,
                            "quelle" to quelle,
                        ),
                    )

                    settingsManager.speichereUnterbrechung(beginn = letzteDatenAt, ende = entdecktAt)

                    val serviceIntent = Intent(context, AudioRecordingService::class.java).apply {
                        putExtra(EXTRA_START_AUDIO_MONITORING, settingsManager.audioMonitoringWasActive)
                    }
                    serviceStarter(context, serviceIntent)
                } catch (t: Throwable) {
                    startInFlight.set(false)
                    throw t
                }
            }
        }
    }

    /**
     * Startet die Pruefung asynchron im uebergebenen [scope].
     */
    fun pruefeUndSetzeFort(
        scope: CoroutineScope,
        quelle: String = "app_geoeffnet",
    ) {
        scope.launch {
            pruefeUndSetzeFortSuspend(quelle)
        }
    }

    internal fun resetStartInFlightForTesting() {
        startInFlight.set(false)
    }
}
