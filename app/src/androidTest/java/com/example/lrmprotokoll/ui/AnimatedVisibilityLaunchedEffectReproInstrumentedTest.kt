package com.example.lrmprotokoll.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lrmprotokoll.messreihe.SpeicherplatzUebersicht
import com.example.lrmprotokoll.messreihe.ermittleSpeicherplatz
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimale Reproduktion fuer PR #150 / Checkliste F6: bildet NUR die exakte Struktur nach, die in
 * SettingsScreen.kt bei der Speicherplatz-Karte haengt (AnimatedVisibility(expanded) mit einem
 * LaunchedEffect(expanded), das ermittleSpeicherplatz() aufruft) - ohne den Rest von SettingsScreen
 * (kein connectionSupervisor.state, keine anderen 100+ States/Effects). ermittleSpeicherplatz()
 * selbst ist laut SpeicherplatzUebersichtInstrumentedTest isoliert nachweislich schnell (<2s).
 * Zweck: eingrenzen, ob das Haengen an AnimatedVisibility+LaunchedEffect+echtem Suspend-Aufruf
 * grundsaetzlich liegt, oder an etwas Spezifischem in SettingsScreens uebriger Komposition.
 */
@RunWith(AndroidJUnit4::class)
class AnimatedVisibilityLaunchedEffectReproInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun launchedEffectInAnimatedVisibilityLiefertErgebnisNachDemAufklappen() {
        composeRule.setContent {
            var expanded by remember { mutableStateOf(false) }
            var speicherplatz by remember { mutableStateOf<SpeicherplatzUebersicht?>(null) }
            val context = LocalContext.current

            Column {
                Text(
                    "Repro-Titel",
                    modifier = androidx.compose.ui.Modifier.clickable { expanded = !expanded },
                )
                AnimatedVisibility(visible = expanded) {
                    LaunchedEffect(expanded) {
                        if (expanded) speicherplatz = ermittleSpeicherplatz(context)
                    }
                    speicherplatz?.let {
                        Text("Audiodateien: ${it.audioBytes}")
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Repro-Titel").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Audiodateien:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Audiodateien:", substring = true).assertIsDisplayed()
    }
}
