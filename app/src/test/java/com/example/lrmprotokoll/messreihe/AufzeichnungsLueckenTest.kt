package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AufzeichnungsLueckenTest {

    private fun session(
        id: Long = 1L,
        startedAt: Long,
        endedAt: Long?,
        deviceAddress: String = "AA:BB:CC:DD:EE:FF",
        deviceName: String = "PCE-323",
        weighting: String? = "A",
        timeWeighting: String? = "FAST",
    ): SessionEntity = SessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        deviceAddress = deviceAddress,
        deviceName = deviceName,
        weighting = weighting,
        timeWeighting = timeWeighting,
    )

    @Test
    fun `keine Session ergibt den gesamten Bereich als Luecke`() {
        val von = 1000L
        val bis = 5000L
        val luecken = aufzeichnungsLuecken(emptyList(), von, bis)

        assertEquals(listOf(Zeitraum(von = von, bis = bis)), luecken)
    }

    @Test
    fun `zwei ueberlappende Sessions ergeben keine Luecke dazwischen`() {
        val von = 0L
        val bis = 10_000L
        // Session 1: 1000..6000, Session 2: 5000..9000
        val sessions = listOf(
            session(id = 1L, startedAt = 1000L, endedAt = 6000L),
            session(id = 2L, startedAt = 5000L, endedAt = 9000L),
        )

        val luecken = aufzeichnungsLuecken(sessions, von, bis)

        // Vor 1000 und nach 9000 ist Luecke, dazwischen verschmolzen
        assertEquals(
            listOf(
                Zeitraum(von = 0L, bis = 1000L),
                Zeitraum(von = 9000L, bis = 10_000L),
            ),
            luecken,
        )
    }

    @Test
    fun `offene aktive Session gilt bis bis`() {
        val von = 0L
        val bis = 10_000L
        // Session 1: 1000..3000, Session 2 (aktiv/offen): 5000..null
        val sessions = listOf(
            session(id = 1L, startedAt = 1000L, endedAt = 3000L),
            session(id = 2L, startedAt = 5000L, endedAt = null),
        )

        val luecken = aufzeichnungsLuecken(sessions, von, bis, aktiveOffeneSessionId = 2L)

        assertEquals(
            listOf(
                Zeitraum(von = 0L, bis = 1000L),
                Zeitraum(von = 3000L, bis = 5000L),
            ),
            luecken,
        )
    }

    @Test
    fun `aeltere verwaiste offene Session wird nicht faelschlich als aktiv bis bis verlaengert`() {
        val von = 0L
        val bis = 20_000L
        // Session 1 (verwaist, nie beendet): startedAt = 2000L, endedAt = null
        // Session 2 (neuer, aktiv/offen): startedAt = 10_000L, endedAt = null
        val sessions = listOf(
            session(id = 1L, startedAt = 2000L, endedAt = null),
            session(id = 2L, startedAt = 10_000L, endedAt = null),
        )

        val luecken = aufzeichnungsLuecken(sessions, von, bis, aktiveOffeneSessionId = 2L)

        // Nur Session 2 gilt als aktive offene bis bis (10_000..20_000).
        // Session 1 ohne endedAt wird ignoriert, damit sie keine riesige Lücke überdeckt.
        assertEquals(
            listOf(
                Zeitraum(von = 0L, bis = 10_000L),
            ),
            luecken,
        )
    }

    @Test
    fun `historische verwaiste Session ohne aktiveOffeneSessionId gilt nicht als aktiv bis bis`() {
        val von = 0L
        val bis = 86_400_000L // z. B. ganzer vergangener Tag
        // Session verwaist (App-Absturz um 08:00, nie beendet, kein aktiver Dienst mehr):
        val sessions = listOf(
            session(id = 1L, startedAt = 28_800_000L, endedAt = null),
        )

        // Keine aktive Session angegeben (da Dienst nicht laeuft bzw. historischer Tag):
        val luecken = aufzeichnungsLuecken(sessions, von, bis, aktiveOffeneSessionId = null)

        // Die verwaiste Session wird nicht bis Tagesende verlaengert; der gesamte Tag bleibt Luecke:
        assertEquals(
            listOf(
                Zeitraum(von = 0L, bis = 86_400_000L),
            ),
            luecken,
        )
    }

    @Test
    fun `Session ueber die Tagesgrenze deckt den Bereich korrekt ab`() {
        val tagStart = 86_400_000L
        val tagEnde = 2 * 86_400_000L

        // Session begann am Vortag und endet mitten im Tag: startedAt = 80_000_000L, endedAt = 100_000_000L
        val session1 = session(id = 1L, startedAt = 80_000_000L, endedAt = 100_000_000L)
        // Session begann im Tag und laeuft ueber den Tag hinaus: startedAt = 150_000_000L, endedAt = 190_000_000L
        val session2 = session(id = 2L, startedAt = 150_000_000L, endedAt = 190_000_000L)

        val luecken = aufzeichnungsLuecken(listOf(session1, session2), tagStart, tagEnde)

        // Luecke nur zwischen den beiden Sessions innerhalb des Tages: [100_000_000, 150_000_000)
        assertEquals(
            listOf(
                Zeitraum(von = 100_000_000L, bis = 150_000_000L),
            ),
            luecken,
        )
    }
}
