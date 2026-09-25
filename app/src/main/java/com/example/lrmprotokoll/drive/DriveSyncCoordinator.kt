package com.example.lrmprotokoll.drive

import android.util.Log
import com.example.lrmprotokoll.data.DriveDailyFileDao
import com.example.lrmprotokoll.data.DriveDailyFileEntity
import com.example.lrmprotokoll.data.DriveSyncState
import com.example.lrmprotokoll.data.LevelSampleDao
import com.example.lrmprotokoll.data.NoiseDao
import com.example.lrmprotokoll.data.SettingsManager
import com.example.lrmprotokoll.meter.InstantSource
import kotlinx.coroutines.sync.Mutex
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "DriveSyncCoordinator"
private const val MIME_TYPE = "text/csv; charset=utf-8"

/**
 * Mindestabstand zwischen zwei Versuchen der Datenbank-Sicherung (siehe
 * [DriveSyncCoordinator.ladeDatenbankSicherungHoch]). Bewusst an das Intervall des
 * regulaeren periodischen Sync-Zyklus angelehnt ([DriveSyncPlanung.plane], 30 Minuten) -
 * kein Wert aus Plan/Prompt vorgegeben, eigene Abwaegung: haeufiger als der ohnehin
 * geplante Zyklus muss die vollstaendige Sicherung nie neu aufgebaut werden, seltener
 * wuerde die von der Sofort-Ausloesung erwartete Aktualitaet unnoetig verzoegern.
 */
private val DATENBANK_SICHERUNG_MIN_INTERVALL: Duration = Duration.ofMinutes(30)

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
     * Baut die Datenbanksicherung STREAMEND in eine selbstgewaehlte Datei und liefert diese
     * zurueck (Bugfix 23.09.2026, docs/PROMPT_FIX_DATENBANK_SICHERUNG.md - siehe
     * [DriveDatenbankSicherung]). `null` heisst "keine automatische Datenbank-Sicherung" - wie
     * [dokumentationsFotoDao]/[beweisVideoDao] optional, damit bestehende Test-Aufbauten nicht
     * alle ein weiteres Fake mitschleppen muessen. Als Funktion statt eines direkten
     * [android.content.Context]-Zugriffs, damit der Koordinator selbst ohne Android-Abhaengigkeit
     * bleibt und testbar - die Wahl DES Verzeichnisses (in der Praxis `cacheDir`, siehe
     * `AppContainer.kt`) liegt deshalb bei der Quelle, nicht beim Koordinator. Vorher lieferte
     * diese Funktion ein `ByteArray` - auf dem Owner-Geraet eine ~492-MB-Allokation bei einer
     * Heap-Grenze von 402 MB, Ursache von 95 gescheiterten `OutOfMemoryError`-Versuchen zwischen
     * dem 16. und 23.09.2026 (`docs/BEFUNDE_P30_2026-09-23.md`, Abschnitt 2/A2). Der Koordinator
     * loescht die zurueckgelieferte Datei in JEDEM Fall (Erfolg wie Fehlschlag) im `finally` von
     * [ladeDatenbankSicherungHoch] - sie gehoert nur diesem einen Versuch.
     */
    private val datenbankSicherungQuelle: (suspend () -> java.io.File)? = null,
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

    /**
     * Sorgt dafuer, dass niemals zwei Sync-Zyklen gleichzeitig laufen (OOM-Bugfix Schritt 1,
     * PROMPT_FIX_OOM_DRIVE_SYNC.md / Befund A1): Der periodische und der sofortige Worker
     * (verschiedene WorkManager-Namen, siehe [DriveSyncPlanung]) sowie die manuellen "Jetzt
     * synchronisieren"-Knoepfe (Diagnose-/Einstellungen-Screen) konnten bisher gleichzeitig
     * laufen und dabei mehrfach riesige Rohwertlisten aus `level_samples` laden - auf dem
     * Owner-Geraet bis zu vier Zyklen innerhalb einer Zehntelsekunde, kurz vor einem
     * OutOfMemoryError. Der Coordinator ist ein Singleton im
     * [com.example.lrmprotokoll.AppContainer] (`by lazy`), das Mutex-Feld gilt also fuer die
     * gesamte App-Laufzeit.
     */
    private val zyklusMutex = Mutex()

    suspend fun syncEinenZyklus(): SyncErgebnis {
        if (!zyklusMutex.tryLock()) {
            // Nur EIN Breadcrumb pro wartendem Lauf, VOR dem eigentlichen (potenziell langen)
            // Warten - tryLock() liefert das direkt, ohne selbst zu blockieren.
            diagnosticsReporter?.breadcrumb("DriveSync", "Drive-Sync wartet auf laufenden Zyklus")
            zyklusMutex.lock()
        }
        try {
            return fuehreSyncZyklusAus()
        } finally {
            zyklusMutex.unlock()
        }
    }

    private suspend fun fuehreSyncZyklusAus(): SyncErgebnis {
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
        // OOM-Bugfix Schritt 4 (PROMPT_FIX_OOM_DRIVE_SYNC.md / Befund A1): Rohwerte stueckweise
        // statt als eine bis zu ~290.000 Zeilen grosse Tagesliste laden.
        val zeilen = aggregiereInAbschnitten(von, jetzt, ereignisse, fensterDauer).zeilen

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
                // OOM-Bugfix Schritt 3 (PROMPT_FIX_OOM_DRIVE_SYNC.md / Befund A1): Registry ZUERST
                // pruefen, VOR jeglichem Rohwerte-Laden. Ein Tag, der NACH seinem eigenen
                // Tagesende erfolgreich synchronisiert wurde, ist endgueltig fertig - Rohwerte
                // eines vergangenen Tages kommen nicht nachtraeglich hinzu, die Messung laeuft
                // live. lastSyncedAt ist bei BEIDEN Schreibstellen (hier unten und in
                // [syncEinenZyklus]) der Zeitpunkt des SYNCS, nicht des Tages - "lastSyncedAt >=
                // tagBis" heisst also praezise "nach Tagesende erfolgreich synchronisiert".
                // Ohne diese Pruefung lud jeder Zyklus erneut die volle Tagesliste (bei ~290.000
                // Zeilen/Tag auf dem Owner-Geraet), nur um dasselbe wie beim letzten Mal
                // festzustellen.
                val registry = dailyFileDao.byDate(tagesSchluessel)
                if (registry != null && registry.state == DriveSyncState.SYNCED &&
                    registry.lastSyncedAt >= tagBis.toEpochMilli()
                ) {
                    return@runCatching // nach Tagesende synchronisiert -> endgueltig, nichts zu tun
                }

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
                // OOM-Bugfix Schritt 4: stueckweise statt als eine ~290.000-Zeilen-Liste laden.
                val abschnitte = aggregiereInAbschnitten(tagVon, tagBis, ereignisse, fensterDauer)
                if (!abschnitte.hatteRohwerte) return@runCatching // wie zuvor: keine Rohwerte -> nichts zu tun

                val zeilen = abschnitte.zeilen
                if (zeilen.isEmpty()) return@runCatching

                if (registry != null && registry.state == DriveSyncState.SYNCED && registry.lastRowCount == zeilen.size) {
                    return@runCatching // Zeilenzahl unveraendert seit dem letzten (nicht-endgueltigen) Sync
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

    /** Ergebnis von [aggregiereInAbschnitten]: die verdichteten Zeilen plus ob ueberhaupt
     * Rohwerte gefunden wurden (siehe dortiges KDoc, Absatz zu `hatteRohwerte`). */
    private data class AbschnittsAggregation(val zeilen: List<AggregatZeile>, val hatteRohwerte: Boolean)

    /**
     * Aggregiert `[von, bis)` stueckweise in Abschnitten von rund einer Stunde, statt die
     * kompletten Rohwerte des Zeitraums als eine Liste zu laden (OOM-Bugfix Schritt 4,
     * PROMPT_FIX_OOM_DRIVE_SYNC.md / Befund A1 - `level_samples` hat auf dem Owner-Geraet rund
     * 290.000 Zeilen/Tag). Die Abschnittslaenge ist ein ganzzahliges Vielfaches von
     * [fensterDauer], ab [von] gerechnet, damit keine Fenstergrenze mitten in einen Abschnitt
     * faellt.
     *
     * **Warum nicht einfach [PegelAggregator.aggregiere] separat je Abschnitt aufrufen und die
     * Ergebnislisten aneinanderhaengen?** Dessen eigentliche Fensterberechnung (`bildeZeile`) ist
     * rein lokal - das Ergebnis EINES Fensters haengt nur von den Samples/Ereignissen in genau
     * diesem Fenster ab, keine gleitenden Mittel, kein Uebertrag zwischen Fenstern IM WERT. Aber
     * `aggregiere()` trimmt Fuehrungs- und Schlusszeilen OHNE jegliche Daten auf den EIGENEN
     * Datenumfang des jeweiligen Aufrufs (Test `leereZeitenVorUndNachMessungWerdenNichtAls
     * LeereZeilenErzeugt`: eine Messung nur von Minute 10-12 erzeugt bei `bis=3600s` KEINE 60
     * Zeilen, nur die eine mit echten Daten). Luecken DAZWISCHEN, innerhalb des Datenumfangs,
     * werden dagegen als KEINE_VERBINDUNG-Zeilen gefuellt (Test
     * `fensterOhneSampleWirdAlsLueckeAusgegebenNichtAusgelassen`). Ruft man das isoliert pro
     * Abschnitt auf, gilt "eigener Datenumfang" ploetzlich pro ABSCHNITT statt pro ganzem Tag -
     * eine Bluetooth-Aussetzer-Luecke, die eine Abschnittsgrenze beruehrt oder einen ganzen
     * Abschnitt fuellt, wuerde dabei faelschlich verschluckt statt als KEINE_VERBINDUNG zu
     * erscheinen. Deshalb ueberbrueckt diese Funktion fehlende Fenster ZWISCHEN zwei Abschnitten
     * mit echten Zeilen explizit selbst, mit demselben "keine Daten"-Wert, den [AggregatZeile]s
     * eigene Default-Parameter ohnehin fuer ein leeres Fenster liefern wuerden (siehe dessen
     * Feld-Dokumentation) - keine Neuimplementierung der (privaten) `bildeZeile()`-Logik, nur
     * deren dokumentiertes Leerfenster-Ergebnis als Literal.
     *
     * **Zweite Abhaengigkeit von Werten ausserhalb des Fensters - urspruenglich hier nur
     * umgangen, seit der Nachbesserung vom 24.09.2026 an der Wurzel behoben:**
     * [PegelAggregator.aggregiere] wies bis dahin einen echten Bug auf, keinen bloss kosmetischen
     * Unterschied: Es waehlte die AUSGEGEBENEN `fensterStart`-Zeitstempel auf dem absoluten
     * Millisekunden-Raster (`floor(minTs/fensterMillis)*fensterMillis`, unabhaengig vom
     * `von`-Parameter), gruppierte Rohwerte in Buckets aber RELATIV zum eigenen `von`-Parameter
     * des jeweiligen Aufrufs. War `von` nicht exakt auf das Fensterdauer-Raster ausgerichtet,
     * fielen beide Bezugssysteme auseinander - je nach Datenlage landeten Rohwerte dadurch unter
     * einem FALSCHEN `fensterStart` oder verschwanden ganz aus der Ausgabe. Das betraf entgegen
     * der urspruenglichen Annahme NICHT nur mehrere Aufrufe mit unterschiedlichem `von` (wie beim
     * Aufteilen in Abschnitte), sondern bereits einen einzigen Aufruf ueber den ganzen Tag - "bei
     * genau einem Aufruf ist die Verschiebung konstant, also wohldefiniert" beschrieb nur, DASS
     * das Ergebnis deterministisch war, nicht dass es korrekt war (siehe
     * [com.example.lrmprotokoll.drive.PegelAggregatorTest], Test
     * `nichtRasterausgerichtetesVonBeiDatengetriebenemFensterbeginnGruppiertKorrekt`, fuer ein
     * durchgerechnetes Gegenbeispiel).
     *
     * [PegelAggregator.aggregiere] berechnet seine Fenstergrenzen (`effektiverStartMillis`/
     * `effektivesEndeMillis`) seitdem VON-RELATIV statt auf dem absoluten Epoch-Raster (siehe
     * dessen KDoc) - das ist der entscheidende Punkt fuer DIESE Funktion hier: Jeder
     * `abschnittVon` ist per Konstruktion ein ganzzahliges Vielfaches von [fensterDauer] vom
     * GEMEINSAMEN, urspruenglichen `von` dieses Gesamtaufrufs entfernt (`abschnittDauer` oben ist
     * selbst ein Vielfaches von [fensterDauer]) - von-relative Fenstergrenzen liegen deshalb fuer
     * JEDEN Abschnitt auf demselben Raster wie fuer jeden anderen, unabhaengig davon, ob die
     * eigenen Rohwerte dieses Abschnitts dicht direkt ab `abschnittVon` beginnen oder erst
     * spaeter. Zwei Zwischenfassungen dieser Nachbesserung reparierten stattdessen NUR die
     * Gruppierung der Rohwerte (zuerst auf dem absoluten Epoch-Raster, dann relativ zum
     * jeweiligen `effektiverStartMillis`) und behoben damit zwar das obige Gegenbeispiel je
     * EINZELNEM Aufruf - aber nicht das Zusammenspiel MEHRERER Aufrufe hier unten:
     * `effektiverStartMillis` wechselte je nach Datenlage weiterhin zwischen "absolut
     * rasteraligniert" und "auf `von` aligniert", wodurch verschiedene Abschnitte auf
     * UNTERSCHIEDLICHEN Rastern landen konnten. Aufgefallen erst durch
     * `DriveSyncCoordinatorTest.stueckweiseAggregationLiefertDasselbeErgebnisWieEinAufrufUeberDenGanzenTag`
     * mit 20.000 dichten Zufallswerten bei Fensterdauer 7s (laengeres CSV als der
     * Vergleichs-Gesamtaufruf) - siehe
     * [com.example.lrmprotokoll.drive.PegelAggregatorTest], Test
     * `nichtRasterausgerichtetesVonAlsBindenderFensterbeginnGruppiertKorrekt`, fuer ein
     * durchgerechnetes Gegenbeispiel im Kleinen.
     *
     * Der vormals hier dokumentierte Fallback auf einen Gesamtaufruf bei nicht rasteraligniertem
     * `von` ist damit ueberfluessig und entfernt: diese Funktion aggregiert jetzt fuer JEDE
     * Fensterdauer stueckweise, auch fuer eine, die 3600 nicht glatt teilt (z. B. 7s) oder in
     * einer Zeitzone mit Nicht-Stunden-Versatz (z. B. UTC+5:30) auf Mitternacht trifft - der volle
     * Speichervorteil aus Schritt 4 gilt seitdem ausnahmslos.
     *
     * `hatteRohwerte` bildet nach, was der bisherige Code implizit tat: War samples.isEmpty()
     * (VOR jeder Ereignis-Betrachtung), wurde der Tag in [holeVersaeumteTageNach] uebersprungen -
     * ein Tag ganz ohne Rohwerte, aber mit Ereignissen, wurde nie hochgeladen. Dieses bestehende
     * Verhalten wird hier bewusst 1:1 fortgefuehrt, nicht nebenbei mitkorrigiert.
     */
    private suspend fun aggregiereInAbschnitten(
        von: Instant,
        bis: Instant,
        ereignisse: List<ProtokollEreignis>,
        fensterDauer: Duration,
    ): AbschnittsAggregation {
        if (!bis.isAfter(von)) return AbschnittsAggregation(emptyList(), hatteRohwerte = false)

        val fensterSekunden = fensterDauer.seconds.coerceAtLeast(1)
        val vielfaches = Math.round(3600.0 / fensterSekunden).coerceAtLeast(1)
        val abschnittDauer = fensterDauer.multipliedBy(vielfaches)

        val zeilen = mutableListOf<AggregatZeile>()
        var hatteRohwerte = false
        var letztesFensterEnde: Instant? = null
        var abschnittVon = von

        while (abschnittVon.isBefore(bis)) {
            val abschnittBis = minOf(abschnittVon.plus(abschnittDauer), bis)
            val abschnittSamples = levelSampleDao.zwischen(abschnittVon.toEpochMilli(), abschnittBis.toEpochMilli())
            if (abschnittSamples.isNotEmpty()) hatteRohwerte = true
            val abschnittEreignisse = ereignisse.filter {
                !it.at.isBefore(abschnittVon) && it.at.isBefore(abschnittBis)
            }

            if (abschnittSamples.isNotEmpty() || abschnittEreignisse.isNotEmpty()) {
                val abschnittZeilen = PegelAggregator.aggregiere(
                    abschnittSamples, abschnittEreignisse, abschnittVon, abschnittBis, fensterDauer,
                )
                if (abschnittZeilen.isNotEmpty()) {
                    val ersteZeile = abschnittZeilen.first()
                    letztesFensterEnde?.let { ende ->
                        // Luecke zwischen dem letzten Abschnitt mit Daten und diesem hier -
                        // beide liegen innerhalb des Gesamt-Datenumfangs, muss also wie bei
                        // einem einzigen aggregiere()-Aufruf als KEINE_VERBINDUNG erscheinen.
                        var lueckenFenster = ende
                        while (lueckenFenster.isBefore(ersteZeile.fensterStart)) {
                            zeilen += AggregatZeile(fensterStart = lueckenFenster)
                            lueckenFenster = lueckenFenster.plus(fensterDauer)
                        }
                    }
                    zeilen += abschnittZeilen
                    letztesFensterEnde = abschnittZeilen.last().fensterStart.plus(fensterDauer)
                }
            }
            abschnittVon = abschnittBis
        }
        return AbschnittsAggregation(zeilen, hatteRohwerte)
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
     *
     * Gedrosselt auf [DATENBANK_SICHERUNG_MIN_INTERVALL] seit dem letzten VERSUCH (Bugfix,
     * Owner-Meldung 16.09.2026, "Upload schmiert nach 1-2h ab" - siehe
     * [SettingsManager.datenbankSicherungLastAttemptAt]-KDoc fuer die volle Herleitung): ohne
     * das baute [datenbankSicherungQuelle] die komplette Datenbank bei jedem einzelnen, per
     * [DriveSyncWorker.starteSofort] durch ein Laermereignis ausgeloesten Zyklus neu auf und lud
     * sie hoch - bei haeufigen Ereignissen unnoetig wiederholter Festplatten-I/O und
     * Upload-Traffic fuer eine mehrere hundert MB grosse Datei. Die Pruefung steht VOR dem teuren
     * [quelle]-Aufruf, nicht erst vor dem Upload.
     *
     * Fehlschlaege (Bauen wie Hochladen) werden seit dem Streaming-Umbau (Bugfix 23.09.2026,
     * docs/PROMPT_FIX_DATENBANK_SICHERUNG.md Schritt 4) als
     * [com.example.lrmprotokoll.diagnose.DiagnosticCode.BACKUP_CREATE_FAILED] gemeldet, nicht
     * mehr nur als INFO-Breadcrumb - genau dieser stille Breadcrumb-Pfad war der Grund, warum die
     * 95 gescheiterten Sicherungsversuche vom 16.-23.09.2026 niemandem aufgefallen sind.
     */
    private suspend fun ladeDatenbankSicherungHoch(ordnerId: String) {
        if (!settings.datenbankSicherungDriveUpload) return
        val quelle = datenbankSicherungQuelle ?: return

        val letzterVersuch = Instant.ofEpochMilli(settings.datenbankSicherungLastAttemptAt)
        if (Duration.between(letzterVersuch, now.now()) < DATENBANK_SICHERUNG_MIN_INTERVALL) {
            return
        }
        settings.datenbankSicherungLastAttemptAt = now.now().toEpochMilli()

        val tempDatei = runCatching { quelle() }.getOrElse { fehler ->
            diagnosticsReporter?.report(
                code = com.example.lrmprotokoll.diagnose.DiagnosticCode.BACKUP_CREATE_FAILED,
                component = "DriveSyncCoordinator",
                operation = "ladeDatenbankSicherungHoch.bauen",
                severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                cause = fehler,
                message = fehler.message,
            )
            return
        }
        try {
            DriveDatenbankSicherung.hochladen(driveApi, ordnerId, tempDatei)
                .onSuccess {
                    settings.datenbankSicherungLastSuccessAt = now.now().toEpochMilli()
                    diagnosticsReporter?.breadcrumb("DriveSync", "Datenbank-Sicherung hochgeladen (${tempDatei.length()} Bytes)")
                }
                .onFailure { fehler ->
                    diagnosticsReporter?.report(
                        code = com.example.lrmprotokoll.diagnose.DiagnosticCode.BACKUP_CREATE_FAILED,
                        component = "DriveSyncCoordinator",
                        operation = "ladeDatenbankSicherungHoch.hochladen",
                        severity = com.example.lrmprotokoll.diagnose.DiagnosticSeverity.WARN,
                        cause = fehler,
                        message = fehler.message,
                    )
                }
        } finally {
            tempDatei.delete()
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
