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
        sauber: Boolean = false,
    ) = LetzterZustand(
        zeitpunkt = datenAt,
        akkuProzent = akku,
        stromquelle = strom,
        akkuStatus = Stromzustand.Status.ENTLAEDT.name,
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

    @Test fun realeKurzeUptimeBelegtKernelNeustart() {
        assertEquals(AusfallKlasse.KERNEL_NEUSTART, einordnen(873, 369_039L)?.klasse)
        assertEquals(AusfallKlasse.KERNEL_NEUSTART, einordnen(429, 1_502_292L)?.klasse)
    }

    @Test fun bootCountWechselBeiLangerUptimeIstFrameworkNeustart() {
        val fall = einordnen(2_749, 361_899_222L, bootJetzt = 240)
        assertEquals(AusfallKlasse.FRAMEWORK_NEUSTART, fall?.klasse)
        assertEquals("239", fall?.belege?.get("bootCountVorher")?.toString())
    }

    @Test fun niedrigerAkkuOhneLadungBelegtAkkuLeerNurMitKernelNeustart() {
        val datenAt = jetzt - 873 * 60_000L
        assertEquals(AusfallKlasse.AKKU_LEER, einordnen(873, 369_039L, vorher = zustand(datenAt, akku = 5))?.klasse)
        assertEquals(
            AusfallKlasse.KERNEL_NEUSTART,
            einordnen(873, 369_039L, vorher = zustand(datenAt, akku = 5, strom = Stromzustand.Quelle.NETZTEIL.name))?.klasse,
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
}
