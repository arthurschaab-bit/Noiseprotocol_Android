package com.example.lrmprotokoll.drive

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCsvTest {

    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun kopfzeileNenntDbNichtDbaWeilDieBewertungUnbestaetigtIst() {
        val csv = DriveCsv.schreibe(emptyList(), zone)
        assertTrue(
            "Spaltenkopf darf keine A-Bewertung behaupten, die nicht bestaetigt ist",
            csv.contains("LAeq_dB;LAFmax_dB;LAFmin_dB"),
        )
        assertTrue(!csv.contains("_dBA"))
    }

    @Test
    fun beginntMitUtf8BomDamitExcelUmlauteKorrektZeigt() {
        val csv = DriveCsv.schreibe(emptyList(), zone)
        assertEquals('﻿', csv.first())
    }

    @Test
    fun normaleZeileEntsprichtDemPlanBeispiel() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:00Z"), // 08:00 MESZ
            laeqDb = 52.3, lafMaxDb = 61.8, lafMinDb = 48.1,
            samples = 20, quelle = "PCE_323", ereignis = false, klassifikation = null,
        )
        val csv = DriveCsv.schreibe(listOf(zeile), zone)

        assertTrue(csv.contains("2026-08-16T08:00:00+02:00;52,3;61,8;48,1;20;PCE_323;;"))
    }

    @Test
    fun ereigniszeileZeigtJaUndKlassifikation() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:10Z"),
            laeqDb = 71.4, lafMaxDb = 89.2, lafMinDb = 53.0,
            samples = 20, quelle = "PCE_323", ereignis = true, klassifikation = "Hämmern",
        )
        val csv = DriveCsv.schreibe(listOf(zeile), zone)

        assertTrue(csv.contains("71,4;89,2;53,0;20;PCE_323;JA;Hämmern"))
    }

    @Test
    fun lueckenzeileZeigtLeereWerteAberEchteNullenNichtSondernLeerstring() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:20Z"),
            laeqDb = null, lafMaxDb = null, lafMinDb = null,
            samples = 0, quelle = QUELLE_KEINE_VERBINDUNG, ereignis = false, klassifikation = null,
        )
        val csv = DriveCsv.schreibe(listOf(zeile), zone)

        assertTrue(csv.contains("2026-08-16T08:00:20+02:00;;;;0;KEINE_VERBINDUNG;;"))
    }

    @Test
    fun dezimaltrennzeichenIstKommaNichtPunkt() {
        val zeile = AggregatZeile(
            fensterStart = Instant.parse("2026-08-16T06:00:00Z"),
            laeqDb = 52.3, lafMaxDb = 61.8, lafMinDb = 48.1,
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
