package com.example.lrmprotokoll.drive

import java.io.File

internal const val BACKUP_ORDNERNAME = "BACKUP"
internal const val BACKUP_DATEINAME = "laermprotokoll_datenbank.zip"
private const val BACKUP_MIME_TYPE = "application/zip"

/**
 * Vollstaendige, verlustfreie Sicherung der Room-Datenbank in Drive - Ergaenzung zur taeglichen,
 * aggregierten CSV ([DriveCsv]), die fuer eine echte Wiederherstellung zu grob ist: Sie enthaelt
 * je Zeitfenster nur ein energetisches Mittel, keine Session-/Geraetedaten und keine
 * YAMNet-Rohdaten (Owner-Entscheidung 09.09.2026 nach Rueckfrage: "Die Rohdaten extra ablegen,
 * dann herunterladen" statt aus der CSV zu rekonstruieren).
 *
 * Liegt bewusst NICHT im Tagesordner-Baum ([DriveOrdnerbaum]): Es ist kein Tagesartefakt, sondern
 * eine rollierende Momentaufnahme der gesamten Historie - eine Ebene ueber den Tagesordnern, in
 * `<gewaehlter Ordner>/BACKUP/` (Owner-Vorgabe). Ein einziger, bei jedem Zyklus ueberschriebener
 * Dateiname statt Tagesdateien: Anders als WAV/Fotos/Videos ist eine Datenbanksicherung immer ein
 * Abbild von allem bis jetzt, kein Zuwachs pro Tag - taeglich neue Dateien wuerden nur denselben
 * Inhalt vervielfachen.
 *
 * Wiederverwendet [com.example.lrmprotokoll.backup.SicherungManager]s ZIP-Format
 * (Manifest + Einstellungen + Datenbank nach WAL-Checkpoint) 1:1 - dieselbe Datei laesst sich
 * also auch ueber die bestehende lokale Wiederherstellung (F13) einspielen.
 */
object DriveDatenbankSicherung {

    /** Findet den `BACKUP`-Ordner oder legt ihn an - direkt unter dem gewaehlten Wurzelordner. */
    suspend fun ordnerSicherstellen(client: DriveApiClient, wurzelOrdnerId: String): Result<String> = runCatching {
        client.ordnerSuchen(BACKUP_ORDNERNAME, wurzelOrdnerId).getOrThrow()?.id
            ?: client.ordnerAnlegen(BACKUP_ORDNERNAME, wurzelOrdnerId).getOrThrow()
    }

    /**
     * Legt [datei] als Sicherungsdatei an oder aktualisiert die bestehende STREAMEND (Bugfix
     * 23.09.2026, docs/PROMPT_FIX_DATENBANK_SICHERUNG.md Schritt 3) - dieselbe Waisen-Absicherung
     * wie beim CSV-/WAV-/Foto-Upload (erst suchen, nur bei echtem Fehlen neu anlegen). Ersetzt das
     * frühere `hochladen(bytes: ByteArray)`: [datei] wird nie vollständig eingelesen, genau wie
     * beim resumable Video-Upload (M11 Etappe B) - eine ~500-MB-Sicherung als `ByteArray` war
     * genau der `OutOfMemoryError`, der diesen Umbau ausgelöst hat
     * (`docs/BEFUNDE_P30_2026-09-23.md`, Abschnitt 2/A2). Es bleibt in JEDEM Fall genau EINE
     * Sicherungsdatei in Drive - die alte wird erst durch einen erfolgreichen Upload ersetzt,
     * nie vorher gelöscht.
     */
    suspend fun hochladen(
        client: DriveApiClient,
        wurzelOrdnerId: String,
        datei: File,
    ): Result<Unit> =
        runCatching {
            val ordnerId = ordnerSicherstellen(client, wurzelOrdnerId).getOrThrow()
            val bestehende = client.dateiSuchen(BACKUP_DATEINAME, ordnerId).getOrThrow()
            if (bestehende != null) {
                client.dateiAktualisierenResumable(bestehende.id, datei, BACKUP_MIME_TYPE).getOrThrow()
            } else {
                client.dateiHochladenResumable(BACKUP_DATEINAME, ordnerId, datei, BACKUP_MIME_TYPE).getOrThrow()
            }
            Unit
        }

    /**
     * Laedt die aktuelle Sicherung STREAMEND nach [ziel] herunter - Fehler, wenn noch keine
     * hochgeladen wurde. Ersetzt das frühere `herunterladen(): Result<ByteArray>`, aus demselben
     * Grund wie [hochladen].
     */
    suspend fun herunterladen(
        client: DriveApiClient,
        wurzelOrdnerId: String,
        ziel: File,
    ): Result<Unit> =
        runCatching {
            val ordner = client.ordnerSuchen(BACKUP_ORDNERNAME, wurzelOrdnerId).getOrThrow()
                ?: error("Kein Backup-Ordner in Drive gefunden - noch keine Sicherung hochgeladen?")
            val datei = client.dateiSuchen(BACKUP_DATEINAME, ordner.id).getOrThrow()
                ?: error("Keine Datenbank-Sicherung in Drive gefunden.")
            client.dateiHerunterladenNach(datei.id, ziel).getOrThrow()
        }
}
