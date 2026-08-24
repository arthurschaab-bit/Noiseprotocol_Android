package com.example.lrmprotokoll.drive

import com.example.lrmprotokoll.data.NoiseRecord
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WavHourlyZipperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun packeStundenZipsGruppiertWavDateienIn1hPakete() {
        val f1 = tempFolder.newFile("rec_0810.wav")
        f1.writeBytes(byteArrayOf(1, 2, 3))
        val f2 = tempFolder.newFile("rec_0845.wav")
        f2.writeBytes(byteArrayOf(4, 5, 6))
        val f3 = tempFolder.newFile("rec_0915.wav")
        f3.writeBytes(byteArrayOf(7, 8, 9))

        // 24.08.2026 08:10 Berlin (UTC+2) -> 06:10 UTC
        val t1 = Instant.parse("2026-08-24T06:10:00Z").toEpochMilli()
        val t2 = Instant.parse("2026-08-24T06:45:00Z").toEpochMilli()
        val t3 = Instant.parse("2026-08-24T07:15:00Z").toEpochMilli()

        val records = listOf(
            NoiseRecord(id = 1, timestamp = t1, amplitude = 0.0, dbValue = 60.0, filePath = f1.absolutePath),
            NoiseRecord(id = 2, timestamp = t2, amplitude = 0.0, dbValue = 65.0, filePath = f2.absolutePath),
            NoiseRecord(id = 3, timestamp = t3, amplitude = 0.0, dbValue = 70.0, filePath = f3.absolutePath),
        )

        // Jetzt = 09:30 Berlin (07:30 UTC)
        val jetzt = Instant.parse("2026-08-24T07:30:00Z")

        val zips = WavHourlyZipper.packeStundenZips(records, jetzt, zone)

        assertEquals(2, zips.size)

        // Paket 1: 08:00 Stunde (abgeschlossen)
        val p1 = zips[0]
        assertEquals("audio_2026-08-24_08-00.zip", p1.zipFileName)
        assertEquals(2, p1.wavCount)
        assertTrue(p1.isClosedHour)

        // Inhalt prüfen
        val zip1In = ZipInputStream(ByteArrayInputStream(p1.zipBytes))
        val entry1 = zip1In.nextEntry
        assertNotNull(entry1)
        assertEquals("rec_0810.wav", entry1!!.name)
        val entry2 = zip1In.nextEntry
        assertNotNull(entry2)
        assertEquals("rec_0845.wav", entry2!!.name)
        assertNull(zip1In.nextEntry)

        // Paket 2: 09:00 Stunde (laufende Stunde)
        val p2 = zips[1]
        assertEquals("audio_2026-08-24_09-00.zip", p2.zipFileName)
        assertEquals(1, p2.wavCount)
        assertFalse(p2.isClosedHour)
    }

    @Test
    fun packeStundenZipsIgnoriertNichtExistierendeDateien() {
        val records = listOf(
            NoiseRecord(id = 1, timestamp = Instant.now().toEpochMilli(), amplitude = 0.0, dbValue = 60.0, filePath = "/nicht/vorhanden.wav")
        )
        val zips = WavHourlyZipper.packeStundenZips(records, Instant.now(), zone)
        assertTrue(zips.isEmpty())
    }
}
