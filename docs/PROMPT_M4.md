# M4 — Persistenz der Messreihe

**Nachträglich dokumentiert**, nicht vorab als Prompt geschrieben: Der Owner hat direkt
"mach weiter mit m4, m6 und m7" angewiesen, statt vorher einen Umsetzungs-Prompt zu bestellen.
Diese Datei hält trotzdem fest, was entschieden und was gebaut wurde — aus demselben Grund wie
bei M7b: damit README, Plan und Code hier zusammenpassen.

Setzt Plan Abschnitt 8.1–8.3 um (Room-Schema, Schreibstrategie, akustische Kennwerte) sowie
Abschnitt 4.5 (Trigger-Umstellung auf das Messgerät neben dem Mikrofon).

## Eine Entscheidung des Owners

Plan §13 Punkt 2 war zu Beginn der Session als offen markiert und betraf sowohl M4 als auch M6 —
nach AGENTS.md („Wenn du auf eine im Plan als offen markierte Entscheidung stößt, entscheide
NICHT — frage den Eigentümer") wurde vorab gefragt statt selbst entschieden:

| Frage | Plan-Vorschlag | Entscheidung |
|---|---|---|
| Aufbewahrungsdauer der Rohmesswerte | 90 Tage, danach Minutenaggregate | **90 Tage**, wie vorgeschlagen. |
| SQLCipher (Datenbankverschlüsselung) | empfohlen | **Nein, unverschlüsselt.** Die App-Sandbox von Android schützt bereits gegen andere Apps; der Aufwand beim Öffnen/Migrieren der Datenbank steht dazu nicht im Verhältnis. Wirkt auch auf M6 zurück: dort entfällt SQLCipher, nur EncryptedSharedPreferences für Alarmkonfiguration/Rufnummern bleibt. |

## Was gebaut wurde

- **Room-Schema** (Migration 8→9, additiv): `SessionEntity` (eine Zeile pro
  Überwachungsperiode — beginnt mit dem Start der Beobachtung, nicht erst beim ersten
  erfolgreichen Frame, und überlebt Reconnects innerhalb derselben Periode),
  `MeasurementEntity` (ein Messwert, mit Bit-Flags für Hold-Max/-Min/großer Sprung),
  `ConnectionEventEntity` (ein Ereignis je Verbindungsänderung: CONNECTED/DISCONNECTED/
  DEGRADED/RECOVERED), `MinuteAggregateEntity` (Ziel der Verdichtung). Bewusst getrennt von
  M7b's `level_samples` — das ist ein schlanker, nach jedem Sync-Zyklus geleerter Puffer für den
  Drive-Export, kein permanenter Messreihen-Bestand. Beide Tabellen koexistieren absichtlich;
  eine Konsolidierung ist als möglicher künftiger Schritt vermerkt, nicht Teil dieser Aufgabe.
  Drei neue Spalten auf `noise_records` (`calibratedDbA`, `meterWeighting`, `meterConnected`),
  ohne `dbValue` zu ersetzen — beide Werte bleiben aussagekräftig (KDoc in `NoiseRecord.kt`).
- **`MeasurementRecorder`**: reiner Session-Lebenszyklus- und Batch-Writer, kennt nur
  `ConnectionState`/`MeterFrame` und die DAOs, kein BLE- oder WorkManager-Detail — wie
  `AlarmCoordinator` vollständig gegen Fakes testbar. Puffert Messwerte im Speicher, schreibt
  alle 5 s oder ab 50 Werten in einer Transaktion, flusht zwangsweise bei `stop()`. Eine
  zusammenhängende Ausfallperiode (z. B. DISCONNECTED → RECONNECTING → FAILED) erzeugt genau
  eine `ConnectionEventEntity`-Zeile, nicht eine je Zwischenzustand (Plan 8.1: vier Ereignistypen,
  nicht ein Typ pro `ConnectionState`).
- **`MeterTriggerSource`**: reine Entscheidungslogik für die Trigger-Umstellung (Plan 4.5) —
  ist ein Messgerät verbunden, löst dessen kalibrierter Wert gegen `meterDbThreshold` aus, sonst
  der Mikrofonwert gegen `dbThreshold`. Zwei getrennte Schwellwerte, weil „60" bei dBFS+Offset
  und kalibriertem dBA nichts Vergleichbares bedeutet — eigener Schieber in den Einstellungen.
- **`AkustischeKennwerte`**: LAeq als energetischer Mittelwert (nicht das arithmetische Mittel —
  Plan 8.3 nennt das ausdrücklich einen „klassischen und im Protokollkontext gravierenden
  Fehler"), Max/Min, L10/L50/L90 per Nearest-Rank-Perzentil, Überschreitungsdauer aus den
  tatsächlichen Zeitabständen zwischen sortierten Messwerten (nicht naiv Anzahl × Intervall, da
  Messwerte unregelmäßig eintreffen).
- **`RetentionCoordinator`/`RetentionWorker`/`RetentionPlanung`**: täglicher WorkManager-Job nach
  dem Coordinator/Worker-Muster von Heartbeat und DriveSync. Verdichtet Rohwerte älter als 90
  Tage je Session und Minute zu `MinuteAggregateEntity` — erst die Aggregate schreiben, dann die
  Rohwerte löschen, damit ein Abbruch dazwischen im schlimmsten Fall doppelte Aggregate erzeugt,
  nie aber Datenverlust.
- Die A/C-Frequenzbewertung wird konsequent als `null` gespeichert, solange
  `MeterFrame.modeAssumptionConfirmed == false` — durchgängig in `MeasurementEntity.weighting`,
  `SessionEntity.weighting`, `NoiseRecord.meterWeighting` und `MinuteAggregateEntity.weighting`
  (dort zusätzlich `null`, wenn innerhalb einer Minute A- und C-Werte gemischt vorkommen).

## Zwei Bugs, die die Verdrahtung selbst aufgedeckt hat

1. **`levelSampleCollector.pegel(LevelSource.MIKROFON, …)` fehlte komplett** in
   `AudioRecordingService`'s Aufnahmeschleife — nur die Messgerät-Seite rief den Collector auf.
   Der in M7b gebaute Drive-Sync hat dadurch **nie** Mikrofonwerte gesammelt, obwohl die
   M7b-PR-Beschreibung ausdrücklich behauptet, der Sync laufe „auch ohne gepinntes PCE-323 allein
   mit Mikrofonwerten". Behoben durch Ergänzen des fehlenden Aufrufs in derselben Schleife, in
   der die Trigger-Umstellung eingebaut wurde.
2. **`RetentionPlanung.plane()` zunächst in `LaermprotokollApp.onCreate()` verdrahtet** — das ließ
   unter Robolectric (instanziiert für jeden Test die echte `Application`) ca. 30 zuvor grüne
   Tests mit „WorkManager is not initialized" abstürzen. Wie bei Heartbeat/DriveSync gehört die
   Planung an das Service-Lifecycle, nicht an `Application.onCreate()` — jetzt in
   `AudioRecordingService.onStartCommand()`, ungated (anders als Heartbeat/DriveSync, da eine
   Verdichtung nicht von einer Einstellung abhängt).

## Bewusst nicht umgesetzt

Plan 8.2 nennt neben dem Flush bei Service-Stopp auch einen Zwangs-Flush bei `onTrimMemory` —
das ist **nicht** umgesetzt. Der 5-s/50-Werte-Flush deckt den Normalfall ab; ein harter
Speicherdruck kurz vor einem Prozess-Kill könnte im ungünstigsten Fall bis zu 5 s bzw. 50
Messwerte verlieren. Als bewusst offen gelassene Lücke im PR vermerkt, nicht stillschweigend
weggelassen.

## Nicht verifizierbar ohne echtes Gerät

- Ob der Retention-Job in der Praxis tatsächlich täglich anläuft und die richtige Datenmenge
  verdichtet, ist nur über einen mehrtägigen Dauerlauf am Gerät zu beobachten.
- Die Trigger-Umstellung ist nie gegen einen echten, gleichzeitig aktiven PCE-323 UND Mikrofon
  gelaufen — nur gegen Fakes.
- Ob die Frequenzbewertung tatsächlich A oder C ist, bleibt bis zur Frequenzgang-Messung aus
  Teil B2 der Geräte-Checkliste offen; M4 speichert bis dahin korrekt `null`.

Details siehe PR-Beschreibung und `docs/CHECKLISTE_GERAETETEST.md`.
