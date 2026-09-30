package com.example.lrmprotokoll.report

import com.example.lrmprotokoll.data.AppDatabase
import com.example.lrmprotokoll.data.ReportConfigEntity
import com.example.lrmprotokoll.data.SessionEntity
import com.example.lrmprotokoll.messreihe.Ausfallband
import com.example.lrmprotokoll.messreihe.Integritaetsbefund
import com.example.lrmprotokoll.messreihe.bewerteMessintegritaet
import com.example.lrmprotokoll.messreihe.leiteAusfallbaenderAb
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Ein Messtag, wie ihn der Bericht-Reiter auflistet (S-4).
 *
 * Bewusst getrennt von [BerichtTag]: der bringt die Stammdaten-Kandidaten fuer die Uebergabe an
 * die Python-Bruecke mit, diese Uebersicht dagegen das, was auf dem Bildschirm steht - Umfang,
 * Integritaetsstufe und die Gruende, die einen Bericht verhindern oder truebem.
 */
data class Messtag(
    val datum: LocalDate,
    val von: Long,
    val bis: Long,
    val sessionIds: List<Long>,
    val messdauerMs: Long,
    val rohwerte: Int,
    val integritaet: Integritaetsbefund,
    val fehlendeStammdatenFelder: List<String>,
) {
    /**
     * Ein Tag mit verdichteten Rohdaten ist nicht mehr berichtsfaehig - die Vorpruefung lehnt ihn
     * ab, statt andere Kennwerte aus Minutenaggregaten zu errechnen (Owner-Entscheidung
     * 13.09.2026, siehe [retentionFehler]). Fehlende Stammdaten blockieren dagegen nichts; sie
     * werden im PDF als Luecke benannt (Owner-Entscheidung 14.09.2026).
     */
    val berichtsfaehig: Boolean get() = !integritaet.rohdatenVerdichtet && rohwerte > 0
}

/**
 * Welche lokalen Kalendertage diese Sessions innerhalb von [zeitraum] beruehren.
 *
 * **Warum nicht ueber jeden Kalendertag laufen:** [ladeBerichtstage] tut genau das und setzt fuer
 * jeden Tag fuenf Abfragen ab - bei zwei Monaten rund 300, von denen die allermeisten auf leere
 * Tage entfallen. Fuer eine Liste, die der Nutzer beim Oeffnen des Reiters sieht, ist das zu viel.
 * Die Tage lassen sich aus den Sessions ableiten, die [com.example.lrmprotokoll.data.SessionDao.zwischen]
 * ohnehin in EINER Abfrage liefert.
 *
 * Eine ueber Mitternacht laufende Session zaehlt fuer **jeden** Tag, den sie beruehrt - anders als
 * bei der Stammdaten-Zuordnung ([messtagFuerStammdatenKorrektur]), wo der Starttag gewinnt: dort
 * geht es um die Dokumentation eines Messaufbaus, hier um die Frage, an welchen Tagen Messwerte
 * liegen.
 *
 * @param jetzt Endzeitpunkt fuer eine noch laufende Session ([SessionEntity.endedAt] ist `null`).
 */
fun messtageAusSessions(
    sessions: List<SessionEntity>,
    zeitraum: BerichtZeitraum,
    zone: ZoneId,
    jetzt: Long,
): List<LocalDate> {
    val ersterTag = zeitraum.ersterTag
    val letzterTag = zeitraum.letzterTag
    val tage = sortedSetOf<LocalDate>()
    sessions.forEach { session ->
        val ende = session.endedAt ?: jetzt
        if (ende < session.startedAt) return@forEach
        var tag = lokalerMesstag(session.startedAt, zone)
        val letzterBeruehrterTag = lokalerMesstag(ende, zone)
        while (!tag.isAfter(letzterBeruehrterTag)) {
            if (!tag.isBefore(ersterTag) && !tag.isAfter(letzterTag)) tage += tag
            tag = tag.plusDays(1)
        }
    }
    return tage.toList()
}

/** Ueberlappung zweier Zeitfenster in Millisekunden, nie negativ. */
internal fun ueberlappungMs(
    vonA: Long,
    bisA: Long,
    vonB: Long,
    bisB: Long,
): Long = (minOf(bisA, bisB) - maxOf(vonA, vonB)).coerceAtLeast(0L)

/**
 * Laedt die Uebersicht fuer alle Messtage in [zeitraum], absteigend nach Datum (der juengste Tag
 * zuerst - er ist der wahrscheinlichste Berichtsgegenstand).
 *
 * Die Verbindungsereignisse werden **je Session einmal** geholt und danach auf die Tage
 * zugeschnitten; eine mehrtaegige Session fuehrt also nicht zu mehrfachen Abfragen derselben
 * Ereignisse.
 */
suspend fun ladeMesstage(
    db: AppDatabase,
    zeitraum: BerichtZeitraum,
    config: ReportConfigEntity = ReportConfigEntity(),
    zone: ZoneId = ZoneId.systemDefault(),
    jetzt: Long = System.currentTimeMillis(),
): List<Messtag> {
    val (zeitraumVon, zeitraumBis) = messtagGrenzen(zeitraum.ersterTag, zone).first to
        messtagGrenzen(zeitraum.letzterTag, zone).second
    val sessions = db.sessionDao().zwischen(zeitraumVon, zeitraumBis)
    val tage = messtageAusSessions(sessions, zeitraum, zone, jetzt)
    if (tage.isEmpty()) return emptyList()

    val ausfallbaenderJeSession = coroutineScope {
        sessions
            .map { session ->
                async {
                    session.id to leiteAusfallbaenderAb(
                        db.connectionEventDao().fuerSession(session.id),
                        session.endedAt,
                    )
                }
            }.awaitAll()
            .toMap()
    }

    return coroutineScope {
        tage
            .map { datum ->
                async {
                    val (von, bis) = messtagGrenzen(datum, zone)
                    val tagesSessions = sessions.filter { session ->
                        ueberlappungMs(session.startedAt, session.endedAt ?: jetzt, von, bis) > 0
                    }
                    val rohwerte = async { db.measurementDao().anzahlZwischen(von, bis) }
                    val verdichtet = async { db.minuteAggregateDao().anzahlZwischen(von, bis) }
                    val unbestaetigt = async { db.measurementDao().anzahlUnbestaetigtZwischen(von, bis) }
                    val stammdaten = async { db.stammdatenVerlaufDao().fuerTag(von, bis) }

                    val baender = tagesSessions
                        .flatMap { ausfallbaenderJeSession[it.id].orEmpty() }
                        .mapNotNull { band ->
                            val geschnittenesVon = maxOf(band.von, von)
                            val geschnittenesBis = minOf(band.bis ?: bis, bis)
                            if (geschnittenesBis > geschnittenesVon) {
                                Ausfallband(geschnittenesVon, geschnittenesBis)
                            } else {
                                null
                            }
                        }
                    val messdauer = tagesSessions.sumOf {
                        ueberlappungMs(it.startedAt, it.endedAt ?: jetzt, von, bis)
                    }

                    Messtag(
                        datum = datum,
                        von = von,
                        bis = bis,
                        sessionIds = tagesSessions.map { it.id },
                        messdauerMs = messdauer,
                        rohwerte = rohwerte.await(),
                        integritaet = bewerteMessintegritaet(
                            von = von,
                            bis = bis,
                            ausfallbaender = baender,
                            verdichteteMinuten = verdichtet.await(),
                            unbestaetigteWerte = unbestaetigt.await(),
                            config = config,
                        ),
                        fehlendeStammdatenFelder = fehlendeStammdatenFelder(stammdaten.await().firstOrNull()),
                    )
                }
            }.awaitAll()
            .sortedByDescending { it.datum }
    }
}
