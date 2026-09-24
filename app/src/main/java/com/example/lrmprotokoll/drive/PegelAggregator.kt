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
     *
     * Alle Fenstergrenzen - die Bereichsgrenzen `effektiverStartMillis`/`effektivesEndeMillis`
     * GENAUSO wie die Gruppierung der Rohwerte - liegen auf demselben Raster: Vielfachen von
     * [fensterDauer] AB [von] (`von + k*fensterMillis`, `k` ganzzahlig), NICHT auf dem absoluten
     * Millisekunden-Raster ab Epoch 0 (Nachbesserung 24.09.2026). Das ist entscheidend fuer
     * [com.example.lrmprotokoll.drive.DriveSyncCoordinator.aggregiereInAbschnitten]: dessen
     * Abschnittsgrenzen sind zwar untereinander alle Vielfache von [fensterDauer] AB dem
     * gemeinsamen, urspruenglichen [von] entfernt - nicht aber notwendigerweise vom absoluten
     * Epoch-Raster aus, wenn [von] selbst nicht rasteraligniert ist
     * (`von.toEpochMilli() % fensterDauer.toMillis()` muss NICHT 0 sein). Ein Aufruf mit
     * `[von]=abschnittVon` liegt dadurch IMMER auf demselben Raster wie jeder andere Aufruf mit
     * `[von]=irgendein anderes abschnittVon` DESSELBEN Gesamtaufrufs - unabhaengig davon, ob der
     * jeweilige Abschnitt seine eigenen Rohwerte dicht ab seinem eigenen `abschnittVon` hat oder
     * erst spaeter. Nur so kann das Zusammenfuegen mehrerer Aufrufe (`aggregiereInAbschnitten`s
     * Luecken-Stitching) je zwei benachbarte Abschnitte exakt auf der Fenstergrenze treffen, statt
     * ausserhalb des Rasters knapp daneben zu landen und dadurch eine zusaetzliche Zeile
     * einzufuegen.
     *
     * Vor der Nachbesserung berechnete `effektiverStartMillis`/`effektivesEndeMillis` ihren
     * datengetriebenen Anteil auf dem ABSOLUTEN Epoch-Raster
     * (`floor(minTs/fensterMillis)*fensterMillis`), waehrend die Gruppierung der Rohwerte schon
     * damals relativ zu [von] arbeitete - beides fiel nur zusammen, wenn [von] selbst
     * rasteraligniert war. Sonst driftete das Bezugssystem auseinander: Rohwerte landeten unter
     * einem falschen `fensterStart` oder verschwanden komplett aus der Ausgabe (siehe
     * [com.example.lrmprotokoll.drive.PegelAggregatorTest], Testfall "Rasterbeginn
     * datengetrieben"). Zwei Zwischenfassungen dieser Nachbesserung versuchten stattdessen, NUR
     * die Gruppierung zu reparieren (zuerst auf das absolute Epoch-Raster, dann relativ zu
     * `effektiverStartMillis`) - beide behoben den EINEN Aufruf fuer sich, aber nicht das
     * Zusammenspiel MEHRERER Aufrufe mit unterschiedlichem `abschnittVon` in
     * `aggregiereInAbschnitten`, weil `effektiverStartMillis` je nach Datenlage weiterhin
     * zwischen "absolut rasteraligniert" (Rasterbeginn datengetrieben) und "auf [von] aligniert"
     * (Rasterbeginn durch [von] gebunden) wechselte - siehe
     * [com.example.lrmprotokoll.drive.PegelAggregatorTest], Testfall "Rasterbeginn durch von
     * gebunden", fuer den dabei uebersehenen Fall.
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

        // Auf Fenster ausrichten und auf [von, bis] begrenzen - VON-RELATIV (Nachbesserung
        // 24.09.2026, siehe KDoc oben), nicht auf dem absoluten Epoch-Raster: startIndex/
        // endIndexExklusiv sind die von-relativen Fenster-Indizes von minTs/maxTs, dieselben
        // Indizes, unter denen die Rohwerte unten gruppiert werden.
        // coerceAtLeast(0): dieselbe Absicherung, die vorher das `maxOf(vonMillis, ...)` an
        // effektiverStartMillis leistete - Rohwerte VOR [von] (sollte bei den bestehenden
        // Aufrufern nie vorkommen, siehe deren eigene [von,bis)-Datenbankabfragen) duerfen
        // effektiverStartMillis nicht unter [von] druecken.
        val startIndex = (minTs - vonMillis).floorDiv(fensterMillis).coerceAtLeast(0)
        val endIndexExklusiv = (maxTs - vonMillis).floorDiv(fensterMillis) + 1
        val effektiverStartMillis = vonMillis + startIndex * fensterMillis
        val effektivesEndeMillis = minOf(bis.toEpochMilli(), vonMillis + endIndexExklusiv * fensterMillis)

        val samplesNachFenster = samples.groupBy { (it.at - vonMillis).floorDiv(fensterMillis) }
        val ereignisseNachFenster = ereignisse.groupBy { (it.at.toEpochMilli() - vonMillis).floorDiv(fensterMillis) }

        val zeilen = mutableListOf<AggregatZeile>()
        var fensterStart = Instant.ofEpochMilli(effektiverStartMillis)
        val fensterEnde = Instant.ofEpochMilli(effektivesEndeMillis)
        var index = startIndex

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
