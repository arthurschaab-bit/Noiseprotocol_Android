package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.StammdatenVerlaufDao
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.messreihe.berechneDatenverfuegbarkeitProzent
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Frei ausfuellbare Geraete-/Messaufbau-/Randbedingungsangaben - seit Owner-Anfrage 10.09.2026 am
 * Messbeginn erfasst (siehe `GesamtberichtStammdatenSheet`) statt fest in den Einstellungen
 * hinterlegt: [ausVerlauf] liest dafuer den zuletzt gespeicherten Eintrag aus
 * [StammdatenVerlaufDao]. Das bleibt weiterhin EIN Snapshot zum Berichtszeitpunkt (die neuesten
 * Stammdaten zum Zeitpunkt des Exports), keine zeitraumgenaue Zuordnung je Session/Tag - siehe
 * [ermittleGesamtbericht]s KDoc zur selben Einschraenkung bei den Messwerten.
 *
 * Ein leeres Feld bleibt ein leerer String, wird NICHT hier durch "nicht angegeben" ersetzt -
 * diese Entscheidung trifft [GesamtberichtExport] beim Zeichnen, damit [GesamtberichtStammdaten]
 * ein reiner Daten-Snapshot ohne Darstellungslogik bleibt (dieselbe Trennung wie ueberall sonst in
 * diesem Berichts-Paket).
 */
data class GesamtberichtStammdaten(
    val geraetHersteller: String,
    val geraetTyp: String,
    val geraetGenauigkeitsklasse: String,
    val geraetSeriennummer: String,
    val geraetKalibrierung: String,
    val messort: String,
    val mikrofonposition: String,
    val mikrofonhoehe: String,
    val entfernungZurQuelle: String,
    val innenAussen: String,
    val fensterzustand: String,
    val wetter: String,
    val datenqualitaetHinweis: String,
) {
    companion object {
        /** Alle Felder leer, solange noch nie ein Stammdaten-Dialog bestaetigt wurde. */
        fun leer() = GesamtberichtStammdaten("", "", "", "", "", "", "", "", "", "", "", "", "")

        fun ausEntity(eintrag: StammdatenVerlaufEntity) = GesamtberichtStammdaten(
            geraetHersteller = eintrag.geraetHersteller,
            geraetTyp = eintrag.geraetTyp,
            geraetGenauigkeitsklasse = eintrag.geraetGenauigkeitsklasse,
            geraetSeriennummer = eintrag.geraetSeriennummer,
            geraetKalibrierung = eintrag.geraetKalibrierung,
            messort = eintrag.messort,
            mikrofonposition = eintrag.mikrofonposition,
            mikrofonhoehe = eintrag.mikrofonhoehe,
            entfernungZurQuelle = eintrag.entfernungZurQuelle,
            innenAussen = eintrag.innenAussen,
            fensterzustand = eintrag.fensterzustand,
            wetter = eintrag.wetter,
            datenqualitaetHinweis = eintrag.datenqualitaetHinweis,
        )

        suspend fun ausVerlauf(dao: StammdatenVerlaufDao): GesamtberichtStammdaten =
            dao.letzte(1).firstOrNull()?.let { ausEntity(it) } ?: leer()
    }

    fun zuEntity(erstelltAm: Long) = StammdatenVerlaufEntity(
        erstelltAm = erstelltAm,
        geraetHersteller = geraetHersteller,
        geraetTyp = geraetTyp,
        geraetGenauigkeitsklasse = geraetGenauigkeitsklasse,
        geraetSeriennummer = geraetSeriennummer,
        geraetKalibrierung = geraetKalibrierung,
        messort = messort,
        mikrofonposition = mikrofonposition,
        mikrofonhoehe = mikrofonhoehe,
        entfernungZurQuelle = entfernungZurQuelle,
        innenAussen = innenAussen,
        fensterzustand = fensterzustand,
        wetter = wetter,
        datenqualitaetHinweis = datenqualitaetHinweis,
    )
}

/** Ein [PeriodenBericht] fuer genau einen Kalendertag, plus die daraus abgeleitete Datenverfuegbarkeit. */
data class GesamtberichtTag(
    val von: Long,
    val bis: Long,
    val bericht: PeriodenBericht,
    val datenverfuegbarkeitProzent: Double,
)

data class Gesamtbericht(
    val gesamt: PeriodenBericht,
    val tage: List<GesamtberichtTag>,
)

/**
 * Wie [ermittlePeriodenBericht], aber zusaetzlich nach Kalendertagen ([zone]) aufgeschluesselt -
 * fuer die Tagesseiten eines Gesamtberichts (Referenzvorlage: eine Seite pro Messtag mit Pegel,
 * Kennwerten und Datenverfuegbarkeit).
 *
 * Bewusst KEINE eigene Aggregation: jeder Tag ist ein ganz normaler Aufruf von
 * [ermittlePeriodenBericht] mit tagesgenauem Fenster - dieselbe, bereits getestete Berechnung wie
 * beim Wochen-/Monatsbericht, nur mehrfach mit engerem Zeitfenster. Das ist genau die Vermeidung
 * eines zweiten Kennwerte-Rechners, die [com.example.lrmprotokoll.report.pdf.BerichtLayout]s KDoc
 * als Grund fuer den Umbau von PR #78 nennt.
 */
suspend fun ermittleGesamtbericht(
    db: AppDatabase,
    von: Long,
    bis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Gesamtbericht {
    val gesamt = ermittlePeriodenBericht(db, von, bis)

    val tage = mutableListOf<GesamtberichtTag>()
    var tagesBeginn = ZonedDateTime.ofInstant(Instant.ofEpochMilli(von), zone)
        .toLocalDate().atStartOfDay(zone)
    while (tagesBeginn.toInstant().toEpochMilli() < bis) {
        val tagVon = maxOf(von, tagesBeginn.toInstant().toEpochMilli())
        val tagBis = minOf(bis, tagesBeginn.plusDays(1).toInstant().toEpochMilli())
        if (tagBis > tagVon) {
            val tagesBericht = ermittlePeriodenBericht(db, tagVon, tagBis)
            tage += GesamtberichtTag(
                von = tagVon,
                bis = tagBis,
                bericht = tagesBericht,
                datenverfuegbarkeitProzent = berechneDatenverfuegbarkeitProzent(
                    tagVon, tagBis, tagesBericht.ausfallbaender,
                ),
            )
        }
        tagesBeginn = tagesBeginn.plusDays(1)
    }

    return Gesamtbericht(gesamt = gesamt, tage = tage)
}
