package com.example.lrmprotokoll.diagnose

/**
 * Stabile englische Fehlercodes fuer strukturierte Diagnose, Klassifizierung und Gruppierung
 * (Konzept Abschnitt 5).
 */
enum class DiagnosticCode {
    // App & Prozess
    APP_UNCAUGHT,
    APP_PREVIOUS_EXIT,
    APP_STARTUP_FAILED,

    /** Der Main-Thread hat laenger als die Watchdog-Schwelle nicht reagiert (O-8, [AnrWatchdog]). */
    APP_ANR_WATCHDOG,

    // Audio & Aufnahme
    AUDIO_INIT_FAILED,
    AUDIO_READ_FAILED,
    AUDIO_FILE_WRITE_FAILED,
    AUDIO_FOREGROUND_SERVICE_FAILED,

    /**
     * Der Dienst soll laut persistentem Zustand weiter Audio erfassen, aber der echte
     * AudioRecord-Pfad ist nicht aktiv. Dieser Fehler haette den HW-Test vom 07.09. direkt nach
     * dem Service-Recreate sichtbar gemacht, statt erst ueber fehlende WAV-Dateien aufzufallen.
     */
    AUDIO_MONITORING_STOPPED_UNEXPECTEDLY,

    /** Service wurde zerstoert, obwohl die Audio-Ueberwachung nicht explizit beendet wurde. */
    AUDIO_SERVICE_DESTROYED_WHILE_RECORDING,

    /** Eine bereits gestartete Ereignis-WAV wurde vor der konfigurierten Dauer unterbrochen. */
    AUDIO_WAV_INTERRUPTED,

    /**
     * Ueber der Schwelle gemessen, aber ueber ein ganzes Zeitfenster kein einziges Ereignis
     * gespeichert - die Ausloesung funktioniert stillschweigend nicht. Siehe [TriggerWachhund].
     */
    TRIGGER_STILLER_AUSFALL,

    // Videobeweis (M11 Etappe B)

    /** Die Kamera liess sich nicht oeffnen oder die Aufnahme brach ab. */
    VIDEO_CAPTURE_FAILED,

    /**
     * Das stumme Video und der mitgeschnittene Ton liessen sich nicht zusammenfuehren. Beide
     * Quelldateien bleiben in diesem Fall erhalten - siehe [com.example.lrmprotokoll.video.VideoMuxWorker].
     */
    VIDEO_MUX_FAILED,

    // KI & Klassifikation
    AI_MODEL_INIT_FAILED,
    AI_INFERENCE_FAILED,
    AI_INVALID_OUTPUT,

    // Datenbank & Persistenz
    DB_OPEN_FAILED,
    DB_MIGRATION_FAILED,
    DB_WRITE_FAILED,

    // BLE & Messgeraet
    BLE_SCAN_FAILED,
    BLE_CONNECT_FAILED,
    BLE_GATT_ERROR,
    BLE_GATT_TIMEOUT,
    BLE_STREAM_STALLED,
    BLE_DECODE_RATE_HIGH,
    BLE_CADENCE_INVALID,

    // Alarmierung
    ALERT_LOCAL_FAILED,
    ALERT_NTFY_FAILED,
    ALERT_STATE_PERSIST_FAILED,

    // Heartbeat / Totmannschalter
    HEARTBEAT_SEND_FAILED,
    HEARTBEAT_CONFIGURATION_INVALID,

    // Google Drive & Synchronisation
    DRIVE_AUTH_REQUIRED,
    DRIVE_AUTH_FAILED,
    DRIVE_SYNC_FAILED,
    DRIVE_UPLOAD_FAILED,
    DRIVE_FOLDER_NOT_FOUND,

    // Sicherung & Wiederherstellung
    /**
     * Erstellen einer Datenbanksicherung ist fehlgeschlagen - beim Bauen der ZIP (z. B. zu wenig
     * Speicherplatz) oder beim Hochladen (lokal ueber SAF oder automatisch nach Google Drive).
     * Ging bis 23.09.2026 nur an eine INFO-Breadcrumb (automatische Drive-Sicherung) bzw.
     * ueberhaupt nirgends hin (lokale Sicherung, [com.example.lrmprotokoll.ui.SettingsScreen]) -
     * 95 gescheiterte Sicherungsversuche zwischen dem 16. und 23.09.2026 blieben deshalb
     * unbemerkt (siehe `docs/BEFUNDE_P30_2026-09-23.md`, Abschnitt 2/A2).
     */
    BACKUP_CREATE_FAILED,

    /**
     * Einspielen einer Sicherung (lokal ueber SAF oder aus Drive heruntergeladen) ist
     * fehlgeschlagen. Ging bis 12.09.2026 nur an eine fluechtige Snackbar
     * ([com.example.lrmprotokoll.ui.SettingsScreen]) - ein Fehlschlag tauchte damit nie im
     * Support-Bundle auf.
     */
    BACKUP_RESTORE_FAILED,

    // Berichte, Export & Wiedergabe
    REPORT_CREATE_FAILED,
    EXPORT_FAILED,
    PLAYBACK_FAILED,
    SUPPORT_BUNDLE_FAILED,

    // Berechtigungen
    PERMISSION_REVOKED_DURING_OPERATION
}
