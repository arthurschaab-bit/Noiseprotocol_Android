package com.example.lrmprotokoll.diagnose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AusfallKlassifikatorTest {
    private val jetzt = 2_000_000_000_000L

    private fun zustand(
        datenAt: Long,
        bootCount: Int? = 239,
        akku: Int? = 80,
        strom: String? = Stromzustand.Quelle.KEINE.name,
        akkuStatus: String = Stromzustand.Status.ENTLAEDT.name,
        sauber: Boolean = false,
    ) = LetzterZustand(
        zeitpunkt = datenAt,
        akkuProzent = akku,
        stromquelle = strom,
        akkuStatus = akkuStatus,
        uptimeMs = 1_000_000L,
        bootCount = bootCount,
        letzteDatenAt = datenAt,
        bleZustand = "GETRENNT",
        mikrofonAktiv = false,
        dienstLaeuft = true,
        sauberBeendet = sauber,
        dozeAktiv = false,
        energiesparModus = false,
        hintergrundEingeschraenkt = false,
        standbyBucket = null,
        waechterLetzterLaufAt = null,
        heartbeatLetzterErfolgAt = null,
    )

    private fun einordnen(
        lueckeMinuten: Long,
        uptimeJetztMs: Long,
        bootJetzt: Int? = 239,
        vorher: LetzterZustand? = zustand(jetzt - lueckeMinuten * 60_000L),
        waechterAt: Long? = null,
        stopp: Boolean = false,
    ) = AusfallKlassifikator.klassifizieren(
        letzterZustand = vorher,
        letzteDatenAt = jetzt - lueckeMinuten * 60_000L,
        waechterLetzterLaufAt = waechterAt,
        uptimeJetztMs = uptimeJetztMs,
        bootCountJetzt = bootJetzt,
        jetztMs = jetzt,
        ausdruecklicherStoppProtokolliert = stopp,
    )

    @Test fun realeKurzeUptimeUndNeunProzentAkkuBelegenAkkuLeer() {
        assertEquals(AusfallKlasse.AKKU_LEER, einordnen(873, 369_039L, vorher = zustand(jetzt - 873 * 60_000L, akku = 9))?.klasse)
        assertEquals(AusfallKlasse.AKKU_LEER, einordnen(429, 1_502_292L, vorher = zustand(jetzt - 429 * 60_000L, akku = 9))?.klasse)
    }

    @Test fun bootCountWechselBeiLangerUptimeIstFrameworkNeustart() {
        val fall = einordnen(2_749, 361_899_222L, bootJetzt = 240)
        assertEquals(AusfallKlasse.FRAMEWORK_NEUSTART, fall?.klasse)
        assertEquals("239", fall?.belege?.get("bootCountVorher")?.toString())
    }

    @Test fun niedrigerAkkuOhneLadungBelegtAkkuLeerNurMitKernelNeustart() {
        val datenAt = jetzt - 873 * 60_000L
        assertEquals(AusfallKlasse.AKKU_LEER, einordnen(873, 369_039L, vorher = zustand(datenAt, akku = 10))?.klasse)
        assertEquals(AusfallKlasse.KERNEL_NEUSTART, einordnen(873, 369_039L, vorher = zustand(datenAt, akku = 11))?.klasse)
        assertEquals(
            AusfallKlasse.KERNEL_NEUSTART,
            einordnen(873, 369_039L, vorher = zustand(datenAt, akku = 9, strom = Stromzustand.Quelle.NETZTEIL.name))?.klasse,
        )
        assertEquals(
            AusfallKlasse.KERNEL_NEUSTART,
            einordnen(873, 369_039L, vorher = zustand(datenAt, akku = 9, akkuStatus = Stromzustand.Status.LAEDT.name))?.klasse,
        )
    }

    @Test fun ausdruecklicherStoppBrauchtZweiBelege() {
        val datenAt = jetzt - 60 * 60_000L
        assertEquals(AusfallKlasse.NUTZER_STOPP, einordnen(60, 10_000_000L, vorher = zustand(datenAt, sauber = true), stopp = true)?.klasse)
        assertEquals(AusfallKlasse.UNBEKANNT, einordnen(60, 10_000_000L, vorher = zustand(datenAt, sauber = true))?.klasse)
    }

    @Test fun gleichesBootUndDurchlaufendeUptimeBelegenStillstand() {
        val datenAt = jetzt - 500 * 60_000L
        val fall = einordnen(500, 100_000_000L, waechterAt = datenAt + 60_000L)
        assertEquals(AusfallKlasse.STILLSTAND_GERAET_LIEF, fall?.klasse)
        assertEquals(WaechterLief.JA, fall?.waechterLief)
        assertEquals(WaechterLief.NEIN, einordnen(500, 100_000_000L, waechterAt = datenAt)?.waechterLief)
    }

    @Test fun fehlendeBelegeUndGrenzbereichBleibenUnbekannt() {
        assertEquals(AusfallKlasse.UNBEKANNT, einordnen(60, 10_000_000L, vorher = null)?.klasse)
        assertEquals(AusfallKlasse.UNBEKANNT, einordnen(60, 10_000_000L, bootJetzt = null)?.klasse)
        assertEquals(AusfallKlasse.UNBEKANNT, einordnen(500, 500 * 60_000L + 10 * 60_000L)?.klasse)
        assertEquals(AusfallKlasse.UNBEKANNT, einordnen(500, 500 * 60_000L - 10 * 60_000L)?.klasse)
        assertNull(einordnen(5, 10_000_000L))
    }

    @Test fun grosseUhrverstellungWirdNichtAlsKernelNeustartAusgegeben() {
        assertEquals(AusfallKlasse.UNBEKANNT, einordnen(500, 500 * 60_000L + 2 * 60 * 60_000L)?.klasse)
    }

    @Test fun durchlaufenderKernelBleibtBeiUhrspruengenErkennbar() {
        for (uhrSprungMinuten in listOf(-120L, -10L, 10L, 120L)) {
            val lueckeMinuten = 500L + uhrSprungMinuten
            assertEquals(
                AusfallKlasse.STILLSTAND_GERAET_LIEF,
                einordnen(lueckeMinuten, 100 * 60 * 60_000L)?.klasse,
            )
            assertEquals(
                AusfallKlasse.FRAMEWORK_NEUSTART,
                einordnen(lueckeMinuten, 100 * 60 * 60_000L, bootJetzt = 240)?.klasse,
            )
        }
    }
}
