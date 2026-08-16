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
| **Bluetooth / PCE-323** | ⬜ **noch nicht begonnen** |
| Alarmierung bei Verbindungsabbruch (SMS + Push) | ⬜ offen |
| Google-Drive-Sync (30 min, eine Datei pro Tag) | ⬜ offen |

**Gesamtfortschritt Bluetooth-Vorhaben: 1 von 10 Meilensteinen.**

---

## Bluetooth-Anbindung: was da ist und was fehlt

### Vorhanden

**Protokollwissen.** Das PCE-323 ist ein OEM-Gerät von CEM und teilt sich das Handbuch mit dem
PCE-322A, dessen serielles Protokoll in libsigrok reverse-engineered wurde. Daraus bekannt und in
[Plan Abschnitt 2](docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md#2-gerätewissen-pce-323)
dokumentiert:

- 6-Byte-Messframe: Startmarker `0x7F`, Messwert als 16-Bit big endian, Flags, Endmarker `0x00`
- Dekodierung `dB = wert / 10.0`, A/C-Bewertung in `buf[3] bit0`, Fast/Slow in `bit1`
- Vollständiger 16-Bit-Kommandosatz (Connect, Weighting umschalten, Speicher auslesen …)

**Fundament.** Build läuft (AGP 9.2.1, targetSdk 36), Room migriert nachweislich ohne
Datenverlust, ein Foreground Service existiert bereits für die Mikrofonaufnahme.

**Architekturentscheidungen.** Transport-Abstraktion statt direktem BLE-Zugriff, Paketstruktur
statt Gradle-Modulschnitt, Alarmkanäle parallel statt als Fallback-Kette — alles begründet im Plan.

### Nicht vorhanden

Im Code existiert **keine einzige Zeile** zu Bluetooth. Konkret fehlt:

| | |
|---|---|
| **GATT-UUIDs des Geräts** | Nirgends dokumentiert. Nur am realen Gerät ermittelbar → **M0** |
| BLE-Scan, Verbindung, Bonding | — |
| `GattQueue` (serialisierte Operationen) | — |
| `Pce323FrameDecoder` | — |
| `MeterTransport` + Fake für Tests | — |
| Verbindungs-Zustandsautomat, Reconnect-Backoff | — |
| Bluetooth-Berechtigungen im Manifest | — |
| Foreground-Service-Typ `connectedDevice` | aktuell nur `microphone` |
| minSdk-Anhebung 29 → 31 | — |

**Der Engpass ist M0.** Ohne die am Gerät ermittelten GATT-UUIDs ist jede
Transport-Implementierung Spekulation. Der Frame-Decoder lässt sich dagegen schon jetzt bauen und
testen, weil das Byte-Format bekannt ist.

---

## Nächste Schritte

| # | Was | Braucht Hardware? |
|---|-----|-------------------|
| **M0** | Protokoll-Discovery am PCE-323 (GATT-Dump mit nRF Connect, HCI-Snoop-Log) | **ja** |
| **M1** | Paketstruktur, `AppContainer`, minSdk 31, `MeterTransport` + Fake, `Pce323FrameDecoder` | nein |
| **B-11** | 16-KB-Seitengröße: `tensorflow-lite-task-audio` ablösen | nein |
| M2–M8 | BLE-Transport, Robustheit, Persistenz, Alarmierung, Sicherheit, UI, Härtung | teilweise |
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
| [`docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md`](docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md) | Der vollständige Plan: Protokoll, Architektur, Robustheit, Sicherheit, Alarmierung, Drive-Sync, Meilensteine, Risiken |
| [`docs/PROMPT_UMSETZUNG.md`](docs/PROMPT_UMSETZUNG.md) | Prompt-Vorlage für Umsetzungs-Sessions, ein Meilenstein pro Session |
| [`docs/PROMPT_REVIEW.md`](docs/PROMPT_REVIEW.md) | Prompt für die Fortschrittskontrolle nach jedem Meilenstein |
| [`docs/PROMPT_M1.md`](docs/PROMPT_M1.md) | Ausformulierter Auftrag für den nächsten Schritt |
