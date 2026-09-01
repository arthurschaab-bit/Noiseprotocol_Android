package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.example.lrmprotokoll.data.KlassifikationsRohdatenDao
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
 *
 * KI-Umbau Etappe 1.4: [classifier] ist jetzt ein [RohdatenClassifier]-Fake statt eines
 * [SoundClassifier]-Fakes - die Funktion persistiert seither zusaetzlich Klassifikations-
 * Rohdaten je Aufnahme (siehe [KlassifikationsRohdatenDao]).
 */
class BatchKlassifizierungTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun rohbauplan(topKlassen: String = "") = RohdatenBauplan(
        modellVersion = "yamnet.tflite@test",
        klassifiziertAm = 1_700_000_000_000L,
        frameAnzahl = 1,
        frameDauerMs = 960,
        frameHopMs = 480,
        klassenIndizes = ROHDATEN_KLASSEN_INDIZES,
        frameScores = ByteArray(0),
        topKlassen = topKlassen,
    )

    private class FakeRohdatenClassifier(
        private val ergebnisJeDatei: Map<String, KlassifikationsErgebnis?>,
    ) : RohdatenClassifier {
        val angefragteDateien = mutableListOf<String>()
        override fun klassifiziereMitRohdaten(file: File): KlassifikationsErgebnis? {
            angefragteDateien += file.path
            return ergebnisJeDatei[file.path]
        }
    }

    private class FakeRohdatenDao : KlassifikationsRohdatenDao {
        val eingefuegt = mutableListOf<KlassifikationsRohdaten>()
        val geloeschtFuerRecord = mutableListOf<Long>()
        override suspend fun insert(rohdaten: KlassifikationsRohdaten): Long {
            eingefuegt += rohdaten
            return eingefuegt.size.toLong()
        }
        override suspend fun fuerRecord(recordId: Long): KlassifikationsRohdaten? =
            eingefuegt.lastOrNull { it.recordId == recordId }
        override suspend fun alle(): List<KlassifikationsRohdaten> = eingefuegt
        override suspend fun loescheFuerRecord(recordId: Long) {
            geloeschtFuerRecord += recordId
            eingefuegt.removeAll { it.recordId == recordId }
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
        override suspend fun insert(record: NoiseRecord): Long = 0
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
        override suspend fun setDetectedLabel(id: Long, label: String?) {}
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
        val rohdatenDao = FakeRohdatenDao()

        val anzahl = klassifiziereUndSpeichere(emptyList(), FakeRohdatenClassifier(emptyMap()), dao, rohdatenDao)

        assertEquals(0, anzahl)
        assertEquals(0, dao.aktualisiert.size)
        assertEquals(0, rohdatenDao.eingefuegt.size)
    }

    @Test
    fun erkannteDateiWirdMitDetectedLabelGespeichertUndGezaehlt() = runTest {
        val datei = tempFolder.newFile("hund.wav")
        val record = sampleRecord(1, datei.path)
        val dao = FakeNoiseDao()
        val rohdatenDao = FakeRohdatenDao()
        val classifier = FakeRohdatenClassifier(
            mapOf(datei.path to KlassifikationsErgebnis("Hundegebell", rohbauplan())),
        )

        val anzahl = klassifiziereUndSpeichere(listOf(record), classifier, dao, rohdatenDao)

        assertEquals(1, anzahl)
        assertEquals(1, dao.aktualisiert.size)
        assertEquals("Hundegebell", dao.aktualisiert.single().detectedLabel)
        assertEquals(record.id, dao.aktualisiert.single().id)
        assertEquals(
            "Rohdaten muessen unabhaengig vom Label persistiert werden, damit spaetere " +
                "Schwellenaenderungen ohne Neu-Inferenz auskommen",
            1, rohdatenDao.eingefuegt.size,
        )
        assertEquals(record.id, rohdatenDao.eingefuegt.single().recordId)
    }

    @Test
    fun classifierOhneLabelWirdNichtAlsGelabeltGezaehltAberRohdatenBleiben() = runTest {
        val datei = tempFolder.newFile("unklar.wav")
        val record = sampleRecord(1, datei.path)
        val dao = FakeNoiseDao()
        val rohdatenDao = FakeRohdatenDao()
        val classifier = FakeRohdatenClassifier(mapOf(datei.path to KlassifikationsErgebnis(null, rohbauplan())))

        val anzahl = klassifiziereUndSpeichere(listOf(record), classifier, dao, rohdatenDao)

        assertEquals(0, anzahl)
        assertEquals(0, dao.aktualisiert.size)
        assertEquals(
            "Auch ohne ableitbares Label sind die Rohscores wertvoll fuer eine spaetere " +
                "Neubewertung mit anderen Schwellen",
            1, rohdatenDao.eingefuegt.size,
        )
    }

    @Test
    fun ganzOhneErgebnisWirdWederLabelNochRohdatenGespeichert() = runTest {
        val datei = tempFolder.newFile("kaputt.wav")
        val record = sampleRecord(1, datei.path)
        val dao = FakeNoiseDao()
        val rohdatenDao = FakeRohdatenDao()
        val classifier = FakeRohdatenClassifier(mapOf(datei.path to null))

        val anzahl = klassifiziereUndSpeichere(listOf(record), classifier, dao, rohdatenDao)

        assertEquals(0, anzahl)
        assertEquals(0, dao.aktualisiert.size)
        assertEquals(0, rohdatenDao.eingefuegt.size)
    }

    @Test
    fun fehlendeDateiWirdUebersprungenOhneDenClassifierAufzurufen() = runTest {
        val record = sampleRecord(1, tempFolder.root.resolve("existiert-nicht.wav").path)
        val dao = FakeNoiseDao()
        val rohdatenDao = FakeRohdatenDao()
        val classifier = FakeRohdatenClassifier(emptyMap())

        val anzahl = klassifiziereUndSpeichere(listOf(record), classifier, dao, rohdatenDao)

        assertEquals(0, anzahl)
        assertEquals(
            "Eine fehlende Datei darf gar nicht erst an den Classifier durchgereicht werden - " +
                "spart unnoetige Arbeit, das Ergebnis waere ohnehin null",
            0, classifier.angefragteDateien.size,
        )
    }

    @Test
    fun erneuteKlassifizierungErsetztVorherigeRohdatenStattSieAnzuhaeufen() = runTest {
        val datei = tempFolder.newFile("nochmal.wav")
        val record = sampleRecord(1, datei.path)
        val dao = FakeNoiseDao()
        val rohdatenDao = FakeRohdatenDao()
        val classifier = FakeRohdatenClassifier(
            mapOf(datei.path to KlassifikationsErgebnis("Bohren", rohbauplan())),
        )

        klassifiziereUndSpeichere(listOf(record), classifier, dao, rohdatenDao)
        klassifiziereUndSpeichere(listOf(record), classifier, dao, rohdatenDao)

        assertEquals(
            "Ein zweiter Lauf ueber dieselbe Aufnahme darf keine zweite Zeile in " +
                "klassifikations_rohdaten hinterlassen",
            1, rohdatenDao.eingefuegt.size,
        )
        assertEquals(
            "loescheFuerRecord() wird bei jedem Lauf aufgerufen (auch beim ersten, wo es noch " +
                "nichts zu loeschen gibt) - das ist unschaedlich, siehe die Assertion oben",
            listOf(1L, 1L), rohdatenDao.geloeschtFuerRecord,
        )
    }

    @Test
    fun mehrereKandidatenWerdenGemischtVerarbeitetUndKorrektGezaehlt() = runTest {
        val erkannt = tempFolder.newFile("bohren.wav")
        val unerkannt = tempFolder.newFile("leise.wav")
        val fehlend = tempFolder.root.resolve("weg.wav")
        val dao = FakeNoiseDao()
        val rohdatenDao = FakeRohdatenDao()
        val classifier = FakeRohdatenClassifier(
            mapOf(
                erkannt.path to KlassifikationsErgebnis("Bohren", rohbauplan()),
                unerkannt.path to KlassifikationsErgebnis(null, rohbauplan()),
            ),
        )
        val kandidaten = listOf(
            sampleRecord(1, erkannt.path),
            sampleRecord(2, unerkannt.path),
            sampleRecord(3, fehlend.path),
        )

        val anzahl = klassifiziereUndSpeichere(kandidaten, classifier, dao, rohdatenDao)

        assertEquals(1, anzahl)
        assertEquals(1L, dao.aktualisiert.single().id)
        assertEquals("Bohren", dao.aktualisiert.single().detectedLabel)
        assertEquals(
            "Fuer beide vorhandenen Dateien (erkannt+unerkannt) muessen Rohdaten entstehen, " +
                "nur die fehlende Datei wird uebersprungen",
            2, rohdatenDao.eingefuegt.size,
        )
    }
}
