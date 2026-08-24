package com.example.lrmprotokoll.drive

import android.util.Log
import com.example.lrmprotokoll.data.NoiseRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "WavHourlyZipper"

/**
 * Repräsentiert ein stündlich gebündeltes ZIP-Archiv für den Google Drive Upload.
 */
data class HourlyZipPackage(
    val zipFileName: String,
    val zipBytes: ByteArray,
    val wavCount: Int,
    val isClosedHour: Boolean,
)

/**
 * Bündelt einzelne WAV-Aufnahmen für jeweils 1-Stunden-Zeitfenster in standardkonforme ZIP-Archive.
 */
object WavHourlyZipper {

    private val STUNDEN_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-00")

    fun packeStundenZips(
        records: List<NoiseRecord>,
        jetzt: Instant,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<HourlyZipPackage> {
        val aktuelleStundeSchluessel = jetzt.atZone(zone).format(STUNDEN_FORMATTER)

        // 1. Nur gültige, physisch existierende WAV-Dateien filtern und nach Stundenfenster gruppieren
        val gultigeDateien = records.mapNotNull { record ->
            val file = File(record.filePath)
            if (file.exists() && file.isFile && file.length() > 0) {
                val stundenSchluessel = Instant.ofEpochMilli(record.timestamp).atZone(zone).format(STUNDEN_FORMATTER)
                stundenSchluessel to file
            } else {
                null
            }
        }

        if (gultigeDateien.isEmpty()) return emptyList()

        val nachStundeGruppiert = gultigeDateien.groupBy({ it.first }, { it.second })

        val ergebnisse = mutableListOf<HourlyZipPackage>()

        for ((stundenSchluessel, dateien) in nachStundeGruppiert) {
            val zipName = "audio_$stundenSchluessel.zip"
            val isClosedHour = stundenSchluessel != aktuelleStundeSchluessel

            val zipBytes = erstelleZipArchiv(dateien)
            if (zipBytes != null && zipBytes.isNotEmpty()) {
                ergebnisse.add(
                    HourlyZipPackage(
                        zipFileName = zipName,
                        zipBytes = zipBytes,
                        wavCount = dateien.size,
                        isClosedHour = isClosedHour,
                    )
                )
                Log.d(TAG, "ZIP-Paket erstellt: $zipName (${dateien.size} WAVs, ${zipBytes.size} Bytes, abgeschlossen=$isClosedHour)")
            }
        }

        return ergebnisse.sortedBy { it.zipFileName }
    }

    private fun erstelleZipArchiv(dateien: List<File>): ByteArray? {
        return runCatching {
            val byteStream = ByteArrayOutputStream()
            ZipOutputStream(byteStream).use { zipOut ->
                val bereitsEnthalteneNamen = mutableSetOf<String>()
                for (datei in dateien) {
                    var eintragName = datei.name
                    // Bei eventuellen Namensdopplungen eindeutigen Suffix anhängen
                    if (bereitsEnthalteneNamen.contains(eintragName)) {
                        eintragName = "${datei.nameWithoutExtension}_${System.identityHashCode(datei)}.wav"
                    }
                    bereitsEnthalteneNamen.add(eintragName)

                    val entry = ZipEntry(eintragName).apply {
                        time = datei.lastModified()
                    }
                    zipOut.putNextEntry(entry)
                    datei.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
            byteStream.toByteArray()
        }.getOrNull()
    }
}
