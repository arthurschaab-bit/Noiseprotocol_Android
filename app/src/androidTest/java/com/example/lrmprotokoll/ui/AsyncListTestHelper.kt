package com.example.lrmprotokoll.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/** Room-Flows laufen auf IO; Compose-Idle garantiert noch keine erste Datenemission. */
internal fun ComposeTestRule.warteUndScrolleZu(matcher: SemanticsMatcher) {
    waitUntil(timeoutMillis = 10_000) {
        try {
            onNodeWithTag("home_lazy_column").performScrollToNode(matcher)
            true
        } catch (_: AssertionError) {
            false
        }
    }
}
