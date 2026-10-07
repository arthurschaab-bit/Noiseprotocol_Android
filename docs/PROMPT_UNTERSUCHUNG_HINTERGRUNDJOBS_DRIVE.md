# Prompt: Untersuchung — Warum laufen Hintergrundjobs nicht oder werden abgebrochen?

**Priorität 7, Welle 2.** Befunde H und B aus
[`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Das ist zuerst eine Untersuchung, kein Fix.** Teil 1 baut Messpunkte ein und ist freigegeben.
Teil 2 wertet die Messung aus. Teil 3 (Umbau) passiert **nur nach Owner-Entscheidung**. Rate
keine Ursache und baue keinen Umbau auf Verdacht.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/hintergrundjobs-drive-untersuchung origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `feat(diagnose): Zustand der WorkManager-Arbeiten ins Support-Bundle schreiben`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK. **Keine Schemaänderung.**
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.
- `PROMPT_FIX_AUFZEICHNUNGSLUECKEN_SICHTBAR.md` ändert später ebenfalls
  `DriveSyncCoordinator.kt`. Halte deine Änderungen dort klein.

---

## 1 · Befunde

### H — Am 06.10. startete kein einziger Sync, obwohl die App aufzeichnete

| Fenster | `Drive-Sync-Zyklus gestartet` (Breadcrumb aus `DriveSyncWorker.doWork`) |
|---|---|
| 01.10. 14:14 – 05.10. 04:31 UTC (P30-Bundle vom 05.10.) | **207 ×** |
| 06.10. 05:58 – 10:09 UTC, App lebt und zeichnet auf (P30-Bundle vom 07.10.) | **0 ×** |
| 07.10. 04:52:15 UTC, direkt nach dem Öffnen der App | 3 × in derselben Millisekunde, dazu 1 × „wartet auf laufenden Zyklus“ |

- Nach jeder WAV-Aufnahme stößt die App einen Sofortlauf an (`DriveSyncPlanung.starteSofort`,
  `ExistingWorkPolicy.KEEP`). Dazu kommt der periodische Lauf alle 30 min. Am 06.10. gab es
  Dutzende Aufnahmen und keinen Start.
- Weitere Zeitpunkte aus `settings.json`:
  - `drive_sync_last_success_at` = 05.10. 12:19:15 UTC;
  - `datenbank_sicherung_last_attempt_at` = 05.10. 11:55:49 UTC, danach kein Erfolg.
- Am 06.10. um 12:41 wurde die App dann vom System beendet (Befund G).

### B — Datenbanksicherung, Bundle vom 05.10.

- 16 × `BACKUP_CREATE_FAILED @ DriveSyncCoordinator.ladeDatenbankSicherungHoch.hochladen: Job was
  cancelled -> JobCancellationException`, 01.10. 18:40 – 02.10. 16:42 UTC.
- 9 × `… hochladen: Drive nicht erreichbar -> DriveApiException`, 01.10. 14:16–21:19 UTC.
- Kein Erfolg in diesem Fenster. Der erste belegte Erfolg ist am 05.10. um 10:59:10 UTC.
- Die Datenbank war am 05.10. 1,10 GB groß.
- `ladeDatenbankSicherungHoch` läuft **innerhalb** des Sync-Zyklus, also unter derselben
  Worker-Laufzeit.
- `JobCancellationException` heißt: Der Worker wurde gestoppt. Das Warum steht nirgends.

---

## 2 · Hypothesen und was sie belegen würde

| Hypothese | Beleg dafür | Beleg dagegen |
|---|---|---|
| **H1** Netzbedingung nicht erfüllt (`driveWlanOnly`, kein WLAN) | Die Arbeit steht auf `ENQUEUED` mit unerfüllter Bedingung | `CONNECTED`/WLAN im Bundle zum fraglichen Zeitpunkt |
| **H2** EMUI unterdrückt die Jobs der App | `ENQUEUED`, Bedingungen erfüllt, `nextScheduleTime` überschritten, trotzdem kein Lauf | Läufe kommen pünktlich |
| **H3** Ein laufender Worker blockiert die eindeutige Arbeit (`KEEP`, periodisch) | `RUNNING` über lange Zeit, Stoppgrund später `TIMEOUT` | kein langer `RUNNING`-Zustand |
| **B1** 10-Minuten-Grenze von JobScheduler beim großen Upload | Stoppgrund `TIMEOUT` passend zur Sicherung | Stopp unter 10 min Laufzeit |
| **B2** Stopp, weil eine Bedingung wegfällt (Netzwechsel) | Stoppgrund `CONSTRAINT_CONNECTIVITY` | — |

WorkManager ist in Version 2.9.1 eingebunden. Prüfe in `gradle/libs.versions.toml`, ob
`WorkInfo.stopReason` (ab 2.9) und `nextScheduleTimeMillis` verfügbar sind. Stoppgründe liefert
das System erst ab Android 12. Auf dem P30 (Android 10) bleibt `stopReason` deshalb unbekannt,
Laufzeit und Zustand helfen trotzdem.

---

## 3 · Auftrag

### Teil 1 — Messpunkte (freigegeben)

1. **WorkManager-Zustand im Support-Bundle:** In `runtime.json` (`SupportBundleExporter`) für jede
   eindeutige Arbeit der App:
   - Name, `state`, `runAttemptCount`;
   - `nextScheduleTimeMillis` und `stopReason`, falls verfügbar;
   - Bedingungen: Netztyp.

   Die Namen stehen in den `…Planung`-Objekten (`WORK_NAME`). Suche sie mit
   `grep -rn "WORK_NAME" app/src/main`.
2. **Angefordert gegen gestartet:** Ein Breadcrumb, wenn `DriveSyncPlanung.starteSofort()`
   aufgerufen wird. Er wird **gedrosselt**, höchstens einer je 10 min, um das Protokoll nicht zu
   fluten (Befund I). Zusammen mit `Drive-Sync-Zyklus gestartet` zeigt das Bundle danach
   „angefordert, aber nie gestartet“.
3. **Warum ein Worker endet:**
   - In `DriveSyncWorker` die Laufzeit und, falls verfügbar, `stopReason` protokollieren, wenn der
     Worker abgebrochen wird. Dazu `isStopped` prüfen bzw. `CancellationException` fangen,
     protokollieren und **erneut werfen**.
   - Bei `BACKUP_CREATE_FAILED` die bisherige Laufzeit des Zyklus und die Größe der Sicherungsdatei
     in die Details schreiben.
4. **Abbrüche nicht verschlucken:** `ladeDatenbankSicherungHoch` nutzt `runCatching { quelle() }`,
   und `DriveDatenbankSicherung.hochladen` liefert ein `Result`. Beides fängt auch
   `CancellationException` und meldet sie als gewöhnlichen Fehler. Danach läuft der
   abgebrochene Zyklus weiter, bis zur nächsten Unterbrechungsstelle. Prüfe das. Trifft es zu,
   wirf `CancellationException` weiter, statt sie als Fehler zu melden. Das ist ein echter
   Fehler, kein reines Messen. Er ist klein und gehört in diesen PR.

### Teil 2 — Auswertung (nach dem Merge, mit dem Owner)

- Der Owner stellt zuerst die EMUI-Einstellungen um (Abschnitt 10 im Befunddokument) und schickt
  nach einem Messtag ein Bundle.
- Werte es gegen die Tabelle in Abschnitt 2 aus und schreib das Ergebnis als Nachtrag in
  `docs/BEFUNDE_BUNDLES_2026-10-07.md`.

### Teil 3 — Umbau (nur nach Owner-Entscheidung, siehe Abschnitt 5)

Kein Code vor der Entscheidung.

---

## 4 · Tests und Akzeptanzkriterien für Teil 1

1. `runtime.json` enthält für jede geplante eindeutige Arbeit Name und Zustand. Robolectric mit
   `WorkManagerTestInitHelper`.
2. `starteSofort()` zweimal in 10 min → genau ein Breadcrumb.
3. Ein abgebrochener Worker → Breadcrumb mit Laufzeit, und die `CancellationException` wird
   weitergeworfen.
4. `ladeDatenbankSicherungHoch` bei Abbruch → **kein** `BACKUP_CREATE_FAILED`, Abbruch
   weitergereicht. **Vorher rot.**

- [ ] Die Tests 1–4 sind grün, Test 4 war vorher rot.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.
- [ ] Im PR steht, welche Hypothese das nächste Bundle beantwortet und wie.

## 5 · Offene Entscheidung (erst nach Teil 2, AGENTS.md §8a)

- **E1 — Datenbanksicherung, falls B1 belegt ist:**
  - (a) Eigener Worker mit `setForeground` (lang laufender Worker, Dienst-Typ `dataSync`). Ohne
    10-Minuten-Grenze, aber mit eigener Benachrichtigung während des Uploads.
  - (b) Den Resumable Upload über mehrere Läufe fortsetzen und die Sitzungs-URI speichern.
  - (c) Sicherung seltener, z. B. nachts oder nur am Netzteil.

  Empfehlung erst nach den Messdaten.

Die Freigabe vom 07.10.2026 („Empfehlungen freigeben“) deckt E1 **nicht** ab, weil es dafür noch
keine Empfehlung gab. E1 bleibt offen, bis Teil 2 ausgewertet ist.

## 6 · Gerätecheck (macht der Owner nach dem Merge)

- Nach einem Messtag ein Bundle erzeugen. `runtime.json` zeigt die Arbeiten mit Zustand, und im
  Protokoll stehen „angefordert“ und „gestartet“ nebeneinander.

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
