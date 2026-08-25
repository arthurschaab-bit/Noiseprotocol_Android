package com.example.lrmprotokoll.report.gesamtbericht

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.report.gesamtbericht.calc.GesamtberichtAggregator
import com.example.lrmprotokoll.report.gesamtbericht.model.GesamtberichtConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class GesamtberichtAggregatorTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testAggregatorEndToEnd() = runBlocking {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.AUGUST, 25, 8, 0, 0)
        val startTime = cal.timeInMillis
        val endTime = startTime + 3600 * 1000L // 1 Stunde

        // 1. Session anlegen
        val sessionId = db.sessionDao().insert(
            SessionEntity(
                startedAt = startTime,
                endedAt = endTime,
                deviceName = "PCE-323",
                deviceAddress = "00:11:22:33:44:55",
                weighting = null,
                timeWeighting = null
            )
        )

        // 2. Minute Aggregates einfügen
        val aggregates = (0 until 60).map { i ->
            MinuteAggregateEntity(
                sessionId = sessionId,
                minuteStart = startTime + i * 60 * 1000L,
                leqDb = 58.0 + (i % 5),
                maxDb = 68.0,
                minDb = 48.0,
                sampleCount = 60,
                weighting = "A"
            )
        }
        db.minuteAggregateDao().insertAll(aggregates)

        // 3. Noise Records einfügen
        db.noiseDao().insert(
            NoiseRecord(
                timestamp = startTime + 10 * 60 * 1000L,
                amplitude = 0.8,
                dbValue = 68.5,
                filePath = "",
                label = "Tiefbohrer",
                detectedLabel = "Bohrmaschine",
                calibratedDbA = 68.5,
                isQuietHour = false
            )
        )

        val config = GesamtberichtConfig(
            messort = "Zeppelinstraße",
            auftraggeber = "Anwohner",
            richtwertTagWa = 55.0
        )

        val aggregator = GesamtberichtAggregator(db)
        val data = aggregator.aggregiere(config)

        assertEquals(1, data.gesamtMesstage)
        assertEquals(1, data.tage.size)

        val tag = data.tage[0]
        assertEquals("2026-08-25", tag.dayKey)
        assertNotNull(tag.laeq)
        assertTrue(tag.laeq!! > 55.0)
        assertTrue(tag.grenzwertUeberschreitungTag)
        assertEquals(1, tag.ereignisse.size)
        assertEquals("Tiefbohrer", tag.ereignisse[0].label)
        assertTrue(data.manifest.isNotEmpty())
    }
}
