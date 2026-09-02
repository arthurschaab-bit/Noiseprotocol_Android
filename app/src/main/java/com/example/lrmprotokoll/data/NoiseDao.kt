package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoiseDao {
    @Query("SELECT * FROM noise_records WHERE deletedAt IS NULL ORDER BY timestamp DESC")
    fun getAll(): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE deletedAt IS NULL ORDER BY timestamp DESC")
    suspend fun getAlleAktiven(): List<NoiseRecord>

    @Query("SELECT * FROM noise_records WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getTrash(): Flow<List<NoiseRecord>>

    /** Fuer den Drive-Sync (M7b): Ereignis-Abgleich der Tages-CSV mit den Aufnahme-Ereignissen. */
    @Query("SELECT * FROM noise_records WHERE timestamp >= :von AND timestamp < :bis AND deletedAt IS NULL ORDER BY timestamp")
    suspend fun zwischenZeitpunkt(von: Long, bis: Long): List<NoiseRecord>

    @Query("SELECT * FROM noise_records WHERE timestamp >= :von AND timestamp <= :bis AND deletedAt IS NULL ORDER BY timestamp DESC")
    fun zwischenZeitpunktFlow(von: Long, bis: Long): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE timestamp >= :von AND deletedAt IS NULL ORDER BY timestamp DESC")
    fun abZeitpunktFlow(von: Long): Flow<List<NoiseRecord>>

    /**
     * KI-Umbau Etappe 1.4: liefert die generierte Id zurueck, damit
     * [com.example.lrmprotokoll.data.KlassifikationsRohdaten] direkt nach dem Einfuegen mit der
     * richtigen `recordId` verknuepft werden kann (Online-Klassifizierung kennt die Id erst
     * NACH dem Insert, siehe [com.example.lrmprotokoll.audio.AudioRecordingService]).
     */
    @Insert
    suspend fun insert(record: NoiseRecord): Long

    @Update
    suspend fun update(record: NoiseRecord)

    @Query("UPDATE noise_records SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE noise_records SET deletedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun softDeleteMultiple(ids: List<Long>, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE noise_records SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("UPDATE noise_records SET deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreMultiple(ids: List<Long>)

    @Query("DELETE FROM noise_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM noise_records WHERE id IN (:ids)")
    suspend fun deleteMultiple(ids: List<Long>)

    @Query("DELETE FROM noise_records WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashAelterAls(cutoff: Long): Int

    @Query("SELECT * FROM noise_records WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getTrashAelterAls(cutoff: Long): List<NoiseRecord>

    @Query("SELECT * FROM noise_records WHERE timestamp < :cutoff AND label IS NULL AND detectedLabel IS NULL AND favorite = 0 AND deletedAt IS NULL")
    suspend fun getAutoRetentionCandidates(cutoff: Long): List<NoiseRecord>

    @Query("UPDATE noise_records SET favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE noise_records SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String?)

    /** KI-Umbau Etappe 1.5 ("Neu bewerten"): Label direkt setzen, ohne den ganzen Datensatz zu lesen. */
    @Query("UPDATE noise_records SET detectedLabel = :label WHERE id = :id")
    suspend fun setDetectedLabel(id: Long, label: String?)

    /**
     * KI-Umbau Etappe 3.5 (Fusion): der kalibrierte PCE-323-Pegel fuer die Impuls-Regel bei
     * "Neu bewerten" - gezielt statt den ganzen Datensatz zu lesen, analog zu [setDetectedLabel].
     */
    @Query("SELECT calibratedDbA FROM noise_records WHERE id = :id")
    suspend fun getCalibratedDbA(id: Long): Double?

    // Reference Sounds
    @Query("SELECT * FROM reference_sounds")
    fun getAllReferences(): Flow<List<ReferenceSound>>

    @Insert
    suspend fun insertReference(sound: ReferenceSound)

    @Query("DELETE FROM reference_sounds WHERE id = :id")
    suspend fun deleteReference(id: Long)
}
