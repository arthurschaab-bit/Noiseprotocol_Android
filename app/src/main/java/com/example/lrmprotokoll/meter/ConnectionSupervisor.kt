package com.example.lrmprotokoll.meter

import android.util.Log
import com.example.lrmprotokoll.diagnose.DiagnosticLogger
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ConnectionSupervisor"

/** Zeitquelle als Funktionstyp statt direktem `Instant.now()` (PROMPT_M3 Aufgabe 1) - sonst
 * sind die Backoff-/Staleness-Tests nicht deterministisch testbar und dauern in echter Zeit. */
fun interface InstantSource {
    fun now(): Instant

    companion object {
        val System = InstantSource { Instant.now() }
    }
}

private val BACKOFF_STEPS_SECONDS = longArrayOf(1, 2, 4, 8, 16, 30)
private const val BACKOFF_CONSTANT_SECONDS = 60L
private const val BACKOFF_JITTER_FRACTION = 0.2
private const val MIN_SAMPLES_FOR_ERROR_RATE = 5

/**
 * Zwei aufeinanderfolgende Kadenz-Abweichungen, nicht eine - ein einzelner verspaeteter
 * OS-Scheduler-Tick darf keinen Reconnect ausloesen (dasselbe Prinzip wie
 * [MIN_SAMPLES_FOR_ERROR_RATE] bei der Fehlerrate: kein Einzelwert reicht).
 */
private const val MIN_CADENCE_VIOLATIONS = 2

/**
 * Treibt den Verbindungs-Zustandsautomaten aus Plan Abschnitt 5.1 ueber die reine
 * [MeterTransport]-Schnittstelle - kennt NICHT [com.example.lrmprotokoll.meter.ble.BleMeterTransport],
 * nur so bleibt sie vollstaendig gegen [FakeMeterTransport] testbar (PROMPT_M3 Aufgabe 1).
 *
 * Vier unabhaengige Ausfallsignale (Plan Abschnitt 7.1) fuehren zu proaktivem Reconnect:
 * GATT-Disconnect (direkt von [transport]), Staleness (kein Frame seit [staleAfter]),
 * Bluetooth-Adapter aus ([adapterEnabled]) und Fehlerrate ueber [errorRateWindow] > [errorRateThreshold].
 *
 * [maxAttempts] = 8: durchlaeuft die komplette Backoff-Folge (1,2,4,8,16,30 s) einmal vollstaendig
 * plus zwei weitere Versuche im konstanten 60-s-Takt, bevor [ConnectionState.FAILED] gemeldet wird -
 * in Summe rund zwei Minuten Wartezeit ueber acht Versuche. Kein Wert aus Plan/Prompt vorgegeben,
 * eigene Abwaegung (nicht einer der sieben in Plan Abschnitt 13 als offen markierten Punkte).
 * Ein Reconnect NACH mindestens [minStableSession] erfolgreich gestreamter Zeit setzt den
 * Zaehler zurueck - eine flatternde, aber grundsaetzlich erreichbare Verbindung darf nie FAILED
 * ausloesen. Eine Session UNTER [minStableSession] zaehlt dagegen wie ein Fehlschlag (Review-
 * Befund 3, PR #16): ohne diese Schwelle wuerde ein Geraet, das verbindet, ein Frame liefert
 * und sofort wieder abbricht, endlos im Sekundentakt neu verbunden - der Backoff wuerde nie
 * greifen, weil jeder Zyklus formal als Erfolg zaehlte. Genau dieses Dauerzyklus-Muster ist es,
 * gegen das Aufgabe 5 (gatt.close() bei spontanem Disconnect) in diesem PR die status-133-
 * Kaskade nach Plan 5.3 vermeiden soll.
 */
class ConnectionSupervisor(
    private val transport: MeterTransport,
    private val scope: CoroutineScope,
    private val adapterEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow(),
    private val now: InstantSource = InstantSource.System,
    private val random: Random = Random.Default,
    private val staleAfter: Duration = Duration.ofSeconds(5),
    private val errorRateWindow: Duration = Duration.ofSeconds(30),
    private val errorRateThreshold: Double = 0.2,
    private val maxAttempts: Int = 8,
    private val minStableSession: Duration = Duration.ofSeconds(5),
    /**
     * Stream-Plausibilisierung als Spoofing-Erkennung (Plan Abschnitt 6): `null` (Default)
     * schaltet die Pruefung ab - ohne eine geraetespezifische Erwartung (nur AppContainer kennt
     * [com.example.lrmprotokoll.meter.ble.Pce323Profile.EXPECTED_FRAME_PERIOD_MS], diese Klasse
     * bleibt bewusst frei von BLE-Details) gibt es nichts, wogegen zu pruefen waere. Ist ein Wert
     * gesetzt, muss die Zeit zwischen zwei Frames innerhalb von ±[cadenceTolerance] liegen -
     * wiederholte Abweichung (Framing/Kadenz eines untergeschobenen Geraets liesse sich kaum
     * exakt nachbilden) trennt die Verbindung wie ein Datenstillstand.
     *
     * ⚠ Der Default hier (0.2) ist bewusst NICHT der Produktionswert - AppContainer setzt ihn
     * explizit hoeher (Owner-Entscheidung nach Geraetetest, siehe Diagnose-Log: reale Deltas
     * schwankten zwischen ~180ms und ~630ms um die erwarteten 515ms, deutlich mehr als ±20%
     * Jitter, und loesten dadurch fast bei jedem Reconnect faelschlich DEGRADED aus). Bestehende
     * Tests pruefen weiterhin explizit gegen diesen 0.2-Default, deshalb bleibt er hier
     * unveraendert.
     */
    private val expectedFramePeriod: Duration? = null,
    private val cadenceTolerance: Double = 0.2,
    /**
     * Optionale Senke fuer das Diagnose-Log (Plan Abschnitt 6, standardmaessig aus - siehe
     * [DiagnosticLogger]-KDoc). `null` (Default) haelt bestehende Aufrufer/Tests unveraendert;
     * [DiagnosticLogger] selbst prueft bei jedem Aufruf erneut, ob das Log ueberhaupt
     * eingeschaltet ist, ein `null` hier ist also nur eine zusaetzliche, gaenzlich kostenlose
     * Abkuerzung fuer Aufrufer, die gar keine Senke haben (z.B. Tests).
     */
    private val diagnosticLogger: DiagnosticLogger? = null,
    private val diagnosticsReporter: com.example.lrmprotokoll.diagnose.DiagnosticsReporter? = null,
) {
    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * Hat Vorrang vor dem vom [transport] gespiegelten Zustand (Review-Befund 1, PR #16): ohne
     * das wuerde z.B. DEGRADED durch das eigene transport.disconnect() direkt danach binnen
     * kuerzester Zeit vom forwarder auf DISCONNECTED ueberschrieben, bevor ein Beobachter auf
     * einem anderen Dispatcher (Notification, MeterScreen) es je sieht - StateFlow konflatiert,
     * ein langsamer Collector wuerde den Zwischenwert schlicht verpassen. Wird in [attemptOnce]
     * bei jedem neuen Verbindungsversuch geloescht, danach wird der Transport-Zustand wieder
     * normal durchgereicht.
     */
    private var supervisorOverride: ConnectionState? = null

    private fun publish(fromTransport: ConnectionState) {
        _state.value = supervisorOverride ?: fromTransport
    }

    private fun setOverride(value: ConnectionState) {
        supervisorOverride = value
        _state.value = value
    }

    private fun clearOverride() {
        supervisorOverride = null
    }

    private var job: Job? = null
    private var currentDevice: BoundDevice? = null

    /**
     * (Re-)Startet die Ueberwachung fuer [device]. Ein Aufruf fuer das bereits aktiv
     * ueberwachte Geraet ist ein No-Op - sowohl [com.example.lrmprotokoll.audio.AudioRecordingService]
     * (beim eigenen Start) als auch die UI (beim Koppeln) rufen [start] auf, ohne sich
     * abzustimmen; ohne diese Absicherung wuerde der zweite Aufruf eine laufende Verbindung
     * unnoetig neu aufbauen.
     */
    fun start(device: BoundDevice) {
        if (job?.isActive == true && currentDevice == device) return
        currentDevice = device
        job?.cancel()
        clearOverride() // ein erneuter Start darf keinen alten FAILED/DEGRADED-Override erben
        job = scope.launch { supervise(device) }
    }

    /** Beendet die Ueberwachung und trennt die Verbindung. */
    fun stop() {
        job?.cancel()
        job = null
        currentDevice = null
        clearOverride()
        scope.launch { runCatching { transport.disconnect() } }
        _state.value = ConnectionState.IDLE
    }

    private suspend fun supervise(device: BoundDevice): Unit = coroutineScope {
        // Spiegelt die feingranularen Zwischenzustaende des Transports (CONNECTING,
        // DISCOVERING, SUBSCRIBING, STREAMING, DISCONNECTED, FAILED) in [_state], solange kein
        // [supervisorOverride] aktiv ist.
        val forwarder = launch { transport.state.collectLatest { publish(it) } }
        try {
            var consecutiveFailures = 0
            var isFirstAttempt = true
            while (isActive) {
                if (!adapterEnabled.value) {
                    setOverride(ConnectionState.DISCONNECTED)
                    diagnosticLogger?.protokolliere("Bluetooth-Adapter aus - Ueberwachung pausiert")
                    adapterEnabled.first { it } // pausiert, bis der Adapter wieder an ist
                    diagnosticLogger?.protokolliere("Bluetooth-Adapter wieder an - Ueberwachung wird fortgesetzt")
                }

                if (!isFirstAttempt) {
                    setOverride(ConnectionState.RECONNECTING)
                    val backoffMillis = backoffDelayMillis(consecutiveFailures)
                    diagnosticLogger?.protokolliere(
                        "Reconnect-Versuch $consecutiveFailures nach ${backoffMillis}ms Backoff"
                    )
                    delay(backoffMillis)
                    if (!adapterEnabled.value) continue // waehrend der Wartezeit wieder ausgeschaltet
                }
                isFirstAttempt = false

                // Review-Befund 2 (PR #16): eine unerwartete Exception aus dem Transport (z.B.
                // DeadObjectException beim Neustart des Bluetooth-Stacks, IllegalStateException
                // aus getRemoteDevice() bei ungueltig gewordener Adresse) darf die Ueberwachung
                // nicht lautlos beenden - ohne Fang wuerde die Coroutine sterben, _state auf dem
                // letzten Wert einfrieren und nie wieder FAILED oder ein Retry melden. Zaehlt
                // hier bewusst wie ein normaler Fehlschlag statt eigener Fehlerbehandlung.
                val outcome = try {
                    attemptOnce(device, consecutiveFailures)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Verbindungsversuch mit Ausnahme gescheitert", e)
                    diagnosticLogger?.protokolliere("Verbindungsversuch gescheitert: ${e.javaClass.simpleName}: ${e.message}")
                    runCatching { transport.disconnect() }
                    AttemptOutcome.NEVER_STREAMED
                }

                when (outcome) {
                    AttemptOutcome.STREAMED_STABLY -> consecutiveFailures = 0
                    AttemptOutcome.ADAPTER_OFF -> {
                        consecutiveFailures = 0
                        isFirstAttempt = true // sofortige Wiederaufnahme ohne Backoff-Wartezeit
                    }
                    AttemptOutcome.STREAMED_BRIEFLY, AttemptOutcome.NEVER_STREAMED -> {
                        consecutiveFailures++
                        if (consecutiveFailures >= maxAttempts) {
                            diagnosticLogger?.protokolliere(
                                "Verbindung endgueltig fehlgeschlagen nach $consecutiveFailures Versuchen"
                            )
                            setOverride(ConnectionState.FAILED)
                            return@coroutineScope
                        }
                    }
                }
            }
        } finally {
            forwarder.cancel()
        }
    }

    /**
     * STREAMED_BRIEFLY: die Session hat [minStableSession] nicht erreicht (Review-Befund 3,
     * PR #16) - zaehlt fuer den Fehlschlagszaehler wie NEVER_STREAMED, sonst wuerde ein Geraet,
     * das verbindet, kurz Daten liefert und sofort wieder abbricht, endlos im Sekundentakt neu
     * verbunden, weil jeder Zyklus formal als Erfolg zaehlte und der Backoff nie greift.
     */
    private enum class AttemptOutcome { STREAMED_STABLY, STREAMED_BRIEFLY, NEVER_STREAMED, ADAPTER_OFF }

    private enum class StreamEndReason { LOST, ADAPTER_OFF }

    private suspend fun attemptOnce(device: BoundDevice, consecutiveFailuresSoFar: Int): AttemptOutcome {
        clearOverride() // ein neuer Versuch beginnt, ein alter Override-Zustand ist ueberholt
        transport.connect(device)
        // transport.connect() kehrt erst zurueck, wenn der GATT-Aufbau (inkl. eigener Timeouts
        // in GattQueue/BleMeterTransport) abgeschlossen ist - hier faengt nur noch das Warten
        // auf das ERSTE Frame nach erfolgreichem CCCD-Write an. Eine stehende Verbindung ohne
        // Datenfluss zaehlt als Ausfall, nicht als Erfolg (Plan Abschnitt 5.1).
        val reached = withTimeoutOrNull(staleAfter.toMillis()) {
            transport.state.first {
                it == ConnectionState.STREAMING || it == ConnectionState.FAILED || it == ConnectionState.DISCONNECTED
            }
        }
        if (reached != ConnectionState.STREAMING) {
            diagnosticLogger?.protokolliere(
                "Kein Frame innerhalb von ${staleAfter.toMillis()}ms nach Verbindungsaufbau - Versuch verworfen"
            )
            transport.disconnect()
            return AttemptOutcome.NEVER_STREAMED
        }
        // Sofort beim Erreichen von STREAMING protokolliert, nicht erst nach Sitzungsende (Plan/
        // Owner-Wunsch: die Wiederherstellung soll sichtbar sein, sobald sie passiert, nicht erst
        // rueckwirkend beim naechsten Abbruch) - deshalb hier bereits mit dem Fehlschlagszaehler
        // VOR diesem Versuch, nicht erst im STREAMED_STABLY-Zweig unten.
        diagnosticLogger?.protokolliere(
            if (consecutiveFailuresSoFar > 0) {
                "Verbindung nach $consecutiveFailuresSoFar Fehlversuch(en) wiederhergestellt"
            } else {
                "Verbunden, Streaming gestartet"
            }
        )
        val streamingStartedAt = now.now()
        val endReason = monitorStreamingSession()
        if (endReason == StreamEndReason.ADAPTER_OFF) return AttemptOutcome.ADAPTER_OFF
        val sessionDuration = Duration.between(streamingStartedAt, now.now())
        return if (sessionDuration >= minStableSession) {
            AttemptOutcome.STREAMED_STABLY
        } else {
            diagnosticLogger?.protokolliere(
                "Streaming nach nur ${sessionDuration.toMillis()}ms beendet - zaehlt als Fehlversuch"
            )
            AttemptOutcome.STREAMED_BRIEFLY
        }
    }

    private suspend fun monitorStreamingSession(): StreamEndReason = coroutineScope {
        val done = CompletableDeferred<StreamEndReason>()

        val disconnectWatcher = launch {
            transport.state.first { it == ConnectionState.DISCONNECTED || it == ConnectionState.FAILED }
            // done.complete() gibt false zurueck, wenn ein anderer Watcher (Staleness/Fehlerrate/
            // Kadenz) das Ergebnis bereits gesetzt hat - dessen eigener transport.disconnect()
            // fuehrt kurz danach ebenfalls zu genau diesem DISCONNECTED/FAILED-Zustand. Ohne diese
            // Pruefung wuerde ein selbst ausgeloester Abbruch faelschlich zusaetzlich als "vom
            // Geraet/System beendet" protokolliert, obwohl die App selbst getrennt hat.
            if (done.complete(StreamEndReason.LOST)) {
                diagnosticLogger?.protokolliere("Verbindung vom Geraet/System beendet (Transport meldete Abbruch)")
            }
        }

        // Watchdog-Timer: jedes neue lastFrameAt bricht den laufenden delay() ab und startet ihn
        // neu (collectLatest). Laeuft er ab, ist seit staleAfter kein Frame mehr angekommen.
        val stalenessWatcher = launch {
            transport.lastFrameAt.collectLatest { last ->
                if (last == null) return@collectLatest
                delay(staleAfter.toMillis())
                setOverride(ConnectionState.DEGRADED)
                val msg = "DEGRADED: Datenstillstand seit ${staleAfter.toMillis()}ms"
                diagnosticLogger?.protokolliere(msg)
                diagnosticsReporter?.report(
                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.BLE_STREAM_STALLED,
                    component = "ConnectionSupervisor",
                    operation = "stalenessWatcher",
                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                    message = msg
                )
                transport.disconnect()
                done.complete(StreamEndReason.LOST)
            }
        }

        // Gleitendes Fenster statt absoluter Zaehler (PROMPT_M3-Warnung): totalFrames/errorFrames
        // in transport.frameQuality werden bei jedem Reconnect zurueckgesetzt. Ein absoluter
        // Vergleich wuerde direkt nach jedem Reconnect auf winzigen Stichproben eine instabile
        // Rate liefern und ggf. sofort den naechsten Reconnect ausloesen - deshalb hier ein
        // eigenes, zeitbasiertes Fenster ueber [now], das nur Deltas innerhalb der letzten
        // [errorRateWindow] betrachtet.
        val errorRateWatcher = launch {
            val window = ArrayDeque<Pair<Instant, FrameQuality>>()
            transport.frameQuality.collect { quality ->
                val t = now.now()
                window.addLast(t to quality)
                while (window.size > 1 && Duration.between(window.first().first, t) > errorRateWindow) {
                    window.removeFirst()
                }
                val (_, baseline) = window.first()
                val totalDelta = quality.totalFrames - baseline.totalFrames
                val errorDelta = quality.errorFrames - baseline.errorFrames
                if (totalDelta >= MIN_SAMPLES_FOR_ERROR_RATE) {
                    val rate = errorDelta.toDouble() / totalDelta
                    if (rate > errorRateThreshold) {
                        setOverride(ConnectionState.DEGRADED)
                        val msg = "DEGRADED: Fehlerrate ${(rate * 100).toInt()}% ueber $errorRateWindow"
                        diagnosticLogger?.protokolliere(msg)
                        diagnosticsReporter?.report(
                            code = com.example.lrmprotokoll.diagnose.DiagnosticCode.BLE_DECODE_RATE_HIGH,
                            component = "ConnectionSupervisor",
                            operation = "errorRateWatcher",
                            severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                            message = msg
                        )
                        transport.disconnect()
                        done.complete(StreamEndReason.LOST)
                    }
                }
            }
        }

        val adapterWatcher = launch {
            adapterEnabled.first { !it }
            transport.disconnect()
            done.complete(StreamEndReason.ADAPTER_OFF)
        }

        // Kadenz-Watcher (Plan Abschnitt 6, Stream-Plausibilisierung): misst die Zeit zwischen
        // zwei Frame-Ankuenften ueber [now] statt ueber die im Frame mitgelieferte Zeit - wie
        // errorRateWatcher oben, damit die Pruefung synchron zur injizierten Uhr laeuft und in
        // Tests ueber advanceTimeBy steuerbar ist, unabhaengig davon, welche Zeit der Transport
        // selbst in lastFrameAt eintraegt.
        val cadenceWatcher = expectedFramePeriod?.let { erwartet ->
            launch {
                var vorherigeAnkunft: Instant? = null
                var abweichungenInFolge = 0
                transport.lastFrameAt.collect { letzter ->
                    if (letzter == null) return@collect
                    val ankunft = now.now()
                    val vorherige = vorherigeAnkunft
                    vorherigeAnkunft = ankunft
                    if (vorherige == null) return@collect

                    val deltaMillis = Duration.between(vorherige, ankunft).toMillis()
                    val minMillis = (erwartet.toMillis() * (1 - cadenceTolerance)).toLong()
                    val maxMillis = (erwartet.toMillis() * (1 + cadenceTolerance)).toLong()
                    if (deltaMillis < minMillis || deltaMillis > maxMillis) {
                        abweichungenInFolge++
                        if (abweichungenInFolge >= MIN_CADENCE_VIOLATIONS) {
                            Log.w(
                                TAG,
                                "Framekadenz ${deltaMillis}ms wiederholt ausserhalb der Toleranz " +
                                    "[$minMillis, $maxMillis]ms - moeglicher Spoofing-Verdacht",
                            )
                            val msg = "DEGRADED: Framekadenz ${deltaMillis}ms wiederholt ausserhalb [$minMillis, $maxMillis]ms"
                            setOverride(ConnectionState.DEGRADED)
                            diagnosticLogger?.protokolliere(msg)
                            diagnosticsReporter?.report(
                                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.BLE_CADENCE_INVALID,
                                component = "ConnectionSupervisor",
                                operation = "cadenceWatcher",
                                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                                message = msg
                            )
                            transport.disconnect()
                            done.complete(StreamEndReason.LOST)
                        }
                    } else {
                        abweichungenInFolge = 0
                    }
                }
            }
        }

        val result = done.await()
        disconnectWatcher.cancel()
        stalenessWatcher.cancel()
        errorRateWatcher.cancel()
        adapterWatcher.cancel()
        cadenceWatcher?.cancel()
        result
    }

    private fun backoffDelayMillis(consecutiveFailures: Int): Long {
        require(consecutiveFailures >= 0) { "consecutiveFailures kann nicht negativ sein" }
        // consecutiveFailures == 0 tritt nach einem STREAMED_THEN_LOST-Reset auf (Geraet war
        // gerade noch erreichbar) - das bekommt bewusst denselben ersten Backoff-Schritt wie
        // der allererste echte Fehlschlag, statt sofort ohne Wartezeit erneut zu verbinden:
        // sonst wuerde schnelles Flattern (Plan 7.1, PROMPT_M3-Testfall) zu einem Reconnect-
        // Sturm ohne jede Verzoegerung fuehren.
        val stepIndex = (consecutiveFailures - 1).coerceAtLeast(0)
        val baseSeconds = BACKOFF_STEPS_SECONDS.getOrElse(stepIndex) { BACKOFF_CONSTANT_SECONDS }
        val jitter = 1.0 + random.nextDouble(-BACKOFF_JITTER_FRACTION, BACKOFF_JITTER_FRACTION)
        return (baseSeconds * 1000 * jitter).toLong().coerceAtLeast(0)
    }
}
