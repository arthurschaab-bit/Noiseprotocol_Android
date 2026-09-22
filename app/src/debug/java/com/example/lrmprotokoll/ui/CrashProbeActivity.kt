package com.example.lrmprotokoll.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Zeigt den echten Diagnose-Screen in einem vom Test-Runner getrennten Prozess. */
class CrashProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DiagnoseScreen(onBack = { finish() }) }
    }
}
