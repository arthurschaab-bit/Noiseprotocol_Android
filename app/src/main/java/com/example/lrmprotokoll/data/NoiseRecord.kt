package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [dbValue] bleibt der Mikrofonwert (dBFS + Offset, unkalibriert) - er wird NICHT durch
 * [calibratedDbA] ersetzt, weil beide Quellen gleichzeitig aussagekräftig bleiben: [dbValue] ist
 * das, was tatsächlich zur Aufnahme geführt hat, wenn kein Messgerät angeschlossen war;
 * [calibratedDbA] der kalibrierte Wert, wenn eines angeschlossen war (Plan 4.5).
 *
 * [calibratedDbA] ist `null`, solange kein Messgerät verbunden war. [meterWeighting] bleibt
 * zusätzlich `null`, solange die A/C-Bewertung unbestätigt ist (siehe [MeasurementEntity]-KDoc) -
 * auch wenn [calibratedDbA] selbst gesetzt ist. [meterConnected] hält fest, welche Quelle den
 * Trigger tatsächlich ausgelöst hat, unabhängig davon, ob ein kalibrierter Wert vorliegt.
 */
@Entity(tableName = "noise_records")
data class NoiseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val amplitude: Double,
    val dbValue: Double = 0.0,
    val filePath: String,
    val label: String? = null,
    val detectedLabel: String? = null,
    val calibratedDbA: Double? = null,
    val meterWeighting: String? = null,
    val meterConnected: Boolean = false,
)

@Entity(tableName = "reference_sounds")
data class ReferenceSound(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pattern: String // Top labels as string
)
