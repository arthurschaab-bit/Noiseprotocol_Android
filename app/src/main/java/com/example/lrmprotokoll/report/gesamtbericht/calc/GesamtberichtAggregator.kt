package com.example.lrmprotokoll.report.gesamtbericht.calc

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.report.gesamtbericht.model.GesamtberichtConfig
import com.example.lrmprotokoll.report.gesamtbericht.model.GesamtberichtData
import com.example.lrmprotokoll.report.gesamtbericht.model.ManifestEintrag
import com.example.lrmprotokoll.report.gesamtbericht.model.TagAuswertung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Aggregiert Messdaten, berechnet Kennwerte pro Tag und erstellt das Rohdaten-Manifest.
 */
class GesamtberichtAggregator(
    private val db: AppDatabase
) {

    suspend fun aggregiere(config: GesamtberichtConfig): GesamtberichtData = withContext(Dispatchers.IO) {
        val allSessions = db.sessionDao().alle().first()
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMAN)

        // Filter sessions by date range if provided
        val filteredSessions = allSessions.filter { s ->
            val inStart = config.vonDatum == null || s.startedAt >= config.vonDatum
            val inEnd = config.bisDatum == null || s.startedAt <= config.bisDatum
            inStart && inEnd
        }

        val sessionsByDay = filteredSessions.groupBy { s ->
            dayFormat.format(Date(s.startedAt))
        }.toSortedMap()

        val tagAuswertungen = mutableListOf<TagAuswertung>()
        val manifestEintraege = mutableListOf<ManifestEintrag>()

        var summeDauerMs = 0L
        val alleTagesLaeq = mutableListOf<Double>()
        var globalLmax: Double? = null
        var maxLautesteStundeDay: String? = null
        var maxLautesteStundeLeq: Double? = null
        var ueberschreitungstageCount = 0
        var totalEreignisseCount = 0
        var totalRuhezeitEreignisseCount = 0

        for ((dayKey, daySessions) in sessionsByDay) {
            val firstTs = daySessions.minOf { it.startedAt }
            var dayDurationMs = 0L
            val dayDbValues = mutableListOf<Double>()
            val dayTimeseries = mutableListOf<Pair<Long, Double>>()
            val dayRecords = mutableListOf<NoiseRecord>()

            for (session in daySessions) {
                val endTs = session.endedAt ?: (session.startedAt + 1000L)
                dayDurationMs += (endTs - session.startedAt).coerceAtLeast(0)

                // 1. Minute Aggregates
                val aggregates = db.minuteAggregateDao().fuerSession(session.id)
                // 2. Rohmesswerte (falls vorhanden)
                val rawMeasurements = db.measurementDao().fuerSession(session.id)

                if (rawMeasurements.isNotEmpty()) {
                    for (m in rawMeasurements) {
                        dayDbValues.add(m.levelDb)
                        dayTimeseries.add(Pair(m.timestamp, m.levelDb))
                    }
                } else if (aggregates.isNotEmpty()) {
                    for (agg in aggregates) {
                        dayDbValues.add(agg.leqDb)
                        dayTimeseries.add(Pair(agg.minuteStart, agg.leqDb))
                    }
                }

                // 3. Noise Records
                val records = db.noiseDao().zwischenZeitpunkt(session.startedAt, endTs)
                dayRecords.addAll(records)

                // 4. Manifest-Eintrag für Session
                val sessionDataString = "Session-${session.id}_${session.deviceName}_${session.startedAt}_${session.endedAt}_samples=${rawMeasurements.size + aggregates.size}"
                val hash = AkustikRechner.berechneSha256(sessionDataString)
                manifestEintraege.add(
                    ManifestEintrag(
                        datum = dayKey,
                        typ = "Room-Messreihe (${session.deviceName})",
                        dateiName = "session_${session.id}_${dayKey}.dat",
                        bytes = (rawMeasurements.size * 16L + aggregates.size * 40L).coerceAtLeast(128L),
                        sha256 = hash
                    )
                )

                // Manifest für WAVs
                if (config.includeWavClassification) {
                    for (rec in records) {
                        if (rec.filePath.isNotBlank()) {
                            val audioFile = File(rec.filePath)
                            if (audioFile.exists()) {
                                manifestEintraege.add(
                                    ManifestEintrag(
                                        datum = dayKey,
                                        typ = "Audio-Beleg (WAV)",
                                        dateiName = audioFile.name,
                                        bytes = audioFile.length(),
                                        sha256 = AkustikRechner.berechneSha256(audioFile.readBytes())
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Berechnungen für den Tag
            val dayLaeq = AkustikRechner.berechneLaeq(dayDbValues)
            val dayPerzentile = AkustikRechner.berechnePerzentile(dayDbValues)
            val dayLmax = dayDbValues.maxOrNull() ?: dayRecords.maxOfOrNull { it.calibratedDbA ?: it.dbValue }
            val pegelklassen = AkustikRechner.berechnePegelklassen(dayDbValues)

            val lautesteStunde = AkustikRechner.berechneLautesteStunde(dayTimeseries)
            val lautesteStundeStart = lautesteStunde?.let { timeFormat.format(Date(it.first)) }
            val lautesteStundeLeq = lautesteStunde?.second

            if (lautesteStundeLeq != null && (maxLautesteStundeLeq == null || lautesteStundeLeq > maxLautesteStundeLeq!!)) {
                maxLautesteStundeLeq = lautesteStundeLeq
                maxLautesteStundeDay = "$dayKey ($lautesteStundeStart Uhr)"
            }

            // Dauer über 60, 65, 70 dB in Minuten
            val dauerUeber60Min = (pegelklassen["60-65"] ?: 0L) + (pegelklassen["65-70"] ?: 0L) + (pegelklassen[">70"] ?: 0L)
            val dauerUeber65Min = (pegelklassen["65-70"] ?: 0L) + (pegelklassen[">70"] ?: 0L)
            val dauerUeber70Min = pegelklassen[">70"] ?: 0L

            // Abdeckung Tagzeitraum 07:00–20:00 Uhr (13h = 46.800 s)
            val abdeckung = (dayDurationMs / 1000.0 / 46800.0).coerceIn(0.0, 1.0)
            val lr = dayLaeq // Bei Baulärm vereinfacht Lr = LAeq (ohne zusätzliche Impulszuschläge)
            val grenzwertUeberschreitung = (dayLaeq ?: 0.0) > config.richtwertTagWa

            if (grenzwertUeberschreitung) {
                ueberschreitungstageCount++
            }

            val ruhezeitCount = dayRecords.count { it.isQuietHour }
            totalEreignisseCount += dayRecords.size
            totalRuhezeitEreignisseCount += ruhezeitCount
            summeDauerMs += dayDurationMs

            if (dayLaeq != null) {
                alleTagesLaeq.add(dayLaeq)
            }
            if (dayLmax != null && (globalLmax == null || dayLmax > globalLmax!!)) {
                globalLmax = dayLmax
            }

            tagAuswertungen.add(
                TagAuswertung(
                    dayKey = dayKey,
                    timestamp = firstTs,
                    sessions = daySessions,
                    messwerteCount = dayDbValues.size,
                    gesamtdauerMs = dayDurationMs,
                    abdeckungTagPct = abdeckung,
                    laeq = dayLaeq,
                    lr = lr,
                    lmax = dayLmax,
                    l1 = dayPerzentile[1],
                    l10 = dayPerzentile[10],
                    l50 = dayPerzentile[50],
                    l90 = dayPerzentile[90],
                    l95 = dayPerzentile[95],
                    dauerUeber60Minuten = dauerUeber60Min / 60.0,
                    dauerUeber65Minuten = dauerUeber65Min / 60.0,
                    dauerUeber70Minuten = dauerUeber70Min / 60.0,
                    pegelklassenSekunden = pegelklassen,
                    lautesteStundeStart = lautesteStundeStart,
                    lautesteStundeLeq = lautesteStundeLeq,
                    ereignisse = dayRecords.sortedBy { it.timestamp },
                    ruhezeitEreignisseCount = ruhezeitCount,
                    grenzwertUeberschreitungTag = grenzwertUeberschreitung
                )
            )
        }

        val globalLaeq = AkustikRechner.berechneLaeq(alleTagesLaeq)

        GesamtberichtData(
            config = config,
            tage = tagAuswertungen,
            gesamtMesstage = tagAuswertungen.size,
            ueberschreitungsTageCount = ueberschreitungstageCount,
            gesamtDauerMs = summeDauerMs,
            gesamtLaeq = globalLaeq,
            gesamtLmax = globalLmax,
            maxLautesteStundeDay = maxLautesteStundeDay,
            maxLautesteStundeLeq = maxLautesteStundeLeq,
            gesamtEreignisseCount = totalEreignisseCount,
            gesamtRuhezeitEreignisseCount = totalRuhezeitEreignisseCount,
            manifest = manifestEintraege
        )
    }
}
