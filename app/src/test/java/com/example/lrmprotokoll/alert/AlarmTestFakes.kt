package com.example.lrmprotokoll.alert

import com.example.lrmprotokoll.data.AlertDao
import com.example.lrmprotokoll.data.AlertEntity
import com.example.lrmprotokoll.meter.InstantSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Steuerbare Uhr - ohne sie dauerte ein einziger Eskalationstest eine Stunde. */
class TestUhr(private var jetzt: Instant = Instant.parse("2026-08-18T12:00:00Z")) : InstantSource {
    override fun now(): Instant = jetzt
    fun vor(dauer: Duration) { jetzt = jetzt.plus(dauer) }
    fun setze(zeitpunkt: Instant) { jetzt = zeitpunkt }
}

/**
 * Merkt sich die gesetzte Weckzeit, statt zu warten. [feuere] simuliert den Weckruf des
 * AlarmManager - die Uhr muss der Test selbst vorstellen, damit sichtbar bleibt, dass die
 * Zeitpunkte und nicht nur die Reihenfolge stimmen.
 */
class TestScheduler : DeadlineScheduler {
    var geplant: Instant? = null
        private set
    val geplanteZeiten = mutableListOf<Instant>()
    var abgebrochen = 0
        private set

    override fun schedule(at: Instant) {
        geplant = at
        geplanteZeiten += at
    }

    override fun cancel() {
        geplant = null
        abgebrochen++
    }

    override val kannExakt: Boolean = true
}

class TestKanal(
    override val id: ChannelId,
    override var isAvailable: Boolean = true,
    private var fehler: Throwable? = null,
    private var scheitertLeise: Boolean = false,
) : AlertChannel {

    val gesendet = mutableListOf<Alert>()

    /** Zahl der Zustellversuche - auch die gescheiterten, anders als [gesendet]. */
    var versuche = 0
        private set

    override suspend fun send(alert: Alert): Result<Unit> {
        versuche++
        fehler?.let { throw it }
        if (scheitertLeise) return Result.failure(IllegalStateException("Zustellung fehlgeschlagen"))
        gesendet += alert
        return Result.success(Unit)
    }

    fun wirftAb(t: Throwable?) { fehler = t }
    fun scheitertAb(wert: Boolean) { scheitertLeise = wert }

    val arten: List<AlertKind> get() = gesendet.map { it.kind }
}

/** In-Memory-Ersatz fuer Room. Haelt sich an dieselben Zusagen wie die echten Queries. */
class TestAlertDao : AlertDao {

    private val zeilen = mutableListOf<AlertEntity>()
    private var naechsteId = 1L

    override suspend fun insert(alert: AlertEntity): Long {
        val id = naechsteId++
        zeilen += alert.copy(id = id)
        return id
    }

    override suspend fun update(alert: AlertEntity) {
        val index = zeilen.indexOfFirst { it.id == alert.id }
        require(index >= 0) { "update() auf unbekannte id ${alert.id}" }
        zeilen[index] = alert
    }

    override suspend fun byId(id: Long): AlertEntity? = zeilen.firstOrNull { it.id == id }

    override suspend fun offenerAusfall(): AlertEntity? =
        zeilen.filter { it.resolvedAt == null }.maxByOrNull { it.outageSince }

    override suspend fun fehlgeschlagene(): List<AlertEntity> =
        zeilen.filter { it.deliveryState == com.example.lrmprotokoll.data.DeliveryState.FAILED }
            .sortedBy { it.outageSince }

    override suspend fun letzterAlarmZeitpunkt(): Long? = zeilen.mapNotNull { it.raisedAt }.maxOrNull()

    override fun alle(): Flow<List<AlertEntity>> = flowOf(zeilen.toList())

    /** Nur fuer Tests: direkter Blick in die Ablage. */
    val inhalt: List<AlertEntity> get() = zeilen.toList()

    /** Simuliert einen Bestand, der einen Prozess-Tod ueberlebt hat. */
    fun lege(alert: AlertEntity) {
        zeilen += alert.copy(id = naechsteId++)
    }
}
