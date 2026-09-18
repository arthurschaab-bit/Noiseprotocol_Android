package com.example.lrmprotokoll.data

import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.LaermprotokollApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M12 Schritt 7 (Konzept Abschnitt 2 Verdacht, behoben): [DiagnosticLogDao.neueste] ersetzt die
 * vormalige `alle()`-Abfrage ohne `LIMIT` fuer die Anzeige im DiagnoseScreen - Gegenprobe, dass
 * bei mehr Eintraegen als der Grenze wirklich nur die Grenze geladen wird, und zwar die
 * NEUESTEN, nicht irgendwelche.
 *
 * Weit auseinanderliegende Basiszeitstempel je Testmethode - dieselbe Begruendung wie in
 * [SessionDaoTest]: die Datenbank ist nicht in-memory-isoliert je Testmethode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticLogDaoTest {

    private fun dao() =
        ApplicationProvider.getApplicationContext<LaermprotokollApp>().container.database.diagnosticLogDao()

    private fun eintrag(timestamp: Long, nachricht: String) =
        DiagnosticLogEntity(timestamp = timestamp, message = nachricht)

    @Test
    fun liefertNieMehrAlsDieGrenzeAuchBeiVielenEintraegen() = runBlocking {
        val dao = dao()
        val basis = 3_000_000_000_000L
        // Deutlich mehr Eintraege als die Grenze - genau der Aufzeichnungsbetrieb-Fall aus dem
        // Konzept-Verdacht, der bisher die komplette Tabelle neu in den Heap zog.
        (1..500).forEach { i -> dao.insert(eintrag(basis + i, "Eintrag $i")) }

        val geladen = dao.neueste(grenze = 50).first()

        assertEquals(50, geladen.size)
    }

    @Test
    fun liefertDieNeuestenZuerstNichtIrgendwelche() = runBlocking {
        val dao = dao()
        val basis = 3_100_000_000_000L
        (1..30).forEach { i -> dao.insert(eintrag(basis + i, "Eintrag $i")) }

        // Grosse Grenze, um sicher die GESAMTE (nicht isolierte, siehe Klassen-KDoc) Tabelle zu
        // erfassen, statt sich darauf zu verlassen, dass SQL LIMIT zufaellig genau die eigenen
        // Zeilen zurueckgibt - andere, in derselben JVM-Fork spaeter oder frueher gelaufene Tests
        // koennten sonst juenger datierte Fremdzeilen vor die eigenen schieben. Die Abfrage ist
        // bereits global absteigend sortiert; das Filtern auf den eigenen Zeitraum erhaelt diese
        // Reihenfolge, die ersten zehn der gefilterten Liste sind deshalb exakt die zehn
        // juengsten eigenen.
        val geladen = dao.neueste(grenze = 100_000).first()
            .filter { it.timestamp in (basis + 1)..(basis + 30) }
            .take(10)

        assertEquals(10, geladen.size)
        // Die zehn juengsten von dreissig sind Eintrag 21..30, absteigend sortiert.
        assertEquals((30 downTo 21).map { basis + it }, geladen.map { it.timestamp })
    }

    @Test
    fun liefertAlleEintraegeWennWenigerAlsDieGrenzeVorhandenSind() = runBlocking {
        val dao = dao()
        val basis = 3_200_000_000_000L
        dao.insert(eintrag(basis + 1, "Einziger Eintrag"))

        // Grosse Grenze aus demselben Grund wie oben - sonst koennten juenger datierte
        // Fremdzeilen aus anderen Tests diesen einzelnen Eintrag aus dem Fenster verdraengen.
        val geladen = dao.neueste(grenze = 100_000).first().filter { it.timestamp == basis + 1 }

        assertTrue(geladen.any { it.message == "Einziger Eintrag" })
    }
}
