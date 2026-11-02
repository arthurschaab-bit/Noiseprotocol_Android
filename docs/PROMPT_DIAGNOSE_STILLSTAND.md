# Prompt: Diagnose — Stillstand der App erkennen, einordnen und belegen

**Auslöser:** Messausfall des Huawei-Messhandys (ELE-L29, Android 10) vom 30.10.2026 13:07:33 bis
01.11.2026 10:57:02, also 45 h 49 min. Der Owner weiß nicht mehr, was am Handy passiert ist. Die
App muss solche Ausfälle künftig **selbst belegen**, statt sich auf die Erinnerung zu verlassen.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

**Dieser Auftrag klärt nicht, *warum* das Handy stillstand.** Auf Android 10 gibt es dafür keine
Quelle in der App. Er sorgt dafür, dass der nächste Ausfall eindeutig **eingeordnet** wird und der
Zustand **kurz vor dem Ausfall** im Bundle steht. Schreib diese Grenze in den PR-Text.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c feature/diagnose-stillstand origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
  Beispiel: `feat(diagnose): Ausfall beim Start nach Ursache einordnen`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK. **Keine Room-Schemaänderung** (Version
  25 bleibt). Zustand liegt in Dateien und `SettingsManager`.
- UI-Texte auf Deutsch, Bezeichner auf Englisch, neue Strings in **allen** Ressourcenordnern.
- **Keine neue Kryptographie.** Keine personenbezogenen Daten und **nie die Heartbeat-URL** in
  Logs oder Bundles (sie ist die Zugangskontrolle, siehe `SettingsManager.heartbeatUrl`).
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## 1 · Befund (aus den Bundles und CSVs vom 30.10.–02.11.2026)

| Beobachtung | Beleg |
|---|---|
| Daten enden 30.10. 13:07:33, Mikrofon ab 01.11. 10:57:02, PCE-323 ab 10:58:47 | `laermprotokoll_*.csv`, `lebenszyklus.jsonl` (`letzteDatenAt` 1793362053107, `lueckeMinuten` 2749) |
| **Kein** Stopp-Ereignis, kein `onDestroy`, keine Ausnahme | `lebenszyklus.jsonl`, `events.jsonl` |
| Der Wächter (`AufzeichnungsWaechterWorker`, alle 15 min) hat in 45,8 h nichts protokolliert. Der einzige Wächter-Neustart liegt 1 s nach dem Prozessstart | `events.jsonl`, `waechter_neustart_count=1` |
| **Der Kernel lief durch:** Uptime beim Prozessstart 100,53 h (`elapsedRealtimeMs` 361899222), also derselbe Boot wie am 28.10. um ca. 06:25. Die Lücke (45,8 h) ist kürzer als die Uptime | `lebenszyklus.jsonl` |
| Trotzdem `BOOT_COUNT` 239 → 240 und `BOOT_COMPLETED` 2 min nach dem Prozessstart | `lebenszyklus.jsonl` |
| Die App meldet das als „Gerät wurde neu gestartet“. Das ist irreführend, ein Kernel-Neustart war es nicht (`LebenszyklusProzessUeberwachung.auswerten`, vergleicht nur `BOOT_COUNT`) | `LebenszyklusRingFile.kt:218` |
| Akku war **nicht** die Ursache: 100 % am Netzteil (07:13), beim Wiederanlauf 82 %. Die Ausfälle am 19.10. (9 %) und 27./28.10. (9 %) waren dagegen leere Akkus | `lebenszyklus.jsonl`, Power-Ereignisse |
| Die Totmannschaltung lief nicht: Der Job `heartbeat` fehlt in `runtime.json` → `hintergrundJobs`, obwohl er in `BEKANNTE_EINDEUTIGE_ARBEITEN` steht (`SupportBundleExporter.kt:97`). Geplant wird er nur in `if (settingsManager.alarmierungAktiv)` (`AudioRecordingService.kt:310–313`), Default `false` | `runtime.json`, Code |
| Auf SDK 29 liefert `ProcessExitCollector` nichts (`ApplicationExitInfo` erst ab API 30, `ProcessExitCollector.kt:57`) | Code |
| Das Lebenszeichen enthält nur `status` und `uhrzeit`. Der letzte bekannte Akkustand vor der Lücke war 6 h alt | `AppContainer.kt:481–490` |

Daraus folgt: Zwei Ausfallarten (Akku leer, ausdrücklicher Stopp) lassen sich heute aus den Logs
ableiten, aber **nur von Hand**. Der dritte Typ (App und Jobs stehen, Gerät läuft weiter) ist nicht
klassifiziert, und für ihn fehlt jeder Zustand aus der Zeit davor.

---

## 2 · Auftrag

### Schritt 1 — Datei „letzter Zustand“ (Vorher-Bild)

Neue Klasse `LetzterZustandDatei` im Paket `diagnose`, **eine** JSON-Datei im internen Speicher,
atomar überschrieben (temporäre Datei, dann `rename`), damit ein Kill mitten im Schreiben nichts
zerstört. Nicht in die Lebenszyklus-Ringdatei: Dort würde jeder Eintrag 64 KB schneller füllen, und
die Ringdatei reicht heute genau 14 Tage zurück.

Inhalt (kleine Zahlen und Flags, nichts Personenbezogenes):
`zeitpunkt`, `akkuProzent`, `stromquelle` (vorhandener `Stromzustand`), `uptimeMs`, `bootCount`,
`letzteDatenAt`, `bleZustand`, `mikrofonAktiv`, `dienstLaeuft`, `sauberBeendet`,
`dozeAktiv` (`isDeviceIdleMode`), `energiesparModus` (`isPowerSaveMode`),
`hintergrundEingeschraenkt` (`ActivityManager.isBackgroundRestricted`, API 28),
`standbyBucket` (`UsageStatsManager.getAppStandbyBucket`, API 28, braucht für die eigene App keine
Sonderberechtigung; prüfen), `waechterLetzterLaufAt`, `heartbeatLetzterErfolgAt`.

Geschrieben wird beim **Lebenszeichen** (`AppContainer.kt`, `onLebenszeichen`), bei jedem
Stromwechsel (`StromversorgungEmpfaenger`) und bei Zustandswechsel des BLE-Transports.
`sauberBeendet=false` beim Dienststart, `true` beim ausdrücklichen Stopp und in `onDestroy`. Damit
erkennt der nächste Start „Prozess ohne Abschluss beendet“ **ohne** `ApplicationExitInfo`.

### Schritt 2 — Wächter-Spur

Der Wächter schreibt bei **jedem** Lauf, auch bei `LAEUFT`/`AUS`, einen Zeitstempel
(`waechter_letzter_lauf_at` im `SettingsManager`, kein Breadcrumb, kein Eintrag pro Lauf). Beim
Wiederanlauf zeigt das, ob JobScheduler während der Lücke lief. Lief er nicht, sind Jobs und App
zusammen ausgefallen (Gerät eingefroren, Zwangsbeendigung, Hersteller-Sperre), nicht nur der Prozess.
Dieselbe Spur für den Heartbeat (`heartbeat_letzter_erfolg_at`, `..._fehler_at`).

### Schritt 3 — `AusfallKlassifikator` (reine Funktion, wird getestet)

Beim Start (neben `LebenszyklusProzessUeberwachung.auswerten`) wird eine Lücke eingeordnet, sobald
`letzteDatenAt` und Jetzt mehr als 5 min auseinanderliegen. Eingabe: die Datei aus Schritt 1, die
Wächter-Spur, `elapsedRealtime`, `BOOT_COUNT`, Jetzt. Ausgabe ein Wert plus die Belege als Map:

| Klasse | Regel (Vorschlag, am Testfall prüfen und im PR begründen) |
|---|---|
| `NUTZER_STOPP` | `sauberBeendet=true` und ausdrücklicher Stopp protokolliert |
| `AKKU_LEER` | Kernel-Neustart in der Lücke **und** letzter bekannter Akku ≤ 10 % ohne Ladung |
| `KERNEL_NEUSTART` | Uptime < Lücke (Gerät war aus oder wurde neu gestartet), Akku unauffällig |
| `FRAMEWORK_NEUSTART` | `BOOT_COUNT` geändert, aber Uptime > Lücke (Kernel lief durch) |
| `STILLSTAND_GERAET_LIEF` | Uptime > Lücke, `BOOT_COUNT` gleich, `sauberBeendet=false` |
| `UNBEKANNT` | alles andere. **Nie raten.** |

Je Klasse zusätzlich, ob der Wächter in der Lücke lief (`waechterLief`: ja/nein/unbekannt).
Uptime-gegen-Lücke ist ein grober Vergleich im Stundenbereich. Eine Uhrverstellung um Minuten ändert
das Ergebnis nicht. Das frühere Problem (Uptime gegen den Abstand zweier Starts, siehe KDoc in
`LebenszyklusRingFile.kt:190–199`) bleibt trotzdem im Kopf: Teste Uhrsprünge ausdrücklich.

Ausgabe: ein Eintrag „Ausfall eingeordnet“ in die Lebenszyklus-Ringdatei, `klassifikation` und
`waechterLief` als Details in das bestehende `RECORDING_ENDED_UNEXPECTEDLY` (`AufzeichnungsFortsetzer`
und Wächter), und `speichereUnterbrechung` bleibt unverändert.

**Umbenennen:** „Gerät wurde neu gestartet“ nur noch bei `KERNEL_NEUSTART`/`AKKU_LEER`. Bei
`BOOT_COUNT`-Wechsel ohne Kernel-Neustart: „Android-Framework neu gestartet (Kernel lief weiter)“.
Suche vorher alle Verwendungen des alten Texts in Code, Tests und `docs/` (`grep`) und ändere sie mit.

### Schritt 4 — Totmannschaltung tatsächlich laufen lassen

Sie ist der **einzige** Schutz, der bei eingefrorenem oder zwangsbeendetem Gerät von außen alarmiert
(Wächter und Ntfy laufen im selben toten Prozess).

- **a) Sichtbar machen:** `runtime.json` bekommt `totmannschaltung`:
  `{konfiguriert, geplant, letzterErfolgAt, letzterFehlerAt}`. `konfiguriert` ist ein Boolean für
  „URL gesetzt und gültig“, **nie die URL**. `geplant` kommt aus der WorkManager-Abfrage, die
  `SupportBundleExporter` schon macht.
- **b) Kopplung prüfen:** `HeartbeatPlanung.plane` hängt an `alarmierungAktiv` und am
  Messgeräte-Pfad (`AudioRecordingService.kt:299–313`; ohne Messgerät oder bei reiner
  Mikrofon-Quelle kommt es nie dorthin). Der Commit `f7020b5` hat die **Eingabe** von Pro-Modus und
  Alarmierung entkoppelt, die **Planung** offenbar nicht. Bestätige das am Code. Wenn ja: planen,
  sobald eine gültige URL gesetzt ist und die Aufzeichnung läuft, unabhängig von Alarmierung und
  Quelle. Stoppen beim ausdrücklichen Stopp wie bisher. Test dazu.
- **c) Empfänger (Owner-Entscheidung 02.11.2026):** healthchecks.io, Einrichtung durch den Owner
  nach `docs/EXTERNE_DIENSTE_EINRICHTUNG.md` Abschnitt 2 (Intervall 15 min, Karenzzeit 30 min,
  E-Mail-Kanal). Der Agent legt **kein** Konto an und trägt keine URL ein. Ergänze dort nur, was
  durch Schritt 4b nicht mehr stimmt (die Planung hängt nicht mehr an der Alarmierung) und dass
  die Planung erst beim nächsten Dienststart greift, sofern du sie nicht auch beim Speichern der
  URL auslöst (am Code klären und im PR vermerken).

### Schritt 5 — Support-Bundle

- `state/letzter_zustand.json` (Datei aus Schritt 1) und `state/ausfaelle.json` (die letzten 20
  eingeordneten Ausfälle mit Klasse und Belegen, aus der Lebenszyklus-Ringdatei).
- Beides in das periodische Bundle, nicht erst bei Absturz. Das ZIP-Budget (2 MB periodisch,
  `ZIP_BUDGET_PERIODISCH`) einhalten, kürzen über die bestehende Kürzungsstufe.
- `checksums.sha256` und `manifest.json` bleiben konsistent (vorhandene Tests erweitern).

### Schritt 6 — Karte „Was ist passiert?“ (Owner-Entscheidung 02.11.2026: ja)

Der Owner kann sich an den Ausfall vom 30.10. nicht erinnern. Die App fragt deshalb beim nächsten
Öffnen nach, solange die Erinnerung frisch ist.

- **Wann:** Nach einem eingeordneten Ausfall von mindestens 30 min, **nicht** bei `NUTZER_STOPP`
  und `AKKU_LEER` (dort ist die Ursache belegt, sonst fragt die App täglich wegen der Morgenroutine).
- **Wie:** Karte oben im Hauptbildschirm mit Zeitraum und Dauer („Keine Messung von 30.10. 13:07 bis
  01.11. 10:57, 45 h 49 min“), der Einordnung der App als Hinweis und **genau fünf** Antworten:
  1. „Ich habe die Messung selbst beendet“
  2. „Das Handy war aus oder der Akku leer“
  3. „Das Handy war eingefroren oder reagierte nicht“
  4. „Das Handy lief normal, aber die App war weg“
  5. „**Unklar**“
- Wegwischbar, ohne Pflicht. Jede Lücke wird **einmal** gefragt (Schlüssel: `letzteDatenAt`).
  Unbeantwortete Lücken bleiben im Diagnose-Bildschirm abrufbar, höchstens die letzten 20.
- Die Antwort geht als `nutzerAngabe` in den Ausfall-Eintrag (Lebenszyklus-Ringdatei und
  `ausfaelle.json`). Kein Freitext, damit nichts Personenbezogenes ins Bundle gelangt.
- UI-lastig: gehört nach AGENTS.md §9 an Antigravity. Die Auswahl der Fälle und die Speicherung
  (reine Logik) werden separat getestet.

### Schritt 7 — Hinweis auf Hintergrundbeschränkung (Owner-Entscheidung 02.11.2026: ja)

Im Diagnose-Bildschirm und, wenn zutreffend, als Hinweis im Hauptbildschirm. **Ehrliche Grenze:**
Huaweis „App-Start-Verwaltung“ und der Autostart lassen sich über keine öffentliche API abfragen.
Erkennen kann die App nur diese Signale:

- `isBackgroundRestricted()` (API 28) und der Standby-Bucket (`RESTRICTED`, `RARE`) aus Schritt 1;
- die Batterieoptimierung (`batterieoptimierungIgnoriert` steht schon in `runtime.json`);
- **indirekt:** Wenn nach einem Ausfall die Wächter-Spur (Schritt 2) zeigt, dass **keine Jobs
  liefen**, obwohl das Gerät lief (`STILLSTAND_GERAET_LIEF`/`FRAMEWORK_NEUSTART`), zeigt der Hinweis
  „Das System hat die App und ihre Hintergrundjobs angehalten. Bei Huawei: Einstellungen → Apps →
  App-Start → Lärmprotokoll → **Manuell verwalten**, alle drei Schalter an.“

Ein Sprung in die Huawei-Einstellungen (Activity des Systemmanagers) darf nur mit `try/catch` und
Rückfall auf die allgemeinen App-Infos versuchen. Der Weg heißt je nach EMUI/HarmonyOS anders;
**ohne Gerät nicht prüfbar**, das gehört in den PR. Die Texte sind Platzhalter und brauchen die
Freigabe des Owners.

### Schritt 8 — Warnhinweis „Kein Schutz bei Ausfall“ (Owner-Entscheidung 02.11.2026: ja)

Anlass: Der Owner wusste nicht, ob die Totmannschaltung eingerichtet war. Sie war es offenbar nicht,
und niemand hat es gemerkt.

- **Logik** als reine Funktion `TotmannschaltungStatus` (auf den Daten aus Schritt 4a):

| Zustand | Bedingung | Hinweis |
|---|---|---|
| `NICHT_EINGERICHTET` | Aufzeichnung läuft, aber keine gültige URL | „Kein Schutz bei Ausfall: Totmannschaltung nicht eingerichtet“ |
| `NICHT_GEPLANT` | URL gültig, Aufzeichnung läuft, aber der Job `heartbeat` ist nicht geplant | „Totmannschaltung eingerichtet, aber nicht aktiv. Messung neu starten.“ |
| `SENDET_NICHT` | geplant, aber letzter Erfolg älter als 45 min (Fenster der Gegenseite) | „Totmannschaltung sendet nicht (Netz?)“ |
| `OK` | alles andere | kein Hinweis |

- **Wo:** dauerhaft im Diagnose-Bildschirm, bei `NICHT_EINGERICHTET` und `NICHT_GEPLANT` zusätzlich
  als Hinweiszeile im Hauptbildschirm mit Knopf „Einrichten“, der zum Abschnitt „Totmannschaltung“
  in den Einstellungen springt. Bei laufender Aufzeichnung ist der Hinweis nicht wegwischbar, solange
  der Zustand nicht `OK` ist. Ohne Aufzeichnung zeigt die App ihn nicht.
- **Grenze, im PR sagen:** Die App erkennt nur, dass **sie** keinen Ping sendet. Ob der Dienst bei
  healthchecks.io einen Alarmkanal hat, sieht sie nicht. Der Hinweistext darf deshalb nicht „Schutz
  aktiv“ behaupten, im Zustand `OK` zeigt die App gar nichts.
- UI-lastig, gehört nach AGENTS.md §9 an Antigravity. Die Logik wird getrennt getestet (Tabelle oben,
  jeder Zustand, die 45-min-Grenze, Aufzeichnung aus).

---

## 3 · Tests (Pflicht)

1. **`AusfallKlassifikatorTest`, tabellengetrieben, mit den echten Fällen:**

| Fall | Lücke | Uptime beim Start | `BOOT_COUNT` | Akku zuletzt | erwartet |
|---|---:|---:|---|---|---|
| 19.10.→20.10. | 873 min | 369039 ms | 238 | 9 %, ohne Ladung | `AKKU_LEER` |
| 27.→28.10. | 429 min | 1502292 ms | 239 | 9 %, ohne Ladung | `AKKU_LEER` |
| 30.10.→01.11. | 2749 min | 361899222 ms | 239 → 240 | 100 %, Netzteil | `FRAMEWORK_NEUSTART` |

   Dazu: ausdrücklicher Stopp (`NUTZER_STOPP`), Prozess ohne Abschluss bei durchlaufender Uptime
   und gleichem `BOOT_COUNT` (`STILLSTAND_GERAET_LIEF`), fehlende Datei (`UNBEKANNT`), Uhrsprung um
   ±10 min und ±2 h, `BOOT_COUNT` unbekannt (`null`).
2. **`LetzterZustandDateiTest`:** atomares Überschreiben, abgeschnittene oder kaputte Datei liefert
   `null` statt Absturz, `sauberBeendet` über Start/Stopp.
3. **Wächter-Spur:** Zeitstempel wird bei jedem Ergebnis geschrieben, auch bei `LAEUFT`.
4. **Heartbeat-Planung:** URL gesetzt, Alarmierung aus, Quelle Messgerät **und** reine Mikrofon-Quelle.
5. **Bundle:** neue Dateien vorhanden, Prüfsummen stimmen, die URL kommt in **keiner** Datei vor
   (Test mit einer eindeutigen Dummy-URL und `contains`-Prüfung über alle Einträge).
6. **Migrationstests** bleiben grün (Schema unverändert).

Läuft etwas nicht ohne Gerät (`isBackgroundRestricted`, Standby-Bucket), kapsle den Systemzugriff
hinter eine kleine Schnittstelle und teste mit einem handgeschriebenen Fake. **Schreib im PR
ausdrücklich, was ohne Gerät nicht geprüft wurde.**

---

## 4 · Akzeptanzkriterien

- [ ] `./gradlew assembleDebug lintDebug test` grün, Ausgabe im PR.
- [ ] Die drei echten Fälle werden wie in der Tabelle eingeordnet.
- [ ] Nach einem simulierten Kill (Test mit gesetzter Datei, `sauberBeendet=false`) steht beim
      nächsten Start eine Einordnung samt Belegen im Lebenszyklus-Protokoll.
- [ ] `runtime.json` sagt, ob die Totmannschaltung konfiguriert und geplant ist, ohne die URL.
- [ ] Die Totmannschaltung hängt nicht mehr an der Alarmierung (oder der PR belegt, dass sie es
      nie tat).
- [ ] Die Karte erscheint nur bei Lücken ab 30 min und nicht bei `NUTZER_STOPP`/`AKKU_LEER`, fragt
      jede Lücke einmal und speichert die Antwort (Logiktest, UI gesondert).
- [ ] Der Hinweis auf Hintergrundbeschränkung erscheint bei den genannten Signalen. Im PR steht
      ausdrücklich, dass die Huawei-Einstellung selbst nicht abfragbar und der Sprung dorthin nicht
      auf einem Gerät geprüft ist.
- [ ] `TotmannschaltungStatus` liefert für jeden der vier Zustände das erwartete Ergebnis (Test),
      und der Hinweis erscheint nicht, wenn keine Aufzeichnung läuft.
- [ ] Keine Schemaänderung, keine neuen Abhängigkeiten, keine neue Kryptographie.
- [ ] README und `docs/DIAGNOSE_OBSERVABILITY_KONZEPT.md` (Abschnitt 12, Trigger-Matrix) kurz
      nachgezogen.

---

## 5 · Offene Entscheidungen — **nicht selbst entscheiden, Owner fragen (AGENTS.md §8a)**

**Entschieden am 02.11.2026:** E1 → Schritt 6 (ja, fünf Antworten inklusive „Unklar“), E4 → Schritt 7
(ja). Beides ist Teil dieses Auftrags.

**Ebenfalls entschieden am 02.11.2026:**

- **E3 · Empfänger der Totmannschaltung:** healthchecks.io (siehe Schritt 4c).
- **E2 · Pixel als Beobachter:** **entfällt.** Die Totmannschaltung deckt den Fall ohne zweites
  Gerät ab. Nicht bauen.

- **E5 · Warnhinweis „Kein Schutz bei Ausfall“:** **ja**, siehe Schritt 8.

**Zu Schritt 6 bis 8 (Karte, Hersteller-Hinweis, Warnhinweis):** Wortlaut und Aussehen der Texte
sind Platzhalter. Der Owner gibt sie im PR frei. Es ist nichts mehr offen, was vor dem Start
geklärt werden muss.

---

## 6 · Beobachtet, nicht Teil dieses Auftrags

- Der letzte automatische Support-Bundle-Upload des Huawei war am 29.10. um 18:40
  (`support_bundle_last_upload_at`), danach nicht mehr. Ob das an der Lücke lag oder weiter besteht,
  ist offen. Eigener Auftrag, falls es sich bestätigt.
- Nach jedem Neustart des Dienstes braucht die BLE-Verbindung zum PCE-323 mehrere Anläufe
  (30.10.: 6 Fehlversuche, 4 min 16 s; 01.11.: 5 Fehlversuche, 1 min 38 s). In dieser Zeit liefert
  nur das Mikrofon Pegel. Gehört zu `PROMPT_FIX_VERBINDUNGSFEHLVERSUCHE_MELDEN.md`, nicht hierher.
- Die vier Pixel-Bundles (Build 764) enthalten für den Ausfall nichts: Das Pixel misst nicht
  (`monitoring_was_active=false`, Datenbank unverändert).

---

## 7 · PR-Text

Was geändert wurde · was verifiziert wurde (Befehl + Ergebnis) · was bewusst offen blieb
(Abschnitt 5 und 6) · Abweichungen vom Plan oder von diesem Prompt · **was ohne Gerät nicht geprüft
werden konnte**. Zum Schluss ein ehrlicher Satz: Der Auftrag ordnet Ausfälle ein und belegt den
Zustand davor. Die **Ursache** eines Stillstands auf Android 10 liefert er nicht.
