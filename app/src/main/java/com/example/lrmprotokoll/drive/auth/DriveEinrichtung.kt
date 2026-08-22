package com.example.lrmprotokoll.drive.auth

import android.content.Context
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.DriveApiClient

import com.example.lrmprotokoll.drive.DriveDatei

/**
 * Einrichtungsschritt und Ordner-Management aus den Einstellungen ("Mit Google verbinden" & Ordner-Picker):
 * meldet an, fordert den drive.file-Scope an und verwaltet den Sync-Ordner (Plan 8.4.3).
 *
 * Sucht vor dem Neuanlegen immer erst nach bestehenden Ordnern gleichen Namens, um Duplikate zu verhindern.
 */
class DriveEinrichtung(
    private val context: Context,
    private val settings: SettingsManager,
    private val driveApi: DriveApiClient,
    private val tokenProvider: GoogleSignInAccessTokenProvider? = null,
) {
    suspend fun richteEin(ordnerName: String): Result<Unit> = runCatching {
        if (settings.googleAccountEmail.isNullOrBlank() && tokenProvider != null) {
            tokenProvider.meldeAnInteraktiv().getOrThrow()
        }
        val trimmed = ordnerName.trim().ifBlank { "Lärmprotokoll" }
        val bestehend = driveApi.ordnerSuchen(trimmed).getOrNull()
        val folderId = if (bestehend != null) {
            bestehend.id
        } else {
            driveApi.ordnerAnlegen(trimmed).getOrThrow()
        }
        settings.driveFolderId = folderId
        settings.driveFolderName = trimmed
        settings.driveOrdnerBlockiert = false
        settings.driveSyncEnabled = true
        settings.driveSyncLastMessage = "Verbunden und bereit zur Synchronisation"
    }

    suspend fun ladeVerfuegbareOrdner(): Result<List<DriveDatei>> = runCatching {
        driveApi.ordnerAuflisten().getOrThrow()
    }

    suspend fun waehleBestehendenOrdner(folder: DriveDatei): Result<Unit> = runCatching {
        settings.driveFolderId = folder.id
        settings.driveFolderName = folder.name
        settings.driveOrdnerBlockiert = false
        settings.driveSyncEnabled = true
        settings.driveSyncLastMessage = "Zielordner ausgewählt: ${folder.name}"
    }

    suspend fun erstelleNeuenOrdner(name: String): Result<DriveDatei> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Ordnername darf nicht leer sein" }
        val bestehend = driveApi.ordnerSuchen(trimmed).getOrNull()
        val folderId = if (bestehend != null) {
            bestehend.id
        } else {
            driveApi.ordnerAnlegen(trimmed).getOrThrow()
        }
        val datei = DriveDatei(id = folderId, name = trimmed)
        settings.driveFolderId = folderId
        settings.driveFolderName = trimmed
        settings.driveOrdnerBlockiert = false
        settings.driveSyncEnabled = true
        settings.driveSyncLastMessage = "Zielordner eingerichtet: $trimmed"
        datei
    }

    suspend fun benenneOrdnerUm(folderId: String, neuerName: String): Result<Unit> = runCatching {
        val trimmed = neuerName.trim()
        require(trimmed.isNotBlank()) { "Neuer Ordnername darf nicht leer sein" }
        driveApi.ordnerUmbenennen(folderId, trimmed).getOrThrow()
        if (settings.driveFolderId == folderId) {
            settings.driveFolderName = trimmed
        }
        settings.driveSyncLastMessage = "Ordner umbenannt in: $trimmed"
    }
}
