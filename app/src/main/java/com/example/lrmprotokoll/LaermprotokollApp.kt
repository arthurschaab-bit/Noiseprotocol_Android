package com.example.lrmprotokoll

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.lrmprotokoll.diagnose.DiagnosticRedactor
import com.example.lrmprotokoll.diagnose.acra.AcraConfig
import io.sentry.android.core.SentryAndroid
import org.acra.ACRA

class LaermprotokollApp : Application() {

    lateinit var container: AppContainer
        internal set

    /**
     * Testseam (M12 Schritt 1): [ACRA.isACRASenderServiceProcess] liest den echten
     * Betriebssystem-Prozessnamen - in einem Robolectric-Test laeuft alles im selben Prozess,
     * das laesst sich nicht faken. `null` (Standard) verwendet die echte ACRA-Abfrage.
     */
    internal var acraSenderProcessOverride: Boolean? = null

    fun setCustomContainer(customContainer: AppContainer) {
        if (::container.isInitialized) container.close()
        container = customContainer
    }

    fun resetContainer() {
        if (::container.isInitialized) container.close()
        container = AppContainer(this)
    }

    /**
     * `instance::container.isInitialized` liesse sich von ausserhalb dieser Klasse nicht
     * aufrufen (Kotlin verlangt fuer die isInitialized-Pruefung eines lateinit-Felds direkten
     * Backing-Field-Zugriff, der nur innerhalb der deklarierenden Klasse besteht) - deshalb hier
     * als schmaler Zugriffspunkt fuer Tests (Schritt 1 Akzeptanzkriterium "Der zweite Prozess
     * baut nachweislich keinen AppContainer auf").
     */
    internal fun isContainerInitialized(): Boolean = ::container.isInitialized

    /**
     * ACRA verlangt Initialisierung hier, nicht in [onCreate] - harte Anforderung der
     * Bibliothek (Konzept 4.1), keine Stilfrage. Sichtbarkeit auf `public` erweitert (Kotlin
     * erlaubt das Weiten einer Override-Sichtbarkeit), damit Tests die Reihenfolge
     * attachBaseContext() -> onCreate() an einer eigenen Instanz nachstellen koennen, ohne auf
     * die von Robolectric automatisch gebaute Singleton-Applikation angewiesen zu sein.
     */
    public override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initAcra()
    }

    override fun onCreate() {
        super.onCreate()
        // Prozess-Weiche (Konzept 4.1): ACRA startet den Sender in einem eigenen Prozess
        // (":acra"). Application.onCreate() laeuft dort erneut - ohne diese Weiche wuerde
        // AppContainer dort ein zweites Mal aufgebaut: zweite Room-Instanz, zweiter
        // BLE-Transport, zweiter OkHttp-Pool, auf einem Geraet, das eventuell gerade wegen
        // Speichermangels abgestuerzt ist.
        if (isAcraSenderProcess()) return
        initSentry()
        container = AppContainer(this)
        // Aufgabe 5 (Konzept 4.3): die Ringdatei wird genau einmal beim Start beschnitten,
        // falls sie durch einen frueheren Fehler die Obergrenze ueberschreitet.
        container.breadcrumbRingFile.beimStartBeschneiden()
        // M12 Schritt 3 (Konzept 6): die Auswertung liegt jetzt in ProcessExitCollector, nicht
        // mehr inline hier - Verhalten bleibt sonst gleich (Breadcrumb + Report bei CRASH/ANR),
        // zusaetzlich jetzt ALLE neuen Eintraege statt nur dem letzten, plus ANR-Thread-Dump und
        // natives Tombstone.
        container.processExitCollector.auswerten()
        // O-8 (Konzept 8a): nach processExitCollector, damit ein beim Neustart nachgeholtes
        // ANR-Bundle auch einen frisch gesicherten System-ANR-Trace (ab Android 11) mitnimmt.
        container.anrWatchdogCoordinator.ausstehendesBundleNachholen()
        if (!laeuftUnterRobolectric()) container.anrWatchdog.start()
        // M12 Schritt 6 (Konzept Schritt 6 Aufgabe 1): immer geplant, der Abschalter wirkt im
        // Coordinator bei jedem Lauf (siehe SupportBundleHealthPlanung-KDoc).
        com.example.lrmprotokoll.diagnose.export.SupportBundleHealthPlanung.plane(this)
    }

    /**
     * O-8: Unter Robolectric laeuft der Main-Looper nur, wenn ein Test ihn antreibt - der
     * ANR-Watchdog meldete dort in jedem laenger laufenden Test einen Haenger, und weil
     * Robolectric die Application fuer jeden Test neu baut, bliebe pro Test ein Thread zurueck.
     * Robolectric setzt `Build.FINGERPRINT` fest auf "robolectric"; auf Geraet und Emulator
     * (auch in den instrumentierten Tests) laeuft der Watchdog.
     */
    internal fun laeuftUnterRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"

    internal fun isAcraSenderProcess(): Boolean =
        acraSenderProcessOverride ?: runCatching { ACRA.isACRASenderServiceProcess() }.getOrDefault(false)

    private fun initAcra() {
        runCatching {
            ACRA.init(this, AcraConfig.build())
        }.onFailure {
            Log.w("LaermprotokollApp", "ACRA konnte nicht initialisiert werden", it)
        }
    }

    private fun initSentry() {
        if (!BuildConfig.DIAGNOSTICS_REMOTE_ENABLED) return
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return

        runCatching {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.environment = BuildConfig.BUILD_TYPE
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.isSendDefaultPii = false
                options.isEnableUserInteractionTracing = false
                options.isEnableAutoSessionTracking = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false

                options.setBeforeSend { event, _ ->
                    event.message?.formatted?.let {
                        event.message?.formatted = DiagnosticRedactor.redactString(it)
                    }
                    event
                }
            }
        }.onFailure {
            Log.w("LaermprotokollApp", "Sentry konnte nicht initialisiert werden", it)
        }
    }
}
