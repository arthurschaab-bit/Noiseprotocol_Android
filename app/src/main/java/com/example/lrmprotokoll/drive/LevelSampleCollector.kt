package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.LevelSampleEntity
import com.example.lrmprotokoll.meter.InstantSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sammelstelle fuer Pegelwerte aus beliebigen Quellen (Plan 8.4 braucht dafuer keine bestimmte
 * Quelle - der Sync soll "vollstaendig mit den heutigen Mikrofonwerten" laufen koennen). Weder
 * [com.example.lrmprotokoll.audio.AudioRecordingService] noch der Meter-Pfad kennt Drive oder
 * Room direkt - beide rufen nur [pegel] auf.
 *
 * Puffert im Speicher und schreibt gebuendelt, nicht pro Wert (Plan 8.2 fuer die
 * Rohwert-Persistenz: "Nicht einzeln inserten" - bei ~20 Hz Mikrofon-Polling waere ein Insert
 * pro Aufruf unnoetiger SQLite-Druck fuer Werte, die ohnehin erst zu 30-min-Sync-Zyklen
 * verdichtet werden).
 */
interface LevelSampleSink {
    fun pegel(quelle: String, dbWert: Double, zeitpunkt: Instant)
}

class LevelSampleCollector(
    private val dao: LevelSampleDao,
    private val scope: CoroutineScope,
    private val now: InstantSource = InstantSource.System,
    private val flushInterval: Duration = Duration.ofSeconds(5),
    private val flushBatchSize: Int = 200,
) : LevelSampleSink {

    private val mutex = Mutex()
    private val puffer = mutableListOf<LevelSampleEntity>()
    private var flushJob: Job? = null

    override fun pegel(quelle: String, dbWert: Double, zeitpunkt: Instant) {
        scope.launch {
            mutex.withLock {
                puffer += LevelSampleEntity(at = zeitpunkt.toEpochMilli(), levelDb = dbWert, source = quelle)
                if (puffer.size >= flushBatchSize) flushOhneSperre()
            }
        }
    }

    /** Startet die periodische Leerung. Ein zweiter Aufruf ist ein No-Op. */
    fun start() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (isActive) {
                delay(flushInterval.toMillis())
                mutex.withLock { flushOhneSperre() }
            }
        }
    }

    /** Stoppt die Periodik und schreibt den Rest sofort weg - sonst gehen die letzten Werte vor
     * einem Dienst-Stopp verloren. */
    fun stop() {
        flushJob?.cancel()
        flushJob = null
        scope.launch { mutex.withLock { flushOhneSperre() } }
    }

    private suspend fun flushOhneSperre() {
        if (puffer.isEmpty()) return
        val zuSchreiben = puffer.toList()
        puffer.clear()
        dao.insertAll(zuSchreiben)
    }
}
