# PCE-323 Protokoll-Discovery — Ergebnis (M0)

Ergebnis von [Plan Abschnitt 3, Phase 0](IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md#3-phase-0--protokoll-discovery-verbindlicher-erster-schritt),
durchgeführt nach [`docs/PROTOKOLL_PCE-323_ANLEITUNG.md`](PROTOKOLL_PCE-323_ANLEITUNG.md).
Datenquelle: nRF-Connect-Log vom 2026-08-17, Gerät PCE-323 (MAC `30:1B:97:F8:C8:AD`).

**Wichtigstes Ergebnis zuerst:** Die im Plan (Abschnitt 2.2) beschriebene Hypothese —
6-Byte-Frame mit `0x7F`-Start-/`0x00`-Endmarker, übernommen aus dem PCE-322A-Protokoll
(libsigrok) — **trifft auf das reale Gerät nicht zu**. Genau das Risiko, das Plan
Abschnitt 13 als „Bluetooth-Profil weicht von der Annahme ab" benannt hatte, ist
eingetreten. Das reale Frame-Format ist unten dokumentiert und ersetzt die alte Annahme
vollständig. Die einzige Quelle der Wahrheit im Code ist
[`meter/ble/Pce323Profile.kt`](../app/src/main/java/com/example/lrmprotokoll/meter/ble/Pce323Profile.kt).

---

## 1. Gerät und Modul

| | |
|---|---|
| Geräte-MAC | `30:1B:97:F8:C8:AD` |
| Device Name (GATT, `0x2A00`) | `PCE-323` |
| Manufacturer (`0x2A29`) | `Lierda` |
| Model Number (`0x2A24`) | `LSD4BTC-T55ALSP001` |
| Firmware Revision (`0x2A26`) | `Rev07` |
| Hardware Revision (`0x2A27`) | `Rev01` |
| Software Revision (`0x2A28`) | `v1.7.231020` |
| PnP ID (`0x2A50`) | Company `0x2717` (reserviert), Product ID `12800`, Version `272` |

Lierda ist ein bekannter chinesischer BLE-Modulhersteller — das PCE-323 nutzt also, wie im
Plan vermutet, ein OEM-BLE-Modul eines Drittanbieters, nicht ein hauseigenes Bluetooth-Stack.
Wichtig: **Lierda LSD4BTC ist eine andere Modulfamilie als das PCE-322A/CEM-Protokoll**, aus
dem die ursprüngliche Byte-Format-Hypothese stammte — daher die Abweichung.

## 2. GATT-Tabelle

Neben den Standard-Services (Generic Access, Generic Attribute, Device Information) gibt es
genau einen relevanten Custom-Service:

| Service/Characteristic | UUID | Properties | Rolle |
|---|---|---|---|
| Service | `0000fff0-0000-1000-8000-00805f9b34fb` | — | Custom-Service, alle Mess-Characteristics |
| Characteristic | `0000fff1-…` | Write, Write No Response | Vermutlich Kommandokanal — **in dieser Aufzeichnung nicht benutzt/beobachtet** |
| Characteristic | `0000fff2-…` | Notify | **Bestätigt: liefert den kontinuierlichen Messwert-Strom** |
| Characteristic | `0000fe63-…` | Notify, Read, Write, Write No Response | Zweck ungeklärt. Read → `GATT READ NOT PERMIT`. Keine Notifications beobachtet |
| Characteristic | `0000fe64-…` | Notify, Read, Write, Write No Response | Zweck ungeklärt. Read → 0 Byte. Keine Notifications beobachtet |

Jede Characteristic hat einen CCCD-Descriptor (`0x2902`) und eine User-Description
(`0x2901`), wie GATT-Standard vorschreibt.

**Kein CONNECT-Kommando nötig.** Anders als beim PCE-322A-Protokoll (dort `0xACFF`) beginnt
der Notify-Strom auf `0000fff2` unmittelbar nach dem Schreiben des CCCD-Descriptors
(`gatt.writeDescriptor(00002902-…, value=0x0100)`), ohne dass vorher ein Kommando auf
`0000fff1` geschrieben wird. Das vereinfacht `BleMeterTransport` in M2.

## 3. Frame-Format (bestätigt)

Auf `0000fff2` trifft alle ~515 ms (siehe Abschnitt 5) ein Notify-Paket ein. Wegen der in
dieser Aufzeichnung verwendeten Default-ATT-MTU von 23 Byte (20 Byte Notify-Nutzlast) wird
das logische 23-Byte-Frame vom Peripheriegerät auf **zwei separate BLE-Notify-Events**
aufgeteilt — in 99 von 99 aufgezeichneten Fällen exakt so:

```
Notify #1 (20 Byte): D5 03 00 00 00 10 C3 00 01 01 08 00 00 00 [4 Byte Messwert] 01 0F
Notify #2 ( 3 Byte): 2C 00 0D
```

Zusammengesetzt zum logischen 23-Byte-Frame:

| Offset | Länge | Inhalt | Status |
|---|---|---|---|
| 0–13 | 14 Byte | `D5 03 00 00 00 10 C3 00 01 01 08 00 00 00` | konstant in 99/99 Frames |
| 14–17 | 4 Byte | Messwert, **IEEE-754 float32, big endian**, Wert direkt in dB | bestätigt (s. u.) |
| 18–19 | 2 Byte | `01 0F` | konstant in 99/99 Frames |
| 20–22 | 3 Byte | `2C 00 0D` | konstant in 99/99 Frames, Funktion ungeklärt |

**Warum float32 big endian gesichert ist:** Die 99 aufgezeichneten Werte liegen zwischen
41,8 und 54,0, Mittelwert 44,7 — exakt der erwartete Bereich für eine ruhige
Innenraum-Umgebungslautstärke. Die Byte-Muster in den Nachkommastellen (`66 66` → `.1`/`.6`,
`CC CD` → `.4`/`.9` gerundet, `99 9A` → `.3`/`.8`, `33 33` → `.2`/`.7`, `00 00` → `.0`/`.5`)
sind das typische IEEE-754-Rundungsmuster einer Dezimalzahl mit einer Nachkommastelle — kein
Zufall bei 99 unabhängigen Messwerten.

**Ungeklärt bleibt:** ob der Wert dBA, dBC oder ungewichtet ist, und welche der konstanten
Bytes (Header, Footer, Trailer) A/C-Bewertung, Fast/Slow oder Messbereich kodieren — dazu
hätte während der Aufzeichnung am Gerät die Bewertung umgeschaltet werden müssen, was nicht
geschehen ist (siehe Abschnitt 7).

### Rohdaten-Beispiele

```
06:09:49.293  d5 03 00 00 00 10 c3 00 01 01 08 00 00 00 42 28 66 66 01 0f  2c 00 0d  42.10
06:09:50.325  d5 03 00 00 00 10 c3 00 01 01 08 00 00 00 42 28 00 00 01 0f  2c 00 0d  42.00
06:09:51.362  d5 03 00 00 00 10 c3 00 01 01 08 00 00 00 42 27 99 9a 01 0f  2c 00 0d  41.90
06:10:38.791  d5 03 00 00 00 10 c3 00 01 01 08 00 00 00 42 2c 00 00 01 0f  2c 00 0d  43.00
06:10:39.783  d5 03 00 00 00 10 c3 00 01 01 08 00 00 00 42 2c 66 66 01 0f  2c 00 0d  43.10
```

Vollständige Rohdaten (alle 99 Frames, Zeitstempel + Rohbytes + dekodierter Wert):
[`docs/discovery/pce323_notify_frames_2026-08-17.txt`](discovery/pce323_notify_frames_2026-08-17.txt).
Dieselben Daten als reine Bytes (99 × 23 Byte, für Decoder-Tests):
[`docs/discovery/pce323_notify_frames_2026-08-17.bin`](discovery/pce323_notify_frames_2026-08-17.bin).

**Plan-Vorgabe war mindestens 200 Roh-Frames — hier liegen 99 vor** (eine Aufzeichnung von
knapp 50 Sekunden). Für belastbare Decoder-Tests reicht das, für die Frage „ändert sich ein
Byte bei A/C-Umschaltung" reicht es nicht — eine längere Folgeaufzeichnung mit bewussten
Zustandswechseln am Gerät wäre wertvoll (siehe Abschnitt 7).

## 4. Sicherheit: kein Bonding

Ein `device.createBond()`-Versuch während der Aufzeichnung (06:08:32) führte umgehend zu:

```
BOND_STATE_CHANGED → BONDING (11)
Connection state changed: DISCONNECTED (0), status 19 ("Connection terminated by peer")
BOND_STATE_CHANGED → NONE (10), reason: AUTH FAILED (1)
```

Das Modul lehnt Pairing-Versuche ab und trennt die Verbindung. Damit ist bestätigt, was Plan
Abschnitt 6 als realistische Einordnung vorwegnahm: **kein Bonding, keine
Link-Layer-Verschlüsselung.** Sicherheit muss vollständig auf App-Ebene über
Geräte-Pinning (MAC-Adresse) und Stream-Plausibilisierung (erwartete Frame-Rate, erwartetes
Framing) hergestellt werden — nicht über Bluetooth-Security-Mechanismen.

## 5. Frame-Rate

Zeitabstände zwischen den 99 Notify-Paaren: Minimum 449 ms, Maximum 586 ms, Mittelwert
515 ms — das entspricht rund **1,9–2,0 Hz** und bestätigt die im Plan erwartete Rate.

## 6. Beantwortung der offenen Fragen (Plan Abschnitt 2.3)

| Frage | Antwort |
|---|---|
| BLE oder Bluetooth Classic SPP? | **BLE**, bestätigt (nRF Connect verbindet über `connectGatt`) |
| GATT-UUIDs? | Service `0000fff0`, Notify `0000fff2`, Write `0000fff1` (siehe Abschnitt 2) — **keiner der im Plan genannten Kandidaten (FFE0/FFE1, Nordic UART) traf zu**, es ist ein eigenes „FFF0"-Schema |
| CONNECT-Kommando nötig? | **Nein** — Notify-Strom startet direkt nach CCCD-Write |
| Frame-Rate? | ~2 Hz, bestätigt |
| MTU/Write-Type-Besonderheiten? | Default-MTU 23 Byte führt zur Zweiteilung 20+3 Byte; kein `requestMtu()` in dieser Aufzeichnung getestet — ob eine größere MTU das Frame in einem Stück liefert, ist offen |
| Bonding unterstützt? | **Nein**, siehe Abschnitt 4 |

## 7. Offene Punkte für eine Folgeaufzeichnung

- **A/C-Bewertung, Fast/Slow, Messbereich, Hold**: keines dieser Bits konnte identifiziert
  werden, weil sich am Gerät während der Aufzeichnung nichts davon geändert hat. Eine
  Folgeaufzeichnung sollte gezielt jede Taste einmal betätigen und dabei mitschneiden, welches
  der 16 konstanten Header-/Footer-/Trailer-Bytes sich ändert.
- **≥ 200 Frames** laut Plan-Vorgabe, hier 99 — für eine längere Testabdeckung (Grenzwerte,
  seltene Zustände) wäre eine längere Aufzeichnung sinnvoll.
- **Zweck von `0000fe63`/`0000fe64`**: keine Aktivität beobachtet. Könnten mit
  `MEMORY_STATUS`/`MEMORY_TRANSFER` (Datenlogger-Auslesen) zusammenhängen, unbestätigt.
  Read auf beiden schlägt fehl bzw. liefert nichts — deutet eher auf geschützte/gerade
  inaktive Funktionen als auf einen offensichtlichen zweiten Datenkanal.
- **Zweck von `0000fff1`** (Write-Characteristic): kein Schreibzugriff in dieser Aufzeichnung
  getestet. Ob und welche Kommandos es akzeptiert, ist unbekannt — die PCE-322A-Kommandotabelle
  aus dem Plan (`0xACFF` usw.) ist durch dieses Ergebnis **nicht bestätigt** und sollte nicht
  ungeprüft übernommen werden.
- **Kalibrierung/Bewertung des Werts**: nicht verifiziert, ob der übertragene Wert dBA, dBC
  oder unbewertet ist. Ein Soll-Ist-Abgleich mit dem Display-Wert des Geräts zum
  Aufzeichnungszeitpunkt wurde in diesem Log nicht dokumentiert.

## 8. Auswirkung auf den bestehenden Code

`meter/Pce323FrameDecoder.kt` (aus M1) wurde gegen die ursprüngliche PCE-322A-Hypothese
gebaut und getestet — mit diesem Ergebnis ist diese Hypothese widerlegt. Der Decoder muss vor
Beginn von M2 durch eine Version ersetzt werden, die das oben dokumentierte 23-Byte-Format
decodiert. `meter/MeterCommand.kt` (ebenfalls M1) bildet die PCE-322A-Kommandotabelle ab, die
durch diese Aufzeichnung ebenfalls nicht bestätigt ist. Beides ist bewusst **nicht** Teil
dieses Dokuments — M0 liefert die Fakten, die Umsetzung im Code ist eine eigene Entscheidung.

## 9. Folgeaufzeichnung: Bereich, Fast/Slow, A/C isoliert getestet (2026-08-17, unbestätigt)

Reaktion auf Abschnitt 7: vier nRF-Connect-Logs vom selben Tag, in denen der Owner gezielt und
möglichst isoliert einzelne Einstellungen am Gerät umgeschaltet hat (Messbereich, Fast/Slow,
A/C), während der Notify-Strom mitlief. Ausgewertet über ein Skript, das bei jedem der drei
bislang konstanten Bytes (Header-Offset 7, Footer-Offset 19, Trailer-Offset 20) Änderungen
gegen Zeitstempel und Pegelverlauf abgleicht — nicht durch manuelles Duchsehen.

**Ergebnis: alle drei Positionen bewegen sich, jede isoliert von den anderen beiden.**

| Offset | Byte-Werte | Beleg | Verdacht |
|---|---|---|---|
| 7 (im Header) | `0x00`–`0x03` | sauberer Rundlauf 0→1→2→3→0… beim wiederholten Betätigen einer Taste, dreimal reproduziert; Pegel macht dabei unphysikalische Sprünge (Bereichswechsel-Signatur) | **Messbereich** |
| 19 (2. Footer-Byte) | `0x0F`/`0x10` | im gezielten Fast/Slow-Test mehrfacher sauberer Wechsel, jeweils mit Pegelsprung (Detektor-Zeitkonstante ändert sich); am Ende 13 s stabil auf `0x10` | **Fast/Slow** |
| 20 (1. Trailer-Byte) | `0x2C`/`0x2D` | im gezielten A/C-Test sauberer Wechsel, während Bereich und Fast/Slow unverändert blieben; am Ende 33 s stabil auf `0x2C` | **A/C-Bewertung** |

Alle anderen 16 der 19 Header-/Footer-/Trailer-Bytes (die restlichen 4 Byte des 23-Byte-Frames
sind der Messwert selbst, der erwartungsgemäß bei jedem Frame variiert) blieben über die
gesamte Aufzeichnung (~3.900 Frames aus fünf Verbindungen) absolut konstant.

**Was damit weiterhin offen ist — Byte-Position ≠ Werte-Zuordnung:**

- Welcher der vier Bereichs-Werte (`0x00`–`0x03`) welchem tatsächlichen Bereich (30–130 /
  30–80 / 50–100 / 80–130 dB) entspricht, ist aus den Logs allein nicht ablesbar — dafür
  müsste der Bildschirm des Geräts bei jedem Tastendruck mitprotokolliert werden.
- Ob `0x0F`/`0x2C` (der Ausgangszustand in **jeder** bisherigen Aufzeichnung inklusive der
  allerersten M0-Aufnahme, vor jedem gezielten Umschalten) tatsächlich Fast/A ist oder Slow/C,
  ist eine Annahme, keine Bestätigung.
- **Hold** wurde in keinem der vier Logs isoliert getestet — kein Byte zeigt ein Verhalten,
  das sich davon unterscheiden ließe.

**Tipp für den A/C-Abgleich am Gerät** (Review PR #15): Die Zuordnung von `0x2C`/`0x2D` lässt
sich physikalisch härten statt nur über das Ausgangszustand-Indiz zu erschließen. Die
A-Bewertung dämpft Frequenzen unter ca. 500 Hz deutlich, die C-Bewertung kaum. Mit einer
tieffrequenten Quelle (Brummen, tiefer Ton, Rauschen) muss der angezeigte Pegel beim Umschalten
auf C spürbar **steigen**. Steigt der Pegel genau dann, wenn das Byte auf `0x2D` wechselt, ist
`0x2D`→C bewiesen statt vermutet — unabhängig davon, ob der Ausgangszustand wirklich A war.

Der Code (`Pce323Profile.kt`, `Pce323FrameDecoder.kt`) setzte seit dieser Aufzeichnung eine
konkrete Annahme für die Werte-Zuordnung um (0x00→30–130 dB, 0x0F→Fast, 0x2C→A, jeweils naheliegendste Deutung des Ausgangszustands) und markierte sie in der App
(`MeterScreen`) ausdrücklich als unbestätigt, damit sie am realen Gerät gegengeprüft werden
kann.

## 10. Gerätetest des Owners (2026-08-20): Fast/Slow und A/C bestätigt, Messbereich korrigiert

Erster Test der App mit dem realen PCE-323 im Alltag (kein isolierter Umschalt-Log wie in
Abschnitt 9, sondern laufender Vergleich App-Anzeige gegen Geräte-Display). Ergebnis:

- **dB(A), dB(C), Fast, Slow und der reine dB-Messwert** stimmten in der Live-Anzeige exakt mit
  der Geräteanzeige überein — die Annahmen `0x0F→Fast`/`0x10→Slow` und `0x2C→A`/`0x2D→C` aus
  Abschnitt 9 sind damit **bestätigt**.
- **Messbereich war falsch:** Der Rundlauf der vier Werte existierte wie erwartet, aber die
  Zuordnung war um zwei Positionen verschoben:

  | Byte (`RANGE_BYTE_OFFSET`) | zeigte die App | tatsächlicher Gerätebereich |
  |---|---|---|
  | (vormals `RANGE_VALUE_30_130` = `0x00`) | 30–130 dB | 50–100 dB |
  | (vormals `RANGE_VALUE_30_80` = `0x01`) | 30–80 dB | 80–130 dB |
  | (vormals `RANGE_VALUE_50_100` = `0x02`) | 50–100 dB | 30–130 dB |
  | (vormals `RANGE_VALUE_80_130` = `0x03`) | 80–130 dB | 30–80 dB |

  In `Pce323Profile.kt` korrigiert: `RANGE_VALUE_50_100=0x00`, `RANGE_VALUE_80_130=0x01`,
  `RANGE_VALUE_30_130=0x02`, `RANGE_VALUE_30_80=0x03`. Der Rundlauf selbst (Byte-Positionen,
  vier Werte) war also korrekt erkannt — nur die Label-Zuordnung innerhalb des Rundlaufs war
  falsch, vermutlich weil der Ausgangszustand in der M0-Aufzeichnung (Abschnitt 9) fälschlich
  als „kleinster/größter Standardbereich" statt anhand des tatsächlich am Gerät sichtbaren
  Displays interpretiert wurde.

Mit dieser Bestätigung kann `Pce323Profile.MODE_ASSUMPTION_CONFIRMED` auf `true` gesetzt werden
— das ist eine bewusste Owner-Entscheidung (Plan §13-Charakter, einziger Schalter für
`weighting`/`timeWeighting`/`range` gemeinsam) und wird separat abgefragt, bevor sie im Code
umgesetzt wird.

Weiterhin ungetestet: **Hold** (siehe Abschnitt 9) sowie B1 (Verfälscht die Funkverbindung die
Messung? — Checkliste `docs/CHECKLISTE_GERAETETEST.md` Teil B1).
