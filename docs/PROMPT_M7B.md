# M7b — Google-Drive-Synchronisation

**Nachträglich dokumentiert**, nicht vorab als Prompt geschrieben: Der Owner hat direkt
"mach eher mal m7b" angewiesen, statt vorher einen Umsetzungs-Prompt zu bestellen. Diese Datei
hält trotzdem fest, was entschieden und was gebaut wurde — aus demselben Grund wie bei den
anderen Meilensteinen: damit README, Plan und Code hier zusammenpassen.

Setzt Plan Abschnitt 8.4 um. Voraussetzung war *nicht* M4 — Plan §12 hält ausdrücklich fest, dass
der Sync "vollständig mit den heutigen Mikrofonwerten" baubar ist, unabhängig vom Bluetooth-Pfad.

## Vier Entscheidungen des Owners

Plan §13 listet für M7b drei offene Punkte mit Vorschlag; eine vierte Frage kam während der
Umsetzung dazu, weil Google Sign-In eine echte OAuth-Client-ID braucht, die kein Agent selbst
anlegen kann.

| Frage | Plan-Vorschlag | Entscheidung |
|---|---|---|
| Aggregationsintervall | 10 s | **So fein wie technisch sinnvoll — 1 s, konfigurierbar.** Dafür WLAN-only default an, um das höhere Uploadvolumen abzufangen. |
| OAuth-Scope | `drive.file` | **`drive.file`**, wie vorgeschlagen. |
| WAV-Upload | Nein | **Als Option vorhanden, Default aus.** Abweichend vom Plan-Vorschlag „nein" — der Owner wollte die Möglichkeit, nicht den Ausschluss. |
| OAuth-Client-ID | — | **Code fertig, Client-ID bleibt Platzhalter.** Der Owner richtet sie selbst über die Google Cloud Console ein (Anleitung in `GoogleClientConfig`), sobald er dazu kommt. |

## Was gebaut wurde

- **`LevelSampleCollector`**: puffert Pegelwerte aus Mikrofon *und* PCE-323 gebündelt, nicht pro
  Wert (Plan-8.2-Prinzip auf den Sync-Puffer übertragen).
- **`PegelAggregator`**: verdichtet zu Zeitfenstern, LAeq als energetischer Mittelwert, Lücken
  als `KEINE_VERBINDUNG`-Zeile statt ausgelassen (Plan 8.4.2), Ereignis-Abgleich mit
  `NoiseRecord`.
- **`DriveCsv`**: Semikolon, Dezimalkomma, UTF-8-BOM, CRLF — mit einer bewussten Abweichung vom
  Plan-Beispiel: Spaltenköpfe heißen `LAeq_dB`, nicht `LAeq_dBA` (weder Mikrofon noch PCE-323
  liefert aktuell einen gesicherten A-bewerteten Wert).
- **`GoogleDriveApiClient`**: dünner REST-Zugriff via OkHttp statt des vollen Google-API-Client-
  SDK, getestet gegen `MockWebServer`.
- **`DriveSyncCoordinator`** / **`DriveSyncWorker`**: die Sync-Entscheidungslogik getrennt von
  der WorkManager-Glue, Idempotenz mit Dedup-Suche gegen Waisen (Plan 8.4.4), Fehlerbehandlung
  nach Plan 8.4.6.
- **`DriveSyncNotifier`**: eigener, ruhiger Notification-Kanal — ausdrücklich getrennt vom
  M5-Alarmkanal (Plan 8.4.6: "sonst wird der Alarmkanal abgestumpft").
- **Google-Anmeldung**: Credential Manager + `AuthorizationClient`, `drive.file`-Scope.
- **Einstellungen**: Sync ein/aus, Google-Verbindung mit Ordneranlage, Aufzeichnungsgenauigkeit
  als Schieber, WLAN-only, WAV-Upload-Option.

## Ein Bug, den die Verdrahtung selbst aufgedeckt hat

`ensureMeterMonitoringStarted()` bricht ganz am Anfang ab, wenn kein Messgerät gepinnt ist. Der
Drive-Sync-Start stand ursprünglich im selben Block — wäre also nie gelaufen, wenn kein PCE-323
gepinnt ist. Genau der Fall, den Plan 8.4 ausdrücklich unterstützen soll. Behoben durch eine
eigene, ungegatete `ensureDriveSyncStarted()`.

## Nicht verifizierbar ohne Netzzugang und ohne echte Client-ID

- Der gzip-Upload-Pfad folgt der dokumentierten Drive-v3-API, ist aber nie gegen den echten
  Server gelaufen (kein Netzzugang zu `googleapis.com` in der Entwicklungsumgebung).
- Die komplette Google-Anmeldung (Credential Manager, `AuthorizationClient`) braucht echte Play
  Services auf einem echten Gerät und eine echte OAuth-Client-ID.
- Ob ntfy (M5) oder Drive (M7b) sich in der Praxis gegenseitig stören (z. B. gleichzeitige
  WorkManager-Läufe), ist nur am Gerät zu beobachten.

Details siehe PR-Beschreibung und `docs/CHECKLISTE_GERAETETEST.md`.
