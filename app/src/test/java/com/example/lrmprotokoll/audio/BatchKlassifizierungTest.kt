package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.NoiseDao
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.ReferenceSound
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Testluecken-Auftrag Stufe 6 / MainActivity-Refactor: [klassifiziereUndSpeichere] war vorher
 * zweimal fast identisch inline in Compose-Callbacks in `NoiseProtocolApp()` (MainActivity.kt) -
 * einmal fuer den globalen "Alle klassifizieren"-Button, einmal fuer den Pro-Tag-Batch. Hier
 * direkt ohne Compose getestet.
 */
class BatchKlassifizierungTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeSoundClassifier(private val ergebnisJeDatei: Map<String, String?>) : SoundClassifier {
        val angefragteDateien = mutableListOf<String>()
        override fun classify(file: File): String? {
            angefragteDateien += file.path
            return ergebnisJeDatei[file.path]
        }
    }

    private class FakeNoiseDao : NoiseDao {
        val aktualisiert = mutableListOf<NoiseRecord>()
        override fun getAll(): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun getAlleAktiven(): List<NoiseRecord> = emptyList()
        override fun getTrash(): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun zwischenZeitpunkt(von: Long, bis: Long): List<NoiseRecord> = emptyList()
        override fun zwischenZeitpunktFlow(von: Long, bis: Long): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override fun abZeitpunktFlow(von: Long): Flow<List<NoiseRecord>> = flowOf(emptyList())
        override suspend fun insert(record: NoiseRecord) {}
        override suspend fun update(record: NoiseRecord) { aktualisiert += record }
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
        override fun getAllReferences(): Flow<List<ReferenceSound>> = flowOf(emptyList())
        override suspend fun insertReference(sound: ReferenceSound) {}
        override suspend fun deleteReference(id: Long) {}
    }

    private fun sampleRecord(id: Long, filePath: String) = NoiseRecord(
        id = id, timestamp = 1_700_000_000_000L, amplitude = 1000.0, dbValue = 50.0, filePath = filePath,
    )

    @Test
    fun leereKandidatenlisteErgibtNullKlassifizierungenUndKeinenDaoAufruf() = runTest {
        val dao = FakeNoiseDao()

        val anzahl = klassifiziereUndSpeichere(emptyList(), FakeSoundClassifier(emptyMap()), dao)

        assertEquals(0, anzahl)
        assertEquals(0, dao.aktualisiert.size)
    }

    @Test
    fun erkannteDateiWirdMitDetectedLabelGespeichertUndGezaehlt() = runTest {
        val datei = tempFolder.newFile("hund.wav")
        val record = sampleRecord(1, datei.path)
        val dao = FakeNoiseDao()
        val classifier = FakeSoundClassifier(mapOf(datei.path to "Hundegebell"))

        val anzahl = klassifiziereUndSpeichere(listOf(record), classifier, dao)

        assertEquals(1, anzahl)
        assertEquals(1, dao.aktualisiert.size)
        assertEquals("Hundegebell", dao.aktualisiert.single().detectedLabel)
        assertEquals(record.id, dao.aktualisiert.single().id)
    }

    @Test
    fun classifierOhneErgebnisWirdNichtGespeichertUndNichtGezaehlt() = runTest {
        val datei = tempFolder.newFile("unklar.wav")
        val record = sampleRecord(1, datei.path)
        val dao = FakeNoiseDao()
        val classifier = FakeSoundClassifier(mapOf(datei.path to null))

        val anzahl = klassifiziereUndSpeichere(listOf(record), classifier, dao)

        assertEquals(0, anzahl)
        assertEquals(0, dao.aktualisiert.size)
    }

    @Test
    fun fehlendeDateiWirdUebersprungenOhneDenClassifierAufzurufen() = runTest {
        val record = sampleRecord(1, tempFolder.root.resolve("existiert-nicht.wav").path)
        val dao = FakeNoiseDao()
        val classifier = FakeSoundClassifier(emptyMap())

        val anzahl = klassifiziereUndSpeichere(listOf(record), classifier, dao)

        assertEquals(0, anzahl)
        assertEquals(
            "Eine fehlende Datei darf gar nicht erst an den Classifier durchgereicht werden - " +
                "spart unnoetige Arbeit, das Ergebnis waere ohnehin null",
            0, classifier.angefragteDateien.size,
        )
    }

    @Test
    fun mehrereKandidatenWerdenGemischtVerarbeitetUndKorrektGezaehlt() = runTest {
        val erkannt = tempFolder.newFile("bohren.wav")
        val unerkannt = tempFolder.newFile("leise.wav")
        val fehlend = tempFolder.root.resolve("weg.wav")
        val dao = FakeNoiseDao()
        val classifier = FakeSoundClassifier(
            mapOf(erkannt.path to "Bohren", unerkannt.path to null),
        )
        val kandidaten = listOf(
            sampleRecord(1, erkannt.path),
            sampleRecord(2, unerkannt.path),
            sampleRecord(3, fehlend.path),
        )

        val anzahl = klassifiziereUndSpeichere(kandidaten, classifier, dao)

        assertEquals(1, anzahl)
        assertEquals(1L, dao.aktualisiert.single().id)
        assertEquals("Bohren", dao.aktualisiert.single().detectedLabel)
    }
}
