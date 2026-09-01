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
 *
 * KI-Umbau Etappe 1 (Beweisdokumentation Baulärm): [aufnahmeQuelle], [abtastrate], [kanalzahl]
 * und [agcAktiv] halten fest, unter welchen Aufnahmebedingungen eine WAV-Datei entstand - im
 * Beweiskontext muss nachvollziehbar bleiben, ob z.B. eine automatische Verstärkungsregelung
 * (AGC) aktiv war, die Pegeldynamik verfälscht haben könnte. Alle vier `null` bei Altaufnahmen
 * und beim reinen Pegelereignis ohne Audio (DSGVO-Modus).
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
    val isQuietHour: Boolean = false,
    val deletedAt: Long? = null,
    val favorite: Boolean = false,
    val notes: String? = null,
    val aufnahmeQuelle: Int? = null,
    val abtastrate: Int? = null,
    val kanalzahl: Int? = null,
    val agcAktiv: Boolean? = null,
)

@Entity(tableName = "reference_sounds")
data class ReferenceSound(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pattern: String // Top labels as string
)
