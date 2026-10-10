package com.example.lrmprotokoll.drive

import android.util.Log
import com.example.lrmprotokoll.data.NoiseRecord
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "WavHourlyZipper"

/**
 * Repräsentiert ein stündlich gebündeltes ZIP-Archiv für den Google Drive Upload.
 * Das Archiv wird erst bei Bedarf und STREAMEND in eine Datei geschrieben ([schreibeZipNach]),
 * nie als `ByteArray` im RAM aufgebaut (Befund 1, docs/BEFUNDE_BUNDLES_2026-10-10.md): Eine
 * Stunde mit 855 WAVs ergab ~138 MB, und der wachsende `ByteArrayOutputStream` samt
 * `toByteArray()` warf auf dem P30 (384 MB Heap) `OutOfMemoryError` bis hin zum Absturz.
 */
class HourlyZipPackage(
    val zipFileName: String,
    val wavCount: Int,
    val isClosedHour: Boolean,
    val tagesordner: String = zipFileName.removePrefix("audio_").substringBefore('_'),
    private val zipSchreiber: (ziel: File) -> Boolean,
) {
    /**
     * Schreibt das ZIP-Archiv nach [ziel] (wird überschrieben). `false`, wenn es nicht
     * geschrieben werden konnte - der Aufrufer überspringt das Paket dann wie bisher ein leeres.
     */
    fun schreibeZipNach(ziel: File): Boolean = zipSchreiber(ziel)

    /** Sekundärer Konstruktor für direkte Bytes (z. B. in Tests). */
    constructor(
        zipFileName: String,
        zipBytes: ByteArray,
        wavCount: Int,
        isClosedHour: Boolean,
        tagesordner: String = zipFileName.removePrefix("audio_").substringBefore('_'),
    ) : this(
        zipFileName = zipFileName,
        wavCount = wavCount,
        isClosedHour = isClosedHour,
        tagesordner = tagesordner,
        zipSchreiber = { ziel ->
            ziel.writeBytes(zipBytes)
            zipBytes.isNotEmpty()
        },
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HourlyZipPackage) return false
        return zipFileName == other.zipFileName &&
            wavCount == other.wavCount &&
            isClosedHour == other.isClosedHour &&
            tagesordner == other.tagesordner
    }

    override fun hashCode(): Int {
        var result = zipFileName.hashCode()
        result = 31 * result + wavCount
        result = 31 * result + isClosedHour.hashCode()
        result = 31 * result + tagesordner.hashCode()
        return result
    }

    override fun toString(): String {
        return "HourlyZipPackage(zipFileName='$zipFileName', wavCount=$wavCount, isClosedHour=$isClosedHour, tagesordner='$tagesordner')"
    }
}

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
            val tagesordner = stundenSchluessel.substringBefore('_')

            ergebnisse.add(
                HourlyZipPackage(
                    zipFileName = zipName,
                    wavCount = dateien.size,
                    isClosedHour = isClosedHour,
                    tagesordner = tagesordner,
                    zipSchreiber = { ziel -> erstelleZipArchiv(dateien, ziel) },
                )
            )
            Log.d(TAG, "ZIP-Paket vorbereitet: $zipName (${dateien.size} WAVs, abgeschlossen=$isClosedHour, Tagesordner=$tagesordner)")
        }

        // Neueste Stunden zuerst synchronisieren, damit heutige Aufnahmen sofort in Drive landen
        return ergebnisse.sortedByDescending { it.zipFileName }
    }

    private fun erstelleZipArchiv(
        dateien: List<File>,
        ziel: File,
    ): Boolean =
        runCatching {
            ZipOutputStream(ziel.outputStream().buffered()).use { zipOut ->
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
            ziel.length() > 0
        }.onFailure { Log.w(TAG, "ZIP-Archiv konnte nicht geschrieben werden: ${ziel.name}", it) }
            .getOrDefault(false)
}
