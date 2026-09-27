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
