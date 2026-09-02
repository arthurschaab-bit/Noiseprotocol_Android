package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.example.lrmprotokoll.data.KlassifikationsRohdatenDao
import com.example.lrmprotokoll.data.NoiseDao
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.ReferenceSound
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KI-Umbau Etappe 1.5, "Neu bewerten": [bewerteAlleNeu] darf keine WAV-Datei lesen und keine
 * Inferenz starten - hier ueber Fakes verifiziert, dass ausschliesslich [leiteLabelAb] auf den
 * gespeicherten Rohdaten aufgerufen wird.
 */
class NeuBewertenTest {

    private class FakeRohdatenDao(private val bestand: List<KlassifikationsRohdaten>) : KlassifikationsRohdatenDao {
        override suspend fun insert(rohdaten: KlassifikationsRohdaten): Long = 0
        override suspend fun fuerRecord(recordId: Long): KlassifikationsRohdaten? = bestand.firstOrNull { it.recordId == recordId }
        override suspend fun alle(): List<KlassifikationsRohdaten> = bestand
        override suspend fun loescheFuerRecord(recordId: Long) {}
    }

    private class FakeNoiseDao : NoiseDao {
        val gesetzteLabels = mutableMapOf<Long, String?>()
        override fun getAll(): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun getAlleAktiven(): List<NoiseRecord> = emptyList()
        override fun getTrash(): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun zwischenZeitpunkt(von: Long, bis: Long): List<NoiseRecord> = emptyList()
        override fun zwischenZeitpunktFlow(von: Long, bis: Long): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override fun abZeitpunktFlow(von: Long): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun insert(record: NoiseRecord): Long = 0
        override suspend fun update(record: NoiseRecord) {}
        override suspend fun softDelete(id: Long, deletedAt: Long) {}
        override suspend fun softDeleteMultiple(ids: List<Long>, deletedAt: Long) {}
        override suspend fun restore(id: Long) {}
        override suspend fun restoreMultiple(ids: List<Long>) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteMultiple(ids: List<Long>) {}
        override suspend fun deleteTrashAelterAls(cutoff: Long): Int = 0
        override suspend fun getTrashAelterAls(cutoff: Long): List<NoiseRecord> = emptyList()
        override suspend fun getAutoRetentionCandidates(cutoff: Long): List<NoiseRecord> = emptyList()
        override suspend fun setFavorite(id: Long, isFavorite: Boolean) {}
        override suspend fun setNotes(id: Long, notes: String?) {}
        override suspend fun setDetectedLabel(id: Long, label: String?) { gesetzteLabels[id] = label }
        override fun getAllReferences(): Flow<List<ReferenceSound>> = flowOf(emptyList())
        override suspend fun insertReference(sound: ReferenceSound) {}
        override suspend fun deleteReference(id: Long) {}
    }

    private fun rohdatenMit(recordId: Long, kandidaten: List<NoiseClassifier.ScoredCategory>) = KlassifikationsRohdaten(
        recordId = recordId,
        modellVersion = "yamnet.tflite@test",
        klassifiziertAm = 1_700_000_000_000L,
        frameAnzahl = kandidaten.size,
        frameDauerMs = 960,
        frameHopMs = 480,
        klassenIndizes = ROHDATEN_KLASSEN_INDIZES,
        frameScores = ByteArray(0),
        topKlassen = kodiereTopKlassen(kandidaten),
    )

    private val konfiguration = AbleitungsKonfiguration(
        referenzMuster = emptyList(), labelMapping = mapOf("Hammer" to "Hämmern"),
    )

    @Test
    fun leererBestandErgibtNullBewerteteAufnahmen() = runTest {
        val anzahl = bewerteAlleNeu(FakeNoiseDao(), FakeRohdatenDao(emptyList()), konfiguration)

        assertEquals(0, anzahl)
    }

    @Test
    fun jedeGespeicherteRohdatenzeileWirdNeuBewertetUndDasLabelGesetzt() = runTest {
        val rohdaten1 = rohdatenMit(1, listOf(NoiseClassifier.ScoredCategory("Hammer", 0.42f)))
        val rohdaten2 = rohdatenMit(2, listOf(NoiseClassifier.ScoredCategory("Drill", 0.30f)))
        val noiseDao = FakeNoiseDao()

        val anzahl = bewerteAlleNeu(noiseDao, FakeRohdatenDao(listOf(rohdaten1, rohdaten2)), konfiguration)

        assertEquals(2, anzahl)
        assertEquals("Hämmern (42%)", noiseDao.gesetzteLabels[1])
        assertEquals(
            "Drill hat kein Mapping in dieser Konfiguration - bleibt beim Originalnamen",
            "Drill (30%)", noiseDao.gesetzteLabels[2],
        )
    }

    @Test
    fun keineKandidatenUeberDerSchwelleSetztDasLabelAufNull() = runTest {
        val rohdaten = rohdatenMit(1, listOf(NoiseClassifier.ScoredCategory("Silence", 0.9f)))
        val noiseDao = FakeNoiseDao()

        bewerteAlleNeu(noiseDao, FakeRohdatenDao(listOf(rohdaten)), konfiguration)

        assertEquals(
            "Ein frueher als Baulaerm erkannter Clip darf nach einer Neubewertung explizit " +
                "auf null zurueckgesetzt werden - das ist der ganze Sinn von 'Neu bewerten'",
            null, noiseDao.gesetzteLabels[1],
        )
        assertEquals(true, noiseDao.gesetzteLabels.containsKey(1L))
    }
}
