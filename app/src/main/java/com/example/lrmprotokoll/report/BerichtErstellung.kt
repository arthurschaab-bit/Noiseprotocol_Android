package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ein fachlicher Messtag verwendet dieselbe lokale Tagesgrenze wie
 * [gruppiereSessionsNachTag]. Der DateRangePicker liefert dagegen UTC-Mitternacht fuer die
 * angezeigten Kalenderdaten; [ausPicker] uebersetzt genau diese Daten in lokale Grenzen.
 */
data class BerichtZeitraum(val ersterTag: LocalDate, val letzterTag: LocalDate) {
    init { require(!letzterTag.isBefore(ersterTag)) { "Der letzte Berichtstag liegt vor dem ersten." } }

    fun tage(): List<LocalDate> = generateSequence(ersterTag) { tag ->
        tag.plusDays(1).takeIf { !it.isAfter(letzterTag) }
    }.toList()

    companion object {
        fun ausPicker(startUtcMillis: Long, endeUtcMillis: Long): BerichtZeitraum = BerichtZeitraum(
            Instant.ofEpochMilli(startUtcMillis).atZone(ZoneOffset.UTC).toLocalDate(),
            Instant.ofEpochMilli(endeUtcMillis).atZone(ZoneOffset.UTC).toLocalDate(),
        )
    }
}

/** Zaehler und Stammdaten-Kandidaten vor dem Python-Aufruf, ohne Rohwert-Massendaten. */
data class BerichtTag(
    val datum: LocalDate,
    val von: Long,
    val bis: Long,
    val sessionIds: List<Long>,
    val rohwerte: Int,
    val verdichteteMinuten: Int,
    val unbestaetigteWerte: Int,
    val stammdatenKandidaten: List<StammdatenVerlaufEntity>,
) {
    val label: String get() = datum.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN))
}

/**
 * Die Vorpruefung lehnt bereits verdichtete Messtage ab, statt unbemerkt andere Kennwerte
 * aus Minutenaggregaten zu errechnen (Owner-Entscheidung 13.09.2026). Keine Stammdaten-Zeile
 * bleibt dagegen als sichtbare Luecke zulässig (Owner-Entscheidung 14.09.2026).
 */
fun retentionFehler(tage: List<BerichtTag>): String? {
    val verdichtet = tage.filter { it.verdichteteMinuten > 0 }.map { it.label }
    return verdichtet.takeIf { it.isNotEmpty() }?.joinToString(
        prefix = "Bericht nicht möglich: Rohdaten wurden bereits verdichtet am ",
        postfix = ".",
    )
}

fun bewertungsFehler(tage: List<BerichtTag>, config: ReportConfigEntity): String? =
    if (tage.any { it.unbestaetigteWerte > 0 } && !config.erzwingeBerichtOhneBestaetigteBewertung) {
        "Bericht nicht möglich: A-/Zeitbewertung ist nicht bestätigt. In den Berichtsparametern kann ein bewusster Override aktiviert werden."
    } else null

/** Bei mehreren Eintraegen muss der Nutzer eine ID explizit wählen; ein einzelner wird
 * automatisch übernommen, eine fehlende Zeile bleibt `null` und wird im PDF bezeichnet. */
fun gewaehlteStammdaten(tag: BerichtTag, gewaehlteIds: Map<LocalDate, Long>): StammdatenVerlaufEntity? {
    val kandidaten = tag.stammdatenKandidaten
    if (kandidaten.size == 1) return kandidaten.single()
    val id = gewaehlteIds[tag.datum] ?: return null
    return kandidaten.firstOrNull { it.id == id }
}

fun auswahlFehler(tage: List<BerichtTag>, gewaehlteIds: Map<LocalDate, Long>): String? {
    val offen = tage.filter { it.stammdatenKandidaten.size > 1 && gewaehlteStammdaten(it, gewaehlteIds) == null }
    return offen.takeIf { it.isNotEmpty() }?.joinToString(
        prefix = "Bitte Stammdaten für diese Tage auswählen: ",
        postfix = ".",
    ) { it.label }
}

/** Erfasst vorhandene, aber noch unvollständige Angaben feldgenau statt sie als voll
 * dokumentierten Messaufbau auszugeben. Wetter und Datenqualitäts-Hinweis sind optional. */
fun fehlendeStammdatenFelder(stammdaten: StammdatenVerlaufEntity?): List<String> {
    if (stammdaten == null) return listOf("Stammdaten insgesamt")
    return buildList {
        if (stammdaten.geraetHersteller.isBlank()) add("Gerätehersteller")
        if (stammdaten.geraetTyp.isBlank()) add("Gerätetyp")
        if (stammdaten.geraetGenauigkeitsklasse.isBlank()) add("Genauigkeitsklasse")
        if (stammdaten.geraetSeriennummer.isBlank()) add("Seriennummer")
        if (stammdaten.geraetKalibrierung.isBlank()) add("Kalibrierung")
        if (stammdaten.messort.isBlank()) add("Messort")
        if (stammdaten.mikrofonposition.isBlank()) add("Mikrofonposition")
        if (stammdaten.mikrofonhoehe.isBlank()) add("Mikrofonhöhe")
        if (stammdaten.entfernungZurQuelle.isBlank()) add("Entfernung Mikrofon–Quelle")
        if (stammdaten.innenAussen.isBlank()) add("Innen-/Außenmessung")
        if (stammdaten.innenAussen.contains("innen", ignoreCase = true) && stammdaten.fensterzustand.isBlank()) {
            add("Fensterzustand")
        }
    }
}

/** Vorläufiger JSON-Vertrag für Teil B; Schritt 4 ergänzt private Rohdaten-Dateipfade. */
fun vorlaeufigeBerichtsparameter(
    tage: List<BerichtTag>,
    config: ReportConfigEntity,
    gewaehlteIds: Map<LocalDate, Long>,
    ausgabePfad: String,
): String {
    require(tage.isNotEmpty()) { "Es wurde kein Berichtszeitraum gewählt." }
    val areaError = areaSelectionError(config.gebietseinstufung)
    require(areaError == null) { areaError.orEmpty() }
    val jsonTage = JSONArray()
    for (tag in tage) {
        val stammdaten = gewaehlteStammdaten(tag, gewaehlteIds)
        val tagJson = JSONObject()
            .put("date", tag.datum.toString())
            .put("fromMillis", tag.von)
            .put("toMillis", tag.bis)
            .put("sessionIds", JSONArray(tag.sessionIds))
            .put("rawSampleCount", tag.rohwerte)
            .put("missingStammdatenFields", JSONArray(fehlendeStammdatenFelder(stammdaten)))
            .put("stammdaten", stammdaten?.alsJson() ?: JSONObject.NULL)
        jsonTage.put(tagJson)
    }
    val unbestaetigt = tage.any { it.unbestaetigteWerte > 0 }
    return JSONObject()
        .put("contractVersion", 1)
        .put("periodFromMillis", tage.first().von)
        .put("periodToMillis", tage.last().bis)
        .put("days", jsonTage)
        .put("reportConfig", config.alsJson())
        .put("unconfirmedWeightingOverride", unbestaetigt && config.erzwingeBerichtOhneBestaetigteBewertung)
        .put("outputPath", ausgabePfad)
        .toString()
}

private fun ReportConfigEntity.alsJson(): JSONObject = JSONObject()
    .put("schaetzpegelTeilerfassungDb", schaetzpegelTeilerfassungDb)
    .put("schaetzpegelMessfensterAbbruchDb", schaetzpegelMessfensterAbbruchDb)
    .put("tierSchwelleVollmessungProzent", tierSchwelleVollmessungProzent)
    .put("tierSchwelleTeilerfassungProzent", tierSchwelleTeilerfassungProzent)
    .put("gebietseinstufung", ReportArea.fromCode(gebietseinstufung)?.name ?: gebietseinstufung)
    .put("geraeteUnsicherheitDb", geraeteUnsicherheitDb)
    .put("konservativFensterStartStunde", konservativFensterStartStunde)
    .put("konservativFensterEndeStunde", konservativFensterEndeStunde)
    .put("erzwingeBerichtOhneBestaetigteBewertung", erzwingeBerichtOhneBestaetigteBewertung)

private fun StammdatenVerlaufEntity.alsJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("erstelltAm", erstelltAm)
    .put("giltFuerTagStart", giltFuerTagStart ?: JSONObject.NULL)
    .put("geraetHersteller", geraetHersteller)
    .put("geraetTyp", geraetTyp)
    .put("geraetGenauigkeitsklasse", geraetGenauigkeitsklasse)
    .put("geraetSeriennummer", geraetSeriennummer)
    .put("geraetKalibrierung", geraetKalibrierung)
    .put("messort", messort)
    .put("mikrofonposition", mikrofonposition)
    .put("mikrofonhoehe", mikrofonhoehe)
    .put("entfernungZurQuelle", entfernungZurQuelle)
    .put("innenAussen", innenAussen)
    .put("fensterzustand", fensterzustand)
    .put("wetter", wetter)
    .put("datenqualitaetHinweis", datenqualitaetHinweis)

/**
 * Lokale Kalendertage statt UTC-24h-Scheiben; das respektiert auch Sommerzeitwechsel. Die
 * 5 Abfragen je Tag sind voneinander unabhaengig, ebenso die Tage selbst - beides laeuft
 * nebenlaeufig statt seriell (Review-Befund PR #144).
 */
suspend fun ladeBerichtstage(db: AppDatabase, zeitraum: BerichtZeitraum, zone: ZoneId = ZoneId.systemDefault()): List<BerichtTag> {
    val rohTage = coroutineScope {
        zeitraum.tage().map { datum ->
            async {
                val von = datum.atStartOfDay(zone).toInstant().toEpochMilli()
                val bis = datum.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val sessions = async { db.sessionDao().zwischen(von, bis) }
                val rohwerte = async { db.measurementDao().anzahlZwischen(von, bis) }
                val verdichtet = async { db.minuteAggregateDao().anzahlZwischen(von, bis) }
                val unbestaetigt = async { db.measurementDao().anzahlUnbestaetigtZwischen(von, bis) }
                val stammdaten = async { db.stammdatenVerlaufDao().fuerTag(von, bis) }
                BerichtTag(
                    datum = datum,
                    von = von,
                    bis = bis,
                    sessionIds = sessions.await().map { it.id },
                    rohwerte = rohwerte.await(),
                    verdichteteMinuten = verdichtet.await(),
                    unbestaetigteWerte = unbestaetigt.await(),
                    stammdatenKandidaten = stammdaten.await(),
                )
            }
        }.awaitAll()
    }
    // Review-Befund PR #144: eine ueber Mitternacht laufende Session kann ihre Stammdaten-Zeile
    // mit einem erstelltAm haben, das noch auf den Vortag faellt (giltFuerTagStart bleibt dann
    // null) - fuerTag() findet sie fuer den Folgetag nicht, obwohl derselbe, bereits dokumentierte
    // Messaufbau beide Kalendertage betrifft. Ein Tag ohne eigene Kandidaten uebernimmt deshalb
    // die eines Nachbartags, wenn beide dieselbe Session teilen. Die Rohdaten-Fensterung je
    // Kalendertag (rohwerte/sessionIds, Abschnitt 4 in DATENMAPPING_BERICHT_SCHRITT4.md) bleibt
    // davon bewusst unberuehrt - nur die Stammdaten-Zuordnung wird ergaenzt.
    return rohTage.mapIndexed { index, tag ->
        if (tag.stammdatenKandidaten.isNotEmpty()) return@mapIndexed tag
        val nachbarMitStammdaten = listOfNotNull(rohTage.getOrNull(index - 1), rohTage.getOrNull(index + 1))
            .firstOrNull { nachbar -> nachbar.stammdatenKandidaten.isNotEmpty() && nachbar.sessionIds.any { it in tag.sessionIds } }
        if (nachbarMitStammdaten != null) tag.copy(stammdatenKandidaten = nachbarMitStammdaten.stammdatenKandidaten) else tag
    }
}
