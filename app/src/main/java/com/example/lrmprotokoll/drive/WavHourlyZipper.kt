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
 * Der ZIP-Inhalt wird bei Bedarf lazy erzeugt, damit bei bereits existierenden
 * Archiven auf Drive keine unnötigen Megabytes im RAM allokiert werden.
 */
class HourlyZipPackage(
    val zipFileName: String,
    val wavCount: Int,
    val isClosedHour: Boolean,
    val tagesordner: String = zipFileName.removePrefix("audio_").substringBefore('_'),
    private val zipBytesProvider: () -> ByteArray,
) {
    val zipBytes: ByteArray by lazy(zipBytesProvider)

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
        zipBytesProvider = { zipBytes },
    )

    operator fun component1(): String = zipFileName
    operator fun component2(): ByteArray = zipBytes
    operator fun component3(): Int = wavCount
    operator fun component4(): Boolean = isClosedHour
    operator fun component5(): String = tagesordner

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
                    zipBytesProvider = { erstelleZipArchiv(dateien) ?: ByteArray(0) },
                )
            )
            Log.d(TAG, "ZIP-Paket vorbereitet: $zipName (${dateien.size} WAVs, abgeschlossen=$isClosedHour, Tagesordner=$tagesordner)")
        }

        // Neueste Stunden zuerst synchronisieren, damit heutige Aufnahmen sofort in Drive landen
        return ergebnisse.sortedByDescending { it.zipFileName }
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
