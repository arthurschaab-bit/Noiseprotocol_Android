package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Die frei waehlbaren Auswertungs- und Schaetzparameter fuer den geplanten High-End-Bericht
 * (Chaquopy, Schritt 1/2 des Bericht-Umbaus), gedruckt im Berichtskopf als § 287 ZPO-Annahme.
 *
 * Eine einzelne Zeile mit fester ID [SINGLETON_ID] statt eines Verlaufs wie bei
 * [StammdatenVerlaufEntity]: Diese Werte sind Auswertungsannahmen fuer die gesamte App, keine
 * Momentaufnahme je Messbeginn - es gibt nur "die aktuell gueltige Einstellung", die per
 * [ReportConfigDao.speichere] ueberschrieben wird ([OnConflictStrategy.REPLACE] auf derselben ID).
 *
 * Tier-Klassifizierung eines Messtages anhand der Datenverfuegbarkeit:
 * - Vollmessung: >= [tierSchwelleVollmessungProzent]
 * - Teilerfassung ("~"): zwischen [tierSchwelleTeilerfassungProzent] und
 *   [tierSchwelleVollmessungProzent] - genutzt wird dann [schaetzpegelTeilerfassungDb]
 * - Messfenster ("M"): < [tierSchwelleTeilerfassungProzent] - genutzt wird
 *   [schaetzpegelMessfensterAbbruchDb]
 */
@Entity(tableName = "report_config")
data class ReportConfigEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val schaetzpegelTeilerfassungDb: Double = 50.0,
    val schaetzpegelMessfensterAbbruchDb: Double = 55.0,
    val tierSchwelleVollmessungProzent: Double = 90.0,
    val tierSchwelleTeilerfassungProzent: Double = 70.0,
    val adresse: String = "",
    val gebietseinstufung: String = "",
    val hardwareId: String = "",
    val geraeteUnsicherheitDb: Double = 1.4,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface ReportConfigDao {

    @Query("SELECT * FROM report_config WHERE id = ${ReportConfigEntity.SINGLETON_ID}")
    suspend fun get(): ReportConfigEntity?

    @Query("SELECT * FROM report_config WHERE id = ${ReportConfigEntity.SINGLETON_ID}")
    fun getFlow(): Flow<ReportConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichere(config: ReportConfigEntity)
}
