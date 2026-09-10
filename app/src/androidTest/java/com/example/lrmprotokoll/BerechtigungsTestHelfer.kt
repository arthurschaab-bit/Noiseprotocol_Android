package com.example.lrmprotokoll

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Gemeinsame Hilfsmittel fuer Berechtigungs-Regressionstests (Owner-Auftrag nach #129: CAMERA
 * war im Manifest deklariert, aber zur Laufzeit nicht gewaehrt - der implizite Kamera-Intent
 * scheiterte dadurch auf vielen Geraeten stillschweigend, der Aufnahme-Button wirkte "ohne
 * Funktion"). Dieselbe Fehlerklasse betrifft jede Stelle, die eine gefaehrliche Laufzeit-
 * berechtigung erst PRUEFT und bei Bedarf ANFRAGT, bevor sie eine Aktion ausfuehrt (Kamera,
 * Standort, Bluetooth) - siehe AGENTS.md Abschnitt 8b.
 *
 * **Testtiefe (Owner-Entscheidung 10.09.2026):** Diese Tests pruefen NUR den eigenen Code-Pfad -
 * dass die App bei fehlender Berechtigung tatsaechlich danach fragt (statt stumm nichts zu tun)
 * und bei erteilter Berechtigung korrekt fortfaehrt. Sie koennen NICHT beweisen, dass eine echte
 * externe App (Kamera, Standort-Provider) auf einem realen Geraet anschliessend erfolgreich
 * reagiert - das bleibt Sache der Geraeteverifikation (docs/CHECKLISTE_GERAETETEST.md).
 *
 * [erlaubeSystemdialog]/[verweigereSystemdialog] bedienen den ECHTEN System-Berechtigungsdialog
 * ueber [androidx.test.uiautomator] - das ist der einzige Weg, "Berechtigung fehlt" + Button-Tipp
 * + "was passiert danach wirklich" in einem einzigen, ehrlichen End-zu-End-Test zu pruefen, statt
 * nur zu pruefen, dass irgendwo `checkSelfPermission()` aufgerufen wurde.
 *
 * **Warum es hier keine `entziehe()`-Funktion (mehr) gibt (CI-Fund 10.09.2026, PR #132):**
 * Entzieht man einer LAUFENDEN, instrumentierten App-Instanz eine bereits gewaehrte Berechtigung,
 * toetet Android den Prozess sofort ("Killing ...: permissions revoked" im Logcat) - und weil
 * `AndroidJUnitRunner` INNERHALB dieses Prozesses laeuft, reisst das den gesamten Testlauf ab
 * (siehe "Instrumentation run failed due to Process crashed", nur 21 von 80 Tests kamen noch zur
 * Ausfuehrung). AGP installiert die Test-APK mit allen im Manifest deklarierten
 * Laufzeitberechtigungen bereits gewaehrt (`pm install -g`), ein In-Prozess-Revoke traefe also so
 * gut wie immer eine tatsaechlich gehaltene Berechtigung.
 *
 * **Die Loesung (Owner-Entscheidung 10.09.2026):** Der Revoke passiert von AUSSEN, per
 * `adb shell pm revoke`, BEVOR der instrumentierte Prozess ueberhaupt startet - es gibt dann
 * keinen laufenden Prozess, den Android toeten koennte. Das kann diese Klasse nicht selbst
 * (sie laeuft ja bereits im Prozess), das macht `.github/workflows/emulator-tests.yml` als
 * eigener Schritt: die betroffenen `*PermissionInstrumentedTest`-Methoden werden aus dem
 * regulaeren `connectedDebugAndroidTest`-Lauf ausgeschlossen (`notClass`-Argument) und danach
 * einzeln per `adb shell am instrument -e class ...` gestartet, jeweils nach einem `pm revoke`
 * fuer genau die Berechtigung, die der Test prueft. Lokal ueber `./gradlew
 * connectedDebugAndroidTest` (ohne dieses CI-Skript) faellt dieser Nachweis deshalb aus - siehe
 * Kommentar in den betroffenen Testklassen.
 */
object BerechtigungsTestHelfer {

    private const val WARTEZEIT_DIALOG_MS = 5_000L
    private const val WARTEZEIT_TEXT_FALLBACK_MS = 1_500L

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val geraet get() = UiDevice.getInstance(instrumentation)

    fun gewaehre(permission: String) {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
    }

    /** Wartet auf den System-Berechtigungsdialog und tippt "Zulassen". */
    fun erlaubeSystemdialog() {
        klickeSystemdialogKnopf(
            idSuffix = "permission_allow_button",
            textKandidaten = listOf(
                "Zulassen", "Allow",
                "Während der Verwendung der App zulassen", "While using the app",
                "Nur diesmal zulassen", "Only this time",
            ),
        )
    }

    /** Wartet auf den System-Berechtigungsdialog und tippt "Nicht zulassen". */
    fun verweigereSystemdialog() {
        klickeSystemdialogKnopf(
            idSuffix = "permission_deny_button",
            textKandidaten = listOf("Nicht zulassen", "Deny", "Ablehnen"),
        )
    }

    /**
     * Wie [erlaubeSystemdialog], wirft aber nicht, wenn (kein weiterer) Dialog erscheint - fuer
     * Anfragen mit mehreren Berechtigungen auf einmal (z.B. BLUETOOTH_SCAN + BLUETOOTH_CONNECT),
     * bei denen Android je nach Geraet/Version einen gemeinsamen oder mehrere aufeinanderfolgende
     * Dialoge zeigt. Aufrufer rufen das wiederholt und pruefen das tatsaechliche Ergebnis danach
     * ueber die echte Berechtigungsabfrage, nicht ueber den Rueckgabewert hier.
     */
    fun erlaubeFallsVorhanden(): Boolean = runCatching { erlaubeSystemdialog() }.isSuccess

    /**
     * Sucht zuerst ueber die Ressourcen-ID (stabil, aber je nach Geraetebild
     * `com.android.permissioncontroller` oder `com.google.android.permissioncontroller`), erst
     * als Rueckfall ueber den - locale-abhaengigen - sichtbaren Text.
     */
    private fun klickeSystemdialogKnopf(idSuffix: String, textKandidaten: List<String>) {
        val idSelektoren = listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
            .map { paket -> By.res(paket, idSuffix) }
        for (selektor in idSelektoren) {
            if (geraet.wait(Until.hasObject(selektor), WARTEZEIT_DIALOG_MS)) {
                geraet.findObject(selektor).click()
                return
            }
        }
        for (text in textKandidaten) {
            val selektor = By.text(text)
            if (geraet.wait(Until.hasObject(selektor), WARTEZEIT_TEXT_FALLBACK_MS)) {
                geraet.findObject(selektor).click()
                return
            }
        }
        throw AssertionError(
            "Kein System-Berechtigungsdialog gefunden (weder per Ressourcen-ID '$idSuffix' " +
                "noch per Text $textKandidaten) - siehe emulator-diagnostics-Artefakt bei einem " +
                "CI-Fehlschlag."
        )
    }
}
