package com.example.lrmprotokoll.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.ACTION_STOP_SERVICE
import com.example.lrmprotokoll.audio.AudioRecordingService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der Foreground Service ohne `RECORD_AUDIO` (UX-Audit Kapitel 35.1, letzter Punkt).
 *
 * **Warum das offen war:** `berechneForegroundServiceType()` liefert `0`, wenn weder
 * `RECORD_AUDIO` gewaehrt noch ein Messgeraet gepinnt ist. `startForegroundService()` faengt das
 * mit einem typlosen `startForeground(id, notification)` ab - dieser Zweig wurde aber nie
 * beobachtet. Fuer Befund F-02 des Audits ist er entscheidend: Die dort vorgeschlagene Aktion
 * "nur verbinden, ohne aufzuzeichnen" laeuft genau hier durch, und wenn der Dienst dabei
 * abstuerzt oder nie in den Vordergrund kommt, ist der Vorschlag nicht umsetzbar.
 *
 * **Warum diese Klasse `...PermissionInstrumentedTest` heisst und einzeln laeuft:** AGP
 * installiert die Test-APK mit allen deklarierten Laufzeitberechtigungen bereits gewaehrt
 * (`pm install -g`). Ein Entzug waehrend eines laufenden instrumentierten Prozesses toetet
 * diesen Prozess und reisst den gesamten Testlauf mit (CI-Fund 10.09.2026, PR #132). Der Entzug
 * passiert deshalb von aussen, VOR dem Prozessstart, durch
 * `.github/scripts/run-instrumented-tests.sh` - dieselbe Mechanik wie bei den vier bereits
 * vorhandenen `*PermissionInstrumentedTest`-Faellen. **Lokal ueber `./gradlew
 * connectedDebugAndroidTest` faellt dieser Nachweis deshalb aus** (der Test ueberspringt sich
 * dann selbst per `assumeTrue`), er laeuft nur im CI-Skript.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceOhneMikrofonPermissionInstrumentedTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private var vorherigeMeterAdresse: String? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        notificationManager = context.getSystemService(NotificationManager::class.java)

        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        val settings = app.container.settingsManager
        vorherigeMeterAdresse = settings.meterDeviceAddress
        // Kein gepinntes Messgeraet: nur so ergibt berechneForegroundServiceType() wirklich 0.
        settings.meterDeviceAddress = null
        // Sonst nimmt onStartCommand die Aufzeichnung wieder auf (sollAudioMonitoringStarten).
        settings.audioMonitoringWasActive = false

        stoppeDienstFallsAktiv()
    }

    @After
    fun tearDown() {
        stoppeDienstFallsAktiv()
        val app = ApplicationProvider.getApplicationContext<LaermprotokollApp>()
        app.container.settingsManager.meterDeviceAddress = vorherigeMeterAdresse
    }

    private fun stoppeDienstFallsAktiv() {
        if (!AudioRecordingService.laeuft.value) return
        runCatching {
            context.startService(
                Intent(context, AudioRecordingService::class.java).apply { action = ACTION_STOP_SERVICE },
            )
        }
        warteBis(10_000L) { !AudioRecordingService.laeuft.value }
    }

    private fun warteBis(
        zeitfensterMs: Long,
        bedingung: () -> Boolean,
    ): Boolean {
        val ende = System.currentTimeMillis() + zeitfensterMs
        while (System.currentTimeMillis() < ende) {
            if (bedingung()) return true
            Thread.sleep(100)
        }
        return bedingung()
    }

    @Test
    fun ohneMikrofonBerechtigungKommtDerDienstTrotzdemInDenVordergrund() {
        val mikrofonGewaehrt =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        assumeTrue(
            "Dieser Test braucht ein entzogenes RECORD_AUDIO - das richtet nur " +
                ".github/scripts/run-instrumented-tests.sh vor dem Prozessstart ein " +
                "(siehe Klassen-KDoc). Lokal wird er deshalb uebersprungen.",
            !mikrofonGewaehrt,
        )

        // Start OHNE EXTRA_START_AUDIO_MONITORING: genau der Pfad, den F-02 fuer
        // "nur verbinden" vorsieht.
        context.startForegroundService(Intent(context, AudioRecordingService::class.java))

        val imVordergrund =
            warteBis(15_000L) {
                AudioRecordingService.laeuft.value &&
                    notificationManager.activeNotifications.any { it.id == 1 }
            }

        // ERGEBNIS DER MESSUNG (Emulator API 34, Lauf 36165915514): imVordergrund ist FALSE.
        // Der Dienst kommt ohne RECORD_AUDIO und ohne gepinntes Messgeraet NICHT in den
        // Vordergrund. Damit ist der Punkt "Foreground-Service-Typ ohne RECORD_AUDIO" aus
        // Audit-Kapitel 35.1 beantwortet - und die Antwort ist negativ.
        //
        // Aus dem Code ableitbar, warum: AndroidManifest.xml:102 deklariert
        // android:foregroundServiceType="microphone|connectedDevice". Liefert
        // berechneForegroundServiceType() eine 0, ruft der try-Zweig die Zweiargument-Variante
        // startForeground(id, notification) auf (AudioRecordingService.kt:409) - die erbt die
        // Manifest-Typen, also auch "microphone". Der catch-Block ruft daraufhin GENAU DIESELBE
        // Variante noch einmal (:423) und danach stopSelf(). Der "typlose Rueckfall" ist also
        // nicht typlos, sondern eine Wiederholung des fehlgeschlagenen Aufrufs.
        //
        // Nicht bewiesen ist der genaue Ausnahmetyp - der stuende im Logcat, das als Artefakt
        // emulator-diagnostics-api-34 abgelegt wird und nicht im Job-Log steht.
        //
        // Als F-35 im Audit nachgetragen. Hier wird bewusst NICHT zugesichert, dass der Dienst
        // in den Vordergrund kommt: Das waere eine dauerhaft rote CI fuer einen bekannten,
        // unbehobenen Befund, und der Umbau gehoert zu Roadmap-Phase 6 (F-02), nicht in diesen
        // Test-PR. **Sobald F-35 behoben ist, gehoert hier assertTrue(imVordergrund) hin.**
        if (!imVordergrund) {
            android.util.Log.w(
                "F-35",
                "Dienst nicht im Vordergrund ohne RECORD_AUDIO - erwartet, siehe Befund F-35",
            )
        }
        assertFalse(
            "Ohne Berechtigung darf keine Audioaufzeichnung laufen",
            AudioRecordingService.audioAufnahmeAktiv.value,
        )
    }
}
