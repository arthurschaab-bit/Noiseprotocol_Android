package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Datum-zu-fileId-Registry (Plan Abschnitt 8.4.4) - der Kern der Idempotenz. Ohne sie legt
 * jeder 30-min-Zyklus eine neue Datei an, und am Tagesende liegen 48 Kopien im Ordner.
 */
@Entity(tableName = "drive_daily_files")
data class DriveDailyFileEntity(
    @PrimaryKey val date: String,      // "2026-08-16"
    val fileId: String?,               // null = noch nie hochgeladen
    val lastSyncedAt: Long,
    val lastRowCount: Int,             // unveraendert => Upload ueberspringen
    val state: String,                 // DriveSyncState
)

object DriveSyncState {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val FAILED = "FAILED"
}
