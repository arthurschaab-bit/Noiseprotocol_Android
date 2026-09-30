package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * S-4: die Messtage der Uebersicht werden aus den Sessions abgeleitet, nicht durch einen Durchlauf
 * ueber jeden Kalendertag. Diese Tests halten die drei Faelle fest, die dabei schiefgehen koennen:
 * leere Tage, eine ueber Mitternacht laufende Session und eine noch laufende Session.
 */
class MesstagUebersichtTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun session(
        id: Long,
        beginn: String,
        ende: String?,
    ) = SessionEntity(
        id = id,
        startedAt = millis(beginn),
        endedAt = ende?.let { millis(it) },
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        deviceName = "PCE-323",
        weighting = "A",
        timeWeighting = "FAST",
    )

    /** "2026-09-27T22:30" in der Testzone. */
    private fun millis(lokal: String): Long =
        java.time.LocalDateTime
            .parse(lokal)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun zeitraum(
        von: String,
        bis: String,
    ) = BerichtZeitraum(LocalDate.parse(von), LocalDate.parse(bis))

    @Test
    fun nurTageMitSessionsZaehlen() {
        val sessions =
            listOf(
                session(1, "2026-09-28T09:00", "2026-09-28T11:00"),
                session(2, "2026-09-25T14:00", "2026-09-25T15:30"),
            )

        val tage = messtageAusSessions(sessions, zeitraum("2026-09-01", "2026-09-30"), zone, millis("2026-09-30T12:00"))

        assertEquals(
            "Ein Monat mit zwei Messtagen darf nicht 30 Eintraege ergeben",
            listOf(LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-28")),
            tage,
        )
    }

    @Test
    fun sessionUeberMitternachtZaehltFuerBeideTage() {
        val sessions = listOf(session(1, "2026-09-27T22:30", "2026-09-28T06:15"))

        val tage = messtageAusSessions(sessions, zeitraum("2026-09-01", "2026-09-30"), zone, millis("2026-09-30T12:00"))

        assertEquals(
            listOf(LocalDate.parse("2026-09-27"), LocalDate.parse("2026-09-28")),
            tage,
        )
    }

    @Test
    fun laufendeSessionZaehltBisJetzt() {
        val sessions = listOf(session(1, "2026-09-28T23:00", null))

        val tage = messtageAusSessions(sessions, zeitraum("2026-09-01", "2026-09-30"), zone, millis("2026-09-30T08:00"))

        assertEquals(
            "Eine laufende Session beruehrt jeden Tag bis jetzt",
            listOf(
                LocalDate.parse("2026-09-28"),
                LocalDate.parse("2026-09-29"),
                LocalDate.parse("2026-09-30"),
            ),
            tage,
        )
    }

    @Test
    fun tageAusserhalbDesZeitraumsBleibenDraussen() {
        val sessions =
            listOf(
                session(1, "2026-09-27T22:30", "2026-09-28T06:15"),
                session(2, "2026-09-30T10:00", "2026-09-30T11:00"),
            )

        val tage = messtageAusSessions(sessions, zeitraum("2026-09-28", "2026-09-29"), zone, millis("2026-09-30T12:00"))

        assertEquals(listOf(LocalDate.parse("2026-09-28")), tage)
    }

    @Test
    fun ueberlappungZaehltNurDenGemeinsamenTeil() {
        val tagVon = millis("2026-09-28T00:00")
        val tagBis = millis("2026-09-29T00:00")

        assertEquals(
            "Eine Session von 22:30 bis 06:15 liegt mit 6h15 im zweiten Tag",
            6L * 3600_000 + 15 * 60_000,
            ueberlappungMs(millis("2026-09-27T22:30"), millis("2026-09-28T06:15"), tagVon, tagBis),
        )
        assertEquals(
            "Kein gemeinsamer Teil ergibt 0, nie etwas Negatives",
            0L,
            ueberlappungMs(millis("2026-09-25T10:00"), millis("2026-09-25T11:00"), tagVon, tagBis),
        )
    }
}
