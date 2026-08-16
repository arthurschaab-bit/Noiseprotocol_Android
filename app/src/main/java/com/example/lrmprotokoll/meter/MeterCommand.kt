package com.example.lrmprotokoll.meter

/**
 * Kommandosatz des PCE-323 (Plan Abschnitt 2.2), 16 Bit big endian. MemoryTransfer traegt
 * zusaetzlich die 16-Bit-Blockadresse, alle anderen Kommandos sind parameterlos.
 */
sealed class MeterCommand(val code: Int) {
    object Connect : MeterCommand(0xACFF)
    object Disconnect : MeterCommand(0xCAFF)
    object ToggleWeightFrequency : MeterCommand(0xAAF1)
    object ToggleMeasurementRange : MeterCommand(0xAAF2)
    object ToggleHoldMaxMin : MeterCommand(0xAAF3)
    object ToggleWeightTime : MeterCommand(0xAAF4)
    object ToggleHold : MeterCommand(0xAAF5)
    object ToggleBacklight : MeterCommand(0xAAF6)
    object ToggleDateTime : MeterCommand(0xAAF7)
    object PowerOff : MeterCommand(0xAAF8)
    object LogStart : MeterCommand(0x7E00)
    object MemoryStatus : MeterCommand(0xADDA)
    data class MemoryTransfer(val blockAddress: Int) : MeterCommand(0xD3DA)
    object MemoryClear : MeterCommand(0xAAC1)
}
