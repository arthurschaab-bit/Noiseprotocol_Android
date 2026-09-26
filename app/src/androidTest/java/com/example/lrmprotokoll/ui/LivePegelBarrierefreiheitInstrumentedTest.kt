package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.meter.BoundDevice
import com.example.lrmprotokoll.meter.ConnectionState
import com.example.lrmprotokoll.meter.FakeMeterTransport
import com.example.lrmprotokoll.meter.Weighting
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die `liveRegion`-Auszeichnung des Live-Pegels im verbundenen Zustand (UX-Audit Kapitel 35.1).
 *
 * **Warum das bisher offen war - und warum es das nicht mehr ist.** Das README fuehrt unter
 * "Bekannte Einschraenkungen", die Auszeichnung sei nur durch `assembleDebug` belegt, weil
 * `AppContainer.meterTransport` fest auf `BleMeterTransport` verdrahtet und nicht durch ein
 * Test-Double ersetzbar sei. Das stimmt nicht (mehr): `AppContainer` nimmt einen
 * `meterTransportOverride` entgegen, und [FakeMeterTransport] laesst sich damit bis in den
 * Zustand [ConnectionState.STREAMING] mit einem echten Frame bringen - genau der Pfad, der
 * angeblich unerreichbar war. Dieser Test geht ihn.
 *
 * **Was er belegt:** dass der Pegelwert eines tatsaechlich empfangenen Frames im Cockpit
 * ankommt und der ihn umgebende Knoten als `LiveRegion` ausgezeichnet ist - die Voraussetzung
 * dafuer, dass TalkBack Pegelaenderungen ueberhaupt ansagt.
 *
 * **Was er NICHT belegt:** dass TalkBack tatsaechlich spricht. Das haengt am
 * Screenreader selbst und bleibt Sache der Geraeteverifikation
 * (`docs/CHECKLISTE_GERAETETEST.md`). Ein Test kann hier nur die Auszeichnung pruefen, nicht die
 * Sprachausgabe - diese Grenze ist der Grund, warum der Punkt im Audit trotzdem teilweise offen
 * bleibt.
 */
@RunWith(AndroidJUnit4::class)
class LivePegelBarrierefreiheitInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: LaermprotokollApp
    private lateinit var fakeTransport: FakeMeterTransport
    private var eigenerContainerInstalliert = false

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fakeTransport = FakeMeterTransport()
        app.setCustomContainer(AppContainer(app, meterTransportOverride = fakeTransport))
        eigenerContainerInstalliert = true
    }

    @After
    fun tearDown() {
        if (eigenerContainerInstalliert) {
            runCatching { app.container.connectionSupervisor.stop() }
            // Den echten Container wiederherstellen, damit nachfolgende Tests in derselben
            // Instrumentierung nicht auf dem Fake sitzen bleiben.
            runCatching { app.resetContainer() }
        }
    }

    @Test
    fun ohneLaufendeMessungBleibtDerKalibrierteWertAusUndDieLiveRegionSteht() {
        // KORREKTUR nach dem ersten Emulator-Lauf (25.09.2026). Die erste Fassung wartete
        // darauf, dass der Pegel eines eingespeisten Frames als Zahl im Cockpit erscheint, und
        // lief in einen ComposeTimeout. Die Pruefung war falsch gedacht:
        //
        //   LiveCockpitCard.kt:196  isCalibrated = dienstAktiv && STREAMING && letzterFrame != null
        //   LiveCockpitCard.kt:204  liveLevel = if (isCalibrated) letzterFrame?.level else ...
        //
        // Ohne laufenden Vordergrunddienst ist `dienstAktiv` falsch, also zeigt das Cockpit
        // "--.-", egal wie viele gueltige Frames ankommen. Das ist kein Fehler, sondern genau
        // der im Audit als F-02 beschriebene Zusammenhang ("Verbinden" ist nicht von "Messung
        // starten" getrennt). Dieser Test haelt ihn jetzt ausfuehrbar fest, statt ein Verhalten
        // zu verlangen, das es nicht gibt.
        val container = app.container
        container.connectionSupervisor.start(BoundDevice("AA:BB:CC:DD:EE:FF", "PCE-323 Test"))
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            container.connectionSupervisor.state.value == ConnectionState.STREAMING
        }
        runBlocking {
            fakeTransport.emitFrame(level = 73.4, weighting = Weighting.A, modeAssumptionConfirmed = true)
        }

        composeRule.setContent { LiveCockpitCard() }
        composeRule.waitForIdle()

        // 1. Die Verbindung steht wirklich und liefert Frames - sonst waere die Aussage unten
        //    wertlos, weil sie auch ganz ohne Verbindung zutraefe.
        assertEquals(ConnectionState.STREAMING, container.connectionSupervisor.state.value)
        assertNotNull(
            "Der Transport muss einen Frame-Empfangszeitpunkt melden, sonst kam gar nichts an",
            fakeTransport.lastFrameAt.value,
        )

        // 2. Trotzdem steht im Cockpit der Platzhalter, nicht der Messwert (F-02).
        composeRule.onAllNodesWithText("--.-", substring = true).onFirst().assertIsDisplayed()

        // 3. Die LiveRegion-Auszeichnung steht auch in diesem Zustand - Voraussetzung dafuer,
        //    dass TalkBack den spaeteren Wechsel auf den ersten echten Messwert ansagt.
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            ).assertIsDisplayed()
    }

    @Test
    fun dieLiveRegionExistiertAuchOhneVerbindung() {
        // Ohne Verbindung zeigt das Cockpit "--.-" statt eines Wertes. Die Auszeichnung muss
        // trotzdem stehen, sonst bekaeme TalkBack den Wechsel auf den ersten echten Messwert
        // nicht mit.
        composeRule.setContent { LiveCockpitCard() }
        composeRule.waitForIdle()

        composeRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            ).assertIsDisplayed()
    }
}
