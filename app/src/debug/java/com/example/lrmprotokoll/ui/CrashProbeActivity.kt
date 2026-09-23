package com.example.lrmprotokoll.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.ui.theme.LaermprotokollTheme

/**
 * Isolierter Prozess (`:crashprobe`, siehe `app/src/debug/AndroidManifest.xml`) fuer die echten
 * Absturztests (M12 Schritt 8 CI-Fund 22.09.2026, PR #182).
 *
 * Rendert bewusst NUR [CrashTriggerButtons], nicht den gesamten [DiagnoseScreen]: der Test
 * (`CrashDiagnoseInstrumentedTest`) muss die drei Ausloeser-Buttons per UiAutomator finden
 * koennen, ohne durch die sehr hohe LazyColumn-Sektion von DiagnoseScreen scrollen zu muessen -
 * genau das scheiterte zuverlaessig (siehe [CrashTriggerButtons]-KDoc fuer die Begruendung).
 */
class CrashProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LaermprotokollTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    CrashTriggerButtons()
                }
            }
        }
    }
}
