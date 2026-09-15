package com.example.lrmprotokoll.report

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MeasurementFlags
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Die SQL-Vorpruefung darf GAP-Zeilen nicht als unbestätigte echte Messung zählen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BerichtErstellungDaoTest {

    @Test fun sqlZaehltRohdatenVerdichtungUndUnbestaetigteWerteGetrennt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val sessionId = db.sessionDao().insert(SessionEntity(
            startedAt = 1_000, endedAt = 2_000, deviceAddress = "AA:BB", deviceName = "PCE-323",
            weighting = null, timeWeighting = null,
        ))
        db.measurementDao().insertAll(listOf(
            MeasurementEntity(sessionId = sessionId, timestamp = 1_100, levelDb = 0.0,
                weighting = null, timeWeighting = null, flags = MeasurementFlags.GAP),
            MeasurementEntity(sessionId = sessionId, timestamp = 1_200, levelDb = 55.0,
                weighting = null, timeWeighting = null, flags = 0),
            MeasurementEntity(sessionId = sessionId, timestamp = 1_300, levelDb = 56.0,
                weighting = "A", timeWeighting = "FAST", flags = 0),
        ))
        db.minuteAggregateDao().insertAll(listOf(MinuteAggregateEntity(
            sessionId = sessionId, minuteStart = 1_000, leqDb = 55.0, maxDb = 56.0,
            minDb = 54.0, sampleCount = 2, weighting = null,
        )))

        assertEquals(3, db.measurementDao().anzahlZwischen(1_000, 2_000))
        assertEquals(1, db.measurementDao().anzahlUnbestaetigtZwischen(1_000, 2_000))
        assertEquals(1, db.minuteAggregateDao().anzahlZwischen(1_000, 2_000))
        db.close()
    }
}
