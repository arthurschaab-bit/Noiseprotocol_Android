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
| **Gerätetest** M2 + M3 am realen PCE-323 | ⬜ **als Nächstes** |
| Alarmierung bei Verbindungsabbruch (SMS + Push) | ⬜ offen |
| Google-Drive-Sync (30 min, eine Datei pro Tag) | ⬜ offen |

**Gesamtfortschritt Bluetooth-Vorhaben: 5 von 10 Meilensteinen.**

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

### Nicht vorhanden

Seit M2 vorhanden: BLE-Scan mit Geräte-Pinning, Verbindungsaufbau über eine serialisierte
`GattQueue`, CCCD-Write, Reassembly der 20 + 3 Byte, Decoder auf dem realen 23-Byte-Format,
Bluetooth-Berechtigungen und Live-Anzeige.

| | |
|---|---|
| Reconnect mit Backoff, Ausfallerkennung | → M3 |
| Verbindung im Foreground Service statt in der UI | → M3 |
| Wiederaufnahme nach Neustart | → M3 |
| Persistenz der Messreihe, Trigger-Umstellung | → M4 |

> ⚠ **Der Gerätetest zu M2 steht noch aus.** Der gesamte BLE-Pfad ist bislang nur gegen die
> 99 aufgezeichneten Frames aus M0 geprüft, nie gegen das reale Gerät.

> ⚠ **Ob der Pegel dBA ist, ist unbestätigt.** Das Protokoll liefert keine erkennbare
> Kodierung der Frequenzbewertung; die App beschriftet den Wert deshalb bewusst nur als „dB".
> Klären lässt sich das nur durch eine zweite Aufzeichnung, bei der am Gerät zwischen A und C
> umgeschaltet wird.

---

## Nächste Schritte

| # | Was | Braucht Hardware? |
|---|-----|-------------------|
| **Gerätetest** | M2 + M3 am realen Gerät, plus die zwei offenen Messfragen — Checkliste: [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **ja** |
| M4–M8 | Persistenz, Alarmierung, Sicherheit, UI, Härtung | teilweise |
| M7b | Google-Drive-Sync | nein |

Fertige Prompts für Umsetzungs-Sessions liegen in [`docs/`](docs/).

### Offene Entscheidungen

Sieben Punkte in [Plan Abschnitt 13](docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md), jeweils mit
Vorschlag — u. a. Drive-Aggregationsintervall, OAuth-Scope, ob WAV-Dateien mit hochgeladen werden.

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
| [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **Checkliste für den Gerätetest** — M2, M3 und die zwei offenen Messfragen |
| [`docs/PROTOKOLL_PCE-323.md`](docs/PROTOKOLL_PCE-323.md) | **Das reale Geräteprotokoll aus M0** — verbindliche Quelle für M2 |
| [`docs/PROTOKOLL_PCE-323_ANLEITUNG.md`](docs/PROTOKOLL_PCE-323_ANLEITUNG.md) | Schritt-für-Schritt-Anleitung für M0 (Protokoll-Discovery am realen Gerät) |
