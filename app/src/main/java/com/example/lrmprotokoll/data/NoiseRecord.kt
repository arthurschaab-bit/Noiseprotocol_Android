package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "noise_records")
data class NoiseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val amplitude: Double,
    val dbValue: Double = 0.0,
    val filePath: String,
    val label: String? = null,
    val detectedLabel: String? = null
)

@Entity(tableName = "reference_sounds")
data class ReferenceSound(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pattern: String // Top labels as string
)
