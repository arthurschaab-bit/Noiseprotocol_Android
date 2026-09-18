package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticLogDao {

    @Insert
    suspend fun insert(eintrag: DiagnosticLogEntity)

    /** Flow statt einmaliger Abfrage (M7c Aufgabe 5): DiagnoseScreen soll neue Eintraege sehen,
     * ohne den Screen neu zu oeffnen - analog zu NoiseDao.getAll(). */
    @Query("SELECT * FROM diagnostic_log_entries ORDER BY timestamp DESC")
    fun alle(): Flow<List<DiagnosticLogEntity>>

    /** Fuer den taeglichen Bereinigungs-Job (Plan Abschnitt 6: 7-Tage-Loeschung). */
    @Query("DELETE FROM diagnostic_log_entries WHERE timestamp < :grenze")
    suspend fun loescheAelterAls(grenze: Long)

    /**
     * Seitenweises, Keyset-paginiertes Lesen fuer den streamenden Bundle-Export (M12 Schritt 4,
     * Konzept Aufgabe 2): liest ab `id > nachId` aufsteigend. Anders als LIMIT/OFFSET liefert das
     * bei gleichzeitigen Einfuegungen waehrend des Exports garantiert jede Zeile genau einmal -
     * OFFSET wuerde bei wachsender Tabelle Zeilen ueberspringen oder doppelt liefern.
     */
    @Query("SELECT * FROM diagnostic_log_entries WHERE id > :nachId ORDER BY id ASC LIMIT :seitengroesse")
    suspend fun seite(nachId: Long, seitengroesse: Int): List<DiagnosticLogEntity>
}
