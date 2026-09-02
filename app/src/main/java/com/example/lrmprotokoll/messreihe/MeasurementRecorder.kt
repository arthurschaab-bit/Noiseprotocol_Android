package com.example.lrmprotokoll.messreihe

import android.util.Log
import com.example.lrmprotokoll.data.ConnectionEventDao
import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import com.example.lrmprotokoll.data.MeasurementDao
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MeasurementFlags
import com.example.lrmprotokoll.data.SessionDao
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.InstantSource
import com.example.lrmprotokoll.meter.MeterFrame
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MeasurementRecorder"

/**
 * Schreibt die Messreihe fuer M4 (Plan Abschnitt 8.1/8.2): eine [SessionEntity] pro
 * Ueberwachungsperiode, [MeasurementEntity] je Messwert, [ConnectionEventEntity] je
 * Verbindungsaenderung. Kennt nur [ConnectionState] und [MeterFrame] - weder BLE noch Room-
 * Implementierungsdetails ausserhalb der DAOs - und ist damit wie [com.example.lrmprotokoll.alert.AlarmCoordinator]
 * vollstaendig gegen Fakes testbar.
 *
 * Eine Session beginnt mit [start] und endet mit [stop] - NICHT erst beim ersten erfolgreichen
 * Frame. Reconnects *innerhalb* dieser Zeit erzeugen keine neue Session, sondern
 * [ConnectionEventEntity]-Zeilen: Eine Session ist die Klammer um "wie lange wurde ueberwacht",
 * nicht "wie lange stand die Verbindung" - siehe [SessionEntity]-KDoc.
 */
class MeasurementRecorder(
    private val states: Flow<ConnectionState>,
    private val frames: Flow<MeterFrame>,
    private val sessionDao: SessionDao,
    private val measurementDao: MeasurementDao,
    private val connectionEventDao: ConnectionEventDao,
    private val scope: CoroutineScope,
    private val now: InstantSource = InstantSource.System,
    private val flushInterval: Duration = Duration.ofSeconds(5),
    private val flushBatchSize: Int = 50,
) {

    private val pufferMutex = Mutex()
    private val puffer = mutableListOf<MeasurementEntity>()

    private var job: Job? = null

    @Volatile private var aktiveSessionId: Long? = null
    private var warJemalsVerbunden = false
    private var zuletztImAusfall = false

    // Letzter bestaetigter Frame-Kontext, fuer SessionEntity.weighting/timeWeighting/range beim
    // Sitzungsende (SessionEntity-KDoc: "speichert den zuletzt bekannten Wert als Kontext").
    private var letzteBewertung: String? = null
    private var letzteZeitbewertung: String? = null
    private var letzterBereich: String? = null

    /** Die laufende Session-ID, falls [start] bereits eine Session eroeffnet hat - fuer
     * Aufrufer, die Messwerte einer Session zuordnen wollen, ohne selbst Buch zu fuehren. */
    val laufendeSessionId: Long? get() = aktiveSessionId

    /**
     * Eroeffnet eine neue Session fuer [device] und beginnt aufzuzeichnen. Ein zweiter Aufruf,
     * waehrend bereits eine Session laeuft, ist ein No-Op.
     *
     * Die Session wird bewusst VOR dem Start der Beobachtung angelegt (nicht parallel dazu) -
     * sonst koennten sehr fruehe Frames oder Zustandswechsel eintreffen, bevor
     * [aktiveSessionId] gesetzt ist, und wuerden mangels Ziel-Session verworfen.
     */
    fun start(device: BoundDevice) {
        if (job?.isActive == true) return
        job = scope.launch {
            schliesseVerwaisteSessions()

            aktiveSessionId = sessionDao.insert(
                SessionEntity(
                    startedAt = now.now().toEpochMilli(),
                    endedAt = null,
                    deviceAddress = device.address,
                    deviceName = device.name,
                    weighting = null,
                    timeWeighting = null,
                )
            )
            warJemalsVerbunden = false
            zuletztImAusfall = false
            letzteBewertung = null
            letzteZeitbewertung = null
            letzterBereich = null

            coroutineScope {
                launch { states.collect { onState(it) } }
                launch { frames.collect { onFrame(it) } }
                launch {
                    while (isActive) {
                        delay(flushInterval.toMillis())
                        pufferMutex.withLock { flushOhneSperre() }
                    }
                }
            }
        }
    }

    /**
     * Schliesst Sessions, die kein [SessionEntity.endedAt] haben, obwohl sie nicht mehr laufen.
     *
     * Eine Session wird in [start] eroeffnet und in [stop] geschlossen. Stirbt der Prozess
     * dazwischen - Systemkill, Absturz, Akku leer -, laeuft [stop] nie, und die Zeile bleibt
     * dauerhaft ohne Endzeitpunkt. Jede Stelle, die `endedAt == null` als "laeuft gerade" liest
     * (Protokollansicht, [com.example.lrmprotokoll.data.SessionDao.offeneSession], die
     * Ausfallbaender-Ableitung, der Zeitraumbericht), sieht diese Leiche danach fuer immer als
     * aktive Messung an.
     *
     * Als Endzeitpunkt wird der letzte tatsaechlich aufgezeichnete Messwert gesetzt - das ist der
     * letzte Zeitpunkt, fuer den es einen Beleg gibt. Gibt es keinen Messwert, wird
     * [SessionEntity.startedAt] selbst eingetragen: eine Session der Laenge null ist die ehrliche
     * Aussage "begonnen, nie etwas aufgezeichnet". Eine gerundete Mindestdauer waere eine
     * erfundene Messzeit - dieselbe Begruendung, aus der [SessionEntity.weighting] `null` bleibt,
     * solange die Bewertung unbestaetigt ist.
     *
     * Laeuft in [start] und damit vor dem Anlegen der neuen Session: Sobald eine neue Messung
     * beginnt, kann keine frueher eroeffnete mehr laufen.
     */
    private suspend fun schliesseVerwaisteSessions() {
        // Kein Log auf dem Erfolgspfad: Diese Klasse wird in reinen JVM-Tests ohne Robolectric
        // geprueft, und android.util.Log ist dort nicht verfuegbar. Wie im Rest der Datei wird
        // deshalb nur in Fehlerpfaden geloggt, die die Tests nicht durchlaufen.
        val offene = try {
            sessionDao.alleOffenen()
        } catch (e: Throwable) {
            // Aufraeumen darf den Start einer neuen Messung nie verhindern - die Messung ist die
            // Kernaufgabe, das Aufraeumen ist Hygiene.
            Log.e(TAG, "Verwaiste Sessions konnten nicht gelesen werden", e)
            return
        }
        for (session in offene) {
            try {
                val letzterMesswert = measurementDao.fuerSession(session.id).maxOfOrNull { it.timestamp }
                sessionDao.update(session.copy(endedAt = letzterMesswert ?: session.startedAt))
            } catch (e: Throwable) {
                Log.e(TAG, "Verwaiste Session ${session.id} konnte nicht geschlossen werden", e)
            }
        }
    }

    /** Beendet die laufende Session, schreibt den Rest des Puffers sofort weg. */
    fun stop() {
        job?.cancel()
        job = null
        val sessionId = aktiveSessionId
        aktiveSessionId = null
        if (sessionId == null) return

        scope.launch {
            pufferMutex.withLock { flushOhneSperre() }
            val session = sessionDao.byId(sessionId) ?: return@launch
            sessionDao.update(
                session.copy(
                    endedAt = now.now().toEpochMilli(),
                    weighting = letzteBewertung,
                    timeWeighting = letzteZeitbewertung,
                    range = letzterBereich,
                )
            )
        }
    }

    private suspend fun onFrame(frame: MeterFrame) {
        val sessionId = aktiveSessionId ?: return
        // null, solange die jeweilige Annahme unbestaetigt ist - siehe MeasurementEntity-KDoc.
        val bewertung = frame.weighting?.takeIf { frame.modeAssumptionConfirmed }?.name
        val zeitbewertung = frame.timeWeighting?.takeIf { frame.modeAssumptionConfirmed }?.name
        val bereich = frame.range?.takeIf { frame.modeAssumptionConfirmed }?.name
        if (bewertung != null) letzteBewertung = bewertung
        if (zeitbewertung != null) letzteZeitbewertung = zeitbewertung
        if (bereich != null) letzterBereich = bereich

        val eintrag = MeasurementEntity(
            sessionId = sessionId,
            timestamp = frame.receivedAt.toEpochMilli(),
            levelDb = frame.level,
            weighting = bewertung,
            flags = flagsAus(frame),
            timeWeighting = zeitbewertung,
            range = bereich,
        )
        pufferMutex.withLock {
            puffer += eintrag
            if (puffer.size >= flushBatchSize) flushOhneSperre()
        }
    }

    private fun flagsAus(frame: MeterFrame): Int {
        var flags = 0
        if (frame.holdMax == true) flags = flags or MeasurementFlags.HOLD_MAX
        if (frame.holdMin == true) flags = flags or MeasurementFlags.HOLD_MIN
        if (frame.largeJump) flags = flags or MeasurementFlags.LARGE_JUMP
        return flags
    }

    private suspend fun flushOhneSperre() {
        if (puffer.isEmpty()) return
        val zuSchreiben = puffer.toList()
        puffer.clear()
        runCatching { measurementDao.insertAll(zuSchreiben) }
            .onFailure { Log.w(TAG, "Konnte ${zuSchreiben.size} Messwerte nicht schreiben", it) }
    }

    private suspend fun onState(zustand: ConnectionState) {
        val sessionId = aktiveSessionId ?: return

        when {
            zustand == ConnectionState.STREAMING -> {
                val typ = when {
                    !warJemalsVerbunden -> ConnectionEventType.CONNECTED
                    zuletztImAusfall -> ConnectionEventType.RECOVERED
                    else -> null // bereits verbunden, kein Ereignis
                }
                warJemalsVerbunden = true
                zuletztImAusfall = false
                if (typ != null) protokolliere(sessionId, typ, reason = null)
            }

            // Ohne den !zuletztImAusfall-Schutz wuerde jeder Zwischenzustand innerhalb EINER
            // zusammenhaengenden Ausfallperiode (z.B. DISCONNECTED -> RECONNECTING -> FAILED)
            // eine eigene Zeile erzeugen. Plan 8.1 kennt aber nur vier Ereignistypen, keinen je
            // ConnectionState - eine Ausfallperiode ist im Verlauf EIN rotes Band (Plan 9), nicht
            // mehrere ueberlappende.
            zustand == ConnectionState.DEGRADED && !zuletztImAusfall -> {
                zuletztImAusfall = true
                protokolliere(sessionId, ConnectionEventType.DEGRADED, reason = "Datenstillstand")
            }

            istHarterAusfall(zustand) && !zuletztImAusfall -> {
                zuletztImAusfall = true
                protokolliere(sessionId, ConnectionEventType.DISCONNECTED, reason = zustand.name)
            }

            // SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, IDLE: reine Zwischen-/Ruhezustaende,
            // erzeugen kein eigenes Ereignis.
            else -> Unit
        }
    }

    private fun istHarterAusfall(zustand: ConnectionState): Boolean = when (zustand) {
        ConnectionState.DISCONNECTED,
        ConnectionState.RECONNECTING,
        ConnectionState.FAILED -> true
        else -> false
    }

    private suspend fun protokolliere(sessionId: Long, typ: String, reason: String?) {
        runCatching {
            connectionEventDao.insert(
                ConnectionEventEntity(sessionId = sessionId, at = now.now().toEpochMilli(), type = typ, reason = reason)
            )
        }.onFailure { Log.w(TAG, "Konnte Verbindungsereignis $typ nicht schreiben", it) }
    }
}
