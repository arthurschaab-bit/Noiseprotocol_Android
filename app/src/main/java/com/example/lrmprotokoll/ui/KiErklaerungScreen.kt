package com.example.lrmprotokoll.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrmprotokoll.ui.components.NoiseCard

/**
 * Erklaert im laufenden Betrieb, wie die Laermerkennung arbeitet und wo ihre Grenzen liegen.
 *
 * Bewusst IN DER APP und nicht als Datei im Repository: Wer wissen will, was "Möglicher Baulärm
 * · 41 %" bedeutet, sitzt vor dem Telefon und nicht vor einem Git-Checkout. Die technische
 * Herleitung bleibt Entwicklerdokumentation; hier steht, was ein Nutzer zum Verstehen und
 * Einordnen der angezeigten Werte braucht - einschliesslich der Unschaerfen, denn ein
 * Beweismittel, dessen Grenzen man nicht kennt, ist im Zweifel wertlos.
 */
private data class Abschnitt(val titel: String, val absaetze: List<String>)

private val ABSCHNITTE = listOf(
    Abschnitt(
        "Die Grundidee",
        listOf(
            "Das Telefon hört mit. Immer wenn es lauter wird als die eingestellte Schwelle, nimmt " +
                "es einen kurzen Ausschnitt auf – nicht nur den lauten Moment, sondern auch ein paar " +
                "Sekunden davor, damit der Anfang eines Geräuschs nicht fehlt.",
            "Diese Aufnahme bekommt eine künstliche Intelligenz namens YAMNet zu hören, die darauf " +
                "trainiert ist, Alltagsgeräusche zu unterscheiden – Sprache, Musik, Verkehr, Tiere, " +
                "und eben auch Hämmern, Bohren oder einen Presslufthammer.",
            "Sie läuft vollständig auf dem Telefon. Es wird nichts ins Internet geschickt und " +
                "nichts in einer Cloud ausgewertet.",
        ),
    ),
    Abschnitt(
        "Was die App nicht tut",
        listOf(
            "Die KI hört sich nicht die ganze Aufnahme an und sagt dann „das war ein " +
                "Presslufthammer\". Sie hört in vielen kleinen Zeitscheiben von rund einer Sekunde hin " +
                "und schätzt für jede einzelne, wie wahrscheinlich verschiedene Geräuschkategorien sind.",
            "Erst aus dieser Zeitreihe entsteht ein Gesamturteil – nicht andersherum. Deshalb steht " +
                "in der App nicht „erkannt: Presslufthammer\", sondern etwas wie: „Baulärm · 68 % der " +
                "Aufnahme · längster Block 4:12 · Spitze: Hämmern\".",
            "Der Prozentwert sagt also, in welchem Anteil der Aufnahme ein baustellentypisches " +
                "Geräusch erkennbar war. Der längste Block sagt, wie lange am Stück – und das ist für " +
                "eine Beschwerde oft die wichtigere Zahl.",
        ),
    ),
    Abschnitt(
        "Die vier möglichen Aussagen",
        listOf(
            "Baulärm – deutliches, teils lang anhaltendes baustellentypisches Geräusch erkannt.",
            "Möglicher Baulärm – etwas Baustellentypisches war kurz oder schwach zu hören, aber " +
                "nicht deutlich genug für eine sichere Aussage.",
            "Kein Baulärm erkannt – die Aufnahme wurde ausgewertet, es wurde nichts " +
                "Baustellentypisches gefunden.",
            "Unklar – die Auswertung selbst konnte nicht durchgeführt werden, etwa bei einer " +
                "technisch fehlerhaften Aufnahme. Das heißt ausdrücklich nicht „vielleicht doch Baulärm\".",
            "Wichtig: „Kein Baulärm erkannt\" ist etwas anderes als „noch nicht ausgewertet\". Eine " +
                "Aufnahme, die noch nicht durch die KI gelaufen ist, zeigt gar keinen dieser Texte an. " +
                "Das ist bewusst so gebaut, damit man beides nie verwechselt.",
        ),
    ),
    Abschnitt(
        "Zwei Zusätze, die auftauchen können",
        listOf(
            "„Gelernt: …\" – die Aufnahme ähnelt stark einem Geräusch, das Sie der App vorher selbst " +
                "als Referenz beigebracht haben. Das hat Vorrang vor der automatischen Einstufung: " +
                "eine bekannte, konkrete Quelle ist eine stärkere Aussage als eine allgemeine Kategorie.",
            "„impulsiv, 12 Hz\" – das Geräusch wurde nicht über seinen Klang erkannt, sondern über " +
                "sein Schwingungsmuster: kurze, kräftige, regelmäßig wiederkehrende Stöße, wie sie ein " +
                "Presslufthammer oder eine Rüttelplatte erzeugt. Das ist eine zweite, von der KI " +
                "völlig unabhängige Prüfung.",
        ),
    ),
    Abschnitt(
        "Warum das nicht hundertprozentig sicher ist",
        listOf(
            "Eine KI, die auf allgemeinen Alltagsgeräuschen trainiert wurde, ersetzt kein " +
                "Fachgutachten. Sie kann sich in beide Richtungen täuschen. Die App ist bewusst so " +
                "gebaut, dass Fehler eher dokumentiert als versteckt werden.",
            "Das Modell kennt keine Kategorie „Baustelle\". Es kennt Werkzeuggeräusche – und eine " +
                "Bohrmaschine in der eigenen Wohnung klingt für die KI genauso wie eine auf der " +
                "Baustelle nebenan. Die App kann nicht unterscheiden, woher ein Geräusch kommt.",
            "Die Zeitscheiben sind rund eine Sekunde lang und überlappen nicht. Ein einzelner kurzer " +
                "Schlag kann deshalb in der Prozentangabe untergehen, obwohl er deutlich zu hören war.",
            "Die Schwellenwerte sind dokumentierte Startwerte, keine kalibrierten Größen. Sie wurden " +
                "plausibel gewählt, nicht an einer bekannten Sammlung echter Baustellenaufnahmen " +
                "geeicht. Wie gut sie zu Ihrer Situation passen, zeigt sich erst im Gebrauch.",
            "Das Ergebnis hängt auch vom Telefon ab: Mikrofonlage, Gehäuse und Hersteller-" +
                "Audioverarbeitung beeinflussen, was überhaupt bei der KI ankommt.",
        ),
    ),
    Abschnitt(
        "Was das für Sie praktisch bedeutet",
        listOf(
            "Die App speichert zu jeder Aufnahme sehr viele Rohdaten, nicht nur das fertige Wort. " +
                "Stellt sich später heraus, dass die Einstellungen nachjustiert werden müssen, können " +
                "alle bisherigen Aufnahmen neu bewertet werden, ohne sie erneut abzuhören – " +
                "Menü „Neu bewerten\".",
            "Behandeln Sie die Einstufung als Hinweis, nicht als Beweis. Der Beweis ist die " +
                "Aufnahme selbst, der gemessene Pegel und – wenn vorhanden – der kalibrierte Wert " +
                "des Messgeräts.",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiErklaerungScreen(
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wie die Lärmerkennung arbeitet") },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Menü")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
            )
        }
    ) { innenAbstand ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innenAbstand),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(ABSCHNITTE) { abschnitt ->
                NoiseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        Text(
                            abschnitt.titel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        abschnitt.absaetze.forEach { absatz ->
                            Text(
                                absatz,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
