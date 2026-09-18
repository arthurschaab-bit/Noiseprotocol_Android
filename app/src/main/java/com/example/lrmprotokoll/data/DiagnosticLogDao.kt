package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticLogDao {

    @Insert
    suspend fun insert(eintrag: DiagnosticLogEntity)

    /**
     * Fuer die Anzeige im DiagnoseScreen (M12 Schritt 7, Konzept Abschnitt 2 Verdacht): Flow statt
     * einmaliger Abfrage (M7c Aufgabe 5), aber mit fester Obergrenze - ersetzt die vormalige
     * `alle()`-Abfrage OHNE `LIMIT`. Im Aufzeichnungsbetrieb schreibt der ConnectionSupervisor
     * laufend neue Zeilen; ohne Obergrenze emittierte Room bei jeder einzelnen Aenderung die
     * VOLLSTAENDIGE, mit der Zeit beliebig lange Ergebnisliste neu (samt Recomposition) - der im
     * Konzept dokumentierte Verdacht fuer den vom Owner gemeldeten Absturz. Neueste zuerst.
     */
    @Query("SELECT * FROM diagnostic_log_entries ORDER BY timestamp DESC LIMIT :grenze")
    fun neueste(grenze: Int): Flow<List<DiagnosticLogEntity>>

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

    /**
     * Fuer das periodische Gesundheits-Bundle (M12 Schritt 6, Konzept Aufgabe 3): Anzahl der
     * Diagnose-Eintraege seit dem letzten periodischen Bundle - eine der Kennzahlen, die nur im
     * Zeitverlauf etwas aussagen.
     */
    @Query("SELECT COUNT(*) FROM diagnostic_log_entries WHERE timestamp >= :von")
    suspend fun anzahlSeit(von: Long): Long
}
