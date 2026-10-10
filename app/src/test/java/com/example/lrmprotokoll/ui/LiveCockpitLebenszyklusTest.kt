package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.example.lrmprotokoll.AppContainer
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.MeasurementEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.meter.FakeMeterTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Befund 3 (docs/BEFUNDE_BUNDLES_2026-10-10.md): Das Cockpit darf die Messwerte nur laden, solange
 * es sichtbar ist (mindestens STARTED). Vorher lud es bei gesperrtem Bildschirm alle 5 s das
 * ganze 4-h-Fenster neu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveCockpitLebenszyklusTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: LaermprotokollApp

    /** Handgeschriebener LifecycleOwner, dessen Zustand der Test selbst setzt. */
    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.setCustomContainer(AppContainer(app, FakeMeterTransport()))
    }

    @After fun cleanup() {
        app.resetContainer()
    }

    @Test fun messwerteWerdenErstGeladenWennDasCockpitSichtbarIst() {
        // Weit in der Zukunft wie in MicrophoneCockpitRegressionTest, damit diese Session die
        // letzte in der testuebergreifend geteilten Datenbank ist.
        val start = 9_100_000_000_000L
        runBlocking {
            val sessionId =
                app.container.database.sessionDao().insert(
                    SessionEntity(
                        startedAt = start,
                        endedAt = start + 60_000L,
                        deviceAddress = "AA:BB:CC:DD:EE:FF",
                        deviceName = "PCE-323",
                        weighting = "A",
                        timeWeighting = null,
                    ),
                )
            app.container.database.measurementDao().insertAll(
                (0 until 20).map { i ->
                    MeasurementEntity(
                        sessionId = sessionId,
                        timestamp = start + i * 500L,
                        levelDb = 77.7,
                        weighting = "A",
                        flags = 0,
                    )
                },
            )
        }

        val owner = TestLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LiveCockpitCard(modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
        composeRule.waitForIdle()

        // Gestoppt (Bildschirm gesperrt): keine Messwerte geladen, also keine Kennwerte.
        composeRule.onAllNodesWithText("77.7 dB").assertCountEquals(0)

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("77.7 dB").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
