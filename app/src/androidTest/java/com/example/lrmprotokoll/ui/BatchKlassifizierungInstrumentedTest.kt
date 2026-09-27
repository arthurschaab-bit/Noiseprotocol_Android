package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BatchKlassifizierungInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun laufenderBatchSperrtBeideMenueaktionenUndZeigtFortschritt() {
        val aufrufe = AtomicInteger()
        val gestartet = CompletableDeferred<Unit>()
        val fortsetzen = CompletableDeferred<Unit>()
        composeRule.setContent {
            NoiseProtocolApp(
                onNavigateToPlayer = {},
                onNavigateToSettings = {},
                onNavigateToMeter = {},
                onNavigateToProtokoll = {},
                onNavigateToDiagnose = {},
                onNavigateToVideo = {},
                batchClassifyOverride = { _, onFortschritt ->
                    aufrufe.incrementAndGet()
                    onFortschritt(0, 1)
                    gestartet.complete(Unit)
                    fortsetzen.await()
                    onFortschritt(1, 1)
                    0
                },
            )
        }

        try {
            composeRule.onNodeWithTag("btn_overflow_menu").performClick()
            composeRule.onNodeWithTag("menu_item_ai_batch").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { gestartet.isCompleted }
            composeRule.onNodeWithTag("ai_batch_progress").assertIsDisplayed()

            composeRule.onNodeWithTag("btn_overflow_menu").performClick()
            composeRule.onNodeWithTag("menu_item_ai_batch").assertIsNotEnabled()
            composeRule.onNodeWithTag("menu_item_ai_reevaluate").assertIsNotEnabled()
            assertEquals(1, aufrufe.get())
        } finally {
            fortsetzen.complete(Unit)
        }
    }
}
