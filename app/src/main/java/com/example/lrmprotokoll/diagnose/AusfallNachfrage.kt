package com.example.lrmprotokoll.diagnose

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val EREIGNIS_AUSFALL_EINGEORDNET = "Ausfall eingeordnet"
const val EREIGNIS_AUSFALL_WAECHTERSPUR_KORRIGIERT = "Ausfall-Waechterspur korrigiert"
const val EREIGNIS_AUSFALL_NUTZERANGABE = "Ausfall-Nutzerangabe erfasst"

/**
 * Die genau fuenf festen Antwortmoeglichkeiten zur Einordnung einer Messluecke (Schritt 6 aus
 * docs/PROMPT_DIAGNOSE_STILLSTAND.md, Owner-Entscheidung 02.11.2026: E1).
 *
 * Bewusst kein Freitext, damit keine personenbezogenen Daten in das Support-Bundle gelangen.
 */
enum class AusfallAntwort(
    val code: String,
) {
    SELBST_BEENDET("SELBST_BEENDET"),
    HANDY_AUS_ODER_AKKU_LEER("HANDY_AUS_ODER_AKKU_LEER"),
    EINGEFROREN("EINGEFROREN"),
    APP_WEG_HANDY_LIEF("APP_WEG_HANDY_LIEF"),
    UNKLAR("UNKLAR"),
    ;

    companion object {
        fun vonCode(code: String?): AusfallAntwort? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Zusammengeführter Ausfall-Eintrag aus der Lebenszyklus-Ringdatei inklusive nachträglicher
 * Wächter-Korrektur und optionaler Nutzerangabe.
 */
data class AusfallNachfrageEintrag(
    val timestampMillis: Long,
    val letzteDatenAt: Long,
    val entdecktAt: Long,
    val lueckeMinuten: Long,
    val klasse: AusfallKlasse,
    val waechterLief: WaechterLief,
    val nutzerAngabe: AusfallAntwort?,
    val belege: Map<String, Any?>,
) {
    val dauerMillis: Long
        get() = (entdecktAt - letzteDatenAt).coerceAtLeast(lueckeMinuten * 60_000L)
}

/**
 * Reine Auswahl- und Zusammenführungslogik für Schritt 6 ("Was ist passiert?"):
 * - Fragt nur nach eingeordneten Ausfällen ab 30 Minuten Dauer.
 * - Fragt NICHT bei [AusfallKlasse.NUTZER_STOPP] und [AusfallKlasse.AKKU_LEER].
 * - Jede Lücke (Schlüssel: `letzteDatenAt`) wird auf dem Hauptbildschirm höchstens einmal gefragt.
 * - Unbeantwortete Lücken bleiben im Diagnose-Bildschirm abrufbar (höchstens die letzten 20).
 */
object AusfallNachfrageLogik {
    const val MINDEST_DAUER_FUER_NACHFRAGE_MINUTEN = 30L
    const val MINDEST_DAUER_FUER_NACHFRAGE_MS = MINDEST_DAUER_FUER_NACHFRAGE_MINUTEN * 60_000L
    const val MAX_EINTRAEGE = 20

    private val AUSGENOMMENE_KLASSEN =
        setOf(
            AusfallKlasse.NUTZER_STOPP,
            AusfallKlasse.AKKU_LEER,
        )

    /**
     * Liest und korreliert die letzten bis zu [MAX_EINTRAEGE] eingeordneten Ausfälle aus den
     * Lebenszyklus-Einträgen (inklusive Wächter-Korrekturen und Nutzerangaben).
     */
    fun leseAusfaelle(eintraege: List<LebenszyklusEintrag>): List<AusfallNachfrageEintrag> {
        val waechterKorrekturen =
            eintraege
                .filter { it.ereignis == EREIGNIS_AUSFALL_WAECHTERSPUR_KORRIGIERT }
                .mapNotNull { eintrag ->
                    val datenAt = (eintrag.details["letzteDatenAt"] as? Number)?.toLong()
                    datenAt?.let { it to eintrag.details }
                }.toMap()

        val nutzerAngaben =
            eintraege
                .filter { it.ereignis == EREIGNIS_AUSFALL_NUTZERANGABE }
                .mapNotNull { eintrag ->
                    val datenAt = (eintrag.details["letzteDatenAt"] as? Number)?.toLong()
                    val angabe = (eintrag.details["nutzerAngabe"] as? String)?.takeIf { it.isNotBlank() }
                    if (datenAt != null && angabe != null) datenAt to angabe else null
                }.toMap()

        return eintraege
            .filter { it.ereignis == EREIGNIS_AUSFALL_EINGEORDNET }
            .takeLast(MAX_EINTRAEGE)
            .mapNotNull { eintrag ->
                val datenAt = (eintrag.details["letzteDatenAt"] as? Number)?.toLong() ?: return@mapNotNull null
                val entdecktAt =
                    (eintrag.details["entdecktAt"] as? Number)?.toLong()
                        ?: eintrag.timestampMillis
                val zusammengefuehrt =
                    buildMap<String, Any?> {
                        putAll(eintrag.details)
                        waechterKorrekturen[datenAt]?.let { putAll(it) }
                        nutzerAngaben[datenAt]?.let { put("nutzerAngabe", it) }
                    }
                val lueckeMinuten =
                    (zusammengefuehrt["lueckeMinuten"] as? Number)?.toLong()
                        ?: ((entdecktAt - datenAt).coerceAtLeast(0L) / 60_000L)
                val klassifikationName = zusammengefuehrt["klassifikation"] as? String
                val klasse =
                    runCatching {
                        if (klassifikationName != null) AusfallKlasse.valueOf(klassifikationName) else AusfallKlasse.UNBEKANNT
                    }.getOrDefault(AusfallKlasse.UNBEKANNT)
                val waechterName = (zusammengefuehrt["waechterLief"] as? String)?.uppercase(Locale.ROOT)
                val waechterLief =
                    runCatching {
                        if (waechterName != null) WaechterLief.valueOf(waechterName) else WaechterLief.UNBEKANNT
                    }.getOrDefault(WaechterLief.UNBEKANNT)
                val antwortCode = zusammengefuehrt["nutzerAngabe"] as? String
                AusfallNachfrageEintrag(
                    timestampMillis = eintrag.timestampMillis,
                    letzteDatenAt = datenAt,
                    entdecktAt = entdecktAt,
                    lueckeMinuten = lueckeMinuten,
                    klasse = klasse,
                    waechterLief = waechterLief,
                    nutzerAngabe = AusfallAntwort.vonCode(antwortCode),
                    belege = zusammengefuehrt,
                )
            }
    }

    /**
     * Prüft, ob für einen Ausfall überhaupt nachgefragt werden soll (>= 30 min, nicht
     * NUTZER_STOPP oder AKKU_LEER).
     */
    fun istNachfrageFall(eintrag: AusfallNachfrageEintrag): Boolean {
        val dauerAusreichend =
            eintrag.lueckeMinuten >= MINDEST_DAUER_FUER_NACHFRAGE_MINUTEN ||
                (eintrag.entdecktAt - eintrag.letzteDatenAt) >= MINDEST_DAUER_FUER_NACHFRAGE_MS
        return dauerAusreichend && eintrag.klasse !in AUSGENOMMENE_KLASSEN
    }

    /**
     * Liefert den neuesten noch offenen und noch nicht weggewischten Ausfall für die Karte oben
     * im Hauptbildschirm, oder `null`.
     */
    fun offeneNachfrageFuerHauptbildschirm(
        eintraege: List<LebenszyklusEintrag>,
        bereitsGefragtOderWeggewischt: Set<Long>,
    ): AusfallNachfrageEintrag? =
        leseAusfaelle(eintraege)
            .lastOrNull { eintrag ->
                istNachfrageFall(eintrag) &&
                    eintrag.nutzerAngabe == null &&
                    eintrag.letzteDatenAt !in bereitsGefragtOderWeggewischt
            }

    /**
     * Liefert alle noch unbeantworteten Nachfrage-Fälle für den Diagnose-Bildschirm (höchstens
     * die letzten 20, neueste zuerst).
     */
    fun unbeantworteteLueckenFuerDiagnose(eintraege: List<LebenszyklusEintrag>): List<AusfallNachfrageEintrag> =
        leseAusfaelle(eintraege)
            .filter { istNachfrageFall(it) && it.nutzerAngabe == null }
            .takeLast(MAX_EINTRAEGE)
            .asReversed()

    /**
     * Formatiert Zeitraum und Dauer wie im Prompt gefordert, z. B.:
     * `30.10. 13:07` bis `01.11. 10:57`, Dauer `45 h 49 min` (oder `35 min` unter einer Stunde).
     */
    fun formatiereZeitpunkt(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val formatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMANY).withZone(zoneId)
        return formatter.format(Instant.ofEpochMilli(epochMillis))
    }

    fun formatiereDauer(dauerMillis: Long): String {
        val gesamtMinuten = (dauerMillis.coerceAtLeast(0L) / 60_000L)
        val stunden = gesamtMinuten / 60L
        val minuten = gesamtMinuten % 60L
        return if (stunden > 0L) {
            "$stunden h $minuten min"
        } else {
            "$minuten min"
        }
    }

    /**
     * Protokolliert die Antwort des Nutzers in der Lebenszyklus-Ringdatei und gibt den erzeugten
     * Eintrag zurück, damit die UI ihren lokalen Zustand sofort aktualisieren kann.
     */
    fun speichereAntwort(
        ringFile: LebenszyklusRingFile,
        letzteDatenAt: Long,
        antwort: AusfallAntwort,
        jetztMs: Long = System.currentTimeMillis(),
    ): LebenszyklusEintrag {
        val details =
            mapOf<String, Any?>(
                "letzteDatenAt" to letzteDatenAt,
                "nutzerAngabe" to antwort.code,
            )
        ringFile.protokolliere(
            ereignis = EREIGNIS_AUSFALL_NUTZERANGABE,
            details = details,
            timestampMillis = jetztMs,
        )
        return LebenszyklusEintrag(
            timestampMillis = jetztMs,
            ereignis = EREIGNIS_AUSFALL_NUTZERANGABE,
            details = details,
        )
    }
}
