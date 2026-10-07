package com.example.lrmprotokoll.data

/**
 * Repraesentiert eine erkannte Unterbrechung der Aufzeichnung (Befund G / E1).
 *
 * @property beginn Zeitstempel (Millis) des letzten Messwerts vor der Unterbrechung, oder null wenn unbekannt.
 * @property ende Zeitstempel (Millis) der Erkennung/Wiederaufnahme.
 */
data class AufzeichnungsUnterbrechung(
    val beginn: Long?,
    val ende: Long,
)
