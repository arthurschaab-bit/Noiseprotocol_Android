package com.example.lrmprotokoll.meter.ble

import java.util.UUID

/**
 * GATT-Profil des PCE-323, ermittelt in M0 (Protokoll-Discovery) am realen Geraet
 * (MAC 30:1B:97:F8:C8:AD, Modul Lierda LSD4BTC-T55ALSP001, Firmware v1.7.231020).
 * Einzige Quelle der Wahrheit fuer UUIDs und Frame-Layout - vollstaendige Herleitung,
 * Rohdaten und offene Fragen in docs/PROTOKOLL_PCE-323.md.
 *
 * WICHTIG: Das im Implementierungsplan (Abschnitt 2.2) beschriebene 6-Byte-Format mit
 * 0x7F-Start-/0x00-Endmarker (PCE-322A-Geraetefamilie, libsigrok) trifft auf dieses reale
 * Geraet NICHT zu - es ist ein voellig anderes, 23 Byte langes Frame-Format. Der in M1
 * gebaute `Pce323FrameDecoder` ist damit gegen die falsche Annahme getestet und muss vor
 * M2 ersetzt werden.
 */
object Pce323Profile {

    /** Custom-Service, unter dem alle Mess-Characteristics haengen. */
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

    /** Liefert per Notify den kontinuierlichen Messwert-Strom (bestaetigt in M0). */
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    /** Write / Write-No-Response, vermutlich Kommandokanal - in M0 nicht benutzt/beobachtet. */
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    /**
     * Zwei weitere Characteristics mit Notify/Read/Write-Eigenschaften, Zweck ungeklaert:
     * Read auf fe63 schlaegt mit "GATT READ NOT PERMIT" fehl, Read auf fe64 liefert 0 Byte.
     * Keine Notifications von beiden in der M0-Aufzeichnung beobachtet.
     */
    val UNKNOWN_CHARACTERISTIC_FE63_UUID: UUID = UUID.fromString("0000fe63-0000-1000-8000-00805f9b34fb")
    val UNKNOWN_CHARACTERISTIC_FE64_UUID: UUID = UUID.fromString("0000fe64-0000-1000-8000-00805f9b34fb")

    /**
     * Kein Kommando noetig, um den Notify-Strom zu starten: In der M0-Aufzeichnung begann
     * der Messwertfluss unmittelbar nach dem Schreiben des CCCD-Descriptors (0x2902) auf
     * [NOTIFY_CHARACTERISTIC_UUID] - ohne vorherigen Write auf [WRITE_CHARACTERISTIC_UUID].
     */
    const val CONNECT_COMMAND_REQUIRED = false

    /**
     * Bonding schlaegt fehl: device.createBond() fuehrte in der M0-Aufzeichnung zu einem
     * sofortigen Disconnect (status 19) und Bond-Status AUTH_FAILED. Das Modul unterstuetzt
     * offenbar kein reguläres Pairing - Sicherheit muss vollstaendig auf App-Ebene
     * hergestellt werden (Plan Abschnitt 6: Geraete-Pinning, Stream-Plausibilisierung).
     */
    const val BONDING_SUPPORTED = false

    /** Beobachtete Frame-Rate: Delta 449-586 ms, Mittel ~515 ms (n=99) - rund 1,9-2 Hz. */
    const val EXPECTED_FRAME_PERIOD_MS = 515L

    /**
     * Logisches Frame-Layout auf [NOTIFY_CHARACTERISTIC_UUID], 23 Byte. In der
     * M0-Aufzeichnung wegen Default-ATT-MTU 23 (20 Byte Notify-Nutzlast) auf zwei
     * BLE-Notify-Events verteilt: 20 Byte + 3 Byte, in 99 von 99 Faellen exakt so. Ein
     * Decoder muss deshalb byteweise ueber einen Ringpuffer resynchronisieren statt
     * paketweise zu arbeiten, unabhaengig davon, ob eine groessere MTU das in M2 vermeidet.
     *
     *   [0..13]  konstanter Header: D5 03 00 00 00 10 C3 00 01 01 08 00 00 00 (14 Byte)
     *   [14..17] Messwert als IEEE-754 float32, big endian, Wert direkt in dB
     *   [18..19] konstanter Footer: 01 0F
     *   [20..22] konstanter Trailer: 2C 00 0D (Funktion ungeklaert)
     *
     * Alle konstanten Bytes waren in 99/99 aufgezeichneten Frames identisch - ihre
     * Bedeutung (Geraete-/Session-ID? Flags? A/C-Bewertung? Bereich?) ist NICHT geklaert,
     * weil sich in dieser Aufzeichnung nichts daran geaendert hat. Dafuer muesste eine
     * Folgeaufnahme die Bewertung (A/C) oder Zeitbewertung (Fast/Slow) am Geraet umschalten
     * und beobachten, welches Byte reagiert - siehe offene Punkte in
     * docs/PROTOKOLL_PCE-323.md.
     */
    const val FRAME_SIZE = 23
    val FRAME_HEADER = byteArrayOf(
        0xD5.toByte(), 0x03, 0x00, 0x00, 0x00, 0x10, 0xC3.toByte(), 0x00,
        0x01, 0x01, 0x08, 0x00, 0x00, 0x00,
    )
    const val LEVEL_OFFSET = 14
    const val LEVEL_SIZE = 4
    val FRAME_FOOTER = byteArrayOf(0x01, 0x0F)
    val FRAME_TRAILER = byteArrayOf(0x2C, 0x00, 0x0D)

    /**
     * Folgeaufzeichnung (Owner, 2026-08-17, vier Logs beim gezielten Umschalten von Bereich,
     * Fast/Slow und A/C): drei der bislang konstanten Bytes bewegen sich, jedes sauber isoliert
     * in einem eigenen Test - siehe docs/PROTOKOLL_PCE-323.md Abschnitt 9 fuer die Rohdaten und
     * die Herleitung.
     *
     *   Offset 7  (im Header): 4 Werte 0x00-0x03, sauberer Rundlauf beim wiederholten
     *             Betaetigen einer Taste -> Messbereich.
     *   Offset 19 (2. Footer-Byte): 0x0F/0x10, Wechsel faellt jeweils mit einem Pegelsprung
     *             zusammen (Detektor-Zeitkonstante aendert sich) -> Fast/Slow.
     *   Offset 20 (1. Trailer-Byte): 0x2C/0x2D, wechselt sauber waehrend Bereich und Fast/Slow
     *             unveraendert blieben -> A/C-Bewertung.
     *
     * Die BYTE-POSITIONEN sind damit durch wiederholte, sich gegenseitig nicht ueberlappende
     * Umschaltungen belegt.
     *
     * Die WERTE-ZUORDNUNG fuer Fast/Slow und A/C hat der Owner am 2026-08-20 im Gerätetest
     * bestätigt (dB(A)/dB(C) und Fast/Slow stimmten in der Live-Anzeige exakt mit der
     * Geräteanzeige überein). Die Messbereichs-Zuordnung war dagegen falsch - der Gerätetest
     * ergab eine um zwei Positionen verschobene Zuordnung innerhalb desselben Rundlaufs
     * (Geraet 30-130 -> App zeigte 50-100, Geraet 30-80 -> App zeigte 80-130, Geraet 50-100 ->
     * App zeigte 30-130, Geraet 80-130 -> App zeigte 30-80), unten korrigiert. Ein nicht
     * erkannter Bytewert liefert weiterhin bewusst `null`, keinen Default.
     */
    const val RANGE_BYTE_OFFSET = 7
    const val TIME_WEIGHTING_BYTE_OFFSET = 19
    const val WEIGHTING_BYTE_OFFSET = 20

    const val RANGE_VALUE_50_100: Byte = 0x00
    const val RANGE_VALUE_80_130: Byte = 0x01
    const val RANGE_VALUE_30_130: Byte = 0x02
    const val RANGE_VALUE_30_80: Byte = 0x03

    const val TIME_WEIGHTING_VALUE_FAST: Byte = 0x0F
    const val TIME_WEIGHTING_VALUE_SLOW: Byte = 0x10

    const val WEIGHTING_VALUE_A: Byte = 0x2C
    const val WEIGHTING_VALUE_C: Byte = 0x2D

    /**
     * Einziger Umschaltpunkt fuer den Bestaetigungsstatus der obigen Werte-Zuordnung
     * (Review PR #15, Befund 1): [Pce323FrameDecoder] setzt [MeterFrame.modeAssumptionConfirmed]
     * hierauf, [MeterScreen] zeigt den Annahme-Hinweis nur, solange dieser Wert `false` ist.
     *
     * Vom Owner am 2026-08-20 im Geraetetest bestaetigt (dB(A)/dB(C) und Fast/Slow stimmten live
     * exakt mit der Geraeteanzeige ueberein, siehe docs/PROTOKOLL_PCE-323.md Abschnitt 10) - ab
     * hier gilt jeder non-null-Wert in [weighting]/[timeWeighting]/[range] als gesichert, nicht
     * mehr nur als angenommen.
     */
    const val MODE_ASSUMPTION_CONFIRMED = true
}
