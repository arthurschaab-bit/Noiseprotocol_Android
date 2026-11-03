package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.data.SettingsManager

const val EREIGNIS_DIENST_AUSDRUECKLICH_GESTOPPT = "Dienst ausdrücklich gestoppt"

/** Liest das Vorher-Bild vor dem ersten neuen Dienststart und protokolliert jede Luecke einmal. */
class AusfallErfassung(
    private val datei: LetzterZustandDatei,
    private val ringFile: LebenszyklusRingFile,
    private val settings: SettingsManager,
) {
    fun beimProzessstart(
        letzteDatenAt: Long?,
        uptimeJetztMs: Long,
        bootCountJetzt: Int?,
        jetztMs: Long,
    ): AusfallEinordnung? {
        val zustand = datei.lesen()
        val stopp =
            ringFile.lesen().any {
                it.ereignis == EREIGNIS_DIENST_AUSDRUECKLICH_GESTOPPT &&
                    it.timestampMillis > (letzteDatenAt ?: Long.MAX_VALUE) &&
                    it.timestampMillis <= jetztMs
            }
        val ergebnis =
            AusfallKlassifikator.klassifizieren(
                letzterZustand = zustand,
                letzteDatenAt = letzteDatenAt,
                waechterLetzterLaufAt = settings.waechterLetzterLaufAt.takeIf { it > 0L },
                uptimeJetztMs = uptimeJetztMs,
                bootCountJetzt = bootCountJetzt,
                jetztMs = jetztMs,
                ausdruecklicherStoppProtokolliert = stopp,
            ) ?: return null

        if (settings.ausfallLetzteDatenAt != ergebnis.letzteDatenAt) {
            ringFile.protokolliere("Ausfall eingeordnet", ergebnis.details(), jetztMs)
            settings.ausfallLetzteDatenAt = ergebnis.letzteDatenAt
            settings.ausfallKlassifikation = ergebnis.klasse.name
            settings.ausfallWaechterLief = ergebnis.waechterLief.name.lowercase()
        }
        return ergebnis
    }
}
