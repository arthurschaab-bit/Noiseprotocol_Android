# M6 — Sicherheit

**Nachträglich dokumentiert**, nicht vorab als Prompt geschrieben: Der Owner hat direkt
"mach weiter mit m4, m6 und m7" angewiesen, statt vorher einen Umsetzungs-Prompt zu bestellen.
Diese Datei hält trotzdem fest, was entschieden und was gebaut wurde — aus demselben Grund wie
bei M4/M7b: damit README, Plan und Code hier zusammenpassen.

Setzt Plan Abschnitt 6 um. Nach AGENTS.md eine besondere Sorgfaltspflicht: "Crypto/BLE security
code (M6): implement exactly as the plan specifies; flag any deviation explicitly in the PR — the
owner reviews these parts personally." Jede Abweichung unten ist deshalb bewusst ausformuliert,
nicht nur erwähnt.

## Eine Entscheidung wirkte aus M4 zurück, keine neue nötig

Plan §13 Punkt 2 (Aufbewahrungsdauer/SQLCipher) wurde bereits zu Beginn der M4-Arbeit mit dem
Owner geklärt (siehe `docs/PROMPT_M4.md`): **kein SQLCipher**. Diese Entscheidung gilt unverändert
für M6 — die Messdatenbank bleibt unverschlüsselt, nur die drei sicherheitsrelevanten
Einstellungswerte (siehe unten) sind verschlüsselt. Keine der M6-Aufgaben stieß auf einen weiteren,
in Plan §13 als offen markierten Punkt.

## Was gebaut wurde

- **Bonding**: `Pce323Profile.BONDING_SUPPORTED = false` war bereits aus M0 mit Beweis belegt
  (`createBond()` führte in der M0-Aufzeichnung zu einem sofortigen Disconnect, status 19 +
  `AUTH_FAILED`). Kein erneuter Versuch — die Plan-Konsequenz für genau diesen Fall ("ehrlich
  kennzeichnen statt Sicherheit vortäuschen") wurde direkt umgesetzt: `MeterScreen` zeigt jetzt
  einen Warnhinweis "Unverschlüsselte Verbindung — dieses Gerät unterstützt kein Bonding".
- **`GeraetePinning`**: reine Prüf-Funktion, erkennt einen Advertiser mit demselben Namen wie das
  gepinnte Gerät, aber anderer Adresse (`VERDAECHTIG_GLEICHER_NAME`) — genau das Muster, das ein
  untergeschobenes Gerät beim Namensspoofing erzeugen würde. Verbunden wird immer nur über die
  persistierte Adresse (`BleMeterTransport.connect()`), das war schon vorher so; neu ist, dass ein
  solcher Fund beim (Neu-)Koppeln in `MeterScreen` nicht mehr kommentarlos wie jedes andere Gerät
  angeboten wird, sondern mit Warnhinweis, Protokollierung (`Log.w`) und Bestätigungsdialog vor dem
  Umpinnen.
- **Stream-Plausibilisierung**: neuer Kadenz-Watcher in `ConnectionSupervisor.monitorStreamingSession()`
  — weicht die Zeit zwischen zwei Frames zwei Mal in Folge um mehr als ±20% vom erwarteten
  Intervall ab (`Pce323Profile.EXPECTED_FRAME_PERIOD_MS`, nur in `AppContainer` bekannt,
  `ConnectionSupervisor` bleibt frei von BLE-Details), gilt das als möglicher Spoofing-Versuch:
  DEGRADED, Trennung, Log — dieselbe Reaktion wie bei Datenstillstand. Bewusst zwei
  aufeinanderfolgende Abweichungen, nicht eine, nach demselben Prinzip wie der bestehende
  Fehlerraten-Watcher (kein einzelner OS-Scheduler-Jitter soll einen Reconnect auslösen). Optionaler
  Konstruktorparameter, Default `null` (abgeschaltet) — bestehende Tests und `FakeMeterTransport`-
  Nutzung bleiben unverändert.
- **"Keine blinde Kommandoausführung"**: kein Code zu ändern — `BleMeterTransport.send()` ist
  weiterhin ein harter Stub (`UnsupportedOperationException`), es existiert schlicht noch keine
  Kommandoausführung, die gegen Missbrauch abgesichert werden müsste. Bleibt als Konstraint für den
  Tag vermerkt, an dem Kommandoversand gebaut wird (nicht Teil dieses Plans).
- **Verschlüsselte Ablage**: `EncryptedSharedPreferences` (androidx.security-crypto, AES256-SIV/GCM,
  Masterkey im Android Keystore) für `ntfyTopic`, `ntfyServer`, `heartbeatUrl` — die drei Werte, die
  seit dem Streichen des SMS-Kanals übrig sind und tatsächlich schützenswert sind (Zugangskontrolle
  des Alarmkanals bzw. der Totmannschaltung). Alle anderen Einstellungen bleiben unverschlüsselt,
  kein Sicherheitsgewinn durch Verschlüsseln eines dB-Schwellwerts. Migration eines bestehenden
  Klartextwerts beim ersten Lesen, Fallback auf Klartext, falls der Keystore auf einem Gerät nicht
  verfügbar ist (dokumentierte statt stillschweigende Lücke).
- **Manifest-Absicherung**: `allowBackup=false`, `dataExtractionRules`, `FileProvider` waren aus
  früheren Meilensteinen bereits korrekt vorhanden — verifiziert, nicht neu gebaut. Eine Lücke in
  den (bei `allowBackup=false` ohnehin wirkungslosen, aber als Sicherheitsnetz belassenen)
  Backup-Regeln geschlossen: `sharedpref` fehlte im Ausschluss, ergänzt.
- **Diagnose-Log**: neue Tabelle `diagnostic_log_entries` (Migration 9→10), standardmäßig aus
  (`SettingsManager.diagnoseLoggingAktiv`), täglicher Bereinigungs-Job löscht Einträge älter als 7
  Tage (`DiagnosticLogCleanupCoordinator`/`-Worker`/`-Planung`, nur geplant, wenn das Log überhaupt
  eingeschaltet ist — anders als der immer laufende M4-Retention-Job). `ConnectionSupervisor`
  protokolliert bei aktiviertem Log die Ereignisse, die es ohnehin erkennt (Datenstillstand, hohe
  Fehlerrate, Kadenz-Abweichung, gescheiterte Verbindungsversuche).

## Eine bewusste Abweichung vom wörtlichen Plan-Text

Plan 6 spricht von "Diagnose-/**Rohdaten**-Logs". M6 protokolliert **keine rohen Frame-Bytes** —
nur die oben genannten, bereits erkannten Ereignisse als Text. Begründung: ein Rohframe-Byte-Capture
hätte aktuell keinen sinnvollen Konsumenten — der Diagnose-Screen, der laut Plan Abschnitt 9 ein
"raw frame log" anzeigen soll, ist explizit M7. Datenerfassung ohne Konsument aufzubauen wäre eine
halbfertige Funktion. Die jetzt protokollierten Ereignisse sind fachlich das, was der geplante
"Reconnect-Zähler"/"Decode-Fehlerrate"-Teil des Diagnose-Screens (Plan 9) ohnehin braucht — ein
echtes Rohframe-Capture lässt sich bei Bedarf ergänzen, sobald M7 es tatsächlich anzeigt.

## Nicht verifizierbar ohne echtes Gerät

- **`EncryptedSharedPreferences` ist unter Robolectric/der JVM nicht prüfbar.** Der Provider
  "AndroidKeyStore" existiert dort schlicht nicht (`KeyStoreException: AndroidKeyStore not found`,
  per Diagnosetest verifiziert, kein Software-Ersatz vorhanden). Jeder bestehende Test, der
  `SettingsManager` unverändert konstruiert, läuft real über den dokumentierten Klartext-Fallback.
  Die Migrations-/Fallback-*Logik* selbst ist gegen ein injiziertes Test-Double geprüft (der zweite
  `SettingsManager`-Konstruktorparameter ist dafür injizierbar geworden) — ob
  `EncryptedSharedPreferences` auf einem echten Gerät tatsächlich verschlüsselt, ist damit
  ausdrücklich NICHT durch diese Tests belegt.
- Die Kadenz-Prüfung ist nie gegen einen echten, gleichzeitig aktiven PCE-323 gelaufen, nur gegen
  `FakeMeterTransport` mit unterschiedlichen `frameRateHz`-Werten.
- Das Geräte-Pinning-Warndialog ist nie gegen einen echten zweiten Advertiser mit kollidierendem
  Namen getestet (kein zweites BLE-Gerät mit demselben Namen in der Entwicklungsumgebung
  verfügbar).

Details siehe PR-Beschreibung und `docs/CHECKLISTE_GERAETETEST.md`.
