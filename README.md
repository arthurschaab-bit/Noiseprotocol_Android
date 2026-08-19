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
| **Gerätetest** M2, M3 + M5 am realen PCE-323 | ⬜ **als Nächstes** |
| Google-Drive-Sync (30 min, eine Datei pro Tag) | ⬜ offen |

**Gesamtfortschritt Bluetooth-Vorhaben: 6 von 10 Meilensteinen.**

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

### Nicht vorhanden

| | |
|---|---|
| Persistenz der Messreihe, Trigger-Umstellung auf das Messgerät | → M4 |
| Verschlüsselung at rest, Geräte-Pinning härten | → M6 |
| Diagnose-Screen, Export der Messreihe | → M7 |
| Google-Drive-Sync | → M7b |

> ⚠ **Der Gerätetest steht noch aus — für M2, M3 *und* M5.** Der gesamte BLE-Pfad und die gesamte
> Robustheitslogik sind bislang nur gegen Fakes und die 99 aufgezeichneten Frames aus M0 geprüft,
> nie gegen das reale Gerät; die Alarmierung ebenso nie gegen echtes ntfy. Checkliste: [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md)

> ⚠ **Ob der Pegel dBA ist, ist unbestätigt.** Die Byte-Position der Frequenzbewertung ist seit
> der Folgeaufzeichnung bekannt, welcher Bytewert aber A und welcher C bedeutet, ist eine
> Annahme — abgebildet über `MeterFrame.modeAssumptionConfirmed`, das auf `false` steht. Die App
> beschriftet den Wert deshalb bewusst nur als „dB". Beweisen lässt sich die Zuordnung über die
> Frequenzgang-Messung in Teil B2 der Checkliste. **Bis dahin darf M4 die Frequenzbewertung nicht
> als Tatsache speichern.**

---

## Nächste Schritte

| # | Was | Braucht Hardware? |
|---|-----|-------------------|
| **Gerätetest** | M2, M3 + M5 am realen Gerät, plus die zwei offenen Messfragen — Checkliste: [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **ja** |
| M4 | Persistenz der Messreihe — Voraussetzung für M7b. Die Frequenzbewertung bleibt bis zum Gerätetest ungespeichert | nein |
| M6–M8 | Sicherheit, UI-Ausbau, Härtung | teilweise |
| M7b | Google-Drive-Sync | nein |

Fertige Prompts für Umsetzungs-Sessions liegen in [`docs/`](docs/).

### Offene Entscheidungen

**Für M5 entschieden und umgesetzt:** Entwarnung je Kanal schaltbar · Cooldown 30 min, Eskalation
nach 60 min, max. 3 Wiederholungen · Push-Kanal **ntfy** (zunächst öffentlicher Server, Basis-URL
konfigurierbar). **SMS wurde gestrichen** — `SEND_SMS` ist eine von Google eingeschränkte
Berechtigung. Damit entfällt die Absicherung gegen „Internet weg", die Plan §7.4 dem SMS-Kanal
zugedacht hatte; sie wird jetzt von der Totmannschaltung getragen: Ohne Internet bleibt auch der
Ping aus, und der Dienst auf der Gegenseite meldet sich.

**Noch offen:** drei Punkte in [Plan Abschnitt 13](docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md),
alle zu M4/M7b — Aufbewahrungsdauer und SQLCipher, Drive-Aggregationsintervall und OAuth-Scope, ob
WAV-Dateien mit hochgeladen werden.

---

## Bekannte Einschränkungen

- **Der Mikrofon-Pegelwert ist unkalibriert.** `20·log10(rms/32767) + 100` ist dBFS plus
  willkürlicher Offset, ohne A-Bewertung und geräteabhängig. Genau deshalb das PCE-323.
- **`applicationId` ist `com.example.lrmprotokoll`** (B-6). Im Play Store unzulässig, aber nach
  Veröffentlichung nie wieder änderbar — bewusst vertagt, weil eine Änderung bestehende Aufnahmen
  auf dem Gerät unerreichbar macht.
- Vier weitere Altbefunde (Ringpuffer-Synchronisation, `audioRecord.release()`, unvollständiges
  `InputStream.read`, `runBlocking`) in [`docs/PROMPT_REVIEW.md`](docs/PROMPT_REVIEW.md), Schritt 5.

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
| [`docs/TESTEN_EINES_PR.md`](docs/TESTEN_EINES_PR.md) | **Einen PR ausprobieren** — APK aus der CI, was der Emulator kann und was nicht |
| [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **Checkliste für den Gerätetest** — M2, M3, M5 und die zwei offenen Messfragen |
| [`docs/PROTOKOLL_PCE-323.md`](docs/PROTOKOLL_PCE-323.md) | **Das reale Geräteprotokoll aus M0** — verbindliche Quelle für M2 |
| [`docs/PROTOKOLL_PCE-323_ANLEITUNG.md`](docs/PROTOKOLL_PCE-323_ANLEITUNG.md) | Schritt-für-Schritt-Anleitung für M0 (Protokoll-Discovery am realen Gerät) |
