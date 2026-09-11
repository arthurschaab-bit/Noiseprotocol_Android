package com.example.lrmprotokoll.audio

/**
 * Fast-zeitgewichteter Pegelschaetzer fuer den unkalibrierten Mikrofonwert (IEC 61672 "Fast",
 * Tau=125ms) - Pruefprotokoll-Befund 01 / Korrekturliste C-1, Owner-Entscheidung vom 11.09.2026
 * ("Auf Fast-zeitgewichtetes RMS umstellen").
 *
 * Ersetzt die vorherige `max(rmsDb, peakDb - 6.0)`-Heuristik in `AudioRecordingService`, deren
 * Herkunft sich mangels Git-Historie (shallow Clone in der Pruefumgebung) nicht rekonstruieren
 * liess. Mittelt bewusst im LEISTUNGS-Bereich (Quadrat der Samples), nicht im dB-Bereich - ein
 * Mitteln bereits logarithmierter dB-Werte waere akustisch nicht das, was ein "Fast"-Schallpegel-
 * messer tut (IEC 61672 definiert die Zeitgewichtung als Einpol-Tiefpass auf der Leistung).
 *
 * Bleibt wie zuvor ausdruecklich UNKALIBRIERT (siehe README "Bekannte Einschraenkungen") - die
 * A-Bewertung und der Bezug auf einen realen Referenzpegel fehlen weiterhin, nur die ZEITLICHE
 * Mittelung folgt jetzt demselben Prinzip wie ein echtes Schallpegelmesser. Die dB-Skala
 * (`10*log10(leistung) + 100`) ist bewusst identisch mit der vorherigen Formel skaliert, damit
 * bestehende Schwellwerte (`db_threshold` u.ae.) ihre Bedeutung nicht stillschweigend aendern.
 *
 * Zustandsbehaftet (haelt die laufende Mittelung zwischen Aufrufen von [naechsterBlock]) -
 * [reset] bei jedem Start einer neuen Mikrofon-Ueberwachung aufrufen, sonst faerbt der Pegel der
 * letzten Sitzung in die neue hinein.
 */
class FastPegelSchaetzer {

    private var gewichteteLeistung = 0.0
    private var initialisiert = false

    /** Setzt die laufende Mittelung zurueck - vor jedem Start einer neuen Aufnahme aufrufen. */
    fun reset() {
        gewichteteLeistung = 0.0
        initialisiert = false
    }

    /**
     * Verarbeitet einen neuen Block PCM-16-Samples und liefert den aktualisierten,
     * Fast-zeitgewichteten Pegel in dB (siehe Klassenkommentar zur Skalierung).
     *
     * @param buffer PCM-16-Samples (little-endian bereits dekodiert)
     * @param readSize Anzahl gueltiger Samples in [buffer] - kann kleiner als dessen Groesse sein
     * @param abtastrate tatsaechlich verhandelte Abtastrate in Hz, bestimmt zusammen mit
     *   [readSize] die Blockdauer und damit den Gewichtungsfaktor fuer diesen Block
     */
    fun naechsterBlock(buffer: ShortArray, readSize: Int, abtastrate: Int): Double {
        if (readSize <= 0 || abtastrate <= 0) return dbAus(gewichteteLeistung)

        var summeQuadrate = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            summeQuadrate += sample * sample
        }
        val blockLeistung = (summeQuadrate / readSize) / VOLLAUSSTEUERUNG_QUADRAT

        gewichteteLeistung = if (!initialisiert) {
            // Beim allerersten Block seit reset() sofort uebernehmen statt sich von Null
            // ueber mehrere Zeitkonstanten heranzutasten - ein frisch gestartetes Monitoring
            // war nicht tatsaechlich still, es hat nur noch keinen Messwert.
            initialisiert = true
            blockLeistung
        } else {
            val blockdauerSekunden = readSize.toDouble() / abtastrate
            val alpha = 1.0 - Math.exp(-blockdauerSekunden / FAST_ZEITKONSTANTE_SEKUNDEN)
            alpha * blockLeistung + (1.0 - alpha) * gewichteteLeistung
        }

        return dbAus(gewichteteLeistung)
    }

    private fun dbAus(leistung: Double): Double {
        val db = if (leistung > 0) 10 * Math.log10(leistung) + 100.0 else 0.0
        return if (db < 0) 0.0 else db
    }

    private companion object {
        const val FAST_ZEITKONSTANTE_SEKUNDEN = 0.125
        const val VOLLAUSSTEUERUNG_QUADRAT = 32767.0 * 32767.0
    }
}
