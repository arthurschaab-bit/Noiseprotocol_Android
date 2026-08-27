package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.meter.ConnectionState

enum class HealthStatus {
    OK,
    WARNING,
    ERROR
}

enum class HealthActionType {
    REQUEST_AUDIO_PERMISSION,
    REQUEST_BLUETOOTH_PERMISSION,
    REQUEST_NOTIFICATION_PERMISSION,
    BATTERY_OPTIMIZATION,
    EXACT_ALARM_PERMISSION,
    ENABLE_BLUETOOTH,
    CONNECT_METER,
    CONFIGURE_ALERTING,
    CONFIGURE_DRIVE,
    OPEN_SETTINGS
}

data class HealthCheckItem(
    val id: String,
    val title: String,
    val description: String,
    val status: HealthStatus,
    val actionLabel: String? = null,
    val actionType: HealthActionType? = null,
)

data class SystemHealthParams(
    val hasAudioPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val hasBluetoothPermission: Boolean,
    val isBatteryOptimizationIgnored: Boolean,
    val canScheduleExactAlarms: Boolean,
    val isBluetoothAdapterEnabled: Boolean,
    val isMeterPinned: Boolean,
    val meterConnectionState: ConnectionState,
    val isAlertingConfigured: Boolean,
    val isDriveSyncConfigured: Boolean,
    val isDiagnoseLoggingActive: Boolean,
    val isMonitoringActive: Boolean,
)

data class SystemHealthOverview(
    val items: List<HealthCheckItem>,
    val overallStatus: HealthStatus,
    val hasProblemWhileMonitoring: Boolean,
)

/**
 * Reine, JVM-testbare Bewertungslogik für die System-Selbstprüfung (F3 / M10).
 */
fun bewerteSystemZustand(params: SystemHealthParams): SystemHealthOverview {
    val items = mutableListOf<HealthCheckItem>()

    // 1. Audio-Berechtigung
    if (params.hasAudioPermission) {
        items += HealthCheckItem(
            id = "audio_perm",
            title = "Mikrofon-Berechtigung",
            description = "Erteilt (Audioaufnahme aktiv)",
            status = HealthStatus.OK
        )
    } else {
        items += HealthCheckItem(
            id = "audio_perm",
            title = "Mikrofon-Berechtigung",
            description = "Fehlt (keine Audioaufnahmen bei Schwellwertüberschreitung möglich)",
            status = HealthStatus.ERROR,
            actionLabel = "Erteilen",
            actionType = HealthActionType.REQUEST_AUDIO_PERMISSION
        )
    }

    // 2. Benachrichtigungen
    if (params.hasNotificationPermission) {
        items += HealthCheckItem(
            id = "notif_perm",
            title = "Benachrichtigungen",
            description = "Erteilt (Vordergrunddienst & Alarme sichtbar)",
            status = HealthStatus.OK
        )
    } else {
        items += HealthCheckItem(
            id = "notif_perm",
            title = "Benachrichtigungen",
            description = "Fehlt (Dienststatus wird nicht in Systemleiste angezeigt)",
            status = HealthStatus.WARNING,
            actionLabel = "Aktivieren",
            actionType = HealthActionType.REQUEST_NOTIFICATION_PERMISSION
        )
    }

    // 3. Bluetooth-Berechtigung & Adapter
    if (!params.hasBluetoothPermission && params.isMeterPinned) {
        items += HealthCheckItem(
            id = "bt_perm",
            title = "Bluetooth-Berechtigung",
            description = "Fehlt für PCE-323 Kopplung",
            status = HealthStatus.ERROR,
            actionLabel = "Erteilen",
            actionType = HealthActionType.REQUEST_BLUETOOTH_PERMISSION
        )
    } else if (!params.isBluetoothAdapterEnabled && params.isMeterPinned) {
        items += HealthCheckItem(
            id = "bt_adapter",
            title = "Bluetooth-Adapter",
            description = "Bluetooth ist am Smartphone ausgeschaltet",
            status = HealthStatus.WARNING,
            actionLabel = "Einschalten",
            actionType = HealthActionType.ENABLE_BLUETOOTH
        )
    } else if (params.isMeterPinned) {
        val meterDesc = when (params.meterConnectionState) {
            ConnectionState.STREAMING -> "PCE-323 verbunden und sendet Messwerte"
            ConnectionState.CONNECTING, ConnectionState.SCANNING, ConnectionState.RECONNECTING,
            ConnectionState.DISCOVERING, ConnectionState.SUBSCRIBING -> "Verbindung wird aufgebaut…"
            ConnectionState.DEGRADED -> "Verbindung instabil oder gestört"
            ConnectionState.FAILED -> "Verbindung fehlgeschlagen"
            ConnectionState.DISCONNECTED -> "Messgerät getrennt (Suche nach Verbindung…)"
            ConnectionState.IDLE -> "Messgerät gepinnt, Überwachung nicht aktiv"
        }
        val meterStatus = when (params.meterConnectionState) {
            ConnectionState.STREAMING -> HealthStatus.OK
            ConnectionState.CONNECTING, ConnectionState.SCANNING, ConnectionState.RECONNECTING,
            ConnectionState.DISCOVERING, ConnectionState.SUBSCRIBING -> HealthStatus.OK
            ConnectionState.DEGRADED -> HealthStatus.WARNING
            ConnectionState.FAILED -> HealthStatus.ERROR
            ConnectionState.DISCONNECTED -> if (params.isMonitoringActive) HealthStatus.WARNING else HealthStatus.OK
            ConnectionState.IDLE -> HealthStatus.OK
        }
        items += HealthCheckItem(
            id = "meter_status",
            title = "Schallpegelmesser PCE-323",
            description = meterDesc,
            status = meterStatus,
            actionLabel = if (params.meterConnectionState == ConnectionState.DISCONNECTED) "Verbinden" else null,
            actionType = if (params.meterConnectionState == ConnectionState.DISCONNECTED) HealthActionType.CONNECT_METER else null
        )
    }

    // 4. Akku-Optimierung
    if (params.isBatteryOptimizationIgnored) {
        items += HealthCheckItem(
            id = "battery_opt",
            title = "Akku-Optimierung",
            description = "Ausgenommen (Dauermessung vor Android-Drosselung geschützt)",
            status = HealthStatus.OK
        )
    } else {
        items += HealthCheckItem(
            id = "battery_opt",
            title = "Akku-Optimierung",
            description = "Aktiv (Android könnte Langzeitmessungen im Standby beenden)",
            status = HealthStatus.WARNING,
            actionLabel = "Deaktivieren",
            actionType = HealthActionType.BATTERY_OPTIMIZATION
        )
    }

    // 5. Exakte Alarme
    if (params.canScheduleExactAlarms) {
        items += HealthCheckItem(
            id = "exact_alarm",
            title = "Exakte Alarme",
            description = "Erlaubt (präzise Karenz- und Totmann-Timer)",
            status = HealthStatus.OK
        )
    } else {
        items += HealthCheckItem(
            id = "exact_alarm",
            title = "Exakte Alarme",
            description = "Eingeschränkt (Timer können verzögert auslösen)",
            status = HealthStatus.WARNING,
            actionLabel = "Erlauben",
            actionType = HealthActionType.EXACT_ALARM_PERMISSION
        )
    }

    // 6. Alarmierung & Cloud-Sync
    if (params.isAlertingConfigured) {
        items += HealthCheckItem(
            id = "alerting",
            title = "Alarmierung (ntfy/Push)",
            description = "Aktiv konfiguriert",
            status = HealthStatus.OK
        )
    }
    if (params.isDriveSyncConfigured) {
        items += HealthCheckItem(
            id = "drive_sync",
            title = "Google Drive Sync",
            description = "Aktiv eingerichtet",
            status = HealthStatus.OK
        )
    }

    // 7. Diagnose-Log (PROMPT_M10_FUNKTIONEN.md F3: "Diagnose-Log an oder aus?" - rein
    // informativ, ein deaktiviertes Log ist kein Problem, deshalb immer OK statt WARNING/ERROR.
    items += HealthCheckItem(
        id = "diagnose_log",
        title = "Diagnose-Log",
        description = if (params.isDiagnoseLoggingActive) "Aktiv" else "Deaktiviert",
        status = HealthStatus.OK
    )

    // Gesamtauswertung
    val overallStatus = when {
        items.any { it.status == HealthStatus.ERROR } -> HealthStatus.ERROR
        items.any { it.status == HealthStatus.WARNING } -> HealthStatus.WARNING
        else -> HealthStatus.OK
    }

    val hasProblemWhileMonitoring = params.isMonitoringActive && items.any { it.status == HealthStatus.ERROR }

    return SystemHealthOverview(
        items = items,
        overallStatus = overallStatus,
        hasProblemWhileMonitoring = hasProblemWhileMonitoring
    )
}
