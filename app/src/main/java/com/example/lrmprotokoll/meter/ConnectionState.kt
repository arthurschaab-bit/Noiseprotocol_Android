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

/**
 * Deutsche Kurzbezeichnung fuer Notification und MeterScreen - an einer Stelle gepflegt, damit
 * beide Anzeigen nie auseinanderlaufen. Verbindungszustand wird nie nur farblich kodiert
 * (Barrierefreiheit); dieser Text ist der verbindliche Teil davon, Icon/Farbe bleiben UI-lokal.
 */
fun ConnectionState.label(): String = when (this) {
    ConnectionState.IDLE -> "Nicht verbunden"
    ConnectionState.SCANNING -> "Suche…"
    ConnectionState.CONNECTING,
    ConnectionState.DISCOVERING,
    ConnectionState.SUBSCRIBING -> "Verbinde…"
    ConnectionState.STREAMING -> "Verbunden"
    ConnectionState.DEGRADED -> "Instabil"
    ConnectionState.RECONNECTING -> "Verbinde erneut…"
    ConnectionState.DISCONNECTED -> "Getrennt"
    ConnectionState.FAILED -> "Fehlgeschlagen"
}
