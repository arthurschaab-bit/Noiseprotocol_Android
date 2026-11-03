package com.example.lrmprotokoll.diagnose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.DiagnosticLogDao
import com.example.lrmprotokoll.data.DiagnosticLogEntity
import com.example.lrmprotokoll.diagnose.export.BundleKontext
import com.example.lrmprotokoll.diagnose.export.BundleTyp
import com.example.lrmprotokoll.diagnose.export.SupportBundleExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipFile

/**
 * Unit-Tests für Schritt 6 aus `docs/PROMPT_DIAGNOSE_STILLSTAND.md`:
 * Auswahl der nachfragewürdigen Ausfälle (>= 30 min, ohne `NUTZER_STOPP` und `AKKU_LEER`),
 * Einmal-Nachfrage je `letzteDatenAt`, Obergrenze von 20 unbeantworteten Lücken im
 * DiagnoseScreen sowie Persistenz von `nutzerAngabe` in `LebenszyklusRingFile` und
 * `state/ausfaelle.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AusfallNachfrageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = (context as LaermprotokollApp).container

    private fun ausfallEintrag(
        letzteDatenAt: Long,
        lueckeMinuten: Long,
        klasse: AusfallKlasse,
        waechterLief: String = "unbekannt",
    ): LebenszyklusEintrag {
        val entdecktAt = letzteDatenAt + lueckeMinuten * 60_000L
        return LebenszyklusEintrag(
            timestampMillis = entdecktAt,
            ereignis = EREIGNIS_AUSFALL_EINGEORDNET,
            details =
                mapOf(
                    "letzteDatenAt" to letzteDatenAt,
                    "entdecktAt" to entdecktAt,
                    "lueckeMinuten" to lueckeMinuten,
                    "klassifikation" to klasse.name,
                    "waechterLief" to waechterLief,
                ),
        )
    }

    @Test
    fun unter30MinutenOderNutzerStoppOderAkkuLeerWerdenNichtNachgefragt() {
        val eintraege =
            listOf(
                ausfallEintrag(1_000_000L, 29L, AusfallKlasse.STILLSTAND_GERAET_LIEF),
                ausfallEintrag(2_000_000L, 120L, AusfallKlasse.NUTZER_STOPP),
                ausfallEintrag(3_000_000L, 240L, AusfallKlasse.AKKU_LEER),
            )

        assertNull(AusfallNachfrageLogik.offeneNachfrageFuerHauptbildschirm(eintraege, emptySet()))
        assertTrue(AusfallNachfrageLogik.unbeantworteteLueckenFuerDiagnose(eintraege).isEmpty())
    }

    @Test
    fun ab30MinutenWirdNachgefragtUndNachWegwischenNurNochInDiagnoseGezeigt() {
        val eintraege =
            listOf(
                ausfallEintrag(10_000_000L, 30L, AusfallKlasse.STILLSTAND_GERAET_LIEF),
                ausfallEintrag(20_000_000L, 95L, AusfallKlasse.KERNEL_NEUSTART),
            )

        val hauptbildschirmOffen =
            AusfallNachfrageLogik.offeneNachfrageFuerHauptbildschirm(eintraege, emptySet())
        assertNotNull(hauptbildschirmOffen)
        assertEquals(20_000_000L, hauptbildschirmOffen?.letzteDatenAt)

        // Nach Wegwischen von 20_000_000L rückt der ältere noch ungefragte Ausfall nach,
        // und nach Wegwischen beider ist der Hauptbildschirm frei, während DiagnoseScreen beide behält.
        val nachBeidenWeggewischt =
            AusfallNachfrageLogik.offeneNachfrageFuerHauptbildschirm(
                eintraege,
                setOf(10_000_000L, 20_000_000L),
            )
        assertNull(nachBeidenWeggewischt)

        val diagnoseOffen = AusfallNachfrageLogik.unbeantworteteLueckenFuerDiagnose(eintraege)
        assertEquals(listOf(20_000_000L, 10_000_000L), diagnoseOffen.map { it.letzteDatenAt })
    }

    @Test
    fun unbeantworteteLueckenFuerDiagnoseSindAufZwanzigBegrenzt() {
        val eintraege =
            (1..25).map { nr ->
                ausfallEintrag(
                    letzteDatenAt = nr * 100_000_000L,
                    lueckeMinuten = 45L,
                    klasse = AusfallKlasse.STILLSTAND_GERAET_LIEF,
                )
            }

        val diagnoseListe = AusfallNachfrageLogik.unbeantworteteLueckenFuerDiagnose(eintraege)
        assertEquals(20, diagnoseListe.size)
        // Neueste zuerst (25 downTo 6)
        assertEquals(25 * 100_000_000L, diagnoseListe.first().letzteDatenAt)
        assertEquals(6 * 100_000_000L, diagnoseListe.last().letzteDatenAt)
    }

    @Test
    fun formatiereDauerUndZeitpunktLiefernKlartextWieGefordert() {
        // 45 h 49 min = (45 * 60 + 49) * 60_000 ms
        val dauer45h49m = (45L * 60L + 49L) * 60_000L
        assertEquals("45 h 49 min", AusfallNachfrageLogik.formatiereDauer(dauer45h49m))
        assertEquals("35 min", AusfallNachfrageLogik.formatiereDauer(35L * 60_000L))

        val zone = ZoneId.of("Europe/Berlin")
        // 2026-10-30T13:07:00+01:00 -> 30.10. 13:07
        val epoch =
            java.time.ZonedDateTime
                .of(2026, 10, 30, 13, 7, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
        assertEquals("30.10. 13:07", AusfallNachfrageLogik.formatiereZeitpunkt(epoch, zone))
    }

    @Test
    fun speichereAntwortPersistiertNutzerAngabeImRingUndInAusfaelleJson() =
        runTest {
            val ringDir = File(context.cacheDir, "nachfrage_ring_${System.nanoTime()}").apply { mkdirs() }
            val ring = LebenszyklusRingFile(ringDir)
            val letzteDatenAt = 1_761_826_020_000L
            val entdecktAt = letzteDatenAt + 45L * 60_000L

            ring.protokolliere(
                ereignis = EREIGNIS_AUSFALL_EINGEORDNET,
                details =
                    mapOf(
                        "letzteDatenAt" to letzteDatenAt,
                        "entdecktAt" to entdecktAt,
                        "lueckeMinuten" to 45L,
                        "klassifikation" to AusfallKlasse.STILLSTAND_GERAET_LIEF.name,
                        "waechterLief" to "nein",
                    ),
                timestampMillis = entdecktAt,
            )

            AusfallNachfrageLogik.speichereAntwort(
                ringFile = ring,
                letzteDatenAt = letzteDatenAt,
                antwort = AusfallAntwort.EINGEFROREN,
                jetztMs = entdecktAt + 5_000L,
            )
            ring.wartenBisFertig()

            val gelesen = AusfallNachfrageLogik.leseAusfaelle(ring.lesen())
            assertEquals(1, gelesen.size)
            assertEquals(AusfallAntwort.EINGEFROREN, gelesen.single().nutzerAngabe)
            assertFalse(AusfallNachfrageLogik.unbeantworteteLueckenFuerDiagnose(ring.lesen()).isNotEmpty())

            // Prüfe zusätzlich state/ausfaelle.json im SupportBundleExporter
            val exporter =
                SupportBundleExporter(
                    context = context,
                    reporter = container.diagnosticsReporter,
                    diagnosticLogDao = LeerDiagnosticLogDao,
                    breadcrumbRingFile = container.breadcrumbRingFile,
                    lebenszyklusRingFile = ring,
                    settingsManager = container.settingsManager,
                    database = container.database,
                    traceVerzeichnis = File(context.cacheDir, "traces_${System.nanoTime()}"),
                )
            val zipFile = exporter.createBundle(BundleKontext(typ = BundleTyp.MANUELL, ausloeser = "Test"))
            ZipFile(zipFile).use { zip ->
                val ausfaelleText =
                    zip
                        .getInputStream(zip.getEntry("state/ausfaelle.json"))
                        .bufferedReader()
                        .readText()
                val arr = JSONArray(ausfaelleText)
                assertEquals(1, arr.length())
                val obj = arr.getJSONObject(0)
                assertEquals("EINGEFROREN", obj.getString("nutzerAngabe"))
                assertEquals("EINGEFROREN", obj.getJSONObject("belege").getString("nutzerAngabe"))
            }
        }

    private object LeerDiagnosticLogDao : DiagnosticLogDao {
        override suspend fun insert(eintrag: DiagnosticLogEntity) = Unit

        override fun neueste(grenze: Int): Flow<List<DiagnosticLogEntity>> = flowOf(emptyList())

        override suspend fun loescheAelterAls(grenze: Long) = Unit

        override suspend fun seiteRueckwaerts(
            vorId: Long,
            seitengroesse: Int,
        ): List<DiagnosticLogEntity> = emptyList()

        override suspend fun anzahlSeit(von: Long): Long = 0L
    }
}
