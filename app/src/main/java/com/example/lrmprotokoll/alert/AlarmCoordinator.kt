package com.example.lrmprotokoll.alert

import android.util.Log
import com.example.lrmprotokoll.data.AlertDao
import com.example.lrmprotokoll.data.AlertEntity
import com.example.lrmprotokoll.data.DeliveryState
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.InstantSource
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AlarmCoordinator"

/**
 * Die Alarmlogik aus Plan Abschnitt 7.2.
 *
 * Kennt nur [ConnectionState] und [AlertChannel] - nicht BLE, nicht SMS, nicht HTTP. Damit ist
 * sie vollstaendig gegen Fakes testbar, und die Entscheidung, welche Kanaele es ueberhaupt gibt,
 * bleibt eine Frage der Verdrahtung (Plan 7.6: der SMS-Kanal muss sich fuer eine
 * Play-Veroeffentlichung entfernen lassen, ohne diese Klasse anzufassen).
 *
 * Der Ablauf in einem Satz: Ein Ausfallsignal oeffnet einen Ausfall in der Datenbank und startet
 * die Karenzzeit; kehrt die Verbindung vorher zurueck, passiert nichts; sonst geht der Alarm
 * ueber alle verfuegbaren Kanaele gleichzeitig raus.
 *
 * **Parallel, nicht als Fallback-Kette.** Naheliegend waere "erst Push, bei Fehlschlag SMS". Das
 * ist hier falsch: Ein Push gilt als erfolgreich, sobald der Server ihn angenommen hat - ob er je
 * ankommt, erfaehrt die App nie. Eine Kette bliebe also genau in dem Fall stumm, fuer den sie
 * gebaut waere. Zwei parallele Alarme sind das kleinere Uebel als ein verpasster.
 */
class AlarmCoordinator(
    private val states: Flow<ConnectionState>,
    private val adapterEnabled: StateFlow<Boolean>,
    private val channels: List<AlertChannel>,
    private val dao: AlertDao,
    private val scheduler: DeadlineScheduler,
    private val scope: CoroutineScope,
    private val now: InstantSource = InstantSource.System,
    private val config: AlarmConfig = AlarmConfig(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * [onState] laeuft auf der Sammel-Coroutine, [onDeadline] dagegen auf dem Weckruf des
     * AlarmManager. Beide lesen denselben offenen Ausfall aus der Datenbank und schreiben ihn
     * zurueck; ohne Serialisierung koennte ein Weckruf, der zeitgleich mit der Rueckkehr der
     * Verbindung eintrifft, einen bereits geschlossenen Ausfall alarmieren.
     */
    private val mutex = Mutex()
    private var job: Job? = null

    /**
     * Ob seit dem Start ueberhaupt schon ein anderer Zustand als IDLE gesehen wurde.
     *
     * Beim Start steht [states] noch auf IDLE, weil die Ueberwachung erst anlaeuft. Wuerde das
     * bereits als "Ueberwachung beendet" zaehlen, wuerde ausgerechnet der Wiederanlauf nach
     * einem Prozess-Tod den gerade wiederaufgenommenen Ausfall sofort still schliessen und die
     * Weckzeit zuruecknehmen - also genau den Alarm verschlucken, fuer den die Wiederaufnahme
     * gebaut ist. IDLE zaehlt deshalb erst, wenn die Ueberwachung nachweislich gelaufen ist.
     */
    private var ueberwachungLief = false

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            wiederaufnehmen()
            states.collect { zustand -> mutex.withLock { onState(zustand) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        ueberwachungLief = false
        scheduler.cancel()
    }

    /** Vom Weckruf des [DeadlineScheduler] aufzurufen. */
    fun onDeadline() {
        scope.launch { mutex.withLock { verarbeiteFaelligkeit() } }
    }

    /** Probenachricht aus den Einstellungen - laeuft an der gesamten Alarmlogik vorbei. */
    suspend fun sendeTest(channelId: ChannelId): Result<Unit> {
        val kanal = channels.firstOrNull { it.id == channelId }
            ?: return Result.failure(IllegalStateException("Kanal $channelId ist nicht eingerichtet"))
        if (!kanal.isAvailable) {
            return Result.failure(IllegalStateException("Kanal $channelId ist nicht verfügbar"))
        }
        val alert = Alert(
            alertId = 0,
            kind = AlertKind.TEST,
            reason = AlertReason.DISCONNECTED,
            since = now.now(),
            message = AlertMessages.formatiere(AlertKind.TEST, AlertReason.DISCONNECTED, now.now(), zone),
        )
        return runCatching { kanal.send(alert).getOrThrow() }
    }

    // ---------------------------------------------------------------- Zustandsuebergaenge

    private suspend fun onState(zustand: ConnectionState) {
        if (zustand != ConnectionState.IDLE) ueberwachungLief = true

        when {
            zustand == ConnectionState.IDLE -> if (ueberwachungLief) beendeUeberwachung()
            zustand == ConnectionState.STREAMING -> onVerbindungZurueck()
            istAusfall(zustand) -> onAusfall(zustand)
            // SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING: Uebergaenge. Sie beenden einen
            // laufenden Ausfall NICHT - erst ein tatsaechlich fliessender Datenstrom tut das.
            // Waehrend eines Reconnects durchlaeuft der Supervisor genau diese Zustaende, und
            // wuerden sie als Entwarnung zaehlen, koennte ein Geraet, das sich verbindet aber
            // nie Daten liefert, den Alarm dauerhaft verhindern.
            else -> Unit
        }
    }

    private fun istAusfall(zustand: ConnectionState): Boolean = when (zustand) {
        ConnectionState.DEGRADED,
        ConnectionState.DISCONNECTED,
        ConnectionState.RECONNECTING,
        ConnectionState.FAILED -> true

        else -> false
    }

    /**
     * Der Supervisor meldet einen abgeschalteten Bluetooth-Adapter als DISCONNECTED und ist von
     * einem echten Abbruch damit nicht zu unterscheiden. Die Adapter-Lage wird deshalb direkt
     * mitgelesen - ein Alarm "Bluetooth aus" ist fuer den Nutzer etwas voellig anderes als
     * "Messgerät nicht erreichbar", und die falsche Ursache schickt ihn in die falsche Richtung.
     */
    private fun grundFuer(zustand: ConnectionState): AlertReason = when {
        !adapterEnabled.value -> AlertReason.ADAPTER_OFF
        zustand == ConnectionState.FAILED -> AlertReason.RECONNECT_EXHAUSTED
        zustand == ConnectionState.DEGRADED -> AlertReason.STALE
        else -> AlertReason.DISCONNECTED
    }

    private suspend fun onAusfall(zustand: ConnectionState) {
        val grund = grundFuer(zustand)
        val offen = dao.offenerAusfall()

        if (offen == null) {
            val beginn = now.now()
            dao.insert(AlertEntity(outageSince = beginn.toEpochMilli(), reason = grund.name))
            scheduler.schedule(beginn.plus(config.grace))
            return
        }

        // Derselbe Ausfall, aber die Ursache hat sich praezisiert (z.B. RECONNECTING -> FAILED).
        // Die Karenzzeit laeuft dabei weiter: Es ist ein Ausfall, kein neuer.
        if (offen.reason != grund.name) {
            dao.update(offen.copy(reason = grund.name))
        }
    }

    private suspend fun onVerbindungZurueck() {
        scheduler.cancel()
        val offen = dao.offenerAusfall() ?: return
        val geschlossen = offen.copy(resolvedAt = now.now().toEpochMilli())
        dao.update(geschlossen)

        // Entwarnung nur, wenn ueberhaupt alarmiert wurde. Ein Ausfall, der innerhalb der
        // Karenzzeit vorbei war, hat den Nutzer nie erreicht - eine Entwarnung dafuer waere
        // die erste Nachricht ueber ein Problem, das es nicht mehr gibt.
        if (offen.raisedAt == null) return

        val kanaele = channels.filter { it.id in config.entwarnungUeber && it.isAvailable }
        if (kanaele.isEmpty()) return
        versende(baueAlert(geschlossen, AlertKind.RESOLVED), kanaele)
    }

    /** Die Ueberwachung wurde beendet - ein offener Ausfall wird still geschlossen, ohne Alarm. */
    private suspend fun beendeUeberwachung() {
        scheduler.cancel()
        val offen = dao.offenerAusfall() ?: return
        dao.update(offen.copy(resolvedAt = now.now().toEpochMilli()))
    }

    // ---------------------------------------------------------------- Faelligkeit

    private suspend fun verarbeiteFaelligkeit() {
        val offen = dao.offenerAusfall() ?: return
        if (offen.raisedAt == null) loeseAus(offen) else eskaliere(offen)
    }

    private suspend fun loeseAus(offen: AlertEntity) {
        val jetzt = now.now()

        // Cooldown: Der vorherige Alarm liegt noch keine 30 min zurueck. Nicht verwerfen,
        // sondern auf das Ende des Cooldowns verschieben - ein Ausfall, der zufaellig in einen
        // Cooldown faellt, darf nicht dauerhaft stumm bleiben.
        val letzter = dao.letzterAlarmZeitpunkt()?.let(Instant::ofEpochMilli)
        if (letzter != null) {
            val frei = letzter.plus(config.cooldown)
            if (jetzt.isBefore(frei)) {
                scheduler.schedule(frei)
                return
            }
        }

        val ausgeloest = offen.copy(raisedAt = jetzt.toEpochMilli())
        val ergebnis = versende(baueAlert(ausgeloest, AlertKind.RAISED), verfuegbareKanaele())
        dao.update(
            ausgeloest.copy(
                attempts = offen.attempts + 1,
                recipients = ergebnis.kanaele,
                deliveryState = ergebnis.zustand,
            )
        )

        if (config.maxEscalations > 0) {
            scheduler.schedule(jetzt.plus(config.escalateAfter))
        }
    }

    private suspend fun eskaliere(offen: AlertEntity) {
        if (offen.escalations >= config.maxEscalations) return

        val ergebnis = versende(baueAlert(offen, AlertKind.ESCALATED), verfuegbareKanaele())
        val neu = offen.copy(
            escalations = offen.escalations + 1,
            attempts = offen.attempts + 1,
            recipients = ergebnis.kanaele,
            deliveryState = ergebnis.zustand,
        )
        dao.update(neu)

        if (neu.escalations < config.maxEscalations && offen.raisedAt != null) {
            val basis = Instant.ofEpochMilli(offen.raisedAt)
            scheduler.schedule(basis.plus(config.escalateAfter.multipliedBy((neu.escalations + 1).toLong())))
        }
    }

    /**
     * Nimmt einen Ausfall wieder auf, der einen Prozess-Tod ueberlebt hat.
     *
     * Ohne diesen Schritt waere jeder Alarm verloren, dessen Karenzzeit gerade lief, als das
     * System die App abgeschossen hat - und das ist kein Randfall, sondern genau die Situation,
     * in der Hersteller-ROMs zuschlagen.
     */
    private suspend fun wiederaufnehmen() = mutex.withLock {
        val offen = dao.offenerAusfall() ?: return@withLock
        val jetzt = now.now()

        // Nach einem Neustart muss die Verbindung erst wieder aufgebaut werden. Sofort zu
        // alarmieren, nur weil die urspruengliche Karenzzeit inzwischen abgelaufen ist, wuerde
        // einen Fehlalarm ausloesen, sobald das Geraet in Reichweite ist.
        val fruehestens = jetzt.plus(config.neustartNachfrist)
        val faellig = if (offen.raisedAt == null) {
            Instant.ofEpochMilli(offen.outageSince).plus(config.grace)
        } else {
            Instant.ofEpochMilli(offen.raisedAt)
                .plus(config.escalateAfter.multipliedBy((offen.escalations + 1).toLong()))
        }
        scheduler.schedule(maxOf(faellig, fruehestens))
    }

    // ---------------------------------------------------------------- Versand

    private fun verfuegbareKanaele(): List<AlertChannel> = channels.filter { it.isAvailable }

    private fun baueAlert(entity: AlertEntity, kind: AlertKind): Alert {
        val grund = runCatching { AlertReason.valueOf(entity.reason) }
            .getOrDefault(AlertReason.DISCONNECTED)
        val seit = Instant.ofEpochMilli(entity.outageSince)
        return Alert(
            alertId = entity.id,
            kind = kind,
            reason = grund,
            since = seit,
            message = AlertMessages.formatiere(kind, grund, seit, zone),
        )
    }

    private data class VersandErgebnis(val kanaele: String, val zustand: String)

    /**
     * Versendet ueber alle uebergebenen Kanaele gleichzeitig. Jeder Kanal wird einzeln
     * abgesichert: Wirft oder scheitert einer, versenden die anderen trotzdem. Ein Alarmsystem,
     * bei dem ein kaputter Kanal die uebrigen mitreisst, ist schlechter als eines mit nur einem
     * Kanal, weil es diese Eigenschaft verschweigt.
     */
    private suspend fun versende(alert: Alert, kanaele: List<AlertChannel>): VersandErgebnis {
        if (kanaele.isEmpty()) {
            Log.w(TAG, "Alarm ${alert.kind} ohne verfügbaren Kanal - nichts versendet")
            return VersandErgebnis(kanaele = "", zustand = DeliveryState.FAILED)
        }

        val ergebnisse = coroutineScope {
            kanaele.map { kanal ->
                async {
                    // Die Absicherung umfasst bewusst den GESAMTEN Rumpf und nicht nur den
                    // send()-Aufruf: Wuerde auch nur die Protokollzeile werfen, risse die
                    // async-Coroutine den umgebenden coroutineScope mit - und ein einziger
                    // kaputter Kanal haette doch wieder alle anderen mitgenommen.
                    kanal.id to runCatching {
                        kanal.send(alert).getOrThrow()
                    }.onFailure {
                        runCatching { Log.w(TAG, "Versand über ${kanal.id} fehlgeschlagen", it) }
                    }.isSuccess
                }
            }.awaitAll()
        }

        val erfolgreich = ergebnisse.filter { it.second }.map { it.first }
        return VersandErgebnis(
            kanaele = ergebnisse.joinToString(",") { it.first.name },
            // SENT, sobald EIN Kanal durchkam: fuer die Alarmlogik zaehlt, ob der Alarm
            // ueberhaupt jemanden erreicht hat, nicht ob jeder Weg funktioniert hat.
            zustand = if (erfolgreich.isNotEmpty()) DeliveryState.SENT else DeliveryState.FAILED,
        )
    }
}
