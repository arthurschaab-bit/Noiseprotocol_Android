# Prompt: Diagnose — Stromversorgung und Akkustand protokollieren

**Priorität 3, Welle 1.** Teil von Befund G aus
[`BEFUNDE_BUNDLES_2026-10-07.md`](BEFUNDE_BUNDLES_2026-10-07.md).

**Owner-Freigabe vom 07.10.2026:** „Ja, mach die Vorschläge.“ Dazu gehört der Vorschlag „Ladezustand
ins Diagnoseprotokoll schreiben, damit der nächste Ausfall beweisbar ist“.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

Lies `AGENTS.md` vollständig, sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann
  `git switch -c fix/ladezustand-protokollieren origin/main`. Nie auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch. Beispiel:
  `feat(diagnose): Wechsel der Stromversorgung ins Diagnoseprotokoll schreiben`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`
  (keine neuen Befunde in den geänderten Dateien). Nur bei Grün committen und pushen.
- **Nur handgeschriebene Fakes.** Kein Mockito, kein MockK. **Keine Schemaänderung.**
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.
- Parallel laufen `PROMPT_FIX_AUFZEICHNUNG_FORTSETZEN.md` und `PROMPT_FIX_AUFZEICHNUNG_WAECHTER.md`.
  Halte deine Änderung an `AudioRecordingService.kt` auf das Registrieren und Abmelden eines
  Empfängers beschränkt, damit sich die PRs nicht in die Quere kommen.

---

## 1 · Befund

- Am 06.10.2026 um 12:41 hat das System die App auf dem P30 beendet.
- Der Owner vermutet als Auslöser die USB-Powerbank: Sie schaltet bei vollem Akku ab, das Handy
  läuft dann auf Akku, und EMUI beendet „energieintensive“ Hintergrund-Apps.
- **Das lässt sich nicht prüfen**, weil die App die Stromversorgung nirgends festhält.
  `SupportBundleExporter.kt:384` schreibt nur `batteryPercent` in `runtime.json`, und das nur
  einmal zum Zeitpunkt des Bundles.

---

## 2 · Auftrag

### Schritt 1 — Reine Logik

Neue Datei, z. B. `diagnose/Stromzustand.kt`, ohne Android-Abhängigkeit:

- `data class Stromzustand(val quelle: Quelle, val status: Status, val prozent: Int?)`
  - `Quelle`: `NETZTEIL`, `USB`, `KABELLOS`, `KEINE`
  - `Status`: `LAEDT`, `VOLL`, `ENTLAEDT`, `UNBEKANNT`
- `fun naechsterEintrag(vorher: Stromzustand?, jetzt: Stromzustand): String?` liefert den
  Protokolltext oder `null`. Einen Eintrag gibt es:
  - immer, wenn sich `quelle` oder `status` ändert, mit Prozentwert. Beispiel:
    „Stromversorgung: USB → keine, Akku 100 %, entlädt“;
  - beim Entladen zusätzlich bei jedem Schritt über eine 10-%-Grenze nach unten;
  - sonst nicht. `ACTION_BATTERY_CHANGED` kommt sehr oft und darf das Protokoll nicht fluten.
- Präfix aller Texte: `Stromversorgung:`. Der spätere Auftrag
  `PROMPT_FIX_DIAGNOSEFENSTER_LEBENSZYKLUS.md` filtert danach.

### Schritt 2 — Empfänger im Dienst

- `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED` und das Sticky-Broadcast
  `ACTION_BATTERY_CHANGED` erreichen seit Android 8 **keine im Manifest registrierten Empfänger**.
  Registriere den Empfänger deshalb dynamisch in `AudioRecordingService`: beim Start der
  Überwachung anmelden, in `onDestroy()` abmelden. Verwende
  `ContextCompat.registerReceiver(…, ContextCompat.RECEIVER_NOT_EXPORTED)`, das ist auch für
  Android 14+ korrekt.
- Übersetze das Intent in einen `Stromzustand`:
  - `BatteryManager.EXTRA_PLUGGED` → Quelle;
  - `EXTRA_STATUS` → Status;
  - `EXTRA_LEVEL` / `EXTRA_SCALE` → Prozent.
- Direkt nach dem Anmelden einen Ausgangseintrag schreiben, z. B. „Stromversorgung beim Start:
  USB, Akku 92 %, lädt“. Der erste Wert kommt über das Sticky-Broadcast sofort.
- Schreibe über den Weg, der in **`events.jsonl`** landet. Prüfe, welcher das ist: Der
  `DiagnosticLogger` schreibt nur bei aktivem Diagnose-Logging. Breadcrumbs gehen in die Ringdatei
  und über Senken eventuell ebenfalls in Room. Die Ringdatei wird an einem Messtag von WAV-Breadcrumbs
  nach knapp 2 h überschrieben (Befund I). Wähle den Weg mit dem längeren Fenster und begründe die
  Wahl im PR.

### Schritt 3 — Support-Bundle

- In `runtime.json` neben `batteryPercent` ergänzen: `stromquelle` und `akkuStatus`, gelesen über
  `registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))`.
- Prüfe, ob ein Test die Feldliste von `runtime.json` festschreibt, und passe ihn an.

### Nicht Teil dieses Auftrags

- Kein Eingreifen bei Stromwechsel, also kein Alarm und kein Neustart. Nur protokollieren.
- Keine Protokollierung ohne laufenden Dienst. Ohne Aufzeichnung gibt es nichts zu beweisen.

---

## 3 · Tests (JVM/Robolectric, handgeschriebene Fakes)

1. `naechsterEintrag`, als Tabelle:
   - erster Aufruf (`vorher = null`) → Eintrag;
   - USB → keine → Eintrag;
   - gleicher Zustand → `null`;
   - Entladen 95 → 91 % → `null`;
   - 91 → 89 % → Eintrag;
   - Laden 89 → 95 % → `null`.
2. Robolectric: Dienst bzw. Empfänger mit Fake-Reporter. Zwei Broadcasts (`ACTION_BATTERY_CHANGED`
   mit `EXTRA_PLUGGED = USB`, danach mit `0`) ergeben genau zwei Einträge mit dem richtigen Text.
3. Nach `onDestroy()` erzeugt ein Broadcast keinen Eintrag mehr (Empfänger abgemeldet).
4. `runtime.json` enthält `stromquelle` und `akkuStatus`.

---

## 4 · Akzeptanzkriterien

- [ ] Jeder Wechsel der Stromversorgung während einer Aufzeichnung steht mit Uhrzeit und Akkustand
      im Diagnoseprotokoll (Test 2).
- [ ] Kein Fluten: höchstens ein Eintrag je Zustandswechsel und je 10 % Entladung (Test 1).
- [ ] Der Empfänger wird sauber abgemeldet (Test 3).
- [ ] `runtime.json` enthält Quelle und Status (Test 4).
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## 5 · Gerätecheck (macht der Owner nach dem Merge, P30)

1. Aufzeichnung an der Powerbank starten. Im Diagnose-Screen steht ein Ausgangseintrag mit „USB“.
2. Kabel ziehen und wieder einstecken. Es erscheinen zwei Einträge.
3. Über Nacht laufen lassen. Im nächsten Bundle ist zu sehen, ob und wann die Powerbank abschaltet
   („Stromversorgung: USB → keine, Akku 100 %“).

Trag diese Checks als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein, die
Ergebnis-Spalte bleibt leer.
