package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.LevelSampleEntity
import com.example.lrmprotokoll.data.LevelSource
import java.time.Duration
import java.time.Instant
import kotlin.math.log10
import kotlin.math.pow

/** Eine verdichtete Zeile der Drive-CSV mit Rohwerten und verarbeiteten Kennwerten. */
data class AggregatZeile(
    val fensterStart: Instant,
    val pegelDb: Double? = null,
    val laeqDb: Double? = null,
    val lafMaxDb: Double? = null,
    val lafMinDb: Double? = null,
    val bewertung: String? = null,
    val zeitbewertung: String? = null,
    val messbereich: String? = null,
    val samples: Int = 0,
    val quelle: String = QUELLE_KEINE_VERBINDUNG,
    val ereignis: Boolean = false,
    val klassifikation: String? = null,
    val notes: String? = null,
)

/** Minimale Sicht auf ein [com.example.lrmprotokoll.data.NoiseRecord] fuer den Abgleich -
 * entkoppelt den Aggregator bewusst von der vollen Entity. */
data class ProtokollEreignis(
    val at: Instant,
    val pegelDb: Double? = null,
    val klassifikation: String? = null,
    val notes: String? = null,
    val weighting: String? = null,
)

const val QUELLE_GEMISCHT = "GEMISCHT"
const val QUELLE_KEINE_VERBINDUNG = "KEINE_VERBINDUNG"

/**
 * Verdichtet Rohpegelwerte zu Zeitfenstern fuer den Drive-Sync (Plan Abschnitt 8.4). Rein,
 * keine Seiteneffekte - deshalb ohne Mocks direkt testbar.
 */
object PegelAggregator {

    /**
     * Bildet Fenster der Laenge [fensterDauer] zwischen [von] (inklusiv) und [bis] (exklusiv).
     *
     * Fenster ohne Samples werden nicht ausgelassen, sondern als [QUELLE_KEINE_VERBINDUNG]-Zeile
     * ausgegeben (Plan 8.4.2): eine Messreihe, in der Ausfaelle einfach fehlen, ist forensisch
     * wertlos - dasselbe Argument wie bei `ConnectionEventEntity` in Plan 8.1.
     *
     * `laeqDb` ist der energetische Mittelwert, NICHT das arithmetische Mittel der dB-Werte -
     * das waere ein klassischer und im Protokollkontext gravierender Fehler (Plan 8.3).
     */
    fun aggregiere(
        samples: List<LevelSampleEntity>,
        ereignisse: List<ProtokollEreignis>,
        von: Instant,
        bis: Instant,
        fensterDauer: Duration,
    ): List<AggregatZeile> {
        require(fensterDauer > Duration.ZERO) { "fensterDauer muss positiv sein" }
        require(!bis.isBefore(von)) { "bis darf nicht vor von liegen" }

        if (samples.isEmpty() && ereignisse.isEmpty()) {
            return emptyList()
        }

        val vonMillis = von.toEpochMilli()
        val fensterMillis = fensterDauer.toMillis()

        val minTs = minOf(
            samples.minOfOrNull { it.at } ?: Long.MAX_VALUE,
            ereignisse.minOfOrNull { it.at.toEpochMilli() } ?: Long.MAX_VALUE
        )
        val maxTs = maxOf(
            samples.maxOfOrNull { it.at } ?: Long.MIN_VALUE,
            ereignisse.maxOfOrNull { it.at.toEpochMilli() } ?: Long.MIN_VALUE
        )

        // Auf Fenster ausrichten und auf [von, bis] begrenzen
        val effektiverStartMillis = maxOf(vonMillis, (minTs / fensterMillis) * fensterMillis)
        val effektivesEndeMillis = minOf(bis.toEpochMilli(), ((maxTs / fensterMillis) + 1) * fensterMillis)

        val samplesNachFenster = samples.groupBy { (it.at - vonMillis).floorDiv(fensterMillis) }
        val ereignisseNachFenster = ereignisse.groupBy {
            (it.at.toEpochMilli() - vonMillis).floorDiv(fensterMillis)
        }

        val zeilen = mutableListOf<AggregatZeile>()
        var fensterStart = Instant.ofEpochMilli(effektiverStartMillis)
        val fensterEnde = Instant.ofEpochMilli(effektivesEndeMillis)
        var index = (effektiverStartMillis - vonMillis) / fensterMillis

        while (fensterStart.isBefore(fensterEnde)) {
            val inDiesemFenster = samplesNachFenster[index].orEmpty()
            val ereignisseHier = ereignisseNachFenster[index].orEmpty()
            zeilen += bildeZeile(inDiesemFenster, ereignisseHier, fensterStart)
            fensterStart = fensterStart.plusMillis(fensterMillis)
            index++
        }
        return zeilen
    }

    private fun bildeZeile(
        samples: List<LevelSampleEntity>,
        ereignisse: List<ProtokollEreignis>,
        fensterStart: Instant,
    ): AggregatZeile {
        if (samples.isEmpty()) {
            val erstEreignis = ereignisse.firstOrNull()
            return AggregatZeile(
                fensterStart = fensterStart,
                pegelDb = erstEreignis?.pegelDb,
                laeqDb = null,
                lafMaxDb = null,
                lafMinDb = null,
                bewertung = erstEreignis?.weighting,
                zeitbewertung = null,
                messbereich = null,
                samples = 0,
                quelle = QUELLE_KEINE_VERBINDUNG,
                ereignis = ereignisse.isNotEmpty(),
                klassifikation = erstEreignis?.klassifikation,
                notes = erstEreignis?.notes,
            )
        }

        val pegel = samples.map { it.levelDb }
        val energetischerMittelwert = 10.0 * log10(pegel.sumOf { 10.0.pow(it / 10.0) } / pegel.size)
        val quellen = samples.map { it.source }.toSet()
        val erstEreignis = ereignisse.firstOrNull()
        val quelleStr = if (quellen.size == 1) quellen.first() else QUELLE_GEMISCHT

        return AggregatZeile(
            fensterStart = fensterStart,
            pegelDb = samples.lastOrNull()?.levelDb,
            laeqDb = energetischerMittelwert,
            lafMaxDb = pegel.max(),
            lafMinDb = pegel.min(),
            bewertung = erstEreignis?.weighting ?: (if (quelleStr == LevelSource.PCE_323) "A" else null),
            zeitbewertung = if (quelleStr == LevelSource.PCE_323) "FAST" else null,
            messbereich = if (quelleStr == LevelSource.PCE_323) "AUTO" else null,
            samples = samples.size,
            quelle = quelleStr,
            ereignis = ereignisse.isNotEmpty(),
            klassifikation = erstEreignis?.klassifikation,
            notes = erstEreignis?.notes,
        )
    }
}
