package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Ein am Messbeginn erfasster Satz Gesamtbericht-Stammdaten (Messgerät, Messaufbau,
 * Randbedingungen) - Owner-Anfrage 10.09.2026: diese Angaben sollen nicht mehr als fixer Wert in
 * den Einstellungen "hardcodiert" sein, sondern bei jedem Messbeginn erfasst werden, wobei die
 * zuletzt verwendeten Werte als Vorschlag dienen.
 *
 * Jede Bestätigung des Stammdaten-Dialogs (siehe `GesamtberichtStammdatenSheet`) legt eine neue
 * Zeile an - bewusst kein Update einer einzigen "aktuellen" Zeile: Nur so lässt sich rückblickend
 * aus den letzten (bis zu) 10 Einträgen auswählen, wie es der Owner verlangt hat. Es wird nichts
 * gelöscht - die Auswahl auf die letzten 10 beschränkt sich allein über
 * [StammdatenVerlaufDao.letzte]s `LIMIT`.
 *
 * Fuer nachtraegliche Angaben zum Rechtsbericht bezeichnet [giltFuerTagStart] den lokalen
 * Kalendertag der Messung, waehrend [erstelltAm] weiterhin den echten Erfassungszeitpunkt
 * dokumentiert. Eine Rueckdatierung von [erstelltAm] wuerde die Beweiskette verfälschen.
 */
@Entity(tableName = "stammdaten_verlauf")
data class StammdatenVerlaufEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val erstelltAm: Long,
    val geraetHersteller: String,
    val geraetTyp: String,
    val geraetGenauigkeitsklasse: String,
    val geraetSeriennummer: String,
    val geraetKalibrierung: String,
    val messort: String,
    val mikrofonposition: String,
    val mikrofonhoehe: String,
    val entfernungZurQuelle: String,
    val innenAussen: String,
    val fensterzustand: String,
    val wetter: String,
    val datenqualitaetHinweis: String,
    val giltFuerTagStart: Long? = null,
)

@Dao
interface StammdatenVerlaufDao {

    @Insert
    suspend fun insert(eintrag: StammdatenVerlaufEntity): Long

    /** Neueste reguläre Messbeginn-Einträge zuerst. Ein historischer Nachtrag soll nicht
     * automatisch zum Default einer neuen Messung werden, nur weil er heute erfasst wurde. */
    @Query("SELECT * FROM stammdaten_verlauf WHERE giltFuerTagStart IS NULL ORDER BY erstelltAm DESC LIMIT :anzahl")
    suspend fun letzte(anzahl: Int = 10): List<StammdatenVerlaufEntity>

    /** Bewahrt bei Nachtraegen beide Zeitangaben: wann erfasst und fuer welchen Messtag. Deckt
     * den regulaeren Fall (Eintrag mit echtem Erfassungszeitpunkt im lokalen Messtag) mit ab,
     * dafuer war frueher eine eigene `zwischen`-Query da - die hatte aber ausser dieser Klasse
     * selbst keinen Aufrufer mehr (Review-Befund PR #144) und wurde entfernt. */
    @Query("SELECT * FROM stammdaten_verlauf WHERE giltFuerTagStart = :von OR (giltFuerTagStart IS NULL AND erstelltAm >= :von AND erstelltAm < :bis) ORDER BY erstelltAm DESC")
    suspend fun fuerTag(von: Long, bis: Long): List<StammdatenVerlaufEntity>
}
