package com.example.lrmprotokoll.diagnose

import com.example.lrmprotokoll.alert.heartbeat.HeartbeatPlanung

/**
 * Die vier Zustände des Warnhinweises "Kein Schutz bei Ausfall" (Schritt 8 aus
 * docs/PROMPT_DIAGNOSE_STILLSTAND.md, Owner-Entscheidung 02.11.2026: E5).
 *
 * **Ehrliche Grenze:** Die App erkennt nur, dass **sie** keinen Ping sendet. Ob der Dienst bei
 * healthchecks.io einen Alarmkanal hat, sieht sie nicht. Der Hinweistext behauptet deshalb nie
 * "Schutz aktiv", und im Zustand [OK] zeigt die App gar keinen Hinweis.
 */
enum class TotmannschaltungZustand {
    /** Aufzeichnung läuft, aber keine gültige URL -> "Kein Schutz bei Ausfall: Totmannschaltung nicht eingerichtet" */
    NICHT_EINGERICHTET,

    /** URL gültig, Aufzeichnung läuft, aber der Job `heartbeat` ist nicht geplant -> "Totmannschaltung eingerichtet, aber nicht aktiv. Messung neu starten." */
    NICHT_GEPLANT,

    /** Geplant, aber letzter Erfolg älter als 45 min (Fenster der Gegenseite) -> "Totmannschaltung sendet nicht (Netz?)" */
    SENDET_NICHT,

    /** Alles andere (auch wenn keine Aufzeichnung läuft) -> kein Hinweis. */
    OK,
}

object TotmannschaltungStatus {
    const val MAX_ALTER_LETZTER_ERFOLG_MINUTEN = 45L
    const val MAX_ALTER_LETZTER_ERFOLG_MS = MAX_ALTER_LETZTER_ERFOLG_MINUTEN * 60_000L

    /**
     * Reine Bewertungsfunktion auf den Daten aus Schritt 4a (`konfiguriert`, `geplant`,
     * `letzterErfolgAt`) und dem aktuellen Aufzeichnungsstatus.
     *
     * @param aufnahmeLaeuft `true`, wenn die Aufzeichnung/Überwachung gerade aktiv ist.
     * @param konfiguriert `true`, wenn eine gültige Ping-URL eingetragen ist.
     * @param geplant `true`, wenn der WorkManager-Job `heartbeat` aktiv eingeplant ist (`false`
     *   wenn nicht geplant, `null` wenn noch nicht abgefragt/unbekannt).
     * @param letzterErfolgAt Zeitstempel (Millis) des letzten erfolgreichen Pings (`0L` = noch nie).
     * @param jetztMs Aktueller Zeitstempel (Millis).
     */
    fun bewerten(
        aufnahmeLaeuft: Boolean,
        konfiguriert: Boolean,
        geplant: Boolean?,
        letzterErfolgAt: Long,
        jetztMs: Long,
    ): TotmannschaltungZustand {
        if (!aufnahmeLaeuft) return TotmannschaltungZustand.OK
        if (!konfiguriert) return TotmannschaltungZustand.NICHT_EINGERICHTET
        if (geplant == false) return TotmannschaltungZustand.NICHT_GEPLANT
        if (geplant == true) {
            val erfolgZuAlt =
                letzterErfolgAt <= 0L ||
                    (jetztMs - letzterErfolgAt) > MAX_ALTER_LETZTER_ERFOLG_MS
            if (erfolgZuAlt) return TotmannschaltungZustand.SENDET_NICHT
        }
        return TotmannschaltungZustand.OK
    }

    fun bewertenMitUrl(
        aufnahmeLaeuft: Boolean,
        heartbeatUrl: String,
        geplant: Boolean?,
        letzterErfolgAt: Long,
        jetztMs: Long,
    ): TotmannschaltungZustand =
        bewerten(
            aufnahmeLaeuft = aufnahmeLaeuft,
            konfiguriert = HeartbeatPlanung.istKonfiguriert(heartbeatUrl),
            geplant = geplant,
            letzterErfolgAt = letzterErfolgAt,
            jetztMs = jetztMs,
        )

    /**
     * Wie [bewertenMitUrl], aber erst nach dem ersten Laden der Einstellungen. Davor gilt
     * [TotmannschaltungZustand.OK]: Die Startwerte (leere URL) duerfen keinen falschen Alarm
     * "nicht eingerichtet" ausloesen, auch wenn die Totmannschaltung eingerichtet ist.
     */
    fun bewertenWennGeladen(
        geladen: Boolean,
        aufnahmeLaeuft: Boolean,
        heartbeatUrl: String,
        geplant: Boolean?,
        letzterErfolgAt: Long,
        jetztMs: Long,
    ): TotmannschaltungZustand =
        if (!geladen) {
            TotmannschaltungZustand.OK
        } else {
            bewertenMitUrl(aufnahmeLaeuft, heartbeatUrl, geplant, letzterErfolgAt, jetztMs)
        }

    /**
     * Im Hauptbildschirm erscheint die Hinweiszeile mit Knopf "Einrichten" bei
     * [TotmannschaltungZustand.NICHT_EINGERICHTET] und [TotmannschaltungZustand.NICHT_GEPLANT].
     */
    fun zeigeImHauptbildschirm(zustand: TotmannschaltungZustand): Boolean =
        zustand == TotmannschaltungZustand.NICHT_EINGERICHTET ||
            zustand == TotmannschaltungZustand.NICHT_GEPLANT

    /**
     * Im Diagnose-Bildschirm erscheint der Hinweis dauerhaft für jeden Zustand außer [TotmannschaltungZustand.OK].
     */
    fun zeigeImDiagnoseBildschirm(zustand: TotmannschaltungZustand): Boolean = zustand != TotmannschaltungZustand.OK
}
