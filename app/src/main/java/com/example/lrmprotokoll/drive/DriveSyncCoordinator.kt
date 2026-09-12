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
    /**
     * Optional, damit die zahlreichen bestehenden Test-Aufbauten nicht alle ein weiteres Fake
     * mitschleppen muessen: `null` heisst schlicht "keine Fotos hochladen".
     */
    private val dokumentationsFotoDao: com.example.lrmprotokoll.data.DokumentationsFotoDao? = null,
    /** Wie [dokumentationsFotoDao]: `null` heisst "keine Videos hochladen" (M11 Etappe B). */
    private val beweisVideoDao: com.example.lrmprotokoll.data.BeweisVideoDao? = null,
    private val diagnosticsReporter: com.example.lrmprotokoll.diagnose.DiagnosticsReporter? = null,
    /**
     * Liefert die Datenbanksicherung als ZIP-Bytes (siehe [DriveDatenbankSicherung]). `null`
     * heisst "keine automatische Datenbank-Sicherung" - wie [dokumentationsFotoDao]/
     * [beweisVideoDao] optional, damit bestehende Test-Aufbauten nicht alle ein weiteres Fake
     * mitschleppen muessen. Als Funktion statt eines direkten [android.content.Context]-Zugriffs,
     * damit der Koordinator selbst ohne Android-Abhaengigkeit bleibt und testbar.
     */
    private val datenbankSicherungQuelle: (suspend () -> ByteArray)? = null,
) {

    /**
     * Loest die Tagesablage `<gewaehlter Ordner>/JJJJMMTT/<Kategorie>` auf (Owner-Vorgabe).
     * Gehoert dem Koordinator, damit sein Zwischenspeicher einen ganzen Zyklus ueberdauert.
     */
    private val ordnerbaum = DriveOrdnerbaum(driveApi)

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
        val datumSchluessel = DriveAblage.tagesordner(jetzt, zone)

        // Praefprotokoll-Anhang (Owner-Entscheidung vom 11.09.2026: "Sync soll die letzten 30
        // Tage pruefen und syncen") - siehe holeVersaeumteTageNach()-KDoc. Best-effort, VOR dem
        // eigentlichen Sync fuer HEUTE: ein Fehlschlag beim Nachholen darf den Sync fuer heute
        // nie verhindern.
        holeVersaeumteTageNach(ordnerId, heute, jetzt)

        // Praefprotokoll-Befund 03 / Korrekturliste C-4 (Owner-Entscheidung vom 11.09.2026:
        // "loescheVor() verdrahten"): ohne das waechst level_samples unbegrenzt. Exakt 30 Tage,
        // weil holeVersaeumteTageNach() oben bis zu 29 Tage zurueck liest (tagOffset 1..29) - eine
        // kuerzere Frist wuerde Rohwerte loeschen, bevor der Nachholsync sie je sehen kann.
        // runCatching wie bei holeVersaeumteTageNach: ein Fehlschlag hier darf weder den
        // Nachholsync noch den Sync fuer heute verhindern.
        runCatching { levelSampleDao.loescheVor(jetzt.minus(Duration.ofDays(30)).toEpochMilli()) }
            .onFailure { Log.w(TAG, "Puffer-Bereinigung (loescheVor) fehlgeschlagen: ${it.message}") }

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

        var zipPackagesUploadedCount = 0
        var totalWavCountInZips = 0

        // WAV-Dateien in stündliche 1h-ZIP-Archive bündeln und hochladen, wenn Option aktiviert ist
        ladeFotosHoch(ordnerId)
        ladeVideosHoch(ordnerId)
        ladeDatenbankSicherungHoch(ordnerId)

        if (settings.driveUploadWav) {
            val wavRecords = noiseDao.getAlleAktiven()
            if (wavRecords.isNotEmpty()) {
                val stundenZips = WavHourlyZipper.packeStundenZips(wavRecords, jetzt, zone)
                if (stundenZips.isNotEmpty()) {
                    // Die WAV-Pakete gehoeren in den jeweiligen Tagesordner des Messtages (Aufnahmedatum),
                    // nicht pauschal in den des Upload-Tags. Cache für Ordner-Listings, um wiederholte API-Aufrufe zu vermeiden.
                    val ordnerDateienCache = mutableMapOf<String, MutableSet<String>>()

                    for (zipPackage in stundenZips) {
                        val dateiName = zipPackage.zipFileName
                        val tagesSchluessel = zipPackage.tagesordner

                        // Geschlossene Stunde und lokal bereits als hochgeladen registriert -> Ueberspringen
                        if (zipPackage.isClosedHour && settings.istZipBereitsHochgeladen(dateiName)) {
                            continue
                        }

                        val wavOrdner = ordnerbaum.ordnerFuer(ordnerId, tagesSchluessel, DriveKategorie.WAV)
                            .getOrElse { ordnerId }

                        val existierendeNamen = ordnerDateienCache.getOrPut(wavOrdner) {
                            driveApi.dateienInOrdnerAuflisten(wavOrdner)
                                .getOrElse { emptySet() }
                                .toMutableSet()
                        }

                        // Wenn die Datei noch nicht auf Drive im Tagesordner existiert -> Neu anlegen
                        if (!existierendeNamen.contains(dateiName)) {
                            val inhalt = zipPackage.zipBytes
                            if (inhalt.isEmpty()) {
                                Log.w(TAG, "ZIP-Inhalt für $dateiName ist leer – überspringe")
                                continue
                            }
                            val uploadResult = driveApi.dateiAnlegen(
                                name = dateiName,
                                ordnerId = wavOrdner,
                                inhalt = inhalt,
                                mimeType = "application/zip",
                                gzip = false,
                            )
                            if (uploadResult.isFailure) {
                                val err = uploadResult.exceptionOrNull()
                                val httpCode = (err as? DriveApiException)?.httpCode
                                Log.w(TAG, "ZIP-Upload fehlgeschlagen für $dateiName in $tagesSchluessel/WAV: ${err?.message}")
                                // Bugfix (Owner-Meldung 12.09.2026, "WAV fehlt in Drive"): dieser
                                // Pfad meldete Fehlschlaege bisher NUR per Log.w (Logcat) - anders
                                // als ladeFotosHoch()/ladeDatenbankSicherungHoch() landete ein
                                // fehlgeschlagener WAV-Upload nie im Diagnoseprotokoll/
                                // Support-Bundle. Genau das hat die Fehlersuche zu diesem Fall
                                // verhindert: das Support-Bundle zeigte "Kein Zugriffstoken
                                // verfuegbar" nur fuer die Datenbank-Sicherung, obwohl derselbe
                                // Tokenfehler vermutlich auch den WAV-Upload betraf.
                                diagnosticsReporter?.report(
                                    code = com.example.lrmprotokoll.diagnose.DiagnosticCode.DRIVE_UPLOAD_FAILED,
                                    component = "DriveSyncCoordinator",
                                    operation = "ladeWavZipsHoch",
                                    severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                                    cause = err,
                                    details = mapOf(
                                        "dateiName" to dateiName,
                                        "tagesordner" to tagesSchluessel,
                                        "httpCode" to httpCode,
                                        "wavCount" to zipPackage.wavCount,
                                    ),
                                )
                                if (httpCode == 403 || httpCode == 429) {
                                    Log.w(TAG, "Drive-Rate-Limit (HTTP $httpCode) beim ZIP-Upload erreicht – breche Batch ab")
                                    break
                                }
                            } else {
                                existierendeNamen.add(dateiName)
                                if (zipPackage.isClosedHour) {
                                    settings.markiereZipAlsHochgeladen(dateiName)
                                }
                                zipPackagesUploadedCount++
                                totalWavCountInZips += zipPackage.wavCount
                                Log.i(TAG, "Stündliches ZIP-Archiv hochgeladen: $dateiName in $tagesSchluessel/WAV (${zipPackage.wavCount} WAVs)")
                                diagnosticsReporter?.breadcrumb(
                                    "DriveSync",
                                    "Stündliches WAV-ZIP hochgeladen: $dateiName",
                                    data = mapOf("tagesordner" to tagesSchluessel, "wavCount" to zipPackage.wavCount),
                                )
                            }
                        } else if (!zipPackage.isClosedHour) {
                            // Laufende Stunde existiert bereits, hat aber eventuell neue WAVs erhalten -> Aktualisieren
                            val suchenResult = driveApi.dateiSuchen(dateiName, wavOrdner)
                            val existierendeDatei = suchenResult.getOrNull()
                            if (existierendeDatei != null) {
                                val inhalt = zipPackage.zipBytes
                                if (inhalt.isNotEmpty()) {
                                    val updateResult = driveApi.dateiAktualisieren(
                                        fileId = existierendeDatei.id,
                                        inhalt = inhalt,
                                        mimeType = "application/zip",
                                        gzip = false,
                                    )
                                    if (updateResult.isSuccess) {
                                        zipPackagesUploadedCount++
                                        totalWavCountInZips += zipPackage.wavCount
                                        Log.i(TAG, "Stündliches ZIP-Archiv aktualisiert: $dateiName in $tagesSchluessel/WAV")
                                    } else {
                                        val err = updateResult.exceptionOrNull()
                                        Log.w(TAG, "ZIP-Aktualisierung fehlgeschlagen für $dateiName in $tagesSchluessel/WAV: ${err?.message}")
                                        diagnosticsReporter?.report(
                                            code = com.example.lrmprotokoll.diagnose.DiagnosticCode.DRIVE_UPLOAD_FAILED,
                                            component = "DriveSyncCoordinator",
                                            operation = "ladeWavZipsHoch.aktualisieren",
                                            severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                                            cause = err,
                                            details = mapOf("dateiName" to dateiName, "tagesordner" to tagesSchluessel),
                                        )
                                    }
                                }
                            }
                        } else {
                            // Bereits auf Drive vorhanden und geschlossene Stunde -> im lokalen Cache merken
                            settings.markiereZipAlsHochgeladen(dateiName)
                        }
                    }
                }
            }
        }

        val registry = dailyFileDao.byDate(datumSchluessel)
        if (zeilen.isEmpty()) {
            if (zipPackagesUploadedCount > 0) {
                settings.driveSyncFehlschlaegeInFolge = 0
                settings.driveSyncLastSuccessAt = jetzt.toEpochMilli()
                settings.driveSyncLastMessage = "$zipPackagesUploadedCount ZIP-Paket(e) ($totalWavCountInZips WAVs) synchronisiert"
                return SyncErgebnis.Erfolgreich(zipPackagesUploadedCount)
            }
            settings.driveSyncLastMessage = "Keine neuen Messwerte zu synchronisieren"
            return SyncErgebnis.KeineAenderung
        }

        if (registry != null && registry.state == DriveSyncState.SYNCED && registry.lastRowCount == zeilen.size) {
            if (zipPackagesUploadedCount > 0) {
                settings.driveSyncFehlschlaegeInFolge = 0
                settings.driveSyncLastSuccessAt = jetzt.toEpochMilli()
                settings.driveSyncLastMessage = "${zeilen.size} Zeilen & $zipPackagesUploadedCount ZIP(s) synchronisiert"
                return SyncErgebnis.Erfolgreich(zeilen.size)
            }
            settings.driveSyncLastMessage = "Aktuell (${zeilen.size} Zeilen synchronisiert)"
            return SyncErgebnis.KeineAenderung
        }

        val dateiName = "laermprotokoll_$datumSchluessel.csv"
        val inhalt = DriveCsv.schreibe(zeilen, zone).toByteArray(Charsets.UTF_8)

        val messOrdner = ordnerbaum.ordnerFuer(ordnerId, datumSchluessel, DriveKategorie.SCHALLMESSUNG)
            .getOrElse { ordnerId }
        val ergebnis = schreibeDatei(registry?.fileId, dateiName, messOrdner, inhalt)

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
                val message = if (zipPackagesUploadedCount > 0) {
                    "${zeilen.size} Zeilen & $zipPackagesUploadedCount ZIP(s) ($totalWavCountInZips WAVs) hochgeladen"
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
     * Holt Tage der letzten 30 Tage nach, die noch nicht (vollstaendig) synchronisiert sind
     * (Prüfprotokoll-Anhang, Owner-Entscheidung vom 11.09.2026: "Sync soll die letzten 30 Tage
     * prüfen und syncen"). Vorher schaute [syncEinenZyklus] ausschließlich auf `[heute 00:00,
     * jetzt)` - blieb die App über Mitternacht offline, wurden gestrige Rohwerte nie hochgeladen.
     * Möglich wird das Nachholen, WEIL [syncEinenZyklus] [LevelSampleDao.loescheVor] erst mit
     * einer 30-Tage-Frist aufruft (Befund 03 / Korrekturliste C-4) - eine Frist von genau 29
     * Tagen oder weniger würde Rohwerte löschen, bevor dieses Nachholen sie je sehen könnte.
     *
     * Bewusst NICHT Teil des regulären, gut getesteten Pfads für HEUTE - eine separate Methode
     * hält das Risiko für den bestehenden, produktiven Ablauf bei null. Jeder Tag wird einzeln
     * mit `runCatching` abgesichert: ein Fehlschlag bei einem Tag (z. B. HTTP 429) darf weder die
     * übrigen 28 Tage noch den Sync für heute verhindern.
     *
     * [heute] wird ausgeschlossen (`tagOffset in 1..29`) - der bleibt beim bestehenden,
     * unveränderten Codepfad in [syncEinenZyklus] darunter.
     */
    private suspend fun holeVersaeumteTageNach(ordnerId: String, heute: java.time.LocalDate, jetzt: Instant) {
        for (tagOffset in 1..29) {
            val tag = heute.minusDays(tagOffset.toLong())
            val tagVon = tag.atStartOfDay(zone).toInstant()
            val tagBis = tag.plusDays(1).atStartOfDay(zone).toInstant()
            val tagesSchluessel = DriveAblage.tagesordner(tagVon, zone)

            runCatching {
                val samples = levelSampleDao.zwischen(tagVon.toEpochMilli(), tagBis.toEpochMilli())
                if (samples.isEmpty()) return@runCatching

                val ereignisse = noiseDao.zwischenZeitpunkt(tagVon.toEpochMilli(), tagBis.toEpochMilli())
                    .map {
                        ProtokollEreignis(
                            at = Instant.ofEpochMilli(it.timestamp),
                            pegelDb = it.calibratedDbA ?: it.dbValue,
                            klassifikation = it.detectedLabel ?: it.label,
                            notes = it.notes,
                            weighting = it.meterWeighting,
                        )
                    }
                val fensterDauer = Duration.ofSeconds(settings.driveAggregationSekunden.toLong())
                val zeilen = PegelAggregator.aggregiere(samples, ereignisse, tagVon, tagBis, fensterDauer)
                if (zeilen.isEmpty()) return@runCatching

                val registry = dailyFileDao.byDate(tagesSchluessel)
                if (registry != null && registry.state == DriveSyncState.SYNCED && registry.lastRowCount == zeilen.size) {
                    return@runCatching // dieser Tag ist bereits vollstaendig synchronisiert
                }

                val dateiName = "laermprotokoll_$tagesSchluessel.csv"
                val inhalt = DriveCsv.schreibe(zeilen, zone).toByteArray(Charsets.UTF_8)
                val messOrdner = ordnerbaum.ordnerFuer(ordnerId, tagesSchluessel, DriveKategorie.SCHALLMESSUNG)
                    .getOrElse { ordnerId }

                schreibeDatei(registry?.fileId, dateiName, messOrdner, inhalt).onSuccess { fileId ->
                    dailyFileDao.upsert(
                        DriveDailyFileEntity(
                            date = tagesSchluessel, fileId = fileId, lastSyncedAt = jetzt.toEpochMilli(),
                            lastRowCount = zeilen.size, state = DriveSyncState.SYNCED,
                        )
                    )
                    diagnosticsReporter?.breadcrumb(
                        "DriveSync",
                        "Versäumten Tag nachgeholt: $tagesSchluessel (${zeilen.size} Zeilen)",
                    )
                }.getOrThrow()
            }.onFailure { fehler ->
                Log.w(TAG, "Nachholen von $tagesSchluessel fehlgeschlagen: ${fehler.message}")
            }
        }
    }

    /**
     * Ermittelt die zu beschreibende Datei. Ist bereits eine `fileId` bekannt, wird sie direkt
     * aktualisiert. Sonst wird ERST gesucht (Plan 8.4.4 Absicherung gegen Waisen: ein vorheriger
     * Zyklus koennte `dateiAnlegen` abgeschlossen, aber vor dem Speichern der Antwort abgebrochen
     * sein) und nur bei echtem Fehlen neu angelegt.
     */
    /**
     * Laedt Belegfotos hoch (M11 Etappe A). Nutzt bewusst den bestehenden
     * [DriveApiClient.dateiAnlegen]-Pfad: Ein herunterskaliertes JPEG liegt weit unter 1 MB, der
     * Spitzenspeicher von rund dem Doppelten der Dateigroesse ist dafuer unproblematisch.
     *
     * `gzip = false`, weil JPEG bereits komprimiert ist - Gzip darueber kostet CPU und bringt
     * nichts. Eine gesetzte [DokumentationsFotoEntity.driveFileId] ist zugleich die
     * Idempotenz-Sicherung: Ein Foto wird nie zweimal hochgeladen.
     */
    private suspend fun ladeFotosHoch(ordnerId: String) {
        if (!settings.fotoDokuDriveUpload) return
        val dao = dokumentationsFotoDao ?: return

        val offene = runCatching { dao.nichtHochgeladene() }.getOrDefault(emptyList())
        if (offene.isNotEmpty()) {
            diagnosticsReporter?.breadcrumb(
                "DriveSync",
                "Foto-Upload gestartet (${offene.size} offene(s) Foto(s))",
            )
        }
        for (foto in offene) {
            val datei = java.io.File(foto.dateiPfad)
            if (!datei.exists()) continue

            val name = datei.name
            // Tagesordner nach dem AUFNAHMEdatum des Fotos, nicht nach dem Upload-Zeitpunkt.
            val ziel = ordnerbaum.ordnerFuer(
                ordnerId,
                DriveAblage.tagesordner(foto.aufgenommenAm, zone),
                DriveKategorie.FOTOS,
            ).getOrElse { continue }

            // Waisen-Absicherung wie bei CSV und WAV: Ein vorheriger, halb fehlgeschlagener
            // Versuch koennte die Datei bereits angelegt haben.
            val vorhanden = driveApi.dateiSuchen(name, ziel).getOrNull()
            if (vorhanden != null) {
                runCatching { dao.setzeDriveFileId(foto.id, vorhanden.id) }
                continue
            }

            val inhalt = runCatching { datei.readBytes() }.getOrNull() ?: continue
            driveApi.dateiAnlegen(name, ziel, inhalt, "image/jpeg", gzip = false)
                .onSuccess { fileId ->
                    runCatching { dao.setzeDriveFileId(foto.id, fileId) }
                    diagnosticsReporter?.breadcrumb("DriveSync", "Foto erfolgreich hochgeladen: $name")
                }
                .onFailure { fehler ->
                    diagnosticsReporter?.breadcrumb("DriveSync", "Foto-Upload fehlgeschlagen ($name): ${fehler.message}")
                }
        }
    }

    /**
     * Laedt Beweisvideos hoch (M11 Etappe B, B.6).
     *
     * Drei Unterschiede zu [ladeFotosHoch], die alle Absicht sind:
     *
     * 1. **Eigener Schalter, Default AUS.** Ein Video kann Dritte, Kennzeichen und
     *    Wohnungsinneres zeigen - die datenschutzsensibelste Datenart der App. Ohne
     *    ausdrueckliche Zustimmung geht hier nichts raus.
     * 2. **Resumable statt [DriveApiClient.dateiAnlegen].** Ein Video ist ein Vielfaches des
     *    verfuegbaren Heaps gross; der einfache Pfad waere ein sicherer OutOfMemoryError.
     *    WorkManager gibt einem Worker ausserdem nur rund zehn Minuten - ein grosses Video
     *    ueberlebt das nicht in einem Durchgang, und genau dafuer wird der Fortschritt
     *    gespeichert.
     * 3. **Nur fertig gemuxte Videos**, siehe [com.example.lrmprotokoll.data.BeweisVideoDao.nichtHochgeladene]:
     *    Sonst landete die stumme Zwischenfassung in Drive.
     */
    private suspend fun ladeVideosHoch(ordnerId: String) {
        if (!settings.videoDriveUpload) return
        val dao = beweisVideoDao ?: return

        val offene = runCatching { dao.nichtHochgeladene() }.getOrDefault(emptyList())
        for (video in offene) {
            val datei = java.io.File(video.dateiPfad)
            if (!datei.exists()) continue

            val ziel = ordnerbaum.ordnerFuer(
                ordnerId,
                DriveAblage.tagesordner(video.gestartetAm, zone),
                DriveKategorie.VIDEOS,
            ).getOrElse { continue }

            // Waisen-Absicherung wie bei CSV, WAV und Fotos.
            val vorhanden = driveApi.dateiSuchen(datei.name, ziel).getOrNull()
            if (vorhanden != null) {
                runCatching { dao.setzeDriveFileId(video.id, vorhanden.id) }
                continue
            }

            // Der aktuelle Session-URI wird mitgefuehrt, nicht aus [video] gelesen: Der Eintrag
            // stammt aus der Abfrage von vor dem Upload, und der Fortschritts-Rueckruf wuerde
            // sonst den gerade gespeicherten URI mit dem alten (meist null) ueberschreiben -
            // womit der naechste Zyklus wieder bei null anfinge.
            var aktuellerSessionUri = video.uploadSessionUri

            driveApi.dateiHochladenResumable(
                name = datei.name,
                ordnerId = ziel,
                datei = datei,
                mimeType = "video/mp4",
                fortsetzenAb = video.uploadSessionUri,
                sessionGestartet = { uri ->
                    aktuellerSessionUri = uri
                    runCatching { dao.setzeUploadFortschritt(video.id, uri, 0) }
                },
                fortschritt = { bestaetigt, _ ->
                    runCatching { dao.setzeUploadFortschritt(video.id, aktuellerSessionUri, bestaetigt) }
                },
            ).onSuccess { fileId ->
                runCatching {
                    dao.setzeDriveFileId(video.id, fileId)
                    // Der Session-URI hat seinen Zweck erfuellt - stehen zu lassen wuerde einen
                    // spaeteren Lauf dazu verleiten, eine abgeschlossene Sitzung abzufragen.
                    dao.setzeUploadFortschritt(video.id, null, datei.length())
                }
            }
        }
    }

    /**
     * Spiegelt die komplette Datenbank nach `<Ordner>/BACKUP/` (siehe [DriveDatenbankSicherung])
     * - eigener Schalter [SettingsManager.datenbankSicherungDriveUpload], eigenes `runCatching`:
     * Ein Fehlschlag hier darf CSV/WAV/Foto/Video-Sync desselben Zyklus nie mitreissen, genau wie
     * bei [ladeFotosHoch]/[ladeVideosHoch].
     */
    private suspend fun ladeDatenbankSicherungHoch(ordnerId: String) {
        if (!settings.datenbankSicherungDriveUpload) return
        val quelle = datenbankSicherungQuelle ?: return

        val bytes = runCatching { quelle() }.getOrElse { fehler ->
            diagnosticsReporter?.breadcrumb("DriveSync", "Datenbank-Sicherung konnte nicht erstellt werden: ${fehler.message}")
            return
        }
        DriveDatenbankSicherung.hochladen(driveApi, ordnerId, bytes)
            .onSuccess {
                diagnosticsReporter?.breadcrumb("DriveSync", "Datenbank-Sicherung hochgeladen (${bytes.size} Bytes)")
            }
            .onFailure { fehler ->
                diagnosticsReporter?.breadcrumb("DriveSync", "Datenbank-Sicherung-Upload fehlgeschlagen: ${fehler.message}")
            }
    }

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
