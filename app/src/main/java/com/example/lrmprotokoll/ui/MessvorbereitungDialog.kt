package com.example.lrmprotokoll.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.KalibrierEnde
import com.example.lrmprotokoll.messreihe.KalibrierAnzeige
import com.example.lrmprotokoll.messreihe.Messphase
import com.example.lrmprotokoll.messreihe.MessphasenSteuerung
import com.example.lrmprotokoll.messreihe.TagesErfassung
import com.example.lrmprotokoll.report.lokalerMesstag
import com.example.lrmprotokoll.report.messtagGrenzen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Oberflaeche der Kalibrier- und Aufbauphase am manuellen Messstart (Owner-Auftrag 12.11.2026).
 *
 * Zeigt je nach [MessphasenSteuerung.phase] den Kalibrier- oder den Aufbau-Dialog. Mit
 * [aufbauBearbeiten] laesst sich der Aufbau-Dialog ausserhalb einer Phase oeffnen, um die Angaben
 * von heute zu aendern - die Messung laeuft dabei weiter.
 */
@Composable
fun MessvorbereitungHost(
    aufbauBearbeiten: Boolean,
    onAufbauBearbeitenEnde: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val steuerung = container.messphasen
    val phase by steuerung.phase.collectAsState()
    val anzeige by steuerung.anzeige.collectAsState()
    when (phase) {
        Messphase.KALIBRIERUNG -> KalibrierDialog(steuerung, anzeige)
        Messphase.AUFBAU -> AufbauDialog(phaseModus = true, onEnde = { })
        Messphase.KEINE -> if (aufbauBearbeiten) AufbauDialog(phaseModus = false, onEnde = onAufbauBearbeitenEnde)
    }
}

internal fun formatiereDb(wert: Double): String = String.format(Locale.getDefault(), "%.1f", wert)

/** Akzeptiert Komma und Punkt; `null` bei Unlesbarem. */
internal fun leseDb(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

@Composable
private fun KalibrierDialog(
    steuerung: MessphasenSteuerung,
    anzeige: KalibrierAnzeige,
) {
    var vorherText by rememberSaveable { mutableStateOf("") }
    var nachherText by rememberSaveable { mutableStateOf("") }
    var vorherBearbeitet by rememberSaveable { mutableStateOf(false) }
    var nachherBearbeitet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(anzeige.abweichungVorherDb) {
        if (!vorherBearbeitet) vorherText = anzeige.abweichungVorherDb?.let(::formatiereDb) ?: ""
    }
    LaunchedEffect(anzeige.abweichungNachherDb) {
        if (!nachherBearbeitet) nachherText = anzeige.abweichungNachherDb?.let(::formatiereDb) ?: ""
    }
    val vorher = leseDb(vorherText)
    val nachher = leseDb(nachherText)
    val eingabeGueltig = (vorherText.isBlank() || vorher != null) && (nachherText.isBlank() || nachher != null)

    fun weicht(
        a: Double?,
        b: Double?,
    ) = (a == null) != (b == null) || (a != null && b != null && abs(a - b) > 0.05)

    AlertDialog(
        onDismissRequest = { },
        modifier = Modifier.testTag("dialog_kalibrierung"),
        title = { Text(stringResource(R.string.kalib_titel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.kalib_hinweis), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text =
                        anzeige.pegelDb?.let { stringResource(R.string.kalib_pegel, formatiereDb(it)) }
                            ?: stringResource(R.string.kalib_warte_geraet),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("kalibrierung_pegel"),
                )
                if (anzeige.endeErkannt) {
                    Text(
                        stringResource(R.string.kalib_ende_erkannt),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("kalibrierung_ende_erkannt"),
                    )
                }
                Text(stringResource(R.string.kalib_werte_hinweis), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = vorherText,
                    onValueChange = {
                        vorherText = it
                        vorherBearbeitet = true
                    },
                    label = { Text(stringResource(R.string.kalib_vorher)) },
                    singleLine = true,
                    isError = vorherText.isNotBlank() && vorher == null,
                    modifier = Modifier.fillMaxWidth().testTag("input_kalibrierung_vorher"),
                )
                OutlinedTextField(
                    value = nachherText,
                    onValueChange = {
                        nachherText = it
                        nachherBearbeitet = true
                    },
                    label = { Text(stringResource(R.string.kalib_nachher)) },
                    singleLine = true,
                    isError = nachherText.isNotBlank() && nachher == null,
                    modifier = Modifier.fillMaxWidth().testTag("input_kalibrierung_nachher"),
                )
                if (!eingabeGueltig) {
                    Text(
                        stringResource(R.string.kalib_ungueltig),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = eingabeGueltig,
                onClick = {
                    val korrigiert =
                        weicht(vorher, anzeige.abweichungVorherDb) ||
                            weicht(nachher, anzeige.abweichungNachherDb)
                    steuerung.kalibrierungBeenden(
                        vorher = vorher,
                        nachher = nachher,
                        korrigiert = korrigiert,
                        grund = if (anzeige.endeErkannt) KalibrierEnde.BESTAETIGT else KalibrierEnde.MANUELL,
                    )
                },
                modifier = Modifier.testTag("button_kalibrierung_beendet"),
            ) {
                Text(
                    stringResource(
                        if (anzeige.endeErkannt) R.string.kalib_btn_ja_beendet else R.string.kalib_btn_beendet,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { steuerung.kalibrierungUeberspringen() },
                modifier = Modifier.testTag("button_kalibrierung_ueberspringen"),
            ) { Text(stringResource(R.string.kalib_btn_ueberspringen)) }
        },
    )
}

private enum class AufbauSheet { KONFIGURATION, FOTOS }

@Composable
private fun AufbauDialog(
    phaseModus: Boolean,
    onEnde: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val settings = container.settingsManager
    val steuerung = container.messphasen
    val scope = rememberCoroutineScope()
    val offeneSession by container.database
        .sessionDao()
        .offeneSessionFlow()
        .collectAsState(initial = null)

    val konfigurationNoetig = settings.stammdatenAbfrageAktiv
    val fotosNoetig = settings.fotoDokuAktiv

    var aktualisieren by remember { mutableIntStateOf(0) }
    var stand by remember { mutableStateOf<TagesErfassung?>(null) }
    var offenesSheet by remember { mutableStateOf<AufbauSheet?>(null) }
    var abgeschlossen by remember { mutableStateOf(false) }

    LaunchedEffect(aktualisieren) {
        stand = withContext(Dispatchers.IO) { TagesErfassung.fuerTag(container.database, System.currentTimeMillis()) }
    }

    // Die Abfragen dieses Messvorgangs gelten damit als beantwortet - sonst kaeme dasselbe
    // gleich noch einmal als Popup, sobald die Messung laeuft.
    suspend fun markiereAbfragenBeantwortet() {
        val session = offeneSession ?: return
        val zone = ZoneId.systemDefault()
        val tagStart = messtagGrenzen(lokalerMesstag(System.currentTimeMillis(), zone), zone).first
        withContext(Dispatchers.IO) {
            container.database.sessionDao().fotoAbfrageAbgeschlossen(session.messvorgangId)
            container.database.sessionDao().stammdatenAbfrageAbgeschlossen(session.messvorgangId)
            settings.stammdatenAbfrageFuerTagAbschliessen(session.messvorgangId, tagStart)
        }
    }

    fun abschliessen() {
        if (abgeschlossen) return
        abgeschlossen = true
        scope.launch {
            if (phaseModus) {
                markiereAbfragenBeantwortet()
                steuerung.aufbauAbschliessen()
            }
            onEnde()
        }
    }

    // Ist heute schon alles erfasst, entfaellt die Abfrage ganz (kein Popup bei jedem Start).
    LaunchedEffect(stand, phaseModus) {
        val heute = stand ?: return@LaunchedEffect
        if (phaseModus && !abgeschlossen && heute.vollstaendig(konfigurationNoetig, fotosNoetig)) {
            Toast.makeText(context, context.getString(R.string.aufbau_uebernommen), Toast.LENGTH_SHORT).show()
            abgeschlossen = true
            markiereAbfragenBeantwortet()
            steuerung.aufbauAbschliessen()
        }
    }

    val session = offeneSession
    offenesSheet?.let { sheet ->
        if (session != null) {
            when (sheet) {
                AufbauSheet.KONFIGURATION ->
                    GesamtberichtStammdatenSheet(
                        sessionId = session.id,
                        messvorgangId = session.messvorgangId,
                        onFertig = {
                            offenesSheet = null
                            aktualisieren++
                        },
                    )
                AufbauSheet.FOTOS ->
                    FotoDokumentationSheet(
                        sessionId = session.id,
                        messvorgangId = session.messvorgangId,
                        onFertig = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    container.database.sessionDao().fotoAbfrageAbgeschlossen(session.messvorgangId)
                                }
                                offenesSheet = null
                                aktualisieren++
                            }
                        },
                    )
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!phaseModus) onEnde() },
        modifier = Modifier.testTag("dialog_aufbau"),
        title = { Text(stringResource(R.string.aufbau_titel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (phaseModus) R.string.aufbau_hinweis_phase else R.string.aufbau_hinweis_bearbeiten,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (konfigurationNoetig) {
                    AufbauZeile(
                        titel = stringResource(R.string.aufbau_konfig),
                        status =
                            if (stand?.konfigurationErfasst == true) {
                                stringResource(R.string.aufbau_erfasst)
                            } else {
                                stringResource(R.string.aufbau_offen)
                            },
                        erledigt = stand?.konfigurationErfasst == true,
                        aktiv = session != null,
                        tag = "aufbau_konfiguration",
                        onKlick = { offenesSheet = AufbauSheet.KONFIGURATION },
                    )
                }
                if (fotosNoetig) {
                    val anzahl = stand?.fotoAnzahl ?: 0
                    AufbauZeile(
                        titel = stringResource(R.string.aufbau_fotos),
                        status =
                            if (anzahl > 0) {
                                stringResource(R.string.aufbau_fotos_anzahl, anzahl)
                            } else {
                                stringResource(R.string.aufbau_offen)
                            },
                        erledigt = anzahl > 0,
                        aktiv = session != null,
                        tag = "aufbau_fotos",
                        onKlick = { offenesSheet = AufbauSheet.FOTOS },
                    )
                }
                if (session == null) {
                    Text(stringResource(R.string.aufbau_ohne_sitzung), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { abschliessen() },
                modifier = Modifier.testTag("button_aufbau_abschliessen"),
            ) {
                Text(stringResource(if (phaseModus) R.string.aufbau_btn_start else R.string.aufbau_btn_fertig))
            }
        },
    )
}

@Composable
private fun AufbauZeile(
    titel: String,
    status: String,
    erledigt: Boolean,
    aktiv: Boolean,
    tag: String,
    onKlick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.titleSmall)
            Text(
                text = (if (erledigt) "✓ " else "") + status,
                style = MaterialTheme.typography.bodySmall,
                color = if (erledigt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onKlick, enabled = aktiv) {
            Text(stringResource(if (erledigt) R.string.aufbau_btn_aendern else R.string.aufbau_btn_erfassen))
        }
    }
}
