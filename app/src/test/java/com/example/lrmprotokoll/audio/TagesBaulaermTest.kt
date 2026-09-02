package com.example.lrmprotokoll.audio

import com.example.lrmprotokoll.data.KlassifikationsRohdaten
import com.example.lrmprotokoll.data.KlassifikationsRohdatenDao
import com.example.lrmprotokoll.data.NoiseRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KI-Umbau Etappe 2.7: Tests fuer [berechneBaulaermMinutenDesTages] - reine JVM-Tests mit einem
 * handgeschriebenen Fake (dieses Projekt nutzt bewusst kein Mocking-Framework).
 */
class TagesBaulaermTest {

    private class FakeRohdatenDao(private val bestand: Map<Long, KlassifikationsRohdaten>) : KlassifikationsRohdatenDao {
        override suspend fun insert(rohdaten: KlassifikationsRohdaten): Long = 0
        override suspend fun fuerRecord(recordId: Long): KlassifikationsRohdaten? = bestand[recordId]
        override suspend fun alle(): List<KlassifikationsRohdaten> = bestand.values.toList()
        override suspend fun loescheFuerRecord(recordId: Long) {}
    }

    private fun record(id: Long) = NoiseRecord(id = id, timestamp = 0L, amplitude = 0.0, dbValue = 0.0, filePath = "")

    private fun rohdatenMitVollemBlock(recordId: Long, frameAnzahl: Int, frameHopMs: Int = 480) = KlassifikationsRohdaten(
        recordId = recordId,
        modellVersion = "test",
        klassifiziertAm = 0,
        frameAnzahl = frameAnzahl,
        frameDauerMs = 960,
        frameHopMs = frameHopMs,
        klassenIndizes = ROHDATEN_KLASSEN_INDIZES,
        frameScores = ByteArray(frameAnzahl * ROHDATEN_KLASSEN_INDIZES.size).also { array ->
            val position = ROHDATEN_KLASSEN_INDIZES.indexOf(413) // "Hammer"
            for (frame in 0 until frameAnzahl) {
                array[frame * ROHDATEN_KLASSEN_INDIZES.size + position] = 255.toByte()
            }
        },
        topKlassen = "",
    )

    @Test
    fun leereRecordlisteErgibtNullMinuten() = runTest {
        val minuten = berechneBaulaermMinutenDesTages(emptyList(), FakeRohdatenDao(emptyMap()), BaulaermKonfiguration())

        assertEquals(0f, minuten, 0.001f)
    }

    @Test
    fun aufnahmenOhneRohdatenTragenNichtsBeiStattAbzustuerzen() = runTest {
        val minuten = berechneBaulaermMinutenDesTages(
            listOf(record(1), record(2)),
            FakeRohdatenDao(emptyMap()),
            BaulaermKonfiguration(),
        )

        assertEquals(0f, minuten, 0.001f)
    }

    @Test
    fun summiertDieBaulaermSekundenMehrererAufnahmenZuMinuten() = runTest {
        // 60 Frames a 480ms Hop voller Baulärm = 28.8s pro Aufnahme, zwei Aufnahmen = 57.6s = 0.96 Min.
        val rohdaten1 = rohdatenMitVollemBlock(1, frameAnzahl = 60)
        val rohdaten2 = rohdatenMitVollemBlock(2, frameAnzahl = 60)
        val dao = FakeRohdatenDao(mapOf(1L to rohdaten1, 2L to rohdaten2))

        val minuten = berechneBaulaermMinutenDesTages(listOf(record(1), record(2)), dao, BaulaermKonfiguration())

        assertEquals(0.96f, minuten, 0.01f)
    }
}
