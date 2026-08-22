package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.meter.MeterFrame

/**
 * Entscheidet, welche Quelle den Aufnahme-Trigger auslöst (Plan Abschnitt 4.5 & User-Option).
 *
 * Kann auf "AUTO", "PCE_323" oder "MIKROFON" konfiguriert werden.
 * Bei Überschreiten des [activeSchwelle]-Grenzwerts durch die ausgewählte Quelle wird die
 * Audio-Aufnahme ausgelöst.
 */
object MeterTriggerSource {

    /** Ergebnis einer Auswertung - trägt die Felder, die [NoiseRecord] zusätzlich braucht. */
    data class Auswertung(
        val ausgeloest: Boolean,
        val pegel: Double,
        val calibratedDbA: Double?,
        val meterWeighting: String?,
        val meterConnected: Boolean,
    )

    /**
     * Wertet Pegel und Schwellenwert anhand der konfigurierten Trigger-Quelle aus.
     */
    fun auswerten(
        letzterMeterFrame: MeterFrame?,
        mikrofonDb: Double,
        activeSchwelle: Float,
        triggerQuelle: String = "AUTO",
    ): Auswertung {
        if (triggerQuelle == "PCE_323") {
            if (letzterMeterFrame == null) {
                return Auswertung(
                    ausgeloest = false,
                    pegel = 0.0,
                    calibratedDbA = null,
                    meterWeighting = null,
                    meterConnected = false,
                )
            }
            return Auswertung(
                ausgeloest = letzterMeterFrame.level > activeSchwelle,
                pegel = letzterMeterFrame.level,
                calibratedDbA = letzterMeterFrame.level,
                meterWeighting = letzterMeterFrame.weighting
                    ?.takeIf { letzterMeterFrame.modeAssumptionConfirmed }?.name,
                meterConnected = true,
            )
        }

        if (triggerQuelle == "MIKROFON") {
            return Auswertung(
                ausgeloest = mikrofonDb > activeSchwelle,
                pegel = mikrofonDb,
                calibratedDbA = null,
                meterWeighting = null,
                meterConnected = false,
            )
        }

        // "AUTO"
        if (letzterMeterFrame != null) {
            return Auswertung(
                ausgeloest = letzterMeterFrame.level > activeSchwelle,
                pegel = letzterMeterFrame.level,
                calibratedDbA = letzterMeterFrame.level,
                meterWeighting = letzterMeterFrame.weighting
                    ?.takeIf { letzterMeterFrame.modeAssumptionConfirmed }?.name,
                meterConnected = true,
            )
        }

        return Auswertung(
            ausgeloest = mikrofonDb > activeSchwelle,
            pegel = mikrofonDb,
            calibratedDbA = null,
            meterWeighting = null,
            meterConnected = false,
        )
    }
}
