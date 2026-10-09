@file:Suppress("ktlint:standard:filename")

package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.SessionEntity

/**
 * Ein Zeitintervall [von, bis) in Millisekunden (E1–E4 aus docs/PROMPT_FIX_AUFZEICHNUNGSLUECKEN_SICHTBAR.md).
 * [von] ist inklusiv, [bis] exklusiv.
 */
data class Zeitraum(
    val von: Long,
    val bis: Long,
) {
    init {
        require(bis >= von) { "bis ($bis) darf nicht vor von ($von) liegen" }
    }
}

/**
 * Ermittelt alle Aufzeichnungslücken im Intervall [[von], [bis]) aus den übergebenen [sessions]
 * (docs/PROMPT_FIX_AUFZEICHNUNGSLUECKEN_SICHTBAR.md Schritt 1).
 *
 * Bildet die Vereinigung aller Session-Intervalle und liefert deren Komplement in [[von], [bis]).
 * - Mikrofon- und Messgerät-Sessions zählen beide als Aufzeichnung.
 * - Überlappende und aneinandergrenzende Sessions werden verschmolzen.
 * - Eine offene Session ([SessionEntity.endedAt] == null) gilt bis [bis], wenn sie die aktive ist
 *   (d.h. die jüngste offene Session nach [SessionEntity.startedAt], analog `SessionDao.offeneSession`).
 *   Ältere verwaiste Sessions ohne [SessionEntity.endedAt] werden ignoriert, um historische Lücken
 *   nicht fälschlich zu überdecken.
 */
fun aufzeichnungsLuecken(
    sessions: List<SessionEntity>,
    von: Long,
    bis: Long,
): List<Zeitraum> {
    if (bis <= von) return emptyList()

    val aktiveOffene =
        sessions
            .filter { it.endedAt == null }
            .maxByOrNull { it.startedAt }

    val sessionIntervalle =
        sessions.mapNotNull { session ->
            val ende = session.endedAt ?: if (session == aktiveOffene) bis else null
            if (ende != null && ende > session.startedAt) {
                val startClamped = maxOf(von, session.startedAt)
                val endClamped = minOf(bis, ende)
                if (endClamped > startClamped) startClamped to endClamped else null
            } else {
                null
            }
        }

    if (sessionIntervalle.isEmpty()) {
        return listOf(Zeitraum(von = von, bis = bis))
    }

    val sortierteIntervalle = sessionIntervalle.sortedBy { it.first }
    val verschmolzen = mutableListOf<Pair<Long, Long>>()
    for (intervall in sortierteIntervalle) {
        if (verschmolzen.isEmpty()) {
            verschmolzen += intervall
        } else {
            val letzte = verschmolzen.last()
            if (intervall.first <= letzte.second) {
                verschmolzen[verschmolzen.lastIndex] = letzte.first to maxOf(letzte.second, intervall.second)
            } else {
                verschmolzen += intervall
            }
        }
    }

    val luecken = mutableListOf<Zeitraum>()
    var cursor = von
    for (intervall in verschmolzen) {
        if (intervall.first > cursor) {
            luecken += Zeitraum(von = cursor, bis = intervall.first)
        }
        cursor = maxOf(cursor, intervall.second)
    }
    if (cursor < bis) {
        luecken += Zeitraum(von = cursor, bis = bis)
    }

    return luecken
}
