package com.example.lrmprotokoll.drive

import android.util.Log
import com.example.lrmprotokoll.data.DriveDailyFileDao
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.NoiseDao
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.meter.InstantSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "DriveSyncCoordinator"
private const val MIME_TYPE = "text/csv; charset=utf-8"

/**
 * Ein einzelner Sync-Zyklus (Plan Abschnitt 8.4) - die eigentliche Entscheidungslogik, getrennt
 * vom [DriveSyncWorker], der nur noch WorkManager-Glue ist. So bleibt sie ohne WorkManager und
 * ohne echtes Netz testbar, wie [com.example.lrmprotokoll.alert.AlarmCoordinator] fuer M5.
 */
class DriveSyncCoordinator(
    private val driveApi: DriveApiClient,
    private val levelSampleDao: LevelSampleDao,
    private val dailyFileDao: DriveDailyFileDao,
    private val noiseDao: NoiseDao,
    private val settings: SettingsManager,
    private val now: InstantSource = InstantSource.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    sealed interface SyncErgebnis {
        data object SyncAusgeschaltet : SyncErgebnis
        data object KeinOrdnerEingerichtet : SyncErgebnis
        data object OrdnerBlockiert : SyncErgebnis
        data object KeineAenderung : SyncErgebnis
        data class Erfolgreich(val zeilen: Int) : SyncErgebnis
        data class OrdnerNichtGefunden(val httpCode: Int?) : SyncErgebnis
        data class Fehlgeschlagen(val grund: String, val httpCode: Int?) : SyncErgebnis
    }

    suspend fun syncEinenZyklus(): SyncErgebnis {
        if (!settings.driveSyncEnabled) {
            settings.driveSyncLastMessage = "Synchronisation pausiert"
            return SyncErgebnis.SyncAusgeschaltet
        }
        if (settings.driveOrdnerBlockiert) {
            settings.driveSyncLastMessage = "Ordner nicht gefunden – bitte neu verbinden"
            return SyncErgebnis.OrdnerBlockiert
        }
        val ordnerId = settings.driveFolderId
        if (ordnerId == null) {
            settings.driveSyncLastMessage = "Kein Zielordner eingerichtet"
            return SyncErgebnis.KeinOrdnerEingerichtet
        }

        val jetzt = now.now()
        // jetzt.atZone(zone).toLocalDate() statt LocalDate.now(zone): "heute" muss aus derselben
        // injizierten Uhr wie "jetzt" kommen, sonst kann "von" (Tagesbeginn nach der echten
        // Systemuhr) nach "jetzt" (fixe Testuhr) liegen, sobald das echte Kalenderdatum den in
        // einem Test fest verdrahteten Zeitpunkt ueberholt hat - ein "bis darf nicht vor von
        // liegen" in PegelAggregator.aggregiere(), das rein vom aktuellen Tagesdatum abhaengt,
        // nicht vom Testinhalt.
        val heute = jetzt.atZone(zone).toLocalDate()
        val von = heute.atStartOfDay(zone).toInstant()
        val datumSchluessel = heute.toString()

        val samples = levelSampleDao.zwischen(von.toEpochMilli(), jetzt.toEpochMilli())
        val ereignisse = noiseDao.zwischenZeitpunkt(von.toEpochMilli(), jetzt.toEpochMilli())
            .map {
                ProtokollEreignis(
                    at = Instant.ofEpochMilli(it.timestamp),
                    pegelDb = it.calibratedDbA ?: it.dbValue,
                    klassifikation = it.detectedLabel ?: it.label,
                    notes = it.notes,
                    weighting = it.meterWeighting
                )
            }

        val fensterDauer = Duration.ofSeconds(settings.driveAggregationSekunden.toLong())
        val zeilen = PegelAggregator.aggregiere(samples, ereignisse, von, jetzt, fensterDauer)

        var wavUploadedCount = 0
        // WAV-Dateien hochladen, wenn Option aktiviert ist (prüft alle aktiven Aufnahmen mit lokaler Datei)
        if (settings.driveUploadWav) {
            val wavRecords = noiseDao.getAlleAktiven()
            if (wavRecords.isNotEmpty()) {
                val existierendeNamen = driveApi.dateienInOrdnerAuflisten(ordnerId).getOrElse { emptySet() }
                for (record in wavRecords) {
                    val file = java.io.File(record.filePath)
                    if (file.exists() && file.isFile) {
                        val dateiName = file.name
                        if (!existierendeNamen.contains(dateiName)) {
                            val bytes = runCatching { file.readBytes() }.getOrNull()
                            if (bytes != null && bytes.isNotEmpty()) {
                                val uploadResult = driveApi.dateiAnlegen(
                                    name = dateiName,
                                    ordnerId = ordnerId,
                                    inhalt = bytes,
                                    mimeType = "audio/wav",
                                    gzip = false,
                                )
                                if (uploadResult.isFailure) {
                                    val err = uploadResult.exceptionOrNull()
                                    val httpCode = (err as? DriveApiException)?.httpCode
                                    Log.w(TAG, "WAV-Upload fehlgeschlagen für $dateiName: ${err?.message}")
                                    if (httpCode == 403 || httpCode == 429) {
                                        Log.w(TAG, "Drive-Rate-Limit (HTTP $httpCode) beim WAV-Upload erreicht – breche Batch ab")
                                        break
                                    }
                                } else {
                                    wavUploadedCount++
                                    Log.i(TAG, "WAV-Upload erfolgreich für $dateiName (ID: ${uploadResult.getOrNull()})")
                                }
                            }
                        }
                    }
                }
            }
        }

        val registry = dailyFileDao.byDate(datumSchluessel)
        if (zeilen.isEmpty()) {
            if (wavUploadedCount > 0) {
                settings.driveSyncFehlschlaegeInFolge = 0
                settings.driveSyncLastSuccessAt = jetzt.toEpochMilli()
                settings.driveSyncLastMessage = "$wavUploadedCount WAV-Aufnahme(n) erfolgreich synchronisiert"
                return SyncErgebnis.Erfolgreich(wavUploadedCount)
            }
            settings.driveSyncLastMessage = "Keine neuen Messwerte zu synchronisieren"
            return SyncErgebnis.KeineAenderung
        }

        if (registry != null && registry.state == DriveSyncState.SYNCED && registry.lastRowCount == zeilen.size) {
            if (wavUploadedCount > 0) {
                settings.driveSyncFehlschlaegeInFolge = 0
                settings.driveSyncLastSuccessAt = jetzt.toEpochMilli()
                settings.driveSyncLastMessage = "${zeilen.size} Zeilen & $wavUploadedCount WAV(s) synchronisiert"
                return SyncErgebnis.Erfolgreich(zeilen.size)
            }
            settings.driveSyncLastMessage = "Aktuell (${zeilen.size} Zeilen synchronisiert)"
            return SyncErgebnis.KeineAenderung
        }

        val dateiName = "laermprotokoll_$datumSchluessel.csv"
        val inhalt = DriveCsv.schreibe(zeilen, zone).toByteArray(Charsets.UTF_8)

        val ergebnis = schreibeDatei(registry?.fileId, dateiName, ordnerId, inhalt)

        return ergebnis.fold(
            onSuccess = { fileId ->
                dailyFileDao.upsert(
                    DriveDailyFileEntity(
                        date = datumSchluessel, fileId = fileId, lastSyncedAt = jetzt.toEpochMilli(),
                        lastRowCount = zeilen.size, state = DriveSyncState.SYNCED,
                    )
                )
                settings.driveSyncFehlschlaegeInFolge = 0
                settings.driveSyncLastSuccessAt = jetzt.toEpochMilli()
                val message = if (wavUploadedCount > 0) {
                    "${zeilen.size} Zeilen & $wavUploadedCount WAV(s) erfolgreich hochgeladen"
                } else {
                    "${zeilen.size} Zeilen erfolgreich hochgeladen"
                }
                settings.driveSyncLastMessage = message
                SyncErgebnis.Erfolgreich(zeilen.size)
            },
            onFailure = { fehler -> behandleFehlschlag(datumSchluessel, registry, jetzt, fehler) },
        )
    }

    /**
     * Ermittelt die zu beschreibende Datei. Ist bereits eine `fileId` bekannt, wird sie direkt
     * aktualisiert. Sonst wird ERST gesucht (Plan 8.4.4 Absicherung gegen Waisen: ein vorheriger
     * Zyklus koennte `dateiAnlegen` abgeschlossen, aber vor dem Speichern der Antwort abgebrochen
     * sein) und nur bei echtem Fehlen neu angelegt.
     */
    private suspend fun schreibeDatei(
        bekannteFileId: String?,
        dateiName: String,
        ordnerId: String,
        inhalt: ByteArray,
    ): Result<String> {
        if (bekannteFileId != null) {
            return driveApi.dateiAktualisieren(bekannteFileId, inhalt, MIME_TYPE, gzip = true).map { bekannteFileId }
        }

        val gefunden = driveApi.dateiSuchen(dateiName, ordnerId).getOrElse { return Result.failure(it) }
        if (gefunden != null) {
            return driveApi.dateiAktualisieren(gefunden.id, inhalt, MIME_TYPE, gzip = true).map { gefunden.id }
        }
        return driveApi.dateiAnlegen(dateiName, ordnerId, inhalt, MIME_TYPE, gzip = true)
    }

    private suspend fun behandleFehlschlag(
        datumSchluessel: String,
        registry: DriveDailyFileEntity?,
        jetzt: Instant,
        fehler: Throwable,
    ): SyncErgebnis {
        val httpCode = (fehler as? DriveApiException)?.httpCode
        Log.w(TAG, "Sync-Zyklus fehlgeschlagen (HTTP $httpCode)", fehler)
        settings.driveSyncFehlschlaegeInFolge += 1

        // 404 beim Aktualisieren einer bekannten fileId heisst: die DATEI ist weg. fileId
        // verwerfen, damit der naechste Zyklus per Suche/Neuanlage repariert (Plan 8.4.6).
        if (httpCode == 404 && registry?.fileId != null) {
            dailyFileDao.upsert(
                DriveDailyFileEntity(
                    date = datumSchluessel, fileId = null,
                    lastSyncedAt = registry.lastSyncedAt, lastRowCount = registry.lastRowCount,
                    state = DriveSyncState.FAILED,
                )
            )
            return SyncErgebnis.Fehlgeschlagen("Datei nicht gefunden, wird neu angelegt", httpCode)
        }

        // 404 OHNE bekannte fileId heisst: schon die Suche/das Anlegen im ORDNER scheiterte -
        // der Ordner selbst ist vermutlich weg. Sync pausieren statt lautlos in "Meine Ablage"
        // zu schreiben (Plan 8.4.6) - der Nutzer muss aktiv neu waehlen.
        if (httpCode == 404 && registry?.fileId == null) {
            settings.driveOrdnerBlockiert = true
            return SyncErgebnis.OrdnerNichtGefunden(httpCode)
        }

        dailyFileDao.upsert(
            DriveDailyFileEntity(
                date = datumSchluessel,
                fileId = registry?.fileId,
                lastSyncedAt = registry?.lastSyncedAt ?: jetzt.toEpochMilli(),
                lastRowCount = registry?.lastRowCount ?: 0,
                state = DriveSyncState.FAILED,
            )
        )
        val grund = when {
            httpCode == 403 || httpCode == 429 -> "Google Drive Übertragungslimit erreicht – nächster Versuch in 30 Min."
            httpCode == 401 -> "Anmeldung abgelaufen – bitte in Einstellungen neu verbinden"
            else -> fehler.message ?: "Unbekannter Fehler"
        }
        settings.driveSyncLastMessage = if (grund.startsWith("Google Drive") || grund.startsWith("Anmeldung")) grund else "Fehler: $grund"
        return SyncErgebnis.Fehlgeschlagen(grund, httpCode)
    }
}
