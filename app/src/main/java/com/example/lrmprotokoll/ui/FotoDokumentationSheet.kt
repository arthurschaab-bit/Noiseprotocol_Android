package com.example.lrmprotokoll.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.FotoKategorie
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aufforderung zur Fotodokumentation nach dem Start eines Messvorgangs (M11 Etappe A).
 *
 * **Die Reihenfolge ist nicht verhandelbar: erst messen, dann fotografieren.** Ein Dialog VOR
 * dem Start wuerde bedeuten, dass genau der laute Moment, wegen dessen der Nutzer zum Handy
 * greift, nicht aufgezeichnet wird, waehrend er die Kamera bedient. Dieses Sheet erscheint
 * deshalb erst, nachdem die Messung bereits laeuft.
 *
 * Die Kamera wird ueber [ActivityResultContracts.TakePicture] geoeffnet - also die
 * System-Kamera-App, nicht CameraX. Fuer ein Belegfoto braucht niemand eine eigene
 * Kameraoberflaeche, und es kostet keine neue Abhaengigkeit.
 *
 * **`android.permission.CAMERA` wird bewusst NICHT deklariert.** Ein Intent an eine fremde
 * Kamera-App braucht die Berechtigung nicht - sobald eine App sie aber im Manifest deklariert,
 * verlangt Android, dass sie auch gewaehrt ist. Man handelt sich damit einen
 * Berechtigungsdialog ein, den man sonst gar nicht braeuchte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FotoDokumentationSheet(
    sessionId: Long,
    onFertig: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val fotoDoku = remember { container.fotoDokumentation }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val kategorien = remember { fotoDoku.abzufragendeKategorien() }
    var offeneKategorie by remember { mutableStateOf<FotoKategorie?>(null) }
    var rohdatei by remember { mutableStateOf<File?>(null) }
    var notiz by remember { mutableStateOf("") }
    val gezaehlt = remember { mutableStateOf(mapOf<FotoKategorie, Int>()) }

    val kameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { erfolgreich ->
        val kategorie = offeneKategorie
        val datei = rohdatei
        offeneKategorie = null
        rohdatei = null
        if (!erfolgreich || kategorie == null || datei == null) {
            kategorie?.let { fotoDoku.meldeUebersprungen(it) }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                fotoDoku.uebernehmeAufnahme(datei, sessionId, kategorie, notiz.takeIf { it.isNotBlank() })
            }
            if (id != null) {
                gezaehlt.value = gezaehlt.value + (kategorie to (gezaehlt.value[kategorie] ?: 0) + 1)
                notiz = ""
            }
        }
    }

    LaunchedEffect(sessionId) {
        gezaehlt.value = withContext(Dispatchers.IO) {
            fotoDoku.fuerSession(sessionId).groupingBy { FotoKategorie.vonName(it.kategorie) }.eachCount()
        }
    }

    ModalBottomSheet(onDismissRequest = onFertig, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Fotodokumentation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Die Messung läuft bereits. Ein Foto vom Messaufbau belegt später, wie und wo gemessen wurde.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = notiz,
                onValueChange = { notiz = it },
                label = { Text("Notiz zum nächsten Foto (optional)") },
                placeholder = { Text("z. B. Messgerät 1,5 m über Boden") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            kategorien.forEach { kategorie ->
                val anzahl = gezaehlt.value[kategorie] ?: 0
                val maximum = container.settingsManager.fotoDokuMaxProKategorie
                val pflicht = fotoDoku.umfangFuer(kategorie).name == "PFLICHT"

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = anzahl < maximum,
                        onClick = {
                            val datei = fotoDoku.neueZieldatei(sessionId, kategorie)
                            val uri: Uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", datei,
                            )
                            offeneKategorie = kategorie
                            rohdatei = datei
                            // Kein resolveActivity(): Das liefert ab targetSdk 30 ohne
                            // <queries>-Eintrag null, auch wenn eine Kamera-App vorhanden ist.
                            // Stattdessen den Fehlschlag beim Start abfangen.
                            runCatching { kameraLauncher.launch(uri) }.onFailure {
                                offeneKategorie = null
                                rohdatei = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${kategorie.anzeigename} ($anzahl/$maximum)")
                    }
                    if (pflicht && anzahl == 0) {
                        Text(
                            "empfohlen",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    // Auslassungen festhalten - besonders relevant bei "PFLICHT". Blockiert wird
                    // die Messung dabei nie: Sie laeuft ohnehin schon.
                    kategorien.filter { (gezaehlt.value[it] ?: 0) == 0 }.forEach { fotoDoku.meldeUebersprungen(it) }
                    onFertig()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (gezaehlt.value.values.sum() == 0) "Ohne Foto fortfahren" else "Fertig")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
