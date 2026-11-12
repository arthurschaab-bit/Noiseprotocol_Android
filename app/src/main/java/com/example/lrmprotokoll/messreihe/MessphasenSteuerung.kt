package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.KALIBRIER_SOLLWERT_DB
import com.example.lrmprotokoll.data.KalibrierEnde
import com.example.lrmprotokoll.data.KalibrierMesswertEntity
import com.example.lrmprotokoll.data.KalibrierphaseDao
import com.example.lrmprotokoll.data.KalibrierphaseEntity
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Vorbereitungsphase vor dem eigentlichen Messen (Owner-Auftrag 12.11.2026). */
enum class Messphase {
    /** Normalbetrieb: Werte fliessen in Messreihe, Tageswerte und Ereignisse. */
    KEINE,

    /** Messgeraet misst den Kalibrator; Werte landen nur in der Kalibriertabelle. */
    KALIBRIERUNG,

    /** Geraet wird aufgebaut, Konfiguration und Fotos werden erfasst; noch keine Messwerte. */
    AUFBAU,
}

/**
 * Steuert Kalibrier- und Aufbauphase am manuellen Messstart.
 *
 * **Solange [phase] nicht [Messphase.KEINE] ist, ist [erfassungPausiert] `true`** - die
 * Aufnahmepfade ([MeasurementRecorder], Pegelsammler, Ereignis-Trigger) pruefen es und
 * schreiben nichts. Das ist ein bewusst begrenzter Eingriff in die Datenerfassung: Eine
 * vergessene Phase wuerde Messwerte kosten, deshalb enden beide nach einer Hoechstdauer von
 * selbst ([hoechstdauerKalibrierung], [hoechstdauerAufbau]) und kehren zu [Messphase.KEINE]
 * zurueck. Ein Prozessende setzt die Phase ebenfalls auf KEINE zurueck (kein persistierter
 * Zustand): im Zweifel wird gemessen.
 *
 * Automatische Starts (Wiederaufnahme nach Absturz, Neustart, Waechter) rufen [startManuell]
 * nie auf - dort ist niemand am Display, die Messung darf nicht blockieren.
 */
class MessphasenSteuerung(
    private val dao: KalibrierphaseDao,
    /** Pegel des Messgeraets als (Zeitstempel in ms, dB). */
    private val messgeraetPegel: Flow<Pair<Long, Double>>,
    private val scope: CoroutineScope,
    private val jetzt: () -> Long = System::currentTimeMillis,
    private val hoechstdauerKalibrierung: Duration = Duration.ofMinutes(30),
    private val hoechstdauerAufbau: Duration = Duration.ofMinutes(60),
    private val onBreadcrumb: ((String, Map<String, Any?>) -> Unit)? = null,
) {
    private val _phase = MutableStateFlow(Messphase.KEINE)
    val phase: StateFlow<Messphase> = _phase.asStateFlow()

    private val _erfassungPausiert = MutableStateFlow(false)

    /** `true`, solange Kalibrier- oder Aufbauphase laufen. */
    val erfassungPausiert: StateFlow<Boolean> = _erfassungPausiert.asStateFlow()

    private val _anzeige = MutableStateFlow(KalibrierAnzeige())
    val anzeige: StateFlow<KalibrierAnzeige> = _anzeige.asStateFlow()

    private var kalibrierJob: Job? = null
    private var zeitwaechter: Job? = null
    private var phaseId: Long? = null
    private var gestartetAm: Long = 0

    /**
     * Beginnt die Vorbereitung. Ohne Messgeraet gibt es nichts zu kalibrieren (das Mikrofon ist
     * unkalibriert), dann beginnt direkt der Aufbau. Eine laufende Phase wird nicht neu gestartet.
     */
    fun startManuell(messgeraetErwartet: Boolean) {
        if (_phase.value != Messphase.KEINE) return
        _anzeige.value = KalibrierAnzeige()
        if (messgeraetErwartet) beginneKalibrierung() else beginneAufbau("OHNE_MESSGERAET")
    }

    private fun beginneKalibrierung() {
        setzePhase(Messphase.KALIBRIERUNG)
        gestartetAm = jetzt()
        meldeKurz("Kalibrierphase gestartet", emptyMap())
        val erkennung = KalibrierErkennung()
        kalibrierJob = scope.launch {
            val phaseIdNeu = dao.insert(KalibrierphaseEntity(startedAt = gestartetAm))
            phaseId = phaseIdNeu
            val puffer = mutableListOf<KalibrierMesswertEntity>()
            try {
                messgeraetPegel.collect { (zeit, db) ->
                    erkennung.nimm(zeit, db)
                    _anzeige.value = erkennung.anzeige()
                    puffer += KalibrierMesswertEntity(phaseId = phaseIdNeu, timestamp = zeit, levelDb = db)
                    if (puffer.size >= FLUSH_GROESSE) {
                        dao.insertMesswerte(puffer.toList())
                        puffer.clear()
                    }
                }
            } finally {
                // Auch bei Abbruch (Phase beendet) bleibt der Rest erhalten. NonCancellable ist nicht
                // noetig: [beenden] wartet per cancelAndJoin und schreibt danach selbst.
                restPuffer = puffer.toList()
            }
        }
        starteZeitwaechter(Messphase.KALIBRIERUNG, hoechstdauerKalibrierung)
    }

    @Volatile private var restPuffer: List<KalibrierMesswertEntity> = emptyList()

    private fun beginneAufbau(grund: String) {
        setzePhase(Messphase.AUFBAU)
        meldeKurz("Aufbauphase gestartet", mapOf("grund" to grund))
        starteZeitwaechter(Messphase.AUFBAU, hoechstdauerAufbau)
    }

    /**
     * Beendet die Kalibrierung und beginnt den Aufbau. [vorher]/[nachher] sind die vom Nutzer
     * bestaetigten oder korrigierten Abweichungen in dB (Anzeige minus Kalibratorpegel);
     * [korrigiert] vermerkt, dass sie von der Erkennung abweichen.
     */
    fun kalibrierungBeenden(vorher: Double?, nachher: Double?, korrigiert: Boolean, grund: String) {
        if (_phase.value != Messphase.KALIBRIERUNG) return
        beendeKalibrierJob(vorher, nachher, korrigiert, grund)
        beginneAufbau("NACH_KALIBRIERUNG")
    }

    /** Bewusst ohne Kalibrierung: der Verzicht wird festgehalten und der Aufbau beginnt. */
    fun kalibrierungUeberspringen() {
        if (_phase.value != Messphase.KALIBRIERUNG) return
        beendeKalibrierJob(null, null, false, KalibrierEnde.UEBERSPRUNGEN)
        beginneAufbau("KALIBRIERUNG_UEBERSPRUNGEN")
    }

    /** Aufbau erledigt (oder bewusst uebersprungen): ab jetzt zaehlen die Messwerte. */
    fun aufbauAbschliessen() {
        if (_phase.value != Messphase.AUFBAU) return
        zeitwaechter?.cancel()
        setzePhase(Messphase.KEINE)
        meldeKurz("Messung beginnt", emptyMap())
    }

    private fun beendeKalibrierJob(vorher: Double?, nachher: Double?, korrigiert: Boolean, grund: String) {
        val job = kalibrierJob
        val id = phaseId
        val ende = jetzt()
        kalibrierJob = null
        phaseId = null
        zeitwaechter?.cancel()
        meldeKurz(
            "Kalibrierphase beendet",
            mapOf("grund" to grund, "vorherDb" to vorher, "nachherDb" to nachher, "korrigiert" to korrigiert),
        )
        scope.launch {
            job?.cancelAndJoin()
            if (id != null) {
                val rest = restPuffer
                restPuffer = emptyList()
                if (rest.isNotEmpty()) dao.insertMesswerte(rest)
                dao.byId(id)?.let {
                    dao.update(
                        it.copy(
                            endedAt = ende,
                            sollwertDb = KALIBRIER_SOLLWERT_DB,
                            abweichungVorherDb = vorher,
                            abweichungNachherDb = nachher,
                            abweichungKorrigiert = korrigiert,
                            endeGrund = grund,
                        ),
                    )
                }
            }
        }
    }

    private fun starteZeitwaechter(fuerPhase: Messphase, dauer: Duration) {
        zeitwaechter?.cancel()
        zeitwaechter = scope.launch {
            delay(dauer.toMillis())
            if (_phase.value != fuerPhase) return@launch
            meldeKurz("Phase nach Hoechstdauer beendet - Messung laeuft weiter", mapOf("phase" to fuerPhase.name))
            if (fuerPhase == Messphase.KALIBRIERUNG) {
                beendeKalibrierJob(null, null, false, KalibrierEnde.ZEITUEBERSCHREITUNG)
            }
            setzePhase(Messphase.KEINE)
        }
    }

    private fun setzePhase(neu: Messphase) {
        _phase.value = neu
        _erfassungPausiert.value = neu != Messphase.KEINE
    }

    private fun meldeKurz(text: String, daten: Map<String, Any?>) {
        runCatching { onBreadcrumb?.invoke(text, daten) }
    }

    private companion object {
        const val FLUSH_GROESSE = 20
    }
}
