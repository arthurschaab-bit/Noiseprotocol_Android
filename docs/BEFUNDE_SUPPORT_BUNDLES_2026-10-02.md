# Befunde aus der Analyse der periodischen Support-Bundles (01.10. und 02.10.2026)

Auswertung von vier automatischen, periodischen Support-Bundles aus Google Drive (`Support-Bundle (1)`).
Analysiert wurden die Systemzustände, Logcats, Breadcrumbs, Diagnoseereignisse, Einstellungen und Datenbankstatistiken zweier Testgeräte:
1. **Google Pixel 9 Pro** (Android 17 / SDK 37, Build `1.0.0-pr216.ci764+bbf1a09`)
2. **HUAWEI P30 (`ELE-L29`)** (Android 10 / SDK 29, Build `1.0.0-pr216.ci764+bbf1a09`)

---

## 1 · Übersicht der untersuchten Bundles

| Bundle | Erstellungszeitpunkt (UTC / Lokal) | Gerät | Zustand / Dienst | DB-Größe |
|---|---|---|---|---|
| `2026-10-01_035739_periodisch.zip` | 01.10. 01:57:42 UTC (03:57) | Google Pixel 9 Pro | Inaktiv (CONNECTING) | 65,27 MB |
| `2026-10-01_065025_periodisch.zip` | 01.10. 04:50:52 UTC (06:50) | HUAWEI ELE-L29 | **Aktiv (STREAMING)** | 913,50 MB |
| `2026-10-02_035948_periodisch.zip` | 02.10. 01:59:50 UTC (03:59) | Google Pixel 9 Pro | Inaktiv (IDLE) | 65,27 MB |
| `2026-10-02_065052_periodisch.zip` | 02.10. 04:51:19 UTC (06:50) | HUAWEI ELE-L29 | **Aktiv (STREAMING)** | **1.044,75 MB** |

### Kennzahlen im direkten Vergleich

| Metrik | Pixel 9 Pro (01.10.) | Pixel 9 Pro (02.10.) | Huawei P30 (01.10.) | Huawei P30 (02.10.) |
|:---|---:|---:|---:|---:|
| `level_samples` | 1.200.082 | 1.200.082 | 16.673.134 | **19.001.421** (+2,33 Mio.) |
| `measurements` | 89.002 | 89.002 | 1.047.227 | **1.214.877** (+167k) |
| `noise_records` | 3.816 | 3.816 | 22.465 | **24.800** (+2.335) |
| `diagnostic_log_entries` | 222 | 232 | 45.538 | **48.462** (+2.924) |
| `sessions` | 11 | 11 | 214 | 214 |
| `connection_events` | 62 | 62 | 523 | 533 |
| DB-Wachstum (letzte 24h) | +49 KB | 0 B | +74,5 MB | **+137,3 MB** |
| Heap genutzt / max | 13 MB / 256 MB | 4 MB / 256 MB | 65 MB / 384 MB | 72 MB / 384 MB |
| Reconnect-Zyklen (Health) | 0 | 0 | 3 | **5** |
| Aufgelaufene Fehlercodes | `BLE_STREAM_STALLED: 3`<br>`BLE_CADENCE_INVALID: 9` | *keine* | `BLE_STREAM_STALLED: 1`<br>`BACKUP_CREATE_FAILED: 1` | `BACKUP_CREATE_FAILED: 30`<br>`DRIVE_UPLOAD_FAILED: 24`<br>`DRIVE_SYNC_FAILED: 2`<br>`BLE_CADENCE_INVALID: 5` |

---

## 2 · Befunde auf dem Produktivgerät HUAWEI P30 (`ELE-L29`)

### Befund 1: Totalausfall der automatischen Datenbanksicherung (`BACKUP_CREATE_FAILED: 30`)

* **Symptom:** In `state/health_metrics.json` des Bundles vom 02.10. sind **30 fehlgeschlagene Datenbanksicherungen** innerhalb von 24 Stunden verzeichnet.
* **Zeitliche Einordnung:**
  * Letzter erfolgreicher Upload (`datenbank_sicherung_last_success_at`): `1790831328869` = **01.10.2026 um 07:08:48 Uhr Lokalzeit**.
  * Letzter Versuch (`datenbank_sicherung_last_attempt_at`): `1790916287396` = **02.10.2026 um 06:44:47 Uhr Lokalzeit**.
  * Seit über 23 Stunden ist keine einzige Datenbanksicherung mehr nach Google Drive hochgeladen worden.
* **Root Cause im Code:**
  In `com.example.lrmprotokoll.backup.SicherungManager.kt` (Zeile 130–135) steht:
  ```kotlin
  val benoetigterPlatz = (dbDatei.length() * SICHERUNG_SPEICHERPLATZ_FAKTOR).toLong() // Faktor 1.1
  val zielVerzeichnis = ziel.absoluteFile.parentFile ?: ziel.absoluteFile
  val freierPlatz = freierPlatzErmitteln(zielVerzeichnis)
  if (freierPlatz < benoetigterPlatz) {
      throw UnzureichenderSpeicherplatzException(freierPlatz, benoetigterPlatz)
  }
  ```
  Die Room-Datenbank `noise_database` ist auf **1.095.499.776 Bytes (1,05 GB)** angewachsen. Die Prüfung verlangt daher mindestens **1,16 GB freien internen Speicher** (`context.cacheDir`). Auf dem Huawei P30 steht dieser zusammenhängende Speicher im Cache nicht mehr zur Verfügung. Die Funktion bricht vor dem Schreiben mit `UnzureichenderSpeicherplatzException` ab, und `DriveSyncCoordinator.ladeDatenbankSicherungHoch.bauen` meldet `BACKUP_CREATE_FAILED`.

---

### Befund 2: Ungebremstes Datenbankwachstum durch `level_samples` (19 Millionen Zeilen)

* **Symptom:** Die Datenbankgröße hat die 1-GB-Marke überschritten (1.044,75 MB) und wächst mit rund **137 MB pro Tag**.
* **Ursache:** Die Tabelle `level_samples` speichert jeden hochfrequenten Pegelwert (10 Hz vom PCE-323). In `DriveSyncCoordinator.kt` (Zeile 246) gilt:
  ```kotlin
  levelSampleDao.loescheVor(jetzt.minus(Duration.ofDays(30)).toEpochMilli())
  ```
  * Da die Werte 30 Tage lang aufbewahrt werden, summiert sich der Datenbestand auf rund **25 Millionen Zeilen**.
  * Gleichzeitig wurden die aggregierten Tageswerte längst als `laermprotokoll_YYYY-MM-DD.csv` nach Google Drive synchronisiert. Die Rohdaten der vergangenen Wochen verbleiben dennoch lokal im SQLite-Speicher und verstopfen das Gerät.

---

### Befund 3: Dauerhafte CursorWindow-Überläufe (2-MB-Limit) und starker GC-Druck

* **Symptom:** Im Logcat des Huawei P30 treten ununterbrochen Warnungen der Form auf:
  ```text
  10-02 06:50:21.075 10349 10454 W CursorWindow: Window is full: requested allocation 216 bytes, free space 169 bytes, window size 2097152 bytes
  10-02 06:50:21.405 10349 10454 W CursorWindow: Window is full: requested allocation 90 bytes, free space 64 bytes, window size 2097152 bytes
  ...
  10-02 06:50:27.444 10349 10363 I le.lrmprotokol: Background young concurrent copying GC freed 323769(13MB) AllocSpace objects, 12% free, 71MB/82MB
  ```
* **Auslöser:**
  1. **Während des Drive-Syncs (`aggregiereInAbschnitten`):** In Abschnitten von einer Stunde lädt `levelSampleDao.zwischen(...)` rund 36.000 Samples. Die Ergebnismenge erreicht bzw. überschreitet die 2.097.152 Bytes des Standard-`CursorWindow`. Android muss das Fenster verwerfen und mehrfach neu allozieren.
  2. **Periodische Ticks:** Alle 5 Sekunden werden Status- und Health-Prüfungen angestoßen, die auf die gewachsene Datenbank zugreifen.
  3. **Folge:** Häufige Garbage-Collection-Pausen (alle 2–5 Sekunden werden 15–28 MB temporäre Objekte abgeräumt).

---

### Befund 4: Nächtliche Upload-Aussetzer (`DRIVE_UPLOAD_FAILED: 24`, `DRIVE_SYNC_FAILED: 2`)

* **Symptom:** 24 fehlgeschlagene Upload-Versuche stündlicher WAV-ZIPs.
* **Verlauf:**
  * Zwischen 00:44 Uhr Lokalzeit (`2026-10-01T22:44:13Z`) und 06:01 Uhr Lokalzeit (`2026-10-02T04:01:10Z`) konnte kein Drive-Sync abgeschlossen werden (WLAN getrennt / Doze-Modus).
  * Da in dieser Zeit 2.335 Lärmereignisse auftraten, triggerte jedes Ereignis einen Sofort-Sync. Die fehlschlagenden Uploads wurden vorschriftsmäßig als `DRIVE_UPLOAD_FAILED` erfasst.
  * Sobald das Netzwerk morgens um 06:13 Uhr wieder verfügbar war, holte der Koordinator den Rückstand auf und lud 22.872 Zeilen erfolgreich hoch.

---

### Befund 5: Schwachstelle im `SupportBundleExporter` (`events.jsonl` veraltet)

* **Symptom:** In den Support-Bundles vom 01.10. und 02.10. enden die Einträge in `log/events.jsonl` am **28.09.2026 um 07:52:33 Uhr UTC**. Neuere Einträge aus Oktober sind im Bundle nicht enthalten.
* **Ursache im Code:**
  In `com.example.lrmprotokoll.diagnose.export.SupportBundleExporter.kt` (Zeile 337–355):
  ```kotlin
  var nachId = 0L
  var geschrieben = 0L
  while (true) {
      val seite = diagnosticLogDao.seite(nachId, SEITENGROESSE)
      if (seite.isEmpty()) break
      for (log in seite) {
          ...
          if (geschrieben + zeile.size > maxBytes) return // 1 MB Obergrenze
          out.write(zeile)
          geschrieben += zeile.size
      }
      nachId = seite.last().id
  }
  ```
  * `diagnostic_log_entries` umfasst 48.462 Zeilen.
  * Das 1-MB-Budget wird bereits nach 7.422 Zeilen erreicht.
  * Da `nachId` bei `0` beginnt und **aufsteigend (alt -> neu)** liest, bricht die Schleife im September ab. Die 41.000 neuesten Logeinträge werden niemals exportiert.

---

## 3 · Befunde auf dem Testgerät Google Pixel 9 Pro

### Befund 6: Endloser Auto-Connect im Ruhezustand führt zu `LOW_MEMORY`-Prozessende

* **Symptom:**
  * Das Monitoring wurde am 30.09. um 18:09:06 Uhr explizit beendet (`STOP_SERVICE`).
  * Dennoch verzeichnet das Bundle vom 01.10. `BLE_STREAM_STALLED: 3` und `BLE_CADENCE_INVALID: 9`.
  * Am 01.10. um 19:10:32 UTC protokollierte das System: `Vorheriger Prozess-Exit: LOW_MEMORY (Status: 9)`.
* **Ursache:**
  * `meter_auto_connect` stand weiterhin auf `true`. `MeterAutoConnect` versuchte im Hintergrund alle 15 Minuten, eine Verbindung zum PCE-323 aufzubauen.
  * Da das Messgerät mit dem Huawei P30 gekoppelt war, brachen die Versuche nach 8 Wiederholungen ab (`Kein Frame innerhalb von 5000ms nach Verbindungsaufbau`).
  * Das inaktive Hintergrundpaket wurde schließlich von Android wegen Speicherdrucks im System terminiert (`ExitReason: LOW_MEMORY`).
