package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.ConnectionEventEntity
import com.example.lrmprotokoll.data.ConnectionEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectsTest {

    private fun event(type: String) = ConnectionEventEntity(sessionId = 1, at = 0, type = type, reason = null)

    @Test
    fun ohneEreignisseKeineReconnects() {
        assertEquals(0, zaehleReconnects(emptyList()))
    }

    @Test
    fun zaehltDisconnectedUndDegraded() {
        val events = listOf(
            event(ConnectionEventType.CONNECTED),
            event(ConnectionEventType.DISCONNECTED),
            event(ConnectionEventType.RECOVERED),
            event(ConnectionEventType.DEGRADED),
            event(ConnectionEventType.RECOVERED),
        )
        assertEquals(2, zaehleReconnects(events))
    }

    @Test
    fun connectedUndRecoveredZaehlenNicht() {
        // Gegentest: nur der Ausfallbeginn (DEGRADED/DISCONNECTED) ist ein Reconnect-Ausloeser,
        // nicht die Erholung davon (RECOVERED) oder die erste Verbindung (CONNECTED).
        val events = listOf(event(ConnectionEventType.CONNECTED), event(ConnectionEventType.RECOVERED))
        assertEquals(0, zaehleReconnects(events))
    }
}
