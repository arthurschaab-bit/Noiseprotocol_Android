package com.example.lrmprotokoll.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ein einzelner Pegelwert, gepuffert bis zum naechsten erfolgreichen Drive-Sync-Zyklus
 * (Plan Abschnitt 8.4).
 *
 * Bewusst eine eigene, schlanke Tabelle und NICHT das vollstaendige Room-Schema aus Plan 8.1
 * (`SessionEntity`, `MeasurementEntity`): M7b wurde vor M4 umgesetzt (Owner-Entscheidung), und
 * der Plan selbst haelt fest, dass der Sync nicht am Bluetooth-Pfad haengt, sondern sich
 * vollstaendig mit den heutigen Werten bauen laesst. Diese Tabelle deckt nur, was der Sync
 * braucht - Sessions, Verbindungsereignisse und die vollen Report-Kennwerte (L10/L50/L90) bleiben
 * M4 vorbehalten. Wenn M4 kommt, sollte diese Tabelle in dessen Schema aufgehen; bis dahin
 * bestehen beide bewusst nebeneinander.
 *
 * [levelDb] ist niemals als dBA beschriftet: Weder das Mikrofon (unkalibriert, siehe README
 * "Bekannte Einschraenkungen") noch das PCE-323 (A/C-Bewertung unbestaetigt, siehe
 * [com.example.lrmprotokoll.meter.MeterFrame.modeAssumptionConfirmed]) liefert aktuell einen
 * gesicherten A-bewerteten Wert. Die CSV-Spalten heissen deshalb bewusst `LAeq_dB`, nicht
 * `LAeq_dBA`, obwohl Plan 8.4.2 dort `LAeq_dBA` als Beispiel zeigt - eine bewusste Abweichung.
 */
@Entity(tableName = "level_samples")
data class LevelSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,          // epoch millis
    val levelDb: Double,
    val source: String,    // LevelSource.name
)

object LevelSource {
    const val MIKROFON = "MIKROFON"
    const val PCE_323 = "PCE_323"
}
