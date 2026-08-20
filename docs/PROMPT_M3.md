# Prompt: M3 — Robuste Verbindungsführung

Für eine eigene Session. Voraussetzung: M-1, M0, M1 und M2 sind auf `main`.

**Vollständig ohne Hardware entwickelbar und testbar.** Der `FakeMeterTransport` kann bereits
Verbindungsabbruch, Datenstillstand bei stehender Verbindung und fehlerhafte Frames simulieren —
genau die drei Fälle, um die es in M3 geht. Hardware braucht erst die Endabnahme.

---

```text
Du setzt Meilenstein M3 eines beschlossenen Plans um. Der Plan steht — deine Aufgabe ist die
Umsetzung, nicht die Neuplanung.

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m3-robustheit

ZUERST LESEN
1. README.md — Statusüberblick
2. docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md, **Abschnitt 5 vollständig** — das ist der
   Inhalt dieses Meilensteins. Dazu 4.5 (Integrationsstrategie) und 7.1 (Ausfallsignale)
3. docs/PROTOKOLL_PCE-323.md — Geräteverhalten, insbesondere Frame-Rate ~515 ms
4. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert

⚠ Plan Abschnitt 2.2 beschreibt ein widerlegtes Frame-Format und ist als überholt markiert.
Verbindlich ist docs/PROTOKOLL_PCE-323.md.

AUSGANGSLAGE
Auf main vorhanden:
- meter/MeterTransport.kt — Schnittstelle, MeterFrame (weighting etc. sind bewusst nullable)
- meter/ConnectionState.kt — die Zustände sind bereits definiert, aber nur teilweise benutzt:
  SCANNING, DEGRADED und RECONNECTING vergibt heute niemand
- meter/ble/BleMeterTransport.kt — Verbindungsaufbau bis STREAMING, kein Reconnect
- meter/ble/GattQueue.kt — serialisiert GATT-Operationen, sperrt nach einem Timeout bis reset()
- meter/FakeMeterTransport.kt — mit simulateConnectionLoss(), simulateStall(Boolean),
  simulateCorruptFrames(Boolean)
- meter/Pce323FrameDecoder.kt — mit reset() und Zähler decodeErrors
- audio/AudioRecordingService.kt — Foreground Service, Typ microphone|connectedDevice
- ui/MeterScreen.kt — treibt die Verbindung derzeit aus der UI heraus

=== AUFGABE 1: ConnectionSupervisor ===

Neue Klasse meter/ConnectionSupervisor.kt. Sie kennt nur die MeterTransport-Schnittstelle,
NICHT die BLE-Implementierung — nur so bleibt sie gegen den Fake testbar.

Zuständig für den Zustandsautomaten aus Plan 5.1 und die Reconnect-Strategie aus 5.3:

- Exponentielles Backoff mit Jitter: 1, 2, 4, 8, 16, 30 s, danach konstant 60 s, Jitter ±20 %.
  Der Jitter ist kein Detail: Ohne ihn synchronisieren sich Wiederholungen nach einem
  Bluetooth-Neustart.
- Vor jedem neuen Versuch trennen und schließen (der Transport tut das in connect() bereits,
  verlass dich aber nicht darauf — prüfe es).
- Zustand RECONNECTING während der Wartezeit, FAILED erst nach n erschöpften Versuchen.
- Die Zeitquelle MUSS injizierbar sein (Interface oder Funktionstyp, kein System.currentTimeMillis()
  direkt). Sonst sind die Backoff-Tests nicht deterministisch und dauern Minuten.

=== AUFGABE 2: Ausfallerkennung ===

Vier unabhängige Signale, Plan 7.1:

1. Verbindungsabbruch (der Transport meldet DISCONNECTED)
2. **Staleness**: lastFrameAt älter als t_stale ⇒ DEGRADED. t_stale = 5 × erwartete
   Frame-Periode, mindestens 5 s. Das Gerät sendet alle ~515 ms, also 2,6 s → aufgerundet 5 s.
   Das ist der wichtigste Fall: Eine stehende GATT-Verbindung ohne Datenfluss ist der häufigste
   stille Ausfall.
3. **Bluetooth-Adapter aus**: BluetoothAdapter.ACTION_STATE_CHANGED beobachten. Bei STATE_OFF
   die Reconnect-Schleife PAUSIEREN statt Versuche zu verbrennen, bei STATE_ON sofort
   wiederaufnehmen.
4. **Fehlerrate**: > 20 % verworfene Frames über 30 s ⇒ DEGRADED ⇒ Verbindung proaktiv neu
   aufbauen (Plan 5.5).

Für Signal 4 fehlt heute die Grundlage: decodeErrors liegt im Decoder und ist von außen nicht
sichtbar. Ergänze MeterTransport um eine Kennzahl, über die der Supervisor Fehler- und
Frame-Zähler lesen kann, und implementiere sie in beiden Transports. Halte die Schnittstelle
klein — ein StateFlow mit einem kleinen Datenobjekt reicht.

⚠ Achtung, sonst baust du eine Schleife, die sich selbst nährt: Der Fehlerzähler wird beim
Reconnect zurückgesetzt (Decoder.reset()). Rechne die Rate über ein gleitendes Fenster, nicht
über den absoluten Zähler, sonst löst ein Reconnect den nächsten aus.

=== AUFGABE 3: Verbindung in den Foreground Service verlagern ===

Heute treibt MeterScreen die Verbindung. Das überlebt weder das Schließen der UI noch einen
Konfigurationswechsel. Die Verbindung gehört in den bestehenden AudioRecordingService, der
bereits als Foreground Service mit Typ microphone|connectedDevice läuft.

- Supervisor und Transport im Service betreiben, die UI beobachtet nur noch.
- Notification erweitern: aktueller Verbindungszustand, damit sichtbar ist, ob die
  Überwachung wirklich läuft.
- Beim Beenden des Service sauber trennen.

NICHT dabei: die Messwerte speichern oder den Aufnahme-Trigger umstellen — das ist M4.

=== AUFGABE 4: Neustart und Hintergrund ===

- BOOT_COMPLETED-Receiver, der die Überwachung nach einem Neustart wieder aufnimmt, wenn sie
  beim Herunterfahren aktiv war. Das Flag gehört in SettingsManager.
- Im Onboarding bzw. den Einstellungen die Ausnahme von der Akku-Optimierung anfordern
  (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) mit ehrlicher Begründung. Ohne sie drosseln
  Hersteller-ROMs den Service, das ist in der Praxis der Unterschied zwischen funktionierender
  und unbrauchbarer Dauerüberwachung.
- KEIN dauerhafter PARTIAL_WAKE_LOCK. BLE-Notifications wecken den Prozess auch im Doze.

=== AUFGABE 5: Kleiner offener Befund aus dem M2-Review ===

BleMeterTransport.onConnectionStateChange schließt bei einem spontanen Abbruch (Gerät aus,
Funkloch) das GATT-Objekt nicht, sondern setzt nur den Zustand. Heute folgenlos, weil der
nächste connect() über disconnect() schließt — mit einer automatischen Reconnect-Schleife wird
daraus aber genau das Szenario aus Plan 5.3: geleakte Client-Interface-Slots und nach einigen
Zyklen nur noch status 133.

g.close() im STATE_DISCONNECTED-Zweig ergänzen und gatt = null setzen.

NICHT TEIL VON M3
Persistenz der Messreihe und Trigger-Integration (M4), Alarmierung und Totmannschaltung (M5),
Verschlüsselung (M6), vollständiger Diagnose-Screen (M7), Drive-Sync (M7b), B-11, die vier
Altbefunde. Auch kein autoConnect-Auffangkanal, wenn er das Backoff verkompliziert — lieber
sauberes Backoff als zwei konkurrierende Mechanismen.

TESTS — hier liegt der Wert dieses Meilensteins
Alles gegen den FakeMeterTransport, mit injizierter Zeit. Mindestens:

- Abbruch ⇒ RECONNECTING ⇒ erfolgreicher Reconnect ⇒ STREAMING
- Backoff-Folge stimmt (1, 2, 4, 8, 16, 30, 60, 60 …), Jitter innerhalb ±20 %
- n Versuche erschöpft ⇒ FAILED
- simulateStall(true) ⇒ nach t_stale DEGRADED, obwohl die "Verbindung" steht
- simulateStall(false) ⇒ zurück zu STREAMING
- Adapter aus ⇒ keine weiteren Versuche; Adapter an ⇒ sofortiger Versuch
- simulateCorruptFrames(true) ⇒ Fehlerrate übersteigt 20 % ⇒ DEGRADED
- **Flapping**: Verbindung fällt 10× kurz aus und kommt zurück ⇒ kein FAILED, keine
  hochlaufende Fehlerrate, kein Reconnect-Sturm
- **Kein Selbstantrieb**: Nach einem Reconnect darf der zurückgesetzte Fehlerzähler nicht
  sofort wieder DEGRADED auslösen

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün — Ausgabe im PR zeigen, nicht behaupten.
  Fehlt ein Android SDK: sdkmanager "platforms;android-36" "build-tools;36.0.0", dann
  local.properties (sdk.dir=...).
- Für jeden neuen Test eine Gegenprobe: Schlägt er fehl, wenn man die zugehörige Logik
  entfernt? Tests, die in keiner Fassung fehlschlagen können, gehören nicht in den PR.
- Beide Room-Migrationstests weiterhin grün
- Draft-PR gegen main mit: was geändert, was verifiziert (Befehl und Ergebnis), was offen

ENDABNAHME AM GERÄT — im PR als offen markieren, nicht behaupten
Diese Punkte kann nur ein Test mit dem echten PCE-323 zeigen:
- Aus der Funkreichweite gehen und zurückkommen ⇒ Reconnect ohne Zutun
- Messgerät ausschalten ⇒ FAILED nach erschöpften Versuchen
- Bluetooth aus/ein ⇒ Pause und automatische Wiederaufnahme
- App-Prozess killen (adb shell am kill) ⇒ Service startet neu
- Telefon neu starten ⇒ Überwachung nimmt wieder auf
- 24-Stunden-Dauerlauf ⇒ kein Speicherleck, keine status-133-Kaskade
```

---

## Warum M3 vor dem Gerätetest sinnvoll ist

Wenn das PCE-323 das nächste Mal angeschlossen wird, will man nicht nur „Verbindung steht"
prüfen, sondern das Verhalten bei Abbruch. Ist M3 bis dahin fertig, deckt **ein** Gerätetermin
beides ab — statt zweimal dieselbe Vorbereitung.

## Zwei Fallen, die im Auftrag stehen

**Der Fehlerzähler als Selbstantrieb.** Plan 5.5 löst bei über 20 % Fehlerrate einen proaktiven
Neuaufbau aus. Seit M2 setzt jeder Reconnect den Decoder zurück. Rechnet der Supervisor über
den absoluten Zähler statt über ein gleitendes Fenster, löst jeder Reconnect den nächsten aus.

**Die Zeitquelle.** Ohne injizierbare Zeit dauern die Backoff-Tests real 60 Sekunden und mehr
pro Fall — und werden dann irgendwann „vereinfacht", bis sie nichts mehr prüfen.
