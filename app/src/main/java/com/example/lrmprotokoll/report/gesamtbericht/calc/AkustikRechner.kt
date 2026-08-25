package com.example.lrmprotokoll.report.gesamtbericht.calc

import java.security.MessageDigest
import kotlin.math.log10
import kotlin.math.pow

/**
 * Mathematische Berechnungen für akustische Kennwerte nach DIN 45641, TA Lärm und AVV Baulärm.
 */
object AkustikRechner {

    /**
     * Berechnet den energieäquivalenten Dauerschallpegel LAeq aus einer Liste von dB-Werten.
     */
    fun berechneLaeq(pegel: List<Double>): Double? {
        val gueltig = pegel.filter { it > 0.0 && !it.isNaN() && !it.isInfinite() }
        if (gueltig.isEmpty()) return null
        val summe = gueltig.sumOf { 10.0.pow(it / 10.0) }
        return 10.0 * log10(summe / gueltig.size)
    }

    /**
     * Berechnet die statistischen Perzentilpegel (L1, L10, L50, L90, L95).
     * Lx ist der Pegel, der in x % der Zeit überschritten wurde.
     */
    fun berechnePerzentile(pegel: List<Double>): Map<Int, Double> {
        val gueltig = pegel.filter { it > 0.0 && !it.isNaN() && !it.isInfinite() }.sortedDescending()
        if (gueltig.isEmpty()) return emptyMap()

        val n = gueltig.size
        fun perzentil(prozent: Int): Double {
            if (n == 1) return gueltig[0]
            val index = ((prozent / 100.0) * n).toInt().coerceIn(0, n - 1)
            return gueltig[index]
        }

        return mapOf(
            1 to perzentil(1),
            5 to perzentil(5),
            10 to perzentil(10),
            50 to perzentil(50),
            90 to perzentil(90),
            95 to perzentil(95)
        )
    }

    /**
     * Teilt Pegelwerte in Belastungsklassen ein und berechnet die kumulierte Dauer in Sekunden.
     */
    fun berechnePegelklassen(pegel: List<Double>, sampleDauerSekunden: Double = 1.0): Map<String, Long> {
        val klassen = mutableMapOf(
            "<50" to 0L,
            "50-55" to 0L,
            "55-60" to 0L,
            "60-65" to 0L,
            "65-70" to 0L,
            ">70" to 0L
        )

        for (p in pegel) {
            val key = when {
                p < 50.0 -> "<50"
                p < 55.0 -> "50-55"
                p < 60.0 -> "55-60"
                p < 65.0 -> "60-65"
                p < 70.0 -> "65-70"
                else -> ">70"
            }
            klassen[key] = (klassen[key] ?: 0L) + sampleDauerSekunden.toLong()
        }

        return klassen
    }

    /**
     * Ermittelt die lauteste Stunde (höchstes gleitendes 60-Minuten LAeq) an einem Messtag.
     * @param zeitreihen Liste von (Timestamp in ms, dB-Wert), aufsteigend nach Timestamp sortiert.
     * @return Pair(Start-Timestamp in ms, LAeq in dB) oder null wenn zu wenig Daten vorliegen (< 30 min).
     */
    fun berechneLautesteStunde(zeitreihen: List<Pair<Long, Double>>): Pair<Long, Double>? {
        if (zeitreihen.isEmpty()) return null
        val sortiert = zeitreihen.sortedBy { it.first }
        val fensterDauerMs = 3600 * 1000L
        val minPunkte = 30 * 60 // Mindestens 30 min bei 1s Takt

        var maxLeq: Double? = null
        var bestStartTs: Long? = null

        var left = 0
        var energeticSum = 0.0
        var count = 0

        for (right in sortiert.indices) {
            val rightItem = sortiert[right]
            energeticSum += 10.0.pow(rightItem.second / 10.0)
            count++

            while (sortiert[right].first - sortiert[left].first > fensterDauerMs) {
                energeticSum -= 10.0.pow(sortiert[left].second / 10.0)
                count--
                left++
            }

            if (count >= minPunkte) {
                val currentLeq = 10.0 * log10(energeticSum / count)
                if (maxLeq == null || currentLeq > maxLeq) {
                    maxLeq = currentLeq
                    bestStartTs = sortiert[left].first
                }
            }
        }

        return if (maxLeq != null && bestStartTs != null) {
            Pair(bestStartTs, maxLeq)
        } else {
            // Fallback: gesamter Zeitraum wenn < 1h
            val allLeq = berechneLaeq(sortiert.map { it.second })
            if (allLeq != null) Pair(sortiert.first().first, allLeq) else null
        }
    }

    /**
     * Berechnet den kryptografischen SHA-256 Hash für ein Byte-Array (Revisionssicherheit).
     */
    fun berechneSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun berechneSha256(text: String): String {
        return berechneSha256(text.toByteArray(Charsets.UTF_8))
    }
}
