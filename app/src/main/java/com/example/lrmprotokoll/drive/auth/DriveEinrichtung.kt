package com.example.lrmprotokoll.drive.auth

import android.content.Context
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.drive.DriveApiClient

/**
 * Einmaliger Einrichtungsschritt aus den Einstellungen ("Mit Google verbinden"): meldet an,
 * fordert den drive.file-Scope an und legt den Sync-Ordner an (Plan 8.4.3 - "Die App legt beim
 * Einrichten einen Ordner an ... merkt sich dessen folderId").
 *
 * Getrennt von [GoogleSignInAccessTokenProvider], weil die Ordnerwahl nur EINMAL passiert,
 * waehrend das Token vor JEDEM Sync-Zyklus neu geholt wird - zwei unterschiedliche Lebenszyklen,
 * die eine gemeinsame Klasse verwischen wuerde.
 */
class DriveEinrichtung(
    private val context: Context,
    private val settings: SettingsManager,
    private val driveApi: DriveApiClient,
) {
    suspend fun richteEin(ordnerName: String): Result<Unit> = runCatching {
        val folderId = driveApi.ordnerAnlegen(ordnerName).getOrThrow()
        settings.driveFolderId = folderId
        settings.driveFolderName = ordnerName
        settings.driveOrdnerBlockiert = false
    }
}
