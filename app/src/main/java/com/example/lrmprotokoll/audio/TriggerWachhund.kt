package com.example.lrmprotokoll.audio

/**
 * Erkennt, dass die Aufnahme-Auslösung stillschweigend nicht mehr funktioniert.
 *
 * Anlass ist ein realer Ausfall beim Owner: 12 Stunden Messung mit durchgehend rund 10 dB über
 * der Schwelle, Messgerät verbunden, WAV-Aufnahme an - und **kein einziges** Ereignis. Die
 * Ursache lag in einer einzigen Zeile (siehe [AudioRecordingService.pruefeSchwellenwertUndTrigger]),
 * aber das eigentliche Problem war, dass niemand es merken konnte: Verbindung, Session,
 * Messreihe, Drive-Sync und Live-Pegel liefen weiter, nur der Zweig, der Ereignisse erzeugt,
 * war tot. Stille sieht von aussen genauso aus wie "es war nichts los".
 *
 * Die Regel: Wenn ueber ein zusammenhaengendes Fenster Pegel oberhalb der Schwelle eingegangen
 * sind und in derselben Zeit **kein einziges** Ereignis entstanden ist, stimmt etwas nicht.
 *
 * Bewusst eine reine Klasse ohne Context, Service oder Coroutine - nur Zeitstempel und Zaehler.
 * Dieselbe Trennung wie bei [com.example.lrmprotokoll.messreihe.MeterTriggerSource] oder
 * [com.example.lrmprotokoll.report.pdf.Seitenlauf]: Ein Service, dessen Kernentscheidung nur am
 * Geraet pruefbar ist, hat diesen Fehler ueberhaupt erst ermoeglicht.
 */
class TriggerWachhund(
    private val fensterMs: Long = 10 * 60 * 1000L,
) {

    /** Beginn der laufenden Strecke ueber der Schwelle; `null`, wenn gerade keine laeuft. */
    private var ueberSchwelleSeit: Long? = null

    /** Wurde fuer die aktuelle Strecke bereits gemeldet? Verhindert Dauerfeuer. */
    private var bereitsGemeldet = false

    /**
     * Meldet einen ausgewerteten Pegel. [ueberSchwelle] ist das Ergebnis derselben Auswertung,
     * die ueber eine Aufnahme entscheidet - nicht ein separat berechneter Vergleich, sonst
     * koennten Wachhund und Trigger unterschiedlicher Meinung sein.
     */
    fun pegelGesehen(zeitpunkt: Long, ueberSchwelle: Boolean) {
        if (!ueberSchwelle) {
            // Unter der Schwelle: Die Strecke endet. Eine ruhige Phase ist kein Defekt, und ohne
            // dieses Zuruecksetzen wuerde jede ruhige Nacht irgendwann als Ausfall gemeldet.
            ueberSchwelleSeit = null
            bereitsGemeldet = false
            return
        }
        if (ueberSchwelleSeit == null) ueberSchwelleSeit = zeitpunkt
    }

    /**
     * Meldet ein tatsaechlich gespeichertes Ereignis - der Beweis, dass die Ausloesung
     * funktioniert. Setzt die laufende Strecke zurueck.
     */
    fun ereignisGespeichert(zeitpunkt: Long) {
        ueberSchwelleSeit = null
        bereitsGemeldet = false
    }

    /**
     * `true` genau einmal je Strecke, sobald ununterbrochen [fensterMs] lang Pegel ueber der
     * Schwelle gesehen wurden, ohne dass ein Ereignis entstanden ist.
     *
     * Danach erst wieder, wenn die Strecke durch einen Pegel unter der Schwelle oder ein
     * gespeichertes Ereignis unterbrochen wurde: Eine Warnung, die im Minutentakt wiederkommt,
     * wird zum Rauschen, und der naechste echte Ausfall geht darin unter.
     */
    fun stillerAusfall(jetzt: Long): Boolean {
        if (bereitsGemeldet) return false
        val seit = ueberSchwelleSeit ?: return false
        if (jetzt - seit < fensterMs) return false
        bereitsGemeldet = true
        return true
    }

    /** Wie lange die aktuelle Strecke ueber der Schwelle schon laeuft - fuer die Meldung. */
    fun dauerUeberSchwelleMs(jetzt: Long): Long = ueberSchwelleSeit?.let { jetzt - it } ?: 0L

    /** Nach einem Stopp der Ueberwachung: alles vergessen, damit die naechste Periode frisch beginnt. */
    fun zuruecksetzen() {
        ueberSchwelleSeit = null
        bereitsGemeldet = false
    }
}
