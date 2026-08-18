package com.example.lrmprotokoll.alert.heartbeat

import com.example.lrmprotokoll.data.SettingsManager
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Das Lebenszeichen der Totmannschaltung (Plan Abschnitt 7.5).
 *
 * **Warum es das ueberhaupt gibt.** Jeder Alarm ist eine *ausgehende* Nachricht des
 * Ueberwachungsgeraets. Kein Alarmkanal deckt deshalb den wahrscheinlichsten Ausfall im
 * Dauerbetrieb ab: dass dieses Geraet selbst stirbt - Akku leer, ROM killt den Dienst, App
 * abgestuerzt. Dann kommt schlicht gar nichts, und der Ausfall bleibt tagelang unbemerkt. Die
 * einzige Konstruktion, die das abfaengt, ist die umgekehrte Richtung: Bleibt das Lebenszeichen
 * aus, alarmiert die Gegenseite von sich aus.
 *
 * **Seit dem Wegfall des SMS-Kanals traegt sie mehr.** Plan 7.4 setzte SMS und Push nebeneinander,
 * weil sie an unterschiedlichen Ausfaellen scheitern: ohne Internet traegt SMS, ohne Mobilfunk
 * traegt Push. Mit ntfy als einzigem Fernkanal ist "Internet weg" nicht mehr durch einen zweiten
 * Kanal abgedeckt - wohl aber hierdurch, denn ohne Internet bleibt auch der Ping aus und die
 * Gegenseite meldet sich.
 *
 * **Was es NICHT ist.** Es bestaetigt "App und Geraet leben", nicht "Messgeraet verbunden".
 * Deshalb kennt diese Klasse den Verbindungszustand bewusst nicht einmal als Parameter: Haenge
 * der Ping am BLE-Zustand, wuerde ein ganz normaler Verbindungsabbruch zusaetzlich einen
 * Totmann-Alarm ausloesen, und beide Signale waeren nicht mehr unterscheidbar. Diese Zusage ist
 * durch die Signatur erzwungen, nicht durch einen Test - ein Test dafuer koennte in keiner
 * Fassung fehlschlagen und gehoerte damit nicht in die Suite.
 */
class HeartbeatPinger(
    private val settings: SettingsManager,
    private val client: OkHttpClient = OkHttpClient(),
) {

    enum class Ergebnis {
        /** Ping abgesetzt und angenommen. */
        GESENDET,

        /** Kein Ping noetig: keine URL hinterlegt oder die Ueberwachung laeuft gar nicht. */
        UEBERSPRUNGEN,

        /** Ping fehlgeschlagen - der Aufrufer soll es spaeter erneut versuchen. */
        FEHLGESCHLAGEN,
    }

    suspend fun ping(): Ergebnis {
        val url = settings.heartbeatUrl
        // Leere URL heisst aus. Niemand wird zu einem Fremddienst gezwungen, und ohne diese
        // Abkuerzung wuerde WorkManager alle 15 Minuten sinnlos einen Fehlschlag wiederholen.
        if (url.isBlank()) return Ergebnis.UEBERSPRUNGEN

        // Laeuft die Ueberwachung nicht, gibt es nichts zu ueberwachen: Ein Ping waere gelogen,
        // ein ausbleibender Ping wuerde die Gegenseite alarmieren, obwohl der Nutzer die
        // Ueberwachung bewusst ausgeschaltet hat. Das ist KEINE Abhaengigkeit vom
        // Verbindungszustand - nur davon, ob ueberwacht werden soll.
        if (!settings.monitoringWasActive) return Ergebnis.UEBERSPRUNGEN

        return runCatching { client.hole(url) }
            .fold(onSuccess = { Ergebnis.GESENDET }, onFailure = { Ergebnis.FEHLGESCHLAGEN })
    }
}

/**
 * Die Ping-URL ist bei healthchecks.io und vergleichbaren Diensten eine Capability-URL: Wer sie
 * kennt, kann die Totmannschaltung stillstellen. Sie wird deshalb genau wie das ntfy-Topic
 * behandelt und taucht in keiner Fehlermeldung auf - OkHttp packt sie ungefragt in seine
 * Ausnahmen.
 */
private suspend fun OkHttpClient.hole(url: String) =
    suspendCancellableCoroutine { fortsetzung ->
        val call = newCall(Request.Builder().url(url).get().build())
        fortsetzung.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fortsetzung.resumeWithException(IOException("Heartbeat-Dienst nicht erreichbar"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        fortsetzung.resumeWith(Result.success(Unit))
                    } else {
                        fortsetzung.resumeWithException(
                            IOException("Heartbeat-Dienst antwortete mit HTTP ${it.code}")
                        )
                    }
                }
            }
        })
    }
