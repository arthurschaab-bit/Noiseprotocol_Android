package com.example.lrmprotokoll

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReportManager(private val context: Context) {

    fun generateDailyReport(records: List<NoiseRecord>): File {
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        val fileName = "Tagesbericht_$dateStr.txt"
        val file = File(context.getExternalFilesDir(null), fileName)
        
        val content = StringBuilder()
        content.append("Lärmprotokoll - Tagesbericht für $dateStr\n")
        content.append("===========================================\n\n")
        
        records.forEach { record ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
            content.append("Zeit: $time\n")
            content.append("Amplitude: ${record.amplitude.toInt()}\n")
            content.append("Label: ${record.label ?: "Keines"}\n")
            content.append("KI Erkannt: ${record.detectedLabel ?: "Keines"}\n")
            content.append("-------------------------------------------\n")
        }
        
        file.writeText(content.toString())
        return file
    }

    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "wav") "audio/wav" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Teilen über..."))
    }

    fun createZipAndShare(records: List<NoiseRecord>, reportFile: File?) {
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        val zipFile = File(context.getExternalFilesDir(null), "Laermprotokoll_$dateStr.zip")
        
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            records.forEach { record ->
                val audioFile = File(record.filePath)
                if (audioFile.exists()) {
                    zos.putNextEntry(ZipEntry(audioFile.name))
                    zos.write(audioFile.readBytes())
                    zos.closeEntry()
                }
            }
            reportFile?.let {
                zos.putNextEntry(ZipEntry(it.name))
                zos.write(it.readBytes())
                zos.closeEntry()
            }
        }
        
        shareFile(zipFile)
    }
}
