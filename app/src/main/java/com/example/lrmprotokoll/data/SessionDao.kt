package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    /** Die noch offene Session, falls es eine gibt - fuer die Wiederaufnahme nach Prozess-Tod
     * (dasselbe Muster wie AlertDao.offenerAusfall in M5). */
    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun offeneSession(): SessionEntity?

    /** Fuer den Diagnose-Screen (Plan Abschnitt 9): der diagnostisch relevante Zeitraum ist die
     * laufende Session, falls es eine gibt, sonst die zuletzt beendete. */
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun letzte(): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun alle(): Flow<List<SessionEntity>>
}

@Dao
interface MeasurementDao {

    @Insert
    suspend fun insertAll(messwerte: List<MeasurementEntity>)

    @Query("SELECT * FROM measurements WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun fuerSession(sessionId: Long): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE timestamp >= :von AND timestamp < :bis ORDER BY timestamp")
    suspend fun zwischen(von: Long, bis: Long): List<MeasurementEntity>

    /** Fuer den Retention-Job (Plan 13.2): Rohwerte aelter als die Grenze. */
    @Query("SELECT * FROM measurements WHERE timestamp < :grenze ORDER BY timestamp")
    suspend fun aelterAls(grenze: Long): List<MeasurementEntity>

    @Query("DELETE FROM measurements WHERE timestamp < :grenze")
    suspend fun loescheAelterAls(grenze: Long)

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun anzahl(): Int
}

@Dao
interface ConnectionEventDao {

    @Insert
    suspend fun insert(event: ConnectionEventEntity)

    @Query("SELECT * FROM connection_events WHERE sessionId = :sessionId ORDER BY at")
    suspend fun fuerSession(sessionId: Long): List<ConnectionEventEntity>
}

@Dao
interface MinuteAggregateDao {

    @Insert
    suspend fun insertAll(aggregate: List<MinuteAggregateEntity>)

    @Query("SELECT * FROM minute_aggregates WHERE sessionId = :sessionId ORDER BY minuteStart")
    suspend fun fuerSession(sessionId: Long): List<MinuteAggregateEntity>

    @Query("SELECT * FROM minute_aggregates WHERE minuteStart >= :von AND minuteStart < :bis ORDER BY minuteStart")
    suspend fun zwischen(von: Long, bis: Long): List<MinuteAggregateEntity>
}
