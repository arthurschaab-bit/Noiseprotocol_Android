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
    fun derLivePegelEinesEchtenFramesIstAlsLiveRegionAusgezeichnet() {
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

        // 1. Der Wert des empfangenen Frames erscheint tatsaechlich im Cockpit. Nur die
        // Vorkommastellen pruefen - die Nachkommastelle haengt am Locale-Trennzeichen.
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("73", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("73", substring = true).onFirst().assertIsDisplayed()

        // 2. Genau ein Knoten traegt die LiveRegion-Auszeichnung (LiveCockpitCard setzt sie mit
        // mergeDescendants auf den Block um Pegel, Einheit und Einordnungstext).
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
