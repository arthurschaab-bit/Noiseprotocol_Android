package com.example.lrmprotokoll.messreihe

import com.example.lrmprotokoll.data.KalibrierEnde
import com.example.lrmprotokoll.data.KalibrierphaseEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Berichtstext zur Kalibrierung, z. B. fuer das Feld "Kalibrierung" der Gesamtbericht-Stammdaten.
 * `null`, wenn keine Kalibrierung stattgefunden hat (uebersprungen, abgelaufen oder ohne Werte).
 */
fun kalibrierBerichtText(phase: KalibrierphaseEntity, locale: Locale = Locale.GERMAN): String? {
    if (phase.endeGrund != KalibrierEnde.BESTAETIGT && phase.endeGrund != KalibrierEnde.MANUELL) return null
    val vorher = phase.abweichungVorherDb
    val nachher = phase.abweichungNachherDb
    if (vorher == null && nachher == null) return null
    fun db(wert: Double?) = wert?.let { String.format(locale, "%+.1f dB", it) } ?: "k. A."
    val zeit = SimpleDateFormat("dd.MM.yyyy HH:mm", locale).format(Date(phase.endedAt ?: phase.startedAt))
    val korrigiert = if (phase.abweichungKorrigiert) " (vom Nutzer korrigiert)" else ""
    return String.format(
        locale,
        "Kalibrator PCE-SC 43, %.1f dB / 1 kHz, %s Uhr: Abweichung vor Justierung %s, danach %s%s",
        phase.sollwertDb, zeit, db(vorher), db(nachher), korrigiert,
    )
}
