package com.example.lrmprotokoll.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Review-Befund zu PR #220: Wer am Folgetag die Stammdaten der gestrigen Messung korrigiert,
 * erzeugte einen Eintrag fuer **heute** - der Bericht fuer gestern sah die Korrektur nie.
 * [messtagFuerStammdatenKorrektur] entscheidet, wann ein Messtag mitgegeben werden muss.
 */
class MesstagFuerStammdatenKorrekturTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun zeit(
        jahr: Int,
        monat: Int,
        tag: Int,
        stunde: Int,
        minute: Int = 0,
    ): Long {
        val lokal = LocalDateTime.of(jahr, monat, tag, stunde, minute)
        val zoniert = lokal.atZone(zone)
        return zoniert.toInstant().toEpochMilli()
    }

    /** Der Fehler aus dem Review: gestern gemessen, heute korrigiert. */
    @Test
    fun beendeteSessionVonGesternWirdDemGestrigenMesstagZugeordnet() {
        val ergebnis =
            messtagFuerStammdatenKorrektur(
                startedAt = zeit(2026, 9, 26, 20),
                endedAt = zeit(2026, 9, 26, 23),
                jetzt = zeit(2026, 9, 27, 9),
                zone = zone,
            )

        assertEquals(
            "Die Korrektur muss zum Messtag der Session gehoeren, nicht zum Tag der Eingabe",
            messtagGrenzen(LocalDate.of(2026, 9, 26), zone).first,
            ergebnis,
        )
    }

    /** Heute gemessen, heute korrigiert: regulaerer Weg, kein Nachtrag. */
    @Test
    fun beendeteSessionVonHeuteBrauchtKeinenMesstag() {
        val ergebnis =
            messtagFuerStammdatenKorrektur(
                startedAt = zeit(2026, 9, 27, 8),
                endedAt = zeit(2026, 9, 27, 10),
                jetzt = zeit(2026, 9, 27, 11),
                zone = zone,
            )

        assertNull(ergebnis)
    }

    /**
     * Eine laufende Session bekommt nie einen Messtag - auch nicht, wenn sie gestern begann. Der
     * Nutzer dokumentiert dann die Bedingungen von jetzt, und die gehoeren zu heute. Ein gesetzter
     * `giltFuerTagStart` wuerde den Eintrag ausserdem zum Nachtrag machen und ihn damit aus
     * `StammdatenVerlaufDao.letzte()` als Vorbelegung der naechsten Messung herausnehmen.
     */
    @Test
    fun laufendeSessionBekommtNieEinenMesstag() {
        val gesternBegonnen =
            messtagFuerStammdatenKorrektur(
                startedAt = zeit(2026, 9, 26, 22),
                endedAt = null,
                jetzt = zeit(2026, 9, 27, 9),
                zone = zone,
            )
        val heuteBegonnen =
            messtagFuerStammdatenKorrektur(
                startedAt = zeit(2026, 9, 27, 8),
                endedAt = null,
                jetzt = zeit(2026, 9, 27, 9),
                zone = zone,
            )

        assertNull("Laufende Messung, gestern begonnen", gesternBegonnen)
        assertNull("Laufende Messung, heute begonnen", heuteBegonnen)
    }

    /** Mehrtaegige Session: Starttag. Eine gezielte Auswahl gibt es noch nicht (Owner-Entscheidung). */
    @Test
    fun mehrtaegigeSessionWirdDemStarttagZugeordnet() {
        val ergebnis =
            messtagFuerStammdatenKorrektur(
                startedAt = zeit(2026, 9, 25, 21),
                endedAt = zeit(2026, 9, 26, 6),
                jetzt = zeit(2026, 9, 27, 9),
                zone = zone,
            )

        assertEquals(
            "Der Starttag ist in jedem Fall richtiger als der heutige Tag",
            messtagGrenzen(LocalDate.of(2026, 9, 25), zone).first,
            ergebnis,
        )
    }

    /**
     * Ueber Mitternacht begonnen und am selben Kalendertag wie "jetzt" beendet: der Starttag
     * zaehlt, nicht das Ende. Eine um 23:50 begonnene und um 00:10 beendete Messung gehoert zum
     * Tag, an dem sie begann.
     */
    @Test
    fun sessionUeberMitternachtZaehltNachDemStarttag() {
        val ergebnis =
            messtagFuerStammdatenKorrektur(
                startedAt = zeit(2026, 9, 26, 23, 50),
                endedAt = zeit(2026, 9, 27, 0, 10),
                jetzt = zeit(2026, 9, 27, 9),
                zone = zone,
            )

        assertEquals(
            messtagGrenzen(LocalDate.of(2026, 9, 26), zone).first,
            ergebnis,
        )
    }

    /** Der zurueckgegebene Wert muss exakt der Tagesbeginn sein - StammdatenVerlaufDao.fuerTag
     * vergleicht `giltFuerTagStart = :von` auf Gleichheit, nicht auf einen Bereich. */
    @Test
    fun rueckgabeIstExaktDerTagesbeginn() {
        val ergebnis =
            messtagFuerStammdatenKorrektur(
                startedAt = zeit(2026, 9, 26, 20),
                endedAt = zeit(2026, 9, 26, 23),
                jetzt = zeit(2026, 9, 27, 9),
                zone = zone,
            )!!

        assertEquals(LocalDate.of(2026, 9, 26), lokalerMesstag(ergebnis, zone))
        assertEquals(zeit(2026, 9, 26, 0), ergebnis)
    }
}
