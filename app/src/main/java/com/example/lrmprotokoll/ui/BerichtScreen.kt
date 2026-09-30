package com.example.lrmprotokoll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.messreihe.Messintegritaet
import com.example.lrmprotokoll.report.BerichtZeitraum
import com.example.lrmprotokoll.report.ChaquopyReportRunner
import com.example.lrmprotokoll.report.GesamtberichtExport
import com.example.lrmprotokoll.report.GesamtberichtStammdaten
import com.example.lrmprotokoll.report.Messtag
import com.example.lrmprotokoll.report.PeriodenBerichtExport
import com.example.lrmprotokoll.report.ermittleGesamtberichtFuerTage
import com.example.lrmprotokoll.report.ermittlePeriodenBerichtFuerTage
import com.example.lrmprotokoll.report.ladeMesstage
import com.example.lrmprotokoll.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Vorbelegung des Zeitraums (Owner-Entscheidung 30.09.2026): die letzten 30 Tage. */
private const val STANDARD_ZEITRAUM_TAGE = 30L

/**
 * Reiter "Bericht" - seit S-4 ein Arbeitsplatz statt einer Zwischenseite.
 *
 * Vorher standen hier zwei Knoepfe und sonst nichts; welche Messtage es gibt und welche davon
 * berichtsfaehig sind, erfuhr man erst nach dem Klick. Jetzt steht der **Zeitraum** oben, darunter
 * die **Messtage darin** mit Integritaetsstufe und den Gruenden, die einen Bericht verhindern, und
 * unten die **drei Ausgaben** mit je einer Zeile, was drinsteht.
 *
 * Zeitraum ist der Umfang, die Haken sind Ausnahmen darin. Beide fuellen denselben Zustand, damit
 * es nicht zwei Auswahlmechanismen nebeneinander gibt - dieselbe Lehre wie aus S-5 (zwei
 * Filtersysteme im Protokoll).
 *
 * Die drei Ausgaben sind benannt, weil der Gesamtbericht vorher hinter einem **Schalter in einem
 * Dialog** hinter dem Knopf "Zeitraumbericht erstellen" lag - der Owner hat ihn in Monaten nie
 * gefunden (Meldung 30.09.2026).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BerichtScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Testbarer Aufrufpfad; ohne Injection wird Chaquopy erst beim Erzeugen gestartet. */
    highEndRunner: (suspend (String) -> ChaquopyReportRunner.Ergebnis)? = null,
    initialHighEndRange: BerichtZeitraum? = null,
    onShowSnackbar: ((String) -> Unit)? = null,
    periodenBerichtExport: PeriodenBerichtExport? = null,
    gesamtberichtExportInstance: GesamtberichtExport? = null,
    /** Testnahtstelle fuer die Vorbelegung des Zeitraums. */
    heute: LocalDate = LocalDate.now(),
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LaermprotokollApp).container }
    val db = container.database
    val scope = rememberCoroutineScope()
    val periodenExport = remember(periodenBerichtExport) { periodenBerichtExport ?: PeriodenBerichtExport(context) }
    val gesamtberichtExport = remember(gesamtberichtExportInstance) { gesamtberichtExportInstance ?: GesamtberichtExport(context) }

    var zeitraum by remember {
        mutableStateOf(initialHighEndRange ?: BerichtZeitraum(heute.minusDays(STANDARD_ZEITRAUM_TAGE - 1), heute))
    }
    var messtage by remember { mutableStateOf<List<Messtag>>(emptyList()) }
    var laedt by remember { mutableStateOf(true) }
    var abgewaehlt by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var zeigeDatumsdialog by remember { mutableStateOf(false) }
    var zeigeHighEndSheet by remember { mutableStateOf(false) }
    var wirdErstellt by remember { mutableStateOf(false) }
    var zeigeMenue by remember { mutableStateOf(false) }

    /**
     * Die Vorbelegung des High-End-Sheets wird **beim Oeffnen** aufgeloest, nicht vorab.
     *
     * [BerichtErstellenSheet] liest `initialRange` genau einmal (`remember { mutableStateOf(...) }`).
     * Wuerde sie asynchron nachgeladen, waere das Sheet bei schnellem Tippen dauerhaft leer - ein
     * Rennen, das der alte Bildschirm durch dieselbe Reihenfolge vermieden hat.
     */
    var highEndZeitraum by remember { mutableStateOf<BerichtZeitraum?>(null) }
    var letzteMessungZeitraum by remember { mutableStateOf<BerichtZeitraum?>(null) }
    var highEndWirdVorbereitet by remember { mutableStateOf(false) }

    // Ein neuer Zeitraum hakt alle Messtage an; nicht berichtsfaehige bleiben aus und gesperrt
    // (Owner-Entscheidung 30.09.2026).
    LaunchedEffect(zeitraum) {
        laedt = true
        val geladen =
            withContext(Dispatchers.IO) {
                val config = runCatching { db.reportConfigDao().get() }.getOrNull() ?: ReportConfigEntity()
                runCatching { ladeMesstage(db, zeitraum, config) }.getOrDefault(emptyList())
            }
        messtage = geladen
        abgewaehlt = geladen.filterNot { it.berichtsfaehig }.map { it.datum }.toSet()
        laedt = false
    }

    val gewaehlteTage = remember(messtage, abgewaehlt) { messtage.filter { it.datum !in abgewaehlt } }
    val waehlbareTage = remember(messtage) { messtage.filter { it.berichtsfaehig } }

    fun setzeZeitraum(neu: BerichtZeitraum) {
        zeitraum = neu
    }

    fun meldeFehler(
        fehler: Throwable,
        aktion: String,
    ) {
        container.diagnosticsReporter.report(
            code = DiagnosticCode.REPORT_CREATE_FAILED,
            component = "BerichtScreen",
            operation = aktion,
            severity = DiagnosticSeverity.WARN,
            cause = fehler,
            message = fehler.message ?: "Export fehlgeschlagen",
        )
        onShowSnackbar?.invoke(context.getString(R.string.export_failed_message))
    }

    fun erstelleUndTeile(alsGesamtbericht: Boolean) {
        if (gewaehlteTage.isEmpty()) {
            onShowSnackbar?.invoke(context.getString(R.string.bericht_keine_auswahl))
            return
        }
        val tage = gewaehlteTage
        wirdErstellt = true
        scope.launch {
            val ergebnis =
                runCatching {
                    if (alsGesamtbericht) {
                        val datei =
                            withContext(Dispatchers.IO) {
                                val bericht = ermittleGesamtberichtFuerTage(db, tage)
                                val stammdaten = GesamtberichtStammdaten.ausVerlauf(db.stammdatenVerlaufDao())
                                gesamtberichtExport.exportierePdf(bericht, stammdaten, "Lärmprotokoll – Gesamtbericht")
                            }
                        gesamtberichtExport.teilen(datei)
                    } else {
                        val datei =
                            withContext(Dispatchers.IO) {
                                val bericht = ermittlePeriodenBerichtFuerTage(db, tage)
                                periodenExport.exportierePdf(bericht, "Lärmprotokoll – Zeitraumbericht")
                            }
                        periodenExport.teilen(datei)
                    }
                }
            wirdErstellt = false
            ergebnis.onFailure {
                meldeFehler(it, if (alsGesamtbericht) "exportGesamtbericht" else "exportZeitraumbericht")
            }
        }
    }

    fun oeffneHighEnd() {
        if (highEndWirdVorbereitet) return
        val auswahl = gewaehlteTage
        val luecke =
            auswahl.isNotEmpty() &&
                messtage.any { tag ->
                    tag.datum in abgewaehlt &&
                        tag.datum.isAfter(auswahl.last().datum) &&
                        tag.datum.isBefore(auswahl.first().datum)
                }
        if (luecke) onShowSnackbar?.invoke(context.getString(R.string.bericht_highend_luecke))
        highEndWirdVorbereitet = true
        scope.launch {
            val zone = ZoneId.systemDefault()
            val letzte =
                withContext(Dispatchers.IO) {
                    val session = runCatching { db.sessionDao().letzteBeendete() }.getOrNull()
                    session?.endedAt?.let { ende ->
                        runCatching {
                            BerichtZeitraum(
                                Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate(),
                                Instant.ofEpochMilli(ende).atZone(zone).toLocalDate(),
                            )
                        }.getOrNull()
                    }
                }
            val gespeichert =
                container.settingsManager.letzterHighEndBerichtszeitraum()?.let { (von, bis) ->
                    runCatching { BerichtZeitraum(LocalDate.ofEpochDay(von), LocalDate.ofEpochDay(bis)) }.getOrNull()
                }
            // Reihenfolge wie vor S-4, nur mit der Tagesauswahl davor: was hier gewaehlt ist,
            // schlaegt die Vorbelegungen; sonst greift die Kette aus F-28 weiter.
            letzteMessungZeitraum = letzte
            highEndZeitraum = auswahl
                .takeIf { it.isNotEmpty() }
                ?.let { BerichtZeitraum(it.last().datum, it.first().datum) }
                ?: initialHighEndRange
                ?: gespeichert
                ?: letzte
            highEndWirdVorbereitet = false
            zeigeHighEndSheet = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_report),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("btn_bericht_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { zeigeMenue = true }, modifier = Modifier.testTag("btn_bericht_menu")) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_menu))
                        }
                        DropdownMenu(expanded = zeigeMenue, onDismissRequest = { zeigeMenue = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_settings)) },
                                onClick = {
                                    zeigeMenue = false
                                    onOpenSettings()
                                },
                                modifier = Modifier.testTag("menu_item_bericht_settings"),
                            )
                        }
                    }
                },
            )
        },
    ) { polster ->
        Column(modifier = Modifier.padding(polster).fillMaxSize().padding(horizontal = 16.dp)) {
            ZeitraumKarte(zeitraum = zeitraum, onAendern = { zeigeDatumsdialog = true })
            Spacer(modifier = Modifier.height(6.dp))
            Schnellwahl(
                heute = heute,
                aktuell = zeitraum,
                onWaehle = ::setzeZeitraum,
                onAlles = {
                    scope.launch {
                        val frueheste =
                            withContext(Dispatchers.IO) {
                                runCatching { db.sessionDao().fruehesterStart() }.getOrNull()
                            }
                        val ersterTag =
                            frueheste
                                ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                                ?: heute
                        setzeZeitraum(BerichtZeitraum(minOf(ersterTag, heute), heute))
                    }
                },
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.bericht_messtage_ueberschrift, messtage.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (waehlbareTage.isNotEmpty()) {
                    val alleGewaehlt = gewaehlteTage.size == waehlbareTage.size
                    TextButton(
                        onClick = {
                            abgewaehlt =
                                if (alleGewaehlt) {
                                    messtage.map { it.datum }.toSet()
                                } else {
                                    messtage.filterNot { it.berichtsfaehig }.map { it.datum }.toSet()
                                }
                        },
                        modifier = Modifier.testTag("btn_bericht_alle_umschalten"),
                    ) {
                        Text(
                            stringResource(
                                if (alleGewaehlt) R.string.bericht_alle_abwaehlen else R.string.bericht_alle_waehlen,
                            ),
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    laedt -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    messtage.isEmpty() ->
                        Text(
                            stringResource(R.string.bericht_messtage_keine),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).testTag("bericht_keine_messtage"),
                        )
                    else ->
                        LazyColumn(modifier = Modifier.fillMaxSize().testTag("bericht_messtagsliste")) {
                            items(messtage, key = { it.datum.toString() }) { tag ->
                                Messtagszeile(
                                    tag = tag,
                                    gewaehlt = tag.datum !in abgewaehlt,
                                    onUmschalten = {
                                        abgewaehlt =
                                            if (tag.datum in abgewaehlt) {
                                                abgewaehlt - tag.datum
                                            } else {
                                                abgewaehlt + tag.datum
                                            }
                                    },
                                )
                            }
                        }
                }
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(R.string.bericht_auswahl_stand, gewaehlteTage.size, messtage.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("bericht_auswahl_stand"),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Ausgaben(
                aktiv = gewaehlteTage.isNotEmpty() && !wirdErstellt,
                wirdErstellt = wirdErstellt,
                onUebersicht = { erstelleUndTeile(alsGesamtbericht = false) },
                onGesamt = { erstelleUndTeile(alsGesamtbericht = true) },
                onHighEnd = ::oeffneHighEnd,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (zeigeDatumsdialog) {
        ZeitraumDialog(
            vorauswahl = zeitraum,
            onAbbrechen = { zeigeDatumsdialog = false },
            onUnvollstaendig = { onShowSnackbar?.invoke(context.getString(R.string.bericht_zeitraum_unvollstaendig)) },
            onUebernehmen = {
                setzeZeitraum(it)
                zeigeDatumsdialog = false
            },
        )
    }

    if (zeigeHighEndSheet) {
        BerichtErstellenSheet(
            onFertig = { zeigeHighEndSheet = false },
            runner = highEndRunner ?: { parameter -> ChaquopyReportRunner(context).erzeugeBericht(parameter) },
            initialRange = highEndZeitraum,
            recentRange = letzteMessungZeitraum,
        )
    }
}

@Composable
private fun ZeitraumKarte(
    zeitraum: BerichtZeitraum,
    onAendern: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.bericht_zeitraum_label), style = MaterialTheme.typography.labelSmall)
                Text(
                    "${KURZ_FORMAT.format(zeitraum.ersterTag)} – ${LANG_FORMAT.format(zeitraum.letzterTag)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("bericht_zeitraum_text"),
                )
            }
            TextButton(onClick = onAendern, modifier = Modifier.testTag("btn_bericht_zeitraum_aendern")) {
                Text(stringResource(R.string.bericht_zeitraum_aendern))
            }
        }
    }
}

@Composable
private fun Schnellwahl(
    heute: LocalDate,
    aktuell: BerichtZeitraum,
    onWaehle: (BerichtZeitraum) -> Unit,
    onAlles: () -> Unit,
) {
    val presets =
        listOf(
            Triple(R.string.bericht_preset_7, "btn_bericht_preset_7", BerichtZeitraum(heute.minusDays(6), heute)),
            Triple(R.string.bericht_preset_30, "btn_bericht_preset_30", BerichtZeitraum(heute.minusDays(29), heute)),
            Triple(
                R.string.bericht_preset_monat,
                "btn_bericht_preset_monat",
                BerichtZeitraum(heute.withDayOfMonth(1), heute),
            ),
        )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        presets.forEach { (textRes, tag, bereich) ->
            FilterChip(
                selected = aktuell == bereich,
                onClick = { onWaehle(bereich) },
                label = { Text(stringResource(textRes), style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.testTag(tag),
            )
        }
        FilterChip(
            selected = false,
            onClick = onAlles,
            label = { Text(stringResource(R.string.bericht_preset_alles), style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.testTag("btn_bericht_preset_alles"),
        )
    }
}

@Composable
private fun Messtagszeile(
    tag: Messtag,
    gewaehlt: Boolean,
    onUmschalten: () -> Unit,
) {
    val farben = MaterialTheme.colorScheme.statusColors
    val punkt: Color =
        when (tag.integritaet.stufe) {
            Messintegritaet.VOLLSTAENDIG -> farben.connected
            Messintegritaet.EINGESCHRAENKT -> farben.warning
            Messintegritaet.LUECKENHAFT -> farben.error
        }
    val stunden = tag.messdauerMs / 3_600_000
    val minuten = (tag.messdauerMs % 3_600_000) / 60_000
    val messreihen =
        if (tag.sessionIds.size == 1) {
            stringResource(R.string.bericht_tag_eine_messreihe)
        } else {
            stringResource(R.string.bericht_tag_messreihen, tag.sessionIds.size)
        }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp).testTag("messtag_${tag.datum}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 2.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Checkbox(
                checked = gewaehlt,
                onCheckedChange = { onUmschalten() },
                enabled = tag.berichtsfaehig,
                modifier = Modifier.testTag("haken_${tag.datum}"),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DATUM_FORMAT.format(tag.datum),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = punkt.copy(alpha = 0.18f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(punkt),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(tag.integritaet.stufe.anzeigetext(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(
                    stringResource(
                        R.string.bericht_tag_umfang,
                        messreihen,
                        stringResource(R.string.bericht_tag_dauer, stunden, minuten),
                        tag.rohwerte.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Hindernisse(tag)
            }
        }
    }
}

@Composable
private fun Hindernisse(tag: Messtag) {
    val zeilen =
        buildList {
            if (tag.integritaet.rohdatenVerdichtet) add(stringResource(R.string.bericht_hindernis_verdichtet))
            if (!tag.integritaet.rohdatenVerdichtet && tag.rohwerte == 0) {
                add(stringResource(R.string.bericht_hindernis_ohne_rohwerte))
            }
            if (tag.integritaet.stufe != Messintegritaet.VOLLSTAENDIG && !tag.integritaet.rohdatenVerdichtet) {
                add(stringResource(R.string.bericht_hindernis_verfuegbarkeit, tag.integritaet.verfuegbarkeitProzent.toInt()))
            }
            if (tag.integritaet.unbestaetigteWerte > 0) add(stringResource(R.string.bericht_hindernis_bewertung))
            if (!tag.stammdatenVorhanden) {
                add(stringResource(R.string.bericht_hindernis_stammdaten_keine))
            } else if (tag.fehlendeStammdatenFelder.isNotEmpty()) {
                add(
                    stringResource(
                        R.string.bericht_hindernis_stammdaten,
                        tag.fehlendeStammdatenFelder.take(3).joinToString(", "),
                    ),
                )
            }
        }
    zeilen.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Ausgaben(
    aktiv: Boolean,
    wirdErstellt: Boolean,
    onUebersicht: () -> Unit,
    onGesamt: () -> Unit,
    onHighEnd: () -> Unit,
) {
    if (wirdErstellt) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(R.string.bericht_wird_erstellt))
        }
        return
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Ausgabezeile(
            R.string.bericht_ausgabe_uebersicht_titel,
            R.string.bericht_ausgabe_uebersicht_text,
            "btn_bericht_uebersicht",
            aktiv,
            onUebersicht,
        )
        HorizontalDivider()
        Ausgabezeile(
            R.string.bericht_ausgabe_gesamt_titel,
            R.string.bericht_ausgabe_gesamt_text,
            "btn_bericht_gesamtbericht",
            aktiv,
            onGesamt,
        )
        HorizontalDivider()
        Ausgabezeile(
            R.string.bericht_ausgabe_highend_titel,
            R.string.bericht_ausgabe_highend_text,
            "btn_bericht_erstellen_v2",
            true,
            onHighEnd,
        )
    }
}

@Composable
private fun Ausgabezeile(
    titelRes: Int,
    textRes: Int,
    testTag: String,
    aktiv: Boolean,
    onKlick: () -> Unit,
) {
    val farbe = if (aktiv) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        onClick = onKlick,
        enabled = aktiv,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(
                stringResource(titelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = farbe,
            )
            Text(
                stringResource(textRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Vollbild statt DatePickerDialog - Owner-Meldung 15.09.2026 am echten Geraet: ein
 * [DateRangePicker] im DatePickerDialog ist hoeher als der Bildschirm, "Uebernehmen" und
 * "Abbrechen" landen ausserhalb des Sichtbereichs. Dieselbe Bauart wie in
 * [BerichtErstellenSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZeitraumDialog(
    vorauswahl: BerichtZeitraum,
    onAbbrechen: () -> Unit,
    onUnvollstaendig: () -> Unit,
    onUebernehmen: (BerichtZeitraum) -> Unit,
) {
    val picker =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis =
                vorauswahl.ersterTag
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
            initialSelectedEndDateMillis =
                vorauswahl.letzterTag
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
        )
    Dialog(onDismissRequest = onAbbrechen, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onAbbrechen, modifier = Modifier.testTag("btn_bericht_zeitraum_abbrechen")) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val start = picker.selectedStartDateMillis
                            val ende = picker.selectedEndDateMillis
                            if (start != null && ende != null) {
                                onUebernehmen(BerichtZeitraum.ausPicker(start, ende))
                            } else {
                                onUnvollstaendig()
                            }
                        },
                        modifier = Modifier.testTag("btn_bericht_zeitraum_uebernehmen"),
                    ) { Text(stringResource(R.string.action_apply)) }
                }
                DateRangePicker(state = picker, modifier = Modifier.weight(1f))
            }
        }
    }
}

private val DATUM_FORMAT = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy", Locale.GERMAN)
private val KURZ_FORMAT = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN)
private val LANG_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)
