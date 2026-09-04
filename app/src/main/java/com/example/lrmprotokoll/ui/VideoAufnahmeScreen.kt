package com.example.lrmprotokoll.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.data.BeweisVideoEntity
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import com.example.lrmprotokoll.video.VideoMuxPlanung
import com.example.lrmprotokoll.video.Videospeicher
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val VIDEO_AUFNAHME_STOP_TAG = "video_aufnahme_stop"

/**
 * Nimmt ein Beweisvideo zur laufenden Messung auf (M11 Etappe B, Owner-Auftrag "Videobeweis
 * starten waehrend Aufzeichnung").
 *
 * **Das Wichtigste an diesem Screen ist, was er NICHT tut:** Er ruft an keiner Stelle
 * `withAudioEnabled()` auf. Damit fasst die Kamera das Mikrofon nicht an, der
 * [AudioRecordingService] misst waehrend der ganzen Aufnahme ungestoert weiter, und die
 * Messreihe hat im Videozeitraum keine Luecke - das ist der Kern von Owner-Entscheidung E9 (V4).
 * Der Ton kommt aus derselben Aufnahmeschleife, die auch misst, und wird nach dem Stopp
 * einmultiplext.
 *
 * Vordergrundbetrieb mit harter Maximaldauer, ohne Foreground Service vom Typ `camera`: Ab
 * Android 14 darf ein solcher Dienst nicht aus dem Hintergrund gestartet werden, und fuer einen
 * Beweisclip von wenigen Minuten ist der Vordergrund ohnehin der richtige Ort.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAufnahmeScreen(
    onBack: () -> Unit,
    onShowSnackbar: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = (context.applicationContext as LaermprotokollApp).container
    val settings = container.settingsManager
    val diagnose = container.diagnosticsReporter
    val scope = rememberCoroutineScope()

    val maxDauerSekunden = remember { settings.videoMaxDauerSekunden }
    val mikrofonFormat by AudioRecordingService.laufendesFormat.collectAsState()

    var kameraErlaubt by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val berechtigung = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        kameraErlaubt = it
        if (!it) onShowSnackbar("Ohne Kamera-Berechtigung ist keine Videoaufnahme möglich")
    }
    LaunchedEffect(Unit) {
        if (!kameraErlaubt) berechtigung.launch(Manifest.permission.CAMERA)
    }

    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var aufnahme by remember { mutableStateOf<Recording?>(null) }
    var laeuft by remember { mutableStateOf(false) }
    var sekunden by remember { mutableLongStateOf(0L) }
    /** Verhindert, dass die automatisch startende Aufnahme nach dem Stopp erneut anlaeuft. */
    var abgeschlossen by remember { mutableStateOf(false) }

    val vorschau = remember { PreviewView(context) }

    DisposableEffect(kameraErlaubt) {
        if (kameraErlaubt) {
            val zukunft = ProcessCameraProvider.getInstance(context)
            zukunft.addListener({
                runCatching {
                    val anbieter = zukunft.get()
                    val qualitaet = if (settings.videoAufloesung == "FHD") Quality.FHD else Quality.HD
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(qualitaet, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    val preview = Preview.Builder().build().also { it.surfaceProvider = vorschau.surfaceProvider }

                    anbieter.unbindAll()
                    anbieter.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    videoCapture = capture
                }.onFailure { fehler ->
                    // Ein Kamerafehler darf die laufende Pegelmessung unter keinen Umstaenden
                    // beruehren - er endet hier in einer Meldung und einem Diagnoseeintrag.
                    diagnose.report(
                        code = DiagnosticCode.VIDEO_CAPTURE_FAILED,
                        component = "VideoAufnahmeScreen",
                        operation = "bindToLifecycle",
                        severity = DiagnosticSeverity.ERROR,
                        cause = fehler,
                    )
                    onShowSnackbar("Kamera konnte nicht geöffnet werden")
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            // Beides, und in dieser Reihenfolge: stop() beendet nur die Aufnahme, die Kamera
            // bliebe an den Lebenszyklus gebunden und liefe sichtbar weiter. Am Geraet sah das
            // so aus, als habe "Aufnahme beenden" nichts bewirkt.
            runCatching { aufnahme?.stop() }
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            videoCapture = null
        }
    }

    /**
     * Die Aufnahme startet von selbst, sobald die Kamera bereit ist.
     *
     * Der Owner-Auftrag ist eindeutig: Wer im Cockpit "Videobeweis" drueckt, will filmen - ein
     * zweiter Knopf auf dem naechsten Screen kostet nur die Sekunden, in denen das Geraeusch
     * noch da war. [abgeschlossen] verhindert, dass nach dem Stopp sofort eine zweite Aufnahme
     * anlaeuft.
     */
    LaunchedEffect(videoCapture, kameraErlaubt) {
        if (!kameraErlaubt || videoCapture == null || laeuft || abgeschlossen) return@LaunchedEffect
        starteAufnahme(
            context = context,
            container = container,
            videoCapture = videoCapture,
            mikrofonFormat = mikrofonFormat,
            scope = scope,
            onGestartet = { neueAufnahme ->
                aufnahme = neueAufnahme
                sekunden = 0
                laeuft = true
            },
            onBeendet = {
                laeuft = false
                aufnahme = null
                // Zurueck ins Cockpit: Die Kamera wird dabei ueber onDispose freigegeben, und
                // der Mux-Lauf laeuft im Hintergrund weiter. Sein Fortschritt steht im
                // Session-Detail, nicht in einer Anzeige, die niemand mehr beobachtet.
                onBack()
            },
            onShowSnackbar = onShowSnackbar,
        )
        // Konnte die Aufnahme nicht starten (kein Speicher, keine laufende Messung), meldet
        // starteAufnahme das bereits - dann bleibt der Screen stehen, ohne es erneut zu
        // versuchen.
        abgeschlossen = true
    }

    // Laufzeituhr und harte Maximaldauer.
    LaunchedEffect(laeuft) {
        while (laeuft) {
            delay(1000)
            sekunden += 1
            if (sekunden >= maxDauerSekunden) {
                onShowSnackbar("Maximaldauer erreicht – Aufnahme beendet")
                aufnahme?.stop()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Videobeweis") },
                navigationIcon = {
                    IconButton(onClick = { if (!laeuft) onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { innen ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innen),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (kameraErlaubt) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { vorschau },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("Kamera-Berechtigung erforderlich", color = Color.White)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Der Hinweis kommt VOR dem Start, nicht hinterher: Danach liesse sich am
                // fehlenden Ton nichts mehr aendern.
                if (mikrofonFormat == null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Das Mikrofon läuft nicht – dieses Video wird ohne Ton aufgezeichnet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = Videospeicher.formatiereDauer(sekunden),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "noch ${Videospeicher.formatiereDauer(maxDauerSekunden - sekunden)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!laeuft && !abgeschlossen) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            text = "  Kamera wird vorbereitet …",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Button(
                    onClick = {
                        // stop() liefert das Finalize-Ereignis; der Rest (Datenbank, Mux-Lauf)
                        // haengt daran. Der Screen schliesst sich danach von selbst - das gibt
                        // die Kamera frei und beantwortet "Aufnahme beenden" sichtbar.
                        abgeschlossen = true
                        aufnahme?.stop()
                    },
                    enabled = laeuft,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag(VIDEO_AUFNAHME_STOP_TAG),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text("  Aufnahme beenden")
                }
            }
        }
    }
}

/**
 * Startet die Aufnahme und haengt alles daran, was danach passieren muss. Steht ausserhalb der
 * Composable, damit der Ablauf am Stueck lesbar bleibt - er ist die eigentliche Fachlogik
 * dieses Screens.
 */
private suspend fun starteAufnahme(
    context: android.content.Context,
    container: com.example.lrmprotokoll.AppContainer,
    videoCapture: VideoCapture<Recorder>?,
    mikrofonFormat: AudioRecordingService.Aufnahmeformat?,
    scope: kotlinx.coroutines.CoroutineScope,
    onGestartet: (Recording) -> Unit,
    onBeendet: () -> Unit,
    onShowSnackbar: (String) -> Unit,
) {
    val capture = videoCapture ?: return
    val diagnose = container.diagnosticsReporter
    val dao = container.database.beweisVideoDao()
    val mitschnitt = container.videoTonMitschnitt

    val session = withContext(Dispatchers.IO) { container.database.sessionDao().offeneSession() }
    if (session == null) {
        // "Videobeweis starten waehrend Aufzeichnung" - ohne laufende Messung gibt es keinen
        // Anker, an dem das Video haengen koennte.
        onShowSnackbar("Es läuft keine Messung – Videobeweis ist nur währenddessen möglich")
        return
    }

    val verzeichnis = context.getExternalFilesDir(null)
    val frei = runCatching { StatFs(verzeichnis!!.path).availableBytes }.getOrDefault(0L)
    if (!Videospeicher.reichtSpeicher(frei)) {
        onShowSnackbar("Zu wenig freier Speicher für eine Videoaufnahme")
        return
    }

    val jetzt = System.currentTimeMillis()
    val videoDatei = File(verzeichnis, Videospeicher.dateiname(jetzt))
    val pcmDatei = File(verzeichnis, Videospeicher.tondateiname(jetzt))

    // Ton nur, wenn das Mikrofon tatsaechlich laeuft - und mit der vom AudioRecord ausgehandelten
    // Rate, nicht mit der eingestellten.
    val tonLaeuft = mikrofonFormat != null &&
        mitschnitt.starte(pcmDatei, mikrofonFormat.abtastrate, mikrofonFormat.kanaele)

    val videoId = withContext(Dispatchers.IO) {
        dao.insert(
            BeweisVideoEntity(
                sessionId = session.id,
                dateiPfad = videoDatei.absolutePath,
                gestartetAm = jetzt,
                dauerMs = 0,
                hatTonspur = false,
                groesseBytes = 0,
                // Ohne Ton gibt es nichts zu muxen - das Video ist sofort fertig.
                tonGemuxt = !tonLaeuft,
            )
        )
    }

    diagnose.breadcrumb(
        "Videobeweis",
        "Aufnahme gestartet (Session ${session.id}, Aufloesung ${container.settingsManager.videoAufloesung}, " +
            "Mikrofon ${if (tonLaeuft) "laeuft" else "aus"}, frei ${frei / (1024 * 1024)} MB)",
    )

    val ausgabe = FileOutputOptions.Builder(videoDatei).build()
    val aufnahme = runCatching {
        capture.output
            .prepareRecording(context, ausgabe)
            // KEIN withAudioEnabled(): In CameraX ist Audio opt-in. Bliebe dieser Aufruf hier
            // stehen, oeffnete die Kamera das Mikrofon, und die Pegelmessung bekaeme waehrend
            // der Videoaufnahme ein Loch - genau das, was Owner-Entscheidung E9 vermeidet. Der
            // Ton kommt aus dem laufenden AudioRecord und wird nachtraeglich einmultiplext.
            .start(ContextCompat.getMainExecutor(context)) { ereignis ->
                if (ereignis is VideoRecordEvent.Finalize) {
                    scope.launch {
                        beendeAufnahme(
                            context = context,
                            container = container,
                            videoId = videoId,
                            videoDatei = videoDatei,
                            pcmDatei = pcmDatei,
                            gestartetAm = jetzt,
                            fehlerhaft = ereignis.hasError(),
                            onShowSnackbar = onShowSnackbar,
                            onFertig = onBeendet,
                        )
                    }
                }
            }
    }.getOrElse { fehler ->
        mitschnitt.verwerfe()
        withContext(Dispatchers.IO) { dao.loesche(videoId) }
        diagnose.report(
            code = DiagnosticCode.VIDEO_CAPTURE_FAILED,
            component = "VideoAufnahmeScreen",
            operation = "start",
            severity = DiagnosticSeverity.ERROR,
            cause = fehler,
        )
        onShowSnackbar("Videoaufnahme konnte nicht gestartet werden")
        return
    }

    onGestartet(aufnahme)
}

private suspend fun beendeAufnahme(
    context: android.content.Context,
    container: com.example.lrmprotokoll.AppContainer,
    videoId: Long,
    videoDatei: File,
    pcmDatei: File,
    gestartetAm: Long,
    fehlerhaft: Boolean,
    onShowSnackbar: (String) -> Unit,
    onFertig: () -> Unit,
) {
    val diagnose = container.diagnosticsReporter
    val dao = container.database.beweisVideoDao()
    // Der Mitschnitt wird IMMER beendet - auch im Fehlerfall. Sonst liefe die PCM-Senke weiter
    // und schriebe den Speicher voll.
    val ton = container.videoTonMitschnitt.beende()

    if (fehlerhaft) {
        diagnose.report(
            code = DiagnosticCode.VIDEO_CAPTURE_FAILED,
            component = "VideoAufnahmeScreen",
            operation = "finalize",
            severity = DiagnosticSeverity.ERROR,
            details = mapOf("videoId" to videoId.toString()),
        )
        onShowSnackbar("Die Videoaufnahme ist fehlgeschlagen")
        withContext(Dispatchers.IO) { dao.loesche(videoId) }
        runCatching { videoDatei.delete() }
        runCatching { pcmDatei.delete() }
        onFertig()
        return
    }

    val dauer = System.currentTimeMillis() - gestartetAm
    val groesse = videoDatei.length()
    diagnose.breadcrumb("Videobeweis", "Aufnahme beendet (Video $videoId, ${dauer} ms, $groesse Bytes)")

    // Aktualisieren statt loeschen und neu einfuegen: Der Mux-Lauf ist bereits mit DIESER id
    // geplant, eine neue Zeile wuerde ihn ins Leere laufen lassen.
    withContext(Dispatchers.IO) { dao.setzeAufnahmeergebnis(videoId, dauer, groesse) }

    if (ton == null) {
        // Kein Ton mitgeschnitten - das Video ist so, wie es ist, fertig.
        onShowSnackbar("Video ohne Ton gespeichert")
        onFertig()
        return
    }

    VideoMuxPlanung.plane(
        context = context,
        videoId = videoId,
        pcmPfad = ton.datei.absolutePath,
        videoStartMs = gestartetAm,
        tonStartMs = ton.ersterBlockAm,
        abtastrate = ton.abtastrate,
        kanaele = ton.kanaele,
    )
    onShowSnackbar("Video gespeichert – der Ton wird im Hintergrund hinzugefügt")
    onFertig()
}
