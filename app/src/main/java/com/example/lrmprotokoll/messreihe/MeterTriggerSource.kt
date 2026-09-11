package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.meter.MeterFrame

/**
 * Entscheidet, welche Quelle den Aufnahme-Trigger auslöst (Plan Abschnitt 4.5 & User-Option).
 *
 * Kann auf "AUTO", "PCE_323" oder "MIKROFON" konfiguriert werden. [mikrofonSchwelle] und
 * [meterSchwelle] sind bewusst GETRENNT (Prüfprotokoll-Befund 02 / Korrekturliste C-3,
 * Owner-Entscheidung vom 11.09.2026): "60" bedeutet auf dem unkalibrierten Mikrofonwert (dBFS +
 * Offset) und auf dem kalibrierten dBA-Wert des Messgeräts nichts Vergleichbares - vor dieser
 * Korrektur gab es nur EINEN Schwellwert, der unverändert an beide Quellen durchgereicht wurde.
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
     * Wertet Pegel und Schwellenwert anhand der konfigurierten Trigger-Quelle aus - [mikrofonDb]
     * gegen [mikrofonSchwelle], ein [letzterMeterFrame] gegen [meterSchwelle].
     */
    fun auswerten(
        letzterMeterFrame: MeterFrame?,
        mikrofonDb: Double,
        mikrofonSchwelle: Float,
        meterSchwelle: Float,
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
                ausgeloest = letzterMeterFrame.level > meterSchwelle,
                pegel = letzterMeterFrame.level,
                calibratedDbA = letzterMeterFrame.level,
                meterWeighting = letzterMeterFrame.weighting
                    ?.takeIf { letzterMeterFrame.modeAssumptionConfirmed }?.name,
                meterConnected = true,
            )
        }

        if (triggerQuelle == "MIKROFON") {
            return Auswertung(
                ausgeloest = mikrofonDb > mikrofonSchwelle,
                pegel = mikrofonDb,
                calibratedDbA = null,
                meterWeighting = null,
                meterConnected = false,
            )
        }

        // "AUTO"
        if (letzterMeterFrame != null) {
            return Auswertung(
                ausgeloest = letzterMeterFrame.level > meterSchwelle,
                pegel = letzterMeterFrame.level,
                calibratedDbA = letzterMeterFrame.level,
                meterWeighting = letzterMeterFrame.weighting
                    ?.takeIf { letzterMeterFrame.modeAssumptionConfirmed }?.name,
                meterConnected = true,
            )
        }

        return Auswertung(
            ausgeloest = mikrofonDb > mikrofonSchwelle,
            pegel = mikrofonDb,
            calibratedDbA = null,
            meterWeighting = null,
            meterConnected = false,
        )
    }
}
