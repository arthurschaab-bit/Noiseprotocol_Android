package com.example.lrmprotokoll.messreihe

import android.content.Context
import com.example.lrmprotokoll.data.NoiseDao
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpeicherplatzUebersicht(val audioBytes: Long, val datenbankBytes: Long)

data class RetentionVorschau(val anzahlAufnahmen: Int, val audioBytes: Long)

/**
 * PROMPT_M10_FUNKTIONEN.md F5: "Vor der ersten Aktivierung zeigen, wie viele Aufnahmen und wie
 * viel Platz das jetzt beträfe. Eine Aufräumfunktion, die ungefragt loslegt, ist ein
 * Datenverlust mit Einstellungsschalter." Dieselben Kandidaten wie [RetentionWorker] (über
 * [NoiseDao.getAutoRetentionCandidates]), nur zum Vorschauen statt zum tatsächlichen
 * Soft-Löschen.
 */
suspend fun ermittleRetentionVorschau(dao: NoiseDao, aufbewahrungsTage: Int): RetentionVorschau =
    withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (aufbewahrungsTage * 24L * 60 * 60 * 1000)
        val kandidaten = dao.getAutoRetentionCandidates(cutoff)
        val bytes = kandidaten.sumOf { record ->
            val datei = File(record.filePath)
            if (datei.exists()) datei.length() else 0L
        }
        RetentionVorschau(anzahlAufnahmen = kandidaten.size, audioBytes = bytes)
    }

private const val DATENBANK_NAME = "noise_database"

/**
 * PROMPT_M10_FUNKTIONEN.md F5: belegter Platz getrennt nach Audiodateien und Datenbank, damit
 * die Einstellungen zeigen können, was eine Auto-Bereinigung überhaupt betrifft. Audiodateien
 * liegen alle direkt in `getExternalFilesDir(null)` (siehe
 * [com.example.lrmprotokoll.audio.AudioRecordingService]s `starteWavAufnahme`), die
 * Datenbankdatei unter dem festen Namen [DATENBANK_NAME] (siehe
 * [com.example.lrmprotokoll.data.AppDatabase]). Zählt die Dateien direkt vom Dateisystem statt
 * über die DB-Einträge zu joinen - zeigt damit den tatsächlichen Plattenverbrauch, auch falls
 * eine Bereinigung je einmal einen verwaisten Dateieintrag zurückließe.
 */
suspend fun ermittleSpeicherplatz(context: Context): SpeicherplatzUebersicht = withContext(Dispatchers.IO) {
    val audioBytes = context.getExternalFilesDir(null)
        ?.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
        ?.sumOf { it.length() }
        ?: 0L
    val datenbankDatei = context.getDatabasePath(DATENBANK_NAME)
    val datenbankBytes = if (datenbankDatei.exists()) datenbankDatei.length() else 0L
    SpeicherplatzUebersicht(audioBytes = audioBytes, datenbankBytes = datenbankBytes)
}

/**
 * Reine, JVM-testbare Formatierung - B/KB/MB/GB je nach Größenordnung, eine Nachkommastelle
 * ab KB, damit auch bei GB-großen Sessions keine unleserliche Ziffernkolonne entsteht.
 */
fun formatiereBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val einheiten = listOf("KB", "MB", "GB", "TB")
    var wert = bytes.toDouble()
    var index = -1
    while (wert >= 1024 && index < einheiten.lastIndex) {
        wert /= 1024
        index++
    }
    return String.format(Locale.getDefault(), "%.1f %s", wert, einheiten[index])
}
