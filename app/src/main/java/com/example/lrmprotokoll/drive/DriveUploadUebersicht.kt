package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.BeweisVideoEntity
import com.example.lrmprotokoll.data.DokumentationsFotoEntity
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState

/** In welchem Zustand ein Eintrag der Upload-Uebersicht steht. */
enum class UploadZustand { HOCHGELADEN, LAEUFT, OFFEN, FEHLGESCHLAGEN }

/**
 * Eine Zeile der Upload-Uebersicht.
 *
 * [gesendeteBytes]/[gesamtBytes] sind nur bei [UploadZustand.LAEUFT] aussagekraeftig - nur der
 * resumable Video-Upload meldet einen Zwischenstand. Fuer alle anderen Dateiarten gibt es
 * keinen: Sie gehen in einem einzigen Aufruf raus, es gibt also nur "davor" und "danach". Einen
 * Fortschrittsbalken zu erfinden, wo keiner gemessen wird, waere gelogen.
 */
data class UploadEintrag(
    val bezeichnung: String,
    val kategorie: DriveKategorie,
    val zustand: UploadZustand,
    val zeitpunkt: Long,
    val gesendeteBytes: Long = 0,
    val gesamtBytes: Long = 0,
) {
    val prozent: Int?
        get() = if (zustand == UploadZustand.LAEUFT && gesamtBytes > 0) {
            ((gesendeteBytes * 100) / gesamtBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }
}

/**
 * Baut die Upload-Uebersicht aus dem, was tatsaechlich bekannt ist (Owner-Wunsch: "Was wurde
 * hochgeladen, was wird gerade hochgeladen").
 *
 * Bewusst eine reine Funktion ohne Room und ohne Netz: Was hier steht, ist eine Aussage
 * gegenueber dem Nutzer darueber, ob seine Beweise gesichert sind. Sie muss stimmen, und sie
 * muss ohne Geraet pruefbar sein.
 */
object DriveUploadUebersicht {

    fun baue(
        tagesdateien: List<DriveDailyFileEntity>,
        fotos: List<DokumentationsFotoEntity>,
        videos: List<BeweisVideoEntity>,
    ): List<UploadEintrag> {
        val eintraege = mutableListOf<UploadEintrag>()

        tagesdateien.forEach { tag ->
            eintraege += UploadEintrag(
                bezeichnung = "Messwerte ${tag.date}",
                kategorie = DriveKategorie.SCHALLMESSUNG,
                zustand = when (tag.state) {
                    DriveSyncState.SYNCED -> UploadZustand.HOCHGELADEN
                    DriveSyncState.FAILED -> UploadZustand.FEHLGESCHLAGEN
                    else -> UploadZustand.OFFEN
                },
                zeitpunkt = tag.lastSyncedAt,
            )
        }

        fotos.forEach { foto ->
            eintraege += UploadEintrag(
                bezeichnung = foto.dateiPfad.substringAfterLast('/'),
                kategorie = DriveKategorie.FOTOS,
                zustand = if (foto.driveFileId != null) UploadZustand.HOCHGELADEN else UploadZustand.OFFEN,
                zeitpunkt = foto.aufgenommenAm,
            )
        }

        videos.forEach { video ->
            eintraege += UploadEintrag(
                bezeichnung = video.dateiPfad.substringAfterLast('/'),
                kategorie = DriveKategorie.VIDEOS,
                zustand = when {
                    video.driveFileId != null -> UploadZustand.HOCHGELADEN
                    // Ein begonnener, aber nicht abgeschlossener resumable Upload: Genau das
                    // meint "wird gerade hochgeladen".
                    video.uploadSessionUri != null -> UploadZustand.LAEUFT
                    else -> UploadZustand.OFFEN
                },
                zeitpunkt = video.gestartetAm,
                gesendeteBytes = video.hochgeladeneBytes,
                gesamtBytes = video.groesseBytes,
            )
        }

        // Neueste zuerst - was gerade passiert, interessiert am meisten.
        return eintraege.sortedByDescending { it.zeitpunkt }
    }

    /** Kurzfassung fuer die Kopfzeile: wie viele Eintraege je Zustand. */
    fun zusammenfassung(eintraege: List<UploadEintrag>): Map<UploadZustand, Int> =
        UploadZustand.entries.associateWith { zustand -> eintraege.count { it.zustand == zustand } }
}
