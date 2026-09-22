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
 * - Messfenster ("M"): < [tierSchwelleTeilerfassungProzent] - Schritt 4b verwendet den
 *   Tagesrichtwert des gewählten Gebiets. [schaetzpegelMessfensterAbbruchDb] bleibt als
 *   Altwert erhalten, wird für die neue Hochrechnung aber nicht verwendet.
 *
 * Owner-Klarstellung 13.09.2026: `adresse`/`hardwareId` (bis v22 hier enthalten) sind entfernt
 * (Migration [MIGRATION_22_23]) - beide waren redundant zu den bereits pro Messung erfassten
 * Feldern `messort`/`geraetSeriennummer` in [StammdatenVerlaufEntity]; report_bridge.py (Schritt 4)
 * liest dafuer die zuletzt gespeicherte Stammdaten-Zeile statt eines eigenen Settings-Werts.
 *
 * Nachtrag 14.09.2026: [konservativFensterStartStunde] und [konservativFensterEndeStunde]
 * ersetzen die Konstanten KONSERVATIV_FENSTER_START/-ENDE des Referenzskripts (15–19 Uhr).
 * [erzwingeBerichtOhneBestaetigteBewertung] setzt die Owner-Entscheidung aus
 * `docs/DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 6.3 um: ohne bestaetigte A-/Zeitbewertung
 * wird ein Rechtsbericht standardmaessig verweigert; ein bewusstes Override verlangt im PDF
 * einen sichtbaren Vorbehalt.
 */
@Entity(tableName = "report_config")
data class ReportConfigEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val schaetzpegelTeilerfassungDb: Double = 50.0,
    val schaetzpegelMessfensterAbbruchDb: Double = 55.0,
    val tierSchwelleVollmessungProzent: Double = 90.0,
    val tierSchwelleTeilerfassungProzent: Double = 70.0,
    // Schritt 4b: Kürzel aus ReportArea; alte Freitexte bleiben bis zur Neuauswahl erhalten.
    val gebietseinstufung: String = "",
    val geraeteUnsicherheitDb: Double = 1.4,
    val konservativFensterStartStunde: Int = 15,
    val konservativFensterEndeStunde: Int = 19,
    val erzwingeBerichtOhneBestaetigteBewertung: Boolean = false,
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
