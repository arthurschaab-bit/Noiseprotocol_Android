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
| **M2** BLE-Transport (Scan, Verbindung, Notify) | ⬜ **als Nächstes** |
| Alarmierung bei Verbindungsabbruch (SMS + Push) | ⬜ offen |
| Google-Drive-Sync (30 min, eine Datei pro Tag) | ⬜ offen |

**Gesamtfortschritt Bluetooth-Vorhaben: 3 von 10 Meilensteinen.**

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

| | |
|---|---|
| BLE-Scan, Verbindung, Bonding | → M2 |
| `GattQueue` (serialisierte Operationen) | → M2 |
| CCCD-Write und Notify-Abonnement | → M2 |
| Reassembly der 20 + 3 Byte zu einem Frame | → M2 |
| Verbindungs-Zustandsautomat, Reconnect-Backoff | → M3 |
| Bluetooth-Berechtigungen im Manifest | → M2 |
| Foreground-Service-Typ `connectedDevice` | aktuell nur `microphone` |

> ⚠ **`Pce323FrameDecoder` aus M1 passt nicht zum realen Gerät.** Er wurde gegen die
> Plan-Hypothese gebaut (6-Byte-Frame mit `0x7F`-Marker aus der PCE-322A-Familie), die M0
> widerlegt hat. Er muss in M2 auf das tatsächliche Format umgebaut werden: 23 Byte,
> float32-Messwert, Zusammensetzen aus zwei Notifications.

---

## Nächste Schritte

| # | Was | Braucht Hardware? |
|---|-----|-------------------|
| **M2** | BLE-Transport: Scan, Verbindung, `GattQueue`, CCCD-Write, Notify-Reassembly, Decoder auf das reale Format umbauen, Live-Anzeige | zum Testen ja |
| **B-11** | 16-KB-Seitengröße: `tensorflow-lite-task-audio` ablösen | nein |
| M3–M8 | Robustheit, Persistenz, Alarmierung, Sicherheit, UI, Härtung | teilweise |
| M7b | Google-Drive-Sync | nein |

Fertige Prompts für Umsetzungs-Sessions liegen in [`docs/`](docs/).

### Offene Entscheidungen

Sieben Punkte in [Plan Abschnitt 13](docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md), jeweils mit
Vorschlag — u. a. Drive-Aggregationsintervall, OAuth-Scope, ob WAV-Dateien mit hochgeladen werden.

---

## Bekannte Einschränkungen

- **Der Mikrofon-Pegelwert ist unkalibriert.** `20·log10(rms/32767) + 100` ist dBFS plus
  willkürlicher Offset, ohne A-Bewertung und geräteabhängig. Genau deshalb das PCE-323.
- **16-KB-Seitengröße** (B-11): `libtask_audio_jni.so` aus dem abgekündigten TFLite-Task-Paket ist
  nicht 16-KB-ausgerichtet. Auf Geräten im 16-KB-Modus schlägt die KI-Klassifikation fehl; der
  `catch (e: Exception)` im `NoiseClassifier` fängt den `UnsatisfiedLinkError` nicht ab.
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
| [`docs/PROMPT_M1.md`](docs/PROMPT_M1.md) | Aufträge für M1 (erledigt) und B-11 (offen) |
| [`docs/PROTOKOLL_PCE-323.md`](docs/PROTOKOLL_PCE-323.md) | **Das reale Geräteprotokoll aus M0** — verbindliche Quelle für M2 |
| [`docs/PROTOKOLL_PCE-323_ANLEITUNG.md`](docs/PROTOKOLL_PCE-323_ANLEITUNG.md) | Schritt-für-Schritt-Anleitung für M0 (Protokoll-Discovery am realen Gerät) |
