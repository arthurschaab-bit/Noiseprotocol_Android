package com.example.lrmprotokoll.meter

import com.example.lrmprotokoll.data.SettingsManager

/**
 * Baut die Verbindung zum gepinnten Messgeraet selbsttaetig auf (S-3/F-02).
 *
 * **Bewusst ohne Vordergrunddienst** (Owner-Entscheidung 26.09.2026): [ConnectionSupervisor]
 * haengt am `AppContainer` und nicht am Dienst, die Verbindung braucht ihn also nicht. Der
 * Vordergrunddienst kommt erst dazu, wenn eine Messung startet. Damit ist "Verbinden" nicht
 * mehr dasselbe wie "Messung starten" - wer nur den Pegel sehen will, loest keine Aufzeichnung
 * mehr aus.
 *
 * Der Preis dieser Wahl: die Verbindung lebt nur, solange der Prozess lebt. Raeumt Android ihn
 * im Hintergrund ab, ist sie weg und wird beim naechsten [verbindeWennGewuenscht] neu
 * aufgebaut. Fuer eine laufende Messung ist das unerheblich - dann laeuft ohnehin der Dienst.
 */
class MeterAutoConnect(
    private val settingsManager: SettingsManager,
    private val connectionSupervisor: ConnectionSupervisor,
    private val hatVerbindungsberechtigung: () -> Boolean,
) {
    /**
     * Verbindet, wenn der Nutzer das will und die Voraussetzungen stimmen; sonst passiert
     * nichts. Gedacht fuer den App-Start und jedes Zurueckkehren in den Vordergrund.
     *
     * Wartet die Ueberwachung gerade in [ConnectionState.FAILED], ist [ConnectionSupervisor.start]
     * ein No-Op (dasselbe Geraet, Job noch aktiv) - dann ist [ConnectionSupervisor.erneutVersuchen]
     * der wirksame Teil und holt den Anlauf sofort statt erst nach der Wartezeit.
     *
     * @return true, wenn ein Verbindungsaufbau angestossen wurde.
     */
    fun verbindeWennGewuenscht(): Boolean {
        if (!settingsManager.meterAutoConnect) return false
        val adresse = settingsManager.meterDeviceAddress ?: return false
        if (!hatVerbindungsberechtigung()) return false

        connectionSupervisor.start(BoundDevice(adresse, settingsManager.meterDeviceName ?: adresse))
        connectionSupervisor.erneutVersuchen()
        return true
    }

    /**
     * Wie [verbindeWennGewuenscht], aber ohne den Schalter zu fragen - fuer den ausdruecklichen
     * "Verbinden"-Knopf auf dem Messgeraet-Screen. Auch er startet keinen Dienst mehr.
     */
    fun verbindeJetzt(): Boolean {
        val adresse = settingsManager.meterDeviceAddress ?: return false
        if (!hatVerbindungsberechtigung()) return false

        connectionSupervisor.start(BoundDevice(adresse, settingsManager.meterDeviceName ?: adresse))
        connectionSupervisor.erneutVersuchen()
        return true
    }
}
