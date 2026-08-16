# Implementierungsplan: PCE-323 dBA-Empfang über Bluetooth

Stand: 2026-08-16 · Zielplattform: Android 8.0 (API 26) – Android 16 (API 36)

---

## 0. Ausgangslage

Das Repository `arthurschaab-bit/Noiseprotocol_Android` ist zum Zeitpunkt dieser Analyse
**vollständig leer** — keine Commits, keine Branches, kein Quellcode auf `origin`.

Konsequenz: Es gibt keine bestehende Architektur, die erweitert werden könnte. Dieser Plan
beschreibt daher den Aufbau der App **von Grund auf**, mit dem PCE-323-Bluetooth-Anbindung als
fachlichem Kern. Sollte lokal oder anderswo bereits ein Stand existieren, muss dieser Plan gegen
den tatsächlichen Code gespiegelt werden — insbesondere Abschnitt 4 (Zielarchitektur).

---

## 1. Zielbild und Anforderungen

### 1.1 Funktionale Anforderungen

| ID | Anforderung |
|----|-------------|
| F-1 | Kopplung mit einem PCE-323 über Bluetooth, Geräteauswahl per Scan-Liste |
| F-2 | Kontinuierlicher Empfang von Schallpegel-Messwerten (Ziel: **dBA**) im Livebetrieb |
| F-3 | Dauerhafte Aufzeichnung im Hintergrund (Foreground Service), auch bei gesperrtem Display |
| F-4 | Persistente Speicherung der Messreihe inkl. Lücken-/Ausfallmarkierung |
| F-5 | Ableitung akustischer Kennwerte: LAeq, LAFmax, LAFmin, Überschreitungsdauer je Schwelle |
| F-6 | **SMS-Alarm bei Verbindungsabbruch** an eine oder mehrere hinterlegte Rufnummern |
| F-7 | Automatischer Wiederverbindungsversuch mit Backoff, Entwarnungs-SMS bei Wiederkehr (optional) |
| F-8 | Export (CSV/PDF) der Messreihe inkl. Ausfallprotokoll |

### 1.2 Nichtfunktionale Anforderungen (Schwerpunkt des Auftrags)

| ID | Anforderung |
|----|-------------|
| N-1 | **Robuste Verbindung**: übersteht Funklöcher, Bluetooth-Aus/Ein, Doze, Prozess-Tod, Reboot |
| N-2 | **Sichere Verbindung**: Bindung an genau ein bekanntes Gerät, Verschlüsselung wo unterstützt, Plausibilisierung der Nutzdaten gegen Spoofing |
| N-3 | Keine Fehlalarme: Alarm erst nach definierter Karenzzeit und mehreren fehlgeschlagenen Reconnects |
| N-4 | Keine SMS-Stürme: Cooldown, Deduplizierung, Zustandsspeicherung über Prozessgrenzen hinweg |
| N-5 | Alarmzustellung nachweisbar: `sentIntent`/`deliveryIntent`, Retry, Persistenz der Alarm-Queue |
| N-6 | Messdaten und Rufnummern verschlüsselt at rest |

---

## 2. Gerätewissen PCE-323

### 2.1 Gesichert (Herstellerangaben)

- Schallpegelmessgerät Klasse 2, Messbereich 30–130 dB, 31,5 Hz – 8 kHz
- Frequenzbewertung **A und C**, per Tastendruck umschaltbar
- Zeitbewertung Fast/Slow
- Datenlogger für bis zu 32.700 Messwerte
- Schnittstellen: Mini-USB **und** Bluetooth
- Hersteller-Apps für Android und iOS: `com.pceinstruments.pce323` sowie `com.cem.supermeterbox`
  (CEM „SuperMeterBox“) — das PCE-323 ist ein OEM-Gerät von CEM/Shenzhen Everbest
- Gemeinsames Handbuch mit PCE-322A und PCE-MSM 4 → sehr wahrscheinlich gemeinsame Protokollfamilie

### 2.2 Protokoll der Gerätefamilie (aus libsigrok, Treiber `pce-322a`)

libsigrok hat das PCE-322A über dessen serielle Schnittstelle (CP210x, 9600 Bd) reverse-engineered.
Das PCE-323 ist der Bluetooth-Nachfolger derselben Familie; die Wahrscheinlichkeit, dass die
Bluetooth-Strecke exakt diesen Byte-Strom tunnelt, ist hoch — **muss aber in Phase 0 verifiziert
werden**.

**Live-Messframe (6 Byte):**

| Offset | Bedeutung |
|--------|-----------|
| 0 | Startmarker `0x7F` |
| 1–2 | Messwert, 16 Bit **big endian** |
| 3 | Bewertungs-Flags |
| 4 | Bereichs- und Hold-Flags |
| 5 | Endmarker `0x00` |

**Dekodierung:**

```
dB = ((buf[1] << 8) or buf[2]) / 10.0

buf[3] bit0 : 0 = A-Bewertung, 1 = C-Bewertung
buf[3] bit1 : 0 = Fast,        1 = Slow
buf[4] bit0..1 : Messbereich  0=30-130, 1=30-80, 2=50-100, 3=80-130 dB
buf[4] bit2 : Max-Hold aktiv
buf[4] bit3 : Min-Hold aktiv
```

**Kommandos (16 Bit, big endian):**

| Kommando | Wert |
|----------|------|
| CONNECT | `0xACFF` |
| DISCONNECT | `0xCAFF` |
| TOGGLE_WEIGHT_FREQ (A/C) | `0xAAF1` |
| TOGGLE_MEAS_RANGE | `0xAAF2` |
| TOGGLE_HOLD_MAX_MIN | `0xAAF3` |
| TOGGLE_WEIGHT_TIME (F/S) | `0xAAF4` |
| TOGGLE_HOLD | `0xAAF5` |
| TOGGLE_BACKLIGHT | `0xAAF6` |
| TOGGLE_DATE_TIME | `0xAAF7` |
| POWER_OFF | `0xAAF8` |
| LOG_START | `0x7E00` |
| MEMORY_STATUS | `0xADDA` |
| MEMORY_TRANSFER | `0xD3DA` (+ 16 Bit Blockadresse) |
| MEMORY_CLEAR | `0xAAC1` |

> **Fachlich wichtig für „dBA“:** Das Gerät liefert *einen* Pegelwert — ob dieser A- oder
> C-bewertet ist, steht ausschließlich in `buf[3] bit0`. Die App darf einen Wert **nur dann als
> dBA speichern**, wenn dieses Bit 0 ist. Steht das Gerät auf C, muss die App das erkennen und
> entweder per `0xAAF1` auf A zurückschalten oder den Nutzer warnen — sonst werden dBC-Werte
> stillschweigend als dBA protokolliert. Das ist ein Datenintegritätsrisiko, kein Kosmetikthema.

### 2.3 Offen — muss am realen Gerät ermittelt werden

- **Bluetooth-Variante**: Da eine iOS-App existiert, ist Bluetooth Classic SPP praktisch
  ausgeschlossen (dafür bräuchte es MFi). → **BLE (GATT) mit seriellem Bridge-Profil** ist die
  Arbeitshypothese.
- **GATT-UUIDs**: unbekannt. Typische Kandidaten bei diesen OEM-Modulen:
  - `0000FFE0-…` Service / `0000FFE1-…` Notify+Write (HM-10-Klasse)
  - `0000FFF0-…` Service / `0000FFF1-…` Notify / `0000FFF2-…` Write
  - Nordic UART `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (TX `…0003`, RX `…0002`)
- **Ob ein CONNECT-Kommando (`0xACFF`) nötig ist**, um den Notify-Strom zu starten
- **Frame-Rate** (erwartet 2 Hz, ggf. 1 Hz)
- **Ob MTU/Write-Type Anpassungen nötig sind** (Write With Response vs. Without)
- **Ob das Modul Bonding/Verschlüsselung unterstützt** (bei Billigmodulen oft nicht)

---

## 3. Phase 0 — Protokoll-Discovery (verbindlicher erster Schritt)

Ohne diese Phase ist jede weitere Codierung Spekulation. Aufwand ca. 0,5–1 Tag.

**Schritt 3.1 — GATT-Tabelle dumpen.** Mit *nRF Connect for Mobile* das PCE-323 verbinden, alle
Services/Characteristics/Descriptors samt Properties exportieren. Notify auf jede
Notify-fähige Characteristic aktivieren und beobachten, welche Rohdaten fließen.

**Schritt 3.2 — Referenzverkehr mitschneiden.** In den Entwickleroptionen *„Bluetooth-HCI-Snoop-Log
aktivieren“*, die Hersteller-App `com.pceinstruments.pce323` benutzen, Log ziehen
(`adb bugreport` bzw. `/data/misc/bluetooth/logs/btsnoop_hci.log`) und in Wireshark auswerten.
Damit sind Handshake, CCCD-Writes, Kommandobytes und Frame-Format eindeutig belegt.

**Schritt 3.3 — Hypothese verifizieren.** Prüfen, ob die Notify-Payloads mit `0x7F` beginnen,
6 Byte lang sind und ein plausibler dB-Wert herausfällt. Falls die Payload gestückelt ankommt
(BLE-MTU-Fragmentierung), ist ein **Reassembly-Puffer** nötig — der Decoder muss byteweise
über einen Ringpuffer resynchronisieren, nicht paketweise arbeiten.

**Schritt 3.4 — Ergebnis fixieren.** Alles Ermittelte wandert in
`transport/ble/src/main/kotlin/.../Pce323Profile.kt` als einzige Quelle der Wahrheit
(UUIDs, Kommandos, Framegrößen) und in `docs/PROTOKOLL_PCE-323.md` als Dokumentation inkl.
Rohdaten-Beispielen, die später als Testvektoren dienen.

**Deliverable Phase 0:** Bestätigtes Profil + mindestens 200 aufgezeichnete Roh-Frames als
`.bin`-Fixture für Unit-Tests.

---

## 4. Zielarchitektur

### 4.1 Technologiestack

- Kotlin, Coroutines + Flow
- Jetpack Compose (Material 3) für die UI
- MVVM + Clean-Layering, unidirektionaler Datenfluss
- Hilt für DI
- Room für die Messreihe, DataStore (verschlüsselt) für Einstellungen
- WorkManager + AlarmManager für Alarmierung und Wartungsjobs
- Gradle Version Catalogs, Kotlin DSL

### 4.2 Modulschnitt

```
:app                    Compose-Navigation, DI-Wiring, Manifest
:core:model             Reine Datenklassen (Measurement, ConnectionState, AlarmEvent)
:core:common            Dispatcher, Result-Typen, Zeitquelle (injizierbar!)
:core:database          Room: MeasurementEntity, SessionEntity, ConnectionEventEntity
:core:datastore         Verschlüsselte Einstellungen (Rufnummern, Schwellen, Gerät)
:transport:contract     interface MeterTransport — technologieunabhängig
:transport:ble          BLE-Implementierung + Pce323Profile + FrameDecoder
:transport:fake         Simulator für Tests und Demo-Modus
:feature:pairing        Scan, Auswahl, Bonding
:feature:live           Live-Anzeige, Pegelverlauf
:feature:protocol       Messreihen, Kennwerte, Export
:feature:settings       Alarmkonfiguration, Rufnummern, Diagnose
:service:monitoring     Foreground Service, Verbindungs-Supervisor
:alerting               Ausfallerkennung, SMS-Versand, Zustellnachweis
```

Begründung des Schnitts: `:transport:contract` erlaubt es, die Ausfallerkennung und die gesamte
UI ohne echtes Gerät zu testen (`:transport:fake`), und hält die Tür für eine spätere
SPP-/USB-Implementierung offen, ohne dass Domänencode angefasst werden muss.

### 4.3 Zentrale Schnittstelle

```kotlin
interface MeterTransport {
    val state: StateFlow<ConnectionState>
    val frames: SharedFlow<MeterFrame>      // dekodierte Frames
    val lastFrameAt: StateFlow<Instant?>    // Grundlage der Staleness-Erkennung

    suspend fun connect(device: BoundDevice)
    suspend fun disconnect()
    suspend fun send(command: MeterCommand): Result<Unit>
}

data class MeterFrame(
    val level: Double,                 // dB
    val weighting: Weighting,          // A oder C
    val timeWeighting: TimeWeighting,  // FAST oder SLOW
    val range: MeasurementRange,
    val holdMax: Boolean,
    val holdMin: Boolean,
    val receivedAt: Instant,
)
```

### 4.4 Datenfluss

```
PCE-323 ──BLE Notify──▶ GattCallback ──▶ Ringpuffer ──▶ FrameDecoder
                                                            │
                                                   Flow<MeterFrame>
                                                            │
                            ┌───────────────────────────────┼──────────────────────┐
                            ▼                               ▼                      ▼
                     MeasurementRepository          ConnectionSupervisor      LiveViewModel
                     (Room, Batch-Insert)           (Watchdog, Backoff)       (Compose UI)
                            │                               │
                     Leq/Max/Min-Aggregation          AlarmCoordinator
                                                            │
                                                    SmsDispatcher ──▶ SmsManager
```

---

## 5. Robuste Verbindung (N-1)

### 5.1 Zustandsautomat

```
        ┌──────────────────────────────────────────────┐
        ▼                                              │
     IDLE ──▶ SCANNING ──▶ CONNECTING ──▶ DISCOVERING ──▶ SUBSCRIBING ──▶ STREAMING
        ▲          │            │              │              │              │
        │          │            │              │              │      kein Frame > t_stale
        │          │            │              │              │              ▼
        │          │            │              │              │          DEGRADED
        │          └────────────┴──────────────┴──────────────┴──────────────┤
        │                                                                    ▼
        └───────────────── RECONNECTING ◀────────────────────────────── DISCONNECTED
                                │
                       n Versuche erschöpft
                                ▼
                             FAILED  ──▶ Alarm
```

`STREAMING` wird erst gemeldet, wenn **mindestens ein valider Frame dekodiert** wurde — nicht
schon bei `onServicesDiscovered`. Eine bestehende GATT-Verbindung ohne Datenfluss ist der
häufigste stille Ausfall und muss als Ausfall zählen.

### 5.2 GATT-Operationsqueue (nicht verhandelbar)

Der Android-BLE-Stack verarbeitet **immer nur eine** GATT-Operation gleichzeitig. Parallele
`writeCharacteristic`/`writeDescriptor`/`requestMtu`-Aufrufe werden stillschweigend verworfen —
Quelle Nr. 1 für „funktioniert auf meinem Gerät, aber nicht auf deinem“.

```kotlin
internal class GattQueue(private val scope: CoroutineScope) {
    private val ops = Channel<GattOp>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (op in ops) {
                withTimeoutOrNull(OP_TIMEOUT) { op.execute() }   // 10 s
                    ?: op.failWith(GattTimeout)
            }
        }
    }
}
```

Jede Operation wartet auf ihren Callback, bevor die nächste startet. Timeout → Operation als
fehlgeschlagen werten und Verbindung neu aufbauen, statt hängen zu bleiben.

### 5.3 Reconnect-Strategie

- **Exponentielles Backoff mit Jitter**: 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, danach konstant 60 s,
  Jitter ±20 % (verhindert Synchronisationseffekte nach Bluetooth-Neustart).
- **`gatt.close()` vor jedem neuen Versuch.** Nur `disconnect()` aufzurufen leakt den
  Client-Interface-Slot; nach ~30 Zyklen liefert der Stack ausschließlich `status 133`.
- **Kein `autoConnect = true` als alleinige Strategie** — die Latenz ist unvorhersehbar. Empfehlung:
  erster Versuch `autoConnect = false` (schnell), ab dem dritten Fehlversuch zusätzlich ein
  paralleler `autoConnect = true`-Kanal als „Auffangnetz“ für Funkloch-Rückkehr.
- **Adapter-Zustand beobachten** via `BluetoothAdapter.ACTION_STATE_CHANGED`: bei `STATE_OFF`
  Reconnect-Schleife pausieren (sonst nur verbrannte Versuche), bei `STATE_ON` sofort
  wiederaufnehmen.
- **Bonding-Verlust erkennen**: `ACTION_BOND_STATE_CHANGED` → `BOND_NONE` bedeutet, der Nutzer hat
  das Gerät entkoppelt; das ist kein Netzfehler, sondern erfordert Nutzerinteraktion → eigener
  Zustand, eigene Meldung, kein endloser Retry.

### 5.4 Überleben im Hintergrund

- **Foreground Service** mit `foregroundServiceType="connectedDevice"` und dauerhafter
  Notification (Pegel + Verbindungsstatus, damit sie informativ statt lästig ist).
- **`BOOT_COMPLETED`-Receiver**, der die Überwachung nach Neustart automatisch wieder aufnimmt,
  falls sie beim Herunterfahren aktiv war (Flag in DataStore).
- **Akku-Optimierung**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mit ehrlicher Begründung im
  Onboarding anfordern. Ohne Ausnahme drosseln Hersteller-ROMs (Xiaomi, Huawei, Samsung, OnePlus)
  den Service aggressiv. Zusätzlich einen Hinweisdialog mit herstellerspezifischer Anleitung
  („Autostart erlauben“) einbauen — das ist in der Praxis der Unterschied zwischen funktionierender
  und unbrauchbarer Dauerüberwachung.
- **WakeLock**: bewusst *kein* dauerhafter `PARTIAL_WAKE_LOCK`. BLE-Notifications wecken den
  Prozess auch im Doze. Ein WakeLock wird nur kurz während Schreibvorgängen und Alarmierung gehalten.
- **Prozess-Tod**: Der letzte bekannte Zustand (Session-ID, Alarmzustand, Zeitpunkt des letzten
  Frames) liegt in Room bzw. DataStore. Beim Neustart des Service wird daraus entschieden, ob
  seit dem Absturz ein alarmwürdiger Ausfall vorlag.

### 5.5 Datenqualität

- **Reassembly-Ringpuffer** mit Resynchronisation auf `0x7F` … `0x00`; unplausible Frames werden
  verworfen und gezählt (Metrik `decodeErrors`).
- **Plausibilitätsfilter**: Werte außerhalb 20–140 dB verwerfen; Sprünge > 40 dB zwischen zwei
  aufeinanderfolgenden Frames markieren (nicht verwerfen — Impulsschall ist real).
- **Fehlerrate als Gesundheitssignal**: > 20 % verworfene Frames über 30 s ⇒ Zustand `DEGRADED`
  ⇒ Verbindung proaktiv neu aufbauen. Eine dauerhaft schlechte Verbindung ist schlimmer als eine
  getrennte, weil sie Lücken erzeugt, ohne dass jemand es merkt.

---

## 6. Sichere Verbindung (N-2)

Realistische Einordnung: Ein OEM-BLE-Modul dieser Preisklasse bietet in der Regel **keine
Authentifizierung**. Es sendet an jeden, der Notifications abonniert, und akzeptiert Kommandos von
jedem. Sicherheit muss deshalb überwiegend auf App-Seite hergestellt werden.

**Auf Transportebene:**
- Nach dem Verbinden `createBond()` versuchen und `gatt.getDevice().bondState` prüfen. Wenn das
  Gerät Bonding unterstützt, wird der Link verschlüsselt (LE Secure Connections ab BT 4.2) und
  gegen passives Mitlesen und einfaches Spoofing geschützt.
- Wenn Bonding **nicht** unterstützt wird: das im UI ehrlich als „unverschlüsselte Verbindung“
  kennzeichnen, statt Sicherheit vorzutäuschen.

**Auf Anwendungsebene:**
- **Geräte-Pinning**: Nach der Erstkopplung wird die MAC-Adresse (bzw. bei Resolvable Private
  Addresses die Identity Address des Bonds) persistiert. Es wird ausschließlich zu diesem Gerät
  verbunden. Ein Advertiser mit gleichem Namen, aber anderer Adresse wird ignoriert und geloggt.
- **Stream-Plausibilisierung als Spoofing-Erkennung**: Ein untergeschobenes Gerät müsste Framing,
  Flag-Kombinationen und die konstante Frame-Rate exakt nachbilden. Abweichungen in Kadenz
  (erwartet 2 Hz ±20 %), Framelänge oder reservierten Bits ⇒ Verbindung trennen, Ereignis
  protokollieren, als sicherheitsrelevanter Ausfall behandeln.
- **Keine blinde Kommandoausführung**: Zustandsverändernde Kommandos (`POWER_OFF`, `MEMORY_CLEAR`,
  Bereichswechsel) nur auf explizite Nutzeraktion, nie automatisch. Ausnahme mit Bestätigung:
  automatisches Zurückschalten auf A-Bewertung.

**Auf Datenebene:**
- Rufnummern und Alarmkonfiguration in **EncryptedSharedPreferences / DataStore mit Tink**,
  Schlüssel im Android Keystore.
- Messdatenbank optional mit **SQLCipher**, Passphrase aus dem Keystore. Empfehlung: standardmäßig
  an, wenn die Protokolle personen- oder betriebsbezogen sind.
- `android:allowBackup="false"` und `android:dataExtractionRules`, damit Rufnummern und
  Messprotokolle nicht über Auto-Backup abfließen.
- Export-Dateien über einen `FileProvider` teilen, niemals über `file://`-URIs oder externen
  Speicher.
- Diagnose-/Rohdaten-Logs standardmäßig aus; wenn aktiviert, nur in den App-internen Speicher und
  mit automatischer Löschung nach 7 Tagen.

---

## 7. Ausfallerkennung und SMS-Alarm (F-6)

### 7.1 Ausfall ist nicht gleich Ausfall

Die Erkennung speist sich aus **vier unabhängigen Signalen**:

| Signal | Quelle | Bedeutung |
|--------|--------|-----------|
| GATT-Disconnect | `onConnectionStateChange` | harter Abbruch |
| Daten-Staleness | `lastFrameAt` älter als `t_stale` | stiller Ausfall bei bestehender Verbindung |
| Adapter aus | `ACTION_STATE_CHANGED` | Nutzer/System hat Bluetooth deaktiviert |
| Reconnect-Erschöpfung | Supervisor | Gerät dauerhaft nicht erreichbar |

`t_stale` = 5 × erwartete Frame-Periode, mindestens 5 s (bei 2 Hz also 5 s). Konfigurierbar.

### 7.2 Alarmlogik mit Entprellung (N-3)

```
Ausfallsignal
     │
     ▼
Karenzzeit t_grace (Default 60 s, einstellbar 10 s – 15 min)
     │  In dieser Zeit laufen Reconnect-Versuche.
     │  Erfolgreicher Reconnect + valider Frame ⇒ abbrechen, kein Alarm.
     ▼
Alarm ausgelöst ⇒ AlarmEvent(id, reason, since) in Room persistieren
     │
     ▼
SMS-Versand an alle konfigurierten Empfänger
     │
     ▼
Cooldown t_cool (Default 30 min): kein weiterer Alarm-SMS für denselben Ausfall.
Optional Eskalation: wenn nach t_esc (Default 60 min) immer noch getrennt ⇒ Wiederholungs-SMS,
maximal n_max Wiederholungen (Default 3).
     │
     ▼
Verbindung kehrt zurück ⇒ optionale Entwarnungs-SMS, AlarmEvent schließen
```

Die Karenzzeit wird über `AlarmManager.setExactAndAllowWhileIdle()` gestellt, **nicht** über einen
Coroutine-`delay()`. Ein `delay()` im Doze-Modus feuert unter Umständen erst Stunden später — genau
dann, wenn der Alarm am wichtigsten wäre. Der Alarmzustand liegt in Room, damit ein Prozess-Tod
während der Karenzzeit den Alarm nicht verschluckt.

### 7.3 SMS-Versand mit Zustellnachweis (N-5)

```kotlin
class SmsDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun send(recipient: String, body: String, alertId: Long) {
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(body)          // > 160 Zeichen sicher behandeln

        val sentIntents = parts.indices.map { index ->
            PendingIntent.getBroadcast(
                context,
                requestCode(alertId, index),
                Intent(ACTION_SMS_SENT)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_ALERT_ID, alertId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        sms.sendMultipartTextMessage(recipient, null, parts, ArrayList(sentIntents), null)
    }
}
```

- Ein `BroadcastReceiver` wertet `resultCode` aus (`RESULT_ERROR_NO_SERVICE`,
  `RESULT_ERROR_RADIO_OFF`, `RESULT_ERROR_GENERIC_FAILURE`) und markiert den `AlarmEvent` als
  `SENT` oder `FAILED`.
- **Bei Fehlschlag**: WorkManager-Job mit `NetworkType.NOT_REQUIRED` und Backoff, der den Versand
  wiederholt, sobald wieder Netz da ist. Alarme dürfen nicht verloren gehen, nur weil das Handy im
  Moment des Ausfalls kein Netz hatte.
- **Dual-SIM**: `SmsManager.createForSubscriptionId()` mit vom Nutzer gewählter SIM
  (`SubscriptionManager.getActiveSubscriptionInfoList()`, benötigt `READ_PHONE_STATE`).
- **Nachrichtentext** kompakt und selbsterklärend, Zeitstempel in Ortszeit:

  > `Lärmprotokoll: Verbindung zu PCE-323 unterbrochen seit 16.08.2026 14:32 (Grund: keine Daten). Aufzeichnung pausiert.`

- **Testfunktion** in den Einstellungen: „Test-SMS senden“ — verifiziert Berechtigung, SIM-Auswahl
  und Rufnummer, bevor es ernst wird.

### 7.4 ⚠ Google-Play-Restriktion für `SEND_SMS`

`SEND_SMS` ist eine von Google eingeschränkte Berechtigung. Eine App, deren Kernfunktion nicht
SMS-Verwaltung ist (also genau unser Fall), wird bei Veröffentlichung im Play Store **abgelehnt**,
sofern nicht eine Ausnahmegenehmigung über das Declaration Form erteilt wird — die für diesen
Anwendungsfall erfahrungsgemäß selten gewährt wird.

Handlungsoptionen, **Entscheidung wird benötigt**:

| Option | Bewertung |
|--------|-----------|
| **A: Interne Verteilung** (APK-Sideload, MDM, Play Private/Interne Testschiene) | Direkter `SmsManager`-Versand uneingeschränkt möglich. **Empfehlung, wenn die App für den Eigenbedarf ist.** |
| **B: SMS-Gateway** (Twilio, sms77/seven, Vonage) | Play-konform, funktioniert auch ohne SIM im Messgerät-Handy — braucht aber Internet im Moment des Ausfalls und einen serverseitigen Schlüssel. Kein API-Key darf in der App liegen ⇒ eigener Relay-Endpunkt nötig. |
| **C: Fallback-Kaskade** | Primär SMS (falls Berechtigung vorhanden), sekundär Push/E-Mail/Gateway. Höchste Zuverlässigkeit, höchster Aufwand. |

Die Architektur bildet das ab: `AlertChannel` als Interface mit den Implementierungen
`SmsAlertChannel`, `GatewayAlertChannel`, `NotificationAlertChannel`. Der `AlarmCoordinator`
arbeitet eine priorisierte Kanalliste ab. Damit ist die Entscheidung reversibel und blockiert die
Umsetzung nicht.

---

## 8. Datenmodell und Kennwerte

### 8.1 Room-Schema

```kotlin
@Entity  data class SessionEntity(
    val id: Long, val startedAt: Instant, val endedAt: Instant?,
    val deviceAddress: String, val deviceName: String,
    val weighting: String, val timeWeighting: String,
)

@Entity  data class MeasurementEntity(
    val id: Long, val sessionId: Long, val timestamp: Instant,
    val levelDb: Double, val weighting: String, val flags: Int,
)

@Entity  data class ConnectionEventEntity(
    val id: Long, val sessionId: Long, val at: Instant,
    val type: String,        // CONNECTED, DISCONNECTED, DEGRADED, RECOVERED
    val reason: String?,
)

@Entity  data class AlertEntity(
    val id: Long, val sessionId: Long, val raisedAt: Instant,
    val resolvedAt: Instant?, val reason: String,
    val recipients: String, val deliveryState: String,   // PENDING/SENT/FAILED
    val attempts: Int,
)
```

`ConnectionEventEntity` ist bewusst eine eigene Tabelle: Das Ausfallprotokoll gehört in den Export,
sonst ist eine Messreihe mit Lücken forensisch wertlos.

### 8.2 Schreibstrategie

Bei 2 Hz fallen 7.200 Werte/Stunde an. **Nicht** einzeln inserten:
- Puffer im Speicher, `insertAll()` alle 5 s oder alle 50 Werte in einer Transaktion.
- Puffer bei Service-Stopp und bei `onTrimMemory` zwangsweise flushen.
- Retention-Job (WorkManager, täglich): Rohwerte älter als N Tage zu Minutenaggregaten verdichten.

### 8.3 Akustische Kennwerte

```
LAeq(T) = 10 · log10( (1/N) · Σ 10^(Li/10) )
```

Wichtig: Der energetische Mittelwert, **nicht** das arithmetische Mittel der dB-Werte — das ist ein
klassischer und im Protokollkontext gravierender Fehler. Zusätzlich LAFmax, LAFmin,
Überschreitungsdauer pro konfigurierter Schwelle sowie Perzentile L10/L50/L90 (streaming-fähig via
Histogramm in 0,1-dB-Bins).

---

## 9. UI (Compose)

| Screen | Inhalt |
|--------|--------|
| **Onboarding** | Berechtigungen erklärt und angefragt, Akku-Ausnahme, Herstellerhinweise |
| **Kopplung** | BLE-Scan gefiltert auf Namensmuster, Signalstärke, Bonding-Status |
| **Live** | Großer dBA-Wert, Verlaufsgraph (60 s), Verbindungs-Badge, A/C-Warnbanner |
| **Protokoll** | Sessions, Kennwerte, Ausfallmarkierungen als rote Bänder im Verlauf |
| **Alarm** | Empfängerliste, Karenzzeit, Cooldown, Eskalation, SIM-Auswahl, Test-SMS |
| **Diagnose** | Zustandsautomat live, Reconnect-Zähler, Decode-Fehlerrate, Roh-Frame-Log |

Der **Diagnose-Screen** ist kein Luxus: Bei einer Dauerüberwachung, die per SMS alarmiert, muss
nachvollziehbar sein, warum ein Alarm ausgelöst wurde oder ausblieb.

Verbindungsstatus wird nie nur farblich kodiert (Barrierefreiheit) — immer zusätzlich Text und Icon.

---

## 10. Berechtigungen und Manifest

```xml
<!-- Bluetooth ab Android 12 -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Bluetooth bis Android 11 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />   <!-- Dual-SIM -->

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

`neverForLocation` auf `BLUETOOTH_SCAN` ist wichtig — sonst verlangt Android 12+ zusätzlich die
Standortberechtigung, was die Onboarding-Abbruchquote deutlich erhöht.

---

## 11. Teststrategie

**Unit-Tests**
- `FrameDecoder` gegen die in Phase 0 aufgezeichneten Golden-Byte-Vektoren
- Fragmentierte, verstümmelte und verschachtelte Frames → Resynchronisation
- Leq-Berechnung gegen handgerechnete Referenzwerte
- `AlarmCoordinator` mit injizierter Testzeit: Karenzzeit, Cooldown, Eskalation, Flapping
  (Verbindung fällt 10× kurz aus ⇒ höchstens 1 SMS)

**Integrationstests**
- Room-Migrationen
- `:transport:fake` speist reale Aufzeichnungen ein; komplette Pipeline bis SMS-Trigger wird
  ohne Hardware verifiziert

**Instrumentierte Tests**
- Zwei Testgeräte: eines als BLE-Peripheral (`BluetoothGattServer`), das das PCE-323-Profil
  emuliert — damit sind Verbindungsabbrüche deterministisch reproduzierbar

**Chaos-/Feldtests (manuelle Checkliste)**
| Szenario | Erwartung |
|----------|-----------|
| Gerät ausschalten | Alarm nach t_grace, SMS zugestellt |
| Aus Funkreichweite gehen und zurückkommen | Reconnect ohne Alarm, wenn < t_grace |
| Bluetooth aus/ein | Pause, danach automatische Wiederaufnahme |
| Flugmodus während Alarm | SMS wird gequeued und später zugestellt |
| App-Prozess killen (`adb shell am kill`) | Service startet neu, Alarmzustand erhalten |
| Reboot | Überwachung nimmt automatisch wieder auf |
| 24-h-Dauerlauf | Kein Speicherleck, keine `status 133`-Kaskade, lückenlose Messreihe |
| Gerät auf C-Bewertung umschalten | Warnung im UI, keine dBC-Werte als dBA gespeichert |

---

## 12. Meilensteine

| # | Meilenstein | Inhalt | Aufwand |
|---|-------------|--------|---------|
| **M0** | Protokoll-Discovery | Abschnitt 3 vollständig, Profil + Testvektoren | 0,5–1 d |
| **M1** | Projektgerüst | Gradle, Module, Hilt, Compose-Navigation, CI | 1 d |
| **M2** | BLE-Basis | Scan, Verbindung, GattQueue, Notify, `FrameDecoder`, Live-Anzeige | 3–4 d |
| **M3** | Robustheit | Zustandsautomat, Backoff, Adapter-Beobachtung, Foreground Service, Boot-Receiver | 3 d |
| **M4** | Persistenz | Room, Batch-Writer, Sessions, Verbindungsereignisse, Leq/Max/Min | 2–3 d |
| **M5** | Alarmierung | Watchdog, Karenzzeit via AlarmManager, `SmsDispatcher`, Zustellnachweis, Retry | 3 d |
| **M6** | Sicherheit | Bonding, Geräte-Pinning, Keystore, verschlüsselter DataStore, SQLCipher, Backup-Regeln | 2 d |
| **M7** | UI-Ausbau | Protokollansicht, Einstellungen, Diagnose, Export CSV/PDF | 3–4 d |
| **M8** | Härtung | Chaos-Checkliste, 24-h-Dauerlauf, Herstellerspezifika, Release-Build | 2–3 d |

**Gesamt ca. 20–26 Personentage.** M0 ist echte Voraussetzung für M2 — ohne bestätigtes Profil
sollte M2 nicht begonnen werden.

**Kritischer Pfad:** M0 → M2 → M3 → M5. M4, M6 und M7 sind parallelisierbar.

---

## 13. Risiken und offene Entscheidungen

| Risiko | Auswirkung | Gegenmaßnahme |
|--------|-----------|---------------|
| Bluetooth-Profil weicht von der Annahme ab (kein 0x7F-Framing) | M2 verzögert sich | M0 vorgeschaltet; Decoder ist gekapselt und austauschbar |
| PCE-323 nutzt Bluetooth Classic SPP statt BLE | Transport neu zu implementieren | `MeterTransport`-Abstraktion; SPP-Variante ist die einfachere Implementierung |
| `SEND_SMS` blockiert Play-Veröffentlichung | Vertriebsweg | Abschnitt 7.4, `AlertChannel`-Abstraktion |
| Hersteller-ROM killt den Foreground Service | Stiller Überwachungsausfall | Akku-Ausnahme, Boot-Receiver, Heartbeat-Selbstüberwachung, Nutzerhinweise |
| BLE-Modul unterstützt kein Bonding | Unverschlüsselter Link | Ehrliche Kennzeichnung im UI + Plausibilisierung auf App-Ebene |
| Gerät steht auf C-Bewertung | Falsche dBA-Werte im Protokoll | Flag-Auswertung, Warnbanner, optionales Auto-Umschalten |
| Meter schaltet sich per Auto-Power-Off ab | Dauerüberwachung endet | Auto-Power-Off am Gerät deaktivieren, Netzteilbetrieb dokumentieren |

**Entscheidungen, die vom Auftraggeber benötigt werden:**

1. **Vertriebsweg** — Play Store oder interne Verteilung? Bestimmt Option A/B/C aus 7.4.
2. **Alarmparameter** — Default-Karenzzeit, Cooldown, Eskalation, Anzahl Empfänger.
3. **Entwarnungs-SMS** — gewünscht oder unerwünscht (verdoppelt das SMS-Aufkommen)?
4. **Aufbewahrungsdauer** der Rohmesswerte und ob die Datenbank verschlüsselt werden soll.
5. **Minimum-SDK** — API 26 (breite Abdeckung, mehr Legacy-Pfade) oder API 31 (deutlich
   einfacheres Bluetooth-Permission-Handling).

---

## 14. Quellen

- [PCE-323 Produktseite (PCE Instruments)](https://www.pce-instruments.com/deutsch/messtechnik/messgeraete-fuer-alle-parameter/schallpegelmessgeraet-schallpegelmesser-pce-instruments-schallpegelmessgeraet-pce-323-det_5990588.htm)
- [PCE-323 Technische Daten](https://www.warensortiment.de/technische-daten/bluetooth-schallpegelmessgeraet-pce-323.htm)
- [sigrok: PCE-322A support](https://www.sigrok.org/blog/pce-pce-322a-support)
- [libsigrok `src/hardware/pce-322a/protocol.h`](https://raw.githubusercontent.com/sigrokproject/libsigrok/master/src/hardware/pce-322a/protocol.h)
- [libsigrok `src/hardware/pce-322a/protocol.c`](https://raw.githubusercontent.com/sigrokproject/libsigrok/master/src/hardware/pce-322a/protocol.c)
- [PCE-323 App im Google Play Store](https://play.google.com/store/apps/details?id=com.pceinstruments.pce323)
- [PCE-322A Bedienungsanleitung](https://www.pce-instruments.com/api/getartfile?_fnr=1045398)
