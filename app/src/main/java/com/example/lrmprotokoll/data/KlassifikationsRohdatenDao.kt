package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface KlassifikationsRohdatenDao {
    @Insert
    suspend fun insert(rohdaten: KlassifikationsRohdaten): Long

    @Query("SELECT * FROM klassifikations_rohdaten WHERE recordId = :recordId LIMIT 1")
    suspend fun fuerRecord(recordId: Long): KlassifikationsRohdaten?

    /** "Neu bewerten" (Etappe 1.5): alle Aufnahmen, für die Rohdaten vorliegen. */
    @Query("SELECT * FROM klassifikations_rohdaten")
    suspend fun alle(): List<KlassifikationsRohdaten>

    @Query("DELETE FROM klassifikations_rohdaten WHERE recordId = :recordId")
    suspend fun loescheFuerRecord(recordId: Long)
}
