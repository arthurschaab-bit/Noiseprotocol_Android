package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoiseDao {
    @Query("SELECT * FROM noise_records ORDER BY timestamp DESC")
    fun getAll(): Flow<List<NoiseRecord>>

    /** Fuer den Drive-Sync (M7b): Ereignis-Abgleich der Tages-CSV mit den Aufnahme-Ereignissen. */
    @Query("SELECT * FROM noise_records WHERE timestamp >= :von AND timestamp < :bis ORDER BY timestamp")
    suspend fun zwischenZeitpunkt(von: Long, bis: Long): List<NoiseRecord>

    @Insert
    suspend fun insert(record: NoiseRecord)

    @Update
    suspend fun update(record: NoiseRecord)

    @Query("DELETE FROM noise_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM noise_records WHERE id IN (:ids)")
    suspend fun deleteMultiple(ids: List<Long>)

    // Reference Sounds
    @Query("SELECT * FROM reference_sounds")
    fun getAllReferences(): Flow<List<ReferenceSound>>

    @Insert
    suspend fun insertReference(sound: ReferenceSound)

    @Query("DELETE FROM reference_sounds WHERE id = :id")
    suspend fun deleteReference(id: Long)
}
