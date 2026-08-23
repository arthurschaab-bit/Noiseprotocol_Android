package com.example.lrmprotokoll.drive

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCsvTest {

    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun kopfzeileNenntPegelDbUndLaeqDbSowieWeitereAussagekraeftigeSpalten() {
        val csv = DriveCsv.schreibe(emptyList(), zone)
        assertTrue(
            "Kopfzeile muss Rohwerte (Pegel_dB) und verarbeitete Kennwerte (LAeq_dB) enthalten",
            csv.contains("Zeit;Pegel_dB;LAeq_dB;LAFmax_dB;LAFmin_dB;Bewertung;Zeitbewertung;Messbereich;Samples;Quelle;Ereignis;Klassifikation;Notizen"),
        )
    }

    @Test
    fun beginntMitUtf8BomDamitExcelUmlauteKorrektZeigt() {
        val csv = DriveCsv.schreibe(emptyList(), zone)
        assertEquals('\uFEFF', csv.first())
    }

    @Test
    fun normaleZeileEntsprichtDemErweitertenFormat() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:00Z"), // 08:00 MESZ
            pegelDb = 52.3, laeqDb = 52.3, lafMaxDb = 61.8, lafMinDb = 48.1,
            bewertung = "A", zeitbewertung = "FAST", messbereich = "AUTO",
            samples = 20, quelle = "PCE_323", ereignis = false, klassifikation = null, notes = null,
        )
        val csv = DriveCsv.schreibe(listOf(zeile), zone)

        assertTrue(csv.contains("2026-08-16T08:00:00+02:00;52,3;52,3;61,8;48,1;A;FAST;AUTO;20;PCE_323;;;"))
    }

    @Test
    fun ereigniszeileZeigtJaUndKlassifikationSowieNotiz() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:10Z"),
            pegelDb = 75.0, laeqDb = 71.4, lafMaxDb = 89.2, lafMinDb = 53.0,
            bewertung = "A", zeitbewertung = "FAST", messbereich = "AUTO",
            samples = 20, quelle = "PCE_323", ereignis = true, klassifikation = "Hämmern", notes = "Nachbar bohrt",
        )
        val csv = DriveCsv.schreibe(listOf(zeile), zone)

        assertTrue(csv.contains("75,0;71,4;89,2;53,0;A;FAST;AUTO;20;PCE_323;JA;Hämmern;Nachbar bohrt"))
    }

    @Test
    fun lueckenzeileZeigtLeereWerteAberEchteNullenNichtSondernLeerstring() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:20Z"),
            pegelDb = null, laeqDb = null, lafMaxDb = null, lafMinDb = null,
            bewertung = null, zeitbewertung = null, messbereich = null,
            samples = 0, quelle = QUELLE_KEINE_VERBINDUNG, ereignis = false, klassifikation = null, notes = null,
        )
        val csv = DriveCsv.schreibe(listOf(zeile), zone)

        assertTrue(csv.contains("2026-08-16T08:00:20+02:00;;;;;;;;0;KEINE_VERBINDUNG;;;"))
    }

    @Test
    fun dezimaltrennzeichenIstKommaNichtPunkt() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:00Z"),
            pegelDb = 52.3, laeqDb = 52.3, lafMaxDb = 61.8, lafMinDb = 48.1,
            samples = 1, quelle = "PCE_323", ereignis = false, klassifikation = null,
        )
        val csv = DriveCsv.schreibe(listOf(zeile), zone)

        assertTrue(!csv.contains("52.3"))
        assertTrue(csv.contains("52,3"))
    }

    @Test
    fun zeilenEndenAufCrlf() {
        val csv = DriveCsv.schreibe(emptyList(), zone)
        assertTrue(csv.endsWith("\r\n"))
        assertTrue(!csv.contains("\n") || csv.replace("\r\n", "").let { !it.contains("\n") })
    }
}
