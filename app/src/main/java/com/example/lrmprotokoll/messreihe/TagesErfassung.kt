package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.report.lokalerMesstag
import com.example.lrmprotokoll.report.messtagGrenzen
import java.time.ZoneId

/**
 * Was fuer den lokalen Messtag schon erfasst ist - Grundlage dafuer, dass Konfiguration und
 * Fotos nicht bei jedem Messstart erneut abgefragt werden (Owner-Auftrag 12.11.2026: "nicht
 * immer wieder als Popup, ausser man hat etwas geaendert").
 *
 * Gezaehlt wird ueber alle Messvorgaenge des Tages: Ein Wechsel vom Mikrofon auf das Messgeraet
 * oder ein Neustart am selben Tag macht die Angaben nicht ungueltig.
 */
data class TagesErfassung(val konfigurationErfasst: Boolean, val fotoAnzahl: Int) {
    /** `true`, wenn fuer alle benoetigten Bereiche schon etwas vorliegt. */
    fun vollstaendig(konfigurationNoetig: Boolean, fotosNoetig: Boolean): Boolean =
        (!konfigurationNoetig || konfigurationErfasst) && (!fotosNoetig || fotoAnzahl > 0)

    companion object {
        suspend fun fuerTag(db: AppDatabase, jetzt: Long, zone: ZoneId = ZoneId.systemDefault()): TagesErfassung {
            val (von, bis) = messtagGrenzen(lokalerMesstag(jetzt, zone), zone)
            // giltFuerTagStart != null sind Nachtraege fuer andere Tage und gelten nicht als "heute".
            val konfiguration = db.stammdatenVerlaufDao().fuerTag(von, bis).any { it.giltFuerTagStart == null }
            return TagesErfassung(konfiguration, db.dokumentationsFotoDao().anzahlZwischen(von, bis))
        }
    }
}
