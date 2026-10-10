package com.example.lrmprotokoll.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.NoiseRecord
import com.example.lrmprotokoll.drive.DriveAblage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * KI-Batch als Hintergrundarbeit (docs/PROMPT_KI_BATCH_HINTERGRUND.md): der Worker bestimmt die
 * Kandidaten selbst, beachtet den Zeitraum, merkt die Tage fuer den Drive-Nachtrag vor und
 * respektiert den Nachtlauf-Schalter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KiBatchWorkerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var app: LaermprotokollApp

    /**
     * Handgeschriebener Klassifizierer: jede vorhandene Datei wird als "Bohren" erkannt.
     * [verzoegerungMs] macht die Inferenz kuenstlich langsam, damit sich zwei Laeufe ueberlappen;
     * [scheitertAb] wirft ab dem n-ten Aufruf (Prozess-Tod mitten im Batch).
     */
    private class BohrenClassifier(
        private val verzoegerungMs: Long = 0L,
        private val scheitertAb: Int = Int.MAX_VALUE,
    ) : RohdatenClassifier {
        val angefragt = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun klassifiziereMitRohdaten(file: File): KlassifikationsErgebnis {
            if (angefragt.size + 1 >= scheitertAb) throw IllegalStateException("simulierter Prozess-Tod")
            if (verzoegerungMs > 0) Thread.sleep(verzoegerungMs)
            angefragt += file.name
            return KlassifikationsErgebnis(
                "Bohren",
                RohdatenBauplan(
                    modellVersion = "yamnet.tflite@test",
                    klassifiziertAm = 1_700_000_000_000L,
                    frameAnzahl = 1,
                    frameDauerMs = 960,
                    frameHopMs = 480,
                    klassenIndizes = ROHDATEN_KLASSEN_INDIZES,
                    frameScores = ByteArray(0),
                    topKlassen = "",
                ),
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as LaermprotokollApp
        app.container.settingsManager.kiNachtlauf = true
        app.container.settingsManager.aiMode = "BATCH"
        val settings = app.container.settingsManager
        settings.kiNachtragOffeneTage.forEach { tag ->
            settings.uebernimmKiNachtrag(tag)?.let { settings.erledigeKiNachtrag(tag, it) }
        }
    }

    @After
    fun tearDown() {
        app.container.settingsManager.kiNachtlauf = true
    }

    private fun bauWorker(
        classifier: RohdatenClassifier,
        vararg daten: Pair<String, Any?>,
    ) = TestListenableWorkerBuilder<KiBatchWorker>(context)
        .setInputData(workDataOf(*daten))
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = KiBatchWorker(appContext, workerParameters, classifier)
            },
        ).build()

    private suspend fun aufnahme(zeitpunkt: Long): Long {
        val wav = tempFolder.newFile("rec_$zeitpunkt.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return app.container.database.noiseDao().insert(
            NoiseRecord(timestamp = zeitpunkt, amplitude = 0.0, dbValue = 60.0, filePath = wav.absolutePath),
        )
    }

    @Test
    fun klassifiziertNurAufnahmenImZeitraumUndMerktDenTagVor() =
        runTest {
            // Weit in der Zukunft, damit die testuebergreifend geteilte Datenbank nicht stoert.
            val tag = 9_200_000_000_000L
            val drinnen = aufnahme(tag)
            val draussen = aufnahme(tag + Duration.ofDays(3).toMillis())
            val classifier = BohrenClassifier()

            val ergebnis =
                bauWorker(
                    classifier,
                    KiBatchWorker.KEY_VON to tag - 1,
                    KiBatchWorker.KEY_BIS to tag + 1,
                ).doWork()

            assertEquals(Result.success(workDataOf(KiBatchWorker.KEY_ANZAHL to 1)), ergebnis)
            val dao = app.container.database.noiseDao()
            val alle = dao.getAll().first()
            assertEquals("Bohren", alle.single { it.id == drinnen }.detectedLabel)
            assertNull(alle.single { it.id == draussen }.detectedLabel)
            assertEquals(
                setOf(DriveAblage.tagesordner(tag, ZoneId.systemDefault())),
                app.container.settingsManager.kiNachtragOffeneTage,
            )
        }

    @Test
    fun nachtlaufTutNichtsWennDerSchalterAusIst() =
        runTest {
            val tag = 9_300_000_000_000L
            val id = aufnahme(tag)
            app.container.settingsManager.kiNachtlauf = false
            val classifier = BohrenClassifier()

            val ergebnis =
                bauWorker(
                    classifier,
                    KiBatchWorker.KEY_NACHTLAUF to true,
                ).doWork()

            assertEquals(Result.success(), ergebnis)
            assertTrue(classifier.angefragt.isEmpty())
            val dao = app.container.database.noiseDao()
            val aufnahme = dao.getAll().first().single { it.id == id }
            assertNull(aufnahme.detectedLabel)
        }

    @Test
    fun nachtlaufBeginntUmEinsUhrDreissig() {
        val zone = ZoneId.of("Europe/Berlin")
        val vorher = ZonedDateTime.of(2026, 10, 10, 0, 30, 0, 0, zone)
        val nachher = ZonedDateTime.of(2026, 10, 10, 6, 0, 0, 0, zone)

        assertEquals(Duration.ofHours(1), KiBatchPlanung.verzoegerungBisNachtlauf(vorher))
        assertEquals(Duration.ofHours(19).plusMinutes(30), KiBatchPlanung.verzoegerungBisNachtlauf(nachher))
    }

    /**
     * Review zu #273 (P1): Die Tage stehen dauerhaft in der Vormerkung, bevor das erste Label
     * gespeichert wird. Bricht der Lauf mitten drin ab (hier eine Ausnahme statt eines
     * Prozess-Tods, beides ohne regulaeres Ende), ist der Tag der bereits klassifizierten
     * Aufnahme trotzdem vorgemerkt.
     */
    @Test
    fun abbruchMittenImBatchVerliertDieVormerkungNicht() =
        runTest {
            val tag = 9_400_000_000_000L
            val ids = listOf(aufnahme(tag), aufnahme(tag + 1_000L))

            runCatching {
                bauWorker(
                    BohrenClassifier(scheitertAb = 2),
                    KiBatchWorker.KEY_VON to tag - 1,
                    KiBatchWorker.KEY_BIS to tag + 2_000L,
                ).doWork()
            }

            val dao = app.container.database.noiseDao()
            val gelabelt = dao.getAll().first().filter { it.id in ids && it.detectedLabel == "Bohren" }
            assertEquals("Genau eine Aufnahme vor dem Abbruch gelabelt", 1, gelabelt.size)
            assertTrue(
                DriveAblage.tagesordner(tag, ZoneId.systemDefault()) in app.container.settingsManager.kiNachtragOffeneTage,
            )
        }

    /**
     * Review zu #273 (P2): Nachtlauf und Handlauf ueberlappen sich. Jede Aufnahme darf nur
     * einmal klassifiziert werden und genau einen Rohdatensatz haben.
     */
    @Test
    fun ueberlappenderNachtUndHandlaufKlassifiziertJedeAufnahmeNurEinmal() =
        runTest {
            val tag = 9_500_000_000_000L
            val ids = (0 until 4).map { aufnahme(tag + it * 1_000L) }
            val classifier = BohrenClassifier(verzoegerungMs = 20L)

            val nacht = async { bauWorker(classifier, KiBatchWorker.KEY_NACHTLAUF to true).doWork() }
            val hand =
                async {
                    bauWorker(classifier, KiBatchWorker.KEY_VON to tag - 1, KiBatchWorker.KEY_BIS to tag + 10_000L).doWork()
                }
            nacht.await()
            hand.await()

            val unsere = classifier.angefragt.filter { name -> ids.any { name == "rec_${tag + ids.indexOf(it) * 1_000L}.wav" } }
            assertEquals("Jede Aufnahme genau einmal klassifiziert", 4, unsere.size)
            val rohdatenDao = app.container.database.klassifikationsRohdatenDao()
            val rohdaten = rohdatenDao.alle()
            for (id in ids) {
                assertEquals("Genau ein Rohdatensatz fuer Aufnahme $id", 1, rohdaten.count { it.recordId == id })
            }
        }
}
