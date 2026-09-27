package com.example.lrmprotokoll.report

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Grenzen eines lokalen Messtags in Millisekunden; das Ende ist exklusiv. */
fun messtagGrenzen(
    tag: LocalDate,
    zone: ZoneId,
): Pair<Long, Long> {
    val beginn = tag.atStartOfDay(zone)
    val naechsterTag = tag.plusDays(1)
    val ende = naechsterTag.atStartOfDay(zone)
    val beginnInstant = beginn.toInstant()
    val endeInstant = ende.toInstant()
    return beginnInstant.toEpochMilli() to endeInstant.toEpochMilli()
}

fun lokalerMesstag(
    zeitpunkt: Long,
    zone: ZoneId,
): LocalDate {
    val instant = Instant.ofEpochMilli(zeitpunkt)
    val zoniert = instant.atZone(zone)
    return zoniert.toLocalDate()
}

/**
 * Welchem Messtag eine nachträgliche Stammdaten-Korrektur zugeordnet werden muss - oder `null`,
 * wenn der reguläre Weg (Erfassungszeitpunkt = Messtag) richtig ist.
 *
 * Hintergrund (Review-Befund zu PR #220, 27.09.2026): Der Knopf "Stammdaten dieser Messung
 * bearbeiten" in der Dauermessungs-Karte erscheint auch für die **letzte beendete** Session,
 * übergab dem Sheet aber nur deren ID. Ohne `giltFuerTagStart` speichert
 * [com.example.lrmprotokoll.ui.GesamtberichtStammdatenSheet] mit `LocalDate.now()` - wer am
 * Folgetag die gestrige Messung korrigiert, erzeugt damit einen Eintrag für **heute**, und der
 * Bericht für gestern sieht die Korrektur nie.
 *
 * Zwei Fälle geben bewusst `null` zurück, denn dort ist der reguläre Weg der richtige:
 *
 * - **Die Session läuft noch** ([endedAt] ist `null`). Dann dokumentiert der Nutzer die
 *   Bedingungen von jetzt (Wetter, Fensterzustand), und die gehören zu heute - auch wenn die
 *   Messung gestern begonnen hat. Das entspricht der Tagesbestätigung, mit der sich das Sheet
 *   beim Messbeginn selbst öffnet.
 * - **Die Session hat heute begonnen.** Dann sind Erfassungs- und Messtag ohnehin derselbe.
 *
 * Warum in diesen Fällen nicht einfach der heutige Tagesbeginn übergeben wird: ein Eintrag mit
 * gesetztem `giltFuerTagStart` gilt als Nachtrag. [com.example.lrmprotokoll.data.StammdatenVerlaufDao.letzte]
 * überspringt solche Einträge bewusst, damit ein historischer Nachtrag nicht zur Vorbelegung der
 * nächsten Messung wird - und das Sheet beschriftet sich anders. Beides wäre für die heutige
 * Messung falsch.
 *
 * Mehrtägige Sessions werden dem **Starttag** zugeordnet. Eine gezielte Auswahl des Messtags
 * gibt es noch nicht; sie wäre neue Bedienoberfläche und damit eine Owner-Entscheidung
 * (AGENTS.md §8a). Der Starttag ist in jedem Fall richtiger als der heutige Tag.
 *
 * @param startedAt Beginn der Session, [com.example.lrmprotokoll.data.SessionEntity.startedAt].
 * @param endedAt Ende der Session oder `null`, solange sie läuft.
 * @param jetzt Aktueller Zeitpunkt.
 * @return Millisekunden des Messtag-Beginns für `giltFuerTagStart`, oder `null` für den
 *   regulären Weg.
 */
fun messtagFuerStammdatenKorrektur(
    startedAt: Long,
    endedAt: Long?,
    jetzt: Long,
    zone: ZoneId,
): Long? {
    if (endedAt == null) return null
    val messtag = lokalerMesstag(startedAt, zone)
    if (messtag == lokalerMesstag(jetzt, zone)) return null
    return messtagGrenzen(messtag, zone).first
}
