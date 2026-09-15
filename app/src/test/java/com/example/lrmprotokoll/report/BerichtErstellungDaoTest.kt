package com.example.lrmprotokoll.report

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.MeasurementFlags
import com.example.lrmprotokoll.data.MinuteAggregateEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Review-Befund PR #144: eine Session, die ueber Mitternacht laeuft, hat ihre
     * Stammdaten-Zeile mit einem `erstelltAm` kurz vor Mitternacht (Tag D). `fuerTag()` allein
     * findet sie fuer Tag D+1 nicht, obwohl derselbe dokumentierte Messaufbau beide Tage betrifft
     * - `ladeBerichtstage` muss den Nachbartag ueber die geteilte Session erkennen.
     */
    @Test fun stammdatenEinerMitternachtsSessionGeltenFuerBeideKalendertage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()

        val tagD = LocalDate.of(2026, 9, 14)
        val tagDPlus1 = LocalDate.of(2026, 9, 15)
        val sessionStart = tagD.atTime(23, 50).toInstant(ZoneOffset.UTC).toEpochMilli()
        val sessionEnde = tagDPlus1.atTime(0, 20).toInstant(ZoneOffset.UTC).toEpochMilli()
        val vorMitternacht = tagD.atTime(23, 55).toInstant(ZoneOffset.UTC).toEpochMilli()
        val nachMitternacht = tagDPlus1.atTime(0, 10).toInstant(ZoneOffset.UTC).toEpochMilli()

        val sessionId = db.sessionDao().insert(SessionEntity(
            startedAt = sessionStart, endedAt = sessionEnde, deviceAddress = "AA:BB", deviceName = "PCE-323",
            weighting = "A", timeWeighting = "FAST",
        ))
        db.measurementDao().insertAll(listOf(
            MeasurementEntity(sessionId = sessionId, timestamp = vorMitternacht, levelDb = 55.0,
                weighting = "A", timeWeighting = "FAST", flags = 0),
            MeasurementEntity(sessionId = sessionId, timestamp = nachMitternacht, levelDb = 56.0,
                weighting = "A", timeWeighting = "FAST", flags = 0),
        ))
        db.stammdatenVerlaufDao().insert(StammdatenVerlaufEntity(
            erstelltAm = sessionStart, geraetHersteller = "PCE", geraetTyp = "323",
            geraetGenauigkeitsklasse = "2", geraetSeriennummer = "SN1", geraetKalibrierung = "Kalibriert",
            messort = "Musterort", mikrofonposition = "Fenster", mikrofonhoehe = "1m",
            entfernungZurQuelle = "5m", innenAussen = "Außen", fensterzustand = "",
            wetter = "trocken", datenqualitaetHinweis = "",
        ))

        val tage = ladeBerichtstage(db, BerichtZeitraum(tagD, tagDPlus1), zone = ZoneOffset.UTC)

        assertEquals(2, tage.size)
        val ersterTag = tage.first { it.datum == tagD }
        val zweiterTag = tage.first { it.datum == tagDPlus1 }
        assertTrue("Tag D sollte die Session enthalten", sessionId in ersterTag.sessionIds)
        assertTrue("Tag D+1 sollte dieselbe Session enthalten (Ueberlappung)", sessionId in zweiterTag.sessionIds)
        assertEquals(1, ersterTag.stammdatenKandidaten.size)
        assertEquals(
            "Tag D+1 muss die Stammdaten vom Vortag uebernehmen statt eine Luecke zu zeigen",
            ersterTag.stammdatenKandidaten, zweiterTag.stammdatenKandidaten,
        )
        db.close()
    }
}
