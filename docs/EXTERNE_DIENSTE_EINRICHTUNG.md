# Externe Dienste – Einrichtung und Konfiguration

**Stand:** 20. August 2026  
**Status:** Begleitende Checkliste zu [DIAGNOSE_OBSERVABILITY_KONZEPT.md](DIAGNOSE_OBSERVABILITY_KONZEPT.md)

Diese Checkliste fasst alle manuellen Schritte zusammen, die außerhalb des Android-Codes in externen Web-Oberflächen und Diensten eingerichtet werden müssen.

---

## 1. Sentry (Fehleranalyse & Absturzberichte)

### 1.1 Projekt anlegen
1. Bei [sentry.io](https://sentry.io/) (oder selbstgehosteter Instanz) anmelden.
2. Neues Projekt vom Plattform-Typ **Android** erstellen.
3. Projektbezeichner vergeben (z. B. `noiseprotocol-android`).
4. Die **DSN** kopieren (Format: `https://<key>@<host>/<project_id>`).
   - Die DSN ist ein öffentlicher Schlüssel und kein geheimes Token.
   - Sie wird über `BuildConfig` bzw. Gradle-Eigenschaften eingebunden.

### 1.2 Datenschutz & PII in Sentry konfigurieren
- **Data Scrubbing:** Aktivieren (Standardfilter für Passwörter, Tokens, API-Keys).
- **Store Native / Client IP:** Deaktivieren oder auf anonymisiert stellen.
- **Session Replay / Screen Capture:** Im Projekt nicht aktivieren (im Android-Code ohnehin deaktiviert).
- **Attachments:** Automatische Anhänge nicht aktivieren.

### 1.3 Alarm- & E-Mail-Regeln einrichten
In *Project Settings → Alerts*:
- **Regel 1: Neuer Fehler / Unhandled Crash / ANR**
  - Bedingung: Ein neues Issue wird erstellt ODER ein behandelter Fehler mit Schweregrad `FATAL` / `ERROR` tritt auf.
  - Aktion: Sofortige E-Mail an den/die Verantwortlichen.
- **Regel 2: Fehler-Regression**
  - Bedingung: Ein zuvor gelöstes Issue tritt erneut auf.
  - Aktion: Sofortige E-Mail.
- **Regel 3: Fehlerspitzen / Rate Limit**
  - Bedingung: Mehr als 50 Events pro 15 Minuten.
  - Aktion: Benachrichtigung zur Prüfung von Loops.

---

## 2. Heartbeat / Totmannschalter (z. B. healthchecks.io)

### 2.1 Check anlegen
1. Bei [healthchecks.io](https://healthchecks.io/) oder eigenem Ping-Server anmelden.
2. Neuen Check erstellen:
   - **Name:** `Lärmprotokoll Überwachung [Gerät/Instanz]`
   - **Intervall:** Entsprechend Überwachungszyklus (z. B. 15 Minuten).
   - **Karenzzeit (Grace Time):** z. B. 10–15 Minuten Puffer für Doze/Netzwerkwechsel.
3. Die eindeutige Ping-URL kopieren (`https://hc-ping.com/<uuid>`).
4. **Wichtig:** Die Ping-URL enthält ein Cap-Token und ist ein Geheimnis. Sie gehört ausschließlich in die verschlüsselten App-Einstellungen (`EncryptedSharedPreferences`) und darf niemals in Logdateien, Sentry-Events oder Support-Paketen erscheinen.

### 2.2 Benachrichtigungskanal
- In Healthchecks.io einen E-Mail-Kanal (oder SMS/Webhook) hinterlegen.
- Bei Ausbleiben der Pings nach Ablauf von Intervall + Karenzzeit wird automatisch eine E-Mail versandt.

---

## 3. ntfy (Fachliche Grenzwert-Alarmierung)

### 3.1 Topic & Server
1. Eigenen ntfy-Server oder `ntfy.sh` wählen.
2. Ein langes, unvorhersehbares Topic generieren (z. B. 32 Zeichen Zufallsstring), falls öffentlich gehostet.
3. Topic und ggf. Auth-Token in den verschlüsselten App-Einstellungen hinterlegen.

### 3.2 Empfänger
1. ntfy-App auf dem Empfänger-Smartphone installieren oder Topic im Web-Interface abonnieren.
2. Test-Alarm über die Diagnose-/Einstellungs-Ansicht auslösen und Empfang prüfen.

---

## 4. Google Drive (Berichts-Synchronisation)

### 4.1 Google Cloud Console
1. Google Cloud Projekt erstellen / auswählen.
2. **Google Drive API** in der API-Bibliothek aktivieren.
3. **OAuth-Zustimmungsbildschirm** konfigurieren:
   - Nutzertyp: Externe oder Interne Nutzer (je nach Verteilung).
   - Scopes: `https://www.googleapis.com/auth/drive.file` (beschränkt Zugriff nur auf von der App erstellte Dateien).
4. **OAuth-Client-ID** anlegen:
   - Typ: Android.
   - Paketname: `com.example.lrmprotokoll`.
   - SHA-1-Fingerprint des Signaturschlüssels (Debug-Keystore und Release-Keystore) hinterlegen.

---

## 5. Externe Checkliste & Übergabe

- [ ] Sentry-Projekt angelegt und DSN hinterlegt
- [ ] Sentry-E-Mail-Alerts für neue Crashes/Issues scharfgeschaltet
- [ ] Sentry PII- und Scrubbing-Einstellungen geprüft
- [ ] Healthchecks.io Ping-URL eingerichtet und E-Mail-Empfänger verifiziert
- [ ] ntfy Topic generiert und auf Empfängergerät getestet
- [ ] Google Cloud OAuth-Client-ID für Drive-Sync registriert
