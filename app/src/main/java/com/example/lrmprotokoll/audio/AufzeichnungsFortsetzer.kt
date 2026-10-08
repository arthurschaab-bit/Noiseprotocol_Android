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
    private val lebenszyklusRingFile: com.example.lrmprotokoll.diagnose.LebenszyklusRingFile? = null,
    private val dienstLaeuftProvider: () -> Boolean = { AudioRecordingService.laeuft.value },
    private val kannInVordergrundProvider: () -> Boolean = { kannDienstInDenVordergrund(context, settingsManager) },
    private val serviceStarter: (Context, Intent) -> Unit = { ctx, intent -> ContextCompat.startForegroundService(ctx, intent) },
    private val zeitProvider: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hinweisZeiger: (String) -> Unit = { text -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() },
    private val startInFlightTimeoutMs: Long = 5_000L,
) {
    private var letzterStartVersuchAt = 0L
    private var nichtStartbarGemeldet = false
    private val lock = Any()

    /**
     * Suspendierende Kernlogik fuer Bewertung und Fortsetzung der Aufzeichnung.
     */
    suspend fun pruefeUndSetzeFortSuspend(
        quelle: String = "app_geoeffnet",
    ) {
        val dienstLaeuft = dienstLaeuftProvider()
        if (dienstLaeuft) {
            synchronized(lock) {
                letzterStartVersuchAt = 0L
                nichtStartbarGemeldet = false
            }
            return
        }

        val kannInDenVordergrund = kannInVordergrundProvider()
        val lage =
            bewerteAufzeichnungsLage(
                monitoringWasActive = settingsManager.monitoringWasActive,
                dienstLaeuft = false,
                kannInDenVordergrund = kannInDenVordergrund,
            )

        when (lage) {
            AufzeichnungsLage.LAEUFT -> {
                synchronized(lock) {
                    letzterStartVersuchAt = 0L
                    nichtStartbarGemeldet = false
                }
            }
            AufzeichnungsLage.AUS -> {
                synchronized(lock) {
                    letzterStartVersuchAt = 0L
                    nichtStartbarGemeldet = false
                }
            }
            AufzeichnungsLage.SOLL_ABER_NICHT_STARTBAR -> {
                val melden =
                    synchronized(lock) {
                        letzterStartVersuchAt = 0L
                        if (!nichtStartbarGemeldet) {
                            nichtStartbarGemeldet = true
                            true
                        } else {
                            false
                        }
                    }
                if (melden) {
                    diagnosticsReporter.report(
                        code = DiagnosticCode.PERMISSION_REVOKED_DURING_OPERATION,
                        component = "Aufzeichnung",
                        operation = "pruefeUndSetzeFort",
                        severity = DiagnosticSeverity.WARN,
                        message =
                            "Aufzeichnung soll laufen, kann aber nicht gestartet werden: " +
                                "weder Mikrofonberechtigung noch gekoppeltes Messgeraet",
                        details =
                            mapOf(
                                "quelle" to quelle,
                                "monitoringWasActive" to true,
                            ),
                    )
                    hinweisZeiger(context.getString(R.string.dienst_start_ohne_quelle))
                }
            }
            AufzeichnungsLage.SOLL_LAEUFT_NICHT -> {
                val jetzt = zeitProvider()
                synchronized(lock) {
                    nichtStartbarGemeldet = false
                    if (jetzt - letzterStartVersuchAt < startInFlightTimeoutMs) {
                        // Startversuch laeuft noch oder wurde kuerzlich initiiert (Doppelstart-Schutz)
                        return
                    }
                    letzterStartVersuchAt = jetzt
                }

                try {
                    val letzteDatenAt =
                        withContext(ioDispatcher) {
                            runCatching { levelSampleDao.maxAt() }.getOrNull()
                        }
                    val entdecktAt = jetzt
                    val lueckeMinuten =
                        if (letzteDatenAt != null && letzteDatenAt < entdecktAt) {
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
                        details =
                            mapOf(
                                "letzteDatenAt" to letzteDatenAt,
                                "entdecktAt" to entdecktAt,
                                "lueckeMinuten" to lueckeMinuten,
                                "audioWarAktiv" to settingsManager.audioMonitoringWasActive,
                                "quelle" to quelle,
                            ),
                    )

                    lebenszyklusRingFile?.protokolliere(
                        ereignis = "Unerwartetes Ende erkannt",
                        details =
                            mapOf(
                                "letzteDatenAt" to letzteDatenAt,
                                "lueckeMinuten" to lueckeMinuten,
                                "quelle" to quelle,
                            ),
                        timestampMillis = entdecktAt,
                    )

                    settingsManager.speichereUnterbrechung(beginn = letzteDatenAt, ende = entdecktAt)

                    val serviceIntent =
                        Intent(context, AudioRecordingService::class.java).apply {
                            putExtra(EXTRA_START_AUDIO_MONITORING, settingsManager.audioMonitoringWasActive)
                        }
                    serviceStarter(context, serviceIntent)
                } catch (t: Throwable) {
                    synchronized(lock) {
                        letzterStartVersuchAt = 0L
                    }
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
        synchronized(lock) {
            letzterStartVersuchAt = 0L
            nichtStartbarGemeldet = false
        }
    }
}
