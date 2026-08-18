package com.example.lrmprotokoll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    @Insert
    suspend fun insert(alert: AlertEntity): Long

    @Update
    suspend fun update(alert: AlertEntity)

    @Query("SELECT * FROM alerts WHERE id = :id")
    suspend fun byId(id: Long): AlertEntity?

    /**
     * Der noch offene Ausfall, falls es einen gibt. Nach einem Prozess-Tod ist das der
     * Einstiegspunkt, um eine laufende Karenzzeit oder einen faelligen Alarm wiederaufzunehmen.
     * Es kann konstruktionsbedingt hoechstens einen geben - der AlarmCoordinator legt keinen
     * zweiten an, solange einer offen ist.
     */
    @Query("SELECT * FROM alerts WHERE resolvedAt IS NULL ORDER BY outageSince DESC LIMIT 1")
    suspend fun offenerAusfall(): AlertEntity?

    /** Alle Alarme, deren Versand fehlgeschlagen ist - Grundlage fuer die Wiederholung. */
    @Query("SELECT * FROM alerts WHERE deliveryState = 'FAILED' ORDER BY outageSince")
    suspend fun fehlgeschlagene(): List<AlertEntity>

    @Query("SELECT * FROM alerts ORDER BY outageSince DESC")
    fun alle(): Flow<List<AlertEntity>>
}
