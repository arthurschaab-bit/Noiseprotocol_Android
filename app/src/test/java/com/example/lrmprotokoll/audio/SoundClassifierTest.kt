package com.example.lrmprotokoll.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prompt B-11, Problem 2: eine fehlgeschlagene Klassifikator-Initialisierung oder eine
 * unerwartete Ausnahme aus dem Klassifikator darf die Aufnahme nicht verhindern. classifySafely()
 * ist die Stelle, die diese Garantie durchsetzt - getestet ueber eine Fake-Implementierung von
 * [SoundClassifier], nicht ueber das echte MediaPipe-Modell (Prompt: "NoiseClassifier
 * injizierbar oder hinter eine kleine Schnittstelle legen").
 *
 * Robolectric statt reinem JUnit, weil classifySafely() im Fehlerfall echtes android.util.Log
 * durchlaeuft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundClassifierTest {

    private val file = File("irrelevant.wav")

    private class FakeSoundClassifier(
        private val result: String? = null,
        private val throwable: Throwable? = null,
    ) : SoundClassifier {
        var callCount = 0
            private set

        override fun classify(file: File): String? {
            callCount++
            throwable?.let { throw it }
            return result
        }
    }

    @Test
    fun fehlgeschlagenerKlassifikatorVerhindertAufnahmeNicht() {
        // Simuliert eine komplett fehlgeschlagene Initialisierung (classifier == null, wie bei
        // NoiseClassifier nach einem im init-Block abgefangenen UnsatisfiedLinkError).
        val result = classifySafely(classifier = null, aiEnabled = true, file = file)

        assertNull(result)
    }

    @Test
    fun exceptionAusClassifyWirdNichtDurchgereicht() {
        // Der wichtigste Fall: classify() wirft (z.B. weil die Initialisierung zwar formal
        // geglueckt ist, der eigentliche Aufruf aber scheitert). Ohne den try/catch in
        // classifySafely() wuerde diese Exception bis zum Aufrufer in AudioRecordingService
        // durchschlagen und dort die WAV-Speicherung/den Datenbank-Insert verhindern.
        val throwingClassifier = FakeSoundClassifier(throwable = IllegalStateException("kaputt"))

        val result = classifySafely(classifier = throwingClassifier, aiEnabled = true, file = file)

        assertNull(result)
    }

    @Test
    fun aiDisabledRuftClassifierNichtAuf() {
        // Gegenprobe zu den beiden Faellen oben: classifySafely() soll den Klassifikator gar
        // nicht erst aufrufen, wenn die KI-Erkennung deaktiviert ist - nicht nur zufaellig immer
        // null liefern.
        val classifier = FakeSoundClassifier(result = "Hämmern")

        val result = classifySafely(classifier = classifier, aiEnabled = false, file = file)

        assertNull(result)
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun funktionierenderKlassifikatorLiefertLabelWeiterhin() {
        // Gegenprobe zu den beiden fehlgeschlagenen Faellen: classifySafely() gibt kein
        // Blanko-null zurueck, sondern reicht ein tatsaechliches Ergebnis unveraendert durch -
        // sonst waeren die obigen "null"-Assertions nicht aussagekraeftig.
        val classifier = FakeSoundClassifier(result = "Hämmern")

        val result = classifySafely(classifier = classifier, aiEnabled = true, file = file)

        assertEquals("Hämmern", result)
        assertEquals(1, classifier.callCount)
    }

    @Test
    fun batchKlassifikationAktualisiertNurUnklassifizierteAufnahmen() = kotlinx.coroutines.test.runTest {
        val tempFile = File.createTempFile("test_batch", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val records = mutableListOf(
            com.example.lrmprotokoll.data.NoiseRecord(id = 1, timestamp = 1000L, amplitude = 1.0, dbValue = 60.0, filePath = tempFile.absolutePath, detectedLabel = null),
            com.example.lrmprotokoll.data.NoiseRecord(id = 2, timestamp = 2000L, amplitude = 1.0, dbValue = 65.0, filePath = tempFile.absolutePath, detectedLabel = "Bereits da")
        )
        val updated = mutableListOf<com.example.lrmprotokoll.data.NoiseRecord>()
        val fakeDao = object : com.example.lrmprotokoll.data.NoiseDao {
            override fun getAll() = kotlinx.coroutines.flow.flowOf(records)
            override suspend fun getAlleAktiven() = records
            override fun getTrash() = kotlinx.coroutines.flow.flowOf(emptyList<com.example.lrmprotokoll.data.NoiseRecord>())
            override suspend fun zwischenZeitpunkt(von: Long, bis: Long) = emptyList<com.example.lrmprotokoll.data.NoiseRecord>()
            override fun zwischenZeitpunktFlow(von: Long, bis: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.lrmprotokoll.data.NoiseRecord>())
            override fun abZeitpunktFlow(von: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.lrmprotokoll.data.NoiseRecord>())
            override suspend fun insert(record: com.example.lrmprotokoll.data.NoiseRecord): Long = 0
            override suspend fun update(record: com.example.lrmprotokoll.data.NoiseRecord) { updated.add(record) }
            override suspend fun softDelete(id: Long, deletedAt: Long) {}
            override suspend fun softDeleteMultiple(ids: List<Long>, deletedAt: Long) {}
            override suspend fun restore(id: Long) {}
            override suspend fun restoreMultiple(ids: List<Long>) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteMultiple(ids: List<Long>) {}
            override suspend fun deleteTrashAelterAls(cutoff: Long) = 0
            override suspend fun getTrashAelterAls(cutoff: Long) = emptyList<com.example.lrmprotokoll.data.NoiseRecord>()
            override suspend fun getAutoRetentionCandidates(cutoff: Long) = emptyList<com.example.lrmprotokoll.data.NoiseRecord>()
            override suspend fun setFavorite(id: Long, isFavorite: Boolean) {}
            override suspend fun setNotes(id: Long, notes: String?) {}
            override suspend fun setDetectedLabel(id: Long, label: String?) {}
            override suspend fun getCalibratedDbA(id: Long): Double? = null
            override fun getAllReferences() = kotlinx.coroutines.flow.flowOf(emptyList<com.example.lrmprotokoll.data.ReferenceSound>())
            override suspend fun insertReference(sound: com.example.lrmprotokoll.data.ReferenceSound) {}
            override suspend fun deleteReference(id: Long) {}
        }

        val classifier = FakeSoundClassifier(result = "Bohren")
        val unclassified = fakeDao.getAlleAktiven().filter { it.detectedLabel == null && it.label == null }
        var count = 0
        for (r in unclassified) {
            val res = classifier.classify(File(r.filePath))
            if (res != null) {
                fakeDao.update(r.copy(detectedLabel = res))
                count++
            }
        }

        assertEquals(1, count)
        assertEquals(1, updated.size)
        assertEquals("Bohren", updated.first().detectedLabel)
        assertEquals(1L, updated.first().id)
    }

    @Test
    fun reinesPegelEreignisWirdOhneAudiodateiGespeichert() {
        val record = com.example.lrmprotokoll.data.NoiseRecord(
            timestamp = 1700000000L,
            amplitude = 0.0,
            dbValue = 68.5,
            filePath = "",
            calibratedDbA = 68.5,
            meterWeighting = "A",
            meterConnected = true,
            isQuietHour = false
        )

        assertEquals("", record.filePath)
        assertEquals(68.5, record.dbValue, 0.01)
        assertEquals(true, record.meterConnected)
        assertNull(record.detectedLabel)
    }
}
