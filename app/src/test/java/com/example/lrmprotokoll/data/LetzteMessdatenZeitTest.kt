package com.example.lrmprotokoll.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetzteMessdatenZeitTest {
    @Test
    fun dauerhafterRohwertBleibtNachPufferBereinigungErhalten() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
            try {
                db.measurementDao().insertAll(
                    listOf(
                        MeasurementEntity(sessionId = 1L, timestamp = 1_000L, levelDb = 50.0, weighting = null, flags = 0),
                        MeasurementEntity(
                            sessionId = 1L,
                            timestamp = 3_000L,
                            levelDb = 50.0,
                            weighting = null,
                            flags = MeasurementFlags.GAP,
                        ),
                    ),
                )
                db.levelSampleDao().insert(LevelSampleEntity(at = 2_000L, levelDb = 50.0, source = LevelSource.PCE_323))
                assertEquals(2_000L, db.letzteMessdatenAt())

                db.levelSampleDao().loescheVor(3_000L)
                assertEquals(1_000L, db.letzteMessdatenAt())
            } finally {
                db.close()
            }
        }
}
