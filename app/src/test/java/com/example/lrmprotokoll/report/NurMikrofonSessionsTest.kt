package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.SessionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüfprotokoll-Befund 01 / Korrekturliste C-2: Grundlage für [leqBezeichnung] in den über
 * mehrere Sessions gepoolten Berichten ([PeriodenBericht.nurMikrofon]). Bewusst ohne Room/
 * Robolectric getestet - reine Listenlogik, siehe [nurMikrofonSessions]-KDoc.
 */
class NurMikrofonSessionsTest {

    private fun session(deviceAddress: String) = SessionEntity(
        startedAt = 0L, endedAt = null, deviceAddress = deviceAddress, deviceName = "x",
        weighting = null, timeWeighting = null,
    )

    @Test
    fun leereListeIstNichtNurMikrofon() {
        // "Keine Sessions" ist etwas anderes als "nur Mikrofon-Sessions" - die Kennwerte sind in
        // diesem Fall ohnehin leer, aber die Bezeichnung soll nicht faelschlich "Mittelwert"
        // suggerieren, wo gar keine Messung stattfand.
        assertFalse(nurMikrofonSessions(emptyList()))
    }

    @Test
    fun ausschliesslichMikrofonSessionsSindNurMikrofon() {
        assertTrue(nurMikrofonSessions(listOf(session(""), session(""))))
    }

    @Test
    fun einzelneMikrofonSessionIstNurMikrofon() {
        assertTrue(nurMikrofonSessions(listOf(session(""))))
    }

    @Test
    fun einzelneMessgeraetSessionIstNichtNurMikrofon() {
        assertFalse(nurMikrofonSessions(listOf(session("AA:BB:CC:DD:EE:FF"))))
    }

    @Test
    fun gemischterZeitraumIstNichtNurMikrofon() {
        // Ein einziger Messgeraet-Tag in einem sonst reinen Mikrofon-Zeitraum reicht, um die
        // Vereinfachung zu greifen (siehe leqBezeichnung-KDoc: keine getrennten Kennwerte je
        // Quelle innerhalb eines Zeitraums) - "LAeq" bleibt dann die Bezeichnung.
        assertFalse(nurMikrofonSessions(listOf(session(""), session("AA:BB:CC:DD:EE:FF"), session(""))))
    }
}
