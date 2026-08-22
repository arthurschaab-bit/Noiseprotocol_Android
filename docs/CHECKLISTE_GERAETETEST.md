# Checkliste: Gerätetest am PCE-323

Deckt in **einem** Durchgang ab, was seit M2 und M3 ungeprüft aufgelaufen ist. Rund 45 Minuten,
ohne den 24-Stunden-Dauerlauf am Ende.

Warum gebündelt: Der gesamte BLE-Transport (M2) und die gesamte Robustheitslogik (M3) sind
bislang nur gegen Fakes und aufgezeichnete Frames geprüft. Zusätzlich hängen zwei inhaltliche
Fragen offen, die nur am Gerät zu beantworten sind.

> Bitte notieren, was tatsächlich passiert — auch und gerade, wenn es abweicht. Ein „hat nicht
> funktioniert" mit Beobachtung ist wertvoller als ein Haken.

---

## Vorbereitung

- [ ] **Datenbank sichern**, bevor eine neue Version installiert wird:
      ```bat
      adb exec-out run-as com.example.lrmprotokoll cat databases/noise_database > backup.db
      ```
- [ ] Aktuellen `main`-Stand bauen und installieren (`git pull`, dann Run in Android Studio)
- [ ] PCE-323 mit Netzteil betreiben, **Auto-Power-Off am Gerät deaktivieren** — sonst schaltet
      es sich mitten im Test ab und verfälscht die Robustheitsszenarien
- [ ] Ruhigen Raum wählen, in dem der Pegel einigermaßen stabil ist
- [ ] Für Teil B: Tongenerator bereitlegen (App oder Webseite), gebraucht werden **63 Hz** und
      **1000 Hz**
- [ ] Logcat mitlaufen lassen: `adb logcat -s BleMeterTransport ConnectionSupervisor AndroidRuntime`

Die App zeigt den Verbindungszustand als Text an — in der Notification und im Messgerät-Screen.
Die möglichen Anzeigen sind: *Nicht verbunden · Suche… · Verbinde… · Verbunden · Instabil ·
Verbinde erneut… · Getrennt · Fehlgeschlagen*.

---

## Teil A — Grundfunktion (M2)

| # | Schritt | Erwartung | Ergebnis |
|---|---------|-----------|----------|
| A1 | Messgerät-Screen öffnen, Scan starten | PCE-323 erscheint mit Name und Signalstärke | |
| A2 | Gerät auswählen | Zustand geht über *Verbinde…* auf **Verbunden** | |
| A3 | Pegel ablesen | App-Wert stimmt mit dem Gerätedisplay überein (±0,1 dB) | |
| A4 | 2 Minuten beobachten | Wert aktualisiert sich flüssig, rund zweimal pro Sekunde, keine Aussetzer | |
| A5 | App schließen (nicht beenden), Notification prüfen | Zustand steht weiterhin auf **Verbunden** | |

**Wenn A3 abweicht:** Zahlenwerte beider Anzeigen notieren. Eine konstante Differenz deutet auf
einen Dekodierfehler, eine schwankende auf ein Timing-Problem.

---

## Teil B — Die zwei inhaltlichen Fragen

### B1 — Verfälscht die Funkverbindung die Messung?

Bei einem vergleichbar aufgebauten Fremdgerät (Uni-T UT353BT) sind rund **15 dB Abweichung**
dokumentiert, weil die BLE-Sendetätigkeit das Mikrofon elektrisch stört. Ob das PCE-323 das
zeigt, weiß niemand — und für ein Lärmprotokoll wäre es der unangenehmste Fehler, weil die Werte
plausibel aussehen und trotzdem falsch sind.

- [ ] **Ohne Bluetooth:** App getrennt, Bluetooth am Telefon aus. Pegel am **Gerätedisplay**
      30 Sekunden beobachten, Minimum und Maximum notieren: ______ bis ______ dB
- [ ] **Mit Bluetooth:** Verbinden, gleicher Raum, gleiche Bedingungen, Telefon direkt neben dem
      Messgerät. Erneut 30 Sekunden am **Gerätedisplay** ablesen: ______ bis ______ dB
- [ ] **Abstand prüfen:** Telefon auf 2 Meter entfernen, nochmals ablesen: ______ bis ______ dB

**Bewertung:** Unterschiede unter etwa 1 dB sind normale Schwankung. Alles darüber — vor allem
wenn es mit dem Abstand kleiner wird — ist ein echter Befund und muss dokumentiert werden.

> **Update 2026-08-20:** Ein erster Alltagstest des Owners hat dB(A)/dB(C) und Fast/Slow bereits
> gegen das Geräte-Display bestätigt (siehe `docs/PROTOKOLL_PCE-323.md` Abschnitt 10) — die
> formale B2-Messung mit 63 Hz/1000 Hz unten ist dadurch nicht mehr zwingend nötig, schadet aber
> nicht als zusätzliche Absicherung. Der Messbereich war dabei falsch zugeordnet und wurde in
> `Pce323Profile.kt` korrigiert.

### B2 — Stimmt die A/C-Annahme?

Die App interpretiert Byte `0x2C` als A-Bewertung und `0x2D` als C. Das ist bislang **nur eine
Annahme**, gestützt darauf, dass `0x2C` in allen Aufzeichnungen der Ausgangszustand war. Im
Messgerät-Screen steht deshalb „Annahme, unbestätigt".

Physikalisch lässt sich das beweisen: Die A-Bewertung dämpft tiefe Frequenzen stark, die
C-Bewertung kaum. Bei 63 Hz beträgt der Unterschied rund **25 dB**. Bei 1000 Hz sind beide
Kurven per Definition identisch — das ist die Gegenprobe.

**Messung mit 63 Hz:**

- [ ] Dauerton 63 Hz nahe am Messgerät abspielen, Lautstärke so, dass der Pegel deutlich über
      dem Grundgeräusch liegt
- [ ] Zustand notieren, den die App anzeigt: Bewertung ______ , Pegel ______ dB
- [ ] Am Gerät auf die andere Bewertung umschalten
- [ ] Erneut notieren: Bewertung ______ , Pegel ______ dB

**Gegenprobe mit 1000 Hz:**

- [ ] Dauerton 1000 Hz, gleiches Vorgehen. Pegel vorher ______ dB, nachher ______ dB

**Bewertung:**

| Beobachtung | Schluss |
|---|---|
| Bei 63 Hz steigt der Pegel deutlich (10–25 dB), wenn die App auf **C** wechselt, und bei 1000 Hz ändert sich fast nichts | Annahme **bestätigt** |
| Bei 63 Hz steigt der Pegel, wenn die App auf **A** wechselt | Zuordnung ist **vertauscht** — `0x2C` ist C |
| Bei 1000 Hz ändert sich der Pegel ebenfalls stark | Das Byte kodiert etwas anderes als die Frequenzbewertung |

Das Ergebnis entscheidet, ob `modeAssumptionConfirmed` auf `true` gesetzt werden darf — und
damit, ob M4 die Frequenzbewertung überhaupt mitspeichern kann.

---

## Teil C — Robustheit (M3)

Zwischen den Szenarien jeweils warten, bis die App wieder **Verbunden** meldet.

| # | Szenario | Erwartung | Ergebnis |
|---|----------|-----------|----------|
| C1 | Mit dem Telefon aus der Funkreichweite gehen (anderer Raum, Tür zu) | Zustand wechselt auf *Verbinde erneut…*, kein Absturz | |
| C2 | Zurückkommen | Verbindung stellt sich **von allein** wieder her, ohne Zutun | |
| C3 | Messgerät ausschalten und aus lassen | Nach rund **zwei Minuten** steht **Fehlgeschlagen** | |
| C4 | Messgerät wieder einschalten | *(App versucht nach FAILED nicht mehr von allein — erneutes Verbinden über den Screen prüfen)* | |
| C5 | Bluetooth am Telefon aus | Zustand *Getrennt*, **keine** hektischen Wiederholversuche im Logcat | |
| C6 | Bluetooth wieder an | Sofortiger Verbindungsversuch, ohne Wartezeit | |
| C7 | App-Prozess killen: `adb shell am kill com.example.lrmprotokoll` | Dienst startet neu, Verbindung kommt zurück | |
| C8 | Telefon neu starten | Überwachung nimmt **automatisch** wieder auf, ohne die App zu öffnen | |

**Bei C3 besonders auf die Zeit achten:** Erwartet sind acht Versuche über die Backoff-Folge
(1, 2, 4, 8, 16, 30, 60, 60 Sekunden). Deutlich schneller oder deutlich langsamer ist ein Befund.

**Bei C5 im Logcat prüfen**, dass wirklich pausiert wird — laufende Verbindungsversuche bei
ausgeschaltetem Bluetooth wären genau der Fehler, den die Adapter-Beobachtung verhindern soll.

---

## Teil D — Dauerlauf

- [ ] Über Nacht verbunden laufen lassen, Telefon am Ladegerät
- [ ] Am Morgen prüfen: Zustand immer noch **Verbunden**? Notification noch da?
- [ ] Logcat auf `status 133` durchsuchen — das wäre die im Plan beschriebene Kaskade
      geleakter GATT-Slots
- [ ] Speicherverbrauch der App in den Entwickleroptionen ansehen

---

## Teil E — Befunde aus dem Praxiseinsatz & Härtung (Google Pixel & Xiaomi Pad 6)

| Bereich / Test | Beobachtung im Feld | Maßnahme & Härtung | Status |
|---|---|---|---|
| **BLE-Kopplung PCE-323** | Erfolgreich verbunden, Messwertübertragung läuft stabil. | Sortierung der BLE-Liste stabilisiert (`sortiereGefundeneGeraete`), Entkopplung Audioaufnahme von Dauermessung (`PR #53`). | ✅ Gelöst |
| **Frequenzgang dB(A) / dB(C)** | Umschaltung und Pegel stimmen mit Display überein. | Bestätigt. | ✅ Gelöst |
| **Google Drive Login & Export** | Login erfolgreich; Daten liegen in `F:\Meine Ablage\Lärmprotokoll\` (`laermprotokoll_*.csv`, Support-Bundles). Bei Wiederverbindung kam erneuter Account-Chooser. | Kontopersistenz via `googleAccountEmail` + stiller Token-Flow + `DriveStatusCard` mit Sofort-Sync in UI (`PR #54`). | ✅ Gelöst |
| **Tablet-Alarmierung (Xiaomi Pad 6)** | Auf MiPad 6 kein Alarm, keine Notification, keine Vibration. | **Ursachen:** 1. Tablets haben keinen Vibrationsmotor (`hasVibrator == false`), 2. Lautlos-Modus unterdrückte Standardtöne, 3. HyperOS Berechtigungs- und Akku-Restriktionen.<br>**Lösung:** Akustischer Alarmton forciert mit `USAGE_ALARM` via `LocalNotificationAlertChannel`, NotificationChannel v3 mit Priorität `MAX`, `OemDeviceHelperCard` mit 1-Klick-Intents (`PR #55`). | ✅ Gelöst |

---

## Was zurückgemeldet werden sollte

1. Die ausgefüllte Tabelle, auch mit den Zeilen, die nicht wie erwartet liefen
2. Die vier Zahlenpaare aus B1 und B2 — daraus folgt direkt, ob `modeAssumptionConfirmed`
   gesetzt werden darf
3. Bei Abstürzen: den Logcat-Auszug ab `FATAL EXCEPTION`
4. Bei auffälligem Verbindungsverhalten: die Logcat-Zeilen von `ConnectionSupervisor`

Aus B2 ergibt sich unmittelbar die nächste Codeänderung, aus B1 möglicherweise eine Anpassung
der Abtastrate.
