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

    // Audio & Aufnahme
    AUDIO_INIT_FAILED,
    AUDIO_READ_FAILED,
    AUDIO_FILE_WRITE_FAILED,
    AUDIO_FOREGROUND_SERVICE_FAILED,

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

    // Berichte, Export & Wiedergabe
    REPORT_CREATE_FAILED,
    EXPORT_FAILED,
    PLAYBACK_FAILED,
    SUPPORT_BUNDLE_FAILED,

    // Berechtigungen
    PERMISSION_REVOKED_DURING_OPERATION
}
