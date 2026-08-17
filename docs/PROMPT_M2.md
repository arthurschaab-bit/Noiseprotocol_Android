# Prompt: M2 — BLE-Transport zum PCE-323

Für eine eigene Session. Voraussetzungen M-1, M0 und M1 sind auf `main`.

Zum Testen wird das **reale PCE-323** gebraucht. Der Decoder-Teil ist dagegen vollständig
hardwarefrei prüfbar, weil aus M0 99 echte Frames als Fixture vorliegen.

---

```text
Du setzt Meilenstein M2 eines beschlossenen Plans um. Der Plan steht — deine Aufgabe ist die
Umsetzung, nicht die Neuplanung.

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m2-ble-transport

ZUERST LESEN, in dieser Reihenfolge
1. README.md — Statusüberblick
2. docs/PROTOKOLL_PCE-323.md — das reale Geräteprotokoll aus M0. VERBINDLICH.
3. app/src/main/java/com/example/lrmprotokoll/meter/ble/Pce323Profile.kt — dasselbe als Code
4. docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md, Abschnitte 4.3 (MeterTransport), 4.5
   (Integrationsstrategie), 5.2 (GATT-Queue), 6 (Sicherheit)
5. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert

⚠ ACHTUNG BEIM LESEN DES PLANS: Abschnitt 2.2 beschreibt ein 6-Byte-Format mit 0x7F-Marker.
Das ist eine WIDERLEGTE Hypothese und im Plan auch so markiert. Verbindlich ist allein
docs/PROTOKOLL_PCE-323.md.

AUSGANGSLAGE
Vorhanden auf main:
- meter/MeterTransport.kt — Schnittstelle + MeterFrame und Enums
- meter/FakeMeterTransport.kt — Simulator, steuerbar für Fehlerfälle
- meter/Pce323FrameDecoder.kt — gegen die WIDERLEGTE Hypothese gebaut, muss ersetzt werden
- meter/ble/Pce323Profile.kt — UUIDs und Frame-Layout aus M0
- docs/discovery/pce323_notify_frames_2026-08-17.bin — 99 echte Frames à 23 Byte (2277 Byte)
- docs/discovery/pce323_notify_frames_2026-08-17.txt — dieselben Daten mit Sollwerten
- minSdk 31, targetSdk 36, AppContainer für DI, CI-Workflow "Android CI"

Im Manifest steht bislang KEINE Bluetooth-Berechtigung, und der Foreground Service hat nur
foregroundServiceType="microphone".

DAS REALE PROTOKOLL — Kurzfassung
- Service 0000fff0, Notify 0000fff2, Write 0000fff1
- Kein CONNECT-Kommando: der Strom startet allein durch den CCCD-Write (0x2902 = 0x0100)
- Logisches Frame 23 Byte:
    [0..13]  konstanter Header D5 03 00 00 00 10 C3 00 01 01 08 00 00 00
    [14..17] Messwert IEEE-754 float32 BIG ENDIAN, Wert direkt in dB
    [18..19] konstanter Footer 01 0F
    [20..22] konstanter Trailer 2C 00 0D
- Bei Default-MTU 23 kommt das Frame als ZWEI Notifications an: 20 Byte + 3 Byte
- Frame-Rate rund 515 ms (1,9–2,0 Hz)
- KEIN BONDING


=== AUFGABE 1: Decoder ersetzen (hardwarefrei) ===

Pce323FrameDecoder.kt komplett auf das reale Format umbauen.

- Arbeitet BYTEWEISE über einen Ringpuffer, nicht paketweise. Der Sync-Anker ist der 14 Byte
  lange konstante Header — such danach, statt auf ein einzelnes Markerbyte zu vertrauen.
  Das macht die Resynchronisation robuster, als sie beim alten Format je war.
- Messwert: 4 Byte ab Offset 14 als float32 big endian lesen (ByteBuffer mit BIG_ENDIAN oder
  Float.fromBits mit manuell zusammengesetztem Int).
- Footer und Trailer gegen die Konstanten prüfen. Abweichung ⇒ Frame verwerfen und
  decodeErrors erhöhen. Das ist zugleich die Spoofing-Plausibilisierung aus Plan Abschnitt 6:
  20 von 23 Byte sind konstant, ein fremder Sender müsste das exakt nachbilden.
- Plausibilitätsfilter: Werte außerhalb 20–140 dB verwerfen und zählen. NaN und Infinity
  ebenfalls — float32 aus einem gestörten Funkstrom kann beides liefern.
- Sprünge > 40 dB zwischen aufeinanderfolgenden Frames markieren, aber NICHT verwerfen
  (Impulsschall ist real). Dafür gibt es largeJump in MeterFrame.

Tests gegen die echten Fixture-Daten, nicht gegen erfundene Bytes:
- Alle 99 Frames aus der .bin dekodieren, Sollwerte aus der .txt vergleichen
- Dieselben 99 Frames als 20+3-Byte-Paare einspeisen ⇒ identisches Ergebnis (Reassembly)
- Alle 2277 Byte am Stück in EINEM Aufruf ⇒ 99 Frames
- Byteweise Einzelfütterung ⇒ 99 Frames
- Müll vor dem ersten Frame ⇒ Resynchronisation, kein Datenverlust
- Verfälschtes Footer-Byte ⇒ verworfen, decodeErrors == 1
- Messwert-Bytes auf 0x7F800000 (Infinity) gesetzt ⇒ verworfen

=== AUFGABE 2: Das MeterFrame-Problem lösen — bitte zuerst lesen ===

MeterFrame verlangt heute weighting, timeWeighting, range, holdMax, holdMin. Diese Felder
stammen aus der widerlegten Hypothese. Das reale Protokoll liefert KEINES davon: Alle
konstanten Bytes waren in 99/99 Frames identisch, ihre Bedeutung ist ungeklärt.

Du darfst diese Werte NICHT erfinden und NICHT defaulten. Insbesondere darf der Pegel NICHT
als dBA ausgegeben oder benannt werden — ob das Gerät A-, C- oder ungewichtet sendet, ist
unbekannt (docs/PROTOKOLL_PCE-323.md Abschnitt 3 und 7).

Das ist wichtig, weil der gesamte Zweck des Vorhabens die Protokollierung kalibrierter
dBA-Werte ist. Ein Wert, der fälschlich als dBA beschriftet wird, ist schlimmer als gar
keiner.

Setze MeterFrame so um, dass Unbekanntes als unbekannt sichtbar ist — z. B. die Enums
nullable machen oder je ein UNKNOWN-Element ergänzen. Entscheide dich für eine Variante,
begründe sie kurz im PR, und sorge dafür, dass die UI "Bewertung unbekannt" anzeigt statt
stillschweigend "dBA". FakeMeterTransport entsprechend nachziehen.

=== AUFGABE 3: BleMeterTransport ===

Neue Klasse meter/ble/BleMeterTransport.kt, implementiert MeterTransport.

Ablauf: Scan → Connect → discoverServices → CCCD-Write auf 0000fff2 → Frames fließen.

- Scan über BluetoothLeScanner. Gerätename ist "PCE-323". Ob der Service 0000fff0 im
  Advertisement steht, ist unbekannt — filtere nicht blind darauf, sonst findest du nichts.
  Sicherer: Ergebnisse anzeigen, Nutzer wählt, danach MAC persistieren und ausschließlich
  noch zu dieser Adresse verbinden (Geräte-Pinning, Plan Abschnitt 6).
- GATT-Operationsqueue: IMMER nur eine GATT-Operation gleichzeitig, jede wartet auf ihren
  Callback, Timeout 10 s. Parallele Aufrufe verwirft der Android-Stack stillschweigend —
  das ist die häufigste Fehlerquelle in BLE-Code. Plan Abschnitt 5.2 zeigt das Muster.
- gatt.close() vor jedem neuen Verbindungsversuch, nicht nur disconnect(). Sonst leckt der
  Client-Interface-Slot und der Stack liefert nach einigen Zyklen nur noch status 133.
- ⚠ createBond() NICHT aufrufen. In M0 führte der Versuch zu sofortigem Disconnect
  (status 19) und AUTH_FAILED. Das Modul kann kein Pairing. Ein "Sicherheit verbessern"-
  Reflex macht die Verbindung hier kaputt.
- MTU: requestMtu(64) versuchen. Gelingt es, kommt das Frame womöglich in einer einzigen
  Notification. Der Decoder muss BEIDE Fälle abdecken — verlasse dich nicht darauf.
- STREAMING wird erst gemeldet, wenn mindestens ein valides Frame dekodiert wurde, nicht
  schon bei onServicesDiscovered. Eine stehende Verbindung ohne Daten ist ein Ausfall.
- lastFrameAt bei jedem validen Frame setzen — M3 baut darauf die Staleness-Erkennung.

send(command) bleibt vorerst eine Stub-Implementierung, die einen Fehler zurückgibt: Der
Kommandokanal 0000fff1 wurde in M0 nie benutzt, es ist kein einziges Kommando bekannt. Nicht
raten.

=== AUFGABE 4: Manifest und Berechtigungen ===

- BLUETOOTH_SCAN mit android:usesPermissionFlags="neverForLocation" und BLUETOOTH_CONNECT.
  Wegen minSdk 31 sind keine Legacy-Berechtigungen und kein ACCESS_FINE_LOCATION nötig.
- FOREGROUND_SERVICE_CONNECTED_DEVICE ergänzen.
- foregroundServiceType="microphone|connectedDevice" am Service, UND beim startForeground()-
  Aufruf in AudioRecordingService beide Typen per or verknüpfen. Der Code setzt aktuell nur
  FOREGROUND_SERVICE_TYPE_MICROPHONE.
- Laufzeitabfrage der Bluetooth-Berechtigungen im bestehenden Permission-Flow ergänzen.

=== AUFGABE 5: Minimale UI ===

- Kopplungs-Screen: Scan starten, gefundene Geräte mit Name und Signalstärke, Auswahl
  persistiert die MAC.
- Live-Anzeige: aktueller Pegel groß, Verbindungszustand als Text UND Icon (nicht nur
  farblich), sichtbarer Hinweis "Frequenzbewertung unbekannt".
- Reine Anzeige. Keine Persistenz, keine Verknüpfung mit dem Aufnahme-Trigger — das ist M4.

NICHT TEIL VON M2
Zustandsautomat mit Reconnect-Backoff und Adapter-Beobachtung (M3), Speichern der Messreihe
und Trigger-Umbau im AudioRecordingService (M4), Alarmierung (M5), Verschlüsselung (M6),
Drive-Sync (M7b), B-11. Auch keine Kommandos an das Gerät senden.

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün — Ausgabe im PR zeigen, nicht behaupten.
  Fehlt ein Android SDK, lässt es sich per sdkmanager installieren
  ("platforms;android-36" "build-tools;36.0.0") und über local.properties einbinden.
- Decoder-Tests laufen gegen die 99 echten Frames, inklusive des 20+3-Reassembly-Falls
- Am realen PCE-323 verifiziert: Verbindung steht, Werte laufen, angezeigter Wert stimmt mit
  dem Display des Messgeräts überein
- Draft-PR gegen main mit: was geändert, was verifiziert (Befehl und Ergebnis), welche
  MeterFrame-Variante gewählt wurde und warum, was offen blieb

ZUSÄTZLICH, wenn das Gerät zur Hand ist — zwei Messungen, die viel wert sind:

(a) Stört die Funkverbindung die Messung? Pegel am Gerätedisplay ohne Bluetooth ablesen,
    dann verbinden und im selben ruhigen Raum erneut ablesen. Bei einem vergleichbar
    aufgebauten Fremdgerät sind ~15 dB Abweichung dokumentiert (Plan Abschnitt 13). Weicht
    es ab, im PR festhalten — der Fehler wäre sonst unentdeckbar, weil die Werte plausibel
    aussehen.

(b) Welches Byte kodiert die Bewertung? Eine zweite Aufzeichnung machen und dabei am Gerät
    A/C und Fast/Slow umschalten, dann prüfen, welches der 19 konstanten Bytes reagiert.
    Das ist der einzige Weg, die dBA-Frage zu beantworten. Ergebnis in
    docs/PROTOKOLL_PCE-323.md und Pce323Profile.kt nachtragen.

Beides ist kein Blocker für M2, aber (b) blockiert später die Aussagekraft des gesamten
Protokolls.
```

---

## Warum die Reihenfolge so ist

Aufgabe 1 und 2 sind **hardwarefrei** und liefern die Grundlage: Ohne korrekten Decoder ist
der Transport nicht testbar. Aufgabe 3 braucht das Gerät erst zum Verifizieren, nicht zum
Schreiben — der `FakeMeterTransport` deckt die Zustandslogik ab.

Aufgabe 2 steht bewusst vor dem Transport: Wird `MeterFrame` erst nachträglich angefasst,
zieht sich die Änderung durch UI, Tests und Fake. Und die Entscheidung „Unbekanntes bleibt
sichtbar unbekannt" ist die einzige, die verhindert, dass am Ende ein womöglich C-bewerteter
Wert als dBA im Protokoll landet.
