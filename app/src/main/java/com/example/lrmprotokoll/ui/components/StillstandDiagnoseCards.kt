package com.example.lrmprotokoll.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.R
import com.example.lrmprotokoll.diagnose.AusfallAntwort
import com.example.lrmprotokoll.diagnose.AusfallKlasse
import com.example.lrmprotokoll.diagnose.AusfallNachfrageEintrag
import com.example.lrmprotokoll.diagnose.AusfallNachfrageLogik
import com.example.lrmprotokoll.diagnose.HintergrundbeschraenkungBefund
import com.example.lrmprotokoll.diagnose.TotmannschaltungZustand

const val AUSFALL_NACHFRAGE_CARD_TAG = "card_ausfall_nachfrage"
const val AUSFALL_NACHFRAGE_DISMISS_TAG = "btn_ausfall_nachfrage_dismiss"
const val HINTERGRUNDBESCHRAENKUNG_CARD_TAG = "card_hintergrundbeschraenkung"
const val HINTERGRUNDBESCHRAENKUNG_BUTTON_TAG = "btn_hintergrundbeschraenkung_oeffnen"
const val TOTMANNSCHALTUNG_WARNUNG_CARD_TAG = "card_totmannschaltung_warnung"
const val TOTMANNSCHALTUNG_EINRICHTEN_BUTTON_TAG = "btn_totmannschaltung_einrichten"

@StringRes
fun AusfallKlasse.labelRes(): Int =
    when (this) {
        AusfallKlasse.NUTZER_STOPP -> R.string.ausfall_klasse_nutzer_stopp
        AusfallKlasse.AKKU_LEER -> R.string.ausfall_klasse_akku_leer
        AusfallKlasse.KERNEL_NEUSTART -> R.string.ausfall_klasse_kernel_neustart
        AusfallKlasse.FRAMEWORK_NEUSTART -> R.string.ausfall_klasse_framework_neustart
        AusfallKlasse.STILLSTAND_GERAET_LIEF -> R.string.ausfall_klasse_stillstand_geraet_lief
        AusfallKlasse.UNBEKANNT -> R.string.ausfall_klasse_unbekannt
    }

@StringRes
fun AusfallAntwort.labelRes(): Int =
    when (this) {
        AusfallAntwort.SELBST_BEENDET -> R.string.ausfall_antwort_selbst_beendet
        AusfallAntwort.HANDY_AUS_ODER_AKKU_LEER -> R.string.ausfall_antwort_handy_aus_oder_akku_leer
        AusfallAntwort.EINGEFROREN -> R.string.ausfall_antwort_eingefroren
        AusfallAntwort.APP_WEG_HANDY_LIEF -> R.string.ausfall_antwort_app_weg_handy_lief
        AusfallAntwort.UNKLAR -> R.string.ausfall_antwort_unklar
    }

/**
 * Karte "Was ist passiert?" (Schritt 6 aus docs/PROMPT_DIAGNOSE_STILLSTAND.md):
 * Zeigt Zeitraum und Dauer der Lücke, die Einordnung der App als Hinweis sowie genau fünf
 * feste Antwort-Knöpfe (kein Freitext). Optional wegwischbar (auf dem Hauptbildschirm).
 */
@Composable
fun AusfallNachfrageCard(
    eintrag: AusfallNachfrageEintrag,
    onAntwort: (AusfallAntwort) -> Unit,
    onWegwischen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val vonText = AusfallNachfrageLogik.formatiereZeitpunkt(eintrag.letzteDatenAt)
    val bisText = AusfallNachfrageLogik.formatiereZeitpunkt(eintrag.entdecktAt)
    val dauerText = AusfallNachfrageLogik.formatiereDauer(eintrag.dauerMillis)
    val einordnungText = stringResource(eintrag.klasse.labelRes())

    Card(
        modifier = modifier.fillMaxWidth().testTag("${AUSFALL_NACHFRAGE_CARD_TAG}_${eintrag.letzteDatenAt}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ausfall_nachfrage_titel),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (onWegwischen != null) {
                    IconButton(
                        onClick = onWegwischen,
                        modifier = Modifier.size(36.dp).testTag(AUSFALL_NACHFRAGE_DISMISS_TAG),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.ausfall_nachfrage_wegwischen),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ausfall_nachfrage_zeitraum, vonText, bisText, dauerText),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.ausfall_nachfrage_einordnung_hinweis, einordnungText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            )

            Spacer(modifier = Modifier.height(10.dp))
            AusfallAntwort.entries.forEach { antwort ->
                OutlinedButton(
                    onClick = { onAntwort(antwort) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .testTag("btn_ausfall_antwort_${antwort.code}_${eintrag.letzteDatenAt}"),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                ) {
                    Text(
                        text = stringResource(antwort.labelRes()),
                        fontWeight = if (antwort == AusfallAntwort.UNKLAR) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Hinweis auf Hintergrundbeschränkung (Schritt 7 aus docs/PROMPT_DIAGNOSE_STILLSTAND.md):
 * Zeigt den Hinweis im Diagnose-Bildschirm und (wenn zutreffend) im Hauptbildschirm, mit einem
 * Aktionsknopf, der die Huawei-App-Start-Verwaltung per `try/catch` und Fallback auf die
 * allgemeinen App-Infos öffnet.
 */
@Composable
fun HintergrundbeschraenkungHinweisCard(
    befund: HintergrundbeschraenkungBefund,
    onOeffneEinstellungen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!befund.aktiv) return

    val hinweisText =
        if (befund.zeigtHuaweiAppStartAnleitung) {
            stringResource(R.string.hintergrundbeschraenkung_waechter_text)
        } else {
            stringResource(R.string.hintergrundbeschraenkung_allgemein_text)
        }

    Card(
        modifier = modifier.fillMaxWidth().testTag(HINTERGRUNDBESCHRAENKUNG_CARD_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.hintergrundbeschraenkung_titel),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hinweisText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = onOeffneEinstellungen,
                modifier = Modifier.align(Alignment.End).testTag(HINTERGRUNDBESCHRAENKUNG_BUTTON_TAG),
            ) {
                Text(
                    text = stringResource(R.string.hintergrundbeschraenkung_aktion_oeffnen),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Warnhinweis "Kein Schutz bei Ausfall" (Schritt 8 aus docs/PROMPT_DIAGNOSE_STILLSTAND.md):
 * Nicht wegwischbar solange die Aufzeichnung läuft und der Zustand nicht [TotmannschaltungZustand.OK]
 * ist. Im Zustand [TotmannschaltungZustand.OK] wird gar nichts gerendert.
 */
@Composable
fun TotmannschaltungWarnungZeile(
    zustand: TotmannschaltungZustand,
    onEinrichtenClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hinweisRes =
        when (zustand) {
            TotmannschaltungZustand.NICHT_EINGERICHTET -> R.string.totmann_warnung_nicht_eingerichtet
            TotmannschaltungZustand.NICHT_GEPLANT -> R.string.totmann_warnung_nicht_geplant
            TotmannschaltungZustand.SENDET_NICHT -> R.string.totmann_warnung_sendet_nicht
            TotmannschaltungZustand.OK -> return
        }

    Card(
        modifier = modifier.fillMaxWidth().testTag(TOTMANNSCHALTUNG_WARNUNG_CARD_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(hinweisRes),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onEinrichtenClick != null) {
                TextButton(
                    onClick = onEinrichtenClick,
                    modifier = Modifier.testTag(TOTMANNSCHALTUNG_EINRICHTEN_BUTTON_TAG),
                ) {
                    Text(
                        text = stringResource(R.string.totmann_aktion_einrichten),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
