# Prompt: nächste Implementierungsschritte

Zwei einsatzbereite Aufträge, jeweils für eine **eigene** Session. Beide brauchen **keine
Hardware** — sie lassen sich sofort umsetzen, während das PCE-323 noch nicht angeschlossen ist.

Reihenfolge: **A vor B.** A ist der kritische Pfad zum Bluetooth-Feature, B ist ein kleines
unabhängiges Paket.

Nicht enthalten ist **M0 (Protokoll-Discovery)** — das ist Handarbeit am Gerät (nRF Connect,
HCI-Snoop-Log) und lässt sich nicht an eine Session delegieren. M0 ist Voraussetzung für M2, nicht
für die Aufträge hier.

---

## A — M1: Fundament und Frame-Decoder

```text
Du setzt einen bereits beschlossenen Implementierungsplan um. Der Plan steht — deine Aufgabe ist
die Umsetzung, nicht die Neuplanung.

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.

ZUERST LESEN
- README.md (Statusüberblick)
- docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md, besonders Abschnitt 2 (Geräteprotokoll),
  4.2 (Paketstruktur), 4.3 (MeterTransport), 4.5 (Integrationsstrategie), 12 (Meilensteine)
- docs/PROMPT_UMSETZUNG.md Abschnitt B (Arbeitsregeln) — die gelten hier unverändert

AUSGANGSLAGE
Meilenstein M-1 ist abgeschlossen und auf main: Build läuft, Room migriert ohne Datenverlust
(inklusive des realen 4→6-Pfads), targetSdk 36. Bluetooth-Code existiert bislang KEINE einzige
Zeile.

AUFTRAG: Meilenstein M1 — Fundament legen und den Frame-Decoder bauen.

Branch: feature/m1-fundament-und-decoder

--- 1. Paketstruktur ---
Der gesamte Code liegt heute flach in einem Package. Sortiere ihn nach Zuständigkeit um,
OHNE inhaltliche Änderungen — reines Verschieben plus Import-Anpassung:

  com.example.lrmprotokoll
  ├── ui/       MainActivity, AudioPlayerScreen, SettingsScreen
  ├── audio/    AudioRecordingService, NoiseClassifier
  ├── data/     AppDatabase, NoiseDao, NoiseRecord, SettingsManager
  ├── report/   ReportManager
  └── meter/    ← neu, siehe unten

Wichtig: Die Room-Entities dürfen dabei ihre Tabellennamen NICHT verlieren. Nach dem Verschieben
muss `./gradlew test` weiterhin grün sein, inklusive der beiden Migrationstests — die sind der
Beweis, dass die Datenbank unangetastet bleibt. Die exportierte Schema-JSON heißt nach dem
Verschieben anders (der Klassenname wandert mit); prüfe, dass Room dieselbe identityHash
erzeugt, und passe den Dateinamen im schemas-Ordner entsprechend an, ohne den Inhalt zu ändern.

--- 2. AppContainer statt Hilt ---
Lege eine Application-Subklasse mit einem schlanken, manuellen AppContainer an (Datenbank,
SettingsManager, später Transport). Kein Hilt — die Begründung steht in Plan-Abschnitt 4.2.
Bestehende Direktinstanziierungen darauf umstellen.

--- 3. minSdk 29 → 31 ---
Anheben. Dadurch entfallen Legacy-Pfade; prüfe, ob im Code Build.VERSION-Abfragen für API < 31
überflüssig werden, und räume die auf, die eindeutig sind.

--- 4. MeterTransport-Abstraktion ---
Nach Plan-Abschnitt 4.3, in meter/:

  interface MeterTransport {
      val state: StateFlow<ConnectionState>
      val frames: SharedFlow<MeterFrame>
      val lastFrameAt: StateFlow<Instant?>
      suspend fun connect(device: BoundDevice)
      suspend fun disconnect()
      suspend fun send(command: MeterCommand): Result<Unit>
  }

Plus die Datentypen MeterFrame, Weighting, TimeWeighting, MeasurementRange, ConnectionState,
MeterCommand. KEINE BLE-Implementierung — die kommt in M2, dafür fehlen noch die GATT-UUIDs.

--- 5. FakeMeterTransport ---
Simulator, der plausible Frames mit einstellbarer Rate (Default 2 Hz) liefert und sich gezielt
in Fehlerzustände versetzen lässt: Verbindungsabbruch, Datenstillstand bei bestehender
Verbindung, fehlerhafte Frames. Der Fake ist die Grundlage dafür, Ausfallerkennung und
Alarmierung in M5 ohne Hardware zu testen — bau ihn entsprechend steuerbar.

--- 6. Pce323FrameDecoder — der inhaltliche Kern dieses Auftrags ---
Das Byte-Format ist bekannt (Plan-Abschnitt 2.2), nur die GATT-UUIDs sind es nicht. Der Decoder
lässt sich deshalb VOLLSTÄNDIG jetzt bauen und testen.

  Frame: 6 Byte
    [0] 0x7F Startmarker
    [1..2] Messwert, 16 Bit BIG ENDIAN  ->  dB = wert / 10.0
    [3] bit0: 0=A-Bewertung 1=C   bit1: 0=Fast 1=Slow
    [4] bit0..1: Bereich 0=30-130 1=30-80 2=50-100 3=80-130
        bit2: Max-Hold   bit3: Min-Hold
    [5] 0x00 Endmarker

Anforderungen an den Decoder:
- Arbeitet BYTEWEISE über einen Ringpuffer, nicht paketweise. BLE liefert Notifications
  fragmentiert; ein Frame kann über zwei Pakete verteilt ankommen, und zwei Frames können in
  einem Paket stecken.
- Resynchronisiert auf 0x7F … 0x00, wenn der Strom aus dem Tritt gerät.
- Verwirft unplausible Werte (außerhalb 20–140 dB) und zählt sie in einer Metrik `decodeErrors`.
- Markiert Sprünge > 40 dB zwischen aufeinanderfolgenden Frames, verwirft sie aber NICHT —
  Impulsschall ist real.

Unit-Tests mit synthetischen Byte-Vektoren, mindestens:
- sauberer Einzelframe -> korrekter dB-Wert, korrekte Flags (alle vier Bereiche, A und C,
  Fast und Slow, Hold-Kombinationen)
- zwei Frames in einem Paket
- ein Frame über zwei Pakete verteilt
- Müll vor dem ersten gültigen Frame -> Resynchronisation
- abgeschnittener Frame am Puffer-Ende -> kein Datenverlust beim nächsten Paket
- Wert außerhalb des Plausibilitätsbereichs -> verworfen, decodeErrors erhöht

NICHT TEIL VON M1
Keine BLE-Implementierung, kein Scan, keine Bluetooth-Berechtigungen im Manifest, keine
Änderung am AudioRecordingService, keine Alarmierung, kein Drive-Sync. Auch die Altbefunde
B-9/B-10/B-11 nicht anfassen.

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test laufen grün — Ausgabe zeigen, nicht behaupten
- Beide bestehenden Migrationstests weiterhin grün (Beweis, dass die Umstrukturierung die
  Datenbank nicht berührt hat)
- Decoder-Tests decken die oben genannten Fälle ab
- Die App startet und verhält sich unverändert: Mikrofon-Trigger, WAV, YAMNet, Player, Bericht
- Draft-PR gegen main, im Text: was geändert, was verifiziert (mit Befehl und Ergebnis), was
  bewusst offen

VERIFIKATION
Falls kein Android SDK vorhanden ist, lässt es sich installieren:
  sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
und per local.properties (sdk.dir=...) einbinden. Ohne ausgeführte Tests ist der Auftrag nicht
fertig — "sollte funktionieren" zählt nicht.
```

---

## B — B-11: 16-KB-Seitengröße und ein verschluckter Fehler

Kleineres, unabhängiges Paket. Ausgelöst durch eine echte Warnung auf einem Pixel:

> Diese App ist nicht mit 16 KB kompatibel. `lib/arm64-v8a/libtask_audio_jni.so`: LOAD-Segment
> stimmt nicht überein

```text
Du setzt einen abgegrenzten Fix um. Repository: arthurschaab-bit/Noiseprotocol_Android,
Branch von main: fix/b11-16kb-seitengroesse

ZUERST LESEN: README.md und docs/PROMPT_UMSETZUNG.md Abschnitt B (Arbeitsregeln).

PROBLEM 1 — 16-KB-Seitengröße
Die App hängt an org.tensorflow:tensorflow-lite-task-audio:0.4.4. Deren native Bibliothek
libtask_audio_jni.so ist nicht auf 16 KB ausgerichtet. Auf Geräten, die im 16-KB-Modus laufen
(aktuelle Pixel können das), lässt sie sich nicht laden. Die Bibliothek ist vorkompiliert — die
Ausrichtung lässt sich NICHT nachträglich korrigieren, auch nicht über useLegacyPackaging oder
zipalign. Der einzige Weg ist der Austausch der Abhängigkeit.

Das Paket ist zudem abgekündigt. Migriere auf einen gepflegten, 16-KB-ausgerichteten Nachfolger:
  - com.google.mediapipe:tasks-audio  (direkter Nachfolger für diesen Anwendungsfall), oder
  - com.google.ai.edge.litert

Betroffen ist im Wesentlichen NoiseClassifier.kt (108 Zeilen). Das YAMNet-Modell
(app/src/main/assets/yamnet.tflite) soll weiter genutzt werden. Die Klassifikations-API des
Nachfolgers ist NICHT identisch — prüfe die aktuelle Dokumentation, statt die alte Signatur zu
raten.

Verhalten, das erhalten bleiben muss:
- Label-Mapping ins Deutsche (Hammering -> Hämmern usw.)
- Abgleich mit gelernten Referenzgeräuschen aus der Tabelle reference_sounds
- Konfidenzschwelle aus SettingsManager.aiConfidenceThreshold
- Rückgabe null, wenn nichts Brauchbares erkannt wurde

PROBLEM 2 — der Fehler, der den Fehler verschluckt
NoiseClassifier fängt Ladefehler mit `catch (e: Exception)` ab. Ein fehlgeschlagenes Laden einer
nativen Bibliothek wirft aber UnsatisfiedLinkError — ein Error, kein Exception. Der Catch greift
also genau im realistischen Fall nicht, und weil NoiseClassifier in
AudioRecordingService.onCreate() gebaut wird, reißt es den Dienst mit.

Auf `catch (e: Throwable)` umstellen, mit Log-Ausgabe. Zusätzlich absichern, dass ein nicht
geladener Klassifikator die Aufzeichnung NICHT verhindert: Pegelmessung, Trigger und WAV-Aufnahme
müssen weiterlaufen, es entfällt lediglich das Label. Das ist die wichtigere Eigenschaft — ein
Lärmprotokoll ohne Klassifikation ist brauchbar, eines ohne Aufnahme nicht.

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün, Ausgabe zeigen
- Test, der belegt: schlägt die Klassifikator-Initialisierung fehl, läuft die Aufzeichnung weiter
- Prüfen, dass libtask_audio_jni.so nicht mehr im APK steckt:
    unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so"
  Ergebnis im PR nennen, ebenso die APK-Größe vorher/nachher
- Draft-PR gegen main

NICHT TEIL DIESES AUFTRAGS
Bluetooth, Alarmierung, Drive-Sync, Paketumstrukturierung, die übrigen Altbefunde.
```

---

## Danach

Nach A ist **M0 an der Reihe** — Protokoll-Discovery am realen PCE-323, siehe Plan Abschnitt 3.
Das ist Handarbeit: GATT-Tabelle mit nRF Connect dumpen, HCI-Snoop-Log der Hersteller-App in
Wireshark auswerten, Ergebnis in `Pce323Profile.kt` festschreiben. Erst danach ist M2 (BLE-Transport)
sinnvoll.

Wenn das Messgerät noch länger nicht verfügbar ist, wäre **M7b (Google-Drive-Sync)** der sinnvolle
Vorzug — er hängt nicht am Bluetooth-Pfad. Dafür müssen vorher die drei Drive-Entscheidungen aus
Plan-Abschnitt 13 geklärt sein: Aggregationsintervall, OAuth-Scope, WAV-Upload ja/nein.
