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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
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
     * Abstand zwischen zwei Anlaeufen, nachdem [maxAttempts] erschoepft waren und
     * [ConnectionState.FAILED] gemeldet wurde (F-03).
     *
     * Bis zum 26.09.2026 endete die Ueberwachungsschleife an dieser Stelle endgueltig - eine
     * einmal fehlgeschlagene Verbindung blieb tot, bis der Nutzer selbst eingriff. Owner-
     * Entscheidung 26.09.2026: periodisch weiterversuchen, mit langem Abstand.
     *
     * 15 Minuten, weil der Wert gegen den Akku abgewogen werden muss: die App protokolliert
     * ueber ganze Tage, und jeder Anlauf durchlaeuft wieder die volle Backoff-Folge von acht
     * Versuchen (rund zwei Minuten Funkbetrieb), bevor erneut FAILED gemeldet wird. Bei 15
     * Minuten sind das rund 96 Anlaeufe je Tag; bei 5 Minuten waeren es 288 - fuer ein Geraet,
     * das vielleicht gar nicht in Reichweite ist. Den nutzersichtbaren Fall deckt ohnehin
     * [erneutVersuchen] sofort ab.
     */
    private val failedRetryInterval: Duration = Duration.ofMinutes(15),
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
    /**
     * Maximaler Abstand zwischen zwei Anlaeufen im Leerlauf (Dienst nicht aktiv, F-03 / Befund 6
     * aus Support-Bundles Oktober 2026).
     *
     * Laeuft keine Messung ([dienstAktiv] liefert false) und ist das Geraet nicht erreichbar
     * (z. B. ausgeschaltet oder an einem anderen Smartphone gekoppelt), wuerden periodische
     * 15-Minuten-Anlaeufe im Hintergrund unnoetig Funkressourcen verbrauchen und den Prozess
     * gefaehrden (Pixel 9 Pro LOW_MEMORY-Absturz).
     *
     * Im Leerlauf verlaengert sich der Abstand nach jedem fehlgeschlagenen Anlauf progressiv
     * (15 min -> 30 min -> 60 min), gedeckelt auf diesen Wert. Startet der Dienst oder holt der
     * Nutzer die App in den Vordergrund ([erneutVersuchen]), wird die Wartezeit sofort beendet.
     */
    private val failedRetryIntervalLeerlauf: Duration = Duration.ofHours(1),
    /**
     * Gibt an, ob der Messdienst aktiv ist (z. B. [AudioRecordingService.laeuft]).
     * Default ist `true` fuer Rueckwaertskompatibilitaet in bestehenden Tests.
     */
    private val dienstAktiv: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow(),
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
     * Zaehlt die Bitten um einen sofortigen neuen Anlauf (F-03). Die Warteschleife merkt sich
     * den Stand, bevor sie FAILED setzt, und laeuft los, sobald er sich aendert.
     *
     * **Ein Zaehler, kein [kotlinx.coroutines.flow.SharedFlow]** (Review-Befund 27.09.2026):
     * ein SharedFlow mit `replay = 0` verwirft eine Emission, solange kein Sammler
     * registriert ist - `extraBufferCapacity` puffert nur fuer vorhandene, langsame Sammler.
     * Zwischen `setOverride(FAILED)` und dem Sammler in der Warteschleife liegt aber genau so
     * ein Fenster: ein `onResume()` in diesem Moment haette `tryEmit` mit Erfolg quittiert
     * bekommen, der Anstoss waere trotzdem verfallen und der Nutzer haette bis zu
     * [failedRetryInterval] gewartet. Ein [MutableStateFlow] hat immer einen aktuellen Wert,
     * den ein spaeter hinzukommender Sammler sieht - das Fenster gibt es damit nicht mehr.
     *
     * Der Stand wird vor dem Setzen von FAILED gelesen, nicht danach: nur so zaehlt
     * ausschliesslich, was waehrend dieser Wartezeit angefordert wurde. Mehrfaches Bitten vor
     * dem naechsten Anlauf ist dasselbe wie einmal - mehr als einen Anlauf gleichzeitig gibt
     * es nicht.
     */
    private val anlaufAnforderungen = MutableStateFlow(0L)

    /** Siehe [anlaufAnforderungen]. Darf aus jedem Thread aufgerufen werden. */
    fun erneutVersuchen() {
        // Nur im Wartezustand zaehlen. Sonst wuerde ein Anstoss von vor einer Stunde die
        // naechste Wartezeit ueberspringen - er soll keinen Anlauf von heute vorziehen.
        if (_state.value != ConnectionState.FAILED) return
        anlaufAnforderungen.update { it + 1 }
    }

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
            var consecutiveFailedRounds = 0
            var isFirstAttempt = true
            var connectFailedInRound = 0
            var noFirstFrameInRound = 0
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
                    AttemptOutcome.NeverStreamed(NeverStreamedReason.CONNECT_FAILED)
                }

                when (outcome) {
                    is AttemptOutcome.StreamedStably -> {
                        consecutiveFailures = 0
                        consecutiveFailedRounds = 0
                        connectFailedInRound = 0
                        noFirstFrameInRound = 0
                    }
                    is AttemptOutcome.AdapterOff -> {
                        consecutiveFailures = 0
                        consecutiveFailedRounds = 0
                        connectFailedInRound = 0
                        noFirstFrameInRound = 0
                        isFirstAttempt = true // sofortige Wiederaufnahme ohne Backoff-Wartezeit
                    }
                    is AttemptOutcome.StreamedBriefly, is AttemptOutcome.NeverStreamed -> {
                        if (outcome is AttemptOutcome.NeverStreamed) {
                            when (outcome.reason) {
                                NeverStreamedReason.CONNECT_FAILED -> connectFailedInRound++
                                NeverStreamedReason.NO_FIRST_FRAME -> noFirstFrameInRound++
                            }
                        }
                        consecutiveFailures++
                        if (consecutiveFailures >= maxAttempts) {
                            if (connectFailedInRound > 0) {
                                diagnosticsReporter?.report(
                                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.BLE_CONNECT_FAILED,
                                    component = "ConnectionSupervisor",
                                    operation = "supervise",
                                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                                    message = "Verbindungsaufbau gescheitert ($connectFailedInRound Versuche)",
                                    details = mapOf("anzahl" to connectFailedInRound, "grund" to "CONNECT_FAILED"),
                                )
                            }
                            if (noFirstFrameInRound > 0) {
                                diagnosticsReporter?.report(
                                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.BLE_NO_FIRST_FRAME,
                                    component = "ConnectionSupervisor",
                                    operation = "supervise",
                                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                                    message = "Kein Frame empfangen ($noFirstFrameInRound Versuche)",
                                    details = mapOf("anzahl" to noFirstFrameInRound, "grund" to "NO_FIRST_FRAME"),
                                )
                            }
                            connectFailedInRound = 0
                            noFirstFrameInRound = 0

                            diagnosticLogger?.protokolliere(
                                "Verbindung fehlgeschlagen nach $consecutiveFailures Versuchen - warte auf neuen Anlauf",
                            )
                            // Stand vor dem Setzen von FAILED lesen: ab FAILED darf
                            // [erneutVersuchen] zaehlen, und genau diese Erhoehungen sollen die
                            // Wartezeit beenden - fruehere nicht. Siehe [anlaufAnforderungen].
                            val standVorDemWarten = anlaufAnforderungen.value
                            setOverride(ConnectionState.FAILED)
                            // F-03: Frueher endete die Schleife hier mit return@coroutineScope und
                            // die Verbindung blieb tot, bis der Nutzer eingriff. Jetzt bleibt sie in
                            // FAILED stehen und nimmt einen neuen Anlauf, sobald entweder
                            // die Wartezeit verstrichen ist, jemand [erneutVersuchen] ruft oder
                            // der Dienst gestartet wird - was immer zuerst kommt.
                            consecutiveFailedRounds++
                            val warteDauer = if (dienstAktiv.value) {
                                consecutiveFailedRounds = 0
                                failedRetryInterval
                            } else {
                                val multiplikator = 1 shl (consecutiveFailedRounds - 1).coerceIn(0, 2)
                                val dauer = failedRetryInterval.multipliedBy(multiplikator.toLong())
                                if (dauer > failedRetryIntervalLeerlauf) failedRetryIntervalLeerlauf else dauer
                            }
                            val aufgewacht =
                                withTimeoutOrNull(warteDauer.toMillis()) {
                                    if (!dienstAktiv.value) {
                                        merge(
                                            anlaufAnforderungen.filter { it != standVorDemWarten },
                                            dienstAktiv.filter { it },
                                        ).first()
                                    } else {
                                        anlaufAnforderungen.first { it != standVorDemWarten }
                                    }
                                }
                            val angestossen = aufgewacht != null
                            diagnosticLogger?.protokolliere(
                                if (angestossen) {
                                    "Neuer Anlauf auf Anforderung"
                                } else {
                                    val minuten = warteDauer.toMinutes()
                                    if (minuten > 0) {
                                        "Neuer Anlauf nach $minuten min"
                                    } else {
                                        "Neuer Anlauf nach ${warteDauer.toMillis()} ms"
                                    }
                                },
                            )
                            consecutiveFailures = 0
                            isFirstAttempt = true // kein Backoff vor dem ersten Versuch des neuen Anlaufs
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
     * PR #16) - zaehlt fuer den Fehlschlagszaehler wie NeverStreamed, sonst wuerde ein Geraet,
     * das verbindet, kurz Daten liefert und sofort wieder abbricht, endlos im Sekundentakt neu
     * verbunden, weil jeder Zyklus formal als Erfolg zaehlte und der Backoff nie greift.
     */
    private sealed interface AttemptOutcome {
        data object StreamedStably : AttemptOutcome

        data object StreamedBriefly : AttemptOutcome

        data class NeverStreamed(
            val reason: NeverStreamedReason,
        ) : AttemptOutcome

        data object AdapterOff : AttemptOutcome
    }

    private enum class NeverStreamedReason { CONNECT_FAILED, NO_FIRST_FRAME }

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
            if (reached == null) {
                diagnosticLogger?.protokolliere(
                    "Kein Frame innerhalb von ${staleAfter.toMillis()}ms nach Verbindungsaufbau - Versuch verworfen",
                )
            } else {
                diagnosticLogger?.protokolliere(
                    "Verbindungsaufbau gescheitert ($reached)",
                )
            }
            transport.disconnect()
            return AttemptOutcome.NeverStreamed(
                if (reached == null) NeverStreamedReason.NO_FIRST_FRAME else NeverStreamedReason.CONNECT_FAILED,
            )
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
        if (endReason == StreamEndReason.ADAPTER_OFF) return AttemptOutcome.AdapterOff
        val sessionDuration = Duration.between(streamingStartedAt, now.now())
        return if (sessionDuration >= minStableSession) {
            AttemptOutcome.StreamedStably
        } else {
            diagnosticLogger?.protokolliere(
                "Streaming nach nur ${sessionDuration.toMillis()}ms beendet - zaehlt als Fehlversuch"
            )
            AttemptOutcome.StreamedBriefly
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
