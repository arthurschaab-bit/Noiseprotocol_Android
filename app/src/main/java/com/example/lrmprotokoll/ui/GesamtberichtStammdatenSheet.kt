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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.StammdatenVerlaufEntity
import com.example.lrmprotokoll.report.GesamtberichtStammdaten
import com.example.lrmprotokoll.report.lokalerMesstag
import com.example.lrmprotokoll.report.messtagGrenzen
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
 * "Überspringen" schließt ohne zu speichern - der Verlauf bleibt unverändert. Erst ein neuer
 * Messvorgang fragt erneut und schlägt wieder den letzten Eintrag vor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GesamtberichtStammdatenSheet(
    sessionId: Long,
    onFertig: () -> Unit,
    /** Nur bei Nacherfassung: Messtag statt des heutigen Erfassungsdatums zuordnen. */
    giltFuerTagStart: Long? = null,
    messvorgangId: Long? = null,
) {
    val context = LocalContext.current
    val speicherFehler = stringResource(R.string.report_metadata_save_error)
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zeitFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    var verlauf by remember { mutableStateOf<List<StammdatenVerlaufEntity>>(emptyList()) }
    var zeitgebundeneQuelle by remember { mutableStateOf<StammdatenVerlaufEntity?>(null) }
    var auswahlOffen by remember { mutableStateOf(false) }

    var bereitsInitialisiert by rememberSaveable(sessionId) { mutableStateOf(false) }

    var geraetHersteller by rememberSaveable { mutableStateOf("") }
    var geraetTyp by rememberSaveable { mutableStateOf("") }
    var geraetGenauigkeitsklasse by rememberSaveable { mutableStateOf("") }
    var geraetSeriennummer by rememberSaveable { mutableStateOf("") }
    var geraetKalibrierung by rememberSaveable { mutableStateOf("") }
    var messort by rememberSaveable { mutableStateOf("") }
    var mikrofonposition by rememberSaveable { mutableStateOf("") }
    var mikrofonhoehe by rememberSaveable { mutableStateOf("") }
    var entfernungZurQuelle by rememberSaveable { mutableStateOf("") }
    var innenAussen by rememberSaveable { mutableStateOf("") }
    var fensterzustand by rememberSaveable { mutableStateOf("") }
    var wetter by rememberSaveable { mutableStateOf("") }
    var datenqualitaetHinweis by rememberSaveable { mutableStateOf("") }

    var standortLaedt by remember { mutableStateOf(false) }
    var wetterLaedt by remember { mutableStateOf(false) }
    var hinweis by remember { mutableStateOf<String?>(null) }
    var speichert by remember { mutableStateOf(false) }
    var pendingAktion by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun uebernehmen(eintrag: StammdatenVerlaufEntity) {
        zeitgebundeneQuelle = eintrag
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

    LaunchedEffect(sessionId, giltFuerTagStart) {
        val zone = ZoneId.systemDefault()
        val tag = giltFuerTagStart?.let { lokalerMesstag(it, zone) } ?: LocalDate.now(zone)
        val (von, bis) = messtagGrenzen(tag, zone)
        val (letzte, fuerTag) =
            withContext(Dispatchers.IO) {
                val dao = container.database.stammdatenVerlaufDao()
                dao.letzte(10) to dao.fuerTag(von, bis).firstOrNull()
            }
        verlauf = letzte
        val quelle = fuerTag ?: letzte.firstOrNull()
        zeitgebundeneQuelle = quelle
        if (!bereitsInitialisiert) {
            quelle?.let {
                uebernehmen(it)
                if (fuerTag == null) {
                    geraetKalibrierung = ""
                    wetter = ""
                    datenqualitaetHinweis = ""
                }
            }
            bereitsInitialisiert = true
        }
    }

    val standortBerechtigungsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erlaubt ->
        val aktion = pendingAktion
        pendingAktion = null
        if (erlaubt && aktion != null) {
            aktion()
        } else if (!erlaubt) {
            standortLaedt = false
            wetterLaedt = false
            hinweis = context.getString(R.string.report_metadata_err_no_location_permission)
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
                    hinweis = context.getString(R.string.report_metadata_err_no_location_available)
                    return@launch
                }
                val adresse = withContext(Dispatchers.IO) { container.standortErmittlung.adresseFuer(standort) }
                standortLaedt = false
                if (adresse != null) {
                    messort = adresse
                } else {
                    messort = "%.5f, %.5f".format(Locale.getDefault(), standort.breitengrad, standort.laengengrad)
                    hinweis = context.getString(R.string.report_metadata_err_address_failed)
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
                    hinweis = context.getString(R.string.report_metadata_err_no_weather_location)
                    return@launch
                }
                val ergebnis = container.wetterProvider.aktuelleWetterlage(standort.breitengrad, standort.laengengrad)
                wetterLaedt = false
                ergebnis.onSuccess { wetter = it.alsKurztext() }
                    .onFailure { hinweis = context.getString(R.string.report_metadata_err_weather_failed) }
            }
        }
    }

    fun speichern() {
        if (speichert) return
        speichert = true
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
            try {
                withContext(Dispatchers.IO) {
                    val jetzt = System.currentTimeMillis()
                    val zone = ZoneId.systemDefault()
                    val tag = lokalerMesstag(giltFuerTagStart ?: jetzt, zone)
                    val (von, bis) = messtagGrenzen(tag, zone)
                    val dao = container.database.stammdatenVerlaufDao()
                    val letzterFuerTag = dao.fuerTag(von, bis).firstOrNull()
                    if (letzterFuerTag == null || !stammdaten.entsprichtEintrag(letzterFuerTag)) {
                        dao.insert(
                            stammdaten.zuEntity(jetzt).copy(
                                giltFuerTagStart = giltFuerTagStart,
                                messvorgangId = if (giltFuerTagStart == null) messvorgangId else null,
                            ),
                        )
                    }
                }
                onFertig()
            } catch (abbruch: CancellationException) {
                throw abbruch
            } catch (_: Exception) {
                hinweis = speicherFehler
                speichert = false
            }
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
            Text(
                if (giltFuerTagStart == null) {
                    stringResource(R.string.report_metadata_title_current)
                } else {
                    stringResource(R.string.report_metadata_title_retroactive)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.report_metadata_description) +
                    if (giltFuerTagStart == null) "" else stringResource(R.string.report_metadata_description_retroactive_suffix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (verlauf.size > 1) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { auswahlOffen = true }) {
                        Text(stringResource(R.string.report_metadata_choose_saved, verlauf.size))
                    }
                    DropdownMenu(expanded = auswahlOffen, onDismissRequest = { auswahlOffen = false }) {
                        verlauf.forEach { eintrag ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${zeitFormat.format(Date(eintrag.erstelltAm))} – " +
                                            eintrag.messort.ifBlank { stringResource(R.string.report_metadata_no_location) }
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
            Text(stringResource(R.string.report_metadata_device_section), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = geraetHersteller, onValueChange = { geraetHersteller = it },
                label = { Text(stringResource(R.string.report_metadata_manufacturer)) },
                modifier = Modifier.testTag("input_bericht_hersteller").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetTyp, onValueChange = { geraetTyp = it },
                label = { Text(stringResource(R.string.report_metadata_type)) },
                modifier = Modifier.testTag("input_bericht_typ").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetGenauigkeitsklasse, onValueChange = { geraetGenauigkeitsklasse = it },
                label = { Text(stringResource(R.string.report_metadata_accuracy_class)) },
                modifier = Modifier.testTag("input_bericht_genauigkeitsklasse").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetSeriennummer, onValueChange = { geraetSeriennummer = it },
                label = { Text(stringResource(R.string.report_metadata_serial_number)) },
                modifier = Modifier.testTag("input_bericht_seriennummer").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geraetKalibrierung, onValueChange = { geraetKalibrierung = it },
                label = { Text(stringResource(R.string.report_metadata_calibration)) },
                placeholder = { Text(stringResource(R.string.report_metadata_calibration_placeholder)) },
                modifier = Modifier.testTag("input_bericht_kalibrierung").fillMaxWidth(),
            )
            zeitgebundeneQuelle?.let { quelle ->
                ZeitgebundenerWertHinweis(
                    feld = stringResource(R.string.report_metadata_calibration),
                    wert = quelle.geraetKalibrierung,
                    aktuellerWert = geraetKalibrierung,
                    bestaetigtAm = zeitFormat.format(Date(quelle.erstelltAm)),
                    testTag = "button_kalibrierung_uebernehmen",
                    onUebernehmen = { geraetKalibrierung = quelle.geraetKalibrierung },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.report_metadata_setup_section), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = messort, onValueChange = { messort = it },
                label = { Text(stringResource(R.string.report_metadata_location)) },
                modifier = Modifier.testTag("input_bericht_messort").fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { standortErmitteln() },
                    enabled = !standortLaedt,
                    modifier = Modifier.testTag("button_standort_ermitteln"),
                ) {
                    Text(
                        if (standortLaedt) {
                            stringResource(R.string.report_metadata_determining_location)
                        } else {
                            stringResource(R.string.report_metadata_determine_location)
                        }
                    )
                }
                if (standortLaedt) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mikrofonposition, onValueChange = { mikrofonposition = it },
                label = { Text(stringResource(R.string.report_metadata_mic_position)) },
                modifier = Modifier.testTag("input_bericht_mikrofonposition").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mikrofonhoehe, onValueChange = { mikrofonhoehe = it },
                label = { Text(stringResource(R.string.report_metadata_mic_height)) },
                modifier = Modifier.testTag("input_bericht_mikrofonhoehe").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = entfernungZurQuelle, onValueChange = { entfernungZurQuelle = it },
                label = { Text(stringResource(R.string.report_metadata_distance)) },
                modifier = Modifier.testTag("input_bericht_entfernung").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = innenAussen, onValueChange = { innenAussen = it },
                label = { Text(stringResource(R.string.report_metadata_indoor_outdoor)) },
                placeholder = { Text(stringResource(R.string.report_metadata_indoor_outdoor_placeholder)) },
                modifier = Modifier.testTag("input_bericht_innen_aussen").fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = fensterzustand, onValueChange = { fensterzustand = it },
                label = { Text(stringResource(R.string.report_metadata_window_state)) },
                placeholder = { Text(stringResource(R.string.report_metadata_window_state_placeholder)) },
                modifier = Modifier.testTag("input_bericht_fensterzustand").fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.report_metadata_conditions_section), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = wetter, onValueChange = { wetter = it },
                label = { Text(stringResource(R.string.report_metadata_weather)) },
                modifier = Modifier.testTag("input_bericht_wetter").fillMaxWidth(),
            )
            zeitgebundeneQuelle?.let { quelle ->
                ZeitgebundenerWertHinweis(
                    feld = stringResource(R.string.report_metadata_weather),
                    wert = quelle.wetter,
                    aktuellerWert = wetter,
                    bestaetigtAm = zeitFormat.format(Date(quelle.erstelltAm)),
                    testTag = "button_wetter_uebernehmen",
                    onUebernehmen = { wetter = quelle.wetter },
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { wetterAbrufen() },
                    enabled = !wetterLaedt,
                    modifier = Modifier.testTag("button_wetter_abrufen"),
                ) {
                    Text(
                        if (wetterLaedt) {
                            stringResource(R.string.report_metadata_fetching_weather)
                        } else {
                            stringResource(R.string.report_metadata_fetch_weather)
                        }
                    )
                }
                if (wetterLaedt) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = datenqualitaetHinweis, onValueChange = { datenqualitaetHinweis = it },
                label = { Text(stringResource(R.string.report_metadata_data_quality_hint)) },
                placeholder = { Text(stringResource(R.string.report_metadata_data_quality_placeholder)) },
                modifier = Modifier.testTag("input_bericht_datenqualitaet").fillMaxWidth(),
            )
            zeitgebundeneQuelle?.let { quelle ->
                ZeitgebundenerWertHinweis(
                    feld = stringResource(R.string.report_metadata_data_quality),
                    wert = quelle.datenqualitaetHinweis,
                    aktuellerWert = datenqualitaetHinweis,
                    bestaetigtAm = zeitFormat.format(Date(quelle.erstelltAm)),
                    testTag = "button_datenqualitaet_uebernehmen",
                    onUebernehmen = { datenqualitaetHinweis = quelle.datenqualitaetHinweis },
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onFertig, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.report_metadata_skip))
                }
                Button(onClick = { speichern() }, enabled = !speichert, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (speichert) R.string.report_metadata_saving else R.string.report_metadata_save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ZeitgebundenerWertHinweis(
    feld: String,
    wert: String,
    aktuellerWert: String,
    bestaetigtAm: String,
    testTag: String,
    onUebernehmen: () -> Unit,
) {
    if (wert.isBlank()) return
    Text(
        stringResource(R.string.report_metadata_last_confirmed, feld, bestaetigtAm, wert),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (aktuellerWert.isBlank()) {
        TextButton(onClick = onUebernehmen, modifier = Modifier.testTag(testTag)) {
            Text(stringResource(R.string.report_metadata_confirm_previous))
        }
    }
}
