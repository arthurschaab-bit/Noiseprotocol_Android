package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.meter.MeterFrame

/**
 * Entscheidet, welche Quelle den Aufnahme-Trigger auslöst (Plan Abschnitt 4.5).
 *
 * "PCE-323 als Pegelwahrheit und Auslöser, das Mikrofon als Beweismittel und Klassifikator": Ist
 * ein Messgerät verbunden und liefert Werte, gilt dessen kalibrierter Pegel und dessen eigene
 * Schwelle - sonst fällt die Prüfung auf die bisherige Mikrofonberechnung zurück, unverändert
 * wie vor M4. Absichtlich rein und ohne jede Android-Abhängigkeit: Der Aufrufer
 * ([com.example.lrmprotokoll.audio.AudioRecordingService]) reicht nur Werte durch, die er
 * ohnehin schon hat.
 *
 * ⚠ Eine bisher eingestellte Mikrofon-Schwelle (Beispiel 60, "dBFS+100") ist NICHT dieselbe Zahl
 * wie 60 dBA vom Messgerät (Plan 4.5) - deshalb zwei getrennte Schwellenwerte, [mikrofonSchwelle]
 * und [meterSchwelle], nie ein gemeinsamer.
 */
object MeterTriggerSource {

    /** Ergebnis einer Auswertung - trägt bereits die drei Felder, die [NoiseRecord] zusätzlich
     * braucht, damit der Aufrufer sie nicht selbst aus [MeterFrame] herleiten muss. */
    data class Auswertung(
        val ausgeloest: Boolean,
        val pegel: Double,
        val calibratedDbA: Double?,
        val meterWeighting: String?,
        val meterConnected: Boolean,
    )

    /**
     * [letzterMeterFrame] ist `null`, wenn kein Messgerät gepinnt ist ODER noch kein Frame
     * eingetroffen ist ODER die Verbindung gerade nicht auf STREAMING steht - der Aufrufer
     * reicht in allen drei Fällen `null` durch und muss selbst nicht zwischen ihnen
     * unterscheiden, weil die Reaktion identisch ist: auf das Mikrofon zurückfallen.
     */
    fun auswerten(
        letzterMeterFrame: MeterFrame?,
        mikrofonDb: Double,
        mikrofonSchwelle: Float,
        meterSchwelle: Float,
    ): Auswertung {
        if (letzterMeterFrame != null) {
            return Auswertung(
                ausgeloest = letzterMeterFrame.level > meterSchwelle,
                pegel = letzterMeterFrame.level,
                calibratedDbA = letzterMeterFrame.level,
                // null, solange die A/C-Bewertung unbestaetigt ist - dieselbe Regel wie bei
                // MeasurementEntity.weighting, hier fuer NoiseRecord durchgesetzt.
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
