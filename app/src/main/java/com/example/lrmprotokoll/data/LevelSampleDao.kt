package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LevelSampleDao {

    @Insert
    suspend fun insert(sample: LevelSampleEntity)

    @Query("SELECT * FROM level_samples WHERE at >= :von AND at < :bis ORDER BY at")
    suspend fun zwischen(von: Long, bis: Long): List<LevelSampleEntity>

    /**
     * Puffer-Bereinigung nach erfolgreichem Sync. Ohne sie waechst die Tabelle unbegrenzt -
     * die Rohwerte sind fuer den Sync selbst nur bis zum naechsten Zyklus relevant, die
     * verdichteten Werte stehen danach in der Drive-CSV.
     */
    @Query("DELETE FROM level_samples WHERE at < :vor")
    suspend fun loescheVor(vor: Long)

    @Query("SELECT COUNT(*) FROM level_samples")
    suspend fun anzahl(): Int
}
