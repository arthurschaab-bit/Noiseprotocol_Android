# Kalibrier- und Aufbauphase am Messstart

Stand: 12.11.2026 · Auftrag des Owners · Umgesetzt in PR #290

## Was sich geändert hat

**Ablauf bei manuellem Start** (Start-Knopf im Cockpit; automatische Starts nach Absturz,
Neustart oder durch den Wächter überspringen alles und messen sofort):

1. **Kalibrierung** (nur wenn ein Messgerät gekoppelt ist). Der Dialog zeigt den Live-Pegel des
   Messgeräts. Der Kalibrator (PCE-SC 43, 94 dB) wird aufgesetzt. Die Werte landen **nicht** in
   der Messreihe, nicht in den Tageswerten, lösen keine Ereignisse oder Aufnahmen aus
   (`MessphasenSteuerung.erfassungPausiert`), sondern in `kalibrier_messwerte`.
   Erkannt werden die Abweichung vor und nach der Justierung (Pegel minus 94,0 dB) und das Ende
   (Pegel fällt). Der Nutzer bestätigt oder korrigiert die Werte.
2. **Aufbau.** Konfiguration und Fotos (Messaufbau, Kalibrierung). Das Gerät wird auf den Balkon
   gebracht. Erst mit **„Messung starten“** zählen die Werte.
   Ist für heute beides schon erfasst, entfällt der Dialog („Aufbau von heute übernommen“).
3. **Messung läuft.**

**Popups sonst:** Foto- und Konfigurations-Popup erscheinen nur noch, wenn für den **Tag** noch
nichts erfasst ist (vorher: je Messvorgang, deshalb kam es nach dem Wechsel Mikrofon → Messgerät
ein zweites Mal). Bei Messungen über Mitternacht werden die letzten Angaben **still** für den
neuen Tag übernommen (Kalibrierung, Wetter und Hinweis bleiben leer).
Überflow-Menü → „Konfiguration & Fotos ändern“ öffnet die Angaben von heute, ohne die Messung
zu unterbrechen.

**Sicherheitsnetz:** Beide Phasen enden von selbst (Kalibrierung nach 30 min, Aufbau nach
60 min) und die Messung läuft dann weiter. Ein Prozessende setzt die Phase auf „keine“ zurück.

**Bericht:** Das Ergebnis erscheint als Vorschlag im Feld „Kalibrierung“ der Gesamtbericht-
Stammdaten (z. B. `Kalibrator PCE-SC 43, 94,0 dB / 1 kHz, 13.11.2026 06:55 Uhr: Abweichung vor
Justierung +0,3 dB, danach +0,0 dB`).

**Datenbank:** Schema 27, neue Tabellen `kalibrierphasen` und `kalibrier_messwerte`,
`MIGRATION_26_27` (Test: `AppDatabaseV27MigrationTest`).

## Die Erkennung ist eine Schätzung

`KalibrierErkennung` stützt sich nur auf die Beschreibung des Owners („einige Sekunden stabil
zwischen 92 und 96 dB, danach Sprünge zwischen 94, 105 und einem weiteren Wert“) und das
Datenblatt des PCE-SC 43 (94/104/114 dB, ±0,4 dB, Stabilisierung 10 s). Nichts davon ist an
echten Verläufen geprüft.

| Parameter | Wert | Bedeutung |
|---|---|---|
| Plateau | 92–96 dB, Spannweite ≤ 1,0 dB über 6 s | stabil am Kalibrator |
| Vorher | erstes Plateau | Abweichung = Median − 94,0 |
| Nachher | zuletzt gesehenes Plateau | nach der Justierung |
| Ende | < 85 dB für 4 s, nachdem ein Plateau erkannt wurde | Kalibrator abgenommen |
| Lücke | > 3 s ohne Wert | Fenster wird verworfen |

## Test am Gerät (morgen)

- [ ] Start-Knopf: Der Kalibrier-Dialog erscheint, die Messung zählt noch nicht.
- [ ] „Warte auf das Messgerät …“ wechselt auf den Live-Pegel, sobald BLE verbunden ist.
- [ ] Kalibrator aufsetzen: Wird nach ca. 6–10 s eine Abweichung vorher angezeigt?
- [ ] Justieren: Ändert sich „nachher“?
- [ ] Kalibrator auf 104/114 dB schalten: Bleiben vorher/nachher unverändert?
- [ ] Kalibrator abnehmen: Erscheint „Kalibrierung beendet?“ nach ca. 4 s?
- [ ] Danach Aufbau: Konfiguration und Fotos, „Messung starten“. Erst jetzt erscheinen Werte im
      Pegelverlauf, und in den Tageswerten fehlt der Kalibrierton.
- [ ] Zweiter Start am selben Tag: Kein Aufbau-Dialog, Hinweis „Aufbau von heute übernommen“.
- [ ] Kein zweites Foto-Popup nach dem Wechsel Mikrofon → Messgerät.
- [ ] Gesamtbericht-Stammdaten: Das Feld „Kalibrierung“ ist vorbefüllt.

## Daten für die Feinabstimmung

Die Kalibrierwerte stehen in `kalibrier_messwerte` (Zeitstempel, dB) und kommen mit der
Datenbank-Sicherung nach Drive. Außerdem schreiben alle Schritte Breadcrumbs der Kategorie
`Messphase` ins Support-Bundle (Start, Ende, Grund, Abweichungen, Korrektur).
Hilfreich: ein Bundle direkt nach der Kalibrierung und die Messwerte der Phase als CSV, dazu
Uhrzeit und die Stufen, die du am Kalibrator durchgeschaltet hast.

## Bekannte Grenzen

- Ohne offene Messsitzung (Mikrofon aus, Messgerät noch nicht verbunden) sind „Konfiguration“
  und „Fotos“ im Aufbau-Dialog gesperrt. Sie folgen dann nach dem Start als Popup.
- Kein Gerätetest, kein Emulator: Oberfläche und Erkennung sind nur per Robolectric-Tests und
  Unit-Tests geprüft.
- Datenverfügbarkeit wird aus Ausfallbändern (Verbindungsausfällen) berechnet; die Phase ist
  kein Ausfall. Aufzeichnungslücken werden aus Sessions abgeleitet (`aufzeichnungsLuecken`):
  Läuft das Mikrofon, deckt dessen Session die Phase ab. Misst nur das Messgerät, gibt es
  während der Phase keine Session, und sie erscheint als Lücke. Das ist nicht am Gerät
  geprüft.
