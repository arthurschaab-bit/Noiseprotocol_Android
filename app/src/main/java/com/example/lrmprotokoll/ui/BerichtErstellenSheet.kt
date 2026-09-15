package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.report.BerichtTag
import com.example.lrmprotokoll.report.BerichtZeitraum
import com.example.lrmprotokoll.report.ChaquopyReportRunner
import com.example.lrmprotokoll.report.auswahlFehler
import com.example.lrmprotokoll.report.bewertungsFehler
import com.example.lrmprotokoll.report.fehlendeStammdatenFelder
import com.example.lrmprotokoll.report.gewaehlteStammdaten
import com.example.lrmprotokoll.report.ladeBerichtstage
import com.example.lrmprotokoll.report.retentionFehler
import com.example.lrmprotokoll.report.vorlaeufigeBerichtsparameter
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Separater Rechtsbericht-Einstieg neben dem alten Zeitraum-Dialog (Owner-Vorgabe 13.09.2026).
 * Eine Datumsbereich-Auswahl passt zu einem dokumentierten Fall statt relativer 7/30-Tage-
 * Presets. Fehlende Stammdaten bleiben bis zur Nacherfassung sichtbar und duerfen auf ausdrueckliche
 * Owner-Entscheidung vom 14.09.2026 mit einem PDF-Vorbehalt weitergereicht werden.
 * Retention und unbestaetigte A-/Zeitbewertung blockieren dagegen vor dem Python-Aufruf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BerichtErstellenSheet(
    onFertig: () -> Unit,
    runner: suspend (String) -> ChaquopyReportRunner.Ergebnis,
    initialRange: BerichtZeitraum? = null,
) {
    val context = LocalContext.current
    val db = remember { (context.applicationContext as LaermprotokollApp).container.database }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var zeitraum by remember { mutableStateOf(initialRange) }
    var tage by remember { mutableStateOf<List<BerichtTag>>(emptyList()) }
    var config by remember { mutableStateOf<ReportConfigEntity?>(null) }
    var ausgewaehlteIds by remember { mutableStateOf<Map<LocalDate, Long>>(emptyMap()) }
    var nachtragTag by remember { mutableStateOf<BerichtTag?>(null) }
    var offenesAuswahlDatum by remember { mutableStateOf<LocalDate?>(null) }
    var datumDialogOffen by remember { mutableStateOf(false) }
    var ladezahl by remember { mutableIntStateOf(0) }
    var laedt by remember { mutableStateOf(false) }
    var erzeugt by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var pdfPfad by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(zeitraum, ladezahl) {
        laedt = true
        meldung = null
        config = withContext(Dispatchers.IO) { db.reportConfigDao().get() ?: ReportConfigEntity() }
        tage = zeitraum?.let { withContext(Dispatchers.IO) { ladeBerichtstage(db, it) } } ?: emptyList()
        ausgewaehlteIds = ausgewaehlteIds.filter { (datum, id) ->
            tage.any { it.datum == datum && it.stammdatenKandidaten.any { kandidat -> kandidat.id == id } }
        }
        laedt = false
    }

    fun erzeugen() {
        val aktuell = config
        val fehler = when {
            zeitraum == null -> "Bitte zuerst einen Datumsbereich wählen."
            laedt || aktuell == null -> "Berichtsdaten werden noch geladen."
            tage.none { it.rohwerte > 0 } -> "Im gewählten Zeitraum liegen keine Rohdaten für einen Bericht vor."
            else -> retentionFehler(tage)
                ?: bewertungsFehler(tage, aktuell)
                ?: auswahlFehler(tage, ausgewaehlteIds)
        }
        if (fehler != null) {
            meldung = fehler
            return
        }
        val parameter = vorlaeufigeBerichtsparameter(
            tage, aktuell!!, ausgewaehlteIds,
            File(context.filesDir, "high_end_report_${System.currentTimeMillis()}.pdf").absolutePath,
        )
        erzeugt = true
        meldung = null
        scope.launch {
            val ergebnis = try {
                runner(parameter)
            } catch (e: Exception) {
                ChaquopyReportRunner.Ergebnis.Fehler("Berichtserzeugung konnte nicht gestartet werden: ${e.message}", e)
            }
            erzeugt = false
            when (ergebnis) {
                is ChaquopyReportRunner.Ergebnis.Erfolg -> pdfPfad = ergebnis.pdfPfad
                is ChaquopyReportRunner.Ergebnis.Fehler -> {
                    meldung = if (ergebnis.nachricht.contains("report_bridge") &&
                        ergebnis.nachricht.contains("module", ignoreCase = true)) {
                        "Der Berichtskern ist noch nicht installiert. Die Auswahl wurde geprüft; der Python-Bericht folgt im nächsten Schritt."
                    } else ergebnis.nachricht
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!erzeugt) onFertig() }, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("High-End-Bericht erzeugen", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Wähle den dokumentierten Messzeitraum. Verdichtete Rohdaten verhindern den Bericht; fehlende Stammdaten werden sichtbar als Lücke bezeichnet.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { datumDialogOffen = true }, modifier = Modifier.testTag("btn_bericht_datumsbereich")) {
                Text("Datumsbereich wählen")
            }
            zeitraum?.let {
                Text("${it.ersterTag.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN))} – " +
                    it.letzterTag.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)),
                    modifier = Modifier.testTag("bericht_datumsbereich_anzeige"))
            }
            if (laedt) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator()
            }
            tage.forEach { tag ->
                Spacer(Modifier.height(12.dp))
                Text(tag.label, style = MaterialTheme.typography.titleSmall)
                Text("Rohwerte: ${tag.rohwerte}" +
                    if (tag.verdichteteMinuten > 0) " · bereits verdichtete Minuten: ${tag.verdichteteMinuten}" else "",
                    style = MaterialTheme.typography.bodySmall)
                if (tag.rohwerte == 0) Text("Messlücke: keine Rohwerte an diesem Tag.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

                val kandidaten = tag.stammdatenKandidaten
                if (kandidaten.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { offenesAuswahlDatum = tag.datum },
                            modifier = Modifier.testTag("btn_bericht_stammdaten_${tag.datum}"),
                        ) { Text(gewaehlteStammdaten(tag, ausgewaehlteIds)?.messort ?: "Stammdaten auswählen") }
                        DropdownMenu(
                            expanded = offenesAuswahlDatum == tag.datum,
                            onDismissRequest = { offenesAuswahlDatum = null },
                        ) {
                            kandidaten.forEach { kandidat ->
                                DropdownMenuItem(
                                    text = { Text("${kandidat.messort.ifBlank { "kein Messort" }} · #${kandidat.id}") },
                                    onClick = {
                                        ausgewaehlteIds = ausgewaehlteIds + (tag.datum to kandidat.id)
                                        offenesAuswahlDatum = null
                                    },
                                )
                            }
                        }
                    }
                }
                val ausgewaehlt = gewaehlteStammdaten(tag, ausgewaehlteIds)
                val luecken = fehlendeStammdatenFelder(ausgewaehlt)
                if (luecken.isNotEmpty()) {
                    Text("Stammdaten-Lücke: ${luecken.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("bericht_stammdaten_luecke_${tag.datum}"))
                }
                OutlinedButton(
                    onClick = { nachtragTag = tag },
                    modifier = Modifier.testTag("btn_bericht_stammdaten_nachtragen_${tag.datum}"),
                ) { Text("Angaben für diesen Tag nachtragen") }
            }

            val unbestaetigt = tage.any { it.unbestaetigteWerte > 0 }
            if (unbestaetigt && config?.erzwingeBerichtOhneBestaetigteBewertung == true) {
                Spacer(Modifier.height(12.dp))
                Text("Warnung: A-/Zeitbewertung nicht bestätigt. Der Bericht muss einen sichtbaren Vorbehalt enthalten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("bericht_override_warnung"))
            }
            meldung?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("bericht_erstellen_fehler"))
            }
            pdfPfad?.let {
                Spacer(Modifier.height(12.dp))
                Text("Bericht erzeugt: $it", modifier = Modifier.testTag("bericht_erstellen_erfolg"))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFertig, enabled = !erzeugt) { Text("Schließen") }
                Button(onClick = { erzeugen() }, enabled = !erzeugt && !laedt,
                    modifier = Modifier.testTag("btn_bericht_erstellen_start")) {
                    Text(if (erzeugt) "Erzeuge …" else "Bericht jetzt erzeugen")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (datumDialogOffen) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { datumDialogOffen = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = picker.selectedStartDateMillis
                    val ende = picker.selectedEndDateMillis
                    if (start != null && ende != null) {
                        zeitraum = BerichtZeitraum.ausPicker(start, ende)
                        ausgewaehlteIds = emptyMap()
                        pdfPfad = null
                        datumDialogOffen = false
                    } else meldung = "Bitte Start- und Enddatum wählen."
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { datumDialogOffen = false }) { Text("Abbrechen") } },
        ) { DateRangePicker(state = picker) }
    }

    nachtragTag?.let { tag ->
        GesamtberichtStammdatenSheet(
            sessionId = tag.sessionIds.firstOrNull() ?: 0,
            giltFuerTagStart = tag.von,
            onFertig = { nachtragTag = null; ladezahl++ },
        )
    }
}
