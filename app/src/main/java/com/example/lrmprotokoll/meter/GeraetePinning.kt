package com.example.lrmprotokoll.meter

/**
 * Ergebnis der Pinning-Pruefung eines gescannten Advertisers gegen das aktuell gepinnte Geraet
 * (Plan Abschnitt 6). [VERDAECHTIG_GLEICHER_NAME] ist der eigentliche Sicherheitsfall: ein
 * fremdes Geraet, das denselben Namen wie das gepinnte traegt, aber eine andere Adresse - genau
 * das Muster, das ein untergeschobenes Geraet beim Namensspoofing erzeugen wuerde. Die
 * Verbindung selbst laeuft immer ueber die persistierte Adresse ([BleMeterTransport.connect]
 * verbindet nie ueber den Namen), dieser Befund betrifft ausschliesslich die Anzeige beim
 * (Neu-)Koppeln in [com.example.lrmprotokoll.ui.MeterScreen].
 */
enum class PinningBefund { NEUES_GERAET, GEPINNTES_GERAET, VERDAECHTIG_GLEICHER_NAME }

/**
 * Rein funktionale Pruefung ohne Android-Bezug, deshalb ohne Fake/Emulator testbar - wie
 * [MeterTriggerSource].
 */
object GeraetePinning {
    fun beurteile(
        gefundeneAdresse: String,
        gefundenerName: String?,
        gepinnteAdresse: String?,
        gepinnterName: String?,
    ): PinningBefund {
        if (gepinnteAdresse == null) return PinningBefund.NEUES_GERAET
        if (gefundeneAdresse == gepinnteAdresse) return PinningBefund.GEPINNTES_GERAET
        if (gefundenerName != null && gefundenerName == gepinnterName) {
            return PinningBefund.VERDAECHTIG_GLEICHER_NAME
        }
        return PinningBefund.NEUES_GERAET
    }
}
