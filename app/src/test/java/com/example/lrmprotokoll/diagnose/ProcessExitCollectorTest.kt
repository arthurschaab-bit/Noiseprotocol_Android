package com.example.lrmprotokoll.diagnose

import android.app.ApplicationExitInfo
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M12 Schritt 3: [ProcessExitCollector] gegen eine handgeschriebene Fake-Quelle von Exit-
 * Eintraegen (AGENTS.md Abschnitt 3: keine Mocking-Bibliothek) - mehrere Eintraege, Entprellung,
 * `null`-Trace, Binaerdaten bleiben byteweise identisch, Obergrenze greift.
 *
 * Plain JUnit: weder [ProcessExitInfo] noch [ProcessExitCollector] fassen `org.json` oder
 * echte Android-Systemdienste an - nur die (konstanten) `ApplicationExitInfo.REASON_*`-Felder,
 * die auch ohne Robolectric-Shadow real vorliegen (Feldwerte werden vom Stub-android.jar nicht
 * wegoptimiert, nur Methodenkoerper).
 */
class ProcessExitCollectorTest {

    private lateinit var verzeichnis: File
    private var gespeicherterZeitstempel = 0L

    @Before
    fun aufbauen() {
        verzeichnis = File.createTempFile("exittraces", "dir").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun aufraeumen() {
        verzeichnis.deleteRecursively()
    }

    private fun exitInfo(
        reason: Int,
        timestamp: Long,
        trace: (() -> InputStream?) = { null },
    ) = ProcessExitInfo(
        reason = reason,
        status = 0,
        timestamp = timestamp,
        importance = 100,
        pss = 12_345L,
        rss = 23_456L,
        description = "Testbeschreibung",
        processName = "com.example.lrmprotokoll",
        definingUid = 10123,
        traceInputStreamProvider = trace,
    )

    private fun reporterUndCollector(exits: List<ProcessExitInfo>, sdkInt: Int = Build.VERSION_CODES.S): Pair<DiagnosticsReporter, ProcessExitCollector> {
        val reporter = CompositeDiagnosticsReporter(sinks = emptyList())
        val collector = ProcessExitCollector(
            source = object : ProcessExitSource {
                override fun historischeExits(): List<ProcessExitInfo> = exits
            },
            diagnosticsReporter = reporter,
            verzeichnis = verzeichnis,
            zuletztVerarbeitet = { gespeicherterZeitstempel },
            setzeZuletztVerarbeitet = { gespeicherterZeitstempel = it },
            sdkInt = sdkInt,
        )
        return reporter to collector
    }

    @Test
    fun mehrereNeueExitsWerdenAlleVerarbeitet() {
        val (reporter, collector) = reporterUndCollector(
            listOf(
                exitInfo(ApplicationExitInfo.REASON_CRASH, timestamp = 100),
                exitInfo(ApplicationExitInfo.REASON_LOW_MEMORY, timestamp = 200),
                exitInfo(ApplicationExitInfo.REASON_ANR, timestamp = 300),
            )
        )

        collector.auswerten()

        assertEquals(3, reporter.recentBreadcrumbs().size)
        // Nur CRASH und ANR gelten als "unnormal" und erzeugen zusaetzlich ein Report-Event
        // (Verhaltensparitaet zur alten Implementierung in LaermprotokollApp).
        assertEquals(2, reporter.recentEvents().size)
        assertEquals(300L, gespeicherterZeitstempel)
    }

    @Test
    fun entprellungUeberspringtBereitsVerarbeiteteExits() {
        gespeicherterZeitstempel = 200L
        val (reporter, collector) = reporterUndCollector(
            listOf(
                exitInfo(ApplicationExitInfo.REASON_CRASH, timestamp = 100),
                exitInfo(ApplicationExitInfo.REASON_CRASH, timestamp = 200),
                exitInfo(ApplicationExitInfo.REASON_ANR, timestamp = 300),
            )
        )

        collector.auswerten()

        assertEquals("Nur der Eintrag NACH dem gespeicherten Zeitstempel darf verarbeitet werden", 1, reporter.recentBreadcrumbs().size)
        assertEquals(300L, gespeicherterZeitstempel)
    }

    @Test
    fun derselbeExitWirdBeiWiederholtemAufrufNichtErneutGemeldet() {
        val exits = listOf(exitInfo(ApplicationExitInfo.REASON_CRASH, timestamp = 100))
        val (reporter, collector) = reporterUndCollector(exits)

        collector.auswerten()
        collector.auswerten()

        assertEquals(1, reporter.recentBreadcrumbs().size)
    }

    @Test
    fun nullTraceFuehrtZuKeinemFehlerUndKeinerDatei() {
        val (_, collector) = reporterUndCollector(
            listOf(exitInfo(ApplicationExitInfo.REASON_ANR, timestamp = 100, trace = { null }))
        )

        collector.auswerten()

        assertFalse(File(verzeichnis, ANR_TRACE_DATEINAME).exists())
    }

    @Test
    fun anrTraceWirdAlsDateiGesichert() {
        val inhalt = "Thread-Dump\n\tat com.example.Foo.bar(Foo.kt:1)".toByteArray(Charsets.UTF_8)
        val (_, collector) = reporterUndCollector(
            listOf(exitInfo(ApplicationExitInfo.REASON_ANR, timestamp = 100, trace = { ByteArrayInputStream(inhalt) }))
        )

        collector.auswerten()

        val datei = File(verzeichnis, ANR_TRACE_DATEINAME)
        assertTrue(datei.exists())
        assertArrayEquals(inhalt, datei.readBytes())
    }

    @Test
    fun nativesTombstoneBleibtByteweiseIdentisch() {
        // Alle 256 Bytewerte inkl. Nullbytes - genau die Art Binaerdaten, die ein Reader/String-
        // Umweg zerstoeren wuerde (Aufgabe 1).
        val binaer = ByteArray(256) { it.toByte() }
        val (_, collector) = reporterUndCollector(
            listOf(
                exitInfo(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 100, trace = { ByteArrayInputStream(binaer) })
            ),
            sdkInt = Build.VERSION_CODES.S,
        )

        collector.auswerten()

        val datei = File(verzeichnis, NATIVE_TOMBSTONE_DATEINAME)
        assertTrue(datei.exists())
        assertArrayEquals(binaer, datei.readBytes())
    }

    @Test
    fun nativesTombstoneWirdUnter31NichtGesichert() {
        val (_, collector) = reporterUndCollector(
            listOf(
                exitInfo(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 100, trace = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
            ),
            sdkInt = Build.VERSION_CODES.R,
        )

        collector.auswerten()

        assertFalse(File(verzeichnis, NATIVE_TOMBSTONE_DATEINAME).exists())
    }

    @Test
    fun obergrenzeGreiftBeiSehrGrossemTrace() {
        val unendlicherStrom = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 0.toByte())
                return len
            }
        }
        val (_, collector) = reporterUndCollector(
            listOf(exitInfo(ApplicationExitInfo.REASON_ANR, timestamp = 100, trace = { unendlicherStrom }))
        )

        collector.auswerten()

        val datei = File(verzeichnis, ANR_TRACE_DATEINAME)
        assertTrue(datei.exists())
        assertEquals(4L * 1024 * 1024, datei.length())
    }

    @Test
    fun sdkUnter30MachtNichtsUndStuerztNichtAb() {
        val (reporter, collector) = reporterUndCollector(
            listOf(exitInfo(ApplicationExitInfo.REASON_CRASH, timestamp = 100)),
            sdkInt = Build.VERSION_CODES.Q, // 29, entspricht minSdk
        )

        collector.auswerten()

        assertEquals(0, reporter.recentBreadcrumbs().size)
        assertEquals(0L, gespeicherterZeitstempel)
    }
}
