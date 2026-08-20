package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class AusfallbaenderTest {

    private fun event(at: Long, type: String) =
        ConnectionEventEntity(sessionId = 1, at = at, type = type, reason = null)

    @Test
    fun ohneEreignisseKeineBaender() {
        val baender = leiteAusfallbaenderAb(emptyList(), sessionEndedAt = 2000L)
        assertEquals(emptyList<Ausfallband>(), baender)
    }

    @Test
    fun geschlossenerAusfallErgibtEinBand() {
        val events = listOf(
            event(1000, ConnectionEventType.CONNECTED),
            event(2000, ConnectionEventType.DISCONNECTED),
            event(2500, ConnectionEventType.RECOVERED),
        )
        val baender = leiteAusfallbaenderAb(events, sessionEndedAt = 3000L)
        assertEquals(listOf(Ausfallband(2000, 2500)), baender)
    }

    @Test
    fun mehrereGetrennteAusfaelleErgebenMehrereBaender() {
        val events = listOf(
            event(1000, ConnectionEventType.CONNECTED),
            event(2000, ConnectionEventType.DEGRADED),
            event(2500, ConnectionEventType.RECOVERED),
            event(4000, ConnectionEventType.DISCONNECTED),
            event(4800, ConnectionEventType.RECOVERED),
        )
        val baender = leiteAusfallbaenderAb(events, sessionEndedAt = 5000L)
        assertEquals(listOf(Ausfallband(2000, 2500), Ausfallband(4000, 4800)), baender)
    }

    @Test
    fun sessionEndetMittenImAusfallSchliesstBandAufSessionende() {
        val events = listOf(
            event(1000, ConnectionEventType.CONNECTED),
            event(4000, ConnectionEventType.DISCONNECTED),
        )
        val baender = leiteAusfallbaenderAb(events, sessionEndedAt = 4500L)
        assertEquals(listOf(Ausfallband(4000, 4500)), baender)
    }

    @Test
    fun laufendeSessionMitOffenemAusfallBleibtOffen() {
        // sessionEndedAt = null heisst "Session laeuft noch" - ein offenes Band darf nicht auf
        // irgendeinen Zeitpunkt geschlossen werden, den es nicht gibt.
        val events = listOf(
            event(1000, ConnectionEventType.CONNECTED),
            event(4000, ConnectionEventType.DISCONNECTED),
        )
        val baender = leiteAusfallbaenderAb(events, sessionEndedAt = null)
        assertEquals(listOf(Ausfallband(4000, null)), baender)
    }

    @Test
    fun unsortierteEreignisseWerdenTrotzdemKorrektGepaart() {
        // Gegentest zur chronologischen Sortierung: DAO liefert i.d.R. sortiert, aber die
        // Funktion darf sich nicht darauf verlassen.
        val events = listOf(
            event(2500, ConnectionEventType.RECOVERED),
            event(1000, ConnectionEventType.CONNECTED),
            event(2000, ConnectionEventType.DISCONNECTED),
        )
        val baender = leiteAusfallbaenderAb(events, sessionEndedAt = 3000L)
        assertEquals(listOf(Ausfallband(2000, 2500)), baender)
    }

    @Test
    fun ueberzaehligesRecoveredOhneOffenesBandWirdIgnoriert() {
        // Kann theoretisch bei einem Datenfehler vorkommen - darf keine Exception werfen und
        // kein Phantom-Band mit negativer oder unsinniger Dauer erzeugen.
        val events = listOf(
            event(1000, ConnectionEventType.RECOVERED),
            event(2000, ConnectionEventType.DISCONNECTED),
            event(2500, ConnectionEventType.RECOVERED),
        )
        val baender = leiteAusfallbaenderAb(events, sessionEndedAt = 3000L)
        assertEquals(listOf(Ausfallband(2000, 2500)), baender)
    }
}
