package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveDailyFileDao {

    @Query("SELECT * FROM drive_daily_files WHERE date = :date")
    suspend fun byDate(date: String): DriveDailyFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DriveDailyFileEntity)

    @Update
    suspend fun update(entity: DriveDailyFileEntity)

    @Query("SELECT * FROM drive_daily_files WHERE state = 'FAILED' ORDER BY date DESC LIMIT 1")
    suspend fun letzterFehlschlag(): DriveDailyFileEntity?

    /** Sync-Historie fuer den Diagnose-Screen (Plan Abschnitt 9). Flow statt einmaliger Abfrage
     * (M7c Aufgabe 5), analog zu [DiagnosticLogDao.alle]. */
    @Query("SELECT * FROM drive_daily_files ORDER BY date DESC")
    fun alle(): Flow<List<DriveDailyFileEntity>>
}
