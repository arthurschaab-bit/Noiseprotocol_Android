package com.example.lrmprotokoll.video

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lrmprotokoll.LaermprotokollApp
import com.example.lrmprotokoll.diagnose.DiagnosticCode
import com.example.lrmprotokoll.diagnose.DiagnosticSeverity
import java.io.File

const val VIDEO_MUX_VIDEO_ID = "videoId"
const val VIDEO_MUX_PCM_PFAD = "pcmPfad"
const val VIDEO_MUX_VIDEO_START = "videoStartMs"
const val VIDEO_MUX_TON_START = "tonStartMs"
const val VIDEO_MUX_ABTASTRATE = "abtastrate"
const val VIDEO_MUX_KANAELE = "kanaele"

private const val WORK_PRAEFIX = "video-mux-"

/**
 * Fuegt einem frisch aufgenommenen, stummen Beweisvideo die Tonspur hinzu (M11 Etappe B, B.2a).
 *
 * **Warum ein Worker und nicht der Aufnahme-Screen:** Der Mux-Lauf dauert je nach Laenge einige
 * Sekunden. Ein Nutzer, der danach sofort zurueck ins Cockpit geht, wuerde eine an den Screen
 * gebundene Verarbeitung abbrechen - und zurueck bliebe ein stummes Video plus einer PCM-Datei,
 * die niemand mehr zusammenfuehrt. Der Worker ueberlebt das Verlassen des Screens und laeuft
 * nach einem Prozess-Tod erneut an.
 *
 * Schlaegt der Lauf fehl, bleiben stumme MP4 **und** PCM-Datei erhalten und `tonGemuxt` bleibt
 * `false`: Ein stummes Video plus separater Tondatei ist immer noch ein Beweismittel; ein
 * geloeschtes Zwischenergebnis ist keines. Der Upload laesst ungemuxte Videos ohnehin liegen
 * ([com.example.lrmprotokoll.data.BeweisVideoDao.nichtHochgeladene]).
 */
class VideoMuxWorker @JvmOverloads constructor(
    context: Context,
    parameter: WorkerParameters,
    /** Testseam wie in [com.example.lrmprotokoll.messreihe.RetentionWorker]. */
    private val muxerOverride: VideoMuxer? = null,
) : CoroutineWorker(context, parameter) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LaermprotokollApp).container
        val dao = container.database.beweisVideoDao()
        val diagnose = container.diagnosticsReporter

        val videoId = inputData.getLong(VIDEO_MUX_VIDEO_ID, -1L)
        val pcmPfad = inputData.getString(VIDEO_MUX_PCM_PFAD)
        val videoStartMs = inputData.getLong(VIDEO_MUX_VIDEO_START, 0L)
        val tonStartMs = inputData.getLong(VIDEO_MUX_TON_START, 0L)
        val abtastrate = inputData.getInt(VIDEO_MUX_ABTASTRATE, 0)
        val kanaele = inputData.getInt(VIDEO_MUX_KANAELE, 1)

        // Fehlende Eingaben sind kein Wiederholungsgrund - ein erneuter Lauf haette dieselben.
        if (videoId <= 0 || pcmPfad.isNullOrBlank() || abtastrate <= 0) return Result.failure()

        val video = dao.byId(videoId) ?: return Result.failure()
        if (video.tonGemuxt) return Result.success()

        val stumm = File(video.dateiPfad)
        val pcm = File(pcmPfad)
        if (!stumm.exists() || !pcm.exists()) {
            diagnose.report(
                code = DiagnosticCode.VIDEO_MUX_FAILED,
                component = "VideoMuxWorker",
                operation = "doWork",
                severity = DiagnosticSeverity.ERROR,
                details = mapOf("videoId" to videoId.toString(), "grund" to "Quelldatei fehlt"),
            )
            return Result.failure()
        }

        val versatzMs = videoStartMs - tonStartMs
        diagnose.breadcrumb(
            "Videobeweis",
            "Mux-Lauf gestartet (Video $videoId, Versatz ${versatzMs} ms, ${abtastrate} Hz, $kanaele Kanal/Kanaele)",
        )

        val ziel = File(stumm.parentFile, "${stumm.nameWithoutExtension}_ton.mp4")
        return runCatching {
            (muxerOverride ?: VideoMuxer()).fuegeTonHinzu(
                stummesVideo = stumm,
                pcm = pcm,
                ziel = ziel,
                videoStartMs = videoStartMs,
                tonStartMs = tonStartMs,
                abtastrate = abtastrate,
                kanaele = kanaele,
            )
        }.fold(
            onSuccess = { groesse ->
                dao.setzeGemuxt(videoId, ziel.absolutePath, groesse, hatTonspur = true)
                // Erst NACH dem erfolgreichen Datenbankeintrag aufraeumen: Waere der Eintrag
                // fehlgeschlagen, zeigte die Zeile noch auf die stumme Datei.
                runCatching { stumm.delete() }
                runCatching { pcm.delete() }
                diagnose.breadcrumb("Videobeweis", "Mux-Lauf fertig (Video $videoId, $groesse Bytes)")
                Result.success()
            },
            onFailure = { fehler ->
                diagnose.report(
                    code = DiagnosticCode.VIDEO_MUX_FAILED,
                    component = "VideoMuxWorker",
                    operation = "fuegeTonHinzu",
                    severity = DiagnosticSeverity.ERROR,
                    cause = fehler,
                    details = mapOf("videoId" to videoId.toString()),
                )
                // Halbfertige Zieldatei wegraeumen - stumme MP4 und PCM bleiben ausdruecklich
                // liegen, damit der Beleg nicht verlorengeht.
                runCatching { ziel.delete() }
                Result.failure()
            },
        )
    }
}

object VideoMuxPlanung {

    /**
     * `REPLACE` mit einer je Video eindeutigen Kennung: Zwei Laeufe fuer dasselbe Video wuerden
     * sich um dieselbe Zieldatei streiten, Laeufe fuer verschiedene Videos duerfen sich aber
     * nicht gegenseitig verdraengen.
     */
    fun plane(
        context: Context,
        videoId: Long,
        pcmPfad: String,
        videoStartMs: Long,
        tonStartMs: Long,
        abtastrate: Int,
        kanaele: Int,
    ) {
        try {
            val daten = Data.Builder()
                .putLong(VIDEO_MUX_VIDEO_ID, videoId)
                .putString(VIDEO_MUX_PCM_PFAD, pcmPfad)
                .putLong(VIDEO_MUX_VIDEO_START, videoStartMs)
                .putLong(VIDEO_MUX_TON_START, tonStartMs)
                .putInt(VIDEO_MUX_ABTASTRATE, abtastrate)
                .putInt(VIDEO_MUX_KANAELE, kanaele)
                .build()

            val anfrage = OneTimeWorkRequestBuilder<VideoMuxWorker>().setInputData(daten).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_PRAEFIX$videoId",
                ExistingWorkPolicy.REPLACE,
                anfrage,
            )
        } catch (e: Throwable) {
            android.util.Log.w("VideoMuxPlanung", "WorkManager konnte nicht aufgerufen werden", e)
        }
    }
}
