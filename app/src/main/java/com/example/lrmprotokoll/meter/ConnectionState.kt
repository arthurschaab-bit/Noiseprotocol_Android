package com.example.lrmprotokoll.meter

/**
 * Zustandsautomat der Verbindungsfuehrung (Plan Abschnitt 5.1). STREAMING wird erst erreicht,
 * wenn mindestens ein valider Frame dekodiert wurde - eine bestehende GATT-Verbindung ohne
 * Datenfluss zaehlt als Ausfall (DEGRADED), nicht als Erfolg.
 */
enum class ConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    SUBSCRIBING,
    STREAMING,
    DEGRADED,
    DISCONNECTED,
    RECONNECTING,
    FAILED,
}
