package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.report.GesamtberichtStammdaten
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stammdaten-Dialog für den Gesamtbericht (Messgerät, Messaufbau, Randbedingungen) - erscheint
 * beim Messbeginn, genau wie [FotoDokumentationSheet] (dieselbe `offeneSessionFlow()`-Erkennung
 * in `MainActivity`), damit diese Angaben zu jeder Messung passen statt fest in den Einstellungen
 * zu stehen (Owner-Anfrage 10.09.2026: "die ganzen Settings sollen vom User ausgefüllt werden ...
 * Merke die letzten 10 Einstellungen ... stelle default erstmal den letzten ein").
 *
 * Die letzten (bis zu) 10 gespeicherten Einträge ([StammdatenVerlaufEntity], siehe
 * `StammdatenVerlaufDao`) stehen über "Andere gespeicherte Angaben" als Auswahl zur Verfügung;
 * beim Öffnen ist automatisch der zuletzt gespeicherte vorausgefüllt. "Wetter automatisch
 * abrufen" und "Standort ermitteln" füllen ihre Felder aus dem zuletzt bekannten Standort
 * ([com.example.lrmprotokoll.standort.StandortErmittlung]) bzw. einer Wetter-API
 * ([com.example.lrmprotokoll.wetter.WetterProvider]) - beides bleibt danach normaler,
 * editierbarer Text, ein Fehlschlag (kein Standort, kein Netz) blockiert das Speichern nicht.
 *
 * "Überspringen" schließt ohne zu speichern - der Verlauf bleibt unverändert, der nächste Dialog
 * schlägt wieder denselben letzten Eintrag vor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GesamtberichtStammdatenSheet(
    sessionId: Long,
    onFertig: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zeitFormat = remember { SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()) }

    var verlauf by remember { mutableStateOf<List<StammdatenVerlaufEntity>>(emptyList()) }
    var auswahlOffen by remember { mutableStateOf(false) }

    var geraetHersteller by remember { mutableStateOf("") }
    var geraetTyp by remember { mutableStateOf("") }
    var geraetGenauigkeitsklasse by remember { mutableStateOf("") }
    var geraetSeriennummer by remember { mutableStateOf("") }
    var geraetKalibrierung by remember { mutableStateOf("") }
    var messort by remember { mutableStateOf("") }
    var mikrofonposition by remember { mutableStateOf("") }
    var mikrofonhoehe by remember { mutableStateOf("") }
    var entfernungZurQuelle by remember { mutableStateOf("") }
    var innenAussen by remember { mutableStateOf("") }
    var fensterzustand by remember { mutableStateOf("") }
    var wetter by remember { mutableStateOf("") }
    var datenqualitaetHinweis by remember { mutableStateOf("") }

    var standortLaedt by remember { mutableStateOf(false) }
    var wetterLaedt by remember { mutableStateOf(false) }
    var hinweis by remember { mutableStateOf<String?>(null) }
    var pendingAktion by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun uebernehmen(eintrag: StammdatenVerlaufEntity) {
        geraetHersteller = eintrag.geraetHersteller
        geraetTyp = eintrag.geraetTyp
        geraetGenauigkeitsklasse = eintrag.geraetGenauigkeitsklasse
        geraetSeriennummer = eintrag.geraetSeriennummer
        geraetKalibrierung = eintrag.geraetKalibrierung
        messort = eintrag.messort
        mikrofonposition = eintrag.mikrofonposition
        mikrofonhoehe = eintrag.mikrofonhoehe
        entfernungZurQuelle = eintrag.entfernungZurQuelle
        innenAussen = eintrag.innenAussen
        fensterzustand = eintrag.fensterzustand
        wetter = eintrag.wetter
        datenqualitaetHinweis = eintrag.datenqualitaetHinweis
    }

    LaunchedEffect(sessionId) {
        val letzte = withContext(Dispatchers.IO) { container.database.stammdatenVerlaufDao().letzte(10) }
        verlauf = letzte
        letzte.firstOrNull()?.let { uebernehmen(it) }
    }

    val standortBerechtigungsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erlaubt ->
        val aktion = pendingAktion
        pendingAktion = null
        if (erlaubt && aktion != null) {
            aktion()
        } else if (!erlaubt) {
            standortLaedt = false
            wetterLaedt = false
            hinweis = "Ohne Standort-Berechtigung nicht möglich"
        }
    }

    fun mitStandortBerechtigung(aktion: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            aktion()
        } else {
            pendingAktion = aktion
            standortBerechtigungsLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun standortErmitteln() {
        mitStandortBerechtigung {
            standortLaedt = true
            scope.launch {
                val standort = withContext(Dispatchers.IO) { container.standortErmittlung.aktuellerStandort() }
                if (standort == null) {
                    standortLaedt = false
                    hinweis = "Kein Standort verfügbar"
                    return@launch
                }
                val adresse = withContext(Dispatchers.IO) { container.standortErmittlung.adresseFuer(standort) }
                standortLaedt = false
                if (adresse != null) {
                    messort = adresse
                } else {
                    messort = "%.5f, %.5f".format(Locale.getDefault(), standort.breitengrad, standort.laengengrad)
                    hinweis = "Adresse konnte nicht aufgelöst werden - Koordinaten eingetragen"
                }
            }
        }
    }

    fun wetterAbrufen() {
        mitStandortBerechtigung {
            wetterLaedt = true
            scope.launch {
                val standort = withContext(Dispatchers.IO) { container.standortErmittlung.aktuellerStandort() }
                if (standort == null) {
                    wetterLaedt = false
                    hinweis = "Kein Standort für die Wetterabfrage verfügbar"
                    return@launch
                }
                val ergebnis = container.wetterProvider.aktuelleWetterlage(standort.breitengrad, standort.laengengrad)
                wetterLaedt = false
                ergebnis.onSuccess { wetter = it.alsKurztext() }
                    .onFailure { hinweis = "Wetterdienst nicht erreichbar" }
            }
        }
    }

    fun speichern() {
        val stammdaten = GesamtberichtStammdaten(
            geraetHersteller = geraetHersteller,
            geraetTyp = geraetTyp,
            geraetGenauigkeitsklasse = geraetGenauigkeitsklasse,
            geraetSeriennummer = geraetSeriennummer,
            geraetKalibrierung = geraetKalibrierung,
            messort = messort,
            mikrofonposition = mikrofonposition,
            mikrofonhoehe = mikrofonhoehe,
            entfernungZurQuelle = entfernungZurQuelle,
            innenAussen = innenAussen,
            fensterzustand = fensterzustand,
            wetter = wetter,
            datenqualitaetHinweis = datenqualitaetHinweis,
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                container.database.stammdatenVerlaufDao().insert(stammdaten.zuEntity(System.currentTimeMillis()))
            }
            onFertig()
        }
    }

    ModalBottomSheet(onDismissRequest = onFertig, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Berichtsangaben für diese Messung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Gerät, Messaufbau und Randbedingungen für den Gesamtbericht. Vorausgefüllt mit den " +
                    "zuletzt verwendeten Angaben - einfach anpassen, was abweicht.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (verlauf.size > 1) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { auswahlOffen = true }) {
                        Text("Andere gespeicherte Angaben wählen (${verlauf.size})")
                    }
                    DropdownMenu(expanded = auswahlOffen, onDismissRequest = { auswahlOffen = false }) {
                        verlauf.forEach { eintrag ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${zeitFormat.format(Date(eintrag.erstelltAm))} – " +
                                            eintrag.messort.ifBlank { "kein Messort" }
                                    )
                                },
                                onClick = { uebernehmen(eintrag); auswahlOffen = false },
                            )
                        }
                    }
                }
            }

            hinweis?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))
            Text("Messgerät", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = geraetHersteller, onValueChange = { geraetHersteller = it },
                label = { Text("Hersteller") }, modifier = Modifier.testTag("input_bericht_hersteller").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetTyp, onValueChange = { geraetTyp = it },
                label = { Text("Typ") }, modifier = Modifier.testTag("input_bericht_typ").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetGenauigkeitsklasse, onValueChange = { geraetGenauigkeitsklasse = it },
                label = { Text("Genauigkeitsklasse") }, modifier = Modifier.testTag("input_bericht_genauigkeitsklasse").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetSeriennummer, onValueChange = { geraetSeriennummer = it },
                label = { Text("Seriennummer") }, modifier = Modifier.testTag("input_bericht_seriennummer").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetKalibrierung, onValueChange = { geraetKalibrierung = it },
                label = { Text("Kalibrierung") },
                placeholder = { Text("z. B. 94 dB(A) mit Kalibrator XY; vor Messung protokolliert") },
                modifier = Modifier.testTag("input_bericht_kalibrierung").fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("Messaufbau", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = messort, onValueChange = { messort = it },
                label = { Text("Genauer Messort") }, modifier = Modifier.testTag("input_bericht_messort").fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { standortErmitteln() }, enabled = !standortLaedt) {
                    Text(if (standortLaedt) "Ermittle …" else "Standort ermitteln")
                }
                if (standortLaedt) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mikrofonposition, onValueChange = { mikrofonposition = it },
                label = { Text("Mikrofonposition") }, modifier = Modifier.testTag("input_bericht_mikrofonposition").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mikrofonhoehe, onValueChange = { mikrofonhoehe = it },
                label = { Text("Mikrofonhöhe") }, modifier = Modifier.testTag("input_bericht_mikrofonhoehe").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = entfernungZurQuelle, onValueChange = { entfernungZurQuelle = it },
                label = { Text("Entfernung Mikrofon–Quelle") }, modifier = Modifier.testTag("input_bericht_entfernung").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = innenAussen, onValueChange = { innenAussen = it },
                label = { Text("Innen-/Außenmessung") }, placeholder = { Text("z. B. Außen") },
                modifier = Modifier.testTag("input_bericht_innen_aussen").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = fensterzustand, onValueChange = { fensterzustand = it },
                label = { Text("Fenster (nur bei Innenraummessung)") }, placeholder = { Text("z. B. geschlossen") },
                modifier = Modifier.testTag("input_bericht_fensterzustand").fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("Randbedingungen", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = wetter, onValueChange = { wetter = it },
                label = { Text("Wetter") }, modifier = Modifier.testTag("input_bericht_wetter").fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { wetterAbrufen() }, enabled = !wetterLaedt) {
                    Text(if (wetterLaedt) "Rufe ab …" else "Wetter automatisch abrufen")
                }
                if (wetterLaedt) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = datenqualitaetHinweis, onValueChange = { datenqualitaetHinweis = it },
                label = { Text("Hinweis zur Datenqualität (optional)") },
                placeholder = { Text("z. B. bekannte Ursache einer Messlücke") },
                modifier = Modifier.testTag("input_bericht_datenqualitaet").fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onFertig, modifier = Modifier.weight(1f)) {
                    Text("Überspringen")
                }
                Button(onClick = { speichern() }, modifier = Modifier.weight(1f)) {
                    Text("Speichern")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
