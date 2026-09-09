package com.example.lrmprotokoll.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ueberbrueckt einen von aussserhalb von Compose (Notification-Aktion, siehe
 * [com.example.lrmprotokoll.audio.EXTRA_REQUEST_STOP_CONFIRMATION]) angestossenen Wunsch, den
 * bestehenden "Messung wirklich beenden?"-Dialog im Cockpit einzublenden. `MainActivity` setzt
 * das Flag beim Empfang der zugehoerigen Intent-Extra, `LiveCockpitCard` beobachtet es und
 * quittiert es sofort wieder - ein einfacher StateFlow statt eines Navigation-Arguments, weil der
 * Ausloeser (onCreate/onNewIntent der Activity) und der Ort des Dialogs (ein eigenstaendiges
 * Composable in einer anderen Datei) nicht ueber denselben Navigations-Callstack verbunden sind.
 */
object PendingUiAction {
    private val _stopConfirmationRequested = MutableStateFlow(false)
    val stopConfirmationRequested: StateFlow<Boolean> = _stopConfirmationRequested.asStateFlow()

    fun requestStopConfirmation() {
        _stopConfirmationRequested.value = true
    }

    fun consumeStopConfirmationRequest() {
        _stopConfirmationRequested.value = false
    }
}
