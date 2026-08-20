# Lärmprotokoll

Android-App zur Dokumentation von Lärmereignissen. Zeichnet bei Überschreiten einer Pegelschwelle
automatisch einen Audioschnitt mit Vorlauf auf, klassifiziert das Geräusch per KI und legt alles
als durchsuchbares Protokoll ab.

**In Arbeit:** Anbindung eines externen Schallpegelmessgeräts **PCE-323** über Bluetooth, um
kalibrierte dBA-Werte statt unkalibrierter Mikrofonwerte zu protokollieren.

---

## Status

| Bereich | Stand |
|---------|-------|
| Aufnahme über Mikrofon, Pre-Roll, WAV | ✅ läuft |
| KI-Klassifikation (YAMNet) | ✅ läuft |
| Wellenform-Player, Tagesbericht, CSV/ZIP-Export | ✅ läuft |
| **M-1** Bestand instandsetzen | ✅ abgeschlossen |
| **M0** Protokoll-Discovery am PCE-323 | ✅ abgeschlossen |
| **M1** Fundament, `MeterTransport`, Decoder | ✅ abgeschlossen |
| **M2** BLE-Transport (Scan, Verbindung, Notify) | ✅ abgeschlossen, Gerätetest offen |
| **M3** Robustheit (Reconnect, Ausfallerkennung) | ✅ abgeschlossen, Gerätetest offen |
| **M5** Alarmierung bei Verbindungsabbruch (ntfy + Totmannschaltung) | ✅ abgeschlossen, Gerätetest offen |
| **M7b** Google-Drive-Sync (Pegel-Upload, eine Datei pro Tag) | ✅ Code abgeschlossen, Google-Anmeldung braucht noch eine echte Client-ID + Gerätetest |
| **M4** Persistenz der Messreihe (Sessions, Verbindungsereignisse, Kennwerte, Retention) | ✅ abgeschlossen, Gerätetest offen |
| **M6** Sicherheit (Pinning-Härtung, Kadenz-Watcher, verschlüsselte Ablage, Diagnose-Log) | ✅ abgeschlossen, Gerätetest offen |
| **M7** UI-Ausbau (Protokollansicht, Diagnose-Screen, CSV/PDF-Export) | ✅ abgeschlossen, Gerätetest offen |
| **Gerätetest** M2, M3, M4, M5, M6, M7 + M7b am realen Gerät | ⬜ **als Nächstes** |

**Gesamtfortschritt Bluetooth-Vorhaben: 10 von 10 Meilensteinen.** (M8 Härtung ist im Plan
zusätzlich vorgesehen, siehe „Nächste Schritte".)

---

## Bluetooth-Anbindung: was da ist und was fehlt

### Vorhanden

**Das Protokoll ist am realen Gerät ermittelt** (M0) und in
[`docs/PROTOKOLL_PCE-323.md`](docs/PROTOKOLL_PCE-323.md) sowie im Code als
`meter/ble/Pce323Profile.kt` festgeschrieben:

- BLE-Modul **Lierda LSD4BTC**, Custom-Service `0000fff0`
- Notify auf `0000fff2`, Write auf `0000fff1` — **kein CONNECT-Kommando nötig**, der Strom läuft
  nach dem CCCD-Write von allein
- Logisches Frame 23 Byte, wegen Default-MTU auf zwei Notifications (20 + 3 Byte) aufgeteilt
- Messwert als **IEEE-754-float32 big endian** in dB, Intervall rund 515 ms

**Fundament und Abstraktion** (M-1, M1): Build läuft, Room migriert nachweislich ohne
Datenverlust, Paketstruktur nach Zuständigkeit, `AppContainer`, `MeterTransport` mit
`FakeMeterTransport` für hardwarefreie Tests.

**Aus M2:** BLE-Scan mit Geräte-Pinning, Verbindungsaufbau über eine serialisierte `GattQueue`,
CCCD-Write, Reassembly der 20 + 3 Byte, Decoder auf dem realen 23-Byte-Format,
Bluetooth-Berechtigungen und Live-Anzeige.

**Aus M3:** Reconnect mit Backoff und Jitter, vier unabhängige Ausfallsignale (Abbruch,
Datenstillstand, Adapter aus, Fehlerrate), Verbindung im Foreground Service statt in der UI,
Wiederaufnahme nach Neustart.

**Aus M5:** Alarm bei Verbindungsabbruch nach 60 s Karenzzeit, Push über **ntfy** auf ein zweites
Gerät und Meldung auf dem Gerät selbst, beide parallel; Cooldown, Eskalation und Entwarnung;
Alarmzustand in Room, damit ein Prozess-Tod während der Karenzzeit den Alarm nicht verschluckt;
**Totmannschaltung** über eine Ping-URL; Probealarm je Kanal in den Einstellungen.

**Aus M7b:** Pegelwerte aus Mikrofon *und* PCE-323 werden gepuffert, zu Zeitfenstern verdichtet
(LAeq als energetischer Mittelwert, Lücken bleiben als Lücken sichtbar) und alle 30 Minuten als
CSV in einen selbst gewählten Drive-Ordner hochgeladen — eine Datei pro Tag, aktualisiert statt
dupliziert, mit Dedup-Absicherung gegen Waisen. Läuft bewusst auch **ohne** gepinntes PCE-323
allein mit Mikrofonwerten. Aufzeichnungsgenauigkeit, WLAN-only und WAV-Upload sind in den
Einstellungen konfigurierbar.

**Aus M4:** Jede Überwachungsperiode wird als `SessionEntity` festgehalten, Messwerte batchweise
(alle 5 s oder 50 Werte) als `MeasurementEntity` weggeschrieben, Verbindungsausfälle als
`ConnectionEventEntity` — eine zusammenhängende Ausfallperiode erzeugt genau eine Zeile, nicht
eine je Zwischenzustand. Der Auslöse-Trigger schaltet automatisch auf das Messgerät um, sobald
eines verbunden ist (eigener Schwellwert, weil „60" bei Mikrofon und Messgerät nichts
Vergleichbares bedeutet), und fällt sonst auf den Mikrofonwert zurück. Kennwerte (LAeq
energetisch, Max/Min, L10/L50/L90, Überschreitungsdauer) lassen sich über `AkustischeKennwerte`
abfragen. Ein täglicher Retention-Job verdichtet Rohwerte älter als 90 Tage zu Minutenaggregaten
(Owner-Entscheidung, Plan 13.2) — erst schreiben, dann löschen, damit ein Abbruch dazwischen nie
Daten verliert. Die A/C-Frequenzbewertung bleibt dabei durchgehend `null`, bis der Gerätetest sie
bestätigt (siehe Warnhinweis unten).

**Aus M6:** Bonding scheitert an diesem Gerät nachweislich (M0-Fund: `createBond()` bricht die
Verbindung sofort ab) — statt es wiederholt zu versuchen, kennzeichnet die Live-Anzeige die
Verbindung jetzt ehrlich als unverschlüsselt. Geräte-Pinning gehärtet: ein Advertiser mit
demselben Namen wie das gekoppelte Gerät, aber anderer Adresse, wird nicht mehr kommentarlos
angeboten, sondern gewarnt, geloggt und nur nach Bestätigung akzeptiert (`GeraetePinning`).
Stream-Plausibilisierung als Spoofing-Erkennung: ein neuer Kadenz-Watcher in
`ConnectionSupervisor` trennt die Verbindung, wenn die Frame-Rate wiederholt mehr als ±20% vom
erwarteten Intervall abweicht. Die ntfy-Konfiguration (Topic, Server, Heartbeat-URL) liegt jetzt
in `EncryptedSharedPreferences` (Android Keystore) statt im Klartext, mit automatischer Migration
bestehender Werte. Ein neues, standardmäßig **ausgeschaltetes** Diagnose-Log (7-Tage-Löschung)
zeichnet technische Ereignisse auf (Datenstillstand, Fehlerrate, Kadenz-Auffälligkeiten,
gescheiterte Verbindungsversuche) — die Anzeige dafür ist M7. SQLCipher bleibt **bewusst aus**
(Owner-Entscheidung, siehe M4/Plan 13.2).

**Aus M7:** Protokollansicht (`ProtokollScreen`/`ProtokollDetailScreen`) zeigt alle Sessions,
Kennwerte je Session (aus Rohwerten oder, falls der Retention-Job sie bereits verdichtet hat, aus
Minutenaggregaten) und Verbindungsausfälle als rote Karten im Verlauf. Diagnose-Screen
(`DiagnoseScreen`) zeigt den Verbindungszustand live, einen aus den Verbindungsereignissen
abgeleiteten Reconnect-Zähler, die Decode-Fehlerrate, das Diagnose-Log (M6) und die
Drive-Sync-Historie. Export der Messreihe als CSV (`MessreiheCsv`, dieselbe Konvention wie
`DriveCsv` aus M7b) und als PDF-Textbericht (`MessreiheExport`, `android.graphics.pdf.PdfDocument`
aus dem SDK, kein neuer Dependency), geteilt über denselben FileProvider-Weg wie der bestehende
Tagesbericht. Einstellungen waren bereits konsolidiert (durchgängig betitelte Abschnitte) —
keine Änderung nötig.

### Nicht vorhanden

Nichts mehr aus dem ursprünglichen Plan-Umfang (M-1 bis M7 sowie M7b) — offen sind nur noch der
Gerätetest (siehe unten) und M8 (Härtung, Plan Abschnitt 12).

> ⚠ **Der Gerätetest steht noch aus — für M2, M3, M4, M5, M6, M7 *und* M7b.** Der gesamte BLE-Pfad und die
> gesamte Robustheitslogik sind bislang nur gegen Fakes und die 99 aufgezeichneten Frames aus M0
> geprüft, nie gegen das reale Gerät; die Alarmierung ebenso nie gegen echtes ntfy, der Drive-Sync
> nie gegen den echten Drive-Server (kein Netzzugang zu googleapis.com in der Entwicklungsumgebung)
> und die Google-Anmeldung braucht zusätzlich eine echte OAuth-Client-ID, die nur der Kontoinhaber
> über die Google Cloud Console anlegen kann (siehe `GoogleClientConfig`-KDoc). Checkliste: [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md)

> ⚠ **Ob der Pegel dBA ist, ist unbestätigt.** Die Byte-Position der Frequenzbewertung ist seit
> der Folgeaufzeichnung bekannt, welcher Bytewert aber A und welcher C bedeutet, ist eine
> Annahme — abgebildet über `MeterFrame.modeAssumptionConfirmed`, das auf `false` steht. Die App
> beschriftet den Wert deshalb bewusst nur als „dB". Beweisen lässt sich die Zuordnung über die
> Frequenzgang-Messung in Teil B2 der Checkliste. **Bis dahin speichert M4 die Frequenzbewertung
> konsequent als `null`**, sowohl in `MeasurementEntity`/`MinuteAggregateEntity` als auch in
> `NoiseRecord.meterWeighting`.

---

## Nächste Schritte

| # | Was | Braucht Hardware? |
|---|-----|-------------------|
| **Google Cloud Console** | Echte OAuth-Client-ID für den Drive-Sync anlegen (nur der Kontoinhaber kann das) — Anleitung in `GoogleClientConfig` | nein, aber ein Google-Konto im Browser |
| **Gerätetest** | M2, M3, M4, M5, M6, M7 + M7b am realen Gerät, plus die zwei offenen Messfragen — Checkliste: [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **ja** |
| M8 | Härtung (Plan Abschnitt 12) | teilweise |

Fertige Prompts für Umsetzungs-Sessions liegen in [`docs/`](docs/).

### Offene Entscheidungen

**Für M5 entschieden und umgesetzt:** Entwarnung je Kanal schaltbar · Cooldown 30 min, Eskalation
nach 60 min, max. 3 Wiederholungen · Push-Kanal **ntfy** (zunächst öffentlicher Server, Basis-URL
konfigurierbar). **SMS wurde gestrichen** — `SEND_SMS` ist eine von Google eingeschränkte
Berechtigung. Damit entfällt die Absicherung gegen „Internet weg", die Plan §7.4 dem SMS-Kanal
zugedacht hatte; sie wird jetzt von der Totmannschaltung getragen: Ohne Internet bleibt auch der
Ping aus, und der Dienst auf der Gegenseite meldet sich.

**Für M7b entschieden und umgesetzt:** Aufzeichnungsgenauigkeit **konfigurierbar, Default so fein
wie technisch sinnvoll (1 s)** statt der im Plan vorgeschlagenen 10 s — dafür WLAN-only per
Default an, um das dadurch höhere Uploadvolumen abzufangen. OAuth-Scope **`drive.file`** (App legt
eigenen Ordner an, keine Google-Verifizierung nötig). WAV-Upload **als Option vorhanden, Default
aus** — Owner-Entscheidung, abweichend vom Plan-Vorschlag „nein", der WAVs komplett ausschließt.

**Für M4 entschieden und umgesetzt:** Aufbewahrungsdauer der Rohmesswerte **90 Tage**, wie im Plan
vorgeschlagen, danach Verdichtung zu Minutenaggregaten. **SQLCipher explizit gestrichen** —
Owner-Entscheidung, abweichend vom Plan-Vorschlag: die App-Sandbox von Android schützt bereits
gegen andere Apps, der Aufwand beim Öffnen/Migrieren stünde dazu nicht im Verhältnis. Die
Datenbank bleibt unverschlüsselt; M6 setzt stattdessen nur EncryptedSharedPreferences für
Alarmkonfiguration/Rufnummern um.

**Für M6 umgesetzt, keine Owner-Entscheidung nötig:** Bonding wird nicht erneut versucht (M0 hat
den Fehlschlag bereits mit Beweis belegt, `createBond()` bricht die Verbindung ab) — die Konsequenz
aus dem Plan (ehrliche UI-Kennzeichnung statt Sicherheit vorzutäuschen) greift direkt, ohne dass
das noch einmal am Gerät ausprobiert werden musste. Das Diagnose-Log protokolliert bewusst keine
rohen Frame-Bytes, sondern die von `ConnectionSupervisor` ohnehin erkannten Ereignisse
(Datenstillstand, Fehlerrate, Kadenz, gescheiterte Verbindungsversuche) — ein Rohframe-Capture
hätte noch keinen Konsumenten, der Diagnose-Screen dafür ist M7.

**Für M7 umgesetzt, keine Owner-Entscheidung nötig:** Export-CSV folgt derselben Konvention wie
`DriveCsv` aus M7b (Semikolon, Dezimalkomma, UTF-8-BOM, CRLF, `_dB` statt `_dBA`) statt einer
eigenen Formatierung — Konsistenz zwischen den beiden Export-Wegen der App. Das PDF ist bewusst
ein reiner Textbericht ohne Diagramm/Grafik und ohne neuen Bibliotheks-Dependency
(`android.graphics.pdf.PdfDocument` aus dem SDK) — passend zum durchgängig minimalen
Abhängigkeits-Stil des Projekts.

---

## Bekannte Einschränkungen

- **Der Mikrofon-Pegelwert ist unkalibriert.** `20·log10(rms/32767) + 100` ist dBFS plus
  willkürlicher Offset, ohne A-Bewertung und geräteabhängig. Genau deshalb das PCE-323.
- **`applicationId` ist `com.example.lrmprotokoll`** (B-6). Im Play Store unzulässig, aber nach
  Veröffentlichung nie wieder änderbar — bewusst vertagt, weil eine Änderung bestehende Aufnahmen
  auf dem Gerät unerreichbar macht.
- Vier weitere Altbefunde (Ringpuffer-Synchronisation, `audioRecord.release()`, unvollständiges
  `InputStream.read`, `runBlocking`) in [`docs/PROMPT_REVIEW.md`](docs/PROMPT_REVIEW.md), Schritt 5.
- **`MeasurementRecorder` flusht nicht bei `onTrimMemory`.** Plan 8.2 nennt das als
  zusätzliche Absicherung neben dem 5-s/50-Werte-Intervall; bewusst nicht umgesetzt (siehe
  `docs/PROMPT_M4.md`) — im ungünstigsten Fall (Speicherdruck kurz vor Prozess-Kill) gehen bis
  zu 5 s bzw. 50 Messwerte verloren.
- **Ob `EncryptedSharedPreferences` auf einem echten Gerät tatsächlich verschlüsselt, ist nicht
  durch Unit-Tests belegt.** Robolectric/die JVM stellen den Provider „AndroidKeyStore"
  grundsätzlich nicht bereit (`KeyStoreException: AndroidKeyStore not found`, selbst geprüft) -
  jeder Test, der `SettingsManager` unverändert konstruiert, läuft real über den dokumentierten
  Klartext-Fallback. Die Migrations-/Fallback-*Logik* ist gegen ein injiziertes Test-Double
  geprüft, die tatsächliche Verschlüsselung nur am Gerät (siehe `docs/PROMPT_M6.md`).
- **Der PDF-Export ist nicht durch Unit-Tests belegt.** `android.graphics.pdf.PdfDocument()`
  wirft unter Robolectric bei jedem `startPage()`-Aufruf `IllegalStateException: document is
  closed!`, reproduzierbar auch isoliert — ein verifiziertes Robolectric-Limit, keine Aussage
  über die Korrektheit von `MessreiheExport.exportierePdf`. Nur durch `assembleDebug` verifiziert,
  Inhalt und Layout müssen am echten Gerät geprüft werden (siehe `docs/PROMPT_M7.md`).
- **Die neuen Compose-Screens aus M7 (Protokoll, Diagnose) sind mangels Emulator in dieser
  Entwicklungsumgebung nur kompiliert, nicht visuell geprüft.**

---

## Entwicklung

```bash
./gradlew assembleDebug      # bauen
./gradlew test               # Unit-Tests inkl. Migrationstests
./gradlew installDebug       # auf verbundenes Gerät installieren
```

Fehlt `JAVA_HOME`, auf das JBR von Android Studio zeigen:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

**Vor App-Updates mit gewachsener Datenbank:** Die Datenbank sichern, bevor eine neue Version
installiert wird. Room migriert seit M-1 statt zu löschen — geht dabei etwas schief, ist das
Backup die einzige Rückfalloption.

```bat
adb exec-out run-as com.example.lrmprotokoll cat databases/noise_database > backup.db
```

---

## Dokumentation

| Datei | Inhalt |
|-------|--------|
| [`AGENTS.md`](AGENTS.md) | Arbeitsregeln für Coding-Agents (Claude Code, Codex, Antigravity/Gemini): Branches, Commits, Verifikation, Zuständigkeiten |
| [`docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md`](docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md) | Der vollständige Plan: Protokoll, Architektur, Robustheit, Sicherheit, Alarmierung, Drive-Sync, Meilensteine, Risiken |
| [`docs/PROMPT_UMSETZUNG.md`](docs/PROMPT_UMSETZUNG.md) | Prompt-Vorlage für Umsetzungs-Sessions, ein Meilenstein pro Session |
| [`docs/PROMPT_REVIEW.md`](docs/PROMPT_REVIEW.md) | Prompt für die Fortschrittskontrolle nach jedem Meilenstein |
| [`docs/PROMPT_M1.md`](docs/PROMPT_M1.md) | Auftrag für M1 (erledigt) |
| [`docs/PROMPT_M2.md`](docs/PROMPT_M2.md) | Auftrag für M2 (erledigt) — BLE-Transport, Decoder-Umbau, Kopplung |
| [`docs/PROMPT_M3.md`](docs/PROMPT_M3.md) | Auftrag für M3 (erledigt) — Reconnect, Ausfallerkennung, Foreground Service |
| [`docs/PROMPT_B11.md`](docs/PROMPT_B11.md) | Auftrag für B-11 (erledigt) — 16-KB-Seitengröße, TFLite-Ablösung |
| [`docs/PROMPT_M5.md`](docs/PROMPT_M5.md) | Auftrag für M5 (erledigt) — Alarmierung, Karenzzeit, ntfy, Totmannschaltung |
| [`docs/PROMPT_M7B.md`](docs/PROMPT_M7B.md) | Auftrag für M7b (erledigt) — Drive-Sync, Aggregation, Google-Anmeldung |
| [`docs/PROMPT_M4.md`](docs/PROMPT_M4.md) | Auftrag für M4 (erledigt) — Persistenz, Trigger-Umstellung, Kennwerte, Retention |
| [`docs/PROMPT_M6.md`](docs/PROMPT_M6.md) | Auftrag für M6 (erledigt) — Bonding-Kennzeichnung, Geräte-Pinning, Kadenz-Watcher, verschlüsselte Ablage, Diagnose-Log |
| [`docs/PROMPT_M7.md`](docs/PROMPT_M7.md) | Auftrag für M7 (erledigt) — Protokollansicht, Diagnose-Screen, CSV/PDF-Export |
| [`docs/BESTANDSAUFNAHME_UI.md`](docs/BESTANDSAUFNAHME_UI.md) | Bestandsaufnahme der App-UI nach dem ersten Gerätetest — Screen-Inventar, Live-Status-Lücken, fehlende Chart-Infrastruktur, Verbesserungsvorschläge |
| [`docs/PROMPT_M7C.md`](docs/PROMPT_M7C.md) | Auftrag für M7c (offen) — Live-Status-Dashboard, Aufzeichnungs-Chart, Navigationsstruktur, Scroll-Fix `MeterScreen` |
| [`docs/TESTEN_EINES_PR.md`](docs/TESTEN_EINES_PR.md) | **Einen PR ausprobieren** — APK aus der CI, was der Emulator kann und was nicht |
| [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **Checkliste für den Gerätetest** — M2, M3, M5 und die zwei offenen Messfragen |
| [`docs/PROTOKOLL_PCE-323.md`](docs/PROTOKOLL_PCE-323.md) | **Das reale Geräteprotokoll aus M0** — verbindliche Quelle für M2 |
| [`docs/PROTOKOLL_PCE-323_ANLEITUNG.md`](docs/PROTOKOLL_PCE-323_ANLEITUNG.md) | Schritt-für-Schritt-Anleitung für M0 (Protokoll-Discovery am realen Gerät) |
