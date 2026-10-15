package com.example.lrmprotokoll.testhilfen

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemeinsamer Ausgabeweg der Wächter-Diagnose (PR #280): `System.err` UND [ZeitwaechterRegel.DIAGNOSEDATEI].
 * Warum beides: Gradle fängt die Ströme der Testworker ab und hängt sie erst beim Abschluss der Aufgabe an den
 * XML-Bericht; schneidet `Test.timeout` die Aufgabe ab, ist die Ausgabe weg. Die Datei entsteht sofort und liegt
 * unter `app/build/`, das die CI mit hochlädt.
 */
internal object Diagnoseausgabe {
    private val hookInstalliert = AtomicBoolean(false)

    fun schreibe(text: String) {
        System.err.print(text)
        System.err.flush()
        runCatching {
            val datei = File(ZeitwaechterRegel.DIAGNOSEDATEI)
            datei.parentFile?.mkdirs()
            datei.appendText(text)
        }
    }

    /** Schreibt [text] einmal beim Ende der Test-JVM; für Zähler, die sich über den ganzen Lauf sammeln. */
    fun beiJvmEnde(text: () -> String) {
        if (!hookInstalliert.compareAndSet(false, true)) return
        runCatching { Runtime.getRuntime().addShutdownHook(Thread { runCatching { schreibe(text()) } }) }
    }
}
