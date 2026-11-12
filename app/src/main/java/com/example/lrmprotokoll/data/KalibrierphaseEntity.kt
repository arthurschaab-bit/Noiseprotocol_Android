package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * Eine Kalibrierphase am Messbeginn (Owner-Auftrag 12.11.2026): Das Messgeraet misst den
 * Kalibrator (PCE-SC 43: 94/104/114 dB), die Werte kommen NICHT in die Messreihe, sondern in
 * [KalibrierMesswertEntity] - so bleiben Tageswerte, Ueberschreitungen und Berichte unberuehrt.
 *
 * [abweichungVorherDb]/[abweichungNachherDb] sind Pegel minus [sollwertDb] (Anzeige am Geraet
 * minus Kalibratorpegel) und koennen vom Nutzer beim Abschluss korrigiert worden sein
 * ([abweichungKorrigiert]); sie sind `null`, wenn kein stabiles Plateau erkannt wurde.
 * [endeGrund] ist einer von [KalibrierEnde].
 */
@Entity(tableName = "kalibrierphasen", indices = [Index("startedAt")])
data class KalibrierphaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val sollwertDb: Double = KALIBRIER_SOLLWERT_DB,
    val abweichungVorherDb: Double? = null,
    val abweichungNachherDb: Double? = null,
    val abweichungKorrigiert: Boolean = false,
    val endeGrund: String? = null,
)

/** Wert in [KalibrierphaseEntity.endeGrund]. */
object KalibrierEnde {
    const val BESTAETIGT = "BESTAETIGT" // Ende automatisch erkannt, Nutzer hat bestaetigt
    const val MANUELL = "MANUELL" // Nutzer hat selbst beendet
    const val UEBERSPRUNGEN = "UEBERSPRUNGEN" // bewusst ohne Kalibrierung gestartet
    const val ZEITUEBERSCHREITUNG = "ZEITUEBERSCHREITUNG" // vergessen, nach Hoechstdauer beendet
}

/** Kalibrierton-Pegel des Kalibrators, auf den die Abweichung bezogen wird. */
const val KALIBRIER_SOLLWERT_DB = 94.0

/** Ein Messwert waehrend einer Kalibrierphase - auch zur nachtraeglichen Analyse der Erkennung. */
@Entity(tableName = "kalibrier_messwerte", indices = [Index("phaseId", "timestamp")])
data class KalibrierMesswertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phaseId: Long,
    val timestamp: Long,
    val levelDb: Double,
)

@Dao
interface KalibrierphaseDao {
    @Insert
    suspend fun insert(phase: KalibrierphaseEntity): Long

    @Update
    suspend fun update(phase: KalibrierphaseEntity)

    @Insert
    suspend fun insertMesswerte(werte: List<KalibrierMesswertEntity>)

    @Query("SELECT * FROM kalibrierphasen WHERE id = :id")
    suspend fun byId(id: Long): KalibrierphaseEntity?

    @Query("SELECT * FROM kalibrierphasen WHERE startedAt >= :von AND startedAt < :bis ORDER BY startedAt")
    suspend fun zwischen(von: Long, bis: Long): List<KalibrierphaseEntity>
}
