package com.example.lrmprotokoll.report.gesamtbericht.model

import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity

/**
 * Konfiguration und Metadaten für die Gesamtbericht-Erstellung nach Vorbild von Baul-rm v10.
 */
data class GesamtberichtConfig(
    val titel: String = "SCHALLIMMISSIONSMESSUNG & BAULÄRMBEWERTUNG",
    val untertitel: String = "Gutachterliche Dokumentation nach TA Lärm & AVV Baulärm",
    val messort: String = "Messstandort",
    val auftraggeber: String = "Dokumentation für Behörden / Vermieter",
    val messgeraet: String = "PCE-323 (Klasse 2, IEC 61672-1)",
    val mikrofonPosition: String = "Balkon / Außenbereich",
    val mikrofonHoehe: String = "140 cm (freies Stativ)",
    val gebietsart: String = "Reines/Allgemeines Wohngebiet (WR/WA)",
    val richtwertTagWa: Double = 55.0,
    val eingreifwertTag: Double = 60.0,
    val richtwertNachtWa: Double = 40.0,
    val eingreifwertNacht: Double = 45.0,
    val messunsicherheitDb: Double = 1.4,
    val vonDatum: Long? = null,
    val bisDatum: Long? = null,
    val includeWavClassification: Boolean = true
)

/**
 * Auswertung eines einzelnen Messtages mit allen akustischen Kennwerten nach TA Lärm / AVV Baulärm.
 */
data class TagAuswertung(
    val dayKey: String, // z. B. "2026-08-25"
    val timestamp: Long,
    val sessions: List<SessionEntity>,
    val messwerteCount: Int,
    val gesamtdauerMs: Long,
    val abdeckungTagPct: Double, // 0.0 - 1.0 (bezogen auf 13h Tagzeitraum 07:00-20:00 = 46.800 s)
    val laeq: Double?,
    val lr: Double?, // Beurteilungspegel inkl. Zuschläge
    val lmax: Double?,
    val l1: Double?,
    val l10: Double?,
    val l50: Double?,
    val l90: Double?,
    val l95: Double?,
    val dauerUeber60Minuten: Double,
    val dauerUeber65Minuten: Double,
    val dauerUeber70Minuten: Double,
    val pegelklassenSekunden: Map<String, Long>, // "<50", "50-55", "55-60", "60-65", "65-70", ">70"
    val lautesteStundeStart: String?,
    val lautesteStundeLeq: Double?,
    val ereignisse: List<NoiseRecord>,
    val ruhezeitEreignisseCount: Int,
    val grenzwertUeberschreitungTag: Boolean,
    val indoor: Boolean = false
)

/**
 * Eintrag im revisionssicheren Rohdaten-Manifest.
 */
data class ManifestEintrag(
    val datum: String,
    val typ: String, // z. B. "Messreihe (DB)", "CSV-Rohdaten", "WAV-Audio"
    val dateiName: String,
    val bytes: Long,
    val sha256: String
)

/**
 * Gesamter aggregierter Datensatz für das PDF-Rendering.
 */
data class GesamtberichtData(
    val config: GesamtberichtConfig,
    val tage: List<TagAuswertung>,
    val gesamtMesstage: Int,
    val ueberschreitungsTageCount: Int,
    val gesamtDauerMs: Long,
    val gesamtLaeq: Double?,
    val gesamtLmax: Double?,
    val maxLautesteStundeDay: String?,
    val maxLautesteStundeLeq: Double?,
    val gesamtEreignisseCount: Int,
    val gesamtRuhezeitEreignisseCount: Int,
    val manifest: List<ManifestEintrag>,
    val generierungsZeitpunkt: Long = System.currentTimeMillis()
)
