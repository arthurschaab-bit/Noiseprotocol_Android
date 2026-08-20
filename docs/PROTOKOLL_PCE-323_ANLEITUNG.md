# Anleitung: Protokoll-Discovery am realen PCE-323 (M0)

Praktische Schritt-für-Schritt-Anleitung für
[Plan Abschnitt 3, Phase 0](IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md#3-phase-0--protokoll-discovery-verbindlicher-erster-schritt).
Diese Phase ist Handarbeit am Gerät und lässt sich nicht an eine Coding-Session delegieren —
diese Anleitung führt dich durch die vier Schritte des Plans (3.1–3.4) und sagt an jeder
Stelle, worauf es ankommt und was am Ende vorliegen muss.

**Aufwand laut Plan:** 0,5–1 Tag. **Ohne diesen Schritt ist M2 (BLE-Transport) reine
Spekulation** — er ist Voraussetzung für alle weitere Bluetooth-Arbeit.

---

## 0. Ziel und Enddeliverable

Am Ende dieser Anleitung musst du folgende Fragen aus
[Plan Abschnitt 2.3](IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md#23-offen--muss-am-realen-gerät-ermittelt-werden)
beantworten können:

| Frage | Wonach du suchst |
|---|---|
| BLE oder Bluetooth Classic SPP? | nRF Connect findet das Gerät nur, wenn es BLE ist. Arbeitshypothese im Plan: BLE, weil es eine iOS-App gibt (SPP bräuchte MFi-Zertifizierung) |
| Welche Service-/Characteristic-UUIDs? | Aus der GATT-Tabelle in nRF Connect |
| Braucht es ein CONNECT-Kommando (`0xACFF`), um den Notify-Strom zu starten? | Aus dem HCI-Snoop-Log: sendet die Hersteller-App vor den ersten Notify-Werten einen Write? |
| Frame-Rate (erwartet 2 Hz)? | Aus den Zeitstempeln der Notify-Pakete in Wireshark |
| MTU/Write-Type-Besonderheiten? | Aus dem Snoop-Log: `MTU Exchange`, Write-Pakete mit/ohne Response |
| Unterstützt das Modul Bonding? | Ob im Log ein Pairing/SMP-Handshake auftaucht |

**Konkretes Enddeliverable** (was danach in einer Coding-Session ausgewertet wird):

1. Export der vollständigen GATT-Tabelle aus nRF Connect (Screenshot oder Log-Export reicht)
2. Ein `.pklg`/`.log`-HCI-Snoop-Log mit mindestens einigen Minuten Verkehr der Hersteller-App
3. Mindestens **200 aufgezeichnete Roh-Frames** als Byte-Dump (Plan-Vorgabe, siehe Schritt 4)
4. Kurze Notizen zu den sechs Fragen aus der Tabelle oben

Das reicht, damit eine Folge-Session daraus `Pce323Profile.kt` (UUIDs, Kommandos) und
`docs/PROTOKOLL_PCE-323.md` (Dokumentation inkl. Testvektoren) schreiben kann.

---

## Voraussetzungen

- **PCE-323**, eingeschaltet, mit ausreichend Akku/am Netzteil (Auto-Power-Off während der
  Untersuchung wenn möglich am Gerät deaktivieren — sonst schläft es dir mitten in der
  Aufzeichnung weg)
- **Ein Android-Handy** (kann das Testgerät sein, auf dem auch Lärmprotokoll läuft) mit
  aktivierten Entwickleroptionen
- **[nRF Connect for Mobile](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp)**
  (Nordic Semiconductor) auf diesem Handy installiert
- **Die PCE-323-Herstellerapp** `com.pceinstruments.pce323` auf demselben oder einem zweiten
  Android-Handy installiert (Play Store)
- **Ein PC mit [Wireshark](https://www.wireshark.org/download.html)** zur Auswertung des
  Snoop-Logs
- **USB-Kabel + adb** (Android SDK Platform-Tools) zum Abholen des Logs vom Handy

---

## Schritt 1 (Plan 3.1): GATT-Tabelle dumpen

1. Bluetooth am Handy aktivieren, PCE-323 einschalten.
2. nRF Connect öffnen, **Scanner**-Tab, Suche starten.
3. Das Gerät in der Liste finden. Der Name ist nicht garantiert „PCE-323" — bei OEM-Geräten
   dieser Preisklasse taucht oft ein generischer Modulname auf (z. B. etwas in Richtung
   `BT-*`, `HC-*` oder ein völlig kryptischer Name). Falls mehrere unbekannte Geräte in
   Reichweite sind: PCE-323 kurz aus-/einschalten und schauen, welcher Eintrag exakt dabei
   erscheint/verschwindet.
4. **Connect** antippen.
5. Im Geräte-Bildschirm siehst du die GATT-Tabelle: Services, darunter Characteristics mit
   ihren Properties (`READ`, `WRITE`, `WRITE NO RESPONSE`, `NOTIFY`, `INDICATE`).
   - Achte besonders auf Services mit UUIDs wie `0000FFE0-…`, `0000FFF0-…`, oder
     `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART) — das sind laut Plan die
     wahrscheinlichsten Kandidaten für diese Art von OEM-BLE-Modul, müssen es aber nicht sein.
   - Notiere **jede** Service- und Characteristic-UUID, auch wenn du nicht sofort weißt,
     wofür sie ist.
6. Für **jede** Characteristic mit `NOTIFY`- oder `INDICATE`-Property: das Download-/Mehrfachpfeil-Icon
   antippen, um Notifications zu abonnieren. Das schreibt automatisch den CCCD-Descriptor
   (`0x2902`) — das ist der Schritt, der bei einer eigenen Implementierung später nachgebaut
   werden muss.
7. Beobachten, ob nach dem Abonnieren von selbst Werte hereinkommen, oder ob nichts passiert.
   Falls nichts passiert: Prüfen, ob das PCE-323 sich gerade im Messmodus befindet (Display
   an, Pegel wird angezeigt) — bei manchen Geräten dieser Klasse muss man zusätzlich ein
   Kommando senden (siehe Schritt 2), damit der Notify-Strom überhaupt losgeht.
8. **Exportieren**: nRF Connect hat eine Log-/Export-Funktion (Menü oben rechts, „Log" oder
   „Export"). Alternativ reicht ein sauberer Screenshot pro Service/Characteristic-Ansicht,
   solange UUID und Properties lesbar sind.

**Ergebnis von Schritt 1:** vollständige GATT-Tabelle, dokumentiert.

---

## Schritt 2 (Plan 3.2): Referenzverkehr mitschneiden

Jetzt wird der Datenverkehr der **echten Hersteller-App** aufgezeichnet — das ist die
verlässlichste Quelle, weil diese App garantiert weiß, wie man mit dem Gerät spricht.

1. **Entwickleroptionen aktivieren** (falls noch nicht geschehen): Einstellungen → Über das
   Telefon → 7× auf die Build-Nummer tippen.
2. Einstellungen → Entwickleroptionen → **„Bluetooth-HCI-Snoop-Log aktivieren"** einschalten.
3. Bluetooth am Handy kurz aus- und wieder einschalten (sauberer Start der Aufzeichnung).
4. Die **Hersteller-App `com.pceinstruments.pce323`** öffnen und mit dem PCE-323 verbinden.
5. Mindestens **2–3 Minuten** Live-Werte beobachten, dabei:
   - Falls am Gerät eine Taste für A/C-Bewertung existiert: einmal umschalten, damit im Log
     sichtbar wird, wie sich `buf[3] bit0` ändert.
   - Falls möglich: Fast/Slow umschalten, Bereich wechseln, Hold aktivieren — jede
     Zustandsänderung, die im Log auftaucht, hilft später beim Verifizieren der Flag-Bits.
   - Einen Moment bewusst laut/leise Umgebung erzeugen (z. B. kurz klatschen), damit
     unterschiedliche Pegelwerte im Log liegen — praktisch für die Plausibilitätsprüfung
     in Schritt 3.
6. Die Verbindung in der App normal trennen (falls möglich) — das erzeugt zusätzlich ein
   Disconnect-Ereignis im Log, das später bei der Reconnect-Logik (M3) hilft.
7. **Log abholen.** Zwei Wege, je nachdem was auf dem Handy funktioniert:
   - `adb bugreport bugreport.zip` — enthält das Snoop-Log irgendwo unter
     `FS/data/misc/bluetooth/logs/btsnoop_hci.log` im Zip.
   - Direkt: `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log` (auf manchen Android-Versionen
     braucht das Root; falls das fehlschlägt, ist `adb bugreport` der zuverlässigere Weg).

**Ergebnis von Schritt 2:** eine `btsnoop_hci.log`-Datei mit dem Referenzverkehr.

---

## Schritt 3 (Plan 3.3): Hypothese verifizieren

1. Log in Wireshark öffnen (`File → Open`).
2. Filter setzen: `btatt` (zeigt nur ATT-Protokoll-Pakete — genau die Ebene, auf der
   GATT read/write/notify läuft).
3. **CCCD-Write finden**: ein `Write Request` auf Handle mit UUID `0x2902` markiert den
   Moment, in dem die App Notifications aktiviert. Alles danach sind die interessanten
   Notify-Pakete.
4. **Notify-Pakete inspizieren** (`Handle Value Notification`): im Byte-Bereich des Pakets
   nachsehen:
   - Beginnt der Payload mit `0x7F`?
   - Ist er genau 6 Byte lang?
   - Endet er mit `0x00`?
   - Ergeben Byte 1+2 als 16-Bit big-endian, geteilt durch 10, einen plausiblen dB-Wert
     (Zimmerlautstärke liegt grob bei 40–60 dB, ein Klatschen sollte einen deutlichen
     Ausschlag zeigen)?
   - **Falls die Payload NICHT sauber bei 6 Byte liegt** (z. B. auf mehrere ATT-Pakete
     verteilt): kein Problem für den Code — der `Pce323FrameDecoder` (bereits in M1 gebaut)
     arbeitet bewusst byteweise über einen Ringpuffer genau für diesen Fall. Wichtig ist nur,
     das hier zu notieren, damit die Annahme beim Verkabeln mit dem echten `BleMeterTransport`
     in M2 stimmt.
5. **Write Requests vor dem ersten Notify-Wert prüfen**: sendet die App ein Kommando, bevor
   Werte fließen? Falls ja, mit `0xACFF` (CONNECT laut Plan-Tabelle) vergleichen — Byte-Wert
   im Write-Payload nachsehen.
6. **Frame-Rate messen**: in Wireshark die Zeitspalte zwischen aufeinanderfolgenden
   Notify-Paketen ablesen (`View → Time Display Format → Seconds Since Previous Displayed
   Packet` ist hier hilfreich). Erwartet werden ca. 500 ms (2 Hz).
7. **MTU/Write-Type prüfen**: nach `MTU Exchange`-Paketen suchen (ausgehandelte MTU-Größe),
   und ob Write-Kommandos als `Write Request` (mit Response) oder `Write Command` (ohne
   Response) gesendet werden.
8. **Bonding prüfen**: nach SMP-Paketen (`btsmp`-Filter) oder einem Pairing-Dialog auf dem
   Handy während des Verbindungsaufbaus suchen. Kein Pairing sichtbar → Modul unterstützt
   vermutlich kein Bonding (relevant für Plan Abschnitt 6, Sicherheit).

**Ergebnis von Schritt 3:** die sechs Fragen aus Abschnitt 0 oben sind beantwortet, mit
Belegen aus dem Log.

---

## Schritt 4 (Plan 3.4): Ergebnis sichern

Dieser Schritt erzeugt die Rohdaten, aus denen eine Coding-Session
`Pce323Profile.kt` und die Testvektoren für `Pce323FrameDecoderTest` ableitet — **du musst
hier nichts programmieren**, nur die Rohdaten sauber exportieren.

1. **Rohbytes der Notify-Pakete extrahieren.** In Wireshark: die Notify-Pakete markieren
   (rechtsklick auf ein Paket im `btatt`-gefilterten Log → `Follow → Follow ATT Stream`, oder
   pro Paket `Copy → Bytes → Hex Stream`). Ziel: **mindestens 200 Frames** als reine Bytes,
   am einfachsten als Textdatei mit einem Hex-Frame pro Zeile, z. B.:
   ```
   7f025c000000
   7f025e000000
   7f0260000000
   ```
   Falls du mit `tshark` (Wireshark-Kommandozeile) vertraut bist, geht das auch automatisiert;
   ein manueller Export aus der GUI reicht aber völlig.
2. Diese Rohdaten-Datei sowie das komplette `.log`-Snoop-File und den GATT-Export aus Schritt 1
   **im Repo ablegen**, z. B. unter `docs/discovery/` (neuer Ordner) oder an mich (die nächste
   Coding-Session) weitergeben — beides funktioniert, wichtig ist nur, dass die Rohdaten nicht
   nur lokal auf deinem Rechner liegen, wenn eine Session damit weiterarbeiten soll.
3. Kurze Notizen zu den sechs Fragen aus Abschnitt 0 dazuschreiben (reicht als Stichpunkte,
   z. B. in einer `NOTIZEN.md` neben den Rohdaten).

---

## Was danach passiert

Sobald GATT-Tabelle, Snoop-Log und die ≥200 Roh-Frames vorliegen, kann eine Coding-Session
daraus:

- `meter/ble/Pce323Profile.kt` schreiben (UUIDs, Kommandos, Framegrößen als einzige Quelle
  der Wahrheit, siehe Plan Abschnitt 4.2)
- `docs/PROTOKOLL_PCE-323.md` mit den verifizierten Angaben und Rohdaten-Beispielen anlegen
- die Roh-Frames als `.bin`-Fixture in `Pce323FrameDecoderTest` gegen den bereits gebauten
  Decoder aus M1 laufen lassen — das ist der erste echte Beweis, dass Annahme und Realität
  übereinstimmen
- M2 (BLE-Basis: Scan, Verbindung, `GattQueue`, `BleMeterTransport`) beginnen

---

## Troubleshooting

| Problem | Ansatz |
|---|---|
| Gerät taucht im nRF-Connect-Scan nicht auf | PCE-323 ist evtl. Bluetooth Classic SPP statt BLE (Plan-Risikotabelle, Abschnitt 13) — dann bräuchte es eine andere Discovery-Methode (`BluetoothDevice.ACTION_FOUND` statt BLE-Scan). Erst mit der Hersteller-App verbinden und in deren Verbindungsdialog schauen, welche Android-API sie nutzt |
| Verbunden, aber keine Services sichtbar | Ein paar Sekunden warten (Service Discovery kann dauern), sonst Verbindung neu aufbauen |
| Notify abonniert, aber keine Werte | Prüfen, ob das Gerät ein CONNECT-Kommando erwartet (Schritt 3.5) — dazu hilft, den Traffic der Hersteller-App genau zu Beginn der Verbindung anzusehen |
| Snoop-Log ist leer oder sehr klein | Prüfen, ob die Aufzeichnung wirklich aktiv war (Entwickleroptionen erneut öffnen, Haken prüfen) und ob genug Zeit mit aktiver Verbindung verstrichen ist |
| PCE-323 schaltet sich während der Untersuchung ab | Auto-Power-Off am Gerät deaktivieren (Menü am PCE-323) oder am Netzteil betreiben |
