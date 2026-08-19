package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DiagnosticLogDao {

    @Insert
    suspend fun insert(eintrag: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_log_entries ORDER BY timestamp DESC")
    suspend fun alle(): List<DiagnosticLogEntity>

    /** Fuer den taeglichen Bereinigungs-Job (Plan Abschnitt 6: 7-Tage-Loeschung). */
    @Query("DELETE FROM diagnostic_log_entries WHERE timestamp < :grenze")
    suspend fun loescheAelterAls(grenze: Long)
}
